package cz.bankintel.sources.imf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelDataPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/** Load and query {@code imf_availability.json} — port {@code imf_availability.py}. */
@Service
public class ImfAvailabilityStore {

    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();
    private Map<String, List<Map<String, Object>>> byCountry = Map.of();
    private Map<String, Map<String, List<Map<String, Object>>>> byIndicator = Map.of();
    private Map<String, Object> meta = Map.of();
    private long loadedMtime;

    public ImfAvailabilityStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean loaded() {
        ensureLoaded();
        return !byCountry.isEmpty();
    }

    public String revision() {
        ensureLoaded();
        Object generated = meta.get("generated_at");
        if (generated == null) {
            generated = meta.get("version");
        }
        return generated != null ? String.valueOf(generated) : "";
    }

    public List<BrowseCountry> listBrowseCountries(int minRows) {
        ensureLoaded();
        List<BrowseCountry> out = new ArrayList<>();
        for (String code : byCountry.keySet().stream().sorted().toList()) {
            List<Map<String, Object>> items = byCountry.getOrDefault(code, List.of());
            if (items.size() < minRows) {
                continue;
            }
            String english = "";
            if (!items.isEmpty()) {
                english = stringOrBlank(items.get(0).get("country_name"));
            }
            String imfCode = ImfApiSupport.normalizeCountryCode(code);
            String browseCode = ImfApiSupport.browseCountryCode(code);
            out.add(new BrowseCountry(
                    browseCode,
                    imfCode,
                    ImfEntityLabels.entityLabel(code, english),
                    ImfEntityLabels.formatBrowseEntity(code, english),
                    items.size()));
        }
        out.sort(Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public List<Map<String, Object>> indicatorsForCountry(String country) {
        ensureLoaded();
        String key = ImfApiSupport.normalizeCountryCode(country);
        String alt = ImfApiSupport.browseCountryCode(country);
        List<Map<String, Object>> items = byCountry.get(key);
        if (items == null) {
            items = byCountry.get(alt);
        }
        if (items == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : items) {
            out.add(new LinkedHashMap<>(row));
        }
        return out;
    }

    public List<Map<String, Object>> countriesForIndicator(String flow, String indicator) {
        ensureLoaded();
        Map<String, List<Map<String, Object>>> flowMap = byIndicator.get(stringOrBlank(flow));
        if (flowMap == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = flowMap.get(stringOrBlank(indicator));
        if (rows == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(new LinkedHashMap<>(row));
        }
        return out;
    }

    public Map<String, Object> findSeriesEntry(String country, String flow, String indicator) {
        for (Map<String, Object> row : indicatorsForCountry(country)) {
            if (stringOrBlank(row.get("flow")).equals(flow) && stringOrBlank(row.get("indicator")).equals(indicator)) {
                return row;
            }
        }
        return null;
    }

    public String indicatorDisplayName(String country, String flow, String indicator) {
        Map<String, Object> row = findSeriesEntry(country, flow, indicator);
        if (row == null) {
            return "";
        }
        return stringOrBlank(row.get("indicator_name"));
    }

    private void ensureLoaded() {
        Path path = BankIntelDataPaths.dataDir().resolve("imf_availability.json");
        if (!Files.isRegularFile(path)) {
            byCountry = Map.of();
            byIndicator = Map.of();
            meta = Map.of();
            return;
        }
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return;
        }
        lock.lock();
        try {
            if (byCountry != null && loadedMtime == mtime && !byCountry.isEmpty()) {
                return;
            }
            load(path);
            loadedMtime = mtime;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void load(Path path) {
        try {
            Map<String, Object> raw = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            Map<String, List<Map<String, Object>>> countries = new LinkedHashMap<>();
            Map<String, Object> metaOut = new LinkedHashMap<>();
            if (raw.containsKey("countries")) {
                for (String key : List.of("version", "generated_at", "policy")) {
                    if (raw.containsKey(key)) {
                        metaOut.put(key, raw.get(key));
                    }
                }
                Object countriesObj = raw.get("countries");
                if (countriesObj instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() instanceof List<?> list) {
                            countries.put(String.valueOf(entry.getKey()), toMapList(list));
                        }
                    }
                }
            } else {
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    if (List.of("version", "generated_at", "policy").contains(entry.getKey())) {
                        metaOut.put(entry.getKey(), entry.getValue());
                    } else if (entry.getValue() instanceof List<?> list) {
                        countries.put(entry.getKey(), toMapList(list));
                    }
                }
            }
            Map<String, Map<String, List<Map<String, Object>>>> indicatorIndex = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> countryEntry : countries.entrySet()) {
                for (Map<String, Object> row : countryEntry.getValue()) {
                    String flow = stringOrBlank(row.get("flow"));
                    String ind = stringOrBlank(row.get("indicator"));
                    if (flow.isBlank() || ind.isBlank()) {
                        continue;
                    }
                    Map<String, Object> enriched = new LinkedHashMap<>(row);
                    enriched.put("country", countryEntry.getKey());
                    enriched.put("browse_country", ImfApiSupport.browseCountryCode(countryEntry.getKey()));
                    indicatorIndex
                            .computeIfAbsent(flow, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(ind, ignored -> new ArrayList<>())
                            .add(enriched);
                }
            }
            byCountry = countries;
            byIndicator = indicatorIndex;
            meta = metaOut;
        } catch (IOException ex) {
            byCountry = Map.of();
            byIndicator = Map.of();
            meta = Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toMapList(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        row.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                out.add(row);
            }
        }
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public record BrowseCountry(String code, String imfCode, String name, String browseLabel, int count) {}
}
