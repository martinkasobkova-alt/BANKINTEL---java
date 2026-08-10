package cz.bankintel.explore;

import cz.bankintel.explore.ExploreDtos.ExploreSummarizeJob;
import cz.bankintel.explore.ExploreDtos.ExploreSummarizeRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExploreInstantThenDetailService {

    private final ExploreSummarizeJobStore jobStore;
    private final ExploreSummarizeFetchService summarizeFetchService;
    private final ExploreSectionedSynthesisService sectionedSynthesisService;
    private final ExploreIndicatorRelationshipService relationshipService;

    public Map<String, Object> start(ExploreSummarizeRequest request, String summarizeMode) {
        String detailJobId = "explore-detail-" + UUID.randomUUID().toString().replace("-", "");
        ExploreSummarizeJob job = new ExploreSummarizeJob(detailJobId, request);
        job.setJobKind("instant_then_detail_v2".equals(summarizeMode) ? "instant_then_detail_v2" : "instant_then_detail");
        job.setStatus("running");
        job.setDetailStatus("running");
        job.setProgressStep("fetch");
        job.setProgressPercent(25);
        job.setDetail("Načítám data pro detailní analýzu.");
        jobStore.put(job);

        String instantAnswer = buildInstantAnswer(request);
        Map<String, Object> instantResult = buildInstantResult(request, instantAnswer, summarizeMode);
        job.setInstantResult(instantResult);

        CompletableFuture.runAsync(() -> runDetailJob(job));

        Map<String, Object> payload = new LinkedHashMap<>(instantResult);
        payload.put("detail_job_id", detailJobId);
        payload.put("detail_status", "running");
        payload.put("detail_analysis_available", true);
        payload.put("detail_poll_after_ms", ExploreConstants.SUMMARIZE_DETAIL_POLL_AFTER_MS);
        payload.put("partial", true);
        payload.put("instant_ready", true);
        payload.put("instant_is_final", false);
        return payload;
    }

    public Map<String, Object> summarizeDetail(String detailJobId) {
        ExploreSummarizeJob job = jobStore.get(detailJobId).orElseThrow(() -> new ExploreSummarizeService.SummarizeJobNotFoundException(detailJobId));
        String kind = job.getJobKind();
        if (!"instant_then_detail".equals(kind) && !"instant_then_detail_v2".equals(kind)) {
            throw new ExploreSummarizeService.SummarizeJobNotFoundException(detailJobId);
        }
        return ExploreDetailPollResponseBuilder.build(job);
    }

    private void runDetailJob(ExploreSummarizeJob job) {
        job.setDetailStatus("running");
        job.setProgressStep("fetch");
        job.setProgressPercent(35);
        job.setDetail("Načítám data vybraných řad z lokálního indexu a katalogových konektorů.");
        try {
            ExploreSummarizeRequest request = job.getRequest();
            ExploreSummarizeFetchService.BatchResult fetch =
                    summarizeFetchService.fetchBatch(request.selectedSeries(), request.country());
            job.setProgressStep("relationships");
            job.setProgressPercent(45);
            job.setDetail("Počítám statistické vztahy mezi ukazateli (korelace, trend, medián).");
            ExploreIndicatorRelationshipService.RelationshipsResult relationships =
                    relationshipService.analyze(fetch.loaded(), request.question(), request.sector());
            job.setProgressStep("ai_sections");
            job.setProgressPercent(55);
            job.setDetail("AI píše sekční detailní interpretaci z načtených dat a spočítaných vztahů.");
            ExploreSectionedSynthesisService.SynthesisResult synthesis =
                    sectionedSynthesisService.synthesize(new ExploreSectionedSynthesisService.SynthesisRequest(
                            request.question(),
                            request.sector(),
                            nullSafe(request.country()),
                            fetch.loaded(),
                            relationships.digest()));
            Map<String, Object> detailResult = new LinkedHashMap<>(synthesis.payload());
            detailResult.put("computed_relationships", relationships.relationships());
            detailResult.put("ok", true);
            detailResult.put("status", "completed");
            detailResult.put("job_id", job.getJobId());
            detailResult.put("chart_payload", ExploreSummarizeFetchService.buildChartPayload(fetch.loaded()));
            detailResult.put("chart_summaries", fetch.loaded().stream().map(row -> String.valueOf(row.get("data_context_line"))).toList());
            detailResult.put("chart_count", fetch.loaded().size());
            detailResult.put("series_count_used", fetch.loaded().size());
            detailResult.put("fetch_summary", fetch.summary());
            detailResult.put("series_used", ExploreSummarizeFetchService.buildSeriesUsed(fetch.loaded()));
            detailResult.put(
                    "series_coverage", ExploreSummarizeFetchService.buildSeriesCoverage(fetch.loaded(), fetch.failed()));
            detailResult.put(
                    "limitations_cz",
                    fetch.loaded().isEmpty()
                            ? "Detailní AI syntéza bez numerických dat — fetch řad selhal."
                            : "Sekční detailní syntéza z " + fetch.loaded().size() + " řad.");
            detailResult.put("question", request.question());
            detailResult.put("primary_segment", request.sector());
            job.setChartPayload(ExploreSummarizeFetchService.buildChartPayload(fetch.loaded()));
            job.setDetailResult(detailResult);
            job.setResult(detailResult);
            job.setStatus("completed");
            job.setDetailStatus("completed");
            job.setProgressStep("completed");
            job.setProgressPercent(100);
            job.setDetail("Hotovo.");
        } catch (Exception ex) {
            job.setStatus("failed");
            job.setDetailStatus("failed");
            job.setProgressStep("failed");
            job.setProgressPercent(100);
            job.setError(ex.getMessage());
            job.setErrorCode(ExploreConstants.SUMMARIZE_AI_REQUIRED_ERROR);
            job.setDetail("AI interpretaci se nepodařilo dokončit.");
        }
    }

    private static String buildInstantAnswer(ExploreSummarizeRequest request) {
        String question = nullSafe(request.question()).strip();
        int count = request.selectedSeries() != null ? request.selectedSeries().size() : 0;
        if (question.isBlank()) {
            return "Předběžná odpověď: vybráno " + count + " datových řad. Detailní analýza právě probíhá.";
        }
        return "Předběžná odpověď k dotazu „" + question + "“ na základě " + count
                + " vybraných řad. Detailní AI analýza právě probíhá na pozadí.";
    }

    private static Map<String, Object> buildInstantResult(
            ExploreSummarizeRequest request, String instantAnswer, String summarizeMode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !instantAnswer.isBlank());
        out.put("summarize_mode", summarizeMode);
        out.put("instant_answer", instantAnswer);
        out.put("assistant_answer_cz", instantAnswer);
        out.put("short_answer", instantAnswer);
        out.put("chart_payload", Map.of("series", List.of()));
        out.put("chart_summaries", List.of());
        out.put("chart_count", 0);
        out.put("limitations_cz", "Okamžitá odpověď — detailní analýza ještě běží.");
        out.put("limitations", out.get("limitations_cz"));
        out.put("series_count_used", request.selectedSeries() != null ? request.selectedSeries().size() : 0);
        return out;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
