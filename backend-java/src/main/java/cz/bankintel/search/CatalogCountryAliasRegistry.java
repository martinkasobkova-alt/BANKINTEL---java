package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * World country alias registry — loads {@code catalog/world_country_aliases.json} at startup.
 * Port of merged {@code WORLD_COUNTRY_ALIASES + COUNTRY_ALIASES} from Python {@code catalog_geo_intent.py}.
 */
public final class CatalogCountryAliasRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, List<String>> ALIASES_BY_CODE = loadAliases();
    private static final AliasTextIndex TITLE_ALIAS_INDEX = buildTitleAliasIndex();
    // Perf fix (geo-detection regex-compilation storm): both regexes below depend only on the alias
    // string, never on the query text being matched - see CompiledCountryAlias javadoc. Compiled once
    // here at class-init instead of once per CatalogGeoIntent.detectedCountryCodes() call (which used to
    // mean once per candidate, per request). Order matches ALIASES_BY_CODE's iteration order exactly
    // (country-by-country, then alias-by-alias within country) so callers see identical match order.
    // Perf fix: test-only, incremented only by compileAliasPattern() below - MUST be initialized
    // before COMPILED_ALIASES (static field init runs in textual order; compileAliases() reads it).
    private static final java.util.concurrent.atomic.AtomicLong COMPILED_PATTERN_COUNT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final List<CompiledCountryAlias> COMPILED_ALIASES = compileAliases();

    private CatalogCountryAliasRegistry() {}

    public static Map<String, List<String>> aliasesByCode() {
        return ALIASES_BY_CODE;
    }

    /**
     * Precompiled, immutable alias index - one entry per (country, alias) pair, in the exact order
     * {@code aliasesByCode().entrySet()} iterates today. Safe for unbounded concurrent reads (built
     * once at class-init, never mutated afterward); not a cache (fixed size = total alias count, no
     * growth at runtime).
     */
    public static List<CompiledCountryAlias> compiledAliases() {
        return COMPILED_ALIASES;
    }

    /** Test-only: total Pattern objects compiled for the country-alias index (expected: fixed, set once at class-init). */
    public static long compiledPatternCountForTest() {
        return COMPILED_PATTERN_COUNT.get();
    }

    /**
     * One (country, alias) pair with both regexes {@code CatalogGeoIntent.detectedCountryCodes()}
     * historically built fresh on every call, precompiled once:
     * <ul>
     *   <li>{@code foldedPattern} - mirrors the old {@code CatalogGeoIntent.matchAlias(query, foldCs(alias))}
     *       call: built from the ASCII-folded/lowercased alias (matches a folded query text).
     *   <li>{@code rawPattern} - mirrors the old {@code CatalogCountryAliasRegistry.matchAlias(query, alias)}
     *       call: built from the alias as stored in the registry (only {@code .strip()}ped, NOT folded).
     *       Kept byte-for-byte equivalent to the original even though it looks redundant with
     *       {@code foldedPattern} for already-lowercase-ASCII aliases, because some aliases are not -
     *       collapsing the two without an equivalence test was explicitly out of scope for this change.
     * </ul>
     * Both patterns are {@code null} only if the alias was blank (never actually stored, but guarded
     * defensively). Neither pattern depends on the query text - only {@code Matcher.find()} does.
     *
     * <p>{@code prefilterSubstring} is a cheap, provably-safe gate for both patterns: every shape either
     * pattern can take - {@code (?:^|[^a-z0-9])LITERAL(?:[^a-z0-9]|$)} or, for a wildcard/stem alias,
     * {@code (?:^|[^a-z0-9])STEM[a-z0-9]*} - requires the literal (folded) alias/stem text to appear
     * verbatim in the query; a regex match is therefore never possible if a plain substring search for
     * that text fails, so {@code normalizedQuery.contains(prefilterSubstring)} can only ever produce
     * false positives (harmless - the real pattern still runs and correctly rejects them), never false
     * negatives. This holds for {@code rawPattern} too: {@code normalizedQuery} is always fully
     * ASCII-folded/lowercased, so a raw (unfolded) alias containing any uppercase or diacritic character
     * can never match it regardless of prefiltering; when the raw alias happens to already be
     * lowercase-ASCII it is - modulo whitespace collapse - the same text as {@code foldedAlias}, so the
     * same substring check still safely bounds it.
     */
    public record CompiledCountryAlias(
            String countryCode,
            String originalAlias,
            boolean wildcard,
            String prefilterSubstring,
            Pattern foldedPattern,
            Pattern rawPattern) {}

    private static List<CompiledCountryAlias> compileAliases() {
        List<CompiledCountryAlias> out = new java.util.ArrayList<>();
        for (Map.Entry<String, List<String>> entry : ALIASES_BY_CODE.entrySet()) {
            for (String alias : entry.getValue()) {
                String foldedAlias = CatalogSearchSynonyms.foldCs(alias);
                if (foldedAlias.isEmpty()) {
                    continue;
                }
                Pattern foldedPattern = compileAliasPattern(foldedAlias);
                Pattern rawPattern = compileAliasPattern(alias == null ? "" : alias.strip());
                boolean wildcard = foldedAlias.endsWith("*") && foldedAlias.length() > 2;
                String prefilterSubstring = wildcard ? foldedAlias.substring(0, foldedAlias.length() - 1) : foldedAlias;
                out.add(new CompiledCountryAlias(
                        entry.getKey(), alias, wildcard, prefilterSubstring, foldedPattern, rawPattern));
            }
        }
        return List.copyOf(out);
    }

    /** Exact regex shape as the original per-call {@code matchAlias(...)} - see its javadoc/callers. */
    private static Pattern compileAliasPattern(String alias) {
        String a = alias == null ? "" : alias.strip();
        if (a.isEmpty()) {
            return null;
        }
        COMPILED_PATTERN_COUNT.incrementAndGet();
        if (a.endsWith("*") && a.length() > 2) {
            String stem = Pattern.quote(a.substring(0, a.length() - 1));
            return Pattern.compile("(?:^|[^a-z0-9])" + stem + "[a-z0-9]*");
        }
        return Pattern.compile("(?:^|[^a-z0-9])" + Pattern.quote(a) + "(?:[^a-z0-9]|$)");
    }

    public static List<String> aliasesFor(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return List.of();
        }
        return ALIASES_BY_CODE.getOrDefault(countryCode.strip().toUpperCase(Locale.ROOT), List.of());
    }

    public static boolean hasCode(String countryCode) {
        return countryCode != null
                && !countryCode.isBlank()
                && ALIASES_BY_CODE.containsKey(countryCode.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * Finds an explicit country name embedded in a display title.
     *
     * <p>The index is built once and title matching is reduced to bounded n-gram lookups. Short two-character
     * aliases are intentionally excluded because values such as {@code AT} and {@code IN} are ordinary words in
     * English titles; structured ISO fields remain the authoritative place for those codes.
     */
    public static Optional<String> countryCodeInTitle(String title) {
        String normalized = CatalogTextUtils.normalizeTokenBoundaries(title);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        String[] tokens = normalized.split(" ");
        for (int width = Math.min(TITLE_ALIAS_INDEX.maxTokens(), tokens.length); width >= 1; width--) {
            Map<String, String> aliases = TITLE_ALIAS_INDEX.aliasesByTokenCount().get(width);
            if (aliases == null || aliases.isEmpty()) {
                continue;
            }
            for (int start = 0; start + width <= tokens.length; start++) {
                String phrase = String.join(" ", java.util.Arrays.copyOfRange(tokens, start, start + width));
                String countryCode = aliases.get(phrase);
                if (countryCode != null) {
                    return Optional.of(countryCode);
                }
            }
        }
        return Optional.empty();
    }

    /** Match alias against normalized query — supports wildcard suffix {@code *}. */
    public static boolean matchAlias(String normalizedQuery, String alias) {
        String a = alias == null ? "" : alias.strip();
        if (a.isEmpty()) {
            return false;
        }
        if (a.endsWith("*") && a.length() > 2) {
            String stem = Pattern.quote(a.substring(0, a.length() - 1));
            return Pattern.compile("(?:^|[^a-z0-9])" + stem + "[a-z0-9]*").matcher(normalizedQuery).find();
        }
        return Pattern.compile("(?:^|[^a-z0-9])" + Pattern.quote(a) + "(?:[^a-z0-9]|$)")
                .matcher(normalizedQuery)
                .find();
    }

    /** Folded alias terms for a country code (excludes wildcard-only entries for display-like token lists). */
    public static List<String> foldedAliasTerms(String countryCode) {
        return foldedAliasTerms(countryCode, false);
    }

    /**
     * Folded country terms used for matching query tokens.
     *
     * <p>Wildcard aliases in the registry are stems such as {@code madarsk*}. For search semantics those stems
     * must still count as geo terms, otherwise a query like "inflace madarsko" is interpreted as
     * metric + unrelated domain and a verified HU preview gets demoted after it was correctly found.
     */
    public static List<String> foldedAliasMatchTerms(String countryCode) {
        return foldedAliasTerms(countryCode, true);
    }

    private static List<String> foldedAliasTerms(String countryCode, boolean includeWildcardStems) {
        List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String cc = countryCode == null ? "" : countryCode.strip().toUpperCase(Locale.ROOT);
        if (!cc.isEmpty() && seen.add(cc.toLowerCase(Locale.ROOT))) {
            out.add(cc.toLowerCase(Locale.ROOT));
        }
        for (String alias : aliasesFor(cc)) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            String cleanAlias = alias.strip();
            if (cleanAlias.endsWith("*")) {
                if (!includeWildcardStems) {
                    continue;
                }
                cleanAlias = cleanAlias.substring(0, cleanAlias.length() - 1);
            }
            String folded = CatalogTextUtils.foldAscii(cleanAlias);
            if (folded.length() >= 2 && seen.add(folded)) {
                out.add(folded);
            }
        }
        return out;
    }

    private static Map<String, List<String>> loadAliases() {
        try (InputStream in = CatalogCountryAliasRegistry.class.getResourceAsStream("/catalog/world_country_aliases.json")) {
            if (in == null) {
                return fallbackAliases();
            }
            Map<String, List<String>> raw =
                    MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, List<String>>>() {});
            Map<String, List<String>> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
                String code = entry.getKey() == null ? "" : entry.getKey().strip().toUpperCase(Locale.ROOT);
                if (!code.isEmpty()) {
                    normalized.put(code, entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
                }
            }
            normalized.computeIfPresent("CZ", (code, aliases) -> withAdditionalAliases(aliases, List.of("czech", "czech*")));
            normalized.putIfAbsent("U2", List.of(
                    "euro area",
                    "eurozone",
                    "euro zona",
                    "eurozona",
                    "eurozóny",
                    "eurozony",
                    "eurozóně",
                    "eurozone aggregate"));
            return Collections.unmodifiableMap(normalized);
        } catch (Exception ex) {
            return fallbackAliases();
        }
    }

    private static Map<String, List<String>> fallbackAliases() {
        Map<String, List<String>> fallback = new LinkedHashMap<>();
        fallback.put("CZ", List.of("cr", "cz", "cesko", "czech", "czechia", "czech republic"));
        fallback.put("FR", List.of("franci*", "france", "french"));
        fallback.put("IT", List.of("italie", "italii", "ital*", "italy", "italian"));
        fallback.put("DE", List.of("nemecko", "germany", "german"));
        fallback.put("GB", List.of("uk", "united kingdom", "britain", "england"));
        fallback.put("US", List.of("usa", "united states", "amerika"));
        fallback.put("U2", List.of("euro area", "eurozone", "euro zona", "eurozona", "eurozony"));
        return Collections.unmodifiableMap(fallback);
    }

    private static List<String> withAdditionalAliases(List<String> aliases, List<String> additions) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(aliases == null ? List.of() : aliases);
        out.addAll(additions == null ? List.of() : additions);
        return List.copyOf(out);
    }

    private static AliasTextIndex buildTitleAliasIndex() {
        Map<Integer, Map<String, String>> aliasesByTokenCount = new HashMap<>();
        int maxTokens = 1;
        for (Map.Entry<String, List<String>> entry : ALIASES_BY_CODE.entrySet()) {
            for (String rawAlias : entry.getValue()) {
                if (rawAlias == null || rawAlias.isBlank() || rawAlias.endsWith("*")) {
                    continue;
                }
                String alias = CatalogTextUtils.normalizeTokenBoundaries(rawAlias);
                if (alias.length() < 3) {
                    continue;
                }
                int tokenCount = alias.split(" ").length;
                maxTokens = Math.max(maxTokens, tokenCount);
                aliasesByTokenCount
                        .computeIfAbsent(tokenCount, ignored -> new HashMap<>())
                        .putIfAbsent(alias, entry.getKey());
            }
        }
        Map<Integer, Map<String, String>> immutable = new HashMap<>();
        aliasesByTokenCount.forEach((width, aliases) -> immutable.put(width, Map.copyOf(aliases)));
        return new AliasTextIndex(Map.copyOf(immutable), maxTokens);
    }

    private record AliasTextIndex(Map<Integer, Map<String, String>> aliasesByTokenCount, int maxTokens) {}
}
