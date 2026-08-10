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
public class ExploreSummarizeService {

    private final ExploreSummarizeJobStore jobStore;
    private final ExploreInstantThenDetailService instantThenDetailService;
    private final ExploreSummarizeFetchService summarizeFetchService;
    private final ExploreSectionedSynthesisService sectionedSynthesisService;
    private final ExploreIndicatorRelationshipService relationshipService;

    public Map<String, Object> startSummarize(ExploreSummarizeRequest request) {
        String mode = request.summarizeMode() != null ? request.summarizeMode().trim().toLowerCase() : "";
        if (isInstantMode(mode)) {
            return instantThenDetailService.start(request, normalizeInstantMode(mode));
        }
        String jobId = "explore-summarize-" + UUID.randomUUID().toString().replace("-", "");
        ExploreSummarizeJob job = new ExploreSummarizeJob(jobId, request);
        jobStore.put(job);
        CompletableFuture.runAsync(() -> runJob(job));
        try {
            Thread.sleep(ExploreConstants.SUMMARIZE_SYNC_WAIT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return statusOrResult(jobId);
    }

    public Map<String, Object> summarizeStatus(String jobId) {
        ExploreSummarizeJob job =
                jobStore.get(jobId).orElseThrow(() -> new SummarizeJobNotFoundException(jobId));
        return statusOrResult(job);
    }

    public Map<String, Object> summarizeDetail(String jobId) {
        return instantThenDetailService.summarizeDetail(jobId);
    }

    private static boolean isInstantMode(String mode) {
        return "instant".equals(mode) || "instant_then_detail".equals(mode) || "instant_then_detail_v2".equals(mode);
    }

    private static String normalizeInstantMode(String mode) {
        if ("instant_then_detail_v2".equals(mode)) {
            return "instant_then_detail_v2";
        }
        return "instant_then_detail";
    }

    private Map<String, Object> statusOrResult(String jobId) {
        ExploreSummarizeJob job =
                jobStore.get(jobId).orElseThrow(() -> new SummarizeJobNotFoundException(jobId));
        return statusOrResult(job);
    }

    private Map<String, Object> statusOrResult(ExploreSummarizeJob job) {
        String status = job.getStatus();
        if ("completed".equals(status) && job.getResult() != null) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("ok", true);
            wrapped.put("status", "completed");
            wrapped.put("job_id", job.getJobId());
            wrapped.put("result", job.getResult());
            return wrapped;
        }
        if ("failed".equals(status)) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("status", "failed");
            failed.put("job_id", job.getJobId());
            failed.put("error", job.getError() != null ? job.getError() : "AI interpretaci se nepodařilo dokončit.");
            failed.put("error_code", job.getErrorCode() != null ? job.getErrorCode() : ExploreConstants.SUMMARIZE_AI_REQUIRED_ERROR);
            return failed;
        }
        return ExploreDtos.pendingSummarizeResponse(job);
    }

    private void runJob(ExploreSummarizeJob job) {
        job.setStatus("running");
        job.setProgressStep("fetch");
        job.setDetail("Načítám data vybraných řad z lokálního indexu a katalogových konektorů.");
        try {
            ExploreSummarizeRequest request = job.getRequest();
            ExploreSummarizeFetchService.BatchResult fetch =
                    summarizeFetchService.fetchBatch(request.selectedSeries(), request.country());
            job.setProgressStep("relationships");
            job.setDetail("Počítám statistické vztahy mezi ukazateli (korelace, trend, medián).");
            String primaryCountry = nullSafe(request.country());
            ExploreIndicatorRelationshipService.RelationshipsResult relationships =
                    relationshipService.analyze(fetch.loaded(), request.question(), request.sector());
            job.setProgressStep("ai_sections");
            job.setDetail("AI píše sekční interpretaci z načtených dat a spočítaných vztahů.");
            ExploreSectionedSynthesisService.SynthesisResult synthesis =
                    sectionedSynthesisService.synthesize(new ExploreSectionedSynthesisService.SynthesisRequest(
                            request.question(), request.sector(), primaryCountry, fetch.loaded(), relationships.digest()));
            Map<String, Object> result = new LinkedHashMap<>(synthesis.payload());
            result.put("computed_relationships", relationships.relationships());
            result.put("ok", true);
            result.put("status", "completed");
            result.put("job_id", job.getJobId());
            result.put("primary_segment", request.sector());
            result.put("series_count_used", fetch.loaded().size());
            result.put("chart_payload", ExploreSummarizeFetchService.buildChartPayload(fetch.loaded()));
            result.put("chart_summaries", fetch.loaded().stream().map(row -> str(row.get("data_context_line"))).toList());
            result.put("chart_count", fetch.loaded().size());
            result.put("fetch_summary", fetch.summary());
            result.put("series_used", ExploreSummarizeFetchService.buildSeriesUsed(fetch.loaded()));
            result.put("series_coverage", ExploreSummarizeFetchService.buildSeriesCoverage(fetch.loaded(), fetch.failed()));
            result.put(
                    "limitations_cz",
                    fetch.loaded().isEmpty()
                            ? "AI interpretace bez numerických dat — fetch řad selhal nebo nebyly vybrány řady."
                            : "Sekční syntéza z " + fetch.loaded().size() + " řad.");
            result.put("question", request.question());
            job.setChartPayload(ExploreSummarizeFetchService.buildChartPayload(fetch.loaded()));
            job.setResult(result);
            job.setStatus("completed");
            job.setProgressStep("completed");
            job.setDetail("Hotovo.");
        } catch (Exception ex) {
            job.setStatus("failed");
            job.setError(ex.getMessage());
            job.setErrorCode(ExploreConstants.SUMMARIZE_AI_REQUIRED_ERROR);
            job.setDetail("AI interpretaci se nepodařilo dokončit.");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public static final class SummarizeJobNotFoundException extends RuntimeException {
        private final String jobId;

        public SummarizeJobNotFoundException(String jobId) {
            super("Summarize job nenalezen. Úloha pravděpodobně vypršela nebo byla smazána — spusťte dotaz znovu.");
            this.jobId = jobId;
        }

        public String getJobId() {
            return jobId;
        }
    }
}
