package cz.bankintel.explore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExploreDetailPollResponseBuilder {

    private static final Map<String, Integer> PROGRESS_PERCENT = Map.of(
            "queued", 5,
            "fetch", 25,
            "ai_sections", 55,
            "completed", 100,
            "failed", 100);

    private static final Map<String, String> PROGRESS_LABELS = Map.of(
            "queued", "Ve frontě",
            "fetch", "Načítám data",
            "ai_sections", "AI píše detailní analýzu",
            "completed", "Hotovo",
            "failed", "Selhalo");

    private ExploreDetailPollResponseBuilder() {}

    public static Map<String, Object> build(ExploreDtos.ExploreSummarizeJob job) {
        String detailStatus = job.getDetailStatus() != null ? job.getDetailStatus() : job.getStatus();
        String progressStep = job.getProgressStep() != null ? job.getProgressStep() : "queued";
        int progressPercent = job.getProgressPercent() > 0
                ? job.getProgressPercent()
                : PROGRESS_PERCENT.getOrDefault(progressStep, 5);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", !"failed".equalsIgnoreCase(detailStatus));
        response.put("status", normalizeStatus(detailStatus));
        response.put("detail_job_id", job.getJobId());
        response.put("detail_status", detailStatus);
        response.put("progress_step", progressStep);
        response.put("progress_step_label", PROGRESS_LABELS.getOrDefault(progressStep, progressStep));
        response.put("progress_percent", progressPercent);
        response.put("poll_after_ms", ExploreConstants.SUMMARIZE_DETAIL_POLL_AFTER_MS);
        response.put("partial_sections", List.of());
        response.put("detail", job.getDetail());
        response.put("error", job.getError());

        if (!"completed".equalsIgnoreCase(detailStatus) && !"failed".equalsIgnoreCase(detailStatus)) {
            Map<String, Object> charts = job.getChartPayload();
            if (charts != null && charts.get("series") instanceof List<?> series && !series.isEmpty()) {
                response.put("chart_payload", charts);
                response.put("charts_ready", true);
            }
        }

        if ("completed".equalsIgnoreCase(detailStatus) && job.getDetailResult() != null) {
            response.putAll(job.getDetailResult());
            response.put("detail_answer", job.getDetailResult().get("assistant_answer_cz"));
            response.put("final_answer_source", "detail_job");
            response.put("detail_ready", true);
        }
        return response;
    }

    private static String normalizeStatus(String detailStatus) {
        if (detailStatus == null) {
            return "running";
        }
        return switch (detailStatus.toLowerCase()) {
            case "queued", "running", "completed", "failed" -> detailStatus.toLowerCase();
            default -> "running";
        };
    }
}
