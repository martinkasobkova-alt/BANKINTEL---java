package cz.bankintel.service.calculations;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ScaleSuggestionService {

    private ScaleSuggestionService() {}

    public static Map<String, Object> suggestScaleFactors(Double scaleA, Double scaleB) {
        double sa = canonical(scaleA);
        double sb = canonical(scaleB);
        Map<String, Object> out = new LinkedHashMap<>();
        if (sa <= 0 || sb <= 0) {
            out.put("suggested_left_multiplier", 1.0);
            out.put("suggested_right_multiplier", 1.0);
            out.put("note_cs", "");
            return out;
        }
        if (sa == sb) {
            out.put("suggested_left_multiplier", 1.0);
            out.put("suggested_right_multiplier", 1.0);
            out.put("note_cs", "Obě řady používají stejnou škálovou základnu.");
            return out;
        }
        double ratio = sb / sa;
        if (ratio > 1) {
            out.put("suggested_left_multiplier", ratio);
            out.put("suggested_right_multiplier", 1.0);
            out.put(
                    "note_cs",
                    "Levá řada je v měřítku ×"
                            + trimDouble(sa)
                            + ", pravá ×"
                            + trimDouble(sb)
                            + ". Navrhujeme násobit levou stranu faktorem "
                            + trimDouble(ratio)
                            + ", aby byl řád hodnot jako vpravo.");
            return out;
        }
        double inv = sa / sb;
        out.put("suggested_left_multiplier", 1.0);
        out.put("suggested_right_multiplier", inv);
        out.put(
                "note_cs",
                "Levá řada je v měřítku ×"
                        + trimDouble(sa)
                        + ", pravá ×"
                        + trimDouble(sb)
                        + ". Navrhujeme násobit pravou stranu faktorem "
                        + trimDouble(inv)
                        + ".");
        return out;
    }

    private static double canonical(Double value) {
        if (value == null || value <= 0) {
            return 1.0;
        }
        return value;
    }

    private static String trimDouble(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
