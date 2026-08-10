package cz.bankintel.service.chartagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChartAgentPlanner {

    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
            "drawdown_series",
            "max_drawdown",
            "sharpe_ratio",
            "calmar_ratio",
            "summary",
            "max_series_gap",
            "correlation_matrix",
            "trend_line",
            "cube_preview",
            "latest_available",
            "clear_period_annotations",
            "web_event_annotations",
            "discover_external_data");

    private static final Pattern DRAWDOWN = Pattern.compile(
            "drawdown|propad|nejv[ěe]t[šs][íi].*(ztr[aá]t|pokles|propad)|nejvetsi.*(ztrat|pokles|propad)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARPE = Pattern.compile("\\bsharpe\\b|sharpeho", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALMAR = Pattern.compile("\\bcalmar\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_A = Pattern.compile(
            "(o\\s+cem|cemu|co\\s+jsou|co\\s+je).*(graf|dashboard|rad|rady|ukazatel)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_B = Pattern.compile(
            "co\\s+.*([řr]ad|rad|ukazatel|graf).*(rik[aá]|[řr][ií]k[aá]|rika|ukazuje|znamena|znamen[aá])|"
                    + "vysv[eě]tl|vysvetl|popi[sš].*(graf|[řr]ad|rad|ukazatel)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX_GAP = Pattern.compile(
            "(nejv[ěe]t[šs][íi]|nejvetsi|maxim[aá]ln[íi]|maximalni|max).*(rozd[ií]l|rozdil|difference|gap)|"
                    + "(rozd[ií]l|rozdil|difference|gap).*(mezi|řad|rad|series)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRELATION = Pattern.compile("korel|vztah|souvis|relationship|correlation", Pattern.CASE_INSENSITIVE);
    private static final Pattern TREND = Pattern.compile("trend|trendov|linku|č[aá]ru|caru|regres", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUBE = Pattern.compile("olap|kostk|cube|pivot|kontingen", Pattern.CASE_INSENSITIVE);
    private static final Pattern LATEST = Pattern.compile("novejs|nejnovejs|aktualn|posledn|latest|current|fresh|cerstv", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLEAR_PERIOD_ANNOTATIONS = Pattern.compile(
            "(odstran|oddelej|smaz|vymaz|skryj|zrus|zrusit|remove|delete|clear|hide).*"
                    + "(anotac|vyznac|zvyrazn|udalost|obdobi|interval|vrstv|popisk|period|annotation|event|vlad|government|kabinet)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB_EVENTS = Pattern.compile(
            "(internet|web|dohled|vyhled).*(kriz|udalost|reces|pandem|valk|sok)|"
                    + "(vyznac|oznac|anot).*(kriz|udalost|reces|pandem|valk|sok)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_DATA = Pattern.compile(
            "(extern|internet|web).*(data|dataset|rada|serie)|"
                    + "(stahni|dohled|najdi).*(oficialn|extern).*(data|dataset)",
            Pattern.CASE_INSENSITIVE);

    private final OpenAiJsonSupport openAiJsonSupport;
    private final ObjectMapper objectMapper;

    @Value("${OPENAI_API_KEY:}")
    private String openAiApiKey;

    @SuppressWarnings("unchecked")
    public Map<String, Object> planChartQuestion(String question, Map<String, Object> contract) {
        return planChartQuestion(question, contract, List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> planChartQuestion(
            String question,
            Map<String, Object> contract,
            List<Map<String, String>> conversationHistory,
            List<Map<String, Object>> conversationState) {
        if (hasOpenAiKey()) {
            try {
                Map<String, Object> llmPlan = planWithLlm(
                        question, contract, conversationHistory, conversationState);
                if (llmPlan != null) {
                    return llmPlan;
                }
            } catch (Exception ignored) {
                // regex fallback below
            }
        }
        return planWithRegex(question, contract);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planWithLlm(
            String question,
            Map<String, Object> contract,
            List<Map<String, String>> conversationHistory,
            List<Map<String, Object>> conversationState) throws Exception {
        List<String> labels = new ArrayList<>();
        int seriesCount = 0;
        if (contract != null && contract.get("series") instanceof List<?> series) {
            seriesCount = series.size();
            for (Object item : series.stream().limit(12).toList()) {
                if (item instanceof Map<?, ?> raw) {
                    String label = ChartContractParser.str(((Map<String, Object>) raw).get("label"));
                    labels.add(label.length() > 160 ? label.substring(0, 160) : label);
                }
            }
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("question", question == null ? "" : question.strip());
        context.put("series_count", seriesCount);
        context.put("series_labels", labels);
        context.put("conversation_history", conversationHistory == null ? List.of() : conversationHistory);
        context.put("conversation_state", conversationState == null ? List.of() : conversationState);
        context.put("active_annotations", ChartConversationContext.activeAnnotations(contract));

        Map<String, Object> result = openAiJsonSupport.chatJsonObject(
                """
                Jsi plánovač analýzy nad ekonomickým grafem/dashboardem. \
                Rozumíš češtině, překlepům a volným dotazům. \
                Vrať jen JSON s poli: intent, operations, chart_action_intents, resolved_question, needs_confirmation, followup_question_cz, warnings. \
                operations je seznam z: drawdown_series, max_drawdown, sharpe_ratio, calmar_ratio, summary, max_series_gap, \
                correlation_matrix, trend_line, cube_preview, latest_available, clear_period_annotations, web_event_annotations, discover_external_data. \
                clear_period_annotations použij, když uživatel chce z aktuálního grafu odstranit, skrýt nebo zrušit dříve vyznačené anotace, období, události nebo vrstvy. \
                Takový požadavek nikdy není hledání nové datové řady. \
                web_event_annotations použij, když uživatel chce dohledat a přímo v grafu vyznačit externí kontext: jednotlivé historické události \
                i souvislé režimy, funkční období, vlády, legislativní etapy, krize nebo jiné časové intervaly. \
                Takový požadavek zůstává web_event_annotations i při slovesech "přidej", "zobraz" nebo "zakresli"; nejde o datovou řadu. \
                discover_external_data použij jen pro výslovné hledání chybějícího oficiálního datasetu mimo interní katalog. \
                Běžné výpočty nad řadami nikdy neposílej na web. \
                Krátký navazující dotaz vylož podle conversation_history, conversation_state a active_annotations. \
                Zájmena a elipsy jako 'to', 'stejně', 'dál' nebo 'pokračuj' musí zachovat téma a typ předchozí akce. \
                resolved_question vždy přepiš na samostatně srozumitelný požadavek včetně zděděného tématu a požadovaného období. \
                Úpravy grafu a webové anotace jsou vratné: pokud uživatel žádá jejich provedení a existuje bezpečná, zdrojovatelná interpretace, \
                nastav needs_confirmation=false a naplánuj akci. Nevyžaduj potvrzení jen kvůli tomu, že existuje více obvyklých klasifikací; \
                zvol nejlépe doloženou interpretaci a případný předpoklad stručně popiš ve výsledku. \
                needs_confirmation=true použij pouze tehdy, když chybí referent nebo období natolik, že nelze vytvořit žádnou validní akci. \
                warnings nesmějí opakovat obecné pochybnosti ani brzdit validní vratnou akci. \
                intent = první operation. chart_action_intents může obsahovat add_derived_series, highlight_period, annotate_period, clear_period_annotations, draw_trend_line, open_cube_view.""",
                objectMapper.writeValueAsString(context),
                OpenAiModelTask.PLANNER);

        List<String> operations = normalizeOperations(result.get("operations"));
        if (operations.isEmpty()) {
            return null;
        }
        List<String> chartActions = stringList(result.get("chart_action_intents"));
        List<String> warnings = stringList(result.get("warnings"));
        appendSeriesWarnings(operations, seriesCount, warnings);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", operations.getFirst());
        out.put("question_original", question == null ? "" : question.strip());
        String resolvedQuestion = ChartContractParser.str(result.get("resolved_question"));
        out.put("resolved_question", resolvedQuestion.isBlank() ? question == null ? "" : question.strip() : resolvedQuestion);
        out.put("operations", operations);
        out.put("chart_action_intents", chartActions);
        out.put("needs_confirmation", Boolean.TRUE.equals(result.get("needs_confirmation")));
        out.put("followup_question_cz", ChartContractParser.str(result.get("followup_question_cz")));
        out.put("warnings", warnings);
        out.put("source", "llm");
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> planWithRegex(String question, Map<String, Object> contract) {
        String q = question == null ? "" : question.strip();
        String ql = q.toLowerCase();
        String qs = ql + " " + fold(ql);
        List<String> operations = new ArrayList<>();
        List<String> chartActions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (DRAWDOWN.matcher(qs).find()) {
            operations.add("drawdown_series");
            operations.add("max_drawdown");
            chartActions.add("add_derived_series");
            chartActions.add("highlight_period");
        }
        if (SHARPE.matcher(qs).find()) {
            operations.add("sharpe_ratio");
            warnings.add("Sharpe ratio v MVP používá bezrizikovou sazbu 0 %, pokud ji uživatel nepředá jako parametr.");
        }
        if (CALMAR.matcher(qs).find()) {
            operations.add("calmar_ratio");
        }
        if (SUMMARY_A.matcher(qs).find() || SUMMARY_B.matcher(qs).find()) {
            operations.add("summary");
        }
        if (MAX_GAP.matcher(qs).find()) {
            operations.add("max_series_gap");
            chartActions.add("highlight_period");
        }
        if (CORRELATION.matcher(qs).find()) {
            operations.add("correlation_matrix");
        }
        if (TREND.matcher(qs).find()) {
            operations.add("trend_line");
            chartActions.add("draw_trend_line");
        }
        if (CUBE.matcher(qs).find()) {
            operations.add("cube_preview");
            chartActions.add("open_cube_view");
        }
        if (LATEST.matcher(qs).find()) {
            operations.add("latest_available");
        }
        if (CLEAR_PERIOD_ANNOTATIONS.matcher(qs).find()) {
            operations.add("clear_period_annotations");
            chartActions.add("clear_period_annotations");
        }
        if (WEB_EVENTS.matcher(qs).find()) {
            operations.add("web_event_annotations");
            chartActions.add("annotate_period");
        }
        if (EXTERNAL_DATA.matcher(qs).find()) {
            operations.add("discover_external_data");
        }

        String followup = "";
        if (operations.isEmpty()) {
            operations.add("summary");
            followup =
                    "Můžu navázat výpočtem drawdownu, korelací, rozdílů mezi řadami, trendu nebo návrhem souvisejících řad.";
        }

        int seriesCount = 0;
        if (contract != null && contract.get("series") instanceof List<?> series) {
            seriesCount = series.size();
        }
        appendSeriesWarnings(operations, seriesCount, warnings);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", operations.getFirst());
        out.put("question_original", q);
        out.put("operations", operations);
        out.put("chart_action_intents", chartActions);
        out.put("needs_confirmation", false);
        out.put("followup_question_cz", followup);
        out.put("warnings", warnings);
        out.put("source", "regex");
        return out;
    }

    private static void appendSeriesWarnings(List<String> operations, int seriesCount, List<String> warnings) {
        if (operations.contains("correlation_matrix") && seriesCount < 2) {
            warnings.add("Korelace potřebuje alespoň dvě řady v grafu.");
        }
        if (operations.contains("max_series_gap") && seriesCount < 2) {
            warnings.add("Rozdíl mezi řadami potřebuje alespoň dvě řady v grafu.");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> normalizeOperations(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String op = String.valueOf(item).trim().toLowerCase();
            if (ALLOWED_OPERATIONS.contains(op) && seen.add(op)) {
                out.add(op);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    private boolean hasOpenAiKey() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
