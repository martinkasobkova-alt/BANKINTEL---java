package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the JSON data migration: {@link CatalogSearchSynonyms}, {@link
 * CatalogLikelySources}, {@link CatalogQueryIntent} and {@link CatalogGeoIntent} must stay thin
 * loaders over {@code src/main/resources/catalog/*.json} — the large synonym/lexicon/rule/geo
 * dictionaries must never be re-inlined as Java literals.
 */
class CatalogDataMigrationNoHardcodesTest {

    @Test
    void searchSynonymsSourceContainsNoInlineMapOfEntries() throws IOException {
        String source = readSource("cz/bankintel/search/CatalogSearchSynonyms.java");
        assertFalse(
                source.contains("Map.ofEntries("),
                "CatalogSearchSynonyms must not hardcode synonym maps as Map.ofEntries — "
                        + "use catalog/search_synonyms.json via the loader instead");
        assertFalse(
                source.contains("BANKING_GENERAL_TRIGGERS = List.of("),
                "CatalogSearchSynonyms must not hardcode banking trigger word lists — "
                        + "use catalog/search_synonyms.json (banking_triggers) instead");
    }

    @Test
    void searchSynonymsLoadsNonEmptyFromJson() {
        assertFalse(
                CatalogSearchSynonyms.expandTerms("zisk bank", 20).isEmpty(),
                "search_synonyms.json must resolve to a non-empty expansion for a known key");
        assertTrue(
                CatalogSearchSynonyms.detectBankingExpansionGroups("bank kapital").contains("banking_capital"),
                "banking_triggers from search_synonyms.json must be loaded");
    }

    @Test
    void likelySourcesSourceContainsNoInlineRuleLiterals() throws IOException {
        String source = readSource("cz/bankintel/search/CatalogLikelySources.java");
        assertFalse(
                source.contains("new Rule(\n"),
                "CatalogLikelySources must not hardcode per-query Rule(...) literals inline — "
                        + "use catalog/likely_sources_rules.json via the loader instead");
        assertFalse(
                source.contains("\"ropa\", \"ropy\", \"ropu\", \"cena ropy\""),
                "CatalogLikelySources must not hardcode LIKELY_RULES match-term literals — "
                        + "use catalog/likely_sources_rules.json via the loader instead");
    }

    @Test
    void likelySourcesRulesLoadedNonEmpty() {
        assertFalse(CatalogLikelySources.LIKELY_RULES.isEmpty(), "likely_sources_rules.json must not be empty");
    }

    @Test
    void queryIntentSourceContainsNoInlineLexiconLiterals() throws IOException {
        String source = readSource("cz/bankintel/search/CatalogQueryIntent.java");
        assertFalse(
                source.contains("Map.ofEntries("),
                "CatalogQueryIntent must not hardcode METRIC_LEXICON/DOMAIN_LEXICON as Map.ofEntries — "
                        + "use catalog/intent_lexicon.json via the loader instead");
    }

    @Test
    void queryIntentLexiconLoadedNonEmpty() {
        var intent = CatalogQueryIntent.classifyQueryIntent("zisk bank");
        assertFalse(intent.metricTerms().isEmpty(), "intent_lexicon.json metric_lexicon must classify 'zisk'");
        assertFalse(intent.domainTerms().isEmpty(), "intent_lexicon.json domain_lexicon must classify 'bank'");
    }

    @Test
    void geoIntentSourceContainsNoInlineIso2LiteralSet() throws IOException {
        String source = readSource("cz/bankintel/search/CatalogGeoIntent.java");
        assertFalse(
                source.contains("\"BY\", \"BE\", \"BA\", \"BG\""),
                "CatalogGeoIntent must not hardcode the European ISO2 code list as a Set.of(...) literal — "
                        + "use catalog/geo_scopes.json via the loader instead");
        assertFalse(
                source.contains("List.of(\n            \"eu\", \"eu27\""),
                "CatalogGeoIntent must not hardcode EU_AGGREGATE_TERMS as a List.of(...) literal — "
                        + "use catalog/geo_scopes.json via the loader instead");
    }

    @Test
    void geoScopesLoadedNonEmpty() {
        assertFalse(CatalogGeoIntent.EUROPEAN_COUNTRY_CODES.isEmpty(), "geo_scopes.json european_iso2 must not be empty");
        assertTrue(CatalogGeoIntent.EUROPEAN_COUNTRY_CODES.contains("CZ"));
        var geo = CatalogGeoIntent.detectGeoIntent("evropska unie");
        assertTrue(
                "eu_aggregate".equals(geo.get("type")),
                "geo_scopes.json eu_aggregate_terms must be loaded, got: " + geo);
        var global = CatalogGeoIntent.detectGeoIntent("global economy world");
        assertTrue(
                "global_aggregate".equals(global.get("type")),
                "geo_scopes.json global_aggregate_terms must be loaded, got: " + global);
    }

    private static String readSource(String relativeJavaPath) throws IOException {
        Path path = Path.of("src/main/java/" + relativeJavaPath);
        assertTrue(Files.isRegularFile(path), "expected to find " + path.toAbsolutePath());
        return Files.readString(path);
    }
}
