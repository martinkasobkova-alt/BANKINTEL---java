package cz.bankintel.search.v2.sidecar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.model.CatalogMapSupport;
import java.io.InputStream;
import java.time.Year;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies catalog rows from structured lifecycle and coverage metadata. */
public final class SearchSeriesLifecycleClassifier {

    private static final Pattern YEAR_PATTERN = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Map<String, Lifecycle> DATASET_REGISTRY = loadDatasetRegistry();

    private SearchSeriesLifecycleClassifier() {}

    public static Map<String, Object> enrich(Map<String, Object> input, String frequency) {
        Map<String, Object> out = new LinkedHashMap<>(input == null ? Map.of() : input);
        String seriesLifecycle = CatalogMapSupport.firstNonBlank(out.get("lifecycle_status"), out.get("lifecycle"))
                .trim()
                .toLowerCase(Locale.ROOT);
        String datasetLifecycle = CatalogMapSupport.firstNonBlank(out.get("dataset_lifecycle"), out.get("dataset_status"))
                .trim()
                .toLowerCase(Locale.ROOT);
        String latestPeriod = CatalogMapSupport.firstNonBlank(
                out.get("latest_date"), out.get("last_date"), out.get("latest_period"), out.get("end_period"), out.get("time_period_max"));

        String source = CatalogMapSupport.firstNonBlank(out.get("source"), out.get("source_type"), out.get("catalog_id"));
        String dataset = CatalogMapSupport.firstNonBlank(out.get("dataset_id"), out.get("dataset"), out.get("ecb_flow"));
        Lifecycle lifecycle = seriesLifecycle.isBlank()
                ? classifyDatasetSeries(source, dataset, datasetLifecycle, latestPeriod, frequency, Year.now().getValue())
                : classify(source, dataset, seriesLifecycle, latestPeriod, frequency, Year.now().getValue());
        out.put("lifecycle_status", lifecycle.status());
        out.put("lifecycle_reason", lifecycle.reason());
        out.put("lifecycle_confidence", lifecycle.confidence());
        if (!latestPeriod.isBlank()) {
            out.put("latest_period", latestPeriod);
        }
        return out;
    }

    static Lifecycle classify(String explicit, String latestPeriod, String frequency, int currentYear) {
        return classify("", "", explicit, latestPeriod, frequency, currentYear);
    }

    static Lifecycle classify(
            String source,
            String dataset,
            String explicit,
            String latestPeriod,
            String frequency,
            int currentYear) {
        String state = explicit == null ? "" : explicit.trim().toLowerCase(Locale.ROOT);
        if (containsAny(state, "discontinued", "historical", "archived", "inactive", "closed", "legacy")) {
            return new Lifecycle("historical", "explicit_dataset_lifecycle", 1.0);
        }
        Integer latestYear = extractLatestYear(latestPeriod);
        if (latestYear != null) {
            int tolerance = freshnessToleranceYears(frequency);
            if (latestYear < currentYear - tolerance) {
                return new Lifecycle("historical", "latest_period_outside_freshness_window", 0.95);
            }
            return new Lifecycle("current", "latest_period_within_freshness_window", 0.95);
        }
        if (containsAny(state, "current", "active", "ongoing", "live")) {
            return new Lifecycle("current", "explicit_dataset_lifecycle", 0.9);
        }
        Lifecycle registered = DATASET_REGISTRY.get(registryKey(source, dataset));
        if (registered != null) {
            return registered;
        }
        return new Lifecycle("current", "no_historical_evidence", 0.35);
    }

    static Lifecycle classifyDatasetSeries(
            String source,
            String dataset,
            String datasetLifecycle,
            String latestPeriod,
            String frequency,
            int currentYear) {
        String state = datasetLifecycle == null ? "" : datasetLifecycle.trim().toLowerCase(Locale.ROOT);
        if (containsAny(state, "discontinued", "historical", "archived", "inactive", "closed", "legacy")) {
            return new Lifecycle("historical", "explicit_dataset_lifecycle", 1.0);
        }
        Integer latestYear = extractLatestYear(latestPeriod);
        if (latestYear != null) {
            return classify("", latestPeriod, frequency, currentYear);
        }
        Lifecycle registered = DATASET_REGISTRY.get(registryKey(source, dataset));
        if (registered != null && "historical".equals(registered.status())) {
            return registered;
        }
        if (containsAny(state, "current", "active", "ongoing", "live") || registered != null) {
            return new Lifecycle("unknown", "active_dataset_series_freshness_unknown", 0.0);
        }
        return new Lifecycle("unknown", "series_freshness_unknown", 0.0);
    }

    private static Map<String, Lifecycle> loadDatasetRegistry() {
        try (InputStream input = SearchSeriesLifecycleClassifier.class
                .getClassLoader()
                .getResourceAsStream("search-v2-dataset-lifecycle.json")) {
            if (input == null) {
                return Map.of();
            }
            Map<String, Object> root = new ObjectMapper().readValue(input, new TypeReference<>() {});
            Map<String, Lifecycle> registry = new HashMap<>();
            Object rows = root.get("datasets");
            if (rows instanceof List<?> datasets) {
                for (Object value : datasets) {
                    if (!(value instanceof Map<?, ?> row)) {
                        continue;
                    }
                    String source = stringValue(row.get("source"), "");
                    String dataset = stringValue(row.get("dataset"), "");
                    String status = stringValue(row.get("status"), "current");
                    String reason = stringValue(row.get("reason"), "dataset_registry");
                    double confidence = row.get("confidence") == null
                            ? 0.9
                            : CatalogMapSupport.toDouble(row.get("confidence"));
                    if (!source.isBlank() && !dataset.isBlank()) {
                        registry.put(registryKey(source, dataset), new Lifecycle(status, reason, confidence));
                    }
                }
            }
            return Map.copyOf(registry);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String registryKey(String source, String dataset) {
        return (source == null ? "" : source.trim().toLowerCase(Locale.ROOT)) + "|"
                + (dataset == null ? "" : dataset.trim().toUpperCase(Locale.ROOT));
    }

    private static String stringValue(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private static int freshnessToleranceYears(String frequency) {
        String normalized = frequency == null ? "" : frequency.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("A") || normalized.equals("Y") || normalized.contains("ANNUAL") || normalized.contains("YEAR")) {
            return 2;
        }
        return 1;
    }

    private static Integer extractLatestYear(String value) {
        Matcher matcher = YEAR_PATTERN.matcher(value == null ? "" : value);
        Integer latest = null;
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            latest = latest == null ? year : Math.max(latest, year);
        }
        return latest;
    }

    private static boolean containsAny(String value, String... alternatives) {
        for (String alternative : alternatives) {
            if (value.contains(alternative)) {
                return true;
            }
        }
        return false;
    }

    record Lifecycle(String status, String reason, double confidence) {}
}
