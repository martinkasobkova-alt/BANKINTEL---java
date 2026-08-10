package cz.bankintel.sources.commodities;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelDataPaths;
import cz.bankintel.util.BankIntelEnvVars;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * World Bank CMO Pink Sheet + prognózy — port {@code services/worldbank_commodities.py}.
 */
@Slf4j
@Service
public class WorldbankCommoditiesService {

    public static final String DEFAULT_PINK_SHEET_MONTHLY_URL =
            "https://thedocs.worldbank.org/en/doc/74e8be41ceb20fa0da750cda2f6b9e4e-0050012026"
                    + "/related/CMO-Historical-Data-Monthly.xlsx";
    public static final String DEFAULT_CMO_FORECASTS_URL =
            "https://thedocs.worldbank.org/en/doc/f3138644a1e8e2bb631399ae11d6c408-0050012026"
                    + "/related/CMO-April-2026-Forecasts.xlsx";

    private static final Map<String, String> CMO_HUB_LINKS = Map.of(
            "commodity_markets", "https://www.worldbank.org/en/research/commodity-markets",
            "price_forecasts", "https://www.worldbank.org/en/research/commodity-markets/price-forecasts",
            "pink_sheet_pdf",
                    "https://thedocs.worldbank.org/en/doc/74e8be41ceb20fa0da750cda2f6b9e4e-0050012026"
                            + "/related/CMO-Pink-Sheet-May-2026.pdf",
            "terms_of_use", "https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets");

    private static final List<String> BIS_COMMODITY_KEYWORDS = List.of("commod", "world market price");

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final HttpClient httpClient;

    public WorldbankCommoditiesService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataDir = BankIntelDataPaths.dataDir();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    }

    @PostConstruct
    void logStartup() {
        log.info("Worldbank commodities data dir={}", dataDir.toAbsolutePath().normalize());
    }

    public void ensureCommoditiesCache() {
        ensureCommoditiesCache(168.0);
    }

    public Map<String, Object> ensureCommoditiesCache(double maxAgeHours) {
        Map<String, Object> catalog = loadPinkSheetCatalog();
        Map<String, Object> meta = loadMeta();
        boolean stale = true;
        Object series = catalog.get("series");
        if (series instanceof List<?> list && !list.isEmpty() && meta.get("refreshed_at") != null) {
            try {
                Instant ts = Instant.parse(String.valueOf(meta.get("refreshed_at")).replace("Z", "+00:00"));
                double ageH = Duration.between(ts, Instant.now()).toSeconds() / 3600.0;
                stale = ageH > maxAgeHours;
            } catch (Exception ex) {
                stale = true;
            }
        }
        if (!(series instanceof List<?> list && !list.isEmpty()) || stale) {
            return refreshAllFromUrls(null, null);
        }
        return meta;
    }

    public Map<String, Object> buildHubPayload() {
        Map<String, Object> meta = loadMeta();
        Map<String, Object> catalog = loadPinkSheetCatalog();
        Map<String, Object> forecasts = loadForecasts();
        List<Map<String, Object>> imfRows = imfCommodityRows();
        List<Map<String, Object>> bisRows = bisCommodityRows();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("title", "Komodity");
        out.put(
                "description_cs",
                "Ceny komodit z World Bank Pink Sheet (CMO), prognózy CMO, doplňkově indexy IMF (WEO). "
                        + "BIS nemá samostatný tok cen komodit — použijte obecný katalog BIS. "
                        + "Oficiální PDF/XLS také na webu World Bank.");
        out.put("links", CMO_HUB_LINKS);
        out.put("meta", meta);
        out.put(
                "stats",
                Map.of(
                        "pink_sheet_series", toInt(catalog.get("series_count")),
                        "cmo_forecast_items", toInt(forecasts.get("item_count")),
                        "imf_commodity_indices", imfRows.size(),
                        "bis_commodity_series", bisRows.size()));
        out.put("imf_commodity_indices", imfRows);
        out.put("bis_commodity_series", bisRows);
        out.put(
                "bis_commodity_note_cs",
                "BIS v aplikaci neobsahuje samostatný datový tok pro světové ceny komodit. "
                        + "Pro komodity použijte Pink Sheet nebo IMF indexy; obecné BIS statistiky najdete v katalogu BIS.");
        out.put("pink_sheet_ready", catalog.get("series") instanceof List<?> s && !s.isEmpty());
        out.put("forecasts_ready", forecasts.get("items") instanceof List<?> i && !i.isEmpty());
        return out;
    }

    public Map<String, Object> buildCombinedCatalogTree() {
        Map<String, Object> pink = buildPinkSheetTree();
        Map<String, Object> fcst = buildForecastsTree();
        List<Map<String, Object>> imfRows = imfCommodityRows();
        List<Map<String, Object>> bisRows = bisCommodityRows();
        Map<String, Object> meta = loadMeta();

        List<Map<String, Object>> supplementalSets = new ArrayList<>();
        for (Map<String, Object> r : imfRows) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("kind", "set");
            Map<String, Object> qp = new LinkedHashMap<>();
            qp.put("imf_country", r.get("imf_country"));
            qp.put("imf_flow", r.get("imf_flow"));
            qp.put("imf_indicator", r.get("imf_indicator"));
            row.put("query_params", qp);
            supplementalSets.add(row);
        }
        for (Map<String, Object> r : bisRows) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("kind", "set");
            supplementalSets.add(row);
        }

        List<Map<String, Object>> children = new ArrayList<>();
        children.addAll(asMapList(pink.get("categories")));
        children.addAll(asMapList(fcst.get("categories")));
        children.add(categoryNode(
                "IMF — commodity price indices (WEO, G001)",
                "Komodity > IMF",
                supplementalSets.subList(0, imfRows.size())));
        if (!bisRows.isEmpty()) {
            children.add(categoryNode(
                    "BIS — commodity world market prices",
                    "Komodity > BIS",
                    supplementalSets.subList(imfRows.size(), supplementalSets.size())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("meta", meta);
        out.put("pink_sheet", pink);
        out.put("forecasts", fcst);
        out.put(
                "bis_commodity_note_cs",
                bisRows.isEmpty()
                        ? "BIS nemá samostatný tok cen komodit — sekce se zobrazí až po indexaci komoditních řad."
                        : "");
        out.put(
                "categories",
                List.of(categoryNodeWithChildren("Komodity", "Komodity", children)));
        return out;
    }

    public Map<String, Object> buildPinkSheetTree() {
        Map<String, Object> catalog = loadPinkSheetCatalog();
        List<Map<String, Object>> series = asMapList(catalog.get("series"));
        Map<String, List<Map<String, Object>>> byCat = new LinkedHashMap<>();
        for (Map<String, Object> row : series) {
            String cat = stringOrBlank(row.get("category"));
            if (cat.isBlank()) {
                cat = "Other";
            }
            byCat.computeIfAbsent(cat, ignored -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        List<String> cats = new ArrayList<>(byCat.keySet());
        cats.sort(String.CASE_INSENSITIVE_ORDER);
        for (String cat : cats) {
            List<Map<String, Object>> setsOut = new ArrayList<>();
            List<Map<String, Object>> catSeries = new ArrayList<>(byCat.get(cat));
            catSeries.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
            for (Map<String, Object> s : catSeries) {
                Map<String, Object> set = new LinkedHashMap<>();
                set.put("set_id", s.get("set_id"));
                set.put("name", s.get("name"));
                set.put("full_path", s.get("full_path"));
                set.put("kind", "set");
                set.put("item_kind", "pink_sheet_actual");
                set.put("unit", s.get("unit"));
                set.put("category", cat);
                set.put("source_type", "worldbank_pink_sheet");
                set.put("query_params", Map.of("kind", "actual"));
                setsOut.add(set);
            }
            categories.add(categoryNode(cat, "Pink Sheet > " + cat, setsOut));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog_id", "worldbank_pink_sheet");
        out.put("label", "World Bank Pink Sheet");
        out.put("updated_on_label", catalog.get("updated_on_label"));
        out.put("series_count", toInt(catalog.get("series_count"), series.size()));
        out.put(
                "categories",
                List.of(categoryNodeWithChildren("Pink Sheet", "Pink Sheet", categories)));
        return out;
    }

    public Map<String, Object> buildForecastsTree() {
        Map<String, Object> data = loadForecasts();
        List<Map<String, Object>> items = asMapList(data.get("items"));
        Map<String, List<Map<String, Object>>> byCat = new LinkedHashMap<>();
        for (Map<String, Object> row : items) {
            String cat = stringOrBlank(row.get("category"));
            if (cat.isBlank()) {
                cat = "Forecasts";
            }
            byCat.computeIfAbsent(cat, ignored -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        List<String> cats = new ArrayList<>(byCat.keySet());
        cats.sort(String.CASE_INSENSITIVE_ORDER);
        for (String cat : cats) {
            List<Map<String, Object>> setsOut = new ArrayList<>();
            List<Map<String, Object>> catItems = new ArrayList<>(byCat.get(cat));
            catItems.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
            for (Map<String, Object> s : catItems) {
                List<String> forecastYears = asMapList(s.get("values")).stream()
                        .map(v -> stringOrBlank(v.get("year")))
                        .filter(v -> !v.isBlank())
                        .toList();
                Map<String, Object> set = new LinkedHashMap<>();
                set.put("set_id", s.get("set_id"));
                set.put("name", s.get("name"));
                set.put("full_path", s.get("full_path"));
                set.put("kind", "set");
                set.put("item_kind", "cmo_forecast");
                set.put("unit", s.get("unit"));
                set.put("category", cat);
                set.put("source_type", "worldbank_pink_sheet");
                set.put("query_params", Map.of("kind", "forecast"));
                set.put("forecast_years", forecastYears);
                setsOut.add(set);
            }
            categories.add(categoryNode(cat, "CMO Forecast > " + cat, setsOut));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog_id", "worldbank_cmo_forecast");
        out.put("label", "World Bank CMO Forecasts");
        out.put("item_count", toInt(data.get("item_count"), items.size()));
        out.put(
                "categories",
                List.of(categoryNodeWithChildren("CMO Forecast", "CMO Forecast", categories)));
        return out;
    }

    public Map<String, Object> buildPreview(String setId, String kind) {
        String sid = stringOrBlank(setId);
        String k = stringOrBlank(kind);
        if (k.isBlank()) {
            k = "actual";
        }
        k = k.toLowerCase(Locale.ROOT);

        if ("forecast".equals(k) || sid.startsWith("FCST|")) {
            Map<String, Object> item = forecastItemById(sid);
            if (item == null) {
                return null;
            }
            List<Map<String, Object>> records = recordsFromForecast(sid);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("source_type", "worldbank_cmo_forecast");
            out.put("set_id", sid);
            out.put("name", item.get("name"));
            out.put("unit", item.get("unit"));
            out.put("unit_label_cs", WorldbankPinkSheetXlsxParser.unitLabelCs(stringOrBlank(item.get("unit"))));
            out.put("chart_frequency", "Y");
            out.put("chart_frequency_label", "Roční");
            out.put("kind", "forecast");
            out.put("records", records);
            out.put("record_count", records.size());
            return out;
        }

        Map<String, Object> meta = pinkSheetSeriesById(sid);
        if (meta == null) {
            return null;
        }
        String resolvedSid = firstNonBlank(stringOrBlank(meta.get("set_id")), sid);
        List<Map<String, Object>> records = recordsFromPinkSheet(sid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source_type", "worldbank_pink_sheet");
        out.put("set_id", resolvedSid);
        if (!resolvedSid.equals(sid)) {
            out.put("requested_set_id", sid);
        }
        out.put("name", meta.get("name"));
        out.put("unit", meta.get("unit"));
        out.put("unit_label_cs", WorldbankPinkSheetXlsxParser.unitLabelCs(stringOrBlank(meta.get("unit"))));
        out.put("chart_frequency", "M");
        out.put("chart_frequency_label", "Měsíční");
        out.put("kind", "actual");
        out.put("records", records);
        out.put("record_count", records.size());
        return out;
    }

    public Map<String, Object> refreshAllFromUrls(String pinkSheetUrl, String forecastsUrl) {
        String psUrl = firstNonBlank(
                pinkSheetUrl,
                BankIntelEnvVars.get("WB_PINK_SHEET_MONTHLY_URL"),
                DEFAULT_PINK_SHEET_MONTHLY_URL);
        String fcUrl = firstNonBlank(
                forecastsUrl,
                BankIntelEnvVars.get("WB_CMO_FORECASTS_URL"),
                DEFAULT_CMO_FORECASTS_URL);
        long t0 = System.nanoTime();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> pinkParsed = null;
        Map<String, Object> fcParsed = null;

        try {
            pinkParsed = WorldbankPinkSheetXlsxParser.parsePinkSheetMonthlyXlsx(fetchBytes(psUrl));
            savePinkSheetBundle(pinkParsed);
        } catch (Exception ex) {
            warnings.add("Pink Sheet: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        try {
            fcParsed = WorldbankPinkSheetXlsxParser.parseCmoForecastsXlsx(fetchBytes(fcUrl));
            saveForecastsBundle(fcParsed);
        } catch (Exception ex) {
            warnings.add("CMO Forecasts: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", 1);
        meta.put("refreshed_at", Instant.now().toString());
        meta.put("pink_sheet_url", psUrl);
        meta.put("forecasts_url", fcUrl);
        meta.put("pink_sheet_series_count", pinkParsed != null ? pinkParsed.get("series_count") : null);
        meta.put("forecast_item_count", fcParsed != null ? fcParsed.get("item_count") : null);
        meta.put("warnings", warnings);
        meta.put("elapsed_ms", (System.nanoTime() - t0) / 1_000_000L);
        try {
            saveMeta(meta);
        } catch (IOException ex) {
            warnings.add("Meta save: " + ex.getMessage());
            meta.put("meta_save_error", ex.getMessage());
        }
        return meta;
    }

    public Map<String, Object> pinkSheetSeriesById(String setId) {
        String sid = stringOrBlank(setId);
        List<Map<String, Object>> series = asMapList(loadPinkSheetCatalog().get("series"));
        Map<String, Object> direct = findPinkSheetSeriesBySetId(series, sid);
        if (direct != null) {
            return direct;
        }
        String legacyTitle = legacyCommodityTitleById(sid);
        return legacyTitle.isBlank() ? null : findPinkSheetSeriesByName(series, legacyTitle);
    }

    public Map<String, Object> forecastItemById(String setId) {
        String sid = stringOrBlank(setId);
        for (Map<String, Object> row : asMapList(loadForecasts().get("items"))) {
            if (sid.equals(stringOrBlank(row.get("set_id")))) {
                return row;
            }
        }
        return null;
    }

    public List<Map<String, Object>> recordsFromPinkSheet(String setId) {
        Map<String, Object> meta = pinkSheetSeriesById(setId);
        if (meta == null) {
            return List.of();
        }
        String resolvedSetId = firstNonBlank(stringOrBlank(meta.get("set_id")), stringOrBlank(setId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> obs : pinkSheetObservationsFor(resolvedSetId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", obs.get("date"));
            row.put("period", obs.get("period"));
            row.put("value", obs.get("value"));
            row.put("unit", meta.get("unit"));
            row.put("commodity_code", resolvedSetId);
            if (!resolvedSetId.equals(stringOrBlank(setId))) {
                row.put("requested_commodity_code", stringOrBlank(setId));
            }
            row.put("commodity_name", meta.get("name"));
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> recordsFromForecast(String setId) {
        Map<String, Object> item = forecastItemById(setId);
        if (item == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : asMapList(item.get("values"))) {
            String year = stringOrBlank(v.get("year"));
            Object val = v.get("value");
            if (year.isBlank() || val == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", year + "-01-01");
            row.put("period", year);
            row.put("value", Double.parseDouble(String.valueOf(val)));
            row.put("unit", item.get("unit"));
            row.put("unit_label_cs", WorldbankPinkSheetXlsxParser.unitLabelCs(stringOrBlank(item.get("unit"))));
            row.put("commodity_name", item.get("name"));
            row.put("is_forecast", true);
            row.put("value_kind", "price_level");
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> stringOrBlank(r.get("period"))));
        return out;
    }

    public Map<String, Object> loadPinkSheetCatalog() {
        return readJson(dataDir.resolve("worldbank_pink_sheet_catalog.json"), defaultCatalog());
    }

    public Map<String, Object> loadForecasts() {
        return readJson(dataDir.resolve("worldbank_cmo_forecasts.json"), Map.of("items", List.of(), "item_count", 0));
    }

    public Map<String, Object> loadMeta() {
        return readJson(dataDir.resolve("worldbank_commodities_meta.json"), Map.of());
    }

    private List<Map<String, Object>> pinkSheetObservationsFor(String setId) {
        Map<String, Object> data = readJson(dataDir.resolve("worldbank_pink_sheet_observations.json"), Map.of());
        Object obs = data.get("observations");
        if (!(obs instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object series = map.get(stringOrBlank(setId));
        return asMapList(series);
    }

    private Map<String, Object> findPinkSheetSeriesBySetId(List<Map<String, Object>> series, String setId) {
        if (setId.isBlank()) {
            return null;
        }
        for (Map<String, Object> row : series) {
            if (setId.equalsIgnoreCase(stringOrBlank(row.get("set_id")))) {
                return row;
            }
        }
        return null;
    }

    private Map<String, Object> findPinkSheetSeriesByName(List<Map<String, Object>> series, String title) {
        String wanted = normalizeCommodityName(title);
        if (wanted.isBlank()) {
            return null;
        }
        for (Map<String, Object> row : series) {
            if (wanted.equals(normalizeCommodityName(row.get("name")))) {
                return row;
            }
        }
        return null;
    }

    private String legacyCommodityTitleById(String setId) {
        if (setId.isBlank()) {
            return "";
        }
        Path path = BankIntelDataPaths.catalogSearchMetadataDir().resolve("commodities.jsonl");
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(path)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = objectMapper.readValue(line, new TypeReference<>() {});
                String legacyId = firstNonBlank(
                        stringOrBlank(row.get("series_id")),
                        stringOrBlank(row.get("dataset_id")),
                        stringOrBlank(row.get("dataset")));
                if (!setId.equalsIgnoreCase(legacyId)) {
                    continue;
                }
                return firstNonBlank(
                        stringOrBlank(row.get("title_original")),
                        stringOrBlank(row.get("original_title")),
                        stringOrBlank(row.get("human_label_en")),
                        stringOrBlank(row.get("canonical_title_en")),
                        stringOrBlank(row.get("human_label_cs")),
                        stringOrBlank(row.get("canonical_title_cs")));
            }
        } catch (Exception ex) {
            log.debug("Pink Sheet legacy id lookup skipped for {}: {}", setId, ex.getMessage());
        }
        return "";
    }

    private static String normalizeCommodityName(Object value) {
        return stringOrBlank(value)
                .toLowerCase(Locale.ROOT)
                .replace("*", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private void savePinkSheetBundle(Map<String, Object> parsed) throws IOException {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("version", parsed.getOrDefault("version", 1));
        catalog.put("generated_at", parsed.get("generated_at"));
        catalog.put("updated_on_label", parsed.get("updated_on_label"));
        catalog.put("series_count", parsed.getOrDefault("series_count", 0));
        catalog.put("series", parsed.getOrDefault("series", List.of()));

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("version", parsed.getOrDefault("version", 1));
        obs.put("generated_at", parsed.get("generated_at"));
        obs.put("observations", parsed.getOrDefault("observations", Map.of()));

        writeJson(dataDir.resolve("worldbank_pink_sheet_catalog.json"), catalog);
        writeJson(dataDir.resolve("worldbank_pink_sheet_observations.json"), obs);
    }

    private void saveForecastsBundle(Map<String, Object> parsed) throws IOException {
        writeJson(dataDir.resolve("worldbank_cmo_forecasts.json"), parsed);
    }

    private void saveMeta(Map<String, Object> meta) throws IOException {
        writeJson(dataDir.resolve("worldbank_commodities_meta.json"), meta);
    }

    private byte[] fetchBytes(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", "BankIntel-Commodities/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private List<Map<String, Object>> imfCommodityRows() {
        Path path = dataDir.resolve("imf_availability.json");
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            Map<String, Object> data = readJson(path, Map.of());
            Object countries = data.get("countries");
            if (!(countries instanceof Map<?, ?> map)) {
                return List.of();
            }
            Object g001 = map.get("G001");
            if (!(g001 instanceof List<?> rows)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object raw : rows) {
                if (!(raw instanceof Map<?, ?> r)) {
                    continue;
                }
                Map<String, Object> row = toStringObjectMap(r);
                String indName = stringOrBlank(row.get("indicator_name"));
                if (!indName.contains("Commodity price index")) {
                    continue;
                }
                String flow = stringOrBlank(row.get("flow"));
                if (flow.isBlank()) {
                    flow = "WEO";
                }
                String ind = stringOrBlank(row.get("indicator"));
                String sid = flow + "/" + ind;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("set_id", sid);
                item.put("name", indName);
                item.put("full_path", "IMF " + flow + " > " + indName);
                item.put("kind", "set");
                item.put("item_kind", "imf_commodity_index");
                item.put("source_type", "imf");
                item.put("imf_country", "G001");
                item.put("imf_flow", flow);
                item.put("imf_indicator", ind);
                item.put("catalog_path", "/imf/browse-tree");
                out.add(item);
            }
            out.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
            return out;
        } catch (Exception ex) {
            log.debug("IMF commodity rows: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> bisCommodityRows() {
        Path indexDir = dataDir.resolve("bis_series_index");
        if (!Files.isDirectory(indexDir)) {
            return List.of();
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            List<Path> files;
            try (var stream = Files.list(indexDir)) {
                files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            }
            for (Path file : files) {
                Map<String, Object> data = readJson(file, Map.of());
                for (Map<String, Object> leaf : asMapList(data.get("leaves"))) {
                    maybeAddBisCommodity(leaf, seen, out);
                }
            }
        } catch (Exception ex) {
            log.debug("BIS commodity rows: {}", ex.getMessage());
        }
        out.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    private void maybeAddBisCommodity(Map<String, Object> leaf, Set<String> seen, List<Map<String, Object>> out) {
        String sid = firstNonBlank(
                stringOrBlank(leaf.get("bis_catalog_set_id")),
                stringOrBlank(leaf.get("set_id")),
                stringOrBlank(leaf.get("id")));
        if (sid.isBlank() || seen.contains(sid)) {
            return;
        }
        String name = firstNonBlank(stringOrBlank(leaf.get("name")), sid);
        String fullPath = firstNonBlank(stringOrBlank(leaf.get("full_path")), "BIS > " + name);
        String hay = String.join(
                        " ",
                        name,
                        fullPath,
                        stringOrBlank(leaf.get("dataset_name")),
                        stringOrBlank(leaf.get("bis_series_key")),
                        stringOrBlank(leaf.get("ref_area")))
                .toLowerCase(Locale.ROOT);
        boolean match = BIS_COMMODITY_KEYWORDS.stream().anyMatch(hay::contains);
        if (!match) {
            return;
        }
        seen.add(sid);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("set_id", sid);
        row.put("name", name);
        row.put("full_path", fullPath);
        row.put("kind", "set");
        row.put("item_kind", "bis_commodity");
        row.put("source_type", "bis");
        row.put("catalog_path", "/bis/catalog");
        row.put("bis_dataflow", leaf.get("bis_dataflow"));
        row.put("bis_series_key", leaf.get("bis_series_key"));
        out.add(row);
    }

    private Map<String, Object> readJson(Path path, Map<String, Object> fallback) {
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>(fallback);
        }
        try {
            return objectMapper.readValue(Files.readString(path), new TypeReference<>() {});
        } catch (Exception ex) {
            log.warn("Nelze načíst {}: {}", path.getFileName(), ex.getMessage());
            return new LinkedHashMap<>(fallback);
        }
    }

    private void writeJson(Path path, Map<String, Object> payload) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), payload);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static Map<String, Object> defaultCatalog() {
        return Map.of("series", List.of(), "series_count", 0);
    }

    private static Map<String, Object> categoryNode(String name, String path, List<Map<String, Object>> sets) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", name);
        node.put("path", path);
        node.put("children", List.of());
        node.put("sets", sets);
        return node;
    }

    private static Map<String, Object> categoryNodeWithChildren(
            String name, String path, List<Map<String, Object>> children) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", name);
        node.put("path", path);
        node.put("children", children);
        node.put("sets", List.of());
        return node;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(toStringObjectMap(map));
            }
        }
        return out;
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static int toInt(Object value) {
        return toInt(value, 0);
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }
}
