package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression guard (kolo 6): {@link CatalogCompositeScorer} must stay dataset/country-code
 * agnostic. Ranking signals must come from generic geo/intent/recency data (JSON registries,
 * {@link CatalogGeoIntent}, {@link CatalogQueryIntent}), never per-dataset-code or per-country
 * literals baked into the scorer itself — those don't generalize and were removed in kolo 6.
 */
class CatalogCompositeScorerNoHardcodesTest {

    /** Dataset-code prefixes that were hardcoded pre-kolo-6 (teaching-to-the-test). */
    private static final List<String> BANNED_DATASET_CODE_LITERALS =
            List.of("teicp", "tips", "prc_hicp", "une_rt", "une_lfu");

    /** Country ISO3 literals that were hardcoded in a 7-country foreign-marker whitelist. */
    private static final List<String> BANNED_COUNTRY_ISO3_LITERALS =
            List.of("\"MEX\"", "\"AUS\"", "\"USA\"", "\"JPN\"", "\"CHN\"", "\"BRA\"", "\"IND\"", "\"SVK\"", "\"CZE\"");

    @Test
    void scorerSourceContainsNoDatasetCodeLiterals() throws IOException {
        String source = readScorerSource();
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        for (String literal : BANNED_DATASET_CODE_LITERALS) {
            assertFalse(
                    lower.contains("\"" + literal),
                    "CatalogCompositeScorer must not hardcode dataset-code literal: " + literal
                            + " (correct datasets must be chosen via intent + geo coverage + AI planner)");
        }
    }

    @Test
    void scorerSourceContainsNoCountryIso3ListLiterals() throws IOException {
        String source = readScorerSource();
        for (String literal : BANNED_COUNTRY_ISO3_LITERALS) {
            assertFalse(
                    source.contains(literal),
                    "CatalogCompositeScorer must not hardcode a country ISO3 literal: " + literal
                            + " (use CatalogCountryIso3Registry / CatalogGeoIntent.extractRowCountryCode instead)");
        }
    }

    @Test
    void scorerSourceContainsNoPerCountryAliasMapLiterals() throws IOException {
        String source = readScorerSource();
        assertFalse(
                source.contains("Map.of(\"SK\""),
                "CatalogCompositeScorer must not hardcode per-country alias maps — use the "
                        + "world_country_aliases.json / iso3_country_codes.json registries instead");
    }

    private static String readScorerSource() throws IOException {
        Path path = Path.of("src/main/java/cz/bankintel/search/CatalogCompositeScorer.java");
        assertTrue(
                Files.isRegularFile(path),
                "expected to find CatalogCompositeScorer.java at " + path.toAbsolutePath());
        return Files.readString(path);
    }
}
