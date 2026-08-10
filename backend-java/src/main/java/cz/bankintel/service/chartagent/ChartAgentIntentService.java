package cz.bankintel.service.chartagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChartAgentIntentService {

    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "add_series",
            "explain_current_series",
            "find_related_series",
            "analyze_chart",
            "compute_or_transform",
            "research_web",
            "discover_external_data");

    private final OpenAiJsonSupport openAiJsonSupport;
    private final ObjectMapper objectMapper;

    @Value("${OPENAI_API_KEY:}")
    private String openAiApiKey;

    @SuppressWarnings("unchecked")
    public Map<String, Object> interpretIntent(Map<String, Object> payload) {
        String question = ChartContractParser.str(payload.get("question"));
        Map<String, Object> contract = payload.get("chart_contract") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<Map<String, Object>> seriesList = ChartContractParser.seriesFromContract(contract);
        Map<String, Object> privacy = ChartPrivacySupport.chartPrivacyAudit(contract);
        Map<String, Object> metadata = contract.get("metadata") instanceof Map<?, ?> meta
                ? (Map<String, Object>) meta
                : Map.of();

        List<String> labels = new ArrayList<>();
        List<String> oldEntityTerms = new ArrayList<>();
        for (Map<String, Object> series : seriesList.stream().limit(12).toList()) {
            String label = ChartContractParser.str(series.get("label"));
            String trimmedLabel = label.length() > 160 ? label.substring(0, 160) : label;
            labels.add(trimmedLabel);
            if (!trimmedLabel.isBlank()) {
                oldEntityTerms.add(trimmedLabel);
            }
            // Ticker/id (např. "aapl") se do catalog_query často propíše samostatně, i když se v
            // labelu ("Apple Inc.") vůbec neobjeví - bez něj by oprava níže "AAPL ..." dotazy minula.
            String id = ChartContractParser.str(series.get("id"));
            if (!id.isBlank() && !sameNormalizedText(id, trimmedLabel)) {
                oldEntityTerms.add(id);
            }
        }
        // Bez tohohle LLM vidí jen holé názvy ("Apple Inc.") a u strohého "přidej google" nemá signál,
        // že jde o CENU akcie - umí si tak vymyslet jinou metriku (tržby, zisk) pro stejnou firmu.
        // Metrika prvni řady je proxy pro celý graf (add_series má cílit na stejný typ dat).
        String metricHint = seriesList.isEmpty()
                ? ""
                : firstNonBlank(
                        ChartContractParser.str(seriesList.getFirst().get("unit")),
                        ChartContractParser.str(seriesList.getFirst().get("source")));

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("question", question);
        context.put("history", ChartConversationContext.historyBrief(payload.get("conversation_history")));
        context.put("conversation_state", ChartConversationContext.stateBrief(payload.get("conversation_state")));
        context.put("active_annotations", ChartConversationContext.activeAnnotations(contract));
        context.put(
                "chart_title",
                firstNonBlank(
                        ChartContractParser.str(contract.get("title")),
                        ChartContractParser.str(metadata.get("title")),
                        ChartContractParser.str(metadata.get("page_title"))));
        context.put("series_count", seriesList.size());
        context.put("series_labels", labels);
        context.put("series_metric_hint", metricHint);
        context.put("contains_private_series", Boolean.TRUE.equals(privacy.get("contains_private_series")));

        if (hasOpenAiKey()) {
            try {
                Map<String, Object> result = openAiJsonSupport.chatJsonObject(
                        """
                        Jsi intent router pro konverzační AI nad ekonomickým grafem/dashboardem. \
                        Rozumíš češtině, překlepům a navazujícím krátkým odpovědím. \
                        Vrať jen JSON s poli: intent, catalog_query, catalog_queries, rewritten_question, context_mode, context_terms, confidence, reason_cz. \
                        Povolené intent hodnoty: add_series, explain_current_series, find_related_series, analyze_chart, compute_or_transform, research_web, discover_external_data. \
                        research_web použij pro dohledání externího kontextu a jeho vyznačení v grafu: jednotlivých událostí i souvislých režimů, \
                        funkčních období, vlád, legislativních etap, krizí nebo jiných časových intervalů. \
                        Rozlišuj objekt akce, ne sloveso uživatele: "přidej/vyznač do grafu období nebo události" je research_web, \
                        zatímco add_series je pouze nová kvantitativní datová řada s pozorováními, která se má vykreslit jako čára, sloupce nebo body. \
                        Textový webový kontext nikdy nepřeváděj na katalogový dotaz jen proto, že uživatel použil slovo "přidej". \
                        discover_external_data použij jen při výslovné žádosti o chybějící data mimo interní katalog. \
                        Navazující krátký dotaz vylož podle history, conversation_state a active_annotations. \
                        rewritten_question musí být samostatně srozumitelný a zachovat téma i typ předchozí akce. \
                        Pokud výrazy jako 'to', 'stejně', 'dál' nebo 'pokračuj' navazují na anotace, zachovej jejich téma; nevymýšlej jiné. \
                        add_series použij pro přidání jiné kvantitativní řady do grafu i když je dotaz formulovaný nepřímo \
                        ('dokážeš přidat inflaci', 'a ještě inflaci', 'řada je inflace'). \
                        find_related_series použij jen když uživatel chce najít další externí/příbuzné ukazatele v katalogu. \
                        Dotaz na vztah, korelaci, rozdíl nebo porovnání mezi řadami, které už jsou v grafu, je analyze_chart. \
                        catalog_query je čistý profesionální anglický hledaný výraz pro katalog, bez slov jako dokážeš, přidej, graf. \
                        catalog_queries obsahuje nejvýše čtyři významově shodné hledací varianty; první je anglická, další mohou být české. \
                        Pro add_series urči context_mode: explicit, když uživatel jmenuje zemi, entitu nebo skupinu; \
                        inherit_chart, když nový ukazatel má navazovat na entity aktivních řad grafu; jinak global. \
                        context_terms obsahuje názvy a běžné katalogové aliasy cílových entit. Při inherit_chart vybírej pouze \
                        ze series_labels a nic nevymýšlej. Při explicit použij entity výslovně uvedené uživatelem. \
                        Příklad: graf obsahuje Czechia, Austria a Bulgaria a uživatel řekne 'přidej úrokové míry': \
                        context_mode=inherit_chart a context_terms obsahuje Czechia, Austria, Bulgaria, nikoli náhodnou jinou zemi. \
                        inherit_chart je JEN pro sdílenou dimenzi (typicky zemi), na kterou má navázat NOVÁ METRIKA - \
                        NIKDY ho nepoužij, když aktivní řady grafu jsou samy entity STEJNÉHO druhu jako to, co se přidává \
                        (např. graf akcií Apple/Netflix a uživatel řekne 'přidej google' - google je NOVÁ SAMOSTATNÁ \
                        firma/entita, ne metrika navazující na Apple/Netflix). V takovém případě je context_mode vždy global. \
                        series_metric_hint popisuje typ dat aktuálního grafu (např. cena akcie, index, úroková sazba). \
                        Když add_series dotaz jen jmenuje jinou firmu/entitu bez výslovného upřesnění jiné metriky \
                        ('přidej google', 'a ještě amazon'), catalog_query MUSÍ cílit na stejný typ metriky jako \
                        series_metric_hint (např. u akciového grafu vždy cena akcie té firmy, ne tržby, zisk ani jiný \
                        finanční ukazatel) - nevymýšlej jinou metriku, i kdyby to bylo v history zmíněné dřív. \
                        Příklad chyby, které se vyvaruj: graf obsahuje jen Apple Inc. a uživatel napíše \
                        'ridej google' (překlep 'přidej'). context_terms správně obsahuje Alphabet Inc./Google, \
                        ale catalog_query NESMÍ zůstat o Apple Inc. jen proto, že se stejná fráze použila \
                        v předchozím kole - catalog_query i všechny catalog_queries musí být o NOVĚ přidávané \
                        firmě (Alphabet Inc./Google), nikdy o firmě, která už v grafu je. \
                        Když uživatel jen opravuje předchozí přidání řady, použij add_series a catalog_query z opravy.""",
                        objectMapper.writeValueAsString(context),
                        OpenAiModelTask.PLANNER);
                String intent = ChartContractParser.str(result.get("intent")).toLowerCase();
                if (ALLOWED_INTENTS.contains(intent)) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("intent", intent);

                    String contextMode = normalizedContextMode(result.get("context_mode"));
                    List<String> requestedContextTerms = stringList(result.get("context_terms"));
                    List<String> contextTerms = requestedContextTerms;
                    if ("inherit_chart".equals(contextMode)) {
                        boolean referencesActiveSeries = requestedContextTerms.stream()
                                .anyMatch(term -> labels.stream().anyMatch(label -> sameNormalizedText(term, label)));
                        if (!referencesActiveSeries) {
                            contextTerms = labels;
                        }
                    }

                    String catalogQuery = ChartContractParser.str(result.get("catalog_query"));
                    List<String> catalogQueries = stringList(result.get("catalog_queries"));
                    if (catalogQueries.isEmpty() && !catalogQuery.isBlank()) {
                        catalogQueries = List.of(catalogQuery);
                    }
                    // Pozorovaný LLM bug (živě reprodukováno na "ridej google" - překlep "přidej"):
                    // context_terms i rewritten_question správně pojmenují NOVOU firmu (Alphabet Inc.),
                    // ale catalog_query/catalog_queries občas zůstanou textově u firmy, která už v grafu
                    // je (Apple Inc.) - LLM "zkopíruje" předchozí metrikovou frázi místo aby dosadil novou
                    // entitu. Když context_mode není inherit_chart a context_terms míří na jinou entitu
                    // než aktivní řady, oprav dotazy, které vůbec nezmiňují žádný z context_terms a textově
                    // cílí na starou entitu.
                    if (!"inherit_chart".equals(contextMode) && !contextTerms.isEmpty()) {
                        catalogQueries = repairQueriesTargetingWrongEntity(catalogQueries, oldEntityTerms, contextTerms)
                                .stream()
                                .distinct()
                                .toList();
                    }
                    if (!catalogQueries.isEmpty()) {
                        catalogQuery = catalogQueries.get(0);
                    }
                    out.put("catalog_query", catalogQuery);
                    out.put("catalog_queries", catalogQueries.stream().limit(4).toList());
                    out.put("context_mode", contextMode);
                    out.put("context_terms", contextTerms);
                    out.put(
                            "rewritten_question",
                            firstNonBlank(ChartContractParser.str(result.get("rewritten_question")), question));
                    out.put("confidence", toDouble(result.get("confidence")));
                    out.put("reason_cz", ChartContractParser.str(result.get("reason_cz")));
                    out.put("source", "llm");
                    return out;
                }
            } catch (Exception ignored) {
                // fallback below
            }
        }
        return fallbackIntent(question);
    }

    private Map<String, Object> fallbackIntent(String question) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("intent", "unknown");
        out.put("catalog_query", "");
        out.put("catalog_queries", List.of());
        out.put("context_mode", "global");
        out.put("context_terms", List.of());
        out.put("rewritten_question", question);
        out.put("confidence", 0.0);
        out.put("reason_cz", "LLM intent router nebyl dostupný, používám lokální fallback.");
        out.put("source", "fallback");
        return out;
    }

    private boolean hasOpenAiKey() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private static String normalizedContextMode(Object value) {
        String mode = ChartContractParser.str(value).toLowerCase();
        return Set.of("inherit_chart", "explicit", "global").contains(mode) ? mode : "global";
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(ChartContractParser::str)
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(12)
                .toList();
    }

    private static boolean sameNormalizedText(String left, String right) {
        return normalizedText(left).equals(normalizedText(right));
    }

    private static List<String> repairQueriesTargetingWrongEntity(
            List<String> queries, List<String> oldEntityTerms, List<String> newTerms) {
        if (queries.isEmpty() || newTerms.isEmpty()) {
            return queries;
        }
        boolean targetsSameEntity = newTerms.stream()
                .anyMatch(term -> oldEntityTerms.stream().anyMatch(old -> sameNormalizedText(term, old)));
        if (targetsSameEntity) {
            return queries;
        }
        String replacement = pickReplacementTerm(newTerms);
        List<String> repaired = new ArrayList<>();
        for (String query : queries) {
            if (containsAnyTerm(query, newTerms)) {
                repaired.add(query);
                continue;
            }
            String matchedTerm = longestMatchingTerm(query, oldEntityTerms);
            if (matchedTerm == null) {
                repaired.add(query);
                continue;
            }
            repaired.add(replaceCaseInsensitive(query, matchedTerm, replacement));
        }
        return repaired;
    }

    private static boolean containsAnyTerm(String haystack, List<String> terms) {
        String normalizedHaystack = normalizedText(haystack);
        return terms.stream()
                .filter(term -> !term.isBlank())
                .anyMatch(term -> normalizedHaystack.contains(normalizedText(term)));
    }

    private static String longestMatchingTerm(String haystack, List<String> terms) {
        String normalizedHaystack = normalizedText(haystack);
        return terms.stream()
                .filter(term -> term.length() >= 3 && normalizedHaystack.contains(normalizedText(term)))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private static String pickReplacementTerm(List<String> newTerms) {
        return newTerms.stream()
                .filter(term -> term.contains(" "))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseGet(() -> newTerms.stream()
                        .max(java.util.Comparator.comparingInt(String::length))
                        .orElse(newTerms.get(0)));
    }

    private static String replaceCaseInsensitive(String text, String target, String replacement) {
        return java.util.regex.Pattern.compile(
                        java.util.regex.Pattern.quote(target), java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    private static String normalizedText(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .strip()
                .toLowerCase();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
