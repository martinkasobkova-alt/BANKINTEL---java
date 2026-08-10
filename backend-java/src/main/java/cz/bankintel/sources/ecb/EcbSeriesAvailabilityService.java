package cz.bankintel.sources.ecb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EcbSeriesAvailabilityService {

    public static final String ECB2_BROWSE_ROOT = "ECB · ověřené řady";
    public static final String ECB2_BROWSE_FORMAT_REVISION = "2026-05-29-named-groups-v2";
    private static final int DISCOVERY_CAP = 8000;
    private static final int MAX_ROWS_PER_LETTER = 500;
    private static final int LETTER_SPLIT_THRESHOLD = 120;

    private static final List<String> FLOW_PRIORITY = List.of(
            "EXR", "ICP", "MIR", "BSI", "MNA", "BPS", "LFSI", "STBS", "GFS", "EDP", "FM", "BOP", "RESH", "RAS", "RTD",
            "E11", "LCI", "AME", "CBD2", "SEC");

    private static final Map<String, String> FLOW_LABELS = Map.ofEntries(
            Map.entry("EXR", "Směnné kurzy"),
            Map.entry("ICP", "Inflace (HICP)"),
            Map.entry("MIR", "Úrokové sazby"),
            Map.entry("BSI", "Bilance bank (BSI)"),
            Map.entry("MNA", "Národní účty"),
            Map.entry("BPS", "Platební bilance / rezervy"),
            Map.entry("LFSI", "Trh práce"),
            Map.entry("STBS", "Podnikové statistiky"),
            Map.entry("GFS", "Vládní finance"),
            Map.entry("EDP", "EDP"),
            Map.entry("FM", "Finanční trhy"),
            Map.entry("CBD2", "Konsolidovaná bankovní data (CBD2)"),
            Map.entry("SEC", "Cenné papíry"),
            Map.entry("SUP", "Dohledové bankovní statistiky"),
            Map.entry("CBD", "Konsolidovaná bankovní data"),
            Map.entry("BLS", "Průzkum úvěrových podmínek bank"),
            Map.entry("SSI", "Strukturální ukazatele bank"),
            Map.entry("SESFOD", "Financování cenných papírů a deriváty"),
            Map.entry("IVF", "Investiční fondy"),
            Map.entry("OFI", "Ostatní finanční zprostředkovatelé"),
            Map.entry("PSS", "Platební systémy"),
            Map.entry("RESH", "Bydlení"),
            Map.entry("RAS", "Devizové rezervy"),
            Map.entry("RTD", "Real-time"),
            Map.entry("E11", "Vládní výdaje"));

    private final ObjectMapper objectMapper;

    @Getter
    private Map<String, Object> meta = Map.of();

    @Getter
    private boolean filteringEnabled;

    private Map<String, Set<String>> seriesToCountries = Map.of();
    private Map<String, List<String>> countryToSeries = Map.of();
    private final Map<String, Map<String, List<String>>> countryFlowIndex = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<String>>> flowLetterIndex = new ConcurrentHashMap<>();
    private final Map<String, String> groupLabelIndex = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        filteringEnabled = false;
        seriesToCountries = Map.of();
        countryToSeries = Map.of();
        meta = Map.of();
        try (InputStream in = new ClassPathResource("data/ecb_series_availability.json").getInputStream()) {
            Map<String, Object> raw = objectMapper.readValue(in, new TypeReference<>() {});
            meta = new LinkedHashMap<>();
            for (String key : List.of("version", "generated_at", "source", "probed_count", "series_count")) {
                if (raw.get(key) != null) {
                    meta.put(key, raw.get(key));
                }
            }
            if (!(raw.get("series") instanceof Map<?, ?> seriesRaw)) {
                return;
            }
            Map<String, Set<String>> s2c = new HashMap<>();
            Map<String, List<String>> c2s = new HashMap<>();
            for (Map.Entry<?, ?> entry : seriesRaw.entrySet()) {
                String setId = normalizeSeriesKey(String.valueOf(entry.getKey()));
                if (setId.isBlank()) {
                    continue;
                }
                if (!(entry.getValue() instanceof List<?> countries)) {
                    continue;
                }
                List<String> codes = new ArrayList<>();
                for (Object c : countries) {
                    String code = String.valueOf(c).trim().toUpperCase(Locale.ROOT);
                    if (!code.isBlank()) {
                        codes.add(code);
                    }
                }
                if (codes.isEmpty()) {
                    continue;
                }
                s2c.put(setId, Set.copyOf(codes));
                for (String code : codes) {
                    c2s.computeIfAbsent(code, ignored -> new ArrayList<>()).add(setId);
                }
            }
            for (Map.Entry<String, List<String>> entry : c2s.entrySet()) {
                entry.getValue().sort(String::compareTo);
            }
            seriesToCountries = s2c;
            countryToSeries = c2s;
            filteringEnabled = !s2c.isEmpty();
        } catch (Exception ex) {
            log.warn("ECB series availability load failed: {}", ex.getMessage());
        }
    }

    public String availabilityRevision() {
        Object rev = meta.get("generated_at");
        if (rev == null) {
            rev = meta.get("version");
        }
        return rev != null ? String.valueOf(rev) : "";
    }

    public String ecb2BrowseRevision() {
        String base = availabilityRevision();
        return base.isBlank() ? ECB2_BROWSE_FORMAT_REVISION : base + "|" + ECB2_BROWSE_FORMAT_REVISION;
    }

    public boolean discoveryBrowseEnabled() {
        return filteringEnabled;
    }

    public List<String> getAvailableSeries(String countryCode) {
        if (!filteringEnabled) {
            return List.of();
        }
        String code = countryCode != null ? countryCode.trim().toUpperCase(Locale.ROOT) : "";
        return List.copyOf(countryToSeries.getOrDefault(code, List.of()));
    }

    public String normalizeSeriesKey(String raw) {
        String s = raw != null ? raw.trim() : "";
        if (s.isBlank()) {
            return "";
        }
        EcbReference.Parsed ref = EcbReference.parseSetId(s);
        return ref != null ? ref.setIdCompat() : s;
    }

    public Map<String, List<String>> countryFlowIndex(String countryCode) {
        String code = countryCode != null ? countryCode.trim().toUpperCase(Locale.ROOT) : "";
        return countryFlowIndex.computeIfAbsent(code, this::buildCountryFlowIndex);
    }

    public Map<String, List<String>> flowLetterIndex(String countryCode, String flowRef) {
        String code = countryCode != null ? countryCode.trim().toUpperCase(Locale.ROOT) : "";
        String flow = flowRef != null ? flowRef.trim().toUpperCase(Locale.ROOT) : "";
        String cacheKey = code + "|" + flow;
        return flowLetterIndex.computeIfAbsent(cacheKey, ignored -> buildFlowLetterIndex(code, flow));
    }

    public int discoveryCap() {
        return DISCOVERY_CAP;
    }

    public int maxRowsPerLetter() {
        return MAX_ROWS_PER_LETTER;
    }

    public int letterSplitThreshold() {
        return LETTER_SPLIT_THRESHOLD;
    }

    public String flowDisplayName(String flowRef) {
        String fr = stringOrBlank(flowRef).toUpperCase(Locale.ROOT);
        String label = FLOW_LABELS.get(fr);
        return label != null ? fr + " · " + label : fr;
    }

    public String groupLabelForBucket(String countryCode, String flowRef, String groupKey) {
        return groupLabelIndex.getOrDefault(countryCode + "|" + flowRef + "|" + groupKey, groupKey);
    }

    public String normalizeLetterBucket(String letter) {
        String raw = letter != null ? letter.trim() : "";
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Neplatná skupina katalogu: '" + letter + "'");
        }
        String up = raw.toUpperCase(Locale.ROOT);
        if ("#".equals(up) || "0-9".equals(up)) {
            return up;
        }
        if (up.length() == 1 && Character.isLetter(up.charAt(0))) {
            return up;
        }
        String slug = raw.toLowerCase(Locale.ROOT);
        if (slug.matches("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")) {
            return slug;
        }
        throw new IllegalArgumentException("Neplatná skupina katalogu: '" + letter + "'");
    }

    public List<Map<String, Object>> rowsFromSetIds(
            List<String> setIds, String countryCode, String flowRef, String letter) {
        String code = countryCode.toUpperCase(Locale.ROOT);
        String flow = flowRef.toUpperCase(Locale.ROOT);
        String letterKey = letter != null && !letter.isBlank() ? normalizeLetterBucket(letter) : "";
        List<Map<String, Object>> rows = new ArrayList<>();
        String pathSuffix = letterKey.isBlank() ? "" : " > " + letterKey;
        for (String setId : setIds) {
            EcbReference.Parsed ref = EcbReference.parseSetId(setId);
            if (ref == null || !ref.validPreviewTarget()) {
                continue;
            }
            Map<String, Object> qp = new LinkedHashMap<>();
            qp.put("ecb_flow", ref.flowRef());
            qp.put("ecb_series_key", ref.seriesKey());
            qp.put("flowRef", ref.flowRef());
            qp.put("seriesKey", ref.seriesKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("set_id", ref.setIdCompat());
            row.put("name", ref.seriesKey());
            row.put("kind", "selection");
            row.put("item_kind", "selection");
            row.put("ecb_flow", ref.flowRef());
            row.put("ecb_series_key", ref.seriesKey());
            row.put("territory", code);
            row.put("ecb_country", code);
            if (!letterKey.isBlank()) {
                row.put("ecb_letter", letterKey);
            }
            row.put("query_params", qp);
            row.put("path", ECB2_BROWSE_ROOT + " > " + code + " > " + flow + pathSuffix);
            row.put("ecb_browse_source", "discovery_availability");
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> stringOrBlank(r.get("name")).toLowerCase(Locale.ROOT)));
        return rows;
    }

    public List<Map<String, Object>> rowsToSets(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sid = stringOrBlank(row.get("set_id"));
            if (sid.isBlank()) {
                continue;
            }
            Map<String, Object> set = new LinkedHashMap<>(row);
            set.put("set_id", sid);
            set.put("name", stringOrBlank(row.get("name")).isBlank() ? sid : row.get("name"));
            set.put("kind", "selection");
            set.put("item_kind", "selection");
            set.put("ecb_browse_source", "discovery_availability");
            set.put("ecb_browse_row_title", row.get("name"));
            out.add(set);
        }
        return out;
    }

    private Map<String, List<String>> buildCountryFlowIndex(String countryCode) {
        Map<String, List<String>> buckets = new HashMap<>();
        for (String sid : orderSeriesIds(getAvailableSeries(countryCode))) {
            String flow = flowRefFromSetId(sid);
            if (flow.isBlank()) {
                continue;
            }
            buckets.computeIfAbsent(flow, ignored -> new ArrayList<>()).add(sid);
        }
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        buckets.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> e) -> flowPriority(e.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    private Map<String, List<String>> buildFlowLetterIndex(String countryCode, String flowRef) {
        List<String> ids = countryFlowIndex(countryCode).getOrDefault(flowRef, List.of());
        Map<String, List<String>> buckets = new HashMap<>();
        for (String sid : ids) {
            String[] group = browseGroupForSeries(sid, countryCode);
            buckets.computeIfAbsent(group[0], ignored -> new ArrayList<>()).add(sid);
            groupLabelIndex.put(countryCode + "|" + flowRef + "|" + group[0], group[1]);
        }
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        buckets.entrySet().stream()
                .sorted(Comparator.comparing(e -> sortGroupKey(e.getKey(), countryCode, flowRef)))
                .forEach(e -> {
                    List<String> sorted = new ArrayList<>(e.getValue());
                    sorted.sort(Comparator.comparing(this::sortLabelForSeries, String.CASE_INSENSITIVE_ORDER));
                    ordered.put(e.getKey(), sorted);
                });
        return ordered;
    }

    private String[] browseGroupForSeries(String setId, String countryCode) {
        EcbReference.Parsed ref = EcbReference.parseSetId(setId);
        String seriesKey = ref != null ? ref.seriesKey() : setId.contains("/") ? setId.substring(setId.indexOf('/') + 1) : setId;
        String label = firstMeaningfulPart(seriesKey);
        String bucket = letterBucketFromLabel(label);
        return new String[] {bucket, displayGroupLabel(label, bucket)};
    }

    private static String firstMeaningfulPart(String seriesKey) {
        String[] parts = seriesKey.split("\\.");
        int start = 0;
        if (parts.length > 0 && parts[0].length() == 1 && "MQADWH".contains(parts[0].toUpperCase(Locale.ROOT))) {
            start = 1;
        }
        for (int i = start; i < parts.length; i++) {
            String part = parts[i].trim();
            if (!part.isBlank() && part.length() > 1) {
                return part;
            }
        }
        return parts.length > start ? parts[start] : seriesKey;
    }

    private static String letterBucketFromLabel(String label) {
        String raw = label != null ? label.trim() : "";
        if (raw.isBlank()) {
            return "other";
        }
        char ch = Character.toUpperCase(raw.charAt(0));
        if (Character.isLetter(ch)) {
            return String.valueOf(ch);
        }
        if (Character.isDigit(ch)) {
            return "0-9";
        }
        return "other";
    }

    private static String displayGroupLabel(String label, String bucket) {
        if ("other".equals(bucket)) {
            return "Ostatní";
        }
        if ("0-9".equals(bucket)) {
            return "0–9";
        }
        if (bucket.length() == 1) {
            return bucket;
        }
        return label.isBlank() ? bucket : label;
    }

    private String sortLabelForSeries(String setId) {
        EcbReference.Parsed ref = EcbReference.parseSetId(setId);
        return ref != null ? ref.seriesKey() : setId;
    }

    private static String sortGroupKey(String groupKey, String countryCode, String flowRef) {
        if ("other".equals(groupKey) || "#".equals(groupKey)) {
            return "{";
        }
        if ("0-9".equals(groupKey)) {
            return "|";
        }
        return groupKey.toLowerCase(Locale.ROOT);
    }

    private static int flowPriority(String flow) {
        int idx = FLOW_PRIORITY.indexOf(flow);
        return idx >= 0 ? idx : 999;
    }

    private static List<String> orderSeriesIds(List<String> setIds) {
        List<String> copy = new ArrayList<>(setIds);
        copy.sort(Comparator.comparingInt((String sid) -> flowPriority(flowRefFromSetId(sid)))
                .thenComparing(EcbSeriesAvailabilityService::flowRefFromSetId)
                .thenComparing(s -> s));
        return copy;
    }

    private static String flowRefFromSetId(String setId) {
        EcbReference.Parsed ref = EcbReference.parseSetId(setId);
        if (ref != null) {
            return ref.flowRef().toUpperCase(Locale.ROOT);
        }
        if (setId != null && setId.contains("/")) {
            return setId.substring(0, setId.indexOf('/')).trim().toUpperCase(Locale.ROOT);
        }
        return "";
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
