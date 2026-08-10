package cz.bankintel.sources.imf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogSearchProperties;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** IMF 2 discovery browse from catalog index — port {@code imf2_browser_routes.py}. */
@Service
public class Imf2BrowseService {

    private static final String IMF2_BROWSE_ROOT = "IMF 2 · ověřené řady";
    private static final int SCAN_CAP = 250_000;

    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            Map.entry("CZE", "Czechia"),
            Map.entry("DEU", "Germany"),
            Map.entry("SVK", "Slovakia"),
            Map.entry("POL", "Poland"),
            Map.entry("USA", "United States"),
            Map.entry("GBR", "United Kingdom"),
            Map.entry("JPN", "Japan"),
            Map.entry("FRA", "France"),
            Map.entry("AUT", "Austria"),
            Map.entry("CHE", "Switzerland"),
            Map.entry("OECD", "OECD"),
            Map.entry("EA20", "Euro area"),
            Map.entry("EU27", "European Union"),
            Map.entry("WEO", "World Economic Outlook aggregate"),
            Map.entry("G001", "Advanced economies"),
            Map.entry("G020", "Major advanced economies (G7)"));

    private final CatalogSearchProperties catalogSearchProperties;
    private final ObjectMapper objectMapper;

    public Imf2BrowseService(CatalogSearchProperties catalogSearchProperties, ObjectMapper objectMapper) {
        this.catalogSearchProperties = catalogSearchProperties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getBrowseTree() {
        requireIndex();
        List<String> codes = listIndexedCountries();
        List<Map<String, Object>> countryChildren = new ArrayList<>();
        for (String code : codes) {
            String name = countryLabel(code);
            countryChildren.add(Map.of(
                    "path", IMF2_BROWSE_ROOT + " > " + code,
                    "name", name + " (" + code + ")",
                    "children", List.of(),
                    "sets", List.of(),
                    "imf_country", code,
                    "imf_country_lazy", true));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("path", IMF2_BROWSE_ROOT);
        root.put("name", IMF2_BROWSE_ROOT);
        root.put("children", countryChildren);
        root.put("sets", List.of());
        root.put(
                "browse_notice",
                "Rozbalte zemi — zobrazí se ověřené CompactData řady s daty. "
                        + "Pro srovnání se search-first katalogem použijte IMF (beta).");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(root));
        out.put("total_sets", 0);
        out.put("browse_mode", "country_first_discovery");
        out.put("availability_revision", catalogSearchProperties.indexDir().toString());
        out.put("imf_discovery_browse_enabled", true);
        return out;
    }

    public Map<String, Object> getCountryBrowseNode(String country, int offset, int limit) {
        requireIndex();
        String code = validateCountry(country);
        List<Map<String, Object>> rows = catalogRowsForCountry(code, offset, limit);
        int total = countRowsForCountry(code);
        boolean capped = offset + rows.size() < total;
        String areaName = countryLabel(code);
        String capNote = capped ? " (zobrazeno " + rows.size() + " z " + total + ")" : "";
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", IMF2_BROWSE_ROOT + " > " + code);
        node.put("name", areaName + " (" + code + ")");
        node.put("children", List.of());
        node.put("sets", rowsToSets(rows));
        node.put("imf_country", code);
        node.put("imf2_discovery_total", total);
        node.put("imf2_discovery_capped", capped);
        if (total > 0) {
            node.put(
                    "browse_notice",
                    total + " ověřených řad IMF pro " + areaName + capNote
                            + ". Klikněte na řadu — CompactData dotaz skládat nemusíte.");
        } else {
            node.put("browse_notice", "Pro " + areaName + " zatím nejsou v mřížce žádné ověřené řady.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", code);
        out.put("country_node", node);
        out.put("available_count", rows.size());
        return out;
    }

    private List<Map<String, Object>> catalogRowsForCountry(String country, int offset, int limit) {
        List<Map<String, Object>> all = loadRowsForCountry(country);
        int off = Math.max(0, offset);
        int lim = Math.max(1, Math.min(limit, 2000));
        if (off >= all.size()) {
            return List.of();
        }
        return all.subList(off, Math.min(off + lim, all.size()));
    }

    private int countRowsForCountry(String country) {
        return loadRowsForCountry(country).size();
    }

    private List<Map<String, Object>> loadRowsForCountry(String country) {
        String code = validateCountry(country);
        var path = catalogSearchProperties.jsonlPath("imf");
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int scanned = 0;
            while ((line = reader.readLine()) != null && scanned < SCAN_CAP) {
                scanned++;
                Map<String, Object> row = objectMapper.readValue(line, new TypeReference<>() {});
                if (!matchesCountry(row, code)) {
                    continue;
                }
                Map<String, Object> mapped = mapRow(row, code);
                if (mapped != null) {
                    out.add(mapped);
                }
            }
        } catch (Exception ex) {
            return List.of();
        }
        out.sort(Comparator.comparing(r -> stringOrBlank(r.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    private List<String> listIndexedCountries() {
        var path = catalogSearchProperties.jsonlPath("imf");
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int scanned = 0;
            while ((line = reader.readLine()) != null && scanned < SCAN_CAP) {
                scanned++;
                Map<String, Object> row = objectMapper.readValue(line, new TypeReference<>() {});
                String code = extractCountryCode(row);
                if (!code.isBlank()) {
                    counts.merge(code, 1, Integer::sum);
                }
            }
        } catch (Exception ex) {
            return List.of();
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.comparing(
                                (Map.Entry<String, Integer> e) ->
                                        countryLabel(e.getKey()).toLowerCase(Locale.ROOT))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static boolean matchesCountry(Map<String, Object> row, String code) {
        return code.equalsIgnoreCase(extractCountryCode(row));
    }

    private static String extractCountryCode(Map<String, Object> row) {
        String territory = stringOrBlank(row.get("territory"));
        if (!territory.isBlank()) {
            return territory.toUpperCase(Locale.ROOT);
        }
        Object qpObj = row.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            String imfCountry = stringOrBlank(qp.get("imf_country"));
            if (!imfCountry.isBlank()) {
                return imfCountry.toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static Map<String, Object> mapRow(Map<String, Object> row, String code) {
        String setId = stringOrBlank(row.get("set_id"));
        if (setId.isBlank()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set_id", setId);
        out.put("name", stringOrBlank(row.get("name")).isBlank() ? setId : row.get("name"));
        out.put("kind", "selection");
        out.put("item_kind", "selection");
        out.put("territory", code);
        out.put("imf_country", code);
        out.put("imf_browse_source", "discovery_availability");
        Object qp = row.get("query_params");
        out.put("query_params", qp instanceof Map<?, ?> m ? new LinkedHashMap<>(stringMap(m)) : Map.of("imf_country", code));
        return out;
    }

    private static List<Map<String, Object>> rowsToSets(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> set = new LinkedHashMap<>();
            for (String key :
                    List.of("set_id", "name", "kind", "item_kind", "territory", "imf_country", "query_params", "imf_browse_source")) {
                if (row.containsKey(key)) {
                    set.put(key, row.get(key));
                }
            }
            if (!stringOrBlank(set.get("set_id")).isBlank()) {
                out.add(set);
            }
        }
        return out;
    }

    private void requireIndex() {
        if (!Files.isRegularFile(catalogSearchProperties.jsonlPath("imf"))) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "IMF 2 vyžaduje catalog_search_indexes/imf.jsonl — spusťte build indexů.");
        }
    }

    static String validateCountry(String country) {
        String code = country != null ? country.trim().toUpperCase(Locale.ROOT) : "";
        if (code.isBlank() || code.length() > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný kód země: " + country);
        }
        return code;
    }

    private static String countryLabel(String code) {
        return COUNTRY_NAMES.getOrDefault(code, code);
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
