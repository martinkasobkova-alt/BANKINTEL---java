package cz.bankintel.sources.imf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Country-first IMF catalog — port {@code imf_country_catalog.py} (availability-backed). */
@Service
public class ImfCountryCatalogService {

    private static final Map<String, String> FLOW_LABELS = Map.ofEntries(
            Map.entry("WEO", "Svetovy ekonomicky vyhled"),
            Map.entry("CPI", "Spotrebitelske ceny"),
            Map.entry("FM", "Fiskalni monitor"),
            Map.entry("BOP", "Platebni bilance"),
            Map.entry("ER", "Smenné kurzy"),
            Map.entry("EER", "Efektivni smenny kurz"),
            Map.entry("NEA", "Narodni ekonomicke ucty"),
            Map.entry("ITG", "Mezinarodni obchod se zbozim"));

    private final ImfAvailabilityStore availability;

    public ImfCountryCatalogService(ImfAvailabilityStore availability) {
        this.availability = availability;
    }

    public Map<String, Object> buildCountryCatalog(String country) {
        String imfCode = ImfApiSupport.normalizeCountryCode(country);
        List<Map<String, Object>> rows = availabilityRows(imfCode);
        List<Map<String, Object>> items = groupVariants(rows);

        Map<String, Object> kategorie = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String catKey = stringOrBlank(item.get("flow"));
            if (catKey.isBlank()) {
                catKey = "other";
            }
            String catName = flowLabelCs(catKey, stringOrBlank(item.get("flow_name")));
            @SuppressWarnings("unchecked")
            Map<String, Object> cat = (Map<String, Object>) kategorie.computeIfAbsent(catName, ignored -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("nazev", catName);
                node.put("nazev_kategorie", catName);
                node.put("pocet", 0);
                node.put("ukazatele", new LinkedHashMap<String, Object>());
                return node;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> ukazatele = (Map<String, Object>) cat.get("ukazatele");
            String indId = stringOrBlank(item.get("indicator"));
            Map<String, Object> uk = new LinkedHashMap<>();
            uk.put("nazev", item.get("nazev"));
            uk.put("jednotka", item.get("jednotka"));
            uk.put("frekvence", item.get("frekvence"));
            uk.put("frekvence_label", item.get("frekvence_label"));
            uk.put("ma_projekce", item.get("ma_projekce"));
            uk.put("posledni_datum", item.get("posledni_datum"));
            uk.put("pocet_bodu", item.get("pocet_bodu"));
            uk.put("flow", item.get("flow"));
            uk.put("flow_name", item.get("flow_name"));
            uk.put("varianty", item.get("varianty"));
            uk.put("agency", item.get("agency"));
            uk.put("version", item.get("version"));
            uk.put("sdmx_key", item.get("sdmx_key"));
            ukazatele.put(indId, uk);
            cat.put("pocet", ukazatele.size());
        }

        String zdroj = items.isEmpty() ? "empty" : "availability";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kategorie", kategorie);
        out.put("celkem_ukazatelu", items.size());
        out.put("catalog_source", zdroj);
        return out;
    }

    public Map<String, Object> resolveSeriesForFetch(String country, String flow, String indicator, String frekvence) {
        String imfCode = ImfApiSupport.normalizeCountryCode(country);
        String flowU = stringOrBlank(flow).toUpperCase(Locale.ROOT);
        String indU = stringOrBlank(indicator).toUpperCase(Locale.ROOT);
        String freqWant = frekvence != null ? frekvence.trim().toUpperCase(Locale.ROOT) : "";
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : availabilityRows(imfCode)) {
            if (stringOrBlank(row.get("flow")).equalsIgnoreCase(flowU)
                    && stringOrBlank(row.get("indicator")).equalsIgnoreCase(indU)) {
                matches.add(row);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (!freqWant.isBlank()) {
            for (Map<String, Object> row : matches) {
                if (stringOrBlank(row.get("frekvence")).equalsIgnoreCase(freqWant)) {
                    return row;
                }
            }
        }
        return matches.get(0);
    }

    private List<Map<String, Object>> availabilityRows(String imfCode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : availability.indicatorsForCountry(imfCode)) {
            rows.add(rowFromAvailability(imfCode, raw));
        }
        return rows;
    }

    private static Map<String, Object> rowFromAvailability(String imfCode, Map<String, Object> raw) {
        String flow = stringOrBlank(raw.get("flow"));
        String indicator = stringOrBlank(raw.get("indicator"));
        String display = stringOrBlank(raw.get("indicator_name"));
        if (display.isBlank()) {
            display = indicator;
        }
        String freq = stringOrBlank(raw.get("frekvence"));
        if (freq.isBlank()) {
            freq = "A";
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("flow", flow);
        row.put("flow_name", flowLabelCs(flow, stringOrBlank(raw.get("flow_name"))));
        row.put("agency", stringOrBlank(raw.get("agency")).isBlank() ? "IMF.RES" : raw.get("agency"));
        row.put("version", stringOrBlank(raw.get("version")).isBlank() ? "1.0.0" : raw.get("version"));
        row.put("indicator", indicator);
        row.put("indicator_name", display);
        row.put("nazev", display);
        row.put("jednotka", raw.get("jednotka"));
        row.put("frekvence", freq);
        row.put("frekvence_label", ImfApiSupport.freqLabelCs(freq));
        row.put("sdmx_key", raw.get("sdmx_key"));
        row.put("pocet_bodu", raw.get("pocet_bodu"));
        row.put("prvni_datum", raw.get("prvni_datum"));
        row.put("posledni_datum", raw.get("posledni_datum"));
        row.put("ma_projekce", Boolean.TRUE.equals(raw.get("ma_projekce")));
        row.put("zdroj", "availability");
        row.put("imf_code", imfCode);
        return row;
    }

    private static List<Map<String, Object>> groupVariants(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = stringOrBlank(row.get("flow")) + "|" + stringOrBlank(row.get("indicator"));
            Map<String, Object> existing = grouped.get(key);
            if (existing == null) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.put("varianty", new ArrayList<Map<String, Object>>());
                grouped.put(key, copy);
                existing = copy;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> varianty = (List<Map<String, Object>>) existing.get("varianty");
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("frekvence", row.get("frekvence"));
            variant.put("frekvence_label", row.get("frekvence_label"));
            variant.put("sdmx_key", row.get("sdmx_key"));
            variant.put("agency", row.get("agency"));
            variant.put("version", row.get("version"));
            variant.put("pocet_bodu", row.get("pocet_bodu"));
            variant.put("ma_projekce", row.get("ma_projekce"));
            boolean dup = false;
            for (Map<String, Object> v : varianty) {
                if (stringOrBlank(v.get("frekvence")).equals(stringOrBlank(row.get("frekvence")))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                varianty.add(variant);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> group : grouped.values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> varianty = (List<Map<String, Object>>) group.get("varianty");
            if (varianty.size() == 1) {
                Map<String, Object> only = varianty.get(0);
                group.put("sdmx_key", only.get("sdmx_key"));
                group.put("frekvence", only.get("frekvence"));
                group.put("frekvence_label", only.get("frekvence_label"));
                group.put("varianty", List.of());
            }
            out.add(group);
        }
        out.sort(Comparator
                .comparing((Map<String, Object> r) -> stringOrBlank(r.get("flow_name")))
                .thenComparing(r -> stringOrBlank(r.get("nazev"))));
        return out;
    }

    static String flowLabelCs(String flow, String fallback) {
        String fb = fallback != null ? fallback.trim() : "";
        if (!fb.isBlank()) {
            return fb;
        }
        return FLOW_LABELS.getOrDefault(stringOrBlank(flow).toUpperCase(Locale.ROOT), flow);
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
