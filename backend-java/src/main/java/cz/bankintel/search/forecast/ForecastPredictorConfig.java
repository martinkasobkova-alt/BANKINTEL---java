package cz.bankintel.search.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogTextUtils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@code catalog/forecast_predictors.json} — the domain -> exogenous-predictor map used
 * by {@link ForecastPlannerService}. Mirrors the loading pattern of {@code CatalogQueryIntent}'s
 * {@code intent_groups.json} so predictor curation stays a data file, not Java code, and can be
 * extended to new domains (new industries, new macro topics) without touching planner logic.
 */
public final class ForecastPredictorConfig {

    private static final Logger log = LoggerFactory.getLogger(ForecastPredictorConfig.class);
    private static final String RESOURCE_PATH = "/catalog/forecast_predictors.json";

    public record Predictor(String role, String conceptCz, String searchQuery, String valueKind) {}

    public record Domain(
            String key,
            String labelCz,
            List<String> matchTerms,
            List<Predictor> predictors,
            List<Integer> commonLags,
            String preferredFrequency,
            String preferredTargetTransformation,
            List<String> preferredSeriesHint,
            String notes) {}

    private static final ForecastPredictorConfig INSTANCE = new ForecastPredictorConfig();

    private final List<Domain> domains;

    private ForecastPredictorConfig() {
        this.domains = load();
    }

    public static ForecastPredictorConfig get() {
        return INSTANCE;
    }

    public List<Domain> domains() {
        return domains;
    }

    /** Resolve the best-matching domain for a free-text target label/query (folded ASCII substring match). */
    public Optional<Domain> resolveDomain(String targetLabel) {
        if (targetLabel == null || targetLabel.isBlank()) {
            return Optional.empty();
        }
        String folded = CatalogTextUtils.foldAscii(targetLabel).toLowerCase(Locale.ROOT);
        Domain best = null;
        int bestScore = 0;
        for (Domain domain : domains) {
            int score = 0;
            for (String term : domain.matchTerms()) {
                if (folded.contains(term)) {
                    int tokenCount = term.isBlank() ? 0 : term.split("\\s+").length;
                    score += tokenCount * 100 + term.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = domain;
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<Domain> load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ForecastPredictorConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.warn("forecast_predictors.json not found on classpath ({}); planner will run without domain predictors", RESOURCE_PATH);
                return List.of();
            }
            JsonNode root = mapper.readTree(in);
            JsonNode domainsNode = root.path("domains");
            List<Domain> out = new ArrayList<>();
            domainsNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode node = entry.getValue();
                String labelCz = node.path("label_cz").asText(key);
                List<String> matchTerms = new ArrayList<>();
                node.path("match_terms").forEach(t -> matchTerms.add(CatalogTextUtils.foldAscii(t.asText("")).toLowerCase(Locale.ROOT)));
                List<Predictor> predictors = new ArrayList<>();
                node.path("predictors").forEach(p -> predictors.add(new Predictor(
                        p.path("role").asText(""),
                        p.path("concept_cz").asText(""),
                        p.path("search_query").asText(""),
                        p.path("value_kind").asText(""))));
                List<Integer> commonLags = new ArrayList<>();
                node.path("common_lags").forEach(l -> commonLags.add(l.asInt()));
                List<String> preferredSeriesHint = new ArrayList<>();
                node.path("preferred_series_hint").forEach(h -> preferredSeriesHint.add(h.asText("")));
                out.add(new Domain(
                        key,
                        labelCz,
                        matchTerms,
                        predictors,
                        commonLags,
                        node.path("preferred_frequency").asText(""),
                        node.path("preferred_target_transformation").asText(""),
                        preferredSeriesHint,
                        node.path("notes").asText("")));
            });
            return out;
        } catch (Exception ex) {
            log.warn("Failed to load forecast_predictors.json: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Exposed for tests that need a raw map view instead of the typed Domain records. */
    public Map<String, Object> domainAsMap(Domain domain) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", domain.key());
        out.put("label_cz", domain.labelCz());
        out.put("common_lags", domain.commonLags());
        out.put("preferred_frequency", domain.preferredFrequency());
        out.put("preferred_series_hint", domain.preferredSeriesHint());
        out.put("predictors", domain.predictors().stream().map(p -> Map.of(
                "role", p.role(), "concept_cz", p.conceptCz(), "search_query", p.searchQuery(), "value_kind", p.valueKind())).toList());
        return out;
    }
}
