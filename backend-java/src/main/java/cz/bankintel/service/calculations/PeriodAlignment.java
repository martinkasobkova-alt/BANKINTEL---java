package cz.bankintel.service.calculations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PeriodAlignment {

    private static final Pattern PERIOD_Q = Pattern.compile("^(?<y>\\d{4})\\s*[Qq](?<q>[1-4])");

    private PeriodAlignment() {}

    @SafeVarargs
    public static List<String> sortedCommonPeriods(Map<String, Double>... maps) {
        Set<String> inter = null;
        for (Map<String, Double> map : maps) {
            if (map == null || map.isEmpty()) {
                return List.of();
            }
            if (inter == null) {
                inter = new LinkedHashSet<>(map.keySet());
            } else {
                inter.retainAll(map.keySet());
            }
        }
        if (inter == null || inter.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(inter);
        out.sort(PeriodAlignment::comparePeriods);
        return out;
    }

    public static String inferYoyPeriodKey(String period, Set<String> candidates) {
        Matcher m = PERIOD_Q.matcher(String.valueOf(period == null ? "" : period).strip());
        if (!m.matches()) {
            return null;
        }
        int y = Integer.parseInt(m.group("y"));
        String q = m.group("q");
        String cand = (y - 1) + "Q" + q;
        List<String> variants = List.of(cand, (y - 1) + "q" + q, (y - 1) + " Q" + q);
        for (String variant : variants) {
            if (candidates.contains(variant)) {
                return variant;
            }
            for (String candidate : candidates) {
                if (candidate.replace(" ", "").equalsIgnoreCase(variant.replace(" ", ""))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static int comparePeriods(String a, String b) {
        List<Integer> ka = digits(a);
        List<Integer> kb = digits(b);
        int len = Math.max(ka.size(), kb.size());
        for (int i = 0; i < len; i++) {
            int va = i < ka.size() ? ka.get(i) : 0;
            int vb = i < kb.size() ? kb.get(i) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        if (ka.size() != kb.size()) {
            return Integer.compare(ka.size(), kb.size());
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    public static String resolvePeriodAlias(String requested, Set<String> keys) {
        String normalized = normPeriodKey(requested);
        if (normalized.isBlank()) {
            return null;
        }
        for (String key : keys) {
            if (normPeriodKey(key).equals(normalized)) {
                return key;
            }
        }
        return null;
    }

    public static String periodSpanNote(List<String> periods) {
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(periods);
        sorted.sort(PeriodAlignment::comparePeriods);
        return sorted.size() > 1 ? sorted.getFirst() + " až " + sorted.getLast() : sorted.getFirst();
    }

    private static String normPeriodKey(String period) {
        return String.valueOf(period == null ? "" : period).strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static List<Integer> digits(String period) {
        String digits = period == null ? "" : period.chars().filter(Character::isDigit).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        List<Integer> out = new ArrayList<>();
        if (digits.length() >= 4) {
            try {
                out.add(Integer.parseInt(digits.substring(0, 4)));
                out.add(digits.length() >= 6 ? Integer.parseInt(digits.substring(4, 6)) : 0);
                out.add(digits.length() >= 8 ? Integer.parseInt(digits.substring(6, 8)) : 0);
                out.add(digits.length());
                return out;
            } catch (NumberFormatException ignored) {
                return List.of(0, 0, 0, 0);
            }
        }
        return List.of(0, 0, 0, 0);
    }
}
