package cz.bankintel.sources.ecb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
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

    // Dodatek pro 11 ICP polozkovych kodu bez specifickeho lidskeho nazvu presunut do sdilene
    // EcbItemCodeHints (pouziva i CatalogIndexStore pro katalogove hledani, ne jen tenhle browse
    // strom) - viz jeji javadoc pro puvod/historii tehle konstanty.

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
    private final CatalogIndexStore catalogIndexStore;

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

        List<EcbReference.Parsed> parsedRefs = new ArrayList<>();
        for (String setId : setIds) {
            EcbReference.Parsed ref = EcbReference.parseSetId(setId);
            if (ref != null && ref.validPreviewTarget()) {
                parsedRefs.add(ref);
            }
        }
        // Appka uz davno ma pro tyhle rady lidsky nazev indexovany v catalog_rows_lookup (stejna
        // data, co pouziva klasicke hledani) - jeden davkovy dotaz na cely seznam, ne per-radek.
        Map<String, Map<String, Object>> enrichedBySetId =
                lookupEnrichedRows(parsedRefs.stream().map(EcbReference.Parsed::setIdCompat).distinct().toList());

        for (EcbReference.Parsed ref : parsedRefs) {
            Map<String, Object> qp = new LinkedHashMap<>();
            qp.put("ecb_flow", ref.flowRef());
            qp.put("ecb_series_key", ref.seriesKey());
            qp.put("flowRef", ref.flowRef());
            qp.put("seriesKey", ref.seriesKey());
            Map<String, Object> enriched = enrichedBySetId.get(ref.setIdCompat());
            String enrichedName = enriched != null ? stringOrBlank(enriched.get("name")) : "";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("set_id", ref.setIdCompat());
            // Beze shody v obohacenem indexu (melo by byt vzacne) zustava dnesni fallback na
            // syrovy SDMX klic - nikdy prazdne/null.
            row.put(
                    "name",
                    enrichedName.isBlank()
                            ? ref.seriesKey()
                            : EcbItemCodeHints.withUnresolvedItemHint(enrichedName, ref.flowRef(), ref.seriesKey()));
            row.put("kind", "selection");
            row.put("item_kind", "selection");
            row.put("ecb_flow", ref.flowRef());
            row.put("ecb_series_key", ref.seriesKey());
            row.put("territory", code);
            row.put("ecb_country", code);
            if (!letterKey.isBlank()) {
                row.put("ecb_letter", letterKey);
            }
            if (enriched != null) {
                String subtitle = stringOrBlank(enriched.get("ecb_subtitle"));
                if (!subtitle.isBlank()) {
                    row.put("ecb_subtitle", subtitle);
                }
            }
            row.put("query_params", qp);
            row.put("path", ECB2_BROWSE_ROOT + " > " + code + " > " + flow + pathSuffix);
            row.put("ecb_browse_source", "discovery_availability");
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> stringOrBlank(r.get("name")).toLowerCase(Locale.ROOT)));
        return rows;
    }


    private Map<String, Map<String, Object>> lookupEnrichedRows(List<String> setIds) {
        if (setIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> enriched = catalogIndexStore.lookupRowsBySetIds("ecb2", setIds, setIds.size());
        Map<String, Map<String, Object>> bySetId = new HashMap<>();
        for (Map<String, Object> row : enriched) {
            String sid = stringOrBlank(row.get("set_id"));
            if (!sid.isBlank()) {
                bySetId.put(sid, row);
            }
        }
        return bySetId;
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
        Map<String, String> descriptors = lookupValueDescriptors(ids);
        Grouping grouping = resolveGrouping(ids, countryCode, descriptors);
        for (Map.Entry<String, String> entry : grouping.labels().entrySet()) {
            groupLabelIndex.put(countryCode + "|" + flowRef + "|" + entry.getKey(), entry.getValue());
        }
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        grouping.buckets().entrySet().stream()
                .sorted(Comparator.comparing(e -> sortGroupKey(e.getKey(), countryCode, flowRef)))
                .forEach(e -> {
                    List<String> sorted = new ArrayList<>(e.getValue());
                    sorted.sort(Comparator.comparing(this::sortLabelForSeries, String.CASE_INSENSITIVE_ORDER));
                    ordered.put(e.getKey(), sorted);
                });
        return ordered;
    }

    record Grouping(Map<String, List<String>> buckets, Map<String, String> labels) {}

    /** Group by the enriched descriptor when it actually discriminates within this country+flow
     * group; otherwise keep today's letter-bucket behaviour untouched. A descriptor segment that
     * is a great bucket key for one flow (e.g. ICP) can be near-constant for another (e.g. MIR) -
     * verified per flow against real data (see AUDIT_2026-09-03.md, osmnacta vlna) rather than
     * assumed, so this is measured from the actual resulting distribution, not a flow allowlist. */
    Grouping resolveGrouping(List<String> ids, String countryCode, Map<String, String> descriptors) {
        Grouping grouping = groupSeries(ids, countryCode, descriptors);
        if (!descriptors.isEmpty() && !wellDiscriminated(grouping.buckets(), ids.size())) {
            grouping = groupSeries(ids, countryCode, Map.of());
        }
        return grouping;
    }

    private Grouping groupSeries(List<String> ids, String countryCode, Map<String, String> descriptors) {
        Map<String, List<String>> buckets = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (String sid : ids) {
            String[] group = browseGroupForSeries(sid, countryCode, descriptors.get(sid));
            buckets.computeIfAbsent(group[0], ignored -> new ArrayList<>()).add(sid);
            labels.put(group[0], group[1]);
        }
        return new Grouping(buckets, labels);
    }

    /** At least 2 buckets, and no single bucket swallowing (almost) everything - thresholds are
     * empirical, tuned against real per-flow distributions (see AUDIT_2026-09-03.md / the
     * accompanying analysis), not a guess. */
    static boolean wellDiscriminated(Map<String, List<String>> buckets, int totalCount) {
        if (buckets.size() < 2 || totalCount <= 0) {
            return false;
        }
        int largest = buckets.values().stream().mapToInt(List::size).max().orElse(0);
        return largest <= totalCount * 0.9;
    }

    private Map<String, String> lookupValueDescriptors(List<String> ids) {
        Map<String, Map<String, Object>> enriched = lookupEnrichedRows(ids.stream().distinct().toList());
        Map<String, String> descriptors = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : enriched.entrySet()) {
            String descriptor = stringOrBlank(entry.getValue().get("ecb_value_descriptor"));
            if (!descriptor.isBlank()) {
                descriptors.put(entry.getKey(), descriptor);
            }
        }
        return descriptors;
    }

    private String[] browseGroupForSeries(String setId, String countryCode, String valueDescriptor) {
        String candidate = descriptorCandidateLabel(valueDescriptor);
        if (candidate != null) {
            String slug = slugify(candidate);
            // Jednoznakovy slug by se v normalizeLetterBucket choval jako legacy pismenkovy
            // kbelik (vraci se uppercase) - necham tenhle pripad na legacy vetvi, aby se klice
            // nerozjely (kbelik ulozeny jako "a", ale po kliknuti hledany jako "A").
            if (!slug.isBlank() && slug.length() > 1) {
                return new String[] {slug, candidate};
            }
        }
        return browseGroupForSeries(setId, countryCode);
    }

    private String[] browseGroupForSeries(String setId, String countryCode) {
        EcbReference.Parsed ref = EcbReference.parseSetId(setId);
        String seriesKey = ref != null ? ref.seriesKey() : setId.contains("/") ? setId.substring(setId.indexOf('/') + 1) : setId;
        String label = firstMeaningfulPart(seriesKey);
        String bucket = letterBucketFromLabel(label);
        return new String[] {bucket, displayGroupLabel(label, bucket)};
    }

    static String descriptorCandidateLabel(String valueDescriptor) {
        if (valueDescriptor == null || valueDescriptor.isBlank()) {
            return null;
        }
        int sep = valueDescriptor.indexOf(" · ");
        String first = (sep >= 0 ? valueDescriptor.substring(0, sep) : valueDescriptor).trim();
        return !first.isBlank() && !looksLikeRawCode(first) ? first : null;
    }

    /** ECB's enriched descriptor falls back to raw SDMX dimension codes (e.g. "S1", "W0", or the
     * series key itself) when no human label mapping exists for that dimension - real per-flow
     * sampling (BPS/CBD2/MNA/RAS) showed these are always a single space-free token, unlike any
     * genuine human phrase observed (including ones with an embedded "." like MIR's "...
     * (S.122)", which is why this doesn't key off punctuation). Checking for a lowercase letter
     * alone isn't enough either - some real COICOP category labels are shouted in full caps
     * (e.g. ICP's "HICP - FOOD AND NON-ALCOHOLIC BEVERAGES") but are still genuine multi-word
     * text, not a code, so only reject when NEITHER signal is present. */
    static boolean looksLikeRawCode(String text) {
        return text.chars().noneMatch(Character::isLowerCase) && !text.contains(" ");
    }

    static String slugify(String text) {
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 64) {
            slug = slug.substring(0, 64).replaceAll("-+$", "");
        }
        return slug;
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
