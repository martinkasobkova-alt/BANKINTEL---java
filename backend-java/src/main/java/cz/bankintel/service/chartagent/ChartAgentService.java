package cz.bankintel.service.chartagent;

import cz.bankintel.service.research.WebResearchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChartAgentService {

    private final ChartAgentEconomistService economistService;
    private final ChartAgentPlanner chartAgentPlanner;
    private final WebResearchService webResearchService;

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeChartQuestion(Map<String, Object> payload) {
        String question = ChartContractParser.str(payload.get("question"));
        Map<String, Object> contract = payload.get("chart_contract") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        Map<String, Object> olapCube = payload.get("olap_cube") instanceof Map<?, ?> cube
                ? (Map<String, Object>) cube
                : Map.of();
        String analysisContext = ChartContractParser.str(payload.get("analysis_context")).toLowerCase();
        List<Map<String, String>> conversationHistory =
                ChartConversationContext.historyBrief(payload.get("conversation_history"));
        List<Map<String, Object>> conversationState =
                ChartConversationContext.stateBrief(payload.get("conversation_state"));
        Map<String, Object> privacyAudit = ChartPrivacySupport.chartPrivacyAudit(contract);
        Map<String, Object> plannerContract = Boolean.TRUE.equals(privacyAudit.get("contains_private_series"))
                ? ChartPrivacySupport.sanitizeChartContractForAi(contract)
                : contract;
        Map<String, Object> plan = chartAgentPlanner.planChartQuestion(
                question, plannerContract, conversationHistory, conversationState);
        List<String> operations = plan.get("operations") instanceof List<?> ops
                ? ops.stream().map(String::valueOf).toList()
                : List.of();
        List<Map<String, Object>> seriesList = ChartContractParser.seriesFromContract(contract);
        Map<String, Object> primary = seriesList.isEmpty() ? null : resolveTargetSeries(question, seriesList);

        List<Map<String, Object>> calculations = new ArrayList<>();
        List<Map<String, Object>> chartActions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Object planWarnings = plan.get("warnings");
        if (planWarnings instanceof List<?> list) {
            for (Object item : list) {
                warnings.add(String.valueOf(item));
            }
        }
        List<String> answerParts = new ArrayList<>();

        if (operations.contains("clear_period_annotations")) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "clear_period_annotations");
            action.put("query", resolvedQuestion(plan, question));
            action.put("target_cz", resolvedQuestion(plan, question));
            action.put("scope", "matching_or_all");
            chartActions.add(action);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("answer_cz", "Odstraním odpovídající anotace z aktuálního grafu.");
            out.put("plan", plan);
            out.put("calculations", List.of());
            out.put("chart_actions", chartActions);
            out.put("warnings", List.of());
            out.put("privacy_audit", privacyAudit);
            out.put("execution_ready", true);
            return out;
        }

        if (seriesList.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("answer_cz", "V grafu nejsou čitelná numerická data pro analýzu.");
            out.put("plan", plan);
            out.put("calculations", List.of());
            out.put("chart_actions", List.of());
            warnings.add("Prázdný ChartDataContract.");
            out.put("warnings", warnings);
            out.put("privacy_audit", privacyAudit);
            return out;
        }

        if (operations.contains("web_event_annotations")) {
            String researchQuestion = resolvedQuestion(plan, question);
            Map<String, Object> researched = new LinkedHashMap<>(
                    webResearchService.researchGraphEvents(researchQuestion, chartResearchContext(plannerContract)));
            researched.put("plan", plan);
            researched.put("privacy_audit", privacyAudit);
            Object actions = researched.get("chart_actions");
            boolean executionReady = actions instanceof List<?> list && !list.isEmpty();
            researched.put("execution_ready", executionReady);
            researched.put("warnings", executionReady ? List.of() : warnings);
            return researched;
        }
        if (operations.contains("discover_external_data")) {
            String researchQuestion = resolvedQuestion(plan, question);
            Map<String, Object> researched = new LinkedHashMap<>(
                    webResearchService.discoverExternalDatasets(researchQuestion, chartResearchContext(plannerContract)));
            researched.put("plan", plan);
            researched.put("privacy_audit", privacyAudit);
            researched.put("warnings", warnings);
            return researched;
        }

        if (operations.contains("latest_available")) {
            List<Map<String, Object>> rows = latestAvailableRows(seriesList);
            if (!rows.isEmpty()) {
                calculations.add(Map.of("type", "latest_available", "rows", rows));
                List<Map<String, Object>> shown = rows.stream().limit(3).toList();
                StringBuilder latestText = new StringBuilder();
                for (int i = 0; i < shown.size(); i++) {
                    Map<String, Object> row = shown.get(i);
                    if (i > 0) {
                        latestText.append("; ");
                    }
                    latestText
                            .append(row.get("series_label"))
                            .append(": posledni bod v grafu je ")
                            .append(row.get("period"))
                            .append(" = ")
                            .append(fmt(row.get("value"), 3));
                    String unit = ChartContractParser.str(row.get("unit"));
                    if (!unit.isBlank()) {
                        latestText.append(' ').append(unit);
                    }
                }
                String extra = rows.size() > shown.size()
                        ? " Zobrazuji prvni " + shown.size() + " z " + rows.size() + " rad."
                        : "";
                answerParts.add(
                        latestText
                                + "."
                                + extra
                                + " Z aktualniho grafu neumim overit, jestli zdroj mezitim publikoval "
                                + "novejsi hodnotu; tady pracuji jen s daty, ktera jsou prave v ChartDataContractu grafu.");
            }
        }

        if (operations.contains("drawdown_series") && primary != null) {
            Map<String, Object> dd = ChartAnalyticsEngine.drawdownSeries(primary);
            Object maxObj = dd.get("max_drawdown");
            calculations.add(Map.of(
                    "type", "drawdown_series",
                    "series_id", primary.get("id"),
                    "rows", dd.get("rows")));
            if (maxObj instanceof Map<?, ?> maxRaw && !((Map<?, ?>) maxRaw).isEmpty()) {
                Map<String, Object> maxDd = (Map<String, Object>) maxRaw;
                Map<String, Object> calc = new LinkedHashMap<>(maxDd);
                calc.put("type", "max_drawdown");
                calc.put("series_id", primary.get("id"));
                calculations.add(calc);
                answerParts.add(
                        "Nejvyšší drawdown řady "
                                + primary.get("label")
                                + " je "
                                + fmt(maxDd.get("drawdown_pct"), 2)
                                + " % mezi "
                                + maxDd.get("peak_period")
                                + " a "
                                + maxDd.get("trough_period")
                                + ".");
                chartActions.add(Map.of(
                        "type", "add_derived_series",
                        "series_id", "drawdown_" + primary.get("id"),
                        "label", "Drawdown - " + primary.get("label") + " (%)",
                        "points", dd.get("rows"),
                        "y_axis", "right"));
                chartActions.add(Map.of(
                        "type", "highlight_period",
                        "from", maxDd.get("peak_period"),
                        "to", maxDd.get("trough_period"),
                        "label", "Největší drawdown"));
            }
        }

        if (operations.contains("sharpe_ratio") && primary != null) {
            double rf = 0.0;
            Object rfObj = payload.get("risk_free_rate_annual");
            if (rfObj != null) {
                try {
                    rf = Double.parseDouble(String.valueOf(rfObj));
                } catch (NumberFormatException ex) {
                    warnings.add("Neplatná bezriziková sazba, používám 0 %.");
                }
            }
            Map<String, Object> sh = ChartAnalyticsEngine.sharpeRatio(primary, rf);
            if (sh != null) {
                Map<String, Object> calc = new LinkedHashMap<>(sh);
                calc.put("type", "sharpe_ratio");
                calc.put("series_id", primary.get("id"));
                calculations.add(calc);
                answerParts.add("Sharpe ratio řady " + primary.get("label") + " vychází " + fmt(sh.get("value"), 3) + ".");
            } else {
                warnings.add("Sharpe ratio nelze spočítat: chybí dostatek výnosů nebo volatilita je nulová.");
            }
        }

        if (operations.contains("calmar_ratio") && primary != null) {
            Map<String, Object> ca = ChartAnalyticsEngine.calmarRatio(primary);
            if (ca != null) {
                Map<String, Object> calc = new LinkedHashMap<>(ca);
                calc.put("type", "calmar_ratio");
                calc.put("series_id", primary.get("id"));
                calculations.add(calc);
                answerParts.add("Calmar ratio řady " + primary.get("label") + " vychází " + fmt(ca.get("value"), 3) + ".");
            } else {
                warnings.add("Calmar ratio nelze spočítat: chybí CAGR nebo drawdown.");
            }
        }

        if (operations.contains("max_series_gap")) {
            if (seriesList.size() < 2) {
                warnings.add("Rozdíl mezi řadami nelze spočítat: graf obsahuje méně než dvě čitelné řady.");
            } else {
                List<Map<String, Object>> pairResults = new ArrayList<>();
                Map<String, Object> bestGap = null;
                Map<String, Object> bestResult = null;
                for (int i = 0; i < seriesList.size(); i++) {
                    for (int j = i + 1; j < seriesList.size(); j++) {
                        Map<String, Object> pairGap = ChartAnalyticsEngine.maxSeriesGap(seriesList.get(i), seriesList.get(j));
                        pairResults.add(pairGap);
                        Object candidateObj = pairGap.get("max_gap");
                        if (candidateObj instanceof Map<?, ?> candidateRaw) {
                            Map<String, Object> candidate = (Map<String, Object>) candidateRaw;
                            if (bestGap == null
                                    || ((Number) candidate.get("abs_difference")).doubleValue()
                                            > ((Number) bestGap.get("abs_difference")).doubleValue()) {
                                bestGap = candidate;
                                bestResult = pairGap;
                            }
                        }
                    }
                }
                Map<String, Object> calc = new LinkedHashMap<>(bestResult != null ? bestResult : Map.of());
                calc.put("type", "max_series_gap");
                calc.put("pairs", pairResults);
                calculations.add(calc);
                if (bestGap != null) {
                    String period = ChartContractParser.str(bestGap.get("period"));
                    answerParts.add(
                            "Největší rozdíl mezi řadami "
                                    + bestGap.get("label_a")
                                    + " a "
                                    + bestGap.get("label_b")
                                    + " byl v "
                                    + periodWord(period)
                                    + " "
                                    + period
                                    + ": "
                                    + bestGap.get("label_a")
                                    + " "
                                    + fmt(bestGap.get("value_a"), 3)
                                    + ", "
                                    + bestGap.get("label_b")
                                    + " "
                                    + fmt(bestGap.get("value_b"), 3)
                                    + ", rozdíl "
                                    + fmt(bestGap.get("difference"), 3)
                                    + " (absolutně "
                                    + fmt(bestGap.get("abs_difference"), 3)
                                    + ").");
                    chartActions.add(Map.of(
                            "type", "highlight_period",
                            "from", period,
                            "to", period,
                            "label", "Největší rozdíl mezi řadami"));
                } else {
                    warnings.add("Rozdíl mezi řadami nelze spočítat: řady nemají společné časové body.");
                }
            }
        }

        if (operations.contains("correlation_matrix")) {
            Map<String, Object> rel = ChartAnalyticsEngine.correlationMatrix(seriesList);
            Map<String, Object> calc = new LinkedHashMap<>(rel);
            calc.put("type", "correlation_matrix");
            calculations.add(calc);
            Object relationshipsObj = rel.get("relationships");
            if (relationshipsObj instanceof List<?> relationships) {
                for (Object item : relationships) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> best = (Map<String, Object>) raw;
                        if (best.get("r") instanceof Number) {
                            answerParts.add(
                                    "Nejsilnější vztah v grafu je "
                                            + best.get("label_a")
                                            + " vs. "
                                            + best.get("label_b")
                                            + " s korelací r="
                                            + fmt(best.get("r"), 3)
                                            + " na "
                                            + best.get("n")
                                            + " společných bodech.");
                            break;
                        }
                    }
                }
            }
        }

        if (operations.contains("trend_line") && primary != null) {
            Map<String, Object> tr = ChartAnalyticsEngine.trendLine(primary);
            if (tr != null) {
                Map<String, Object> calc = new LinkedHashMap<>(tr);
                calc.put("type", "trend_line");
                calc.put("series_id", primary.get("id"));
                calculations.add(calc);
                chartActions.add(Map.of(
                        "type", "draw_trend_line",
                        "series_id", primary.get("id"),
                        "label", "AI trendová linka",
                        "start", tr.get("start"),
                        "end", tr.get("end"),
                        "slope_per_step", tr.get("slope_per_step")));
                answerParts.add("Trendová linka má sklon " + fmt(tr.get("slope_per_step"), 4) + " na jeden krok období.");
            } else {
                warnings.add("Trendovou linku nelze spočítat: graf má příliš málo bodů.");
            }
        }

        if (operations.contains("cube_preview") && !olapCube.isEmpty()) {
            Map<String, Object> counts = olapTableCounts(olapCube);
            calculations.add(Map.of(
                    "type", "cube_preview",
                    "olap_table_counts", counts,
                    "olap_schema", olapCube.get("schema")));
            chartActions.add(Map.of("type", "open_cube_view", "fact_row_count", counts.getOrDefault("fact_values", 0)));
            answerParts.add(
                    "OLAP kostka je uz navazana na aktualni pozorovani grafu: "
                            + "fact_values ma "
                            + counts.getOrDefault("fact_values", 0)
                            + " radku, dim_period "
                            + counts.getOrDefault("dim_period", 0)
                            + ", dim_series "
                            + counts.getOrDefault("dim_series", 0)
                            + ", dim_geo "
                            + counts.getOrDefault("dim_geo", 0)
                            + " a dim_source "
                            + counts.getOrDefault("dim_source", 0)
                            + ".");
        } else if (operations.contains("cube_preview")) {
            Map<String, Object> cube = ChartAnalyticsEngine.buildCubePreview(contract);
            calculations.add(cube);
            Object factRows = cube.get("fact_rows");
            int count = factRows instanceof List<?> list ? list.size() : 0;
            chartActions.add(Map.of("type", "open_cube_view", "fact_row_count", count));
            answerParts.add("Analytická kostka je připravená jako long-form fact table s " + count + " řádky.");
        }

        if (answerParts.isEmpty()) {
            if (operations.contains("summary")) {
                answerParts.add(economistFallbackAnswer(question, seriesList));
            } else {
                String labels = seriesList.stream()
                        .limit(4)
                        .map(s -> ChartContractParser.str(s.get("label")))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                answerParts.add(
                        "Graf obsahuje "
                                + seriesList.size()
                                + " řad: "
                                + labels
                                + ". Můžu spočítat drawdown, Sharpe/Calmar, korelace, trendovou linku nebo připravit kostku.");
            }
        }

        String deterministicAnswer = String.join(" ", answerParts);
        String answerCz = deterministicAnswer;
        boolean answerFromLlm = false;

        Set<String> privateIds = new java.util.LinkedHashSet<>();
        Object privateIdsObj = privacyAudit.get("private_series_ids");
        if (privateIdsObj instanceof List<?> list) {
            for (Object item : list) {
                privateIds.add(String.valueOf(item));
            }
        }
        List<Map<String, Object>> publicSeries = privateIds.isEmpty()
                ? seriesList
                : seriesList.stream()
                        .filter(s -> !privateIds.contains(ChartContractParser.str(s.get("id"))))
                        .toList();

        if (!publicSeries.isEmpty()) {
            // Primární řada může být sama privátní (soukromé řady se do promptu vůbec nedostanou) —
            // pak se přesné období nedohledává vůbec, aby se jeho hodnota neprozradila anonymizovaným
            // kontextem oklikou přes requested_period_lookup.
            boolean primaryIsPrivate =
                    primary != null && privateIds.contains(ChartContractParser.str(primary.get("id")));
            Map<String, Object> periodLookup =
                    primaryIsPrivate ? Map.of() : requestedPeriodLookup(question, primary);
            String llmAnswer;
            if (!privateIds.isEmpty()) {
                llmAnswer = economistService.economistAnswer(
                        question, plannerContract, publicSeries, List.of(), plan, "", conversationHistory,
                        periodLookup);
            } else {
                llmAnswer = economistService.economistAnswer(
                        question, contract, seriesList, calculations, plan, deterministicAnswer, conversationHistory,
                        periodLookup);
            }
            if (llmAnswer != null && !llmAnswer.isBlank()) {
                answerCz = llmAnswer;
                answerFromLlm = true;
                List<String> unverifiedNumbers =
                        ChartAgentNumberFactCheck.unverifiedNumbers(llmAnswer, seriesList, calculations);
                if (!unverifiedNumbers.isEmpty()) {
                    warnings.add(
                            "Odpověď zmiňuje čísla, která se nepodařilo dohledat v datech grafu ani ve "
                                    + "výpočtech: "
                                    + String.join(", ", unverifiedNumbers)
                                    + ". Může jít o legitimní odvozenou hodnotu i o chybu modelu — než číslo "
                                    + "použijete, ověřte ho v grafu nebo tabulce.");
                }
                String periodWarning = ChartAgentNumberFactCheck.misattributedPeriodWarning(llmAnswer, periodLookup);
                if (periodWarning != null) {
                    warnings.add(periodWarning);
                }
            }
        }

        // Cedulka pod odpovědí nesmí slibovat determinismus tam, kde text napsal jazykový model.
        // Deterministicky spočítané zůstávají výpočty (`calculations`), ale samotné znění odpovědi
        // je formulace modelu nad nimi — uživatel to musí vědět, jinak nemá důvod čísla ověřovat.
        String methodology = answerFromLlm
                ? "Výpočty nad daty grafu proběhly deterministicky na serveru; znění odpovědi nad "
                        + "nimi formuloval jazykový model, proto si konkrétní čísla ověřte v grafu "
                        + "nebo v tabulce."
                : "Dotaz je převeden na výpočetní plán a všechny hodnoty jsou spočítané deterministicky "
                        + "z aktuálního ChartDataContractu grafu.";
        if (Boolean.TRUE.equals(privacyAudit.get("contains_private_series"))) {
            // Dřív odpověď o soukromých řadách jen mlčky zmizela: deterministický návrh je mohl
            // pokrývat, ale LLM odpověď (postavená jen na veřejných řadách) ho bez varování
            // přepsala. Věta teď říká přímo, že text výše je z hlediska soukromých řad neúplný -
            // ne jen že se s daty "zacházelo opatrně".
            int privateCount = privateIds.size();
            methodology += privateCount == 1
                    ? " Režim Strict private: 1 privátní/nahraná řada v grafu se do znění odpovědi "
                            + "výše vůbec nedostala (AI dostala jen anonymizovaná metadata bez hodnot) — "
                            + "její čísla najdete ve výpočtech nebo v tabulce grafu."
                    : " Režim Strict private: " + privateCount + " privátních/nahraných řad v grafu se do "
                            + "znění odpovědi výše vůbec nedostalo (AI dostala jen anonymizovaná metadata "
                            + "bez hodnot) — jejich čísla najdete ve výpočtech nebo v tabulce grafu.";
        }
        if (!olapCube.isEmpty()) {
            Map<String, Object> counts = olapTableCounts(olapCube);
            methodology += " AI chat ma prilozeny OLAP kontext aktualniho grafu "
                    + "(fact_values="
                    + counts.getOrDefault("fact_values", 0)
                    + ", dim_series="
                    + counts.getOrDefault("dim_series", 0)
                    + ", dim_period="
                    + counts.getOrDefault("dim_period", 0)
                    + ").";
            if ("olap_cube".equals(analysisContext)) {
                methodology += " Aktivni kontext dotazu je OLAP/star-schema nahled.";
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("answer_cz", answerCz);
        out.put("methodology_cz", methodology);
        out.put("plan", plan);
        out.put("calculations", calculations);
        out.put("chart_actions", chartActions);
        out.put("privacy_audit", privacyAudit);
        out.put("warnings", warnings);
        return out;
    }

    private static Map<String, Object> chartResearchContext(Map<String, Object> contract) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (contract == null || contract.isEmpty()) {
            return out;
        }
        out.put("title", ChartContractParser.str(contract.get("title")));
        List<Map<String, Object>> series = ChartContractParser.seriesFromContract(contract);
        out.put("series_labels", series.stream()
                .map(row -> ChartContractParser.str(row.get("label")))
                .filter(label -> !label.isBlank())
                .limit(12)
                .toList());
        out.put("sources", series.stream()
                .map(row -> ChartContractParser.str(row.get("source")))
                .filter(source -> !source.isBlank())
                .distinct()
                .limit(12)
                .toList());
        List<String> periods = series.stream()
                .flatMap(row -> row.get("points") instanceof List<?> points
                        ? points.stream()
                        : java.util.stream.Stream.empty())
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(row -> ChartContractParser.str(row.get("period")))
                .filter(period -> !period.isBlank())
                .sorted()
                .toList();
        if (!periods.isEmpty()) {
            out.put("start_period", periods.getFirst());
            out.put("end_period", periods.getLast());
        }
        out.put("active_annotations", ChartConversationContext.activeAnnotations(contract));
        return out;
    }

    private static final Pattern LABEL_TOKEN_SPLIT = Pattern.compile("[^a-z0-9áčďéěíňóřšťúůýž]+");

    /**
     * Katalogove nazvy rad mivaji tvar "Zeme · Ukazatel (jednotka)" (napr. "France · HDP
     * nominalni (USD)") - vyzadovat shodu VSECH slov z celeho nazvu by selhalo na otazku, ktera
     * zmini jen zemi ("co rika rada France?"). Rozdelime proto nazev i na jednotlive segmenty a
     * zkusime shodu na kazdem zvlast - cely nazev i kazdy segment jsou samostatni kandidati.
     */
    private static List<String> labelMatchCandidates(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(trimmed);
        for (String part : LABEL_SEGMENT_SPLIT.split(trimmed)) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static final Pattern LABEL_SEGMENT_SPLIT = Pattern.compile("[·:—–]");

    /**
     * Vypocty jako drawdown/Sharpe/Calmar/trendova linka byly natvrdo navazane na PRVNI radu
     * z kontraktu, bez ohledu na to, o ktere rade se uzivatel zeptal (napr. multi-series
     * srovnani "Srovnat s..."). Pokud text otazky obsahuje vsechna vyznamova slova z nazvu (nebo
     * jen jednoho segmentu nazvu) jine rady v grafu, pocitame z ni - jinak (zadna shoda, jedina
     * rada) zustava prvni rada jako drive.
     */
    private static Map<String, Object> resolveTargetSeries(String question, List<Map<String, Object>> seriesList) {
        if (seriesList.size() < 2) {
            return seriesList.isEmpty() ? null : seriesList.getFirst();
        }
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return seriesList.getFirst();
        }
        Map<String, Object> best = null;
        int bestScore = 0;
        for (Map<String, Object> series : seriesList) {
            String label = ChartContractParser.str(series.get("label"));
            if (label.isBlank()) {
                continue;
            }
            for (String part : labelMatchCandidates(label)) {
                List<String> tokens = significantLabelTokens(part);
                int score;
                if (!tokens.isEmpty()) {
                    if (!tokens.stream().allMatch(q::contains)) {
                        continue;
                    }
                    score = tokens.size();
                } else if (q.contains(part.toLowerCase(Locale.ROOT))) {
                    score = 1;
                } else {
                    continue;
                }
                if (score > bestScore) {
                    best = series;
                    bestScore = score;
                }
            }
        }
        return best != null ? best : seriesList.getFirst();
    }

    private static List<String> significantLabelTokens(String label) {
        return LABEL_TOKEN_SPLIT.splitAsStream(label.toLowerCase(Locale.ROOT))
                .filter(t -> t.length() >= 3 && !t.chars().allMatch(Character::isDigit))
                .toList();
    }

    private static final Pattern YEAR_IN_QUESTION = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    /**
     * Když se dotaz ptá na konkrétní rok, model dřív dostal jen vzorkovaný `series_points` a
     * pokyn "najdi nejbližší bod" — v praxi si tak nejbližší dostupnou hodnotu tiše přivlastnil
     * pro rok, na který se ptal. Tahle metoda rok v otázce deterministicky dohledá v PLNÉ (ne
     * vzorkované) řadě a výsledek ({@code exact_matches}, nebo když rok v datech chybí,
     * {@code nearest_available} s VLASTNÍM obdobím) jde do promptu jako hotové zadání — model tak
     * nemá prostor si "nejbližší bod" domyslet sám a vydat ho za jiné období.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> requestedPeriodLookup(String question, Map<String, Object> primary) {
        if (primary == null || question == null) {
            return Map.of();
        }
        Matcher m = YEAR_IN_QUESTION.matcher(question);
        if (!m.find()) {
            return Map.of();
        }
        String year = m.group(1);
        Object pointsObj = primary.get("points");
        if (!(pointsObj instanceof List<?> points)) {
            return Map.of();
        }
        List<Map<String, Object>> exact = new ArrayList<>();
        Map<String, Object> nearestBefore = null;
        Map<String, Object> nearestAfter = null;
        for (Object ptObj : points) {
            if (!(ptObj instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> pt = (Map<String, Object>) raw;
            if (ChartContractParser.num(pt.get("value")) == null) {
                continue;
            }
            String period = ChartContractParser.str(pt.get("period"));
            if (period.isBlank()) {
                continue;
            }
            if (period.startsWith(year)) {
                exact.add(Map.of("period", period, "value", pt.get("value")));
            } else if (period.compareTo(year) < 0) {
                nearestBefore = pt;
            } else if (nearestAfter == null) {
                nearestAfter = pt;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested_year", year);
        out.put("exact_matches", exact);
        if (exact.isEmpty()) {
            Map<String, Object> nearest = closerYear(year, nearestBefore, nearestAfter);
            if (nearest != null) {
                out.put(
                        "nearest_available",
                        Map.of("period", nearest.get("period"), "value", nearest.get("value")));
            }
        }
        return out;
    }

    private static Map<String, Object> closerYear(String year, Map<String, Object> before, Map<String, Object> after) {
        if (before == null) {
            return after;
        }
        if (after == null) {
            return before;
        }
        int target = Integer.parseInt(year);
        int distBefore = target - yearOf(before);
        int distAfter = yearOf(after) - target;
        return distBefore <= distAfter ? before : after;
    }

    private static int yearOf(Map<String, Object> point) {
        String period = ChartContractParser.str(point.get("period"));
        try {
            return Integer.parseInt(period.substring(0, 4));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String resolvedQuestion(Map<String, Object> plan, String originalQuestion) {
        String resolved = ChartContractParser.str(plan == null ? null : plan.get("resolved_question"));
        return resolved.isBlank() ? originalQuestion : resolved;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> latestAvailableRows(List<Map<String, Object>> seriesList) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> series : seriesList) {
            Object pointsObj = series.get("points");
            if (!(pointsObj instanceof List<?> points)) {
                continue;
            }
            List<Map<String, Object>> usable = new ArrayList<>();
            for (Object ptObj : points) {
                if (ptObj instanceof Map<?, ?> rawPt) {
                    Map<String, Object> pt = (Map<String, Object>) rawPt;
                    if (ChartContractParser.num(pt.get("value")) != null) {
                        usable.add(pt);
                    }
                }
            }
            if (usable.isEmpty()) {
                continue;
            }
            Map<String, Object> latest = usable.getLast();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("series_id", series.get("id"));
            row.put("series_label", series.get("label"));
            row.put("period", latest.get("period"));
            row.put("value", latest.get("value"));
            String unit = ChartContractParser.str(series.get("unit"));
            if (unit.isBlank()) {
                unit = ChartContractParser.str(latest.get("unit"));
            }
            row.put("unit", unit);
            rows.add(row);
        }
        return rows;
    }

    private static String economistFallbackAnswer(String question, List<Map<String, Object>> seriesList) {
        String q = question == null ? "" : question.strip().toLowerCase();
        if (seriesList.isEmpty()) {
            return "Nemám k dispozici čitelné časové řady, takže nemohu dát ekonomickou interpretaci.";
        }
        List<String> stories = new ArrayList<>();
        for (Map<String, Object> series : seriesList.stream().limit(3).toList()) {
            stories.add(seriesStory(series));
        }
        String intro = "Tahle data jsou sada časových řad v aktuálním grafu.";
        if (q.contains("dashboard")) {
            intro = "Dashboard obsahuje "
                    + seriesList.size()
                    + " čitelných časových řad; níže je rychlé ekonomické čtení hlavních z nich.";
        } else if (q.contains("řad") || q.contains("rad")) {
            intro = "V grafu je " + seriesList.size() + " časových řad. Ekonomicky je čtu takto:";
        }
        return intro + " " + String.join(" ", stories);
    }

    @SuppressWarnings("unchecked")
    private static String seriesStory(Map<String, Object> series) {
        Object pointsObj = series.get("points");
        if (!(pointsObj instanceof List<?> points)) {
            return "Řada " + series.get("label") + " má příliš málo bodů pro trendové shrnutí.";
        }
        List<Map<String, Object>> usable = new ArrayList<>();
        for (Object ptObj : points) {
            if (ptObj instanceof Map<?, ?> rawPt) {
                Map<String, Object> pt = (Map<String, Object>) rawPt;
                if (ChartContractParser.num(pt.get("value")) != null) {
                    usable.add(pt);
                }
            }
        }
        if (usable.size() < 2) {
            return "Řada " + series.get("label") + " má příliš málo bodů pro trendové shrnutí.";
        }
        Map<String, Object> first = usable.getFirst();
        Map<String, Object> last = usable.getLast();
        double delta = ChartContractParser.num(last.get("value")) - ChartContractParser.num(first.get("value"));
        Double rel = ChartContractParser.num(first.get("value")) != 0.0
                ? (delta / ChartContractParser.num(first.get("value"))) * 100.0
                : null;
        String direction = delta > 0 ? "roste" : delta < 0 ? "klesá" : "je zhruba beze změny";
        String relPart = rel != null ? ", relativně " + fmt(rel, 2) + " %" : "";
        return "Řada "
                + series.get("label")
                + " v zobrazeném období "
                + direction
                + ": první hodnota "
                + first.get("period")
                + " je "
                + fmt(first.get("value"), 3)
                + ", poslední hodnota "
                + last.get("period")
                + " je "
                + fmt(last.get("value"), 3)
                + "; změna je "
                + fmt(delta, 3)
                + relPart
                + ".";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> olapTableCounts(Map<String, Object> olapCube) {
        Map<String, Object> tables = olapCube.get("tables") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> explicit =
                olapCube.get("table_counts") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        List<String> keys = List.of("fact_values", "dim_period", "dim_series", "dim_geo", "dim_source");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            if (explicit.get(key) instanceof Number n) {
                out.put(key, n.intValue());
            } else {
                Object table = tables.get(key);
                out.put(key, table instanceof List<?> list ? list.size() : 0);
            }
        }
        Object latest = olapCube.get("latest_fact_rows");
        if (latest instanceof List<?> list) {
            out.put("latest_fact_rows", list.size());
        }
        return out;
    }

    private static String periodWord(String period) {
        return period.length() == 4 && period.chars().allMatch(Character::isDigit) ? "roce" : "období";
    }

    private static String fmt(Object value, int digits) {
        if (value == null) {
            return "n/a";
        }
        try {
            return String.format(java.util.Locale.US, "%." + digits + "f", Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return "n/a";
        }
    }
}
