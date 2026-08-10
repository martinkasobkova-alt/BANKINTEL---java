package cz.bankintel.search;

import cz.bankintel.sources.ecb.EcbAvailabilityService;
import cz.bankintel.sources.ecb.EcbReference;
import cz.bankintel.sources.oecd.OecdSdmx2SetId;
import cz.bankintel.util.BankIntelEnvVars;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic rules: is {@code set_id} fetchable for live catalog preview? Port of Python {@code catalog_row_validators}. */
public final class CatalogPreviewSetIdSupport {

    private static final Pattern ARAD = Pattern.compile("^[0-9]+$");
    private static final Pattern ARAD_SERIES = Pattern.compile("^[0-9]+:[A-Za-z0-9_.-]+$");
    private static final Pattern CSU = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{3,}$");
    private static final Pattern FRED = Pattern.compile("^[A-Za-z0-9._-]{3,32}$");
    private static final Pattern DATA360 = Pattern.compile("^[A-Za-z0-9_.-]+\\|[A-Za-z0-9_.-]+$");
    /**
     * Many Eurostat enrichment aliases append English labels after the frequency token
     * ({@code sts_inpr_m_manufacturing_total} → {@code sts_inpr_m}). Used only when {@code dataset}
     * is missing from the candidate row.
     */
    private static final Pattern EUROSTAT_FREQ_PARENT =
            Pattern.compile("^(?<parent>.+_[amqd])(?:_.+)$", Pattern.CASE_INSENSITIVE);
    /**
     * A NACE section+division suffix ({@code c29}, {@code c291}, ...) — {@link #EUROSTAT_FREQ_PARENT}
     * would otherwise strip this as if it were an English enrichment label, but it names a distinct,
     * independently-fetchable Eurostat dataset (e.g. {@code sts_inpr_m_c29} = industrial production for
     * NACE C29 specifically, NOT the same series as its parent {@code sts_inpr_m}); stripping it silently
     * resolves the preview fetch to the wrong (empty-for-this-query) dataset.
     */
    private static final Pattern NACE_CODE_SUFFIX = Pattern.compile("^[a-z]\\d{1,3}$", Pattern.CASE_INSENSITIVE);

    private CatalogPreviewSetIdSupport() {}

    public static boolean isPreviewFetchable(String sourceType, String setId, Map<String, Object> row) {
        String source = CatalogSourceRegistry.normalizeSearchSource(sourceType);
        String sid = setId != null ? setId.trim() : "";
        if (sid.isBlank()) {
            return false;
        }
        // Enrichment aliases (e.g. eurostat sts_inpr_m_manufacturing_total) must resolve to a real
        // connector dataset code before we claim the row is live-previewable.
        String resolved = resolvePreviewSetId(source, sid, row);
        if (resolved.isBlank()) {
            return false;
        }
        return switch (source) {
            case "arad" -> !env("ARAD_API_KEY").isBlank()
                    && (ARAD.matcher(resolved).matches() || ARAD_SERIES.matcher(resolved).matches());
            case "csu" -> CSU.matcher(resolved).matches() && resolved.length() >= 4;
            case "eurostat" -> isEurostatDatasetCode(resolved);
            case "ecb", "ecb2" -> ecbPreviewable(resolved, row);
            case "imf" -> !env("IMF_API_KEY").isBlank() && imfPreviewable(resolved, row);
            case "oecd", "oecd4" -> oecdPreviewable(resolved, row);
            case "fred" -> FRED.matcher(resolved).matches() && !resolved.chars().allMatch(Character::isDigit);
            case "bis" -> resolved.contains("/") || resolved.startsWith("BIS|");
            case "data360" -> DATA360.matcher(resolved).matches();
            case "worldbank_pink_sheet", "commodities" -> !resolved.isBlank();
            default -> true;
        };
    }

    /**
     * Returns the connector-facing dataset / series id for live preview.
     *
     * <p>Sidecar enrichment often stores a synthetic {@code series_id} (e.g.
     * {@code sts_inpr_m_manufacturing_total}) while the real Eurostat API code lives in
     * {@code dataset}/{@code dataset_id}. Fetching the synthetic id returns HTTP 404 and burns
     * Manager Explorer preview slots even though the parent dataset is chartable.
     */
    public static String resolvePreviewSetId(String sourceType, String setId, Map<String, Object> row) {
        String source = CatalogSourceRegistry.normalizeSearchSource(sourceType);
        String sid = setId != null ? setId.trim() : "";
        if (sid.isBlank()) {
            return "";
        }
        if ("eurostat".equals(source)) {
            return resolveEurostatDatasetCode(sid, row);
        }
        return sid;
    }

    static String resolveEurostatDatasetCode(String setId, Map<String, Object> row) {
        Map<String, Object> params = mergeRowParams(row);
        String dataset = firstNonBlank(
                stringField(params, "dataset"),
                stringField(params, "dataset_id"),
                stringField(params, "dataset_code"));
        Object raw = params.get("raw");
        if (raw instanceof Map<?, ?> rawMap) {
            dataset = firstNonBlank(
                    dataset,
                    stringField(castStringMap(rawMap), "dataset_id"),
                    stringField(castStringMap(rawMap), "dataset"),
                    stringField(castStringMap(rawMap), "set_id"));
        }
        if (isEurostatDatasetCode(dataset) && !dataset.equalsIgnoreCase(setId)) {
            return dataset.trim();
        }
        var matcher = EUROSTAT_FREQ_PARENT.matcher(setId);
        if (matcher.matches()) {
            String parent = matcher.group("parent");
            String suffix = setId.substring(Math.min(parent.length() + 1, setId.length()));
            if (isEurostatDatasetCode(parent) && !NACE_CODE_SUFFIX.matcher(suffix).matches()) {
                return parent;
            }
        }
        if (isEurostatDatasetCode(setId)) {
            return setId;
        }
        return setId;
    }

    static boolean isEurostatDatasetCode(String setId) {
        if (setId == null) {
            return false;
        }
        String sid = setId.trim();
        if (sid.length() < 2 || sid.contains("/") || sid.contains("|") || sid.contains(" ")) {
            return false;
        }
        // Eurostat dataset codes are alphanumeric + underscore; enrichment aliases append long
        // English suffixes after a real code (sts_inpr_m_manufacturing_total). Prefer the parent
        // when present; standalone synthetic ids still pass the charset check here so callers that
        // already resolved via dataset stay consistent.
        return sid.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '-');
    }

    private static boolean ecbPreviewable(String setId, Map<String, Object> row) {
        if (isEcbDataflowRow(setId, row)) {
            return false;
        }
        if (EcbAvailabilityService.isCuratedSetIdFormat(setId)) {
            return true;
        }
        Map<String, Object> params = mergeRowParams(row);
        if (setId.toLowerCase(Locale.ROOT).startsWith("ecb_") && hasCountryHint(params)) {
            return true;
        }
        EcbReference.Parsed ref = EcbReference.parseSetId(setId);
        return ref != null && ref.validPreviewTarget();
    }

    private static boolean oecdPreviewable(String setId, Map<String, Object> row) {
        if (setId.startsWith("OECD4|")) {
            String[] parts = setId.split("\\|", -1);
            if (parts.length == 3 && "dataset".equalsIgnoreCase(parts[2])) {
                return false;
            }
            return parts.length >= 5;
        }
        if (setId.startsWith("SDMX2|")) {
            return OecdSdmx2SetId.parse(setId) != null;
        }
        if (setId.contains("/") && !setId.toUpperCase(Locale.ROOT).contains("||DATAFLOW")) {
            return true;
        }
        Object qp = rowParams(row).get("query_params");
        if (qp instanceof Map<?, ?> map && map.get("oecd4_key") != null) {
            return true;
        }
        return false;
    }

    private static boolean imfPreviewable(String setId, Map<String, Object> row) {
        if (setId.startsWith("IMF|") && setId.split("\\|").length >= 5) {
            return true;
        }
        Map<String, Object> params = rowParams(row);
        return !stringField(params, "imf_country").isBlank()
                && !stringField(params, "imf_flow").isBlank()
                && (!stringField(params, "imf_indicator").isBlank() || setId.contains("."));
    }

    private static boolean isEcbDataflowRow(String setId, Map<String, Object> row) {
        if (setId.toUpperCase(Locale.ROOT).contains("||DATAFLOW")) {
            return true;
        }
        if (row != null && "dataflow".equals(String.valueOf(row.get("kind")).trim())) {
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowParams(Map<String, Object> row) {
        if (row == null) {
            return Map.of();
        }
        Object nested = row.get("row");
        if (nested instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return row;
    }

    private static Map<String, Object> mergeRowParams(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row != null) {
            out.putAll(row);
            out.putAll(rowParams(row));
        }
        return out;
    }

    private static boolean hasCountryHint(Map<String, Object> params) {
        for (String key : List.of("country", "ecb_country", "territory")) {
            if (!stringField(params, key).isBlank()) {
                return true;
            }
        }
        Object qp = params.get("query_params");
        if (qp instanceof Map<?, ?> map) {
            for (String key : List.of("country", "ecb_country", "territory")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).trim().isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stringField(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String env(String name) {
        String value = BankIntelEnvVars.get(name);
        return value != null ? value.trim() : "";
    }
}
