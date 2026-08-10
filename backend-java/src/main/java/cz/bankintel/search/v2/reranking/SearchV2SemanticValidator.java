package cz.bankintel.search.v2.reranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import cz.bankintel.search.v2.entity.SearchV2ExactEntityScorer;
import cz.bankintel.search.v2.geo.SearchV2GeoCompatibility;
import cz.bankintel.search.v2.ontology.SearchV2ConceptOntology;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.schema.SearchCandidate;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SemanticDecision;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2SemanticValidator {

    private static final int BATCH_SIZE = 5;
    private static final int MAX_CANDIDATES_FOR_FALLBACK = 80;
    private static final long BATCH_TIMEOUT_MS = 20_000;
    private static final String PROMPT = loadPrompt();

    /** See {@code SearchV2QueryPlanner.PLANNER_PROMPT_VERSION} for the versioning rationale. */
    public static final String RERANKER_PROMPT_VERSION = "v1-2026-07-24";

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;
    private final SearchV2ConceptOntology conceptOntology;
    private final SearchV2ExactEntityScorer exactEntityScorer;
    private final SearchV2InstitutionalSectorRegistry institutionalSectorRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public record ValidationResult(
            List<SemanticDecision> decisions,
            String status,
            String model,
            long latencyMs,
            int approxPromptTokens,
            List<Map<String, Object>> batches,
            List<String> errors) {}

    private record BatchOutcome(
            int index,
            List<SearchCandidate> candidates,
            List<SemanticDecision> decisions,
            long latencyMs,
            String error) {}

    public ValidationResult validate(SearchQueryPlan plan, List<SearchCandidate> candidates) {
        return validate(plan, candidates, true);
    }

    public ValidationResult validate(SearchQueryPlan plan, List<SearchCandidate> candidates, boolean useAi) {
        long start = System.currentTimeMillis();
        if (candidates == null || candidates.isEmpty()) {
            return new ValidationResult(List.of(), "empty", null, 0, 0, List.of(), List.of());
        }
        if (!useAi) {
            return fallback(plan, candidates, start, "disabled", "Semantic validation disabled by request.");
        }
        if (!openAiClient.isConfigured()) {
            return fallback(plan, candidates, start, "unavailable", "OPENAI_API_KEY is not configured");
        }
        List<SemanticDecision> out = new ArrayList<>();
        List<Map<String, Object>> batches = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String model = openAiClient.modelFor(OpenAiModelTask.RERANKER);
        int tokenEstimate = 0;
        List<CompletableFuture<BatchOutcome>> pending = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
            int batchIndex = i / BATCH_SIZE;
            List<SearchCandidate> batch = List.copyOf(candidates.subList(i, Math.min(candidates.size(), i + BATCH_SIZE)));
            try {
                String prompt = buildUserPrompt(plan, batch);
                tokenEstimate += prompt.length() / 4;
                long batchStart = System.currentTimeMillis();
                pending.add(CompletableFuture
                        .supplyAsync(() -> {
                            JsonNode json = openAiClient.chatCompletionJson(PROMPT, prompt, OpenAiModelTask.RERANKER);
                            return parseDecisions(json, batch);
                        }, executor)
                        .orTimeout(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .handle((decisions, error) -> new BatchOutcome(
                                batchIndex,
                                batch,
                                error == null ? decisions : List.of(),
                                System.currentTimeMillis() - batchStart,
                                error == null ? null : rootMessage(error))));
            } catch (Exception ex) {
                pending.add(CompletableFuture.completedFuture(
                        new BatchOutcome(batchIndex, batch, List.of(), 0, rootMessage(ex))));
            }
        }
        for (CompletableFuture<BatchOutcome> future : pending) {
            BatchOutcome outcome = future.join();
            if (outcome.error() == null) {
                out.addAll(enforceProvenStructuredConflicts(plan, outcome.candidates(), outcome.decisions()));
                batches.add(batchStat(
                        outcome.index(),
                        outcome.candidates().size(),
                        outcome.decisions().size(),
                        outcome.latencyMs(),
                        true,
                        null));
            } else {
                errors.add(outcome.error());
                out.addAll(fallbackDecisions(plan, outcome.candidates(), out.size()));
                batches.add(batchStat(
                        outcome.index(),
                        outcome.candidates().size(),
                        0,
                        outcome.latencyMs(),
                        false,
                        outcome.error()));
            }
        }
        if (out.isEmpty()) {
            return fallback(plan, candidates, start, "unavailable", String.join("; ", errors));
        }
        return new ValidationResult(out, errors.isEmpty() ? "validated" : "partial", model, System.currentTimeMillis() - start, tokenEstimate, batches, errors);
    }

    private List<SemanticDecision> enforceProvenStructuredConflicts(
            SearchQueryPlan plan,
            List<SearchCandidate> candidates,
            List<SemanticDecision> decisions) {
        Map<String, SearchCandidate> bySeriesId = candidates.stream()
                .collect(Collectors.toMap(SearchCandidate::seriesId, candidate -> candidate, (left, right) -> left));
        TargetProfile target = TargetProfile.from(plan, institutionalSectorRegistry);
        List<SemanticDecision> reconciled = new ArrayList<>(decisions.size());
        for (SemanticDecision decision : decisions) {
            SearchCandidate candidate = bySeriesId.get(decision.seriesId());
            if (candidate == null) {
                reconciled.add(decision);
                continue;
            }
            CandidateProfile profile = CandidateProfile.from(candidate);
            String candidateText = normalized(String.join(
                    " ",
                    safe(candidate.title()),
                    safe(candidate.description()),
                    safe(candidate.dataset()),
                    safe(candidate.seriesId())));
            List<String> conflicts = new ArrayList<>();
            metadataCompatibilityScore(target, profile, candidateText, new ArrayList<>(), conflicts);
            String provenConflict = conflicts.stream()
                    .filter(conflict -> hardSemanticConflict(conflict, target, profile))
                    .filter(conflict -> hasStructuredConflictEvidence(candidate, conflict))
                    .findFirst()
                    .orElse("");
            if (provenConflict.isBlank()) {
                reconciled.add(decision);
                continue;
            }
            List<String> semanticConflicts = new ArrayList<>(decision.semanticConflicts());
            if (!semanticConflicts.contains(provenConflict)) {
                semanticConflicts.add(provenConflict);
            }
            semanticConflicts.add("proven_structured_conflict");
            reconciled.add(new SemanticDecision(
                    decision.seriesId(),
                    "drop",
                    Math.min(decision.relevanceScore(), 0.2),
                    Math.max(decision.confidence(), 0.9),
                    decision.matchedUserNeed(),
                    semanticConflicts.stream().distinct().toList(),
                    "Candidate rejected because structured catalog metadata contradicts an explicit query constraint.",
                    "reject"));
        }
        return reconciled;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current.getCause() != null)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? "Unknown semantic validation error" : current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public ValidationResult unavailableFallback(List<SearchCandidate> candidates, String reason) {
        return fallback(null, candidates, System.currentTimeMillis(), "unavailable", reason);
    }

    public ValidationResult unavailableFallback(SearchQueryPlan plan, List<SearchCandidate> candidates, String reason) {
        return fallback(plan, candidates, System.currentTimeMillis(), "unavailable", reason);
    }

    private ValidationResult fallback(
            SearchQueryPlan plan, List<SearchCandidate> candidates, long start, String status, String reason) {
        List<SemanticDecision> decisions = new ArrayList<>();
        int i = 0;
        for (SearchCandidate candidate : candidates.stream().limit(MAX_CANDIDATES_FOR_FALLBACK).toList()) {
            decisions.add(fallbackDecision(plan, candidate, i++));
        }
        return new ValidationResult(
                decisions,
                status,
                null,
                System.currentTimeMillis() - start,
                0,
                List.of(),
                reason == null || reason.isBlank() ? List.of() : List.of(reason));
    }

    private List<SemanticDecision> fallbackDecisions(SearchQueryPlan plan, List<SearchCandidate> candidates, int offset) {
        List<SemanticDecision> decisions = new ArrayList<>();
        int rank = Math.max(0, offset);
        for (SearchCandidate candidate : candidates == null ? List.<SearchCandidate>of() : candidates) {
            decisions.add(fallbackDecision(plan, candidate, rank++));
        }
        return decisions;
    }

    private SemanticDecision fallbackDecision(SearchQueryPlan plan, SearchCandidate candidate, int rank) {
        List<String> terms = fallbackTerms(plan);
        TargetProfile target = TargetProfile.from(plan, institutionalSectorRegistry);
        CandidateProfile candidateProfile = CandidateProfile.from(candidate);
        String title = normalized(candidate.title());
        String text = normalized(String.join(
                " ",
                safe(candidate.title()),
                safe(candidate.description()),
                safe(candidate.dataset()),
                safe(candidate.seriesId()),
                String.join(" ", candidate.concepts() == null ? List.of() : candidate.concepts()),
                String.join(" ", candidate.tags() == null ? List.of() : candidate.tags()),
                String.join(" ", candidate.categoryPath() == null ? List.of() : candidate.categoryPath())));
        double score = 0.34;
        List<String> matched = new ArrayList<>();
        for (String term : terms) {
            if (CatalogTextUtils.containsWholeTokenOrPhrase(title, term)) {
                score += 0.08;
                matched.add(term);
            } else if (CatalogTextUtils.containsWholeTokenOrPhrase(text, term)) {
                score += 0.04;
                matched.add(term);
            }
            if (matched.size() >= 8) {
                break;
            }
        }
        if (coversOriginalQuery(plan, candidate, text)) {
            score += 0.16;
            matched.add("original_query_full_coverage");
        }
        List<String> missingRequiredSignals = missingRequiredSignals(plan, text);
        for (String signal : requiredSignals(plan)) {
            if (!missingRequiredSignals.contains(signal) && !matched.contains(signal)) {
                score += 0.12;
                matched.add(signal);
            }
        }
        boolean contextOnly = containsContextOnlyTerm(text) && !containsContextOnlyTerm(normalized(plan == null ? "" : plan.originalQuery()));
        if (contextOnly) {
            score -= 0.22;
        }
        List<String> conflicts = new ArrayList<>();
        score += metadataCompatibilityScore(target, candidateProfile, text, matched, conflicts);
        List<String> requestedGeo = plan == null || plan.geographies() == null ? List.of() : plan.geographies();
        String inferredGeo = SearchV2GeoCompatibility.inferredCandidateGeo(candidate);
        boolean geoCompatible = SearchV2GeoCompatibility.candidateMatchesRequestedGeo(candidate, requestedGeo, plan);
        boolean explicitGeoConflict = !requestedGeo.isEmpty() && !inferredGeo.isBlank() && !geoCompatible;
        if (explicitGeoConflict) {
            score -= 0.5;
            conflicts.add("explicit_geo_mismatch:" + inferredGeo);
        } else if (!requestedGeo.isEmpty() && !inferredGeo.isBlank()) {
            score += 0.16;
            matched.add("geo:" + inferredGeo);
        }
        score -= Math.min(rank * 0.01, 0.16);
        score = clamp(score);
        conflicts.add("semantic_rerank_unavailable");
        if (contextOnly) {
            conflicts.add("fallback_context_series");
        }
        boolean semanticConflict = explicitGeoConflict
                || conflicts.stream().anyMatch(conflict ->
                        hardSemanticConflict(conflict, target, candidateProfile)
                                && hasStructuredConflictEvidence(candidate, conflict));
        boolean unprovenSemanticConflict = !semanticConflict
                && conflicts.stream().anyMatch(conflict ->
                        hardSemanticConflict(conflict, target, candidateProfile)
                                && !hasStructuredConflictEvidence(candidate, conflict));
        boolean softOnly = !semanticConflict && conflicts.stream().anyMatch(conflict -> softSemanticConflict(conflict, target, candidateProfile));
        boolean lexicalMiss = (!terms.isEmpty() && matched.isEmpty()) || !missingRequiredSignals.isEmpty();
        if (lexicalMiss || unprovenSemanticConflict) {
            if (!conflicts.contains("fallback_unverified_candidate")) {
                conflicts.add("fallback_unverified_candidate");
            }
        }
        if (lexicalMiss) {
            conflicts.add("fallback_no_metadata_match");
        }
        for (String signal : missingRequiredSignals) {
            conflicts.add("fallback_missing_required_signal:" + signal);
        }
        String decision = semanticConflict ? "drop" : "keep";
        String role = semanticConflict
                ? "reject"
                : ((lexicalMiss || unprovenSemanticConflict || contextOnly || softOnly) ? "context" : "primary");
        double finalScore = semanticConflict
                ? Math.min(score, 0.42)
                : (lexicalMiss || unprovenSemanticConflict)
                        ? Math.min(Math.max(score, 0.2), 0.38)
                        : Math.max(0.2, softOnly ? Math.min(score, 0.55) : score);
        return new SemanticDecision(
                candidate.seriesId(),
                decision,
                finalScore,
                lexicalMiss || unprovenSemanticConflict || contextOnly ? 0.24 : 0.38,
                matched.isEmpty() ? List.of("fts_candidate") : matched.stream().distinct().limit(8).toList(),
                conflicts,
                semanticConflict
                        ? "LLM semantic validation is unavailable; the candidate has a proven structured conflict."
                        : "LLM semantic validation is unavailable; the candidate is retained without a semantic verdict.",
                role);
    }

    private static boolean hasStructuredConflictEvidence(SearchCandidate candidate, String conflict) {
        if (candidate == null || conflict == null || conflict.isBlank()) {
            return false;
        }
        if (conflict.startsWith("institutional_sector_mismatch:")) {
            return hasRawValue(candidate, "institutional_sector");
        }
        if (conflict.startsWith("missing_explicit_institutional_sector:")) {
            return false;
        }
        return switch (conflict) {
            case "core_vs_headline_inflation", "net_profit_vs_profitability_ratio",
                    "house_price_vs_housing_quantity", "market_price_vs_non_price_series" ->
                hasRawValue(candidate, "measure_type");
            case "policy_rate_vs_retail_lending_rate" ->
                hasRawValue(candidate, "measure_type") || hasRawValue(candidate, "instrument");
            case "real_vs_nominal" -> hasRawValue(candidate, "nominal_real");
            case "total_economy_vs_government_sector" -> hasRawValue(candidate, "institutional_sector");
            case "market_price_vs_reserve_asset", "gold_price_vs_other_commodity" ->
                hasRawValue(candidate, "economic_object");
            case "equity_market_price_vs_unrelated_series" ->
                hasRawValue(candidate, "catalog_family") || hasRawValue(candidate, "instrument");
            case "automotive_vs_unrelated_industry", "industrial_production_vs_unrelated_series" ->
                hasRawValue(candidate, "industry_sector");
            case "bank_profit_vs_balance_sheet", "wages_vs_unrelated_series", "house_price_vs_unrelated_series" ->
                hasRawValue(candidate, "economic_object") || hasRawValue(candidate, "catalog_family");
            default -> false;
        };
    }

    private static boolean hasRawValue(SearchCandidate candidate, String key) {
        return !raw(candidate, key).isBlank();
    }

    private static boolean coversOriginalQuery(SearchQueryPlan plan, SearchCandidate candidate, String candidateText) {
        if (plan == null || plan.originalQuery() == null || plan.originalQuery().isBlank()) {
            return false;
        }
        String original = normalized(plan.originalQuery());
        List<String> tokens = Arrays.stream(original.split(" "))
                .filter(token -> token.length() >= 2)
                .distinct()
                .toList();
        if (tokens.size() < 2) {
            return false;
        }
        boolean matchedOriginalVariant = normalized(candidate.matchedQuery()).equals(original);
        return matchedOriginalVariant
                && tokens.stream().allMatch(token -> CatalogTextUtils.containsWholeTokenOrPhrase(candidateText, token));
    }

    private List<String> fallbackTerms(SearchQueryPlan plan) {
        if (plan == null) {
            return List.of();
        }
        List<String> raw = new ArrayList<>();
        raw.add(plan.originalQuery());
        raw.addAll(plan.allSearchTerms());
        List<String> out = new ArrayList<>();
        for (String value : raw) {
            for (String token : normalized(value).split("\\s+")) {
                if (token.isBlank() || conceptOntology.fallbackStopTerms().contains(token)) {
                    continue;
                }
                if (token.length() < 4 && !conceptOntology.isShortMeaningfulTerm(token)) {
                    continue;
                }
                if (!out.contains(token)) {
                    out.add(token);
                }
            }
        }
        return out.stream().limit(20).toList();
    }

    private boolean containsContextOnlyTerm(String text) {
        for (String term : conceptOntology.contextOnlyTerms()) {
            if (CatalogTextUtils.containsWholeTokenOrPhrase(text, term)) {
                return true;
            }
        }
        return false;
    }

    private static double metadataCompatibilityScore(
            TargetProfile target,
            CandidateProfile candidate,
            String candidateText,
            List<String> matched,
            List<String> conflicts) {
        if (target.empty()) {
            return 0.0;
        }
        double score = 0.0;
        if (compatible(target.measureType(), candidate.measureType(), candidate.primaryConcept())) {
            score += 0.24;
            addMatch(matched, "measure_type:" + target.measureType());
        } else {
            addMeasureConflict(target, candidate, candidateText, conflicts);
        }
        if (compatible(target.economicObject(), candidate.economicObject(), candidate.primaryConcept())) {
            score += 0.12;
            addMatch(matched, "economic_object:" + target.economicObject());
        } else if ("bank_profit".equals(target.economicObject())
                && Set.of("net_profit", "roe", "roa", "income").contains(candidate.measureType())) {
            score += 0.08;
            addMatch(matched, "economic_object:bank_profit_proxy");
        } else if ("bank_profit".equals(target.economicObject())
                && ("banking".equals(candidate.catalogFamily())
                        || "banks".equals(candidate.institutionalSector())
                        || containsAny(candidate.primaryConcept(), "bank"))) {
            conflicts.add("bank_profit_vs_balance_sheet");
        } else if ("wages".equals(target.economicObject())
                && !"wages".equals(candidate.economicObject())
                && !containsAny(candidate.primaryConcept(), "wages", "earnings")) {
            conflicts.add("wages_vs_unrelated_series");
        } else if ("housing".equals(target.economicObject())
                && !"housing".equals(candidate.economicObject())
                && !"house_price_index".equals(candidate.measureType())
                && !"house_price_index".equals(candidate.primaryConcept())) {
            conflicts.add("house_price_vs_unrelated_series");
        } else if ("gold".equals(target.economicObject())
                && !"gold".equals(candidate.economicObject())
                && !containsAny(candidateText, "gold", "zlato")) {
            conflicts.add("gold_price_vs_other_commodity");
        }
        if (compatible(target.instrument(), candidate.instrument(), candidate.primaryConcept())) {
            score += 0.12;
            addMatch(matched, "instrument:" + target.instrument());
        }
        if (compatible(target.catalogFamily(), candidate.catalogFamily(), candidate.primaryConcept())) {
            score += 0.10;
            addMatch(matched, "catalog_family:" + target.catalogFamily());
        }
        if (compatible(target.industrySector(), candidate.industrySector(), candidate.primaryConcept())) {
            score += 0.14;
            addMatch(matched, "industry_sector:" + target.industrySector());
        } else if ("automotive_manufacturing".equals(target.industrySector())
                && !"automotive_manufacturing".equals(candidate.industrySector())) {
            conflicts.add("automotive_vs_unrelated_industry");
        } else if ("industry".equals(target.industrySector())
                && containsAny(candidateText, "electricity", "bioenergy", "energy capacity", "power generation")) {
            conflicts.add("industrial_production_vs_unrelated_series");
        }
        if (compatible(target.nominalReal(), candidate.nominalReal(), candidate.primaryConcept())) {
            score += 0.10;
            addMatch(matched, "nominal_real:" + target.nominalReal());
        } else if (!target.nominalReal().isBlank()
                && !candidate.nominalReal().isBlank()
                && !target.nominalReal().equals(candidate.nominalReal())) {
            conflicts.add("real_vs_nominal");
        }
        if ("total_economy".equals(target.institutionalSector())
                && "government".equals(candidate.institutionalSector())) {
            conflicts.add("total_economy_vs_government_sector");
        }
        if (!target.institutionalSector().isBlank()
                && !candidate.institutionalSector().isBlank()
                && !target.institutionalSector().equals(candidate.institutionalSector())) {
            conflicts.add("institutional_sector_mismatch:"
                    + target.institutionalSector() + ":" + candidate.institutionalSector());
        } else if (!target.institutionalSector().isBlank()
                && candidate.institutionalSector().isBlank()) {
            conflicts.add("missing_explicit_institutional_sector:" + target.institutionalSector());
        }
        if ("commodity_market_price".equals(target.priceType())
                && "central_bank_gold_reserves".equals(candidate.economicObject())) {
            conflicts.add("market_price_vs_reserve_asset");
        }
        if ("markets_equities".equals(target.catalogFamily())
                && !"markets_equities".equals(candidate.catalogFamily())
                && !"equity".equals(candidate.instrument())) {
            conflicts.add("equity_market_price_vs_unrelated_series");
        }
        if ("house_price_index".equals(target.measureType())
                && (Set.of("count", "output").contains(candidate.measureType())
                        || containsAny(candidateText, "completed dwellings", "construction production", "housing completions"))) {
            conflicts.add("house_price_vs_housing_quantity");
        }
        return score;
    }

    private static void addMeasureConflict(
            TargetProfile target, CandidateProfile candidate, String candidateText, List<String> conflicts) {
        String expected = target.measureType();
        String actual = candidate.measureType();
        if (expected.isBlank()) {
            return;
        }
        if ("core_inflation".equals(expected)
                && (Set.of("headline_inflation", "price_index").contains(actual)
                        || containsAny(candidateText, "headline inflation", "all items", "overall inflation"))) {
            conflicts.add("core_vs_headline_inflation");
        }
        if ("net_profit".equals(expected) && Set.of("roa", "roe", "ratio").contains(actual)) {
            conflicts.add("net_profit_vs_profitability_ratio");
        }
        if ("central_bank_policy_rate".equals(expected)
                && (containsAny(candidateText, "lending rate", "loan", "mortgage", "retail rate")
                        || "loan".equals(candidate.instrument()))) {
            conflicts.add("policy_rate_vs_retail_lending_rate");
        }
        if ("industrial_production_index".equals(expected)
                && containsAny(candidateText, "bank lending survey", "construction only", "construction production",
                        "electricity", "bioenergy", "energy capacity", "power generation")) {
            conflicts.add("industrial_production_vs_unrelated_series");
        }
        if ("market_price".equals(expected)
                && "central_bank_gold_reserves".equals(candidate.economicObject())) {
            conflicts.add("market_price_vs_reserve_asset");
        }
        if ("market_price".equals(expected)
                && Set.of("output", "count", "stock").contains(candidate.measureType())) {
            conflicts.add("market_price_vs_non_price_series");
        }
    }

    private static boolean hardSemanticConflict(String conflict, TargetProfile target, CandidateProfile candidate) {
        return !conflict.startsWith("fallback_")
                && !"semantic_rerank_unavailable".equals(conflict)
                && !softSemanticConflict(conflict, target, candidate);
    }

    private static boolean softSemanticConflict(String conflict, TargetProfile target, CandidateProfile candidate) {
        return "real_vs_nominal".equals(conflict)
                && "wages".equals(target.economicObject())
                && "wages".equals(candidate.economicObject())
                && !"government".equals(candidate.institutionalSector());
    }

    private static boolean compatible(String expected, String actual, String primaryConcept) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String exp = expected.trim().toLowerCase(Locale.ROOT);
        String act = actual == null ? "" : actual.trim().toLowerCase(Locale.ROOT);
        String concept = primaryConcept == null ? "" : primaryConcept.trim().toLowerCase(Locale.ROOT);
        return exp.equals(act) || exp.equals(concept) || concept.contains(exp);
    }

    private static void addMatch(List<String> matched, String value) {
        if (value != null && !value.isBlank() && !matched.contains(value)) {
            matched.add(value);
        }
    }

    private List<String> requiredSignals(SearchQueryPlan plan) {
        if (plan == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        sources.add(plan.originalQuery());
        for (String source : sources) {
            for (String token : normalized(source).split("\\s+")) {
                if (conceptOntology.isRequiredSignal(token) && !out.contains(token)) {
                    out.add(token);
                }
            }
        }
        return out;
    }

    private List<String> missingRequiredSignals(SearchQueryPlan plan, String candidateText) {
        List<String> missing = new ArrayList<>();
        for (String signal : requiredSignals(plan)) {
            boolean matched = false;
            for (String alias : conceptOntology.aliasesForSignal(signal)) {
                if (CatalogTextUtils.containsWholeTokenOrPhrase(candidateText, alias)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                missing.add(signal);
            }
        }
        return missing;
    }

    private static boolean containsAny(String text, String... needles) {
        String haystack = normalized(text);
        if (haystack.isBlank()) {
            return false;
        }
        for (String needle : needles == null ? new String[0] : needles) {
            String n = normalized(needle);
            if (!n.isBlank() && (" " + haystack + " ").contains(" " + n + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String raw(SearchCandidate candidate, String key) {
        if (candidate == null || candidate.raw() == null || key == null) {
            return "";
        }
        Object value = candidate.raw().get(key);
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values == null ? new String[0] : values) {
            if (value != null && !value.isBlank()) {
                return value.trim().toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private record TargetProfile(
            String measureType,
            String economicObject,
            String instrument,
            String industrySector,
            String nominalReal,
            String institutionalSector,
            String priceType,
            String catalogFamily) {

        static TargetProfile from(
                SearchQueryPlan plan, SearchV2InstitutionalSectorRegistry institutionalSectorRegistry) {
            if (plan == null) {
                return new TargetProfile("", "", "", "", "", "", "", "");
            }
            String queryText = normalized(safe(plan.originalQuery()));
            String primaryConceptText = normalized(
                    String.join(" ", plan.primaryConcepts() == null ? List.of() : plan.primaryConcepts()));
            String explicitSector = institutionalSectorRegistry.resolve(plan.originalQuery());
            // An explicit sector in the user's own wording is immutable. Planner concepts
            // may enrich an underspecified query, but must not silently change its entity class.
            String text = normalized(queryText + (explicitSector.isBlank() ? " " + primaryConceptText : ""));
            String measure = "";
            String object = "";
            String instrument = "";
            String industry = "";
            String nominalReal = "";
            String sector = explicitSector;
            String priceType = "";
            String family = "";
            if (containsAny(text, "jadrova inflace", "core inflation", "underlying inflation")) {
                measure = "core_inflation";
                object = "consumer_prices";
                priceType = "consumer";
                family = "macro";
            } else if (containsAny(text, "inflace", "inflation", "hicp", "cpi")) {
                measure = "headline_inflation";
                object = "consumer_prices";
                priceType = "consumer";
                family = "macro";
            }
            if (containsAny(text, "realne mzdy", "real wages", "real earnings")) {
                measure = "real_level";
                object = "wages";
                nominalReal = "real";
                sector = "total_economy";
                family = "macro";
            } else if (containsAny(text, "mzdy", "wages", "earnings")) {
                object = "wages";
                sector = containsAny(text, "government", "public sector", "verejny sektor") ? "government" : "total_economy";
                nominalReal = containsAny(text, "real", "realne") ? "real" : "";
                family = "macro";
            }
            if (containsAny(text, "net profit", "cisty zisk", "bank net profit", "net income", "income statement")) {
                measure = "net_profit";
                object = "bank_profit";
                sector = "banks";
                family = "banking";
            } else if (containsAny(text, "zisk bank", "bank profit", "profit of banks", "bank profitability")) {
                object = "bank_profit";
                sector = "banks";
                family = "banking";
            }
            if (containsAny(text, "roa", "return on assets")) {
                measure = "roa";
                object = "assets";
                if ("banks".equals(explicitSector)) {
                    family = "banking";
                }
            }
            if (containsAny(text, "roe", "return on equity")) {
                measure = "roe";
                object = "equity";
                if ("banks".equals(explicitSector)) {
                    family = "banking";
                }
            }
            if (containsAny(text, "sazby cnb", "policy rate", "repo rate", "discount rate", "lombard rate",
                    "central bank rate", "official interest rate")) {
                measure = "central_bank_policy_rate";
                object = "interest_rate";
                instrument = "interest_rate";
                sector = "central_bank";
                family = "macro";
            }
            if (containsAny(text, "prumyslova vyroba", "industrial production")) {
                measure = "industrial_production_index";
                object = "production";
                industry = "industry";
                family = "sectoral";
            }
            if (containsAny(text, "vyroba automobilu", "automotive production", "motor vehicle", "automobile", "cars")) {
                measure = "industrial_production_index";
                object = "production";
                industry = "automotive_manufacturing";
                family = "sectoral";
            }
            if (containsAny(text, "ceny nemovitosti", "house price", "property price", "real estate prices")) {
                measure = "house_price_index";
                object = "housing";
                priceType = "transaction_price";
                family = "real_estate";
            }
            if (containsAny(text, "cena zlata", "gold price")) {
                measure = "market_price";
                object = "gold";
                priceType = "commodity_market_price";
                family = "commodities";
            }
            if (containsAny(queryText, "akcie", "stock", "share price", "ticker")
                    || containsAny(primaryConceptText, "equity_market_price", "stock_price", "markets_equities")) {
                measure = "market_price";
                object = "equity";
                instrument = "equity";
                family = "markets_equities";
            }
            if (!explicitSector.isBlank()) {
                sector = explicitSector;
            }
            return new TargetProfile(measure, object, instrument, industry, nominalReal, sector, priceType, family);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("measure_type", measureType);
            out.put("economic_object", economicObject);
            out.put("instrument", instrument);
            out.put("industry_sector", industrySector);
            out.put("nominal_real", nominalReal);
            out.put("institutional_sector", institutionalSector);
            out.put("price_type", priceType);
            out.put("catalog_family", catalogFamily);
            return out;
        }

        boolean empty() {
            return measureType.isBlank()
                    && economicObject.isBlank()
                    && instrument.isBlank()
                    && industrySector.isBlank()
                    && nominalReal.isBlank()
                    && institutionalSector.isBlank()
                    && priceType.isBlank()
                    && catalogFamily.isBlank();
        }
    }

    private record CandidateProfile(
            String primaryConcept,
            String measureType,
            String economicObject,
            String instrument,
            String industrySector,
            String nominalReal,
            String institutionalSector,
            String priceType,
            String catalogFamily) {

        static CandidateProfile from(SearchCandidate candidate) {
            String concepts = String.join(" ", candidate.concepts() == null ? List.of() : candidate.concepts());
            String text = normalized(String.join(
                    " ",
                    safe(candidate.title()),
                    safe(candidate.description()),
                    safe(candidate.dataset()),
                    safe(candidate.seriesId()),
                    concepts,
                    String.join(" ", candidate.tags() == null ? List.of() : candidate.tags()),
                    String.join(" ", candidate.categoryPath() == null ? List.of() : candidate.categoryPath())));
            String primary = firstNonBlank(raw(candidate, "primary_concept"), concepts);
            String measure = firstNonBlank(raw(candidate, "measure_type"), measureFromConcept(primary, text));
            String object = firstNonBlank(raw(candidate, "economic_object"), objectFromConcept(primary, text));
            String instrument = firstNonBlank(raw(candidate, "instrument"), instrumentFromConcept(candidate.source(), primary, text));
            String industry = firstNonBlank(raw(candidate, "industry_sector"), industryFromText(primary + " " + text));
            String nominalReal = firstNonBlank(raw(candidate, "nominal_real"), nominalRealFromText(primary + " " + text));
            String sector = firstNonBlank(raw(candidate, "institutional_sector"), sectorFromText(primary + " " + text));
            String priceType = firstNonBlank(raw(candidate, "price_type"), priceTypeFromText(candidate.source(), primary + " " + text));
            String family = firstNonBlank(raw(candidate, "catalog_family"), familyFromText(candidate.source(), primary, measure, object, instrument, industry, text));
            return new CandidateProfile(primary, measure, object, instrument, industry, nominalReal, sector, priceType, family);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("primary_concept", primaryConcept);
            out.put("measure_type", measureType);
            out.put("economic_object", economicObject);
            out.put("instrument", instrument);
            out.put("industry_sector", industrySector);
            out.put("nominal_real", nominalReal);
            out.put("institutional_sector", institutionalSector);
            out.put("price_type", priceType);
            out.put("catalog_family", catalogFamily);
            return out;
        }

        private static String measureFromConcept(String concept, String text) {
            if (containsAny(concept, "core_inflation") || containsAny(text, "core inflation", "jadrova inflace")) {
                return "core_inflation";
            }
            if (containsAny(concept, "return_on_assets") || containsAny(text, "return on assets", " roa ")) {
                return "roa";
            }
            if (containsAny(concept, "return_on_equity") || containsAny(text, "return on equity", " roe ")) {
                return "roe";
            }
            if (containsAny(concept, "bank_net_profit") || containsAny(text, "net profit", "profit or loss")) {
                return "net_profit";
            }
            if (containsAny(concept, "central_bank_policy_rate", "policy_rate")) {
                return "central_bank_policy_rate";
            }
            if (containsAny(concept, "industrial_production", "automotive_production")) {
                return "industrial_production_index";
            }
            if (containsAny(concept, "house_price_index")) {
                return "house_price_index";
            }
            if (containsAny(concept, "equity_market_price", "commodity_spot_price")) {
                return "market_price";
            }
            if (containsAny(concept, "consumer_price_inflation")) {
                return "headline_inflation";
            }
            return "";
        }

        private static String objectFromConcept(String concept, String text) {
            if (containsAny(concept, "central_bank_gold_reserves") || containsAny(text, "official reserve assets gold")) {
                return "central_bank_gold_reserves";
            }
            if (containsAny(concept, "commodity_spot_price") && containsAny(text, "gold")) {
                return "gold";
            }
            if (containsAny(concept, "bank_net_profit")) {
                return "bank_profit";
            }
            if (containsAny(concept, "wages", "real_wages", "average_wages") || containsAny(text, "wages", "mzdy")) {
                return "wages";
            }
            if (containsAny(concept, "equity_market_price")) {
                return "equity";
            }
            return "";
        }

        private static String instrumentFromConcept(String source, String concept, String text) {
            if ("stocks".equals(source)
                    || "yahoo_finance".equals(source)
                    || containsAny(concept, "equity_market_price", "stock_price", "markets_equities")
                    || containsAny(text, "stock price", "share price")) {
                return "equity";
            }
            if (containsAny(text, "loan", "mortgage")) {
                return "loan";
            }
            if (containsAny(text, "interest rate", "policy rate", "repo rate")) {
                return "interest_rate";
            }
            return "";
        }

        private static String industryFromText(String text) {
            if (containsAny(text, "automotive production", "motor vehicle production", "manufacture of motor vehicles",
                    "nace c29", " c29 ")) {
                return "automotive_manufacturing";
            }
            if (containsAny(text, "industry", "industrial", "manufacturing")) {
                return "industry";
            }
            if (containsAny(text, "construction")) {
                return "construction";
            }
            return "";
        }

        private static String nominalRealFromText(String text) {
            if (containsAny(text, "real", "constant prices", "deflated", "inflation adjusted")) {
                return "real";
            }
            if (containsAny(text, "nominal", "current prices")) {
                return "nominal";
            }
            return "";
        }

        private static String sectorFromText(String text) {
            if (containsAny(text, "government", "public sector", "general government")) {
                return "government";
            }
            if (containsAny(text, "total economy", "all economy")) {
                return "total_economy";
            }
            if (containsAny(text, "central bank")) {
                return "central_bank";
            }
            if (containsAny(text, "pension fund", "pension funds", "retirement fund")) {
                return "pension_funds";
            }
            if (containsAny(text,
                    "insurance company",
                    "insurance companies",
                    "insurance corporation",
                    "insurance corporations",
                    "insurer",
                    "insurers")) {
                return "insurance";
            }
            if (containsAny(text, "other financial corporation", "other financial corporations")) {
                return "other_financial_corporations";
            }
            if (containsAny(text, "bank", "banks", "banking", "credit institutions", "significant institutions")) {
                return "banks";
            }
            return "";
        }

        private static String priceTypeFromText(String source, String text) {
            if ("commodities".equals(source) || containsAny(text, "spot price", "commodity market price", "pink sheet")) {
                return "commodity_market_price";
            }
            if (containsAny(text, "consumer price", "hicp", "cpi")) {
                return "consumer";
            }
            return "";
        }

        private static String familyFromText(
                String source, String primary, String measure, String object, String instrument, String industry, String text) {
            if ("commodities".equals(source) || "commodity_market_price".equals(object) || containsAny(primary, "commodity")) {
                return "commodities";
            }
            if ("stocks".equals(source) || "yahoo_finance".equals(source) || "equity".equals(instrument)) {
                return "markets_equities";
            }
            if (containsAny(primary, "bank") || containsAny(text, "bank", "banks")) {
                return "banking";
            }
            if ("house_price_index".equals(measure)) {
                return "real_estate";
            }
            if (!industry.isBlank() || "industrial_production_index".equals(measure)) {
                return "sectoral";
            }
            return "";
        }
    }

    private static String normalized(String value) {
        return CatalogTextUtils.normalizeTokenBoundaries(value == null ? "" : value).toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String buildUserPrompt(SearchQueryPlan plan, List<SearchCandidate> candidates) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", plan.originalQuery());
        payload.put("query_plan", plan.toMap());
        payload.put("candidates", candidates.stream().map(candidate -> compactCandidate(plan, candidate)).toList());
        return objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> compactCandidate(SearchQueryPlan plan, SearchCandidate c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_id", c.seriesId());
        out.put("title", c.title());
        out.put("description", truncate(c.description(), 320));
        out.put("source", c.source());
        out.put("dataset", c.dataset());
        out.put("geo", c.geo());
        out.put("frequency", c.frequency());
        out.put("unit", c.unit());
        out.put("concepts", limit(c.concepts(), 8));
        out.put("tags", limit(c.tags(), 8));
        out.put("category_path", limit(c.categoryPath(), 6));
        out.put("latest_date", c.latestDate());
        out.put("fts_score", c.ftsScore());
        out.put("matched_query", c.matchedQuery());
        out.put("deterministic_evidence", deterministicEvidence(plan, c));
        return out;
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength).trim();
    }

    private static List<String> limit(List<String> values, int maxSize) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).limit(maxSize).toList();
    }

    private Map<String, Object> deterministicEvidence(SearchQueryPlan plan, SearchCandidate candidate) {
        TargetProfile target = TargetProfile.from(plan, institutionalSectorRegistry);
        CandidateProfile candidateProfile = CandidateProfile.from(candidate);
        String candidateText = normalized(String.join(
                " ",
                safe(candidate.title()),
                safe(candidate.description()),
                safe(candidate.dataset()),
                safe(candidate.seriesId()),
                String.join(" ", candidate.concepts() == null ? List.of() : candidate.concepts()),
                String.join(" ", candidate.tags() == null ? List.of() : candidate.tags()),
                String.join(" ", candidate.categoryPath() == null ? List.of() : candidate.categoryPath())));
        List<String> matched = new ArrayList<>();
        List<String> potentialConflicts = new ArrayList<>();
        double compatibilityScore = metadataCompatibilityScore(
                target, candidateProfile, candidateText, matched, potentialConflicts);
        List<String> missingSignals = missingRequiredSignals(plan, candidateText);
        List<String> hardConflicts = potentialConflicts.stream()
                .filter(conflict -> hardSemanticConflict(conflict, target, candidateProfile))
                .distinct()
                .toList();
        List<String> requestedGeo = plan == null || plan.geographies() == null ? List.of() : plan.geographies();
        SearchV2GeoCompatibility.GeoAssessment geoAssessment =
                SearchV2GeoCompatibility.assessCandidateGeo(candidate, requestedGeo, plan);
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("requested", requestedGeo);
        geo.put("candidate_inferred", geoAssessment.candidateInferred());
        geo.put("source_scope", geoAssessment.sourceScope());
        geo.put("status", geoAssessment.status());
        geo.put("hard_conflict", geoAssessment.hardConflict());
        geo.put("dimension_selectable", geoAssessment.dimensionSelectable());
        geo.put("compatible_or_dimension_selectable", "compatible".equals(geoAssessment.status()));

        Map<String, Object> exactEntity = new LinkedHashMap<>();
        exactEntity.put("requested", plan != null && plan.highConfidenceExactEntity());
        exactEntity.put(
                "match_score",
                plan == null ? 0.0 : exactEntityScorer.exactScore(plan.entityResolution(), candidate));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("authority", "advisory_semantic_evidence");
        evidence.put("target_profile", target.toMap());
        evidence.put("candidate_profile", candidateProfile.toMap());
        evidence.put("metadata_compatibility_score", clamp(compatibilityScore));
        evidence.put("matched_constraints", matched.stream().distinct().toList());
        evidence.put("potential_conflicts", potentialConflicts.stream().distinct().toList());
        evidence.put("high_confidence_conflicts", hardConflicts);
        evidence.put("missing_required_signals", missingSignals);
        evidence.put("geo", geo);
        evidence.put("exact_entity", exactEntity);
        return evidence;
    }

    private static List<SemanticDecision> parseDecisions(JsonNode json, List<SearchCandidate> candidates) {
        Set<String> allowedIds = new HashSet<>();
        for (SearchCandidate candidate : candidates) {
            allowedIds.add(candidate.seriesId());
        }
        JsonNode array = json.path("decisions");
        if (!array.isArray()) {
            return List.of();
        }
        List<SemanticDecision> out = new ArrayList<>();
        for (JsonNode node : array) {
            String seriesId = node.path("series_id").asText("");
            if (!allowedIds.contains(seriesId)) {
                continue;
            }
            String decision = normalizeDecision(node.path("decision").asText("drop"));
            out.add(new SemanticDecision(
                    seriesId,
                    decision,
                    clamp(node.path("relevance_score").asDouble(0.0)),
                    clamp(node.path("confidence").asDouble(0.0)),
                    stringArray(node.path("matched_user_need")),
                    stringArray(node.path("semantic_conflicts")),
                    node.path("reason").asText(""),
                    normalizeRole(node.path("result_role").asText(""))));
        }
        return out;
    }

    private static String normalizeDecision(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if ("keep".equals(value) || "supporting".equals(value) || "drop".equals(value)) {
            return value;
        }
        return "drop";
    }

    private static String normalizeRole(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if (List.of("primary", "context", "comparison", "driver", "reject").contains(value)) {
            return value;
        }
        return "reject";
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        });
        return out;
    }

    private static Map<String, Object> batchStat(
            int index, int candidateCount, int decisionCount, long latencyMs, boolean ok, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", index);
        out.put("candidate_count", candidateCount);
        out.put("decision_count", decisionCount);
        out.put("latency_ms", latencyMs);
        out.put("ok", ok);
        if (error != null) {
            out.put("error", error);
        }
        return out;
    }

    private static String loadPrompt() {
        try (InputStream in = SearchV2SemanticValidator.class.getResourceAsStream("/search_v2/reranker_prompt.md")) {
            if (in == null) {
                return "Return semantic decisions JSON.";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "Return semantic decisions JSON.";
        }
    }

    /** Stable SHA-256 hex digest of the exact prompt text currently loaded. See {@link #RERANKER_PROMPT_VERSION}. */
    public static String promptContentHash() {
        return sha256Hex(PROMPT);
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return "";
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
