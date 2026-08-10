package cz.bankintel.explore.manager.fetch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.connector.ConnectorHttpSupport;
import cz.bankintel.util.BankIntelDataPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shared local mirror CSV/JSON loading — ref Python {@code *_mirror_store.py} + {@code load_long_rows}. */
@Component
public class ManagerMirrorFetchSupport {

    private static final Logger log = LoggerFactory.getLogger(ManagerMirrorFetchSupport.class);

    private final ConnectorHttpSupport httpSupport;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Map<String, Object>>> csvCache = new ConcurrentHashMap<>();

    public ManagerMirrorFetchSupport(ConnectorHttpSupport httpSupport, ObjectMapper objectMapper) {
        this.httpSupport = httpSupport;
        this.objectMapper = objectMapper;
    }

    public Path dataRoot() {
        return BankIntelDataPaths.dataDir();
    }

    public Path mirrorLongCsv(String subdir, String filename) {
        return dataRoot().resolve(subdir).resolve(filename);
    }

    public boolean mirrorAvailable(Path csvPath) {
        return Files.isRegularFile(csvPath);
    }

    /** Explicit unavailable marker when local mirror CSV is missing — ref *_mirror_store.py. */
    public List<Map<String, Object>> mirrorUnavailableRows(String domain, Path csvPath) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_mirror_status", "source_unavailable");
        row.put("_mirror_domain", domain);
        row.put("_mirror_path", csvPath.toAbsolutePath().normalize().toString());
        row.put(
                "reason_cs",
                "Doménový mirror pro " + domain + " není v ./data — spusťte mirror tooling nebo live fetch.");
        return List.of(row);
    }

    public static boolean isMirrorUnavailable(List<Map<String, Object>> rows) {
        return rows != null
                && rows.size() == 1
                && "source_unavailable".equals(String.valueOf(rows.get(0).get("_mirror_status")));
    }

    public List<Map<String, Object>> loadLongCsv(Path csvPath) {
        String key = csvPath.toAbsolutePath().normalize().toString();
        return csvCache.computeIfAbsent(key, k -> readLongCsv(csvPath));
    }

    public void clearAllCaches() {
        csvCache.clear();
    }

    public List<Map<String, Object>> filterBySeriesAndGeo(
            List<Map<String, Object>> rows,
            String seriesId,
            String seriesColumn,
            String geoColumn,
            String geo,
            String periodColumn) {
        String sid = str(seriesId);
        String geoFilter = str(geo).toUpperCase(Locale.ROOT);
        if (sid.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!sid.equals(str(row.get(seriesColumn)))) {
                continue;
            }
            if (!geoFilter.isBlank()) {
                String rowGeo = str(row.get(geoColumn)).toUpperCase(Locale.ROOT);
                if (!geoFilter.equals(rowGeo)) {
                    if (!geoEquivalent(geoFilter, rowGeo)) {
                        continue;
                    }
                }
            }
            Double value = toDouble(row.get("value"));
            if (value == null) {
                value = toDouble(row.get("OBS_VALUE"));
            }
            if (value == null) {
                continue;
            }
            String period = firstNonBlank(row.get(periodColumn), row.get("period"), row.get("date"), row.get("TIME_PERIOD"));
            if (period.isBlank()) {
                continue;
            }
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("date", period);
            obs.put("period", period);
            obs.put("value", value);
            if (row.get("unit") != null) {
                obs.put("unit", row.get("unit"));
            }
            out.add(obs);
        }
        out.sort((a, b) -> str(a.get("period")).compareTo(str(b.get("period"))));
        return out;
    }

    public List<Map<String, Object>> loadOecd4SnapshotRows(String datasetKey) {
        Path snapDir = BankIntelDataPaths.oecd4Dir().resolve("snapshots").resolve(datasetKey);
        if (!Files.isDirectory(snapDir)) {
            return List.of();
        }
        try (var stream = Files.list(snapDir)) {
            Path latest = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> b.getFileName().compareTo(a.getFileName()))
                    .findFirst()
                    .orElse(null);
            if (latest == null) {
                return List.of();
            }
            Map<String, Object> root = objectMapper.readValue(Files.readString(latest), new TypeReference<>() {});
            Object rows = root.get("rows");
            if (rows instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        out.add(castMap(map));
                    }
                }
                return out;
            }
        } catch (IOException ex) {
            log.debug("oecd4 snapshot read failed {}: {}", datasetKey, ex.getMessage());
        }
        return List.of();
    }

    static String resolveSeriesId(String query, Map<String, Object> context, String qpKey) {
        String fromQuery = str(query);
        if (!fromQuery.isBlank()) {
            return stripPrefix(fromQuery);
        }
        String setId = stripPrefix(str(context.get("set_id")));
        if (!setId.isBlank()) {
            return setId;
        }
        Object qpObj = context.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            String fromQp = str(qp.get(qpKey));
            if (!fromQp.isBlank()) {
                return fromQp;
            }
        }
        return stripPrefix(str(context.get("series_id")));
    }

    static String resolveGeo(Map<String, Object> context) {
        Object qpObj = context.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            for (String key : List.of("geo", "country", "ref_area", "REF_AREA", "country_code", "bidding_zone")) {
                String val = str(qp.get(key));
                if (!val.isBlank()) {
                    return val.toUpperCase(Locale.ROOT);
                }
            }
        }
        return firstNonBlank(context.get("context_country"), context.get("country"), context.get("geo"))
                .toUpperCase(Locale.ROOT);
    }

    /** ISO2 ↔ EL/GR parity — ref {@code manager_series_cache.normalize_cache_geo_variants}. */
    static boolean geoEquivalent(String requested, String rowGeo) {
        if ("GR".equals(requested) && "EL".equals(rowGeo)) {
            return true;
        }
        return "EL".equals(requested) && "GR".equals(rowGeo);
    }

    static String refAreaForGeo(String geo) {
        String code = str(geo).toUpperCase(Locale.ROOT);
        if (code.length() == 3) {
            return code;
        }
        return switch (code) {
            case "CZ" -> "CZE";
            case "DE" -> "DEU";
            case "SK" -> "SVK";
            case "PL" -> "POL";
            case "AT" -> "AUT";
            case "US" -> "USA";
            case "GB", "UK" -> "GBR";
            case "EU" -> "EU27_2020";
            case "EA" -> "EA20";
            case "FR" -> "FRA";
            case "IT" -> "ITA";
            case "ES" -> "ESP";
            case "NL" -> "NLD";
            case "BE" -> "BEL";
            case "CH" -> "CHE";
            case "JP" -> "JPN";
            case "GLOBAL", "OECD" -> "OECD";
            default -> code;
        };
    }

    static String stripPrefix(String raw) {
        String val = str(raw);
        if (val.contains("/")) {
            return val.substring(val.lastIndexOf('/') + 1);
        }
        return val;
    }

    private List<Map<String, Object>> readLongCsv(Path csvPath) {
        if (!Files.isRegularFile(csvPath)) {
            return List.of();
        }
        try {
            String text = Files.readString(csvPath);
            return httpSupport.parseCsv(text);
        } catch (IOException ex) {
            log.debug("mirror CSV read failed {}: {}", csvPath, ex.getMessage());
            return List.of();
        }
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

    static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim().replace(",", "."));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }
}
