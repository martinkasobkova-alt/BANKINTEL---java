package cz.bankintel.explore.manager;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Read-first lookup v MongoDB kolekci {@code manager_series_cache} (parity s Pythonem). */
@Service
@RequiredArgsConstructor
public class ManagerSeriesCacheReader {

    private static final Logger log = LoggerFactory.getLogger(ManagerSeriesCacheReader.class);
    private static final String COLLECTION = "manager_series_cache";

    private static final Map<String, String> SOURCE_ALIASES = Map.of(
            "financial_markets", "financial_markets_mirror",
            "ecb2", "ecb",
            "world_bank", "worldbank",
            "world_bank_data360", "worldbank",
            "oecd_local", "oecd");

    private final ManagerSeriesCacheMongoConnection mongoConnection;

    public boolean isAvailable() {
        return mongoConnection.isAvailable();
    }

    public Optional<List<Map<String, Object>>> readObservations(Map<String, Object> ref, String geo) {
        if (!isAvailable() || ref == null || ref.isEmpty()) {
            return Optional.empty();
        }
        String src = str(ref.get("source_type")).toLowerCase(Locale.ROOT);
        if (src.isBlank() || "user_upload".equals(src)) {
            return Optional.empty();
        }
        List<String> geoVariants = normalizeGeoVariants(firstNonBlank(geo, resolveRefGeo(ref)));
        if (geoVariants.isEmpty()) {
            return Optional.empty();
        }
        List<String> seriesIds = seriesIdCandidates(ref);
        if (seriesIds.isEmpty()) {
            return Optional.empty();
        }
        try {
            MongoCollection<Document> coll = mongoConnection.collection(COLLECTION);
            for (String source : sourceVariants(src)) {
                for (String geoU : geoVariants) {
                    for (String seriesId : seriesIds) {
                        Document doc = findLatest(coll, Filters.and(
                                Filters.eq("source", source),
                                Filters.eq("series_id", seriesId),
                                Filters.eq("geo", geoU)));
                        if (doc != null) {
                            return observationsFromDoc(doc);
                        }
                        doc = findLatest(coll, Filters.and(
                                Filters.eq("source", source),
                                Filters.eq("dataset_id", seriesId),
                                Filters.eq("geo", geoU)));
                        if (doc != null) {
                            return observationsFromDoc(doc);
                        }
                    }
                    String segmentId = str(ref.get("segment_id"));
                    if (!segmentId.isBlank()) {
                        List<Bson> orClauses = new ArrayList<>();
                        for (String seriesId : seriesIds) {
                            orClauses.add(Filters.eq("series_id", seriesId));
                            orClauses.add(Filters.eq("dataset_id", seriesId));
                        }
                        Document doc = findLatest(
                                coll,
                                Filters.and(
                                        Filters.eq("segment_id", segmentId),
                                        Filters.eq("source", source),
                                        Filters.eq("geo", geoU),
                                        Filters.or(orClauses),
                                        Filters.ne("freshness", "unavailable")));
                        if (doc != null) {
                            return observationsFromDoc(doc);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("manager_series_cache read failed: {}", ex.getMessage());
        }
        return Optional.empty();
    }

    private static Document findLatest(MongoCollection<Document> coll, Bson filter) {
        return coll.find(filter)
                .sort(Sorts.orderBy(Sorts.descending("latest_period"), Sorts.descending("updated_at")))
                .limit(1)
                .first();
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<Map<String, Object>>> observationsFromDoc(Document doc) {
        List<Map<String, Object>> raw = new ArrayList<>();
        Object obs5y = doc.get("observations_5y");
        if (obs5y instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) {
                    raw.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
        }
        if (raw.isEmpty()) {
            Object obs = doc.get("observations");
            if (obs instanceof List<?> list) {
                for (Object row : list) {
                    if (row instanceof Map<?, ?> map) {
                        raw.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
            }
        }
        if (raw.isEmpty()) {
            String latestPeriod = str(doc.get("latest_period"));
            Object latestValue = doc.get("latest_value");
            if (!latestPeriod.isBlank() && latestValue != null) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("period", latestPeriod);
                point.put("date", latestPeriod);
                point.put("value", latestValue);
                raw.add(point);
            }
        }
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Double value = toDouble(row.get("value"));
            if (value == null) {
                value = toDouble(row.get("y"));
            }
            String period = firstNonBlank(row.get("period"), row.get("date"), row.get("x"));
            if (value == null || period.isBlank()) {
                continue;
            }
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("period", period);
            obs.put("date", period);
            obs.put("value", value);
            normalized.add(obs);
        }
        normalized.sort((a, b) -> str(a.get("period")).compareTo(str(b.get("period"))));
        return normalized.size() >= 1 ? Optional.of(normalized) : Optional.empty();
    }

    private static List<String> seriesIdCandidates(Map<String, Object> ref) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : List.of("series_id", "set_id", "dataset_id")) {
            addSeriesId(out, str(ref.get(key)));
        }
        Object qpObj = ref.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            for (String key :
                    List.of("acea_series_id", "eba_series_id", "entsoe_series_id", "csu_selection_code", "arad_set_id")) {
                addSeriesId(out, str(qp.get(key)));
            }
        }
        return new ArrayList<>(out);
    }

    private static void addSeriesId(Set<String> out, String raw) {
        String val = str(raw);
        if (val.isBlank()) {
            return;
        }
        out.add(val);
        if (val.contains("/")) {
            out.add(val.substring(val.lastIndexOf('/') + 1));
        }
        for (String prefix : List.of("eurostat/", "ecb/", "ecb2/", "imf/", "worldbank/", "oecd/")) {
            if (val.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(val.substring(prefix.length()));
            }
        }
    }

    private static List<String> sourceVariants(String source) {
        String normalized = SOURCE_ALIASES.getOrDefault(source, source);
        List<String> variants = new ArrayList<>();
        variants.add(normalized);
        if (normalized.endsWith("_mirror")) {
            variants.add(normalized.substring(0, normalized.length() - "_mirror".length()));
        }
        String alias = SOURCE_ALIASES.get(normalized);
        if (alias != null && !variants.contains(alias)) {
            variants.add(alias);
        }
        return variants;
    }

    private static List<String> normalizeGeoVariants(String geo) {
        String geoU = str(geo).toUpperCase(Locale.ROOT);
        if (geoU.isBlank()) {
            return List.of();
        }
        List<String> variants = new ArrayList<>();
        variants.add(geoU);
        if ("GR".equals(geoU)) {
            variants.add("EL");
        } else if ("EL".equals(geoU)) {
            variants.add("GR");
        }
        return variants;
    }

    @SuppressWarnings("unchecked")
    private static String resolveRefGeo(Map<String, Object> ref) {
        Object qpObj = ref.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            for (String key : List.of("geo", "country", "ref_area", "REF_AREA")) {
                String val = str(qp.get(key)).toUpperCase(Locale.ROOT);
                if (!val.isBlank()) {
                    return val;
                }
            }
        }
        return firstNonBlank(ref.get("context_country"), ref.get("country"), ref.get("geo"));
    }

    private static Double toDouble(Object value) {
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

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }
}
