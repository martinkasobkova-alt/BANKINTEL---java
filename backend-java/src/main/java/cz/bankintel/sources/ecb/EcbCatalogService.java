package cz.bankintel.sources.ecb;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.service.sources.SourceService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ECB kurátorovaný katalog — browse strom, snapshot, porovnání zemí.
 * Port {@code ecb_catalog_routes.py} + služby {@code services/ecb_*}.
 */
@Service
@RequiredArgsConstructor
public class EcbCatalogService {

    private final EcbCuratedCatalog catalog;
    private final EcbAvailabilityService availability;
    private final EcbSeriesAvailabilityService seriesAvailability;
    private final EcbApiClient apiClient;
    private final SourceService sourceService;

    public Map<String, Object> getBrowseTree() {
        List<Map<String, Object>> countryChildren = new ArrayList<>();
        int totalSets = 0;
        for (String code : catalog.sortedCountryCodes()) {
            Map<String, Object> info = catalog.countryInfo(code);
            int count = availability.availableIndicators(code).size();
            totalSets += count;
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("path", EcbCuratedCatalog.ECB_BROWSE_ROOT + " > " + code);
            child.put("name", info.get("name") + " (" + code + ")");
            child.put("children", List.of());
            child.put("sets", List.of());
            child.put("ecb_country", code);
            child.put("ecb_country_lazy", true);
            countryChildren.add(child);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("path", EcbCuratedCatalog.ECB_BROWSE_ROOT);
        root.put("name", EcbCuratedCatalog.ECB_BROWSE_ROOT);
        root.put("children", countryChildren);
        root.put("sets", List.of());
        root.put(
                "browse_notice",
                "Rozbalte zemi — zobrazí se kurátorované ukazatele. Není potřeba skládat SDMX dimenze ručně. "
                        + "Pro ~211k ověřených řad použijte katalog ECB 2.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(root));
        out.put("total_sets", totalSets);
        out.put("browse_mode", "country_first");
        out.put("availability_revision", combinedAvailabilityRevision());
        out.put("ecb_availability_filtered", availability.filteringEnabled());
        out.put("ecb_discovery_browse_enabled", seriesAvailability.discoveryBrowseEnabled());
        return out;
    }

    public Map<String, Object> getCountryBrowseNode(String country) {
        String c = catalog.validateCountryCode(country);
        Map<String, Object> node = buildCountryBrowseNode(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", c);
        out.put("country_node", node);
        out.put("availability_revision", combinedAvailabilityRevision());
        out.put("available_count", availability.availableIndicators(c).size());
        return out;
    }

    public Map<String, Object> getCountryDiscoverySeries(String country, int offset, int limit) {
        String c = catalog.validateCountryCode(country);
        if (!seriesAvailability.discoveryBrowseEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Discovery browse není aktivní — spusťte build_ecb_series_availability.py.");
        }
        List<String> all = seriesAvailability.getAvailableSeries(c);
        int total = all.size();
        int off = Math.max(0, offset);
        int lim = Math.max(1, Math.min(2000, limit));
        List<String> slice = all.subList(Math.min(off, total), Math.min(off + lim, total));
        List<Map<String, Object>> rows = seriesAvailability.rowsFromSetIds(slice, c, "MIX", "");
        boolean capped = total > seriesAvailability.discoveryCap();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", c);
        out.put("rows", rows);
        out.put("count", rows.size());
        out.put("total", total);
        out.put("offset", off);
        out.put("limit", lim);
        out.put("capped", capped);
        out.put("availability_revision", seriesAvailability.availabilityRevision());
        out.put("ecb_browse_source", "discovery_availability");
        return out;
    }

    public Map<String, Object> listCountries(boolean euroOnly, boolean euOnly) {
        Map<String, Map<String, Object>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : catalog.getCountries().entrySet()) {
            Map<String, Object> info = entry.getValue();
            if (euroOnly && !Boolean.TRUE.equals(info.get("euro"))) {
                continue;
            }
            if (euOnly && !Boolean.TRUE.equals(info.get("eu"))) {
                continue;
            }
            filtered.put(entry.getKey(), info);
        }
        Map<String, Object> zeme = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : filtered.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(entry.getValue());
            row.put("dostupne_ukazatele", availability.availableIndicators(entry.getKey()).size());
            zeme.put(entry.getKey(), row);
        }
        return Map.of("celkem_zemi", zeme.size(), "zeme", zeme);
    }

    public Map<String, Object> listCategories() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> cat : catalog.getCategories().entrySet()) {
            Map<String, Object> indicators = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> ind : catalog.getIndicators().entrySet()) {
                if (cat.getKey().equals(String.valueOf(ind.getValue().get("cat")))) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("nazev", ind.getValue().get("name"));
                    meta.put("frekvence", ind.getValue().get("freq"));
                    meta.put("jednotka", ind.getValue().get("unit"));
                    indicators.put(ind.getKey(), meta);
                }
            }
            out.put(
                    cat.getKey(),
                    Map.of("nazev", cat.getValue(), "pocet", indicators.size(), "ukazatele", indicators));
        }
        return out;
    }

    public Map<String, Object> getCountryIndicators(String country, String kategorie) {
        String c = catalog.validateCountryCode(country);
        Map<String, Map<String, Object>> avail = availability.availableIndicators(c);
        if (kategorie != null && !kategorie.isBlank()) {
            avail = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : availability.availableIndicators(c).entrySet()) {
                if (kategorie.equals(String.valueOf(entry.getValue().get("cat")))) {
                    avail.put(entry.getKey(), entry.getValue());
                }
            }
            if (avail.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Neznámá kategorie '" + kategorie + "' nebo žádné ukazatele.");
            }
        }
        Map<String, Object> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : avail.entrySet()) {
            String cat = String.valueOf(entry.getValue().getOrDefault("cat", "other"));
            @SuppressWarnings("unchecked")
            Map<String, Object> catNode = (Map<String, Object>) grouped.computeIfAbsent(cat, ignored -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("nazev_kategorie", catalog.getCategories().getOrDefault(cat, cat));
                node.put("ukazatele", new LinkedHashMap<String, Object>());
                return node;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> ukazatele = (Map<String, Object>) catNode.get("ukazatele");
            Map<String, Object> indOut = new LinkedHashMap<>();
            indOut.put("nazev", entry.getValue().get("name"));
            indOut.put("jednotka", entry.getValue().get("unit"));
            indOut.put("frekvence", entry.getValue().get("freq"));
            indOut.put("poznamka", entry.getValue().getOrDefault("note", ""));
            indOut.put("data_url", "/api/ecb/country/" + c + "/data/" + entry.getKey());
            ukazatele.put(entry.getKey(), indOut);
        }
        Map<String, Object> info = catalog.countryInfo(c);
        Map<String, Object> nodePreview = buildCountryBrowseNode(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zeme", c);
        out.put("nazev_zeme", info.get("name"));
        out.put("clenstvi_eurozony", info.get("euro"));
        out.put("clenstvi_eu", info.get("eu"));
        out.put("celkem_ukazatelu", avail.size());
        out.put("kategorie", grouped);
        out.put("browse_notice", nodePreview.get("browse_notice"));
        out.put("availability_revision", combinedAvailabilityRevision());
        return out;
    }

    public Map<String, Object> getCountryData(String country, String indicator, String start, String end) {
        String c = catalog.validateCountryCode(country);
        Map<String, Object> ind = catalog.indicatorById(indicator);
        if (ind == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Neznámý ukazatel '" + indicator + "'. Dostupné: GET /api/ecb/country/" + c);
        }
        if (!availability.isPairAvailable(c, indicator)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Tento ukazatel není pro vybranou zemi v katalogu dostupný.");
        }
        try {
            List<Map<String, Object>> series = apiClient.fetchCuratedSeries(catalog, availability, indicator, c, start, end);
            EcbCuratedCatalog.SdmxKey sdmx = catalog.sdmxKeyForCountry(ind, c);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("zeme", c);
            out.put("nazev_zeme", catalog.countryInfo(c).get("name"));
            out.put("ukazatel", indicator);
            out.put("nazev", ind.get("name"));
            out.put("kategorie", catalog.getCategories().getOrDefault(String.valueOf(ind.get("cat")), String.valueOf(ind.get("cat"))));
            out.put("jednotka", ind.get("unit"));
            out.put("frekvence", ind.get("freq"));
            out.put("poznamka", ind.getOrDefault("note", ""));
            out.put("flow", sdmx.flow());
            out.put("sdmx_key", sdmx.key());
            out.put("pocet_zaznamu", series.size());
            out.put("data", series);
            return out;
        } catch (EcbApiClient.EcbUpstreamException ex) {
            throw new ResponseStatusException(
                    ex.status() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    public Map<String, Object> getCountrySnapshot(String country, String start) {
        String c = catalog.validateCountryCode(country);
        Map<String, Map<String, Object>> avail = availability.availableIndicators(c);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : catalog.getSnapshotKeys()) {
            if (!avail.containsKey(key)) {
                snapshot.put(key, Map.of("dostupne", false));
                continue;
            }
            Map<String, Object> ind = catalog.indicatorById(key);
            try {
                List<Map<String, Object>> series =
                        apiClient.fetchCuratedSeries(catalog, availability, key, c, start, null);
                Map<String, Object> last = series.isEmpty() ? null : series.get(series.size() - 1);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dostupne", true);
                row.put("nazev", ind.get("name"));
                row.put("jednotka", ind.get("unit"));
                row.put("frekvence", ind.get("freq"));
                row.put("posledni_hodnota", last != null ? last.get("value") : null);
                row.put("posledni_datum", last != null ? last.get("date") : null);
                snapshot.put(key, row);
            } catch (Exception ex) {
                snapshot.put(key, Map.of("dostupne", false, "nazev", ind.get("name")));
            }
        }
        return Map.of(
                "zeme", c,
                "nazev_zeme", catalog.countryInfo(c).get("name"),
                "od", start,
                "prehled", snapshot);
    }

    public Map<String, Object> compareCountries(String zemeRaw, String ukazatel, String start, String end) {
        Map<String, Object> ind = catalog.indicatorById(ukazatel);
        if (ind == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Neznámý ukazatel '" + ukazatel + "'");
        }
        Map<String, Object> vysledky = new LinkedHashMap<>();
        for (String code : parseCountryList(zemeRaw)) {
            if (!catalog.getCountries().containsKey(code)) {
                vysledky.put(code, Map.of("chyba", "Neznámý kód země '" + code + "'"));
                continue;
            }
            if (!availability.isPairAvailable(code, ukazatel)) {
                vysledky.put(code, Map.of("nazev_zeme", catalog.countryInfo(code).get("name"), "data", List.of()));
                continue;
            }
            try {
                List<Map<String, Object>> series =
                        apiClient.fetchCuratedSeries(catalog, availability, ukazatel, code, start, end);
                vysledky.put(code, Map.of("nazev_zeme", catalog.countryInfo(code).get("name"), "data", series));
            } catch (Exception ex) {
                vysledky.put(code, Map.of("nazev_zeme", catalog.countryInfo(code).get("name"), "data", List.of()));
            }
        }
        return Map.of(
                "ukazatel", ukazatel,
                "nazev", ind.get("name"),
                "jednotka", ind.get("unit"),
                "frekvence", ind.get("freq"),
                "zeme", vysledky);
    }

    public Map<String, Object> addSource(Map<String, Object> payload) {
        String country = stringOrBlank(payload.get("country")).toUpperCase(Locale.ROOT);
        String indicatorId = stringOrBlank(payload.get("indicator"));
        if (indicatorId.isBlank()) {
            indicatorId = stringOrBlank(payload.get("indicator_id"));
        }
        String c = catalog.validateCountryCode(country);
        Map<String, Object> ind = catalog.indicatorById(indicatorId);
        if (ind == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Neznámý ukazatel '" + indicatorId + "'.");
        }
        if (!availability.isPairAvailable(c, indicatorId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Tento ukazatel není pro vybranou zemi v katalogu dostupný.");
        }
        EcbCuratedCatalog.SdmxKey sdmx = catalog.sdmxKeyForCountry(ind, c);
        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "ECB · " + catalog.countryInfo(c).get("name") + " · " + ind.get("name");
        }
        Map<String, Object> qp = new LinkedHashMap<>();
        qp.put("country", c);
        qp.put("ecb_indicator_id", indicatorId);
        qp.put("ecb_flow", sdmx.flow());
        qp.put("ecb_series_key", sdmx.key());

        Integer refreshInterval = toInteger(payload.get("refresh_interval_minutes"));
        if (refreshInterval == null) {
            refreshInterval = 1440;
        }
        Boolean active = toBoolean(payload.get("active"));
        if (active == null) {
            active = true;
        }

        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "ecb",
                "https://data-api.ecb.europa.eu",
                "/service/data/" + sdmx.flow() + "/" + sdmx.key(),
                "GET",
                "none",
                null,
                Map.of("Accept", "text/csv", "User-Agent", "banking-bi/1.0"),
                qp,
                refreshInterval,
                active,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);
        return Map.of(
                "id", created.get("id"),
                "name", displayName,
                "country", c,
                "indicator", indicatorId,
                "flow", sdmx.flow(),
                "sdmx_key", sdmx.key());
    }

    private Map<String, Object> buildCountryBrowseNode(String code) {
        Map<String, Object> info = catalog.countryInfo(code);
        Map<String, Map<String, Object>> avail = availability.availableIndicators(code);
        Map<String, List<Map.Entry<String, Map<String, Object>>>> byCat = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : avail.entrySet()) {
            String cat = String.valueOf(entry.getValue().getOrDefault("cat", "other"));
            byCat.computeIfAbsent(cat, ignored -> new ArrayList<>()).add(entry);
        }
        List<Map<String, Object>> children = new ArrayList<>();
        byCat.entrySet().stream()
                .sorted(Comparator.comparing(e -> catalog.getCategories().getOrDefault(e.getKey(), e.getKey())))
                .forEach(catEntry -> {
                    List<Map<String, Object>> sets = new ArrayList<>();
                    catEntry.getValue().stream()
                            .sorted(Comparator.comparing(
                                    e -> stringOrBlank(e.getValue().get("name")).toLowerCase(Locale.ROOT)))
                            .forEach(entry -> {
                                String indId = entry.getKey();
                                Map<String, Object> ind = entry.getValue();
                                Map<String, Object> set = new LinkedHashMap<>();
                                set.put("set_id", catalog.composeCuratedSetId(code, indId));
                                set.put("name", ind.get("name"));
                                set.put("kind", "selection");
                                set.put("item_kind", "selection");
                                set.put("ecb_country", code);
                                set.put("ecb_indicator_id", indId);
                                set.put(
                                        "query_params",
                                        Map.of(
                                                "country", code,
                                                "ecb_indicator_id", indId,
                                                "indicator", indId));
                                set.put("territory", code);
                                set.put("period", ind.getOrDefault("freq", ""));
                                sets.add(set);
                            });
                    Map<String, Object> child = new LinkedHashMap<>();
                    child.put(
                            "path",
                            EcbCuratedCatalog.ECB_BROWSE_ROOT + " > " + code + " > "
                                    + catalog.getCategories().getOrDefault(catEntry.getKey(), catEntry.getKey()));
                    child.put("name", catalog.getCategories().getOrDefault(catEntry.getKey(), catEntry.getKey()));
                    child.put("children", List.of());
                    child.put("sets", sets);
                    children.add(child);
                });
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", EcbCuratedCatalog.ECB_BROWSE_ROOT + " > " + code);
        node.put("name", info.get("name") + " (" + code + ")");
        node.put("children", children);
        node.put("sets", List.of());
        node.put("ecb_country", code);
        String notice = browseNotice(code, avail.size());
        if (notice != null) {
            node.put("browse_notice", notice);
        }
        return node;
    }

    private static String browseNotice(String code, int availableCount) {
        return switch (code) {
            case "CH", "US", "JP", "NO" ->
                    "ECB u této země neposkytuje harmonizovanou inflaci (HICP). V katalogu jsou jen ukazatele, které ECB skutečně publikuje.";
            case "U6" ->
                    "Agregát EU27 — část řad ECB vrací pod kódem I9; ne všechny ukazatele jsou dostupné jako u jednotlivých členských států.";
            case "U2" ->
                    availableCount < 25
                            ? "Eurozóna (EA) — některé řady (např. meziroční růst HDP) ECB u agregátu nepublikuje; dostupné jsou především nominální úrovně a inflace."
                            : null;
            default -> null;
        };
    }

    private String combinedAvailabilityRevision() {
        String rev = availability.availabilityRevision();
        if (seriesAvailability.discoveryBrowseEnabled()) {
            String drev = seriesAvailability.availabilityRevision();
            if (!drev.isBlank()) {
                return rev + "|discovery:" + drev;
            }
        }
        return rev;
    }

    private static List<String> parseCountryList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream()
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .filter(v -> !v.isBlank())
                .toList();
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return null;
    }
}
