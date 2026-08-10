package cz.bankintel.search;

import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.service.chartagent.ChartContractParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogSeriesExplainService {

    private final OpenAiClient openAiClient;
    private final OpenAiJsonSupport openAiJsonSupport;

    public Map<String, Object> explainSeries(Map<String, Object> body) {
        Map<String, Object> meta = ChartDataQualityService.normalizeMetaPublic(body);
        String title = stringOrBlank(meta.get("title")).isBlank()
                ? stringOrBlank(meta.get("name"))
                : stringOrBlank(meta.get("title"));
        // Vysvětlení řady bylo dřív jen definiční ("co ukazatel měří"), takže "AI nad grafem" ignorovala
        // konkrétní graf, na který se uživatel dívá — a působila hloupě. Když je přiložený ChartDataContract,
        // spočítáme faktické shrnutí (první/poslední/min/max/změna) a necháme AI přečíst i AKTUÁLNÍ graf,
        // striktně z těchto čísel (bez halucinací).
        Map<String, Object> dataSummary = primarySeriesSummary(body, title);
        boolean hasData = !dataSummary.isEmpty();
        boolean aiUsed = false;
        String explanation = null;
        String topicLabel = "";
        String readHint = "";
        if (openAiClient.isConfigured()) {
            try {
                Map<String, Object> parsed = openAiJsonSupport.chatJsonObject(
                        buildSystemPrompt(hasData),
                        buildExplainUserPrompt(title, meta) + buildReadingBlock(dataSummary));
                explanation = stringOrBlank(parsed.get("explanation_cz"));
                topicLabel = stringOrBlank(parsed.get("topic_label_cz"));
                readHint = stringOrBlank(parsed.get("read_hint_cz"));
                aiUsed = !explanation.isBlank();
            } catch (Exception ex) {
                log.warn("OpenAI explain-series failed, using heuristic fallback: {}", ex.getMessage());
            }
        }
        if (explanation == null || explanation.isBlank()) {
            // I bez AI ať vysvětlení mluví o skutečném grafu: k definici připojíme deterministické čtení dat.
            explanation = matchConceptBody(title, meta) + buildCurrentReadingSentence(dataSummary);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", true);
        out.put("ai_used", aiUsed);
        out.put("source", aiUsed ? "openai" : "heuristic");
        if (!aiUsed) {
            out.put("fallback_reason", openAiClient.isConfigured() ? "openai_failed" : "openai_unavailable");
        }
        out.put("title", title);
        out.put("explanation", explanation);
        out.put("explanation_cz", explanation);
        out.put("explanation_cs", explanation);
        if (!topicLabel.isBlank()) {
            out.put("topic_label_cz", topicLabel);
        }
        if (!readHint.isBlank()) {
            out.put("read_hint_cz", readHint);
        }
        out.put("source_type", meta.get("source_type"));
        out.put("set_id", meta.get("set_id"));
        return out;
    }

    public Map<String, Object> askFollowup(Map<String, Object> body) {
        Map<String, Object> meta = ChartDataQualityService.normalizeMetaPublic(body);
        String question = stringOrBlank(body.get("question"));
        if (question.isBlank()) {
            question = stringOrBlank(body.get("user_question"));
        }
        String prior = stringOrBlank(body.get("prior_explanation"));
        if (prior.isBlank()) {
            prior = stringOrBlank(body.get("initial_explanation"));
        }
        String searchQuery = inferCatalogSearch(question, meta, prior);
        Map<String, Object> dataSummary = primarySeriesSummary(body, stringOrBlank(meta.get("title")));
        boolean aiUsed = false;
        String answer = null;
        if (openAiClient.isConfigured() && !question.isBlank()) {
            try {
                Map<String, Object> parsed = openAiJsonSupport.chatJsonObject(
                        """
                        Jsi ekonomický analytik. Odpovídej stručně v češtině (max 120 slov).
                        Nevymýšlej čísla ani fakta mimo dodaný kontext; pokud je přiložený blok
                        „Aktuální data grafu", čísla ber jen z něj. Vrať JSON:
                        {"answer_cz":"...","suggested_catalog_search_query":null nebo "dotaz"}
                        """,
                        buildFollowupUserPrompt(question, prior, meta) + buildReadingBlock(dataSummary));
                answer = stringOrBlank(parsed.get("answer_cz"));
                if (searchQuery == null) {
                    searchQuery = stringOrBlank(parsed.get("suggested_catalog_search_query"));
                    if (searchQuery.isBlank()) {
                        searchQuery = null;
                    }
                }
                aiUsed = !answer.isBlank();
            } catch (Exception ex) {
                log.warn("OpenAI explain-series ask failed: {}", ex.getMessage());
            }
        }
        if (answer == null || answer.isBlank()) {
            answer = buildFollowupAnswer(question, prior, meta);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("ai_used", aiUsed);
        out.put("question", question);
        out.put("answer", answer);
        if (searchQuery != null) {
            out.put("suggested_catalog_search_query", searchQuery);
        }
        return out;
    }

    private static String buildSystemPrompt(boolean hasData) {
        if (hasData) {
            return """
                    Jsi ekonomický analytik. Odpovídej česky, stručně (max ~110 slov). Vysvětli DVĚ věci:
                    1) CO ukazatel měří (definice, metodika);
                    2) CO ukazuje TENTO graf — poslední hodnotu, směr vývoje (růst/pokles) a rozsah za zobrazené období.
                    Pro bod 2 používej VÝHRADNĚ čísla z bloku „Aktuální data grafu"; nikdy si nevymýšlej hodnoty
                    ani období a nedopočítávej data mimo zobrazené období. Vrať pouze JSON:
                    {"explanation_cz":"...","topic_label_cz":"...","read_hint_cz":"..."}
                    """;
        }
        return """
                Jsi ekonomický analytik. Vysvětli CO ukazatel měří (definice, metodika), ne konkrétní hodnoty.
                Piš v češtině, stručně (max 100 slov). Vrať pouze JSON:
                {"explanation_cz":"...","topic_label_cz":"...","read_hint_cz":"..."}
                """;
    }

    /**
     * Faktické shrnutí řady z přiloženého ChartDataContractu — reuse stejného parseru jako ekonomistova
     * cesta ({@code /chart-agent/ask}), takže čtení odpovídá tomu, co je právě v grafu. U multi-series
     * grafu vybere řadu, jejíž popisek nejlépe sedí na název z metadat (jinak první). Prázdná mapa =
     * žádná čitelná data (nebo privátní/nahraná řada) → vysvětlení zůstane jen definiční.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> primarySeriesSummary(Map<String, Object> body, String title) {
        Object contractObj = body == null ? null : body.get("chart_contract");
        if (contractObj instanceof Map<?, ?> raw) {
            Map<String, Object> contract = (Map<String, Object>) raw;
            if (!looksPrivate(contract)) {
                List<Map<String, Object>> series = ChartContractParser.seriesFromContract(contract);
                if (!series.isEmpty()) {
                    Map<String, Object> summary = summarizeSeries(pickSeriesForTitle(series, title));
                    if (!summary.isEmpty()) {
                        return summary;
                    }
                }
            }
        }
        // Fallback: kompaktní shrnutí spočítané na frontendu (✨ v exploreru / widgetech), když volající
        // nemá plný ChartDataContract. Stejný tvar jako summarizeSeries → čtení grafu funguje i tam.
        Object summaryObj = body == null ? null : body.get("chart_summary");
        if (summaryObj instanceof Map<?, ?> rawSummary) {
            return normalizeClientSummary((Map<String, Object>) rawSummary, title);
        }
        return Map.of();
    }

    private static Map<String, Object> normalizeClientSummary(Map<String, Object> raw, String title) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (ChartContractParser.num(raw.get("first_value")) == null
                || ChartContractParser.num(raw.get("last_value")) == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(raw);
        if (stringOrBlank(out.get("label")).isBlank() && !stringOrBlank(title).isBlank()) {
            out.put("label", title);
        }
        if (ChartContractParser.num(out.get("delta")) == null) {
            out.put("delta", ChartContractParser.num(out.get("last_value")) - ChartContractParser.num(out.get("first_value")));
        }
        return out;
    }

    private static boolean looksPrivate(Map<String, Object> contract) {
        Object metaObj = contract.get("metadata");
        if (metaObj instanceof Map<?, ?> m) {
            if (Boolean.TRUE.equals(m.get("contains_private_series"))) {
                return true;
            }
            String privacy = stringOrBlank(m.get("privacy_mode")).toLowerCase(Locale.ROOT);
            return privacy.contains("private");
        }
        return false;
    }

    private static Map<String, Object> pickSeriesForTitle(List<Map<String, Object>> series, String title) {
        if (series.size() == 1) {
            return series.getFirst();
        }
        String t = stringOrBlank(title).toLowerCase(Locale.ROOT);
        if (t.isBlank()) {
            return series.getFirst();
        }
        Map<String, Object> best = null;
        int bestScore = 0;
        for (Map<String, Object> s : series) {
            String label = stringOrBlank(s.get("label")).toLowerCase(Locale.ROOT);
            if (label.isBlank()) {
                continue;
            }
            int score;
            if (t.contains(label) || label.contains(t)) {
                score = 100;
            } else {
                score = 0;
                for (String tok : label.split("[^a-z0-9áčďéěíňóřšťúůýž]+")) {
                    if (tok.length() >= 4 && t.contains(tok)) {
                        score++;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : series.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summarizeSeries(Map<String, Object> series) {
        Object pointsObj = series == null ? null : series.get("points");
        if (!(pointsObj instanceof List<?> points) || points.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> usable = new ArrayList<>();
        for (Object o : points) {
            if (o instanceof Map<?, ?> m && ChartContractParser.num(((Map<String, Object>) m).get("value")) != null) {
                usable.add((Map<String, Object>) m);
            }
        }
        if (usable.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> first = usable.getFirst();
        Map<String, Object> last = usable.getLast();
        Map<String, Object> minPt = first;
        Map<String, Object> maxPt = first;
        for (Map<String, Object> pt : usable) {
            double v = ChartContractParser.num(pt.get("value"));
            if (v < ChartContractParser.num(minPt.get("value"))) {
                minPt = pt;
            }
            if (v > ChartContractParser.num(maxPt.get("value"))) {
                maxPt = pt;
            }
        }
        double firstV = ChartContractParser.num(first.get("value"));
        double lastV = ChartContractParser.num(last.get("value"));
        double delta = lastV - firstV;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", stringOrBlank(series.get("label")));
        out.put("unit", stringOrBlank(series.get("unit")));
        out.put("first_period", stringOrBlank(first.get("period")));
        out.put("first_value", firstV);
        out.put("last_period", stringOrBlank(last.get("period")));
        out.put("last_value", lastV);
        out.put("min_value", ChartContractParser.num(minPt.get("value")));
        out.put("min_period", stringOrBlank(minPt.get("period")));
        out.put("max_value", ChartContractParser.num(maxPt.get("value")));
        out.put("max_period", stringOrBlank(maxPt.get("period")));
        out.put("delta", delta);
        if (firstV != 0.0) {
            out.put("delta_pct", (delta / Math.abs(firstV)) * 100.0);
        }
        out.put("n_points", usable.size());
        return out;
    }

    /** Textový blok s fakty pro AI prompt — čísla, ze kterých smí model číst aktuální graf. */
    private static String buildReadingBlock(Map<String, Object> s) {
        if (s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\nAktuální data grafu (zobrazené období) — používej jen tato čísla, nic si nevymýšlej:\n");
        appendMeta(sb, "Řada", s.get("label"));
        appendMeta(sb, "Jednotka", s.get("unit"));
        sb.append("První bod: ").append(s.get("first_period")).append(" = ").append(fmtNum(s.get("first_value"))).append('\n');
        sb.append("Poslední bod: ").append(s.get("last_period")).append(" = ").append(fmtNum(s.get("last_value"))).append('\n');
        sb.append("Minimum: ").append(fmtNum(s.get("min_value"))).append(" (").append(s.get("min_period"))
                .append("), Maximum: ").append(fmtNum(s.get("max_value"))).append(" (").append(s.get("max_period")).append(")\n");
        sb.append("Změna za období: ").append(fmtNum(s.get("delta")));
        if (s.get("delta_pct") != null) {
            sb.append(" (").append(fmtNum(s.get("delta_pct"))).append(" %)");
        }
        sb.append('\n');
        sb.append("Počet bodů: ").append(s.get("n_points")).append('\n');
        return sb.toString();
    }

    /** Deterministické čtení grafu do fallbacku (bez AI) — 100% přesné, přímo z hodnot. */
    private static String buildCurrentReadingSentence(Map<String, Object> s) {
        if (s.isEmpty()) {
            return "";
        }
        double delta = ChartContractParser.num(s.get("delta"));
        String dir = delta > 0 ? "vzrostla" : delta < 0 ? "klesla" : "je beze změny";
        String unit = stringOrBlank(s.get("unit"));
        String u = unit.isBlank() ? "" : " " + unit;
        StringBuilder sb = new StringBuilder();
        sb.append(" Aktuální graf: hodnota se od ").append(s.get("first_period")).append(" (").append(fmtNum(s.get("first_value")))
                .append(u).append(") do ").append(s.get("last_period")).append(" (").append(fmtNum(s.get("last_value")))
                .append(u).append(") ").append(dir);
        if (s.get("delta_pct") != null) {
            sb.append(" o ").append(fmtNum(s.get("delta_pct"))).append(" %");
        }
        sb.append("; rozsah ").append(fmtNum(s.get("min_value"))).append("–").append(fmtNum(s.get("max_value"))).append(u).append(".");
        return sb.toString();
    }

    private static String fmtNum(Object value) {
        Double d = ChartContractParser.num(value);
        if (d == null) {
            return "n/a";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) (double) d);
        }
        return String.format(Locale.US, "%.2f", d);
    }

    private static String buildExplainUserPrompt(String title, Map<String, Object> meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("Název řady: ").append(title).append('\n');
        appendMeta(sb, "Zdroj", meta.get("source_type"));
        appendMeta(sb, "Set ID", meta.get("set_id"));
        appendMeta(sb, "Indikátor", meta.get("indicator_name"));
        appendMeta(sb, "Země", meta.get("country_label"));
        appendMeta(sb, "Frekvence", meta.get("frequency"));
        appendMeta(sb, "Jednotka", meta.get("unit"));
        return sb.toString();
    }

    private static String buildFollowupUserPrompt(String question, String prior, Map<String, Object> meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("Otázka uživatele: ").append(question).append('\n');
        if (!prior.isBlank()) {
            sb.append("Předchozí vysvětlení: ").append(prior).append('\n');
        }
        appendMeta(sb, "Název řady", meta.get("title"));
        appendMeta(sb, "Zdroj", meta.get("source_type"));
        appendMeta(sb, "Země", meta.get("country_label"));
        return sb.toString();
    }

    private static void appendMeta(StringBuilder sb, String label, Object value) {
        String s = stringOrBlank(value);
        if (!s.isBlank()) {
            sb.append(label).append(": ").append(s).append('\n');
        }
    }

    private static String buildFollowupAnswer(String question, String prior, Map<String, Object> meta) {
        String title = stringOrBlank(meta.get("title"));
        if (question.toLowerCase(Locale.ROOT).contains("proč") || question.toLowerCase(Locale.ROOT).contains("proc")) {
            return "U řady „" + title + "“ jde o statistický ukazatel z katalogu "
                    + stringOrBlank(meta.get("source_type"))
                    + ". Konkrétní hodnoty v grafu závisí na zvolené dimenzi a období; pro alternativy použijte katalogové hledání.";
        }
        return prior.isBlank()
                ? "Zeptejte se na definici ukazatele, frekvenci publikace nebo vhodnou alternativní řadu v katalogu."
                : prior;
    }

    private static String inferCatalogSearch(String question, Map<String, Object> meta, String prior) {
        String q = question.toLowerCase(Locale.ROOT);
        if (!(q.contains("najdi") || q.contains("hledej") || q.contains("jin") || q.contains("find") || q.contains("search"))) {
            return null;
        }
        String country = stringOrBlank(meta.get("country_label"));
        if (q.contains("nezam") || q.contains("unemployment")) {
            return country.isBlank() ? "unemployment rate" : "unemployment rate " + country;
        }
        if (country.isBlank() || q.contains(country.toLowerCase(Locale.ROOT))) {
            return question.length() > 200 ? question.substring(0, 200) : question;
        }
        return (question + " " + country).trim();
    }

    static String matchConceptBody(String title, Map<String, Object> meta) {
        String blob = (title + " " + stringOrBlank(meta.get("indicator_name")) + " "
                        + stringOrBlank(meta.get("source_type")))
                .toLowerCase(Locale.ROOT);
        if (blob.contains("inflac") || blob.contains("cpi") || blob.contains("hicp")) {
            return "Ukazatel měří vývoj cenové hladiny (inflace) — tempo růstu cen spotřebitelského koše v čase.";
        }
        if (blob.contains("gdp") || blob.contains("hrub") || blob.contains("hdp")) {
            return "Ukazatel popisuje objem ekonomické produkce (HDP) — celkovou hodnotu zboží a služeb vyprodukovaných v ekonomice.";
        }
        if (blob.contains("unemployment") || blob.contains("nezam")) {
            return "Ukazatel sleduje podíl nezaměstnaných v aktivní populaci (míra nezaměstnanosti).";
        }
        if (blob.contains("interest") || blob.contains("sazb") || blob.contains("rate")) {
            return "Ukazatel v oblasti úrokových sazeb popisuje cenu peněz nebo náklady úvěrů v čase — pozor na rozdíl policy rate vs. bankovní lending rate.";
        }
        if (blob.contains("loan") || blob.contains("úvěr") || blob.contains("uver") || blob.contains("credit")) {
            return "Ukazatel v oblasti úvěrů popisuje objem nebo růst bankovního financování ekonomiky či sektorů.";
        }
        if (blob.contains("profit") || blob.contains("zisk") || blob.contains("roe")
                || blob.contains("rentabil") || blob.contains("návratnost") || blob.contains("navratnost")) {
            return "Ukazatel měří ziskovost — typicky návratnost kapitálu (ROE) nebo marže bank/sektorů.";
        }
        return "Statistická časová řada „" + (title.isBlank() ? "bez názvu" : title)
                + "“ z katalogu "
                + stringOrBlank(meta.get("source_type"))
                + ". Pro přesnou definici otevřete metadata zdroje nebo použijte katalogové hledání podobných ukazatelů.";
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
