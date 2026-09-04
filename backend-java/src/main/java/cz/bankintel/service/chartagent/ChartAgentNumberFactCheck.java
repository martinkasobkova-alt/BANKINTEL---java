package cz.bankintel.service.chartagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kontrola čísel, která jazykový model uvedl v odpovědi nad grafem, proti skutečným datům.
 *
 * Odpověď nad grafem formuluje LLM (viz {@code ChartAgentService#answerFromLlm}) — text tedy
 * může obsahovat číslo, které v grafu vůbec není (halucinace), i když samotné výpočty
 * ({@code calculations}) jsou spočítané deterministicky v Javě. Tahle třída z odpovědi vytáhne
 * čísla, která vypadají jako datový údaj (mají desetinnou čárku/tečku, jsou u procenta, nebo mají
 * 3+ číslic a nejsou rok), a ověří je proti hodnotám z bodů grafu, výsledkům výpočtů a jejich
 * mezidobním/celkovým změnám (běžná formulace typu "meziročně o X % výš").
 *
 * Nejde o jistotu ani v jednom směru: legitimní odvozená hodnota, kterou tahle množina referencí
 * nepokrývá (např. průměr jiné podmnožiny bodů), dopadne stejně jako skutečná halucinace — a
 * číslo, které náhodou padne blízko nějaké reference, projde i kdyby ve skutečnosti bylo špatně.
 * Proto se nález hlásí jako "nepodařilo se ověřit", ne jako "je špatně".
 */
public final class ChartAgentNumberFactCheck {

    private ChartAgentNumberFactCheck() {}

    private static final Pattern NUMBER =
            Pattern.compile("-?\\d{1,3}(?:[\\s\\u00A0]\\d{3})+(?:[.,]\\d+)?|-?\\d+(?:[.,]\\d+)?");
    private static final double MIN_ABS_TOLERANCE = 0.05;
    private static final double REL_TOLERANCE = 0.01;

    public static List<String> unverifiedNumbers(
            String answerCz, List<Map<String, Object>> seriesList, List<Map<String, Object>> calculations) {
        List<String> unverified = new ArrayList<>();
        if (answerCz == null || answerCz.isBlank()) {
            return unverified;
        }
        List<Double> refs = referenceValues(seriesList, calculations);
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(answerCz);
        while (matcher.find()) {
            String raw = matcher.group();
            boolean percent = isPercentSuffix(answerCz, matcher.end());
            boolean decimal = raw.indexOf(',') >= 0 || raw.indexOf('.') >= 0;
            if (!decimal && !percent && !looksLikeMeasurement(raw)) {
                continue;
            }
            Double value = normalize(raw);
            if (value == null || !seen.add(raw)) {
                continue;
            }
            if (!matchesAny(value, refs)) {
                unverified.add(raw + (percent ? " %" : ""));
            }
        }
        return unverified;
    }

    /**
     * Kdy je "nejbližší dostupná" hodnota vydávaná za dotazovaný rok, aniž by to odpověď řekla.
     *
     * Funguje v páru s {@code ChartAgentService#requestedPeriodLookup}: ten do
     * {@code requested_period_lookup} dá {@code nearest_available} právě tehdy, když pro
     * dotazovaný rok v datech řady nic není. Pokud se pak v odpovědi objeví číslo blízké téhle
     * náhradní hodnotě, ale její skutečné období nikde v textu není, jde přesně o ten zmatek,
     * který {@code requestedPeriodLookup} měl v promptu předejít — model si ho přesto domyslel.
     */
    public static String misattributedPeriodWarning(String answerCz, Map<String, Object> requestedPeriodLookup) {
        if (answerCz == null || answerCz.isBlank() || requestedPeriodLookup == null || requestedPeriodLookup.isEmpty()) {
            return null;
        }
        Object exactObj = requestedPeriodLookup.get("exact_matches");
        if (exactObj instanceof List<?> exact && !exact.isEmpty()) {
            return null;
        }
        Object nearestObj = requestedPeriodLookup.get("nearest_available");
        if (!(nearestObj instanceof Map<?, ?> nearest)) {
            return null;
        }
        Double nearestValue = ChartContractParser.num(nearest.get("value"));
        String nearestPeriod = ChartContractParser.str(nearest.get("period"));
        String requestedYear = ChartContractParser.str(requestedPeriodLookup.get("requested_year"));
        if (nearestValue == null || nearestPeriod.isBlank() || requestedYear.isBlank()) {
            return null;
        }
        if (answerCz.contains(nearestPeriod)) {
            return null;
        }
        if (!containsNumberCloseTo(answerCz, nearestValue)) {
            return null;
        }
        return "Pro rok "
                + requestedYear
                + " nejsou v datech řady žádná pozorování. Odpověď ale obsahuje hodnotu z nejbližšího "
                + "dostupného období ("
                + nearestPeriod
                + "), aniž by u ní to skutečné období uvedla — může jít o hodnotu omylem vydávanou za rok "
                + requestedYear
                + ".";
    }

    private static boolean containsNumberCloseTo(String text, double target) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            Double value = normalize(matcher.group());
            if (value != null
                    && Math.abs(value - target) <= Math.max(MIN_ABS_TOLERANCE, Math.abs(target) * REL_TOLERANCE)) {
                return true;
            }
        }
        return false;
    }

    /** Holé celé číslo je datový údaj jen od 3 číslic výš a jen když nevypadá jako rok. */
    private static boolean looksLikeMeasurement(String raw) {
        String digitsOnly = raw.replaceAll("[^0-9]", "");
        if (digitsOnly.length() < 3) {
            return false;
        }
        if (digitsOnly.length() == 4) {
            int asYear = Integer.parseInt(digitsOnly);
            if (asYear >= 1900 && asYear <= 2100) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPercentSuffix(String text, int afterIndex) {
        int i = afterIndex;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == '%';
    }

    private static Double normalize(String raw) {
        String s = raw.replaceAll("[\\s\\u00A0]", "");
        if (s.indexOf(',') >= 0) {
            s = s.replace(".", "").replace(',', '.');
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean matchesAny(double value, List<Double> refs) {
        for (double r : refs) {
            double tolerance = Math.max(MIN_ABS_TOLERANCE, Math.abs(r) * REL_TOLERANCE);
            if (Math.abs(value - r) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Double> referenceValues(
            List<Map<String, Object>> seriesList, List<Map<String, Object>> calculations) {
        List<Double> refs = new ArrayList<>();
        if (seriesList != null) {
            for (Map<String, Object> series : seriesList) {
                List<Double> levels = new ArrayList<>();
                Object pointsObj = series.get("points");
                if (pointsObj instanceof List<?> points) {
                    for (Object ptObj : points) {
                        if (ptObj instanceof Map<?, ?> raw) {
                            Double v = ChartContractParser.num(((Map<String, Object>) raw).get("value"));
                            if (v != null) {
                                levels.add(v);
                            }
                        }
                    }
                }
                refs.addAll(levels);
                addDerivedChanges(refs, levels);
            }
        }
        if (calculations != null) {
            for (Map<String, Object> calc : calculations) {
                collectNumbers(calc, refs);
            }
        }
        return refs;
    }

    /** Mezidobní a celkové změny (v hodnotě i v %) — časté formulace typu "meziročně o X % výš". */
    private static void addDerivedChanges(List<Double> refs, List<Double> levels) {
        for (int i = 1; i < levels.size(); i++) {
            double prev = levels.get(i - 1);
            double cur = levels.get(i);
            refs.add(cur - prev);
            if (prev != 0.0) {
                refs.add((cur - prev) / prev * 100.0);
            }
        }
        if (levels.size() >= 2) {
            double first = levels.get(0);
            double last = levels.get(levels.size() - 1);
            refs.add(last - first);
            if (first != 0.0) {
                refs.add((last - first) / first * 100.0);
            }
        }
    }

    private static void collectNumbers(Object node, List<Double> refs) {
        if (node instanceof Number n) {
            refs.add(n.doubleValue());
        } else if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                collectNumbers(v, refs);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectNumbers(v, refs);
            }
        }
    }
}
