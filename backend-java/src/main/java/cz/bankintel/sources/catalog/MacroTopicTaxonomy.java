package cz.bankintel.sources.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Macro topic taxonomy — port {@code macro_topic_taxonomy.py} (subset for matching).
 *
 * <p>{@link #TOPICS} and {@link #GEO_LABELS} are loaded from
 * {@code catalog/macro_topics_taxonomy.json} at startup (same pattern as
 * {@code CatalogQueryIntent}'s {@code intent_groups.json}).
 */
final class MacroTopicTaxonomy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String FALLBACK_ID = "makro_ostatni";

    static final List<Group> GROUPS = List.of(
            new Group("inflace", "Ceny a inflace"),
            new Group("rust", "Ekonomika"),
            new Group("prace", "Trh práce"),
            new Group("finance", "Finance a úvěry"),
            new Group("mena", "Měna a kurzy"),
            new Group("fiskal", "Veřejné finance"),
            new Group("external", "Zahraniční obchod"),
            new Group("demografie", "Demografie"),
            new Group("komodity", "Komodity a energie"),
            new Group("trhy", "Trhy a rizika"));

    static final List<Topic> TOPICS = loadTopics();

    static final Map<String, String> GEO_LABELS = loadGeoLabels();

    static final Map<String, Topic> TOPIC_BY_ID = buildTopicMap();

    static final Map<String, String> SOURCE_TO_CATALOG = Map.ofEntries(
            Map.entry("arad", "arad"),
            Map.entry("csu", "csu"),
            Map.entry("eurostat", "eurostat"),
            Map.entry("ecb", "ecb2"),
            Map.entry("ecb2", "ecb2"),
            Map.entry("fred", "fred"),
            Map.entry("imf", "imf"),
            Map.entry("worldbank", "data360"),
            Map.entry("world_bank", "data360"),
            Map.entry("data360", "data360"),
            Map.entry("bis", "bis"),
            Map.entry("oecd", "oecd3"),
            Map.entry("oecd3", "oecd3"));

    static final List<Map.Entry<String, String>> CONTINENTS = List.of(
            Map.entry("aggregates", "Agregáty a regiony"),
            Map.entry("europe", "Evropa"),
            Map.entry("north_america", "Severní Amerika"),
            Map.entry("south_america", "Jižní Amerika"),
            Map.entry("asia", "Asie"),
            Map.entry("africa", "Afrika"),
            Map.entry("oceania", "Australie a Oceánie"),
            Map.entry("other", "Ostatní"));

    private MacroTopicTaxonomy() {}

    private static Map<String, Topic> buildTopicMap() {
        Map<String, Topic> out = new LinkedHashMap<>();
        for (Topic topic : TOPICS) {
            out.put(topic.id(), topic);
        }
        return out;
    }

    private record RawTopic(
            @JsonProperty("id") String id,
            @JsonProperty("label_cs") String labelCs,
            @JsonProperty("group_id") String groupId,
            @JsonProperty("indicator_ids") List<String> indicatorIds,
            @JsonProperty("title_any") List<String> titleAny,
            @JsonProperty("title_none") List<String> titleNone) {}

    private static List<Topic> loadTopics() {
        try (InputStream in =
                MacroTopicTaxonomy.class.getResourceAsStream("/catalog/macro_topics_taxonomy.json")) {
            if (in == null) {
                return List.of();
            }
            Map<String, Object> root = MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object rawTopics = root.get("topics");
            if (!(rawTopics instanceof List<?> list)) {
                return List.of();
            }
            List<Topic> out = new ArrayList<>();
            for (Object item : list) {
                RawTopic raw = MAPPER.convertValue(item, RawTopic.class);
                out.add(new Topic(
                        raw.id(),
                        raw.labelCs(),
                        raw.groupId(),
                        Set.copyOf(raw.indicatorIds() == null ? List.of() : raw.indicatorIds()),
                        raw.titleAny() == null ? List.of() : List.copyOf(raw.titleAny()),
                        raw.titleNone() == null ? List.of() : List.copyOf(raw.titleNone())));
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Map<String, String> loadGeoLabels() {
        try (InputStream in =
                MacroTopicTaxonomy.class.getResourceAsStream("/catalog/macro_topics_taxonomy.json")) {
            if (in == null) {
                return Map.of();
            }
            Map<String, Object> root = MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object rawLabels = root.get("geo_labels");
            if (!(rawLabels instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return Map.copyOf(out);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    record Group(String id, String labelCs) {}

    record Topic(
            String id,
            String labelCs,
            String groupId,
            Set<String> indicatorIds,
            List<String> titleAny,
            List<String> titleNone) {

        boolean matches(Map<String, Object> row) {
            String ind = stringOrBlank(row.get("indicator_id"));
            if (ind.isBlank()) {
                ind = stringOrBlank(row.get("codex_economic_pillar"));
            }
            String role = stringOrBlank(row.get("role"));
            String blob = buildBlob(row);
            if (!indicatorIds.isEmpty() && !indicatorIds.contains(ind)) {
                return false;
            }
            if (!titleNone.isEmpty()) {
                for (String kw : titleNone) {
                    if (blob.contains(kw.toLowerCase(Locale.ROOT))) {
                        return false;
                    }
                }
            }
            if (!titleAny.isEmpty()) {
                boolean hit = false;
                for (String kw : titleAny) {
                    if (blob.contains(kw.toLowerCase(Locale.ROOT))) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return false;
                }
            }
            return !indicatorIds.isEmpty() || !titleAny.isEmpty();
        }

        private static String buildBlob(Map<String, Object> row) {
            StringBuilder sb = new StringBuilder();
            for (String key : List.of("title", "series_name", "dataset_name", "description", "label_cs", "final_reason")) {
                sb.append(' ').append(stringOrBlank(row.get(key)).toLowerCase(Locale.ROOT));
            }
            Object qpObj = row.get("query_params");
            if (qpObj instanceof Map<?, ?> qp) {
                sb.append(' ').append(stringOrBlank(qp.get("coicop")).toLowerCase(Locale.ROOT));
            }
            return sb.toString();
        }

        private static String stringOrBlank(Object value) {
            return value != null ? String.valueOf(value).trim() : "";
        }
    }
}
