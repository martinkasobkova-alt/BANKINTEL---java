package cz.bankintel.explore;

import com.fasterxml.jackson.databind.JsonNode;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes REAL statistical relationships between the loaded indicators (correlation, trend,
 * median) instead of letting the AI narrate them from raw numbers. The LLM's role is scoped to
 * proposing WHICH relationships are worth computing (it sees only titles/set_ids, never values) —
 * the actual numbers (Pearson correlation, linear-regression trend, median) are computed here in
 * Java from each series' own {@code observations}, so the figures reported to the manager are
 * real, not LLM-estimated.
 */
@Service
@RequiredArgsConstructor
public class ExploreIndicatorRelationshipService {

    private static final Logger log = LoggerFactory.getLogger(ExploreIndicatorRelationshipService.class);
    private static final int MAX_PROPOSALS = 6;
    private static final int MIN_OBSERVATIONS_TO_CONSIDER = 3;
    private static final int MIN_OVERLAP_FOR_CORRELATION = 6;

    private final OpenAiClient openAiClient;

    public record RelationshipsResult(List<Map<String, Object>> relationships, String digest) {
        static RelationshipsResult empty() {
            return new RelationshipsResult(List.of(), "");
        }
    }

    public RelationshipsResult analyze(List<Map<String, Object>> loadedItems, String question, String sector) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> item : loadedItems) {
            if (observationsOf(item).size() >= MIN_OBSERVATIONS_TO_CONSIDER) {
                candidates.add(item);
            }
        }
        if (candidates.size() < 2 || !openAiClient.isConfigured()) {
            return RelationshipsResult.empty();
        }
        List<Map<String, Object>> proposals = proposeRelationships(candidates, question, sector);
        List<Map<String, Object>> computed = new ArrayList<>();
        for (Map<String, Object> proposal : proposals) {
            Map<String, Object> result = computeOne(proposal, candidates);
            if (result != null) {
                computed.add(result);
            }
        }
        return new RelationshipsResult(computed, buildDigest(computed));
    }

    private List<Map<String, Object>> proposeRelationships(
            List<Map<String, Object>> items, String question, String sector) {
        StringBuilder catalog = new StringBuilder();
        for (Map<String, Object> item : items) {
            catalog.append("- set_id=")
                    .append(str(item.get("set_id")))
                    .append(" | ")
                    .append(str(item.get("title")))
                    .append(" | zdroj=")
                    .append(str(item.get("source_type")))
                    .append('\n');
        }
        String system =
                """
                Jsi datový analytik. Z nabídnutých ukazatelů navrhni, které vztahy MÁ SMYSL SPOČÍTAT
                (čísla dopočítá kód, ty jen vybíráš co a proč — nevymýšlej hodnoty).
                Typy:
                "correlation" — vztah DVOU řad, jen když má věcný smysl (např. inflace vs. úrokové sazby,
                průmyslová výroba vs. tržby v odvětví). Vyžaduje series_a i series_b.
                "trend" — vývoj JEDNÉ řady v čase. Jen series_a.
                "median" — typická/střední hodnota JEDNÉ řady za období. Jen series_a.
                Vyber jen dvojice/řady se skutečnou vypovídací hodnotou pro manažerské rozhodnutí,
                ne mechanicky všechny kombinace. Max %d návrhů.
                Vrať JSON: {"relationships":[{"type":"correlation|trend|median","series_a":"<set_id>","series_b":"<set_id nebo null>","reason":"krátce proč"}]}
                """
                        .formatted(MAX_PROPOSALS);
        String user = "Otázka: " + nullSafe(question) + "\nSegment: " + nullSafe(sector) + "\n\nDostupné řady:\n" + catalog;
        try {
            JsonNode json = openAiClient.chatCompletionJson(system, user, OpenAiModelTask.CHAT);
            JsonNode relationships = extractRelationshipsNode(json);
            List<Map<String, Object>> out = new ArrayList<>();
            if (relationships != null && relationships.isArray()) {
                for (JsonNode node : relationships) {
                    if (out.size() >= MAX_PROPOSALS) {
                        break;
                    }
                    Map<String, Object> proposal = new LinkedHashMap<>();
                    proposal.put("type", node.path("type").asText("").trim().toLowerCase(Locale.ROOT));
                    proposal.put("series_a", node.path("series_a").asText("").trim());
                    proposal.put("series_b", node.path("series_b").asText("").trim());
                    proposal.put("reason", node.path("reason").asText("").trim());
                    out.add(proposal);
                }
            }
            return out;
        } catch (Exception ex) {
            log.debug("relationship proposal failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private static JsonNode extractRelationshipsNode(JsonNode json) {
        if (json == null) {
            return null;
        }
        if (json.has("relationships")) {
            return json.get("relationships");
        }
        JsonNode content = json.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(content.asText())
                        .get("relationships");
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> computeOne(Map<String, Object> proposal, List<Map<String, Object>> items) {
        String type = str(proposal.get("type"));
        Map<String, Object> seriesA = findBySetId(items, str(proposal.get("series_a")));
        if (seriesA == null) {
            return null;
        }
        switch (type) {
            case "correlation" -> {
                Map<String, Object> seriesB = findBySetId(items, str(proposal.get("series_b")));
                if (seriesB == null || seriesB == seriesA) {
                    return null;
                }
                return computeCorrelation(seriesA, seriesB, str(proposal.get("reason")));
            }
            case "trend" -> {
                return computeTrend(seriesA, str(proposal.get("reason")));
            }
            case "median" -> {
                return computeMedian(seriesA, str(proposal.get("reason")));
            }
            default -> {
                return null;
            }
        }
    }

    private Map<String, Object> computeCorrelation(Map<String, Object> a, Map<String, Object> b, String reason) {
        Map<String, Double> byPeriodA = periodValueMap(observationsOf(a));
        Map<String, Double> byPeriodB = periodValueMap(observationsOf(b));
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (Map.Entry<String, Double> entry : byPeriodA.entrySet()) {
            Double y = byPeriodB.get(entry.getKey());
            if (y != null) {
                xs.add(entry.getValue());
                ys.add(y);
            }
        }
        if (xs.size() < MIN_OVERLAP_FOR_CORRELATION) {
            return null;
        }
        double r = pearsonCorrelation(xs, ys);
        if (Double.isNaN(r)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "correlation");
        out.put("series_a_title", a.get("title"));
        out.put("series_a_set_id", a.get("set_id"));
        out.put("series_b_title", b.get("title"));
        out.put("series_b_set_id", b.get("set_id"));
        out.put("value", round(r, 2));
        out.put("sample_points", xs.size());
        out.put("reason", reason);
        out.put(
                "description",
                "Korelace mezi „"
                        + str(a.get("title"))
                        + "“ a „"
                        + str(b.get("title"))
                        + "“: "
                        + round(r, 2)
                        + " ("
                        + correlationStrengthCz(r)
                        + ") za "
                        + xs.size()
                        + " společných období.");
        return out;
    }

    private Map<String, Object> computeTrend(Map<String, Object> a, String reason) {
        List<Map<String, Object>> observations = observationsOf(a);
        if (observations.size() < MIN_OBSERVATIONS_TO_CONSIDER) {
            return null;
        }
        List<Double> ys = new ArrayList<>();
        for (Map<String, Object> obs : observations) {
            Double v = toDouble(obs.get("value"));
            if (v != null) {
                ys.add(v);
            }
        }
        if (ys.size() < MIN_OBSERVATIONS_TO_CONSIDER) {
            return null;
        }
        double slope = linearRegressionSlope(ys);
        double mean = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double normalizedSlopePct = mean == 0.0 ? 0.0 : (slope / Math.abs(mean)) * 100.0;
        String direction = trendDirectionCz(normalizedSlopePct);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "trend");
        out.put("series_a_title", a.get("title"));
        out.put("series_a_set_id", a.get("set_id"));
        out.put("value", round(normalizedSlopePct, 2));
        out.put("sample_points", ys.size());
        out.put("reason", reason);
        out.put(
                "description",
                "Trend „"
                        + str(a.get("title"))
                        + "“: "
                        + direction
                        + " (průměrná změna "
                        + round(normalizedSlopePct, 2)
                        + " % za období, z "
                        + ys.size()
                        + " pozorování).");
        return out;
    }

    private Map<String, Object> computeMedian(Map<String, Object> a, String reason) {
        List<Double> ys = new ArrayList<>();
        for (Map<String, Object> obs : observationsOf(a)) {
            Double v = toDouble(obs.get("value"));
            if (v != null) {
                ys.add(v);
            }
        }
        if (ys.isEmpty()) {
            return null;
        }
        double median = median(ys);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "median");
        out.put("series_a_title", a.get("title"));
        out.put("series_a_set_id", a.get("set_id"));
        out.put("value", round(median, 2));
        out.put("sample_points", ys.size());
        out.put("reason", reason);
        out.put(
                "description",
                "Medián „" + str(a.get("title")) + "“ za sledované období: " + round(median, 2) + " (z " + ys.size()
                        + " pozorování).");
        return out;
    }

    private static String buildDigest(List<Map<String, Object>> computed) {
        if (computed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> rel : computed) {
            sb.append("- ").append(str(rel.get("description"))).append('\n');
        }
        return sb.toString().trim();
    }

    private static Map<String, Double> periodValueMap(List<Map<String, Object>> observations) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> obs : observations) {
            String period = str(obs.get("period"));
            Double value = toDouble(obs.get("value"));
            if (!period.isBlank() && value != null) {
                out.put(period, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> observationsOf(Map<String, Object> item) {
        Object raw = item == null ? null : item.get("observations");
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        return List.of();
    }

    private static Map<String, Object> findBySetId(List<Map<String, Object>> items, String setId) {
        if (setId == null || setId.isBlank()) {
            return null;
        }
        for (Map<String, Object> item : items) {
            if (setId.equalsIgnoreCase(str(item.get("set_id")))) {
                return item;
            }
        }
        return null;
    }

    private static double pearsonCorrelation(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        double meanX = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanY = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double cov = 0.0;
        double varX = 0.0;
        double varY = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = xs.get(i) - meanX;
            double dy = ys.get(i) - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX == 0.0 || varY == 0.0) {
            return Double.NaN;
        }
        return cov / Math.sqrt(varX * varY);
    }

    private static double linearRegressionSlope(List<Double> ys) {
        int n = ys.size();
        double meanX = (n - 1) / 2.0;
        double meanY = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            num += dx * (ys.get(i) - meanY);
            den += dx * dx;
        }
        return den == 0.0 ? 0.0 : num / den;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String correlationStrengthCz(double r) {
        double abs = Math.abs(r);
        String strength =
                abs >= 0.7 ? "silná" : abs >= 0.4 ? "středně silná" : abs >= 0.2 ? "slabá" : "prakticky žádná";
        String sign = r > 0 ? "pozitivní" : r < 0 ? "negativní" : "neutrální";
        return strength + " " + sign + " vazba";
    }

    private static String trendDirectionCz(double normalizedSlopePct) {
        if (Math.abs(normalizedSlopePct) < 1.0) {
            return "stabilní";
        }
        return normalizedSlopePct > 0 ? "rostoucí" : "klesající";
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
