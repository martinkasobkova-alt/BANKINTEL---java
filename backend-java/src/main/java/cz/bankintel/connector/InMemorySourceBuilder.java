package cz.bankintel.connector;
import cz.bankintel.util.BankIntelEnvVars;

import cz.bankintel.sources.bis.BisCatalogService;
import cz.bankintel.sources.bis.BisCatalogService.ParsedSetId;
import cz.bankintel.sources.ecb.EcbAvailabilityService;
import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import cz.bankintel.sources.ecb.EcbReference;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import cz.bankintel.search.AradSeriesIdentity;
import cz.bankintel.search.CatalogCountryIso3Registry;
import cz.bankintel.search.CatalogGeoIntent;
import java.util.List;
import java.util.Locale;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sestaví dočasný „source“ objekt pro preview/sync z HTTP payloadu.
 *
 * <p>Port Python {@code catalog_preview_routes._build_in_memory_source} — nic se neukládá do DB,
 * config žije jen po dobu requestu.
 */
@Component
public class InMemorySourceBuilder {

    private final EcbCuratedCatalog ecbCuratedCatalog;
    private final EurostatDimensionService eurostatDimensionService;
    private final Oecd4BrowseService oecd4BrowseService;

    public InMemorySourceBuilder(
            EcbCuratedCatalog ecbCuratedCatalog,
            EurostatDimensionService eurostatDimensionService,
            Oecd4BrowseService oecd4BrowseService) {
        this.ecbCuratedCatalog = ecbCuratedCatalog;
        this.eurostatDimensionService = eurostatDimensionService;
        this.oecd4BrowseService = oecd4BrowseService;
    }

    private static final String ARAD_BASE_URL = "https://www.cnb.cz/aradb/api/v1";
    private static final String ARAD_DATA_ENDPOINT = "/data";
    private static final String EUROSTAT_BASE_URL =
            "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data";
    private static final String FRED_BASE_URL = "https://api.stlouisfed.org/fred";
    private static final String CSU_BASE_URL = "https://data.csu.gov.cz";
    private static final String IMF_BASE_URL = "https://api.imf.org/external/sdmx/3.0";
    private static final String OECD_SDMX_PUBLIC_REST = "https://sdmx.oecd.org/public/rest";
    private static final String OECD_BASE_URL_LEGACY = "https://stats.oecd.org";
    private static final String DATA360_BASE_URL = "https://data360api.worldbank.org";
    private static final String OECD_DATA_ACCEPT = "application/vnd.sdmx.data+json; charset=utf-8; version=2";

    public Map<String, Object> build(String sourceType, Map<String, Object> params) {
        String st = sourceType != null ? sourceType.trim().toLowerCase() : "";
        if ("financial_markets".equals(st)) {
            st = "financial_markets_mirror";
        }
        String setId = stringField(params, "set_id");
        if (st.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing source_type");
        }

        Map<String, Object> common = new LinkedHashMap<>();
        common.put("id", UUID.randomUUID().toString());
        common.put("name", "preview-" + st + "-" + (setId.isBlank() ? "na" : setId));
        common.put("source_type", st);
        common.put("method", "GET");
        common.put("headers", new LinkedHashMap<>());
        common.put("query_params", new LinkedHashMap<>());
        common.put("auth_type", "none");
        common.put("active", true);

        return switch (st) {
            case "arad" -> buildArad(common, setId);
            case "eurostat" -> buildEurostat(common, setId, params);
            case "fred" -> buildFred(common, setId, params);
            case "csu" -> buildCsu(common, setId, params);
            case "bis" -> buildBis(common, setId, params);
            case "imf" -> buildImf(common, setId, params);
            case "oecd" -> buildOecd(common, setId, params);
            case "world_bank_data360" -> buildData360(common, setId, params);
            case "ecb", "ecb2" -> buildEcb(common, setId, params);
            default -> {
                if (setId.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id");
                }
                common.put("set_id", setId);
                yield common;
            }
        };
    }

    private Map<String, Object> buildArad(Map<String, Object> common, String setId) {
        if (setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id");
        }
        String fetchSetId = setId;
        String selectedIndicator = "";
        String[] aradSeries = AradSeriesIdentity.parse(setId);
        if (aradSeries != null) {
            fetchSetId = aradSeries[0];
            selectedIndicator = aradSeries[1];
        }
        String apiKey = env("ARAD_API_KEY");
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ARAD_API_KEY není nastaven na serveru.");
        }
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("set_id", fetchSetId);
        queryParams.put("lang", "CS");
        queryParams.put("api_key", apiKey);

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", ARAD_BASE_URL);
        out.put("endpoint", ARAD_DATA_ENDPOINT);
        out.put("auth_type", "api_key_query");
        out.put("query_params", queryParams);
        out.put("set_id", fetchSetId);
        if (!selectedIndicator.isBlank()) {
            out.put("requested_set_id", setId);
            out.put("selected_indicator", selectedIndicator);
        }
        return out;
    }

    private Map<String, Object> buildEurostat(Map<String, Object> common, String setId, Map<String, Object> params) {
        if (setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id");
        }
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("format", "JSON");
        queryParams.put("lang", "EN");
        Object rawQp = params.get("query_params");
        if (rawQp instanceof Map<?, ?> qpMap) {
            for (Map.Entry<?, ?> entry : qpMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    queryParams.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        Object rawDimensionFilters = params.get("dimension_filters");
        if (rawDimensionFilters instanceof Map<?, ?> dimMap) {
            for (Map.Entry<?, ?> entry : dimMap.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim();
                if (key.isBlank() || "indicator_id".equalsIgnoreCase(key)) {
                    continue;
                }
                queryParams.put(key, entry.getValue());
            }
        }
        String country = stringField(params, "country");
        if (country.isBlank()) {
            country = stringField(queryParams, "geo");
        }
        if (country.isBlank()) {
            Object geoIntent = params.get("geo_intent");
            if (geoIntent instanceof Map<?, ?> geoMap) {
                List<String> codes = CatalogGeoIntent.requestedGeoCodes(castMap(geoMap));
                if (!codes.isEmpty()) {
                    country = codes.get(0);
                }
            }
        }
        if (!hasGeo(queryParams) && !country.isBlank()) {
            queryParams.put("geo", country.toUpperCase());
        } else if (!hasGeo(queryParams)) {
            queryParams.put("geo", "CZ");
        }
        boolean hasExplicitTimeFilter = hasTimeFilter(queryParams);
        String explicitGeoKey = firstPresentGeoKey(queryParams);
        Object explicitGeoValue = explicitGeoKey.isBlank() ? null : queryParams.get(explicitGeoKey);
        if (needsEurostatDimensionResolution(queryParams)) {
            Map<String, Object> resolved =
                    eurostatDimensionService.resolvePreviewQueryParams(setId, firstGeoValue(queryParams));
            if (resolved != null && !resolved.isEmpty()) {
                queryParams.putAll(resolved);
                if (!explicitGeoKey.isBlank() && explicitGeoValue != null) {
                    queryParams.put(explicitGeoKey, explicitGeoValue);
                    if (!"geo".equalsIgnoreCase(explicitGeoKey)) {
                        queryParams.remove("geo");
                    }
                }
            }
        }
        if (!hasExplicitTimeFilter) {
            // Eurostat treats lastTimePeriod as a latest-period flag, not as a number of periods.
            // Dimension discovery may add it for a cheap verification request; a detail preview
            // without an explicit time filter must remove that hint and return the history.
            queryParams.keySet().removeIf(key -> "lastTimePeriod".equalsIgnoreCase(key));
        }

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", EUROSTAT_BASE_URL);
        out.put("endpoint", "/" + setId);
        out.put("headers", Map.of("Accept", "application/json"));
        out.put("query_params", queryParams);
        out.put("set_id", setId);
        out.put("_preview_filters_applied", eurostatPreviewFiltersApplied(queryParams));
        out.put("_preview_user_query", stringField(params, "user_query"));
        Map<String, Object> previewDimensions = eurostatDimensionService.previewAvailableDimensions(setId);
        if (previewDimensions != null && !previewDimensions.isEmpty()) {
            out.put("_preview_available_dimensions", previewDimensions);
        }
        if (params.get("geo_intent") instanceof Map<?, ?> geoIntent) {
            out.put("_preview_geo_intent", ConnectorHttpSupport.stringMap(geoIntent));
        }
        return out;
    }

    private Map<String, Object> buildFred(Map<String, Object> common, String setId, Map<String, Object> params) {
        String seriesId = setId;
        if (seriesId.isBlank()) {
            Map<String, Object> qp = queryParams(params);
            seriesId = stringField(qp, "series_id");
            if (seriesId.isBlank()) {
                seriesId = stringField(qp, "id");
            }
        }
        if (seriesId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id / fred series_id");
        }
        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", FRED_BASE_URL);
        out.put("fred_series_id", seriesId);
        out.put("set_id", seriesId);
        out.put("query_params", queryParams(params));
        return out;
    }

    private Map<String, Object> buildCsu(Map<String, Object> common, String setId, Map<String, Object> params) {
        if (setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id");
        }
        String datasetCode = stringField(params, "dataset_code");
        Map<String, Object> qp = queryParams(params);
        boolean useSelection = truthy(qp.get("csu_selection_endpoint")) || truthy(qp.get("csu_use_selection_endpoint"));

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", CSU_BASE_URL);
        out.put("set_id", setId);
        if (!useSelection && !datasetCode.isBlank()) {
            Map<String, Object> fullQuery = new LinkedHashMap<>(qp);
            fullQuery.put("format", "CSV");
            fullQuery.put("csu_full_dataset", "1");
            out.put("csu_dataset_code", datasetCode);
            out.put("endpoint", "/api/dotaz/v1/data/sady/" + datasetCode + "/vlastni");
            out.put("method", "POST");
            out.put("query_params", fullQuery);
        } else {
            out.put("endpoint", "/api/dotaz/v1/data/vybery/" + setId);
            out.put("query_params", Map.of("format", "CSV"));
        }
        return out;
    }

    private Map<String, Object> buildBis(Map<String, Object> common, String setId, Map<String, Object> params) {
        if (setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id");
        }
        Map<String, Object> qpIn = new LinkedHashMap<>(queryParamsWithDimensionFilters(params));
        Object extra = params.get("bis_params");
        if (extra == null) {
            extra = params.get("params");
        }
        if (extra instanceof Map<?, ?> extraMap) {
            for (Map.Entry<?, ?> entry : extraMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    qpIn.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()).trim());
                }
            }
        }

        ParsedSetId parsed;
        try {
            parsed = BisCatalogService.parseSetId(setId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        Map<String, Object> normalized =
                BisCatalogService.normalizeBisQueryParams(Map.of("query_params", qpIn), "preview");
        Map<String, Object> outQp = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : normalized.entrySet()) {
            if (entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                outQp.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        String canon = BisCatalogService.composeCatalogSetId(parsed.flow(), parsed.key());
        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", BisCatalogService.BIS_API_ROOT);
        out.put("endpoint", "/data/preview/");
        out.put("headers", Map.of(
                "Accept", BisCatalogService.BIS_GENERICDATA_ACCEPT,
                "User-Agent", "banking-bi/1.0"));
        out.put("query_params", outQp);
        out.put("set_id", setId);
        out.put("bis_catalog_set_id", canon);
        out.put("_bis_query_context", "preview");
        return out;
    }

    private Map<String, Object> buildImf(Map<String, Object> common, String setId, Map<String, Object> params) {
        if (env("IMF_API_KEY").isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IMF_API_KEY není nastaven na serveru.");
        }

        Map<String, Object> qpIn = new LinkedHashMap<>(queryParamsWithDimensionFilters(params));
        String agency = "IMF.RES";
        String version = "1.0.0";
        String flow = stringField(qpIn, "imf_flow");
        if (flow.isBlank()) {
            flow = stringField(params, "imf_flow");
        }
        String indicator = stringField(qpIn, "imf_indicator");
        if (indicator.isBlank()) {
            indicator = stringField(params, "imf_indicator");
        }
        String country = stringField(qpIn, "imf_country");
        if (country.isBlank()) {
            country = stringField(qpIn, "country");
        }
        if (country.isBlank()) {
            country = firstGeoValueFromAny(qpIn.get("COUNTRY"));
        }
        if (country.isBlank()) {
            country = firstGeoValueFromAny(qpIn.get("REF_AREA"));
        }
        if (country.isBlank()) {
            country = firstGeoValueFromAny(qpIn.get("geo"));
        }
        if (country.isBlank()) {
            country = stringField(params, "imf_country");
        }
        if (country.isBlank()) {
            country = stringField(params, "country");
        }
        if (country.isBlank()) {
            country = firstGeoValueFromAny(params.get("geo"));
        }
        String sdmxKey = stringField(params, "imf_sdmx_key");

        String sid = setId != null ? setId.trim() : "";
        if (sid.startsWith("IMF|")) {
            String[] parts = sid.split("\\|");
            if (parts.length >= 5) {
                agency = parts[1];
                flow = parts[2];
                version = parts[3];
                sdmxKey = parts[4];
                if (country.isBlank() && sdmxKey.contains(".")) {
                    country = sdmxKey.substring(0, sdmxKey.indexOf('.'));
                }
                if (indicator.isBlank() && sdmxKey.contains(".")) {
                    indicator = sdmxKey.substring(sdmxKey.indexOf('.') + 1);
                }
            }
        }

        if (sdmxKey.isBlank() && !country.isBlank() && !indicator.isBlank()) {
            sdmxKey = country.toUpperCase() + "." + indicator;
        }
        String requestedCountries = imfCountrySelection(params, qpIn);
        if (!requestedCountries.isBlank() && !sdmxKey.isBlank() && sdmxKey.contains(".")) {
            sdmxKey = requestedCountries + sdmxKey.substring(sdmxKey.indexOf('.'));
            country = requestedCountries;
        }
        if (flow.isBlank() || sdmxKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "IMF náhled vyžaduje set_id IMF|agency|flow|version|key nebo imf_country + imf_flow + imf_indicator.");
        }

        Map<String, Object> qp = new LinkedHashMap<>();
        qp.put("startPeriod", "1990");
        qp.put("endPeriod", "2035");
        qp.putAll(qpIn);

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", IMF_BASE_URL);
        out.put("endpoint", "/data/dataflow/" + agency + "/" + flow + "/" + version + "/" + sdmxKey);
        out.put("headers", Map.of(
                "Accept", "application/vnd.sdmx.data+json;version=2.0.0",
                "User-Agent", "banking-bi/1.0"));
        out.put("query_params", qp);
        out.put("set_id", sid.isBlank() ? flow + "/" + sdmxKey : sid);
        out.put("imf_flow", flow);
        out.put("imf_indicator", indicator);
        out.put("imf_country", country.toUpperCase());
        out.put("imf_agency", agency);
        out.put("imf_version", version);
        out.put("imf_sdmx_key", sdmxKey);
        out.put("imf_sdmx3", true);
        return out;
    }

    private Map<String, Object> buildOecd(Map<String, Object> common, String setId, Map<String, Object> params) {
        String sid = setId != null ? setId.trim() : "";
        if (sid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id");
        }

        if (sid.startsWith("OECD4|")) {
            return buildOecd4Offline(common, sid, queryParamsWithDimensionFilters(params));
        }

        Map<String, Object> oecd4Params = queryParamsWithDimensionFilters(params);
        if (isOecd4OfflineParams(oecd4Params)) {
            return buildOecd4Offline(common, sid, oecd4Params);
        }

        Map<String, Object> slashOfflineParams = slashFormatOecd4OfflineParams(sid, oecd4Params);
        if (slashOfflineParams != null) {
            return buildOecd4Offline(common, sid, slashOfflineParams);
        }

        if (sid.startsWith("SDMX2|")) {
            String[] parts = sid.split("\\|", 5);
            if (parts.length < 5) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný OECD SDMX v2 set_id.");
            }
            String agency = parts[1];
            String dataflow = parts[2];
            String version = parts[3];
            String filter = parts[4];
            Map<String, Object> qpIn = new LinkedHashMap<>(queryParamsWithDimensionFilters(params));
            qpIn.putIfAbsent("attributes", "dsd");
            qpIn.putIfAbsent("measures", "all");
            qpIn.putIfAbsent("dimensionAtObservation", "AllDimensions");
            // Puvodnich 120 tise oriznulo "cele obdobi" na poslednich 120 pozorovani, i kdyz nikdo
            // konkretni rozsah nezadal (stejna trida chyby jako u yahoo_finance) - zvyseno tak, aby
            // v praxi znamenalo "vse, co OECD ma", bez ohledu na periodicitu rady.
            qpIn.putIfAbsent("lastNObservations", 20_000);

            String endpoint = String.join(
                    "/",
                    "v2",
                    "data",
                    "dataflow",
                    urlEncodePath(agency),
                    urlEncodePath(dataflow),
                    urlEncodePath(version),
                    urlEncodePath(filter.isBlank() ? "*" : filter));

            Map<String, Object> out = new LinkedHashMap<>(common);
            out.put("base_url", OECD_SDMX_PUBLIC_REST);
            out.put("endpoint", endpoint);
            out.put("headers", Map.of(
                    "Accept", OECD_DATA_ACCEPT,
                    "Accept-Language", "en",
                    "User-Agent", "banking-bi/1.0"));
            out.put("query_params", qpIn);
            out.put("set_id", sid);
            out.put("oecd_sdmx_v2", true);
            out.put("oecd_agency", agency);
            out.put("oecd_dataflow", dataflow);
            out.put("oecd_filter_expression", filter);
            return out;
        }

        if (!sid.contains("/")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "OECD očekává set_id ve tvaru 'DATASET/FILTER', 'OECD4|key|ref|measure|freq' nebo 'SDMX2|agency|dataflow|verze|filter'.");
        }
        String[] split = sid.split("/", 2);
        String datasetCode = split[0].trim();
        String filter = split[1].trim();
        if (datasetCode.isBlank() || filter.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný OECD set_id.");
        }

        Map<String, Object> qp = new LinkedHashMap<>();
        qp.put("format", "csvfilewithlabels");
        qp.put("startTime", "2010");
        qp.put("dimensionAtObservation", "AllDimensions");
        // Stejna oprava jako u SDMX2 vetve vyse - 120 tise oriznulo "cele obdobi" na poslednich 120
        // pozorovani.
        qp.put("lastNObservations", 20_000);
        qp.putAll(queryParamsWithDimensionFilters(params));
        filter = replaceLeadingOecdGeoFilter(filter, qp);

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", OECD_BASE_URL_LEGACY);
        out.put("endpoint", "/SDMX-JSON/data/" + datasetCode + "/" + filter + "/all");
        out.put("headers", Map.of("User-Agent", "banking-bi/1.0"));
        out.put("query_params", qp);
        out.put("set_id", sid);
        return out;
    }

    /**
     * The OECD4 catalog indexes series-level candidates as {@code "{datasetKey}/{refArea}/{measure}/
     * .../{freq}"} (e.g. {@code economic_outlook_117/CZE/GDP_USD/_/A}) - a legacy-looking, slash-
     * delimited format distinct from the {@code "OECD4|key|refArea|measure|freq"} pipe format {@link
     * #buildOecd4Offline} otherwise expects. Before this fix, any set_id not already recognized as
     * pipe-format or carrying explicit offline hints fell through to the retired {@code
     * stats.oecd.org} legacy branch and 404'd for every single one of these candidates - even though
     * the exact data already sits in the local offline mirror (confirmed: all 21 dataset keys used by
     * the catalog have a downloaded snapshot). Returns the {@code oecd4_*} query params to route this
     * request to the offline mirror instead, or {@code null} if {@code setId} isn't in this format or
     * no local snapshot exists for its dataset key (in which case the caller falls through to the
     * existing SDMX2/legacy branches unchanged). See the OECD4 transport_failure root-cause diagnosis
     * (2026-07-30).
     *
     * <p>Only {@code REF_AREA}/{@code MEASURE}/{@code FREQ} are extracted positionally (index 1, 2,
     * and the last segment) - the same three dimensions the pipe format alone can express. Datasets
     * with additional required dimensions (e.g. {@code ACTIVITY}/{@code ADJUSTMENT}) are not filtered
     * on those extra dimensions here either, matching the pipe format's existing, already-accepted
     * precision rather than introducing a new gap.
     */
    private Map<String, Object> slashFormatOecd4OfflineParams(String sid, Map<String, Object> incomingQueryParams) {
        if (sid.startsWith("OECD4|") || sid.startsWith("SDMX2|") || !sid.contains("/")) {
            return null;
        }
        String[] parts = sid.split("/", -1);
        if (parts.length < 4) {
            return null;
        }
        String datasetKey = parts[0].trim();
        if (datasetKey.isBlank() || !oecd4BrowseService.hasOfflineSnapshot(datasetKey)) {
            return null;
        }
        String refArea = parts[1].trim().toUpperCase(Locale.ROOT);
        String measure = parts[2].trim().toUpperCase(Locale.ROOT);
        String freq = parts[parts.length - 1].trim().toUpperCase(Locale.ROOT);
        Map<String, Object> qp = new LinkedHashMap<>(incomingQueryParams != null ? incomingQueryParams : Map.of());
        qp.put("oecd4_key", datasetKey);
        qp.put("oecd4_ref_area", refArea);
        qp.put("ref_area", refArea);
        qp.put("oecd4_measure", measure);
        qp.put("measure", measure);
        qp.put("freq", freq);
        return qp;
    }

    private Map<String, Object> buildOecd4Offline(
            Map<String, Object> common, String setId, Map<String, Object> incomingQueryParams) {
        Map<String, Object> qp = new LinkedHashMap<>(incomingQueryParams != null ? incomingQueryParams : Map.of());
        if (setId.startsWith("OECD4|")) {
            String[] parts = setId.split("\\|", -1);
            if (parts.length == 3 && "dataset".equalsIgnoreCase(parts[2])) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "OECD4 dataset-level set_id nelze nacist bez dimenzi.");
            }
            if (parts.length < 5) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatny OECD4 set_id: " + setId);
            }
            qp.put("oecd4_key", parts[1].trim());
            qp.put("oecd4_ref_area", parts[2].trim().toUpperCase(Locale.ROOT));
            qp.put("ref_area", parts[2].trim().toUpperCase(Locale.ROOT));
            qp.put("oecd4_measure", parts[3].trim().toUpperCase(Locale.ROOT));
            qp.put("measure", parts[3].trim().toUpperCase(Locale.ROOT));
            qp.put("freq", parts[4].trim().toUpperCase(Locale.ROOT));
        }
        String requestedGeo = oecdGeoSelection(qp);
        if (!requestedGeo.isBlank()) {
            qp.put("oecd4_ref_area", requestedGeo);
            qp.put("ref_area", requestedGeo);
            qp.put("REF_AREA", requestedGeo);
        }
        qp.put("provider", "oecd4");
        qp.put("oecd_api_mode", "oecd4_offline");

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", "oecd4://local");
        out.put("endpoint", "/offline-preview");
        out.put("headers", Map.of());
        out.put("query_params", qp);
        out.put("set_id", setId);
        out.put("oecd4_offline", true);
        out.put("oecd4_key", stringField(qp, "oecd4_key"));
        out.put("oecd4_ref_area", stringField(qp, "oecd4_ref_area"));
        return out;
    }

    private static boolean isOecd4OfflineParams(Map<String, Object> params) {
        String mode = stringField(params, "oecd_api_mode").toLowerCase(Locale.ROOT);
        String provider = stringField(params, "provider").toLowerCase(Locale.ROOT);
        return "oecd4_offline".equals(mode)
                || "oecd4".equals(provider)
                || !stringField(params, "oecd4_key").isBlank();
    }

    private String translateOecd4SetId(String setId, Map<String, Object> params) {
        String[] parts = setId.split("\\|", -1);
        if (parts.length == 3 && "dataset".equalsIgnoreCase(parts[2])) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "OECD4 dataset-level set_id nelze načíst bez dimenzí.");
        }
        if (parts.length < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný OECD4 set_id: " + setId);
        }
        String key = parts[1].trim();
        String refArea = parts[2].trim().toUpperCase();
        String measure = parts[3].trim().toUpperCase();
        String freq = parts[4].trim().toUpperCase();
        Oecd4BrowseService.Oecd4DatasetMeta ds;
        try {
            ds = oecd4BrowseService.datasetMeta(key);
        } catch (ResponseStatusException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neznámý OECD4 dataset: " + key);
        }
        String filter = refArea + "." + measure + "." + freq;
        return "SDMX2|" + ds.agency() + "|" + ds.dataflow() + "|" + ds.version() + "|" + filter;
    }

    private Map<String, Object> buildEcb(Map<String, Object> common, String setId, Map<String, Object> params) {
        Map<String, Object> qpIn = new LinkedHashMap<>(queryParamsWithDimensionFilters(params));
        String flow = stringField(qpIn, "ecb_flow");
        if (flow.isBlank()) {
            flow = stringField(qpIn, "flowRef");
        }
        String key = stringField(qpIn, "ecb_series_key");
        if (key.isBlank()) {
            key = stringField(qpIn, "seriesKey");
        }

        String sid = setId != null ? setId.trim() : "";
        if (flow.isBlank() || key.isBlank()) {
            EcbAvailabilityService.ParsedCurated curated =
                    EcbAvailabilityService.parseCuratedSetId(sid, params, ecbCuratedCatalog);
            if (curated != null) {
                Map<String, Object> ind = ecbCuratedCatalog.indicatorById(curated.indicatorId());
                if (ind == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neznámý ECB ukazatel.");
                }
                EcbCuratedCatalog.SdmxKey sdmx = ecbCuratedCatalog.sdmxKeyForCountry(ind, curated.country());
                flow = sdmx.flow();
                key = sdmx.key();
                qpIn.put("country", curated.country());
                qpIn.put("ecb_indicator_id", curated.indicatorId());
            } else {
                EcbReference.Parsed ref = EcbReference.parseSetId(sid);
                if (ref == null || !ref.validPreviewTarget()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "ECB náhled vyžaduje set_id ve tvaru FLOW/SERIES nebo ecb:CZ:ukazatel.");
                }
                flow = ref.flowRef();
                key = ref.seriesKey();
            }
        }

        key = replaceEcbRefAreaInKey(flow, key, qpIn);
        Map<String, Object> qp = new LinkedHashMap<>(qpIn);
        qp.put("ecb_flow", flow);
        qp.put("ecb_series_key", key);
        qp.put("flowRef", flow);
        qp.put("seriesKey", key);
        qp.putIfAbsent("format", "csvdata");
        qp.putIfAbsent("detail", "dataonly");
        if (!hasTimeFilter(qp)) {
            // 120 tise oriznulo "cele obdobi" (napr. denni EUR/USD kurz od 1999) na poslednich 120
            // pozorovani, kdyz nikdo vyslovne nezadal rozsah - stejna trida chyby jako u yahoo_finance.
            qp.put("lastNObservations", "20000");
        }

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", "https://data-api.ecb.europa.eu");
        out.put("endpoint", "/service/data/" + flow + "/" + key);
        out.put("headers", Map.of("Accept", "text/csv", "User-Agent", "banking-bi/1.0"));
        out.put("query_params", qp);
        out.put("set_id", sid.isBlank() ? flow + "/" + key : sid);
        out.put("ecb_flow", flow);
        out.put("ecb_series_key", key);
        return out;
    }

    private Map<String, Object> buildData360(Map<String, Object> common, String setId, Map<String, Object> params) {
        Map<String, Object> qp = mergeData360QueryParams(setId, params);
        if (stringField(qp, "DATABASE_ID").isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Pro World Bank Data360 je povinný DATABASE_ID (nebo validní set_id).");
        }
        applyData360RefArea(qp, params);
        qp.putIfAbsent("skip", "0");

        Map<String, Object> out = new LinkedHashMap<>(common);
        out.put("base_url", DATA360_BASE_URL);
        out.put("endpoint", "/data360/data");
        out.put("headers", Map.of("Accept", "application/json", "User-Agent", "banking-bi/1.0"));
        out.put("query_params", qp);
        out.put("set_id", setId);
        out.put("data360_database_id", qp.get("DATABASE_ID"));
        out.put("data360_indicator", qp.get("INDICATOR"));
        return out;
    }

    private static void applyData360RefArea(Map<String, Object> qp, Map<String, Object> params) {
        String existing = data360RefAreaSelection(qp.get("REF_AREA"));
        if (!existing.isBlank()) {
            qp.put("REF_AREA", existing);
            return;
        }

        String candidate = data360RefAreaSelection(
                params.get("data360_country"),
                params.get("country"),
                qp.get("country"),
                params.get("geo"));
        if (candidate.isBlank() && params.get("geo_intent") instanceof Map<?, ?> geoMap) {
            List<String> codes = CatalogGeoIntent.requestedGeoCodes(castMap(geoMap));
            candidate = data360RefAreaSelection(codes);
        }
        if (!candidate.isBlank()) {
            qp.put("REF_AREA", candidate);
        }
    }

    private static String data360RefAreaSelection(Object... rawValues) {
        return String.join(",", geoSelectionValues(rawValues).stream()
                .map(InMemorySourceBuilder::toData360Iso3)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList());
    }

    private static String toData360Iso3(String raw) {
        String iso2 = CatalogGeoIntent.resolveTerritoryToCountryCode(raw);
        if (!iso2.isBlank()) {
            return CatalogCountryIso3Registry.iso3For(iso2);
        }
        String upper = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return CatalogCountryIso3Registry.isKnownIso3(upper) ? upper : "";
    }

    private static Map<String, Object> mergeData360QueryParams(String setId, Map<String, Object> params) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("skip", "0");

        String sidDb = "";
        String sidIndicator = "";
        if (setId != null && setId.contains("|")) {
            String[] split = setId.split("\\|", 2);
            sidDb = split[0].trim();
            sidIndicator = split.length > 1 ? split[1].trim() : "";
        }

        Map<String, Object> qp = queryParamsWithDimensionFilters(params);
        String qpDb = stringField(qp, "DATABASE_ID");
        String qpIndicator = stringField(qp, "INDICATOR");

        if (!sidDb.isBlank()) {
            merged.put("DATABASE_ID", sidDb);
        } else if (!qpDb.isBlank()) {
            merged.put("DATABASE_ID", qpDb);
        }
        if (!sidIndicator.isBlank()) {
            merged.put("INDICATOR", sidIndicator);
        } else if (!qpIndicator.isBlank()) {
            merged.put("INDICATOR", qpIndicator);
        }

        Set<String> allow = Set.of(
                "REF_AREA", "FREQ", "TIME_PERIOD", "timePeriodFrom", "timePeriodTo", "SEX", "AGE",
                "URBANISATION", "COMP_BREAKDOWN_1", "COMP_BREAKDOWN_2", "COMP_BREAKDOWN_3",
                "UNIT_MEASURE", "UNIT_TYPE", "UNIT_MULT", "skip");
        for (Map.Entry<String, Object> entry : qp.entrySet()) {
            String key = data360QueryKey(entry.getKey());
            if ("DATABASE_ID".equals(key) || "INDICATOR".equals(key) || !allow.contains(key)) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            String value = "REF_AREA".equals(key)
                    ? data360RefAreaSelection(entry.getValue())
                    : String.valueOf(entry.getValue()).trim();
            if (!value.isBlank()) {
                merged.put(key, value);
            }
        }
        if (stringField(merged, "INDICATOR").isBlank()) {
            merged.remove("INDICATOR");
        }
        return merged;
    }

    private static String data360QueryKey(Object rawKey) {
        String key = rawKey != null ? String.valueOf(rawKey).trim() : "";
        if (key.isBlank()) {
            return "";
        }
        String low = key.toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (low) {
            case "database_id" -> "DATABASE_ID";
            case "indicator" -> "INDICATOR";
            case "ref_area", "refarea", "geo", "country" -> "REF_AREA";
            case "time_period" -> "TIME_PERIOD";
            case "timeperiodfrom" -> "timePeriodFrom";
            case "timeperiodto" -> "timePeriodTo";
            case "freq" -> "FREQ";
            case "unit_measure" -> "UNIT_MEASURE";
            case "unit_type" -> "UNIT_TYPE";
            case "unit_mult" -> "UNIT_MULT";
            default -> key.contains("_") || key.equals(key.toUpperCase(Locale.ROOT))
                    ? key.toUpperCase(Locale.ROOT)
                    : key;
        };
    }

    private static String urlEncodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<String, Object> queryParams(Map<String, Object> params) {
        Object raw = params.get("query_params");
        if (raw instanceof Map<?, ?> map) {
            return ConnectorHttpSupport.stringMap(map);
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> queryParamsWithDimensionFilters(Map<String, Object> params) {
        Map<String, Object> out = new LinkedHashMap<>(queryParams(params));
        Object raw = params.get("dimension_filters");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim();
                if (key.isBlank() || "indicator_id".equalsIgnoreCase(key)) {
                    continue;
                }
                Object value = entry.getValue();
                if (hasUsableValue(value)) {
                    out.put(key, value);
                }
            }
        }
        return out;
    }

    private static String firstGeoValueFromAny(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = firstGeoValueFromAny(item);
                if (!text.isBlank()) {
                    return text;
                }
            }
            return "";
        }
        String text = String.valueOf(value).trim();
        text = text.replace("[", "").replace("]", "");
        if (text.contains(",")) {
            text = text.substring(0, text.indexOf(',')).trim();
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private static String imfCountrySelection(Map<String, Object> params, Map<String, Object> qp) {
        List<String> values = firstNonEmptyGeoSelection(
                qp.get("COUNTRY"),
                qp.get("REF_AREA"),
                qp.get("geo"),
                qp.get("country"),
                params.get("geo"),
                params.get("country"),
                qp.get("imf_country"),
                params.get("imf_country"));
        List<String> normalized = values.stream()
                .map(InMemorySourceBuilder::normalizeImfCountryCode)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        return String.join("+", normalized);
    }

    private static String oecdGeoSelection(Map<String, Object> qp) {
        List<String> values = firstNonEmptyGeoSelection(
                qp.get("REF_AREA"),
                qp.get("geo"),
                qp.get("country"),
                qp.get("ref_area"),
                qp.get("oecd4_ref_area"));
        List<String> normalized = values.stream()
                .map(InMemorySourceBuilder::normalizeOecdRefAreaCode)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        return String.join("+", normalized);
    }

    private static String replaceLeadingOecdGeoFilter(String filter, Map<String, Object> qp) {
        String requestedGeo = oecdGeoSelection(qp);
        if (requestedGeo.isBlank() || filter == null || filter.isBlank()) {
            return filter;
        }
        int slash = filter.indexOf('/');
        if (slash < 0) {
            return requestedGeo;
        }
        return requestedGeo + filter.substring(slash);
    }

    private static String replaceEcbRefAreaInKey(String flow, String key, Map<String, Object> qp) {
        String requestedGeo = ecbRefAreaSelection(qp);
        if (requestedGeo.isBlank() || flow == null || key == null || key.isBlank()) {
            return key;
        }
        if (!ecbSecondDimensionIsRefArea(flow)) {
            return key;
        }
        String[] parts = key.split("\\.", -1);
        if (parts.length < 2 || parts[1].isBlank()) {
            return key;
        }
        parts[1] = requestedGeo;
        return String.join(".", parts);
    }

    private static String ecbRefAreaSelection(Map<String, Object> qp) {
        List<String> values = firstNonEmptyGeoSelection(
                qp.get("REF_AREA"),
                qp.get("geo"),
                qp.get("country"),
                qp.get("ref_area"));
        if (values.isEmpty()) {
            return "";
        }
        return String.join("+", values.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
    }

    private static boolean ecbSecondDimensionIsRefArea(String flow) {
        String normalized = flow != null ? flow.trim().toUpperCase(Locale.ROOT) : "";
        return Set.of("BLS", "BSI", "CBD", "CBD2", "FM", "ICP", "MIR").contains(normalized);
    }

    private static List<String> firstNonEmptyGeoSelection(Object... rawValues) {
        for (Object raw : rawValues) {
            List<String> values = geoSelectionValues(raw);
            if (!values.isEmpty()) {
                return values;
            }
        }
        return List.of();
    }

    private static List<String> geoSelectionValues(Object... rawValues) {
        List<String> out = new java.util.ArrayList<>();
        for (Object raw : rawValues) {
            collectGeoSelectionValues(raw, out);
        }
        return out;
    }

    private static void collectGeoSelectionValues(Object raw, List<String> out) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectGeoSelectionValues(item, out);
            }
            return;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return;
        }
        text = text.replace("[", "").replace("]", "");
        for (String part : text.split("[,+]")) {
            String code = part.trim().toUpperCase(Locale.ROOT);
            if (!code.isBlank()) {
                out.add(code);
            }
        }
    }

    private static String normalizeImfCountryCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            return "";
        }
        if (code.length() == 2) {
            String iso3 = CatalogCountryIso3Registry.iso3For(code);
            return iso3 != null ? iso3 : code;
        }
        return code;
    }

    private static String normalizeOecdRefAreaCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            return "";
        }
        if (code.length() == 2) {
            String iso3 = CatalogCountryIso3Registry.iso3For(code);
            return iso3 != null ? iso3 : code;
        }
        return code;
    }

    private static boolean needsEurostatDimensionResolution(Map<String, Object> qp) {
        int substantive = 0;
        for (String key : qp.keySet()) {
            String low = key.toLowerCase(Locale.ROOT);
            if ("format".equals(low)
                    || "lang".equals(low)
                    || "query_mode".equals(low)
                    || low.startsWith("lasttime")
                    || low.startsWith("since")
                    || low.startsWith("until")
                    || low.startsWith("start")
                    || low.startsWith("end")
                    || low.endsWith("_label")) {
                continue;
            }
            substantive++;
        }
        return substantive <= 1;
    }

    private static Map<String, Object> eurostatPreviewFiltersApplied(Map<String, Object> qp) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : qp.entrySet()) {
            String key = entry.getKey() != null ? entry.getKey().trim() : "";
            Object value = entry.getValue();
            if (key.isBlank() || value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            String low = key.toLowerCase(Locale.ROOT);
            if ("format".equals(low)
                    || "lang".equals(low)
                    || "query_mode".equals(low)
                    || low.startsWith("lasttime")
                    || low.startsWith("since")
                    || low.startsWith("until")
                    || low.startsWith("start")
                    || low.startsWith("end")
                    || low.endsWith("_label")) {
                continue;
            }
            out.put(key, value);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static boolean hasGeo(Map<String, Object> qp) {
        return hasUsableValue(qp.get("geo")) || hasUsableValue(qp.get("REF_AREA")) || hasUsableValue(qp.get("ref_area"));
    }

    private static String firstPresentGeoKey(Map<String, Object> qp) {
        if (hasUsableValue(qp.get("geo"))) {
            return "geo";
        }
        if (hasUsableValue(qp.get("REF_AREA"))) {
            return "REF_AREA";
        }
        if (hasUsableValue(qp.get("ref_area"))) {
            return "ref_area";
        }
        return "";
    }

    private static String firstGeoValue(Map<String, Object> qp) {
        String key = firstPresentGeoKey(qp);
        if (key.isBlank()) {
            return "";
        }
        Object value = qp.get(key);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = item == null ? "" : String.valueOf(item).trim();
                if (!text.isBlank()) {
                    return text.toUpperCase(Locale.ROOT);
                }
            }
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? "" : text.toUpperCase(Locale.ROOT);
    }

    private static boolean hasUsableValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).trim().isBlank()) {
                    return true;
                }
            }
            return false;
        }
        return !String.valueOf(value).trim().isBlank();
    }

    private static boolean hasTimeFilter(Map<String, Object> qp) {
        for (String key :
                new String[] {"time", "lastTimePeriod", "sinceTimePeriod", "untilTimePeriod", "startPeriod", "endPeriod"}) {
            if (!stringField(qp, key).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return "1".equals(text) || "true".equals(text) || "yes".equals(text) || "on".equals(text);
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

    private static String env(String name) {
        String value = BankIntelEnvVars.get(name);
        return value != null ? value.trim() : "";
    }
}
