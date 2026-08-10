package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Port of {@code catalog_search_metadata_sidecar.py} — retrieval rescue for Czech queries
 * (e.g. „zisk bank“) that FTS misses in English titles.
 */
@Component
public class CatalogSearchMetadataSidecar {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchMetadataSidecar.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int SIDECAR_CAP = 40;
    private static final int INTENT_RESCUE_CAP = 20;

    private static final Set<String> ALLOWED_SOURCES = CatalogSourceRegistry.METADATA_SIDECAR_ALLOWED_SOURCES;

    /** Mirrors Python INTENT_RULES (subset used for query-side tag derivation). */
    private static final List<Map.Entry<String, List<String>>> INTENT_RULES = List.of(
            Map.entry("profitability", List.of("zisk", "profit", " roe ", " roa ", "rentabilit", "marže", "marze", "earnings")),
            Map.entry("debt", List.of("dluh", "debt", "zadluz", "zadluž", " bond ", "dluhopis")),
            Map.entry("lending", List.of("uver", "úvěr", " loan", "lending", " credit")),
            Map.entry("mortgage", List.of("hypote", "mortgage")),
            Map.entry("banking", List.of("bank", " mfi ", "monetary financial")),
            Map.entry("retail", List.of("maloobchod", "retail", "spotrebitel", "spotřebitel", "consumer")),
            Map.entry("production", List.of("vyrob", "výrob", "production", " output", "prumysl", "průmysl", "industrial")),
            Map.entry("energy", List.of("energi", "energy", "elektr", "electricity", " plyn", " gas ", " ropa", " oil ", " fuel")),
            Map.entry("inflation", List.of("inflac", "inflation", " cpi ", " hicp", " cen ", " price")),
            Map.entry("gdp", List.of(" hdp ", " gdp ", "domestic product")),
            Map.entry("wages", List.of(" mzd", "mzda", " wage", " salar", " plat ")));

    private final CatalogSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CatalogSearchMetadataSidecar(CatalogSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void logStartup() {
        Path dir = properties.metadataDir();
        int fileCount = 0;
        try {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    fileCount = (int) stream
                            .filter(p -> Files.isRegularFile(p)
                                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsonl"))
                            .count();
                }
            }
        } catch (Exception ex) {
            log.warn("catalog metadata sidecar startup scan failed: {}", ex.getMessage());
        }
        log.info("catalog metadata sidecar dir={} files={}", dir.toAbsolutePath().normalize(), fileCount);
        Thread.startVirtualThread(this::warmIndexes);
    }

    private void warmIndexes() {
        long started = System.currentTimeMillis();
        int loaded = 0;
        for (String source : ALLOWED_SOURCES) {
            if (!loadIndex(source).isEmpty()) {
                loaded++;
            }
        }
        log.info("catalog metadata sidecar warmup loaded_sources={} duration_ms={}", loaded, System.currentTimeMillis() - started);
    }

    public boolean enabledForClassic() {
        String flag = System.getenv("CATALOG_SEARCH_METADATA_ENABLED");
        if (flag != null && !flag.isBlank()) {
            return !Set.of("0", "false", "no", "off").contains(flag.trim().toLowerCase(Locale.ROOT));
        }
        return true;
    }

    /** set_id / dataset_id values to load from FTS lookup (sidecar retrieval rescue). */
    public List<String> sidecarRetrievalSetIds(String source, String queryRaw) {
        return sidecarRetrievalSetIds(source, List.of(queryRaw), SIDECAR_CAP);
    }

    /**
     * Resolves several equivalent query variants in one metadata pass. Deep search previously scanned
     * the complete per-source sidecar once per variant, even though every pass read the same immutable
     * records. Matching any variant preserves the union semantics while avoiding repeated full scans.
     */
    public List<String> sidecarRetrievalSetIds(
            String source, List<String> queryVariants, int maxResults) {
        if (!enabledForClassic()) {
            return List.of();
        }
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (src.isBlank() || !ALLOWED_SOURCES.contains(src) || queryVariants == null || queryVariants.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> index = loadIndex(src);
        if (index.isEmpty()) {
            return List.of();
        }
        List<String> variants = queryVariants.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (variants.isEmpty()) {
            return List.of();
        }
        int resultCap = Math.max(1, maxResults);
        String combinedQuery = String.join(" ", variants);
        List<String> requestedGeoCodes =
                CatalogGeoIntent.requestedGeoCodes(CatalogGeoIntent.detectGeoIntent(combinedQuery));
        List<String> foldedQueries = variants.stream()
                .map(value -> " " + CatalogTextUtils.foldAscii(value) + " ")
                .toList();
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> qTags = variants.stream()
                .flatMap(value -> queryIntentTags(value).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!qTags.isEmpty()) {
            List<IntentHit> intentHits = new ArrayList<>();
            for (Map<String, Object> rec : index.values()) {
                String sid = str(rec.get("series_id"));
                if (sid.isBlank() || seen.contains(sid.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!metadataGeoApplicable(src, rec, requestedGeoCodes)) {
                    continue;
                }
                Set<String> recTags = intentTags(rec);
                int overlap = (int) qTags.stream().filter(recTags::contains).count();
                if (overlap > 0) {
                    intentHits.add(new IntentHit(overlap, sid, str(rec.getOrDefault("dataset_id", sid))));
                }
            }
            intentHits.sort(Comparator.comparingInt((IntentHit h) -> -h.overlap));
            int intentAdded = 0;
            for (IntentHit hit : intentHits) {
                if (seen.contains(hit.seriesId.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                seen.add(hit.seriesId.toLowerCase(Locale.ROOT));
                out.add(hit.seriesId);
                intentAdded++;
                if (out.size() >= resultCap || intentAdded >= INTENT_RESCUE_CAP * variants.size()) {
                    break;
                }
            }
        }
        for (Map<String, Object> rec : index.values()) {
            String sid = str(rec.get("series_id"));
            if (sid.isBlank() || seen.contains(sid.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!metadataGeoApplicable(src, rec, requestedGeoCodes)) {
                continue;
            }
            if (foldedQueries.stream().anyMatch(qf -> keywordHit(qf, rec))) {
                seen.add(sid.toLowerCase(Locale.ROOT));
                out.add(sid);
                if (out.size() >= resultCap) {
                    break;
                }
            }
        }
        return out;
    }

    private static boolean metadataGeoApplicable(String source, Map<String, Object> rec, List<String> requestedCodes) {
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            return true;
        }
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if ("eurostat".equals(src)) {
            return true;
        }
        String scope = CatalogGeoIntent.sourceGeoScope(src);
        if ("CZ".equals(scope)) {
            return requestedCodes.stream().allMatch("CZ"::equals);
        }
        String blob = metadataGeoBlob(rec);
        for (String code : requestedCodes) {
            if (metadataBlobMatchesCountry(blob, code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean metadataBlobMatchesCountry(String blob, String code) {
        String cc = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (cc.isBlank()) {
            return false;
        }
        if (CatalogTextUtils.containsWholeTokenOrPhrase(blob, cc.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String iso3 = CatalogCountryIso3Registry.iso3For(cc);
        if (!iso3.isBlank() && CatalogTextUtils.containsWholeTokenOrPhrase(blob, iso3.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String alias : CatalogCountryAliasRegistry.foldedAliasMatchTerms(cc)) {
            if (alias.length() >= 3 && CatalogTextUtils.containsTokenOrPhrase(blob, alias)) {
                return true;
            }
        }
        return false;
    }

    private static String metadataGeoBlob(Map<String, Object> rec) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of(
                "series_id",
                "dataset_id",
                "title_original",
                "human_label_cs",
                "human_label_en",
                "description_cs",
                "description_en",
                "search_keywords_cs",
                "search_keywords_en",
                "geo_tags")) {
            Object value = rec.get(field);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        parts.add(String.valueOf(item));
                    }
                }
            } else if (value != null) {
                parts.add(String.valueOf(value));
            }
        }
        return CatalogTextUtils.normalizeTokenBoundaries(String.join(" ", parts));
    }

    /** Static intent tag derivation — same rules as instance {@link #queryIntentTags(String)}. */
    public static List<String> deriveQueryIntentTags(String queryRaw) {
        String blob = " " + CatalogTextUtils.foldAscii(queryRaw) + " ";
        List<String> tags = new ArrayList<>();
        for (Map.Entry<String, List<String>> rule : INTENT_RULES) {
            for (String needle : rule.getValue()) {
                if (CatalogTextUtils.containsTokenOrPhrase(blob, needle)) {
                    tags.add(rule.getKey());
                    break;
                }
            }
            if (tags.size() >= 5) {
                break;
            }
        }
        return tags;
    }

    public List<String> queryIntentTags(String queryRaw) {
        return deriveQueryIntentTags(queryRaw);
    }

    public Map<String, Object> getSearchMetadata(String source, String seriesId, String datasetId) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (src.isBlank()) {
            return null;
        }
        Map<String, Map<String, Object>> index = loadIndex(src);
        for (String key : lookupKeys(src, seriesId, datasetId)) {
            Map<String, Object> rec = index.get(key);
            if (rec != null) {
                return rec;
            }
        }
        return null;
    }

    private static List<String> lookupKeys(String src, String seriesId, String datasetId) {
        List<String> keys = new ArrayList<>();
        String sid = str(seriesId);
        String did = str(datasetId);
        if (!sid.isBlank()) {
            keys.add(src + "|" + sid.toLowerCase(Locale.ROOT));
        }
        if (!did.isBlank() && !did.equalsIgnoreCase(sid)) {
            keys.add(src + "|" + did.toLowerCase(Locale.ROOT));
        }
        if (sid.contains("/")) {
            keys.add(src + "|" + sid.split("/", 2)[0].toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static boolean keywordHit(String qf, Map<String, Object> rec) {
        for (String field : List.of("search_keywords_cs", "search_keywords_en", "human_label_cs", "human_label_en")) {
            Object values = rec.get(field);
            List<String> list = new ArrayList<>();
            if (values instanceof String s) {
                list.add(s);
            } else if (values instanceof List<?> raw) {
                for (Object item : raw) {
                    if (item != null) {
                        list.add(String.valueOf(item));
                    }
                }
            }
            for (String kw : list) {
                String kf = CatalogTextUtils.foldAscii(kw).trim();
                if (kf.length() < 2) {
                    continue;
                }
                if (CatalogTextUtils.containsWholeTokenOrPhrase(qf, kf)) {
                    return true;
                }
                String[] words = CatalogTextUtils.normalizeTokenBoundaries(kf).split("\\s+");
                List<String> longWords = new ArrayList<>();
                for (String w : words) {
                    if (w.length() >= 3) {
                        longWords.add(w);
                    }
                }
                if (longWords.size() == 1 && CatalogTextUtils.containsWholeTokenOrPhrase(qf, longWords.get(0))) {
                    return true;
                }
                if (longWords.size() >= 2
                        && longWords.stream().allMatch(w -> CatalogTextUtils.containsTokenOrPhrase(qf, w))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> intentTags(Map<String, Object> rec) {
        Set<String> out = new LinkedHashSet<>();
        Object raw = rec.get("intent_tags");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String tag = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
                if (!tag.isBlank()) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    private Map<String, Map<String, Object>> loadIndex(String source) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        if (src.isBlank()) {
            return Map.of();
        }
        Path path = properties.metadataPath(src);
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            cache.put(src, new CacheEntry(System.currentTimeMillis(), 0L, 0L, index));
            return index;
        }
        long modifiedAtMs = 0L;
        long sizeBytes = 0L;
        try {
            modifiedAtMs = Files.getLastModifiedTime(path).toMillis();
            sizeBytes = Files.size(path);
        } catch (Exception ex) {
            log.debug("metadata sidecar stat failed source={}: {}", src, ex.getMessage());
        }
        CacheEntry cached = cache.get(src);
        if (cached != null && cached.modifiedAtMs == modifiedAtMs && cached.sizeBytes == sizeBytes) {
            return cached.index;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.trim();
                if (text.isBlank() || text.startsWith("#")) {
                    continue;
                }
                try {
                    Map<String, Object> rec = objectMapper.readValue(text, MAP_TYPE);
                    String sid = str(rec.get("series_id"));
                    String did = str(rec.getOrDefault("dataset_id", sid));
                    if (sid.isBlank()) {
                        continue;
                    }
                    String recSrc = str(rec.getOrDefault("source", src)).toLowerCase(Locale.ROOT);
                    index.put(recSrc + "|" + sid.toLowerCase(Locale.ROOT), rec);
                    String didKey = recSrc + "|" + did.toLowerCase(Locale.ROOT);
                    if (!did.equalsIgnoreCase(sid) && !index.containsKey(didKey)) {
                        index.put(didKey, rec);
                    }
                } catch (Exception lineEx) {
                    log.debug("metadata sidecar skip bad line source={}: {}", src, lineEx.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("metadata sidecar load failed source={}: {}", src, ex.getMessage());
        }
        cache.put(src, new CacheEntry(System.currentTimeMillis(), modifiedAtMs, sizeBytes, index));
        return index;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record IntentHit(int overlap, String seriesId, String datasetId) {}

    private record CacheEntry(long loadedAtMs, long modifiedAtMs, long sizeBytes, Map<String, Map<String, Object>> index) {}
}
