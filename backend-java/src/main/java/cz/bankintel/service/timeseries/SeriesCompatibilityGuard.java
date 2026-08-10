package cz.bankintel.service.timeseries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic pre-calculation guardrails for the analytics engine — a Java port of the
 * metadata-mismatch and data-quality pattern already proven in {@code
 * forecast-service/app/guardrails.py}, generalized so any comparison/relationship/ratio
 * calculation between two series (not just a forecast target + exogenous candidate) gets the
 * same frequency/geo/unit/seasonal-adjustment/nominal-real/stock-flow checks before running.
 * Never silently proceeds with an economically nonsensical calculation — always either blocks
 * ({@code not_reliable}) or clearly warns ({@code warning}) with a stated reason.
 */
@Component
public final class SeriesCompatibilityGuard {

    public static final int MIN_OBSERVATIONS_ANY_CALCULATION = 6;
    public static final int MIN_COMMON_OBSERVATIONS_FOR_COMPARISON = 6;
    public static final int FREQUENCY_INCOMPATIBLE_RANK_GAP = 3;
    private static final double OUTLIER_Z_THRESHOLD = 4.0;
    private static final double STRUCTURAL_BREAK_Z_THRESHOLD = 3.0;

    private static final Map<String, Integer> FREQUENCY_RANK =
            Map.of("D", 0, "B", 0, "W", 1, "M", 2, "Q", 3, "S", 4, "H", 4, "Y", 5, "A", 5);

    public record SeriesMetadata(
            String frequency, String geo, String unit, String seasonalAdjustment, String priceBasis, String stockOrFlow) {

        public static SeriesMetadata of(String frequency, String geo, String unit) {
            return new SeriesMetadata(frequency, geo, unit, null, null, null);
        }
    }

    public record GuardrailResult(String status, List<String> warnings, List<String> whatWouldHelp) {

        public static GuardrailResult ok() {
            return new GuardrailResult("ok", List.of(), List.of());
        }
    }

    /** Single-series quality checks: minimum length, outliers, structural break heuristic. */
    public GuardrailResult checkSeries(Map<String, Double> series) {
        List<String> warnings = new ArrayList<>();
        List<String> whatWouldHelp = new ArrayList<>();
        int n = series.size();
        if (n < MIN_OBSERVATIONS_ANY_CALCULATION) {
            return new GuardrailResult(
                    "not_reliable",
                    List.of("series_too_short: " + n + " obs < " + MIN_OBSERVATIONS_ANY_CALCULATION + " required"),
                    List.of("Alespoň " + MIN_OBSERVATIONS_ANY_CALCULATION + " historických pozorování (nyní " + n + ")."));
        }
        warnings.addAll(outlierWarnings(series));
        warnings.addAll(structuralBreakWarnings(series));
        return new GuardrailResult(warnings.isEmpty() ? "ok" : "warning", warnings, whatWouldHelp);
    }

    /** Cross-series metadata compatibility checks for comparisons/relationships/ratios. */
    public GuardrailResult checkCompatibility(SeriesMetadata a, SeriesMetadata b) {
        List<String> warnings = new ArrayList<>();
        List<String> whatWouldHelp = new ArrayList<>();

        Integer rankA = a.frequency() != null ? FREQUENCY_RANK.get(a.frequency().toUpperCase(Locale.ROOT)) : null;
        Integer rankB = b.frequency() != null ? FREQUENCY_RANK.get(b.frequency().toUpperCase(Locale.ROOT)) : null;
        if (rankA != null && rankB != null && Math.abs(rankA - rankB) >= FREQUENCY_INCOMPATIBLE_RANK_GAP) {
            return new GuardrailResult(
                    "not_reliable",
                    List.of("frequency_incompatible: " + a.frequency() + " vs " + b.frequency()
                            + " (rank gap " + Math.abs(rankA - rankB) + " >= " + FREQUENCY_INCOMPATIBLE_RANK_GAP + ")"),
                    List.of("Použít řady se srovnatelnou frekvencí, nebo agregovat/resamplovat jednu z nich před porovnáním."));
        }
        if (rankA != null && rankB != null && !rankA.equals(rankB)) {
            warnings.add("frequency_mismatch: " + a.frequency() + " vs " + b.frequency() + " — porovnání přes odlišné frekvence, interpretujte obezřetně.");
        }
        if (notBlank(a.geo()) && notBlank(b.geo()) && !a.geo().equalsIgnoreCase(b.geo())) {
            warnings.add("geo_mismatch: " + a.geo() + " vs " + b.geo());
        }
        if (notBlank(a.unit()) && notBlank(b.unit()) && !a.unit().equalsIgnoreCase(b.unit())) {
            warnings.add("unit_mismatch: " + a.unit() + " vs " + b.unit() + " — hodnoty nejsou ve stejné jednotce, poměr/rozdíl může být zavádějící.");
        }
        if (notBlank(a.seasonalAdjustment()) && notBlank(b.seasonalAdjustment()) && !a.seasonalAdjustment().equalsIgnoreCase(b.seasonalAdjustment())) {
            warnings.add("seasonal_adjustment_mismatch: " + a.seasonalAdjustment() + " vs " + b.seasonalAdjustment());
        }
        if (notBlank(a.priceBasis()) && notBlank(b.priceBasis()) && !a.priceBasis().equalsIgnoreCase(b.priceBasis())) {
            warnings.add("nominal_real_mismatch: " + a.priceBasis() + " vs " + b.priceBasis() + " — jedna řada je nominální, druhá reálná (deflátovaná).");
        }
        if (notBlank(a.stockOrFlow()) && notBlank(b.stockOrFlow()) && !a.stockOrFlow().equalsIgnoreCase(b.stockOrFlow())) {
            warnings.add("stock_flow_mismatch: " + a.stockOrFlow() + " vs " + b.stockOrFlow() + " — porovnání stavové a tokové veličiny vyžaduje opatrnost.");
        }
        return new GuardrailResult(warnings.isEmpty() ? "ok" : "warning", warnings, whatWouldHelp);
    }

    /** Checks the number of periods the two series actually share is enough for a meaningful comparison. */
    public GuardrailResult checkCommonObservations(int commonObservations) {
        if (commonObservations < MIN_COMMON_OBSERVATIONS_FOR_COMPARISON) {
            return new GuardrailResult(
                    "not_reliable",
                    List.of("insufficient_common_observations: " + commonObservations + " < " + MIN_COMMON_OBSERVATIONS_FOR_COMPARISON),
                    List.of("Rozšířit časové překrytí obou řad, nebo zvolit řady se stejným rozsahem dat."));
        }
        return GuardrailResult.ok();
    }

    private static List<String> outlierWarnings(Map<String, Double> series) {
        if (series.size() < MIN_OBSERVATIONS_ANY_CALCULATION) {
            return List.of();
        }
        double sigma = TimeSeriesMath.populationStdev(series.values());
        if (sigma == 0.0) {
            return List.of();
        }
        double mean = TimeSeriesMath.mean(series);
        long flagged = series.values().stream().filter(v -> Math.abs((v - mean) / sigma) > OUTLIER_Z_THRESHOLD).count();
        if (flagged == 0) {
            return List.of();
        }
        return List.of("outliers_detected: " + flagged + " point(s) with |z|>" + (int) OUTLIER_Z_THRESHOLD);
    }

    private static List<String> structuralBreakWarnings(Map<String, Double> series) {
        Double z = TimeSeriesMath.structuralBreakZScore(series);
        if (z == null || z <= STRUCTURAL_BREAK_Z_THRESHOLD) {
            return List.of();
        }
        return List.of(String.format(
                Locale.US,
                "possible_structural_break: first-half vs second-half mean shift z=%.1f (heuristic screen, not a formal break test) — treat conclusions as more uncertain",
                z));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
