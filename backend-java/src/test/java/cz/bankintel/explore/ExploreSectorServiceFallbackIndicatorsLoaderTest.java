package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the JSON data migration: {@link ExploreSectorService}'s fallback
 * sector/macro indicators (used when no OpenAI planner is configured) must be loaded from {@code
 * catalog/explore_fallback_indicators.json} rather than declared as inline Java literals.
 */
class ExploreSectorServiceFallbackIndicatorsLoaderTest {

    @Test
    void fallbackIndicatorsLoadedNonEmptyFromJson() throws ReflectiveOperationException {
        Field field = ExploreSectorService.class.getDeclaredField("FALLBACK_INDICATORS");
        field.setAccessible(true);
        Object fallbackSet = field.get(null);

        Method sectorIndicatorsMethod = fallbackSet.getClass().getDeclaredMethod("sectorIndicators");
        sectorIndicatorsMethod.setAccessible(true);
        Method macroIndicatorsMethod = fallbackSet.getClass().getDeclaredMethod("macroIndicators");
        macroIndicatorsMethod.setAccessible(true);

        List<?> sectorIndicators = (List<?>) sectorIndicatorsMethod.invoke(fallbackSet);
        List<?> macroIndicators = (List<?>) macroIndicatorsMethod.invoke(fallbackSet);

        assertFalse(sectorIndicators.isEmpty(), "explore_fallback_indicators.json sector_indicators must not be empty");
        assertFalse(macroIndicators.isEmpty(), "explore_fallback_indicators.json macro_indicators must not be empty");

        Object firstSector = sectorIndicators.get(0);
        Method nameForMethod = firstSector.getClass().getDeclaredMethod("nameFor", String.class);
        nameForMethod.setAccessible(true);
        String rendered = (String) nameForMethod.invoke(firstSector, "Bankovnictví");
        assertTrue(rendered.contains("Bankovnictví"), "name_template {sector} placeholder must be substituted: " + rendered);
    }

    @Test
    void sourceContainsNoInlineFallbackIndicatorLiterals() throws IOException {
        String source = readSource();
        assertFalse(
                source.contains("\"nama_10_a10\""),
                "ExploreSectorService must not hardcode fallback dataset ids — "
                        + "use catalog/explore_fallback_indicators.json via the loader instead");
        assertFalse(
                source.contains("\"CPIAUCSL\""),
                "ExploreSectorService must not hardcode fallback dataset ids — "
                        + "use catalog/explore_fallback_indicators.json via the loader instead");
    }

    private static String readSource() throws IOException {
        Path path = Path.of("src/main/java/cz/bankintel/explore/ExploreSectorService.java");
        assertTrue(Files.isRegularFile(path), "expected to find " + path.toAbsolutePath());
        return Files.readString(path);
    }
}
