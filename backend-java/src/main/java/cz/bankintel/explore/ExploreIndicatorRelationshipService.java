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
        return analyze(loadedItems, question, sector, ExploreUserDataPrivacy.STRICT_PRIVATE);
    }

    /**
     * {@code privacyMode} rozhoduje, jestli nahrané uživatelské řady (source_type "user_upload")
     * smí vůbec vstoupit do korelace/trendu/mediánu. Za „Strict private" se úplně vynechají -
     * nechci stavět dvojí verzi výpočtu (jednu pro lokální zobrazení, jednu maskovanou pro AI
     * prompt), když jde párování jednoduše přeskočit a poslat AI jen katalogové řady.
     */
    public RelationshipsResult analyze(
            List<Map<String, Object>> loadedItems, String question, String sector, String privacyMode) {
        boolean excludeUploads = ExploreUserDataPrivacy.isStrict(privacyMode);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> item : loadedItems) {
            if (excludeUploads && "user_upload".equals(str(item.get("source_type")))) {
                continue;
            }
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
                    // Živě zjištěno: appka nabízela ukazatele bez rozlišení, jestli jde o
                    // odvětvovou nebo makro řadu, takže vybraný pár nedával ekonomicky smysl o
                    // nic spolehlivěji, než náhoda. manager_category je jediné pole, co je
                    // spolehlivě dostupné na každém kandidátovi (viz ExploreDiscoveryService/
                    // ExploreSectorService) - jemnější "stejný konkrétní segment" spolehlivě
                    // dostupné není.
                    .append(" | kategorie=")
                    .append(str(item.get("manager_category")))
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
                U korelace dej přednost párování podle pole "kategorie" každého ukazatele:
                nejdřív dvě odvětvové řady mezi sebou (sector_indicators/leading_
                indicators/cost_indicators/financial_indicators/external_indicators/risk_
                indicators), pak odvětvová řada s makro řadou (macro_indicators). Dvě makro řady
                mezi sebou navrhni JEN když je pro to konkrétní věcný důvod (např. inflace vs.
                úrokové sazby patří k sobě přímo; HDP a náhodná FX řada bez souvislosti k dotazu
                ne) - není to zákaz, jen vodítko, neignoruj kvůli němu jinak smysluplný pár.
                Vrať JSON: {"relationships":[{"type":"correlation|trend|median","series_a":"<set_id>","series_b":"<set_id nebo null>","reason":"krátce proč"}]}
                """
                        .formatted(MAX_PROPOSALS);
        String user = "Otázka: " + nullSafe(question) + "\nSegment: " + nullSafe(sector) + "\n\nDostupné řady:\n" + catalog;
        try {
            // forceDeterministic=true: tohle volání rozhoduje, KTERÝ pár řad uživatel uvidí -
            // stejná kategorie rozhodnutí jako plánovač/reranker (viz OpenAiClient), jen dřív na
            // ni nikdo tu samou opravu neaplikoval. Naměřeno živě: stejný dotaz, stejný běh,
            // pokaždé jiný navržený pár.
            JsonNode json = openAiClient.chatCompletionJson(system, user, OpenAiModelTask.CHAT, true);
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

    static Map<String, Object> computeCorrelation(Map<String, Object> a, Map<String, Object> b, String reason) {
        Map<String, Double> byPeriodA = periodValueMap(observationsOf(a));
        Map<String, Double> byPeriodB = periodValueMap(observationsOf(b));
        List<String> commonPeriods = new ArrayList<>();
        for (String period : byPeriodA.keySet()) {
            if (byPeriodB.containsKey(period)) {
                commonPeriods.add(period);
            }
        }
        if (commonPeriods.size() < MIN_OVERLAP_FOR_CORRELATION) {
            return null;
        }
        // Period řetězce (YYYY-MM, YYYY-Qn, YYYY) se v projektu řadí lexikograficky jako chronologicky
        // (viz ExploreSummarizeFetchService, ManagerMirrorFetchSupport) — netřeba parsovat kalendář.
        commonPeriods.sort(String::compareTo);

        // Korelace se počítá z mezidobních změn, ne z hladin. Makroukazatele (inflace, mzdy, tržby)
        // skoro vždy v čase rostou nebo klesají, takže na hladinách vyjde vysoká korelace i mezi
        // řadami bez věcné souvislosti — obě jen táhne stejný trend ("spurious correlation").
        // Rozdíly mezi po sobě jdoucími pozorováními sdílený trend odstraní.
        List<Double> dxs = new ArrayList<>();
        List<Double> dys = new ArrayList<>();
        for (int i = 1; i < commonPeriods.size(); i++) {
            dxs.add(byPeriodA.get(commonPeriods.get(i)) - byPeriodA.get(commonPeriods.get(i - 1)));
            dys.add(byPeriodB.get(commonPeriods.get(i)) - byPeriodB.get(commonPeriods.get(i - 1)));
        }
        double r = pearsonCorrelation(dxs, dys);
        if (Double.isNaN(r)) {
            return null;
        }
        int n = dxs.size();
        boolean significant = isSignificant(r, n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "correlation");
        out.put("series_a_title", a.get("title"));
        out.put("series_a_set_id", a.get("set_id"));
        out.put("series_b_title", b.get("title"));
        out.put("series_b_set_id", b.get("set_id"));
        out.put("value", round(r, 2));
        out.put("sample_points", n);
        out.put("significant", significant);
        out.put("reason", reason);
        out.put(
                "description",
                "Korelace mezidobních změn „"
                        + str(a.get("title"))
                        + "“ a „"
                        + str(b.get("title"))
                        + "“: "
                        + round(r, 2)
                        + " ("
                        + correlationStrengthCz(r)
                        + ") za "
                        + n
                        + " společných období"
                        + (significant
                                ? ", statisticky průkazné (p < 0,05)"
                                : " — při tomto počtu pozorování statisticky neprůkazné, může jít o náhodu")
                        + ".");
        return out;
    }

    /**
     * Dvouvýběrový t-test pro Pearsonovo r na hladině p = 0,05 (oboustranně). Bez testu značí
     * kód jako "silnou vazbu" i korelaci spočtenou z hrstky pozorování, kde by podobně vysoké r
     * vyšlo i z čistého šumu.
     */
    private static boolean isSignificant(double r, int n) {
        int df = n - 2;
        if (df < 1) {
            return false;
        }
        double t = r * Math.sqrt(df / (1 - r * r));
        return Math.abs(t) >= criticalTValue(df);
    }

    private static final double[] CRITICAL_T_DF_1_TO_30 = {
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
    };

    /** Kritická hodnota Studentova t-rozdělení; nad 30 stupňů volnosti se rychle blíží normálnímu. */
    private static double criticalTValue(int df) {
        if (df >= 1 && df <= 30) {
            return CRITICAL_T_DF_1_TO_30[df - 1];
        }
        if (df <= 40) {
            return 2.021;
        }
        if (df <= 60) {
            return 2.000;
        }
        if (df <= 120) {
            return 1.980;
        }
        return 1.960;
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
        // Normalizace sklonu průměrem selhává u řad, které procházejí nulou (saldo bilance,
        // saldo rozpočtu, čistý sentiment, meziroční tempa): jmenovatel jde k nule a procento
        // k nekonečnu — vznikaly věty typu „průměrná změna 847 % za období". Procento se proto
        // uvádí jen tehdy, když je průměr vůči rozpětí řady dost velký na to, aby dával smysl;
        // jinak se hlásí sklon přímo v jednotkách řady.
        double min = ys.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = ys.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double range = Math.abs(max - min);
        boolean meanIsUsableBase = Math.abs(mean) > 0.0 && Math.abs(mean) >= 0.1 * range;
        double normalizedSlopePct = meanIsUsableBase ? (slope / Math.abs(mean)) * 100.0 : 0.0;
        // Směr se určuje ze sklonu, ne z (možná nespočitatelného) procenta — jinak by řada
        // s prudkým trendem kolem nuly vyšla jako „stabilní".
        String direction = trendDirectionCz(
                meanIsUsableBase ? normalizedSlopePct : slopeDirectionPct(slope, range));
        String changeText = meanIsUsableBase
                ? "průměrná změna " + round(normalizedSlopePct, 2) + " % na pozorování"
                : "průměrná změna " + round(slope, 3) + " na pozorování (v jednotkách řady)";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "trend");
        out.put("series_a_title", a.get("title"));
        out.put("series_a_set_id", a.get("set_id"));
        out.put("value", round(meanIsUsableBase ? normalizedSlopePct : slope, meanIsUsableBase ? 2 : 3));
        out.put("value_is_percent", meanIsUsableBase);
        out.put("slope_per_step", round(slope, 4));
        out.put("sample_points", ys.size());
        out.put("reason", reason);
        out.put(
                "description",
                "Trend „"
                        + str(a.get("title"))
                        + "“: "
                        + direction
                        + " ("
                        + changeText
                        + ", z "
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
            String period = canonicalPeriod(str(obs.get("period")));
            Double value = toDouble(obs.get("value"));
            if (!period.isBlank() && value != null) {
                out.put(period, value);
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern ISO_DATE_WITH_DAY =
            java.util.regex.Pattern.compile("^(\\d{4}-\\d{2})-\\d{2}(?:[T ].*)?$");

    /**
     * Živě potvrzeno: tři různé cesty načtení (ExploreSummarizeFetchService.normalizeObservations,
     * ManagerSeriesCacheReader, ManagerMirrorFetchSupport) si period string berou z trochu jiných
     * syrových polí, žádná ho nekanonizuje - dvě řady stejného období, které přišly různou cestou,
     * tak mohly mít "2024-01" na jedné straně a "2024-01-15" na druhé, a přesná shoda klíče
     * ({@link #periodValueMap}) je nespárovala. Řeší se jen tady, na místě porovnání - tři
     * upstream producenty period stringu se nemění.
     */
    static String canonicalPeriod(String raw) {
        String period = raw == null ? "" : raw.trim();
        java.util.regex.Matcher matcher = ISO_DATE_WITH_DAY.matcher(period);
        return matcher.matches() ? matcher.group(1) : period;
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

    /** Sklon vůči rozpětí řady — náhrada za procento z průměru, když průměr leží u nuly. */
    private static double slopeDirectionPct(double slope, double range) {
        return range > 0.0 ? (slope / range) * 100.0 : 0.0;
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
