package cz.bankintel.explore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ExploreDtos {

    private ExploreDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExploreSectorRequest(
            String sector,
            String question,
            String country,
            /**
             * Alternativní tvar payloadu, který posílá část klientů. Dřív se tiše zahazoval
             * (record ho neměl a {@code @JsonIgnoreProperties} ho spolkl), takže analýza
             * na otázku „…v Česku?" proběhla pro celý svět a tvářila se správně.
             */
            @JsonProperty("countries") List<String> countries,
            @JsonProperty("geo_mode") String geoMode,
            String continent,
            @JsonProperty("related_segments") String relatedSegments,
            @JsonProperty("related_segment_ranking") String relatedSegmentRanking,
            @JsonProperty("analysis_mode") String analysisMode,
            @JsonProperty("suggestions_only") boolean suggestionsOnly,
            @JsonProperty("broader_search") boolean broaderSearch,
            @JsonProperty("force_live_deep_search") boolean forceLiveDeepSearch,
            @JsonProperty("include_user_data") boolean includeUserData,
            @JsonProperty("upload_ids") List<String> uploadIds,
            @JsonProperty("user_data_privacy_mode") String userDataPrivacyMode,
            @JsonProperty("analysis_scope") String analysisScope,
            @JsonProperty("company_id") String companyId,
            @JsonProperty("query_only") boolean queryOnly) {
        public ExploreSectorRequest {
            if (sector == null) {
                sector = "";
            }
            if (uploadIds == null) {
                uploadIds = List.of();
            }
            if (countries == null) {
                countries = List.of();
            }
        }

        /** {@code countries: ["CZ","SK"]} → {@code "CZ,SK"}, tedy tvar, kterému rozumí geo resolver. */
        public String countriesAsCsv() {
            return countries == null
                    ? ""
                    : countries.stream()
                            .filter(c -> c != null && !c.isBlank())
                            .map(String::trim)
                            .reduce((a, b) -> a + "," + b)
                            .orElse("");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExploreSummarizeSeriesItem(
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("set_id") String setId,
            String title,
            @JsonProperty("query_params") Map<String, Object> queryParams,
            @JsonProperty("user_selected") boolean userSelected,
            @JsonProperty("series_id") String seriesId,
            @JsonProperty("from_related_segment") Boolean fromRelatedSegment,
            @JsonProperty("indicator_role") String indicatorRole,
            @JsonProperty("manager_category") String managerCategory,
            @JsonProperty("segment_id") String segmentId,
            @JsonProperty("linked_sector_id") String linkedSectorId,
            @JsonProperty("primary_sector_id") String primarySectorId,
            @JsonProperty("context_country") String contextCountry) {
        public ExploreSummarizeSeriesItem {
            if (title == null) {
                title = "";
            }
            if (queryParams == null) {
                queryParams = Map.of();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExploreSummarizeRequest(
            String question,
            String sector,
            String country,
            String countries,
            @JsonProperty("geo_mode") String geoMode,
            String continent,
            @JsonProperty("related_segments") String relatedSegments,
            @JsonProperty("related_segment_ranking") String relatedSegmentRanking,
            @JsonProperty("selected_series") List<ExploreSummarizeSeriesItem> selectedSeries,
            @JsonProperty("include_user_data") boolean includeUserData,
            @JsonProperty("upload_ids") List<String> uploadIds,
            @JsonProperty("fast_mode") boolean fastMode,
            @JsonProperty("summarize_mode") String summarizeMode) {
        public ExploreSummarizeRequest {
            if (selectedSeries == null) {
                selectedSeries = List.of();
            }
            if (uploadIds == null) {
                uploadIds = List.of();
            }
        }
    }

    public static class ExploreSummarizeJob {
        private final String jobId;
        private volatile String jobKind = "standard";
        private volatile String status = "queued";
        private volatile String detailStatus = "queued";
        private volatile String progressStep = "queued";
        private volatile int progressPercent = 5;
        private volatile String detail = "AI přebírá dotaz a připravuje finální interpretaci.";
        private volatile Map<String, Object> result;
        private volatile Map<String, Object> detailResult;
        private volatile Map<String, Object> instantResult;
        private volatile Map<String, Object> chartPayload;
        private volatile String error;
        private volatile String errorCode;
        private final ExploreSummarizeRequest request;

        public ExploreSummarizeJob(String jobId, ExploreSummarizeRequest request) {
            this.jobId = jobId;
            this.request = request;
        }

        public String getJobId() {
            return jobId;
        }

        public String getJobKind() {
            return jobKind;
        }

        public void setJobKind(String jobKind) {
            this.jobKind = jobKind;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getDetailStatus() {
            return detailStatus;
        }

        public void setDetailStatus(String detailStatus) {
            this.detailStatus = detailStatus;
        }

        public String getProgressStep() {
            return progressStep;
        }

        public void setProgressStep(String progressStep) {
            this.progressStep = progressStep;
        }

        public int getProgressPercent() {
            return progressPercent;
        }

        public void setProgressPercent(int progressPercent) {
            this.progressPercent = progressPercent;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public Map<String, Object> getResult() {
            return result;
        }

        public void setResult(Map<String, Object> result) {
            this.result = result;
        }

        public Map<String, Object> getDetailResult() {
            return detailResult;
        }

        public void setDetailResult(Map<String, Object> detailResult) {
            this.detailResult = detailResult;
        }

        public Map<String, Object> getInstantResult() {
            return instantResult;
        }

        public void setInstantResult(Map<String, Object> instantResult) {
            this.instantResult = instantResult;
        }

        public Map<String, Object> getChartPayload() {
            return chartPayload;
        }

        public void setChartPayload(Map<String, Object> chartPayload) {
            this.chartPayload = chartPayload;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public void setErrorCode(String errorCode) {
            this.errorCode = errorCode;
        }

        public ExploreSummarizeRequest getRequest() {
            return request;
        }
    }

    public static Map<String, Object> pendingSummarizeResponse(ExploreSummarizeJob job) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("status", job.getStatus());
        payload.put("job_id", job.getJobId());
        payload.put("poll_after_ms", ExploreConstants.SUMMARIZE_POLL_AFTER_MS);
        payload.put("detail", job.getDetail());
        return payload;
    }
}
