package cz.bankintel.sources.catalog;
import cz.bankintel.util.BankIntelEnvVars;

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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** Macro topic browse index — port {@code macro_topic_catalog.py}. */
@Service
public class MacroTopicCatalogService {

    private static final Set<String> LOCAL_ONLY = Set.of("csu", "arad");
    private static final Set<String> COMPARISON_PANEL = Set.of("inflace_celkova", "hdp_rust");

    private final ObjectMapper objectMapper;
    private final AtomicReference<IndexCache> indexCache = new AtomicReference<>();

    public MacroTopicCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getOverview() {
        Index index = buildIndex();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (MacroTopicTaxonomy.Group group : MacroTopicTaxonomy.GROUPS) {
            List<Map<String, Object>> topics = new ArrayList<>();
            for (MacroTopicTaxonomy.Topic topic : MacroTopicTaxonomy.TOPICS) {
                if (!topic.groupId().equals(group.id())) {
                    continue;
                }
                int count = index.topicCounts().getOrDefault(topic.id(), 0);
                if (count <= 0) {
                    continue;
                }
                topics.add(Map.of(
                        "id", topic.id(),
                        "label_cs", topic.labelCs(),
                        "series_count", count,
                        "country_count", index.topicGeo().getOrDefault(topic.id(), Map.of()).size()));
            }
            if (!topics.isEmpty()) {
                groups.add(Map.of("id", group.id(), "label_cs", group.labelCs(), "topics", topics));
            }
        }
        int fallback = index.topicCounts().getOrDefault(MacroTopicTaxonomy.FALLBACK_ID, 0);
        if (fallback > 0) {
            groups.add(Map.of(
                    "id", "ostatni",
                    "label_cs", "Ostatní",
                    "topics", List.of(Map.of(
                            "id", MacroTopicTaxonomy.FALLBACK_ID,
                            "label_cs", "Ostatní makro ukazatele",
                            "series_count", fallback,
                            "country_count", index.topicGeo().getOrDefault(MacroTopicTaxonomy.FALLBACK_ID, Map.of()).size()))));
        }
        List<Map<String, Object>> countriesRaw = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : index.geoCounts().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(e -> geoLabel(e.getKey())))
                .toList()) {
            countriesRaw.add(countryEntry(entry.getKey(), entry.getValue(), index.geoTopic().getOrDefault(entry.getKey(), Map.of()).size()));
        }
        List<Map<String, Object>> countryGroups = groupCountriesForPicker(countriesRaw);
        List<Map<String, Object>> countries = new ArrayList<>();
        for (Map<String, Object> group : countryGroups) {
            Object groupCountries = group.get("countries");
            if (groupCountries instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        countries.add(toStringObjectMap(map));
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic_count", groups.stream().mapToInt(g -> ((List<?>) g.get("topics")).size()).sum());
        out.put("series_count", loadRuntimeSeries().size());
        out.put("indexed_links", index.topicCounts().values().stream().mapToInt(Integer::intValue).sum());
        out.put("country_count", countries.size());
        out.put("comparison_panel_size", COMPARISON_PANEL.size());
        out.put("comparison_panel_topics", COMPARISON_PANEL.stream().toList());
        out.put("groups", groups);
        out.put("countries", countries);
        out.put("country_groups", countryGroups);
        return out;
    }

    public Map<String, Object> browseByTopic(String topicId, String geo, int limit) {
        MacroTopicTaxonomy.Topic topic = MacroTopicTaxonomy.TOPIC_BY_ID.get(topicId);
        if (topic == null) {
            return Map.of("error", "unknown_topic", "topic_id", topicId);
        }
        Index index = buildIndex();
        Map<String, List<Map<String, Object>>> byGeo = index.topicGeo().getOrDefault(topicId, Map.of());
        if (geo != null && !geo.isBlank()) {
            String g = normalizeGeo(geo);
            List<Map<String, Object>> series = new ArrayList<>(byGeo.getOrDefault(g, List.of()));
            if (series.size() > limit) {
                series = series.subList(0, limit);
            }
            return Map.of(
                    "mode", "topic",
                    "topic", Map.of("id", topicId, "label_cs", topic.labelCs()),
                    "country", Map.of("code", g, "label_cs", geoLabel(g)),
                    "series", series);
        }
        List<Map<String, Object>> countriesRaw = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byGeo.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<Map<String, Object>>>>comparingInt(e -> -e.getValue().size())
                        .thenComparing(e -> geoLabel(e.getKey())))
                .toList()) {
            countriesRaw.add(countryEntry(entry.getKey(), entry.getValue().size(), index.geoTopic().getOrDefault(entry.getKey(), Map.of()).size()));
        }
        List<Map<String, Object>> countryGroups = groupCountriesForPicker(countriesRaw);
        List<Map<String, Object>> countries = new ArrayList<>();
        for (Map<String, Object> group : countryGroups) {
            Object groupCountries = group.get("countries");
            if (groupCountries instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        countries.add(toStringObjectMap(map));
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "topic");
        out.put("topic", Map.of("id", topicId, "label_cs", topic.labelCs(), "group_id", topic.groupId()));
        out.put("countries", countries);
        out.put("country_groups", countryGroups);
        out.put("comparison_panel_size", COMPARISON_PANEL.size());
        return out;
    }

    public Map<String, Object> browseByCountry(String countryCode, String topicId, int limit) {
        String geo = normalizeGeo(countryCode);
        if (geo.isBlank()) {
            return Map.of("error", "missing_country");
        }
        Index index = buildIndex();
        Map<String, List<Map<String, Object>>> byTopic = index.geoTopic().getOrDefault(geo, Map.of());
        if (topicId != null && !topicId.isBlank()) {
            MacroTopicTaxonomy.Topic topic = MacroTopicTaxonomy.TOPIC_BY_ID.get(topicId);
            if (topic == null) {
                return Map.of("error", "unknown_topic", "topic_id", topicId);
            }
            List<Map<String, Object>> series = new ArrayList<>(byTopic.getOrDefault(topicId, List.of()));
            if (series.size() > limit) {
                series = series.subList(0, limit);
            }
            return Map.of(
                    "mode", "country",
                    "country", Map.of("code", geo, "label_cs", geoLabel(geo)),
                    "topic", Map.of("id", topicId, "label_cs", topic.labelCs()),
                    "series", series);
        }
        List<Map<String, Object>> topics = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byTopic.entrySet()) {
            if (!COMPARISON_PANEL.contains(entry.getKey())) {
                continue;
            }
            MacroTopicTaxonomy.Topic tdef = MacroTopicTaxonomy.TOPIC_BY_ID.get(entry.getKey());
            if (tdef == null) {
                continue;
            }
            List<Map<String, Object>> rows = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.getKey());
            row.put("label_cs", tdef.labelCs());
            row.put("group_id", tdef.groupId());
            row.put("series_count", rows.size());
            row.put("preview", rows.size() > 2 ? rows.subList(0, 2) : rows);
            topics.add(row);
        }
        return Map.of(
                "mode", "country",
                "country", Map.of("code", geo, "label_cs", geoLabel(geo)),
                "topics", topics,
                "comparison_panel_size", COMPARISON_PANEL.size());
    }

    private Index buildIndex() {
        Path path = dataPath();
        long mtime = 0;
        try {
            if (Files.isRegularFile(path)) {
                mtime = Files.getLastModifiedTime(path).toMillis();
            }
        } catch (IOException ex) {
            return Index.empty();
        }
        IndexCache cached = indexCache.get();
        if (cached != null && cached.mtime() == mtime) {
            return cached.index();
        }
        Index built = buildIndexFresh();
        indexCache.set(new IndexCache(mtime, built));
        return built;
    }

    private Index buildIndexFresh() {
        Map<String, Map<String, List<Map<String, Object>>>> topicGeo = new LinkedHashMap<>();
        Map<String, Map<String, List<Map<String, Object>>>> geoTopic = new LinkedHashMap<>();
        Map<String, Integer> topicCounts = new LinkedHashMap<>();
        Map<String, Integer> geoCounts = new LinkedHashMap<>();
        for (Map<String, Object> raw : loadRuntimeSeries()) {
            List<String> topics = assignTopics(raw);
            if (topics.isEmpty()) {
                continue;
            }
            List<String> geos = geosForRow(raw);
            for (String tid : topics) {
                topicCounts.merge(tid, 1, Integer::sum);
            }
            for (String geo : geos) {
                geoCounts.merge(geo, 1, Integer::sum);
                for (String tid : topics) {
                    Map<String, Object> pub = seriesPublicRow(raw, tid, geo);
                    upsert(topicGeo.computeIfAbsent(tid, ignored -> new LinkedHashMap<>()).computeIfAbsent(geo, ignored -> new ArrayList<>()), pub);
                    upsert(geoTopic.computeIfAbsent(geo, ignored -> new LinkedHashMap<>()).computeIfAbsent(tid, ignored -> new ArrayList<>()), pub);
                }
            }
        }
        sortBuckets(topicGeo);
        sortBuckets(geoTopic);
        return new Index(topicGeo, geoTopic, topicCounts, geoCounts);
    }

    private static void sortBuckets(Map<String, Map<String, List<Map<String, Object>>>> buckets) {
        for (Map<String, List<Map<String, Object>>> inner : buckets.values()) {
            for (List<Map<String, Object>> rows : inner.values()) {
                rows.sort(Comparator
                        .comparing((Map<String, Object> r) -> stringOrBlank(r.get("geo_label")))
                        .thenComparing(r -> stringOrBlank(r.get("name"))));
            }
        }
    }

    private static void upsert(List<Map<String, Object>> bucket, Map<String, Object> pub) {
        String sid = stringOrBlank(pub.get("set_id"));
        for (Map<String, Object> existing : bucket) {
            if (stringOrBlank(existing.get("set_id")).equals(sid)) {
                return;
            }
        }
        bucket.add(pub);
    }

    private List<Map<String, Object>> loadRuntimeSeries() {
        Path path = dataPath();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            Object rowsObj = payload.get("series");
            if (!(rowsObj instanceof List<?> rows)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object rowObj : rows) {
                if (!(rowObj instanceof Map<?, ?> row)) {
                    continue;
                }
                Map<String, Object> map = toStringObjectMap(row);
                if (!"yes".equalsIgnoreCase(stringOrBlank(map.get("runtime_default_use")))) {
                    continue;
                }
                if (!"use".equalsIgnoreCase(stringOrBlank(map.get("catalog_qc_status")))) {
                    continue;
                }
                if (Boolean.TRUE.equals(map.get("runtime_exclude"))) {
                    continue;
                }
                if (LOCAL_ONLY.contains(stringOrBlank(map.get("source")).toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(map);
            }
            return out;
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static List<String> assignTopics(Map<String, Object> row) {
        List<String> matched = new ArrayList<>();
        for (MacroTopicTaxonomy.Topic topic : MacroTopicTaxonomy.TOPICS) {
            if (topic.matches(row)) {
                matched.add(topic.id());
            }
        }
        if (!matched.isEmpty()) {
            return matched;
        }
        if (!stringOrBlank(row.get("indicator_id")).isBlank()) {
            return List.of(MacroTopicTaxonomy.FALLBACK_ID);
        }
        return List.of();
    }

    private static List<String> geosForRow(Map<String, Object> row) {
        List<String> out = new ArrayList<>();
        Object geoObj = row.get("geo");
        if (geoObj instanceof String s) {
            out.add(normalizeGeo(s));
        } else if (geoObj instanceof List<?> list) {
            for (Object item : list) {
                String g = normalizeGeo(String.valueOf(item));
                if (!g.isBlank() && !out.contains(g)) {
                    out.add(g);
                }
            }
        }
        if (out.isEmpty()) {
            Object qpObj = row.get("query_params");
            if (qpObj instanceof Map<?, ?> qp) {
                for (String key : List.of("geo", "imf_country", "country", "ecb_country", "wb_country")) {
                    Object value = qp.get(key);
                    if (value != null) {
                        String g = normalizeGeo(String.valueOf(value));
                        if (!g.isBlank()) {
                            out.add(g);
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add("GLOBAL");
        }
        return out;
    }

    private static Map<String, Object> seriesPublicRow(Map<String, Object> row, String topicId, String geo) {
        String src = stringOrBlank(row.get("source")).toLowerCase(Locale.ROOT);
        String setId = composeSetId(row);
        String name = stringOrBlank(row.get("title"));
        if (name.isBlank()) {
            name = stringOrBlank(row.get("series_name"));
        }
        if (name.isBlank()) {
            name = stringOrBlank(row.get("label_cs"));
        }
        if (name.isBlank()) {
            name = setId;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic_id", topicId);
        out.put("source", src);
        out.put("catalog_id", MacroTopicTaxonomy.SOURCE_TO_CATALOG.getOrDefault(src, src));
        out.put("set_id", setId);
        out.put("name", name);
        out.put("title", name);
        out.put("geo", geo);
        out.put("geo_label", geoLabel(geo));
        out.put("unit", row.get("unit"));
        out.put("frequency", row.get("publication_frequency_norm") != null ? row.get("publication_frequency_norm") : row.get("frequency"));
        out.put("indicator_id", row.get("indicator_id"));
        out.put("role", row.get("role"));
        out.put("label_cs", row.get("label_cs"));
        out.put("query_params", row.get("query_params"));
        out.put("kind", "set");
        out.put("item_kind", "selection");
        out.put("source_type", src);
        return out;
    }

    private static String composeSetId(Map<String, Object> row) {
        String sid = stringOrBlank(row.get("dataset_id"));
        if (sid.isBlank()) {
            sid = stringOrBlank(row.get("series_id"));
        }
        String src = stringOrBlank(row.get("source")).toLowerCase(Locale.ROOT);
        Object qpObj = row.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            if ("imf".equals(src)) {
                String country = stringOrBlank(qp.get("imf_country")).toUpperCase(Locale.ROOT);
                String flow = stringOrBlank(qp.get("imf_flow"));
                if (flow.isBlank()) {
                    flow = "WEO";
                }
                String ind = stringOrBlank(qp.get("imf_indicator")).toUpperCase(Locale.ROOT);
                if (country.isBlank()) {
                    ind = stringOrBlank(row.get("series_id")).toUpperCase(Locale.ROOT);
                }
                if (!country.isBlank() && !ind.isBlank()) {
                    return "IMF|IMF.RES|" + flow + "|9.0.0|" + country + "." + ind;
                }
            }
        }
        return sid.isBlank() ? stringOrBlank(row.get("series_id")) : sid;
    }

    private static Map<String, Object> countryEntry(String code, int seriesCount, int topicCount) {
        return Map.of(
                "code", code,
                "label_cs", geoLabel(code),
                "series_count", seriesCount,
                "topic_count", topicCount);
    }

    private static List<Map<String, Object>> groupCountriesForPicker(List<Map<String, Object>> countriesRaw) {
        Map<String, List<Map<String, Object>>> byContinent = new LinkedHashMap<>();
        for (Map<String, Object> country : countriesRaw) {
            String continent = continentForGeo(stringOrBlank(country.get("code")));
            byContinent.computeIfAbsent(continent, ignored -> new ArrayList<>()).add(country);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> continent : MacroTopicTaxonomy.CONTINENTS) {
            List<Map<String, Object>> countries = byContinent.get(continent.getKey());
            if (countries == null || countries.isEmpty()) {
                continue;
            }
            out.add(Map.of("id", continent.getKey(), "label_cs", continent.getValue(), "countries", countries));
        }
        return out;
    }

    private static String continentForGeo(String geo) {
        if (Set.of("GLOBAL", "WORLD", "GLO", "EU", "EA", "U2", "U6", "WLD").contains(geo)) {
            return "aggregates";
        }
        if (Set.of("CZ", "DE", "PL", "SK", "AT", "FR", "IT", "ES", "GB", "SE", "NO", "CH", "NL", "BE").contains(geo)) {
            return "europe";
        }
        if (Set.of("US", "CA", "MX").contains(geo)) {
            return "north_america";
        }
        if (Set.of("BR", "AR", "CL", "CO").contains(geo)) {
            return "south_america";
        }
        if (Set.of("CN", "JP", "KR", "IN", "ID", "SG", "TR", "IL").contains(geo)) {
            return "asia";
        }
        if (Set.of("ZA", "NG", "EG", "KE").contains(geo)) {
            return "africa";
        }
        if (Set.of("AU", "NZ").contains(geo)) {
            return "oceania";
        }
        return "other";
    }

    private static String normalizeGeo(String code) {
        String c = stringOrBlank(code).toUpperCase(Locale.ROOT);
        return switch (c) {
            case "WORLD", "GLO", "WLD" -> "GLOBAL";
            case "EU27", "EU27_2020" -> "U6";
            case "EA20" -> "U2";
            default -> c;
        };
    }

    private static String geoLabel(String code) {
        return MacroTopicTaxonomy.GEO_LABELS.getOrDefault(code, code);
    }

    private static Path dataPath() {
        String configured = BankIntelEnvVars.get("MACRO_TOPIC_CATALOG_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return BankIntelDataPaths.dataDir().resolve("final_macro_series_selection_sector_relevance.json");
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

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record Index(
            Map<String, Map<String, List<Map<String, Object>>>> topicGeo,
            Map<String, Map<String, List<Map<String, Object>>>> geoTopic,
            Map<String, Integer> topicCounts,
            Map<String, Integer> geoCounts) {
        static Index empty() {
            return new Index(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private record IndexCache(long mtime, Index index) {}
}
