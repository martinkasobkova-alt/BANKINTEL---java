package cz.bankintel.service.chartagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChartAgentEconomistService {

    private final OpenAiJsonSupport openAiJsonSupport;
    private final ObjectMapper objectMapper;

    @Value("${OPENAI_API_KEY:}")
    private String openAiApiKey;

    public String economistAnswer(
            String question,
            Map<String, Object> contract,
            List<Map<String, Object>> seriesList,
            List<Map<String, Object>> calculations,
            Map<String, Object> plan,
            String fallbackAnswer,
            List<Map<String, String>> conversationHistory,
            Map<String, Object> requestedPeriodLookup) {
        if (seriesList == null || seriesList.isEmpty() || !hasOpenAiKey()) {
            return null;
        }
        try {
            Map<String, Object> metadata = contract.get("metadata") instanceof Map<?, ?> m
                    ? objectMap(m)
                    : Map.of();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", question);
            payload.put(
                    "chart_title",
                    firstNonBlank(
                            ChartContractParser.str(contract.get("title")),
                            ChartContractParser.str(metadata.get("title")),
                            ChartContractParser.str(metadata.get("page_title"))));
            payload.put(
                    "scope",
                    firstNonBlank(
                            ChartContractParser.str(metadata.get("scope")),
                            ChartContractParser.str(metadata.get("context"))));
            payload.put("series", seriesAiBrief(seriesList));
            payload.put("calculations", calculations == null ? List.of() : calculations.stream().limit(8).toList());
            payload.put("deterministic_draft", fallbackAnswer == null ? "" : fallbackAnswer);
            payload.put("planned_operations", plan.get("operations"));
            payload.put("conversation_history", conversationHistory == null ? List.of() : conversationHistory);
            boolean hasPeriodLookup = requestedPeriodLookup != null && !requestedPeriodLookup.isEmpty();
            if (hasPeriodLookup) {
                payload.put("requested_period_lookup", requestedPeriodLookup);
            }

            Map<String, Object> result = openAiJsonSupport.chatJsonObject(
                    """
                    Jsi seniorní ekonom a datový analytik v české finanční aplikaci. \
                    Odpovídej česky, věcně a užitečně. Nejsi vyhledávač: vysvětli význam dat, \
                    porovnej řady, pojmenuj ekonomickou intuici, upozorni na omezení a navrhni další krok. \
                    Nehalucinuj externí fakta; používej jen dodaný datový kontext a výpočty. \
                    Pokud conversation_history obsahuje předchozí kola, navazuj na ně: nerozebírej znovu od \
                    začátku to, co už bylo řečeno, a pokud je otázka eliptická nebo odkazuje na předchozí \
                    odpověď (např. 'a jaký je vzorec', 'co to znamená u té druhé řady'), interpretuj ji v \
                    kontextu historie. \
                    Vrať JSON: {"answer_cz": "..."}.""",
                    "Uživatel se ptá nad grafem/dashboardem. Vytvoř ekonomickou odpověď, ne pouze opis hodnot.\n"
                            + "V každé řadě je pole series_points s reprezentativním (rovnoměrně vzorkovaným) průběhem "
                            + "celé řady včetně minima a maxima — čti z něj skutečný tvar, zlomy a vývoj, ne jen první a "
                            + "poslední bod.\n"
                            // series_points je vzorek, ne celá řada: u dlouhé měsíční řady se posílá jen část.
                            // Bez tohohle pravidla model bral „nejbližší bod" a vydával ho za dotazované
                            // období — uživatel pak četl číslo, které v grafu pro ten měsíc není.
                            + "series_points je VZOREK, ne úplný výčet. Uváděj jen hodnoty, které v něm "
                            + "skutečně jsou, a ke každému číslu napiš období, ze kterého pochází. Když se "
                            + "uživatel ptá na období, které ve vzorku není, napiš to a nabídni nejbližší "
                            + "dostupné — ale nevydávej jeho hodnotu za hodnotu dotazovaného období. Čísla "
                            + "si nedopočítávej ani neodhaduj.\n"
                            + (hasPeriodLookup
                                    ? "Otázka se ptá na konkrétní rok — kontext obsahuje requested_period_lookup "
                                            + "s přesně dohledanými daty pro primární řadu za ten rok. Když má "
                                            + "exact_matches neprázdné, odpověz VÝHRADNĚ z těchto hodnot (ne z "
                                            + "series_points). Když je exact_matches prázdné, znamená to, že "
                                            + "daný rok v datech chybí — v odpovědi to výslovně řekni a jako "
                                            + "náhradu uveď nearest_available VČETNĚ jeho vlastního období (jinak "
                                            + "to čtenář přečte jako hodnotu dotazovaného roku).\n"
                                    : "")
                            + "Kontext JSON:\n"
                            + objectMapper.writeValueAsString(payload),
                    OpenAiModelTask.CHAT);
            String answer = ChartContractParser.str(result.get("answer_cz"));
            return answer.isBlank() ? null : answer;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Map<String, Object>> seriesAiBrief(List<Map<String, Object>> seriesList) {
        List<Map.Entry<Map<String, Object>, List<Map<String, Object>>>> withPoints = new ArrayList<>();
        for (Map<String, Object> series : seriesList.stream().limit(20).toList()) {
            Object pointsObj = series.get("points");
            if (!(pointsObj instanceof List<?> points)) {
                continue;
            }
            List<Map<String, Object>> usable = new ArrayList<>();
            for (Object ptObj : points) {
                if (ptObj instanceof Map<?, ?> rawPt) {
                    Map<String, Object> pt = objectMap(rawPt);
                    if (ChartContractParser.num(pt.get("value")) != null) {
                        usable.add(pt);
                    }
                }
            }
            if (!usable.isEmpty()) {
                withPoints.add(Map.entry(series, usable));
            }
        }
        if (withPoints.isEmpty()) {
            return List.of();
        }
        int perSeriesCap = Math.max(40, Math.min(200, 900 / withPoints.size()));
        List<Map<String, Object>> briefs = new ArrayList<>();
        for (Map.Entry<Map<String, Object>, List<Map<String, Object>>> entry : withPoints) {
            Map<String, Object> series = entry.getKey();
            List<Map<String, Object>> points = entry.getValue();
            List<Double> values = points.stream().map(p -> ChartContractParser.num(p.get("value"))).toList();
            Map<String, Object> first = points.getFirst();
            Map<String, Object> last = points.getLast();
            int minI = 0;
            int maxI = 0;
            for (int i = 1; i < values.size(); i++) {
                if (values.get(i) < values.get(minI)) {
                    minI = i;
                }
                if (values.get(i) > values.get(maxI)) {
                    maxI = i;
                }
            }
            double delta = values.getLast() - values.getFirst();
            Double rel = values.getFirst() != 0.0 ? (delta / values.getFirst()) * 100.0 : null;
            Set<Integer> keep = new TreeSet<>(sampleIndices(points.size(), perSeriesCap));
            keep.add(minI);
            keep.add(maxI);
            List<Map<String, Object>> seriesPoints = new ArrayList<>();
            for (int idx : keep) {
                Map<String, Object> p = points.get(idx);
                seriesPoints.add(Map.of("period", p.get("period"), "value", p.get("value")));
            }
            String src = ChartContractParser.str(series.get("source"));
            if (src.isBlank() && !points.isEmpty()) {
                src = ChartContractParser.str(points.getFirst().get("source"));
            }
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("id", series.get("id"));
            brief.put("label", series.get("label"));
            brief.put("source", src);
            brief.put(
                    "geo",
                    firstNonBlank(
                            ChartContractParser.str(series.get("geo")),
                            ChartContractParser.str(series.get("country"))));
            brief.put("unit", series.get("unit"));
            brief.put("frequency", series.get("frequency"));
            brief.put("observations", points.size());
            brief.put("first", Map.of("period", first.get("period"), "value", first.get("value")));
            brief.put("last", Map.of("period", last.get("period"), "value", last.get("value")));
            brief.put("change_abs", delta);
            brief.put("change_pct", rel);
            brief.put(
                    "min",
                    Map.of("period", points.get(minI).get("period"), "value", points.get(minI).get("value")));
            brief.put(
                    "max",
                    Map.of("period", points.get(maxI).get("period"), "value", points.get(maxI).get("value")));
            brief.put("series_points", seriesPoints);
            briefs.add(brief);
        }
        return briefs;
    }

    private static List<Integer> sampleIndices(int n, int cap) {
        if (n <= 0) {
            return List.of();
        }
        if (cap <= 1) {
            return List.of(0);
        }
        if (n <= cap) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                all.add(i);
            }
            return all;
        }
        Set<Integer> idxs = new TreeSet<>();
        double step = (n - 1) / (double) (cap - 1);
        for (int i = 0; i < cap; i++) {
            idxs.add((int) Math.round(i * step));
        }
        idxs.add(0);
        idxs.add(n - 1);
        return new ArrayList<>(idxs);
    }

    private boolean hasOpenAiKey() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static Map<String, Object> objectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
