package cz.bankintel.sources.imf;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.service.sources.SourceService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** IMF country-first browse — port {@code imf_browser_routes.py}. */
@Service
public class ImfBrowseService {

    private static final String IMF_BROWSE_ROOT = "IMF · zeme a ukazatele";

    private final ImfApiSupport imfApi;
    private final ImfAvailabilityStore availability;
    private final ImfCountryCatalogService countryCatalog;
    private final SourceService sourceService;

    public ImfBrowseService(
            ImfApiSupport imfApi,
            ImfAvailabilityStore availability,
            ImfCountryCatalogService countryCatalog,
            SourceService sourceService) {
        this.imfApi = imfApi;
        this.availability = availability;
        this.countryCatalog = countryCatalog;
        this.sourceService = sourceService;
    }

    public Map<String, Object> getCountries() {
        requireImfApi();
        Map<String, Object> countries = new LinkedHashMap<>();
        for (ImfAvailabilityStore.BrowseCountry c : availability.listBrowseCountries(1)) {
            countries.put(
                    c.code(),
                    Map.of(
                            "name", c.name(),
                            "browse_label", c.browseLabel(),
                            "imf_code", c.imfCode()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("countries", countries);
        out.put("pocet", countries.size());
        out.put("availability_revision", availability.loaded() ? availability.revision() : null);
        return out;
    }

    public Map<String, Object> getBrowseTree() {
        requireImfApi();
        List<Map<String, Object>> countryChildren = new ArrayList<>();
        int totalSets = 0;
        for (ImfAvailabilityStore.BrowseCountry c : availability.listBrowseCountries(1)) {
            totalSets += c.count();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("path", IMF_BROWSE_ROOT + " > " + c.code());
            node.put("name", c.browseLabel());
            node.put("children", List.of());
            node.put("sets", List.of());
            node.put("imf_country", c.code());
            node.put("imf_country_lazy", true);
            node.put("imf_browse_count", c.count());
            countryChildren.add(node);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("path", IMF_BROWSE_ROOT);
        root.put("name", IMF_BROWSE_ROOT);
        root.put("children", countryChildren);
        root.put("sets", List.of());
        root.put(
                "browse_notice",
                "Vyberte zemi nebo regionalni skupinu — zobrazeny jsou jen entity s overenymi daty v katalogu.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(root));
        out.put("total_sets", totalSets);
        out.put("browse_mode", "country_first");
        out.put("availability_revision", availability.revision());
        out.put("imf_sdmx_base", ImfApiSupport.IMF_SDMX_BASE_URL);
        return out;
    }

    public Map<String, Object> getCountryBrowseNode(String country) {
        requireImfApi();
        Map<String, Object> node = buildCountryBrowseNode(country);
        String bc = ImfApiSupport.browseCountryCode(ImfApiSupport.normalizeCountryCode(country));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", bc);
        out.put("country_node", node);
        out.put("availability_revision", availability.revision());
        out.put("available_count", node.get("imf_indicator_count"));
        return out;
    }

    public Map<String, Object> getCountryIndicators(String country, String kategorie) {
        requireImfApi();
        String imfCode = ImfApiSupport.normalizeCountryCode(country);
        String bc = ImfApiSupport.browseCountryCode(imfCode);
        Map<String, Object> catalog = countryCatalog.buildCountryCatalog(imfCode);
        @SuppressWarnings("unchecked")
        Map<String, Object> kategorieOut = new LinkedHashMap<>((Map<String, Object>) catalog.getOrDefault("kategorie", Map.of()));
        if (kategorie != null && !kategorie.isBlank()) {
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : kategorieOut.entrySet()) {
                if (entry.getKey().equals(kategorie) || entry.getKey().equalsIgnoreCase(kategorie)) {
                    filtered.put(entry.getKey(), entry.getValue());
                }
            }
            if (filtered.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Neznama kategorie '" + kategorie + "' pro zemi " + bc + ".");
            }
            kategorieOut = filtered;
        }
        for (Object catObj : kategorieOut.values()) {
            if (!(catObj instanceof Map<?, ?> cat)) {
                continue;
            }
            Object ukObj = cat.get("ukazatele");
            if (!(ukObj instanceof Map<?, ?> ukazatele)) {
                continue;
            }
            for (Map.Entry<?, ?> indEntry : ukazatele.entrySet()) {
                if (!(indEntry.getValue() instanceof Map<?, ?> ind)) {
                    continue;
                }
                String flow = stringOrBlank(ind.get("flow"));
                String indKey = String.valueOf(indEntry.getKey());
                @SuppressWarnings("unchecked")
                Map<String, Object> indMap = (Map<String, Object>) ind;
                indMap.put("data_url", "/api/imf/country/" + bc + "/data/" + flow + "/" + indKey);
            }
        }
        List<Map<String, Object>> datasety = new ArrayList<>();
        for (Map.Entry<String, Object> entry : kategorieOut.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .toList()) {
            if (!(entry.getValue() instanceof Map<?, ?> cat)) {
                continue;
            }
            Object ukObj = cat.get("ukazatele");
            int count = ukObj instanceof Map<?, ?> uk ? uk.size() : 0;
            String flow = entry.getKey();
            if (ukObj instanceof Map<?, ?> uk && !uk.isEmpty()) {
                Object first = uk.values().iterator().next();
                if (first instanceof Map<?, ?> firstInd) {
                    flow = stringOrBlank(firstInd.get("flow"));
                    if (flow.isBlank()) {
                        flow = entry.getKey();
                    }
                }
            }
            datasety.add(Map.of(
                    "key", entry.getKey(),
                    "flow", flow,
                    "label", stringOrBlank(cat.get("nazev")).isBlank() ? entry.getKey() : cat.get("nazev"),
                    "count", count));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zeme", bc);
        out.put("imf_code", imfCode);
        out.put("nazev_zeme", ImfEntityLabels.entityLabel(imfCode, ""));
        out.put("celkem_ukazatelu", catalog.get("celkem_ukazatelu"));
        out.put("kategorie", kategorieOut);
        out.put("catalog_source", catalog.get("catalog_source"));
        out.put(
                "browse_notice",
                catalog.get("celkem_ukazatelu") instanceof Number n && n.intValue() > 0
                        ? "Nejdriv zvolte dataset (WEO, CPI…), pak ukazatel — graf se nacte hned."
                        : "Pro tuto zemi zatim nejsou data — zkuste jinou zemi nebo spustte build katalogu.");
        out.put("datasety", datasety);
        out.put("availability_revision", availability.loaded() ? availability.revision() : null);
        return out;
    }

    public Map<String, Object> getIndicatorCountries(String flow, String indicator) {
        requireAvailability();
        List<Map<String, Object>> rows = availability.countriesForIndicator(flow, indicator);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Indikator " + flow + "/" + indicator + " nema overena data v imf_availability.json.");
        }
        Map<String, Object> sample = rows.get(0);
        Map<String, Object> zeme = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String cc = stringOrBlank(row.get("country"));
            String bc = ImfApiSupport.browseCountryCode(cc);
            zeme.put(
                    bc,
                    Map.of(
                            "nazev_zeme", ImfEntityLabels.entityLabel(cc, stringOrBlank(row.get("country_name"))),
                            "imf_code", ImfApiSupport.normalizeCountryCode(cc),
                            "posledni_hodnota", row.get("posledni_hodnota"),
                            "posledni_datum", row.get("posledni_datum"),
                            "jednotka", stringOrBlank(row.get("jednotka")),
                            "frekvence", stringOrBlank(row.get("frekvence")),
                            "ma_projekce", Boolean.TRUE.equals(row.get("ma_projekce")),
                            "pocet_bodu", row.get("pocet_bodu")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flow", flow);
        out.put("indicator", indicator);
        out.put("nazev", stringOrBlank(sample.get("indicator_name")).isBlank() ? indicator : sample.get("indicator_name"));
        out.put("flow_name", stringOrBlank(sample.get("flow_name")).isBlank() ? flow : sample.get("flow_name"));
        out.put("zeme", zeme);
        out.put("pocet_zemi", zeme.size());
        out.put("availability_revision", availability.revision());
        return out;
    }

    public Map<String, Object> getCountrySeriesData(
            String country, String flow, String indicator, String od, String doParam, String frekvence) {
        requireImfApi();
        String imfCode = ImfApiSupport.normalizeCountryCode(country);
        String bc = ImfApiSupport.browseCountryCode(imfCode);
        Map<String, Object> entry = countryCatalog.resolveSeriesForFetch(imfCode, flow, indicator, frekvence);
        if (entry == null && availability.loaded()) {
            entry = availability.findSeriesEntry(imfCode, flow, indicator);
        }
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rada " + flow + "/" + indicator + " neni pro " + bc + " dostupna.");
        }
        String agency = stringOrBlank(entry.get("agency")).isBlank() ? "IMF.RES" : stringOrBlank(entry.get("agency"));
        String version = stringOrBlank(entry.get("version")).isBlank() ? "1.0.0" : stringOrBlank(entry.get("version"));
        String key = stringOrBlank(entry.get("sdmx_key"));
        if (key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybi sdmx_key — znovu nactete katalog zeme.");
        }
        ImfApiSupport.FetchSeriesResult fetched =
                imfApi.fetchSeriesData(agency, flow, version, key, od, doParam);
        if (fetched.statusCode() != 200 || fetched.rows().isEmpty()) {
            int statusCode = fetched.statusCode() >= 500 || fetched.statusCode() == 0 ? 502 : 404;
            throw new ResponseStatusException(
                    HttpStatus.valueOf(statusCode), "IMF API nevratilo data pro tuto radu.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zeme", bc);
        out.put("imf_code", imfCode);
        out.put("nazev_zeme", ImfEntityLabels.entityLabel(imfCode, ""));
        out.put("flow", flow);
        out.put("flow_name", entry.get("flow_name") != null ? entry.get("flow_name") : flow);
        out.put("indicator", indicator);
        out.put("nazev", stringOrBlank(entry.get("indicator_name")).isBlank() ? indicator : entry.get("indicator_name"));
        out.put("jednotka", stringOrBlank(entry.get("jednotka")));
        out.put("frekvence", stringOrBlank(entry.get("frekvence")).isBlank() ? fetched.meta().get("frekvence") : entry.get("frekvence"));
        out.put("frekvence_label", ImfApiSupport.freqLabelCs(stringOrBlank(out.get("frekvence"))));
        out.put("ma_projekce", Boolean.TRUE.equals(entry.get("ma_projekce")));
        out.put("sdmx_key", key);
        out.put("pocet_zaznamu", fetched.rows().size());
        out.put("data", fetched.rows());
        return out;
    }

    public Map<String, Object> compareCountries(String zeme, String flow, String indicator, String od, String doParam) {
        requireAvailability();
        List<String> kody = new ArrayList<>();
        for (String part : stringOrBlank(zeme).split(",")) {
            if (!part.trim().isBlank()) {
                kody.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        Map<String, Object> sample = null;
        Map<String, Object> vysledky = new LinkedHashMap<>();
        for (String code : kody) {
            String imfCode = ImfApiSupport.normalizeCountryCode(code);
            String bc = ImfApiSupport.browseCountryCode(imfCode);
            Map<String, Object> entry = availability.findSeriesEntry(imfCode, flow, indicator);
            if (entry == null) {
                vysledky.put(
                        bc,
                        Map.of(
                                "nazev_zeme", ImfEntityLabels.entityLabel(imfCode, ""),
                                "data", List.of(),
                                "chyba", "Rada neni v katalogu dostupnosti"));
                continue;
            }
            if (sample == null) {
                sample = entry;
            }
            String agency = stringOrBlank(entry.get("agency")).isBlank() ? "IMF.RES" : stringOrBlank(entry.get("agency"));
            String version = stringOrBlank(entry.get("version")).isBlank() ? "1.0.0" : stringOrBlank(entry.get("version"));
            String key = stringOrBlank(entry.get("sdmx_key"));
            ImfApiSupport.FetchSeriesResult fetched = imfApi.fetchSeriesData(agency, flow, version, key, od, doParam);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nazev_zeme", ImfEntityLabels.entityLabel(imfCode, ""));
            row.put("imf_code", imfCode);
            row.put("data", fetched.statusCode() == 200 ? fetched.rows() : List.of());
            row.put("ma_projekce", Boolean.TRUE.equals(entry.get("ma_projekce")));
            vysledky.put(bc, row);
        }
        if (sample == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Indikator " + flow + "/" + indicator + " neni dostupny pro zadnou z uvedenych zemi.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flow", flow);
        out.put("flow_name", stringOrBlank(sample.get("flow_name")).isBlank() ? flow : sample.get("flow_name"));
        out.put("indicator", indicator);
        out.put("nazev", stringOrBlank(sample.get("indicator_name")).isBlank() ? indicator : sample.get("indicator_name"));
        out.put("jednotka", stringOrBlank(sample.get("jednotka")));
        out.put("frekvence", stringOrBlank(sample.get("frekvence")));
        out.put("zeme", vysledky);
        return out;
    }

    public Map<String, Object> addSource(Map<String, Object> payload) {
        requireAvailability();
        String country = stringOrBlank(payload.get("country"));
        if (country.isBlank()) {
            country = stringOrBlank(payload.get("imf_country"));
        }
        String flow = stringOrBlank(payload.get("flow"));
        if (flow.isBlank()) {
            flow = stringOrBlank(payload.get("imf_flow"));
        }
        String indicator = stringOrBlank(payload.get("indicator"));
        if (indicator.isBlank()) {
            indicator = stringOrBlank(payload.get("imf_indicator"));
        }
        if (country.isBlank() || flow.isBlank() || indicator.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vyzadovano: country, flow, indicator.");
        }
        String imfCode = ImfApiSupport.normalizeCountryCode(country);
        Map<String, Object> entry = availability.findSeriesEntry(imfCode, flow, indicator);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kombinace neni v imf_availability.json.");
        }
        String agency = stringOrBlank(entry.get("agency")).isBlank() ? "IMF.RES" : stringOrBlank(entry.get("agency"));
        String version = stringOrBlank(entry.get("version")).isBlank() ? "1.0.0" : stringOrBlank(entry.get("version"));
        String key = stringOrBlank(entry.get("sdmx_key"));
        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "IMF · "
                    + ImfEntityLabels.entityLabel(imfCode, "")
                    + " · "
                    + stringOrBlank(entry.get("indicator_name"));
        }
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("startPeriod", stringOrBlank(payload.get("startPeriod")).isBlank() ? "1990" : payload.get("startPeriod"));
        queryParams.put("endPeriod", stringOrBlank(payload.get("endPeriod")).isBlank() ? "2035" : payload.get("endPeriod"));
        Integer refresh = toInteger(payload.get("refresh_interval_minutes"));
        if (refresh == null) {
            refresh = 1440;
        }
        Boolean active = toBoolean(payload.get("active"));
        if (active == null) {
            active = true;
        }
        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "imf",
                ImfApiSupport.IMF_SDMX_BASE_URL,
                "/data/dataflow/" + agency + "/" + flow + "/" + version + "/" + key,
                "GET",
                "api_key_header",
                null,
                Map.of(
                        "Accept", "application/vnd.sdmx.data+json;version=2.0.0",
                        "User-Agent", "bankintel-bi/1.0"),
                queryParams,
                refresh,
                active,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);
        return Map.of("id", created.get("id"), "name", displayName);
    }

    private Map<String, Object> buildCountryBrowseNode(String country) {
        String imfCode = ImfApiSupport.normalizeCountryCode(country);
        String browseCode = ImfApiSupport.browseCountryCode(imfCode);
        Map<String, Object> catalog = countryCatalog.buildCountryCatalog(imfCode);
        @SuppressWarnings("unchecked")
        Map<String, Object> kategorie = (Map<String, Object>) catalog.getOrDefault("kategorie", Map.of());
        List<Map<String, Object>> children = new ArrayList<>();
        int totalSets = 0;
        for (Map.Entry<String, Object> entry : kategorie.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .toList()) {
            if (!(entry.getValue() instanceof Map<?, ?> cat)) {
                continue;
            }
            Object ukObj = cat.get("ukazatele");
            if (!(ukObj instanceof Map<?, ?> ukazatele)) {
                continue;
            }
            List<Map<String, Object>> sets = new ArrayList<>();
            for (Map.Entry<?, ?> indEntry : ukazatele.entrySet()) {
                if (!(indEntry.getValue() instanceof Map<?, ?> ind)) {
                    continue;
                }
                String indKey = String.valueOf(indEntry.getKey());
                String humanName = stringOrBlank(ind.get("nazev"));
                if (humanName.isBlank()) {
                    humanName = indKey;
                }
                sets.add(browseSetEntry(imfCode, browseCode, indKey, ind, humanName));
            }
            sets.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
            totalSets += sets.size();
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("path", IMF_BROWSE_ROOT + " > " + browseCode + " > " + entry.getKey());
            child.put("name", entry.getKey());
            child.put("children", List.of());
            child.put("sets", sets);
            child.put("imf_browse_count", sets.size());
            children.add(child);
        }
        String notice = totalSets == 0
                ? "IMF pro tuto zemi/skupinu v overenem katalogu zatim nevratilo zadne rady."
                : "Ukazatele maji lidske nazvy. Volitelna dimenze je jen frekvence (Rocne / Ctvrtletne).";
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", IMF_BROWSE_ROOT + " > " + browseCode);
        node.put("name", ImfEntityLabels.formatBrowseEntity(imfCode, ""));
        node.put("children", children);
        node.put("sets", List.of());
        node.put("imf_country", browseCode);
        node.put("browse_notice", notice);
        node.put("imf_indicator_count", totalSets);
        node.put("catalog_source", catalog.get("catalog_source"));
        return node;
    }

    private static Map<String, Object> browseSetEntry(
            String imfCode, String browseCode, String indKey, Map<?, ?> ind, String humanName) {
        String flow = stringOrBlank(ind.get("flow"));
        String agency = stringOrBlank(ind.get("agency"));
        if (agency.isBlank()) {
            agency = "IMF.RES";
        }
        String version = stringOrBlank(ind.get("version"));
        if (version.isBlank()) {
            version = "9.0.0";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set_id", "IMF|" + agency + "|" + flow + "|" + version + "|" + stringOrBlank(ind.get("sdmx_key")));
        out.put("name", humanName);
        out.put("imf_indicator_name", humanName);
        out.put("imf_indicator_code", indKey);
        out.put("imf_unit", stringOrBlank(ind.get("jednotka")));
        out.put("kind", "selection");
        out.put("item_kind", "selection");
        out.put("imf_country", browseCode);
        out.put("imf_flow", flow);
        out.put("imf_indicator", indKey);
        out.put("imf_has_projections", Boolean.TRUE.equals(ind.get("ma_projekce")));
        out.put("frekvence", stringOrBlank(ind.get("frekvence")).isBlank() ? "A" : ind.get("frekvence"));
        out.put("frekvence_label", ind.get("frekvence_label"));
        out.put(
                "query_params",
                Map.of(
                        "imf_country", browseCode,
                        "imf_flow", flow,
                        "imf_indicator", indKey,
                        "imf_frekvence", stringOrBlank(ind.get("frekvence")).isBlank() ? "A" : ind.get("frekvence")));
        out.put("territory", browseCode);
        out.put("period", stringOrBlank(ind.get("frekvence_label")).isBlank() ? ind.get("frekvence") : ind.get("frekvence_label"));
        return out;
    }

    private void requireImfApi() {
        if (!imfApi.configured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "IMF_API_KEY neni nastaven. Doplnite dev.local.env a spustte apply-dev-env.ps1.");
        }
    }

    private void requireAvailability() {
        requireImfApi();
        if (!availability.loaded()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Chybi backend/data/imf_availability.json — spustte python scripts/build_imf_availability.py.");
        }
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value).trim()) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> null;
        };
    }
}
