package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogResultSpecificityScorerTest {

    @Test
    void penalizesUnrequestedCarbonIntensityForPlainGdpQuery() {
        int adjustment = CatalogResultSpecificityScorer.adjustment(
                "gdp cesko",
                Map.of(
                        "title",
                        "Uhlikova intenzita HDP",
                        "set_id",
                        "WB_WDI|WB_WDI_EN_GHG_CO2_RT_GDP_KD",
                        "full_path",
                        "CO2 emissions per unit of GDP"));

        assertTrue(adjustment <= -700, "plain GDP query should not prefer emissions per GDP: " + adjustment);
    }

    @Test
    void keepsRequestedCarbonIntensityRelevant() {
        int adjustment = CatalogResultSpecificityScorer.adjustment(
                "co2 intensity gdp",
                Map.of(
                        "title",
                        "Carbon intensity of GDP",
                        "full_path",
                        "CO2 emissions per unit of GDP"));

        assertTrue(adjustment >= 0, "requested carbon intensity should not be penalized: " + adjustment);
    }
}
