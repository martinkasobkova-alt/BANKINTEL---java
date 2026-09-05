package cz.bankintel.search.v2.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SearchV2IndustrySectorRegistryTest {

    private final SearchV2IndustrySectorRegistry registry = new SearchV2IndustrySectorRegistry(new ObjectMapper());

    @Test
    void resolvesConstructionSectionFromCzechStavebnictvi() {
        assertThat(registry.resolve("zamestnanost ve stavebnictvi")).isEqualTo("F");
    }

    @Test
    void resolvesAgricultureSectionFromCzechZemedelstvi() {
        assertThat(registry.resolve("zamestnanost v zemedelstvi")).isEqualTo("A");
    }

    @Test
    void distinguishesConstructionFromAgricultureForTheSameQueryShape() {
        String construction = registry.resolve("zamestnanost ve stavebnictvi");
        String agriculture = registry.resolve("zamestnanost v zemedelstvi");

        assertThat(construction).isNotEqualTo(agriculture);
    }

    @Test
    void resolvesManufacturingFromEnglishManufacturingWord() {
        assertThat(registry.resolve("manufacturing output growth")).isEqualTo("C");
    }

    @Test
    void unrecognizedIndustryResolvesToBlankNotAnException() {
        assertThat(registry.resolve("inflace v eurozone")).isBlank();
    }

    @Test
    void matchedAliasExposesTheActualTermNotJustTheSectionId() {
        assertThat(registry.matchedAlias("zamestnanost ve stavebnictvi")).isEqualTo("stavebnictvi");
    }
}
