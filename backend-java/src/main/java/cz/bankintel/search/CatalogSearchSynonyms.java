package cz.bankintel.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Czech search synonyms and query expansion — port of
 * {@code Bankoapp-main/backend/services/catalog_search_synonyms.py} (Wave 2 subset).
 *
 * <p>Data-driven: synonym maps, banking synonym groups and trigger word lists are loaded from
 * {@code catalog/search_synonyms.json} at startup (same pattern as {@link CatalogQueryIntent}'s
 * {@code intent_groups.json}), so this class stays a thin loader + expansion algorithm.
 */
public final class CatalogSearchSynonyms {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SynonymData DATA = loadSynonymData();

    private CatalogSearchSynonyms() {}

    /**
     * Port of {@code fold_cs} in catalog_search_synonyms.py — diacritics-fold + whitespace
     * collapse. Delegates the actual Unicode fold to {@link CatalogTextUtils#foldAscii(String)}
     * (single source of truth for ASCII-folding; NFD vs. NFKD is equivalent for the plain Latin
     * diacritics used here — see {@code CatalogTextUtilsFoldingTest}) and only adds the
     * whitespace-collapsing behavior specific to this port.
     */
    public static String foldCs(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return CatalogTextUtils.foldAscii(text).replaceAll("\\s+", " ").trim();
    }

    /** Port of {@code expand_terms}. */
    public static List<String> expandTerms(String text, int maxExtra) {
        String raw = text == null ? "" : text.strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(raw);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(foldCs(raw));

        Set<String> bankingGroups = detectBankingExpansionGroups(raw);
        if (!bankingGroups.isEmpty()) {
            List<String> sortedGroups = new ArrayList<>(bankingGroups);
            sortedGroups.sort(String::compareTo);
            for (String groupName : sortedGroups) {
                for (String term : DATA.bankingSynonymGroups().getOrDefault(groupName, List.of())) {
                    String t = term.strip();
                    String ft = foldCs(t);
                    if (!ft.isEmpty() && seen.add(ft)) {
                        out.add(t);
                    }
                    if (out.size() >= maxExtra + 1) {
                        return out;
                    }
                }
            }
        }

        for (String key : synonymKeysForText(raw)) {
            for (String term : DATA.czechSearchSynonyms().getOrDefault(key, List.of())) {
                String t = term.strip();
                String ft = foldCs(t);
                if (!ft.isEmpty() && seen.add(ft)) {
                    out.add(t);
                }
                if (out.size() >= maxExtra + 1) {
                    return out;
                }
            }
        }
        return out;
    }

    public static List<String> expandTerms(String text) {
        return expandTerms(text, 12);
    }

    /** Port of {@code expand_search_queries}. */
    public static List<String> expandSearchQueries(String query, int maxQueries) {
        List<String> terms = expandTerms(query, 10);
        List<String> queries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : terms) {
            String ft = foldCs(t);
            if (!ft.isEmpty() && seen.add(ft)) {
                queries.add(t);
            }
            if (queries.size() >= maxQueries) {
                break;
            }
        }
        if (terms.size() > 2 && queries.size() < maxQueries) {
            String combo = String.join(" ", terms.subList(1, Math.min(4, terms.size())));
            if (seen.add(foldCs(combo))) {
                queries.add(combo);
            }
        }
        if (queries.size() > maxQueries) {
            return queries.subList(0, maxQueries);
        }
        return queries;
    }

    public static List<String> expandSearchQueries(String query) {
        return expandSearchQueries(query, 6);
    }

    static Set<String> detectBankingExpansionGroups(String text) {
        String n = foldCs(text);
        if (n.isEmpty()) {
            return Set.of();
        }
        String padded = " " + n + " ";
        boolean hasGeneral = blobHasAny(n, DATA.bankingTriggers().getOrDefault("banking_general", List.of()))
                || padded.contains(" bank ")
                || n.endsWith(" bank");
        boolean hasProfit = blobHasAny(n, DATA.bankingTriggers().getOrDefault("banking_profitability", List.of()));
        boolean hasBalance = blobHasAny(n, DATA.bankingTriggers().getOrDefault("banking_balance", List.of()));
        boolean hasCapital = blobHasAny(n, DATA.bankingTriggers().getOrDefault("banking_capital", List.of()));
        boolean hasLending = blobHasAny(n, DATA.bankingTriggers().getOrDefault("banking_lending", List.of()));
        boolean hasRates = blobHasAny(n, DATA.bankingTriggers().getOrDefault("banking_rates", List.of()));

        if (!hasGeneral && !hasProfit && !hasBalance && !hasCapital && !hasLending && !hasRates) {
            return Set.of();
        }

        Set<String> groups = new LinkedHashSet<>();
        if (hasProfit) {
            groups.add("banking_profitability");
        }
        if (hasBalance) {
            groups.add("banking_balance");
        }
        if (hasCapital) {
            groups.add("banking_capital");
        }
        if (hasLending) {
            groups.add("banking_lending");
        }
        if (hasRates) {
            groups.add("banking_rates");
        }
        if (hasGeneral || !groups.isEmpty()) {
            groups.add("banking_general");
        }
        if (hasGeneral && !hasProfit && !hasBalance && !hasCapital && !hasLending && !hasRates) {
            return Set.of("banking_general");
        }
        return groups;
    }

    private static List<String> synonymKeysForText(String text) {
        String needle = foldCs(text);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(DATA.czechSearchSynonyms().keySet());
        keys.sort(ComparatorByLengthDesc.INSTANCE);
        List<String> matched = new ArrayList<>();
        for (String key : keys) {
            if (DATA.bankingFlatKeys().contains(key)) {
                continue;
            }
            if (CatalogTextUtils.containsTokenOrPhrase(needle, key)) {
                matched.add(key);
            }
        }
        return matched;
    }

    private static boolean blobHasAny(String blob, List<String> triggers) {
        String b = " " + blob + " ";
        for (String t : triggers) {
            String tf = foldCs(t);
            if (tf.isEmpty()) {
                continue;
            }
            if (CatalogTextUtils.containsTokenOrPhrase(b, tf)) {
                return true;
            }
        }
        return false;
    }

    private enum ComparatorByLengthDesc implements java.util.Comparator<String> {
        INSTANCE;

        @Override
        public int compare(String a, String b) {
            return Integer.compare(b.length(), a.length());
        }
    }

    /** Deserialized shape of {@code catalog/search_synonyms.json}. */
    private record SynonymData(
            Map<String, List<String>> czechSearchSynonyms,
            Map<String, List<String>> bankingSynonymGroups,
            Set<String> bankingFlatKeys,
            Map<String, List<String>> bankingTriggers) {

        static SynonymData empty() {
            return new SynonymData(Map.of(), Map.of(), Set.of(), Map.of());
        }
    }

    private static SynonymData loadSynonymData() {
        try (InputStream in = CatalogSearchSynonyms.class.getResourceAsStream("/catalog/search_synonyms.json")) {
            if (in == null) {
                return SynonymData.empty();
            }
            JsonNode root = MAPPER.readTree(in);
            Map<String, List<String>> czechSynonyms =
                    readStringListMap(root.path("czech_search_synonyms"));
            Map<String, List<String>> bankingGroups =
                    readStringListMap(root.path("banking_synonym_groups"));
            Map<String, List<String>> bankingTriggers =
                    readStringListMap(root.path("banking_triggers"));
            List<String> flatKeysList = root.path("banking_flat_keys").isArray()
                    ? MAPPER.convertValue(root.path("banking_flat_keys"), new TypeReference<List<String>>() {})
                    : List.of();
            Set<String> bankingFlatKeys = Set.copyOf(flatKeysList);
            return new SynonymData(czechSynonyms, bankingGroups, bankingFlatKeys, bankingTriggers);
        } catch (Exception ex) {
            return SynonymData.empty();
        }
    }

    private static Map<String, List<String>> readStringListMap(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> raw =
                MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, List<String>>>() {});
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(normalized);
    }
}
