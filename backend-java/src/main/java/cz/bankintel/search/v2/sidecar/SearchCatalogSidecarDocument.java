package cz.bankintel.search.v2.sidecar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchCatalogSidecarDocument(
        @JsonProperty("series_id")
        String seriesId,
        String source,
        String dataset,
        @JsonProperty("original_title")
        String originalTitle,
        @JsonProperty("original_description")
        String originalDescription,
        @JsonProperty("canonical_title_cs")
        String canonicalTitleCs,
        @JsonProperty("canonical_title_en")
        String canonicalTitleEn,
        @JsonProperty("canonical_description_cs")
        String canonicalDescriptionCs,
        @JsonProperty("canonical_description_en")
        String canonicalDescriptionEn,
        @JsonProperty("primary_concept")
        String primaryConcept,
        @JsonProperty("secondary_concepts")
        List<String> secondaryConcepts,
        @JsonProperty("measure_type")
        String measureType,
        @JsonProperty("economic_object")
        String economicObject,
        @JsonProperty("institutional_sector")
        String institutionalSector,
        @JsonProperty("counterpart_sector")
        String counterpartSector,
        String instrument,
        @JsonProperty("price_type")
        String priceType,
        @JsonProperty("flow_stock")
        String flowStock,
        @JsonProperty("industry_sector")
        String industrySector,
        @JsonProperty("nominal_real")
        String nominalReal,
        String scope,
        String geo,
        String frequency,
        String unit,
        @JsonProperty("seasonal_adjustment")
        String seasonalAdjustment,
        @JsonProperty("price_basis")
        String priceBasis,
        @JsonProperty("dataset_family")
        String datasetFamily,
        @JsonProperty("catalog_family")
        String catalogFamily,
        @JsonProperty("aliases_cs")
        List<String> aliasesCs,
        @JsonProperty("aliases_en")
        List<String> aliasesEn,
        List<String> abbreviations,
        @JsonProperty("negative_concepts")
        List<String> negativeConcepts,
        @JsonProperty("metadata_quality_score")
        double metadataQualityScore,
        @JsonProperty("enrichment_version")
        String enrichmentVersion,
        @JsonProperty("enrichment_source")
        String enrichmentSource,
        @JsonProperty("updated_at")
        String updatedAt,
        @JsonProperty("search_text_cs")
        String searchTextCs,
        @JsonProperty("search_text_en")
        String searchTextEn,
        Map<String, Object> raw) {

    public Map<String, Object> toSearchRow(double score, String matchedQuery, List<String> matchedFields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("source_type", source);
        out.put("series_id", seriesId);
        out.put("set_id", seriesId);
        out.put("id", seriesId);
        out.put("dataset", dataset);
        out.put("title", firstNonBlank(canonicalTitleCs, canonicalTitleEn, originalTitle, seriesId));
        out.put("name", out.get("title"));
        out.put("description", firstNonBlank(canonicalDescriptionCs, canonicalDescriptionEn, originalDescription));
        out.put("original_title", originalTitle);
        out.put("original_description", originalDescription);
        out.put("canonical_title_cs", canonicalTitleCs);
        out.put("canonical_title_en", canonicalTitleEn);
        out.put("canonical_description_cs", canonicalDescriptionCs);
        out.put("canonical_description_en", canonicalDescriptionEn);
        out.put("primary_concept", primaryConcept);
        out.put("secondary_concepts", secondaryConcepts);
        out.put("measure_type", measureType);
        out.put("economic_object", economicObject);
        out.put("institutional_sector", institutionalSector);
        out.put("counterpart_sector", counterpartSector);
        out.put("instrument", instrument);
        out.put("price_type", priceType);
        out.put("flow_stock", flowStock);
        out.put("industry_sector", industrySector);
        out.put("nominal_real", nominalReal);
        out.put("scope", scope);
        out.put("geo", geo);
        out.put("frequency", frequency);
        out.put("freq", frequency);
        out.put("unit", unit);
        out.put("seasonal_adjustment", seasonalAdjustment);
        out.put("price_basis", priceBasis);
        out.put("dataset_family", datasetFamily);
        out.put("catalog_family", catalogFamily);
        out.put("aliases_cs", aliasesCs);
        out.put("aliases_en", aliasesEn);
        out.put("abbreviations", abbreviations);
        out.put("negative_concepts", negativeConcepts);
        out.put("concepts", concepts());
        out.put("tags", concepts());
        out.put("metadata_quality_score", metadataQualityScore);
        out.put("enrichment_version", enrichmentVersion);
        out.put("enrichment_source", enrichmentSource);
        out.put("updated_at", updatedAt);
        out.put("lifecycle_status", lifecycleStatus());
        out.put("lifecycle_reason", lifecycleReason());
        out.put("lifecycle_confidence", lifecycleConfidence());
        out.put("latest_date", latestPeriod());
        out.put("search_text_cs", searchTextCs);
        out.put("search_text_en", searchTextEn);
        out.put("_fts_rank", -score);
        out.put("_sidecar_score", score);
        out.put("_matched_query", matchedQuery);
        out.put("_matched_fields", matchedFields);
        out.put("_catalog_index", "sidecar");
        out.put("raw", raw);
        return out;
    }

    public String lifecycleStatus() {
        return resolvedLifecycle().status();
    }

    public String lifecycleReason() {
        return resolvedLifecycle().reason();
    }

    public double lifecycleConfidence() {
        return resolvedLifecycle().confidence();
    }

    public String latestPeriod() {
        if (raw == null) {
            return "";
        }
        for (String key : List.of("latest_period", "latest_date", "last_date", "end_period")) {
            String value = String.valueOf(raw.getOrDefault(key, "")).trim();
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private String rawString(String key, String fallback) {
        String value = raw == null ? "" : String.valueOf(raw.getOrDefault(key, "")).trim();
        return value.isBlank() || "null".equalsIgnoreCase(value) ? fallback : value;
    }

    private SearchSeriesLifecycleClassifier.Lifecycle resolvedLifecycle() {
        String storedStatus = rawString("lifecycle_status", "");
        if (!storedStatus.isBlank()) {
            return new SearchSeriesLifecycleClassifier.Lifecycle(
                    storedStatus,
                    rawString("lifecycle_reason", "no_historical_evidence"),
                    rawDouble("lifecycle_confidence", 0.35));
        }
        return SearchSeriesLifecycleClassifier.classifyDatasetSeries(
                source,
                dataset,
                rawString("dataset_lifecycle", ""),
                latestPeriod(),
                frequency,
                java.time.Year.now().getValue());
    }

    private double rawDouble(String key, double fallback) {
        Object value = raw == null ? null : raw.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public List<String> concepts() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        add(out, primaryConcept);
        if (secondaryConcepts != null) {
            secondaryConcepts.forEach(value -> add(out, value));
        }
        add(out, measureType);
        add(out, economicObject);
        add(out, institutionalSector);
        add(out, counterpartSector);
        add(out, instrument);
        add(out, priceType);
        add(out, flowStock);
        add(out, industrySector);
        add(out, nominalReal);
        add(out, scope);
        add(out, datasetFamily);
        add(out, catalogFamily);
        return List.copyOf(out);
    }

    private static void add(java.util.Set<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
