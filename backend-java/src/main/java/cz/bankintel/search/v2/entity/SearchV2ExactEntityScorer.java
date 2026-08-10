package cz.bankintel.search.v2.entity;

import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SearchV2ExactEntityScorer {

    public double exactScore(ExactEntityResolution resolution, SearchCandidate candidate) {
        if (resolution == null || candidate == null || !"exact_entity".equals(resolution.resolutionType())) {
            return 0.0;
        }
        String seriesId = safe(candidate.seriesId());
        String dataset = safe(candidate.dataset());
        String identifierText = normalized(String.join(" ", seriesId, dataset));
        String candidateText = normalized(String.join(
                " ",
                seriesId,
                dataset,
                safe(candidate.title()),
                safe(candidate.description()),
                raw(candidate, "canonical_title_en"),
                raw(candidate, "canonical_title_cs"),
                raw(candidate, "original_title"),
                raw(candidate, "aliases_en"),
                raw(candidate, "aliases_cs"),
                raw(candidate, "abbreviations")));
        double best = 0.0;
        for (String symbol : resolution.symbols() == null ? List.<String>of() : resolution.symbols()) {
            if (sameIdentifier(seriesId, symbol) || sameIdentifier(dataset, symbol)) {
                best = Math.max(best, 1.0);
            } else if (contains(identifierText, symbol)) {
                best = Math.max(best, 0.93);
            } else if (contains(candidateText, symbol)) {
                best = Math.max(best, 0.88);
            }
        }
        for (String exact : resolution.exactTerms() == null ? List.<String>of() : resolution.exactTerms()) {
            if (contains(candidateText, exact)) {
                best = Math.max(best, 0.94);
            }
        }
        if (contains(candidateText, resolution.canonicalName())) {
            best = Math.max(best, 0.96);
        }
        for (String alias : resolution.aliases() == null ? List.<String>of() : resolution.aliases()) {
            if (contains(candidateText, alias)) {
                best = Math.max(best, 0.90);
            }
        }
        if (resolution.catalogFamily() != null && !resolution.catalogFamily().isBlank()) {
            String rawFamily = raw(candidate, "catalog_family");
            if (resolution.catalogFamily().equalsIgnoreCase(rawFamily)) {
                best += 0.03;
            }
        }
        best = applyMeasureCompatibility(resolution, candidate, best, candidateText);
        best = applyRequestedReturnType(resolution, candidate, best);
        return Math.min(1.0, best);
    }

    private static double applyMeasureCompatibility(
            ExactEntityResolution resolution, SearchCandidate candidate, double score, String candidateText) {
        if (!"commodity".equalsIgnoreCase(resolution.entityType())
                || !"market_price".equalsIgnoreCase(attribute(resolution, "measure_type"))) {
            return score;
        }
        String family = normalized(raw(candidate, "catalog_family"));
        boolean marketOrEquityProxy = family.contains("markets")
                || family.contains("equities")
                || contains(candidateText, "stock index")
                || contains(candidateText, "equity index")
                || contains(candidateText, "industry index")
                || contains(candidateText, "sector index");
        return marketOrEquityProxy ? Math.min(score, 0.46) : score;
    }

    private static double applyRequestedReturnType(
            ExactEntityResolution resolution, SearchCandidate candidate, double score) {
        String requested = attribute(resolution, "requested_return_type");
        if (requested.isBlank() || !"market_index".equalsIgnoreCase(resolution.entityType())) {
            return score;
        }
        String primaryText = primaryCandidateText(candidate);
        String candidateType = candidateReturnType(candidate, primaryText);
        boolean primaryContainsCanonical = contains(primaryText, resolution.canonicalName());
        if (requested.equals(candidateType)) {
            if (!primaryContainsCanonical) {
                return Math.min(Math.max(score, 0.90), 0.93);
            }
            if (hasUnrequestedReturnVariant(primaryText)) {
                return Math.min(Math.max(score, 0.94), 0.96);
            }
            return 1.0;
        }
        if ("total_return".equals(requested) && "net_total_return".equals(candidateType)) {
            return primaryContainsCanonical ? Math.max(score, 0.97) : Math.min(Math.max(score, 0.90), 0.93);
        }
        if ("net_total_return".equals(requested) && "total_return".equals(candidateType)) {
            return primaryContainsCanonical ? Math.max(score, 0.95) : Math.min(Math.max(score, 0.90), 0.93);
        }
        return Math.min(score, 0.78);
    }

    private static String candidateReturnType(SearchCandidate candidate, String candidateText) {
        String rawType = normalized(raw(candidate, "return_type"));
        if (rawType.contains("net") && rawType.contains("total")) {
            return "net_total_return";
        }
        if (rawType.contains("total")) {
            return "total_return";
        }
        if (contains(candidateText, "net total return")) {
            return "net_total_return";
        }
        if (contains(candidateText, "total return") || contains(candidateText, "gross return")) {
            return "total_return";
        }
        return "price_index";
    }

    private static String primaryCandidateText(SearchCandidate candidate) {
        return normalized(String.join(
                " ",
                safe(candidate.seriesId()),
                safe(candidate.dataset()),
                safe(candidate.title()),
                raw(candidate, "canonical_title_en"),
                raw(candidate, "canonical_title_cs"),
                raw(candidate, "original_title"),
                raw(candidate, "return_type")));
    }

    private static boolean hasUnrequestedReturnVariant(String primaryText) {
        return containsAny(primaryText, Set.of(
                "esg",
                "environmental",
                "social",
                "governance",
                "currency hedged",
                "hedged",
                "decrement",
                "settle",
                "settlement",
                "opening",
                "aud",
                "cad",
                "chf",
                "eur",
                "gbp",
                "hkd",
                "inr",
                "jpy",
                "nzd"));
    }

    private static boolean containsAny(String haystack, Set<String> needles) {
        for (String needle : needles) {
            if (contains(haystack, needle)) {
                return true;
            }
        }
        return false;
    }

    private static String attribute(ExactEntityResolution resolution, String key) {
        if (resolution == null || resolution.attributes() == null) {
            return "";
        }
        return CatalogMapSupport.str(resolution.attributes().get(key));
    }

    private static boolean contains(String haystack, String needle) {
        String normalizedNeedle = normalized(needle);
        return !normalizedNeedle.isBlank() && (" " + haystack + " ").contains(" " + normalizedNeedle + " ");
    }

    private static boolean sameIdentifier(String candidateIdentifier, String symbol) {
        String left = normalized(candidateIdentifier);
        String right = normalized(symbol);
        return !left.isBlank() && left.equals(right);
    }

    private static String raw(SearchCandidate candidate, String key) {
        if (candidate == null || candidate.raw() == null) {
            return "";
        }
        Object value = candidate.raw().get(key);
        if (value instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                String text = CatalogMapSupport.str(item);
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return String.join(" ", out);
        }
        return CatalogMapSupport.str(value);
    }

    private static String normalized(String value) {
        return CatalogTextUtils.normalizeTokenBoundaries(value == null ? "" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

}
