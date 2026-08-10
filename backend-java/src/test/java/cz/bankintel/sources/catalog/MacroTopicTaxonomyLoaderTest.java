package cz.bankintel.sources.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the JSON data migration: {@link MacroTopicTaxonomy#TOPICS} and {@link
 * MacroTopicTaxonomy#GEO_LABELS} must be loaded from {@code catalog/macro_topics_taxonomy.json}
 * rather than declared as inline Java literals.
 */
class MacroTopicTaxonomyLoaderTest {

    @Test
    void topicsLoadedNonEmptyFromJson() {
        assertFalse(MacroTopicTaxonomy.TOPICS.isEmpty(), "macro_topics_taxonomy.json topics must not be empty");
        assertTrue(MacroTopicTaxonomy.TOPIC_BY_ID.containsKey("inflace_celkova"));
        MacroTopicTaxonomy.Topic topic = MacroTopicTaxonomy.TOPIC_BY_ID.get("inflace_celkova");
        assertFalse(topic.titleAny().isEmpty());
        assertTrue(topic.indicatorIds().contains("inflation_prices"));
    }

    @Test
    void geoLabelsLoadedNonEmptyFromJson() {
        assertFalse(MacroTopicTaxonomy.GEO_LABELS.isEmpty(), "macro_topics_taxonomy.json geo_labels must not be empty");
        assertTrue(MacroTopicTaxonomy.GEO_LABELS.containsKey("CZ"));
    }

    @Test
    void sourceContainsNoInlineTopicFactoryCalls() throws IOException {
        String source = readSource();
        assertFalse(
                source.contains("topic(\"inflace_celkova\""),
                "MacroTopicTaxonomy must not hardcode TOPICS via inline topic(...) calls — "
                        + "use catalog/macro_topics_taxonomy.json via the loader instead");
    }

    private static String readSource() throws IOException {
        Path path = Path.of("src/main/java/cz/bankintel/sources/catalog/MacroTopicTaxonomy.java");
        assertTrue(Files.isRegularFile(path), "expected to find " + path.toAbsolutePath());
        return Files.readString(path);
    }
}
