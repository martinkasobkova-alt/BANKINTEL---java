package cz.bankintel.search.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalyticsValueFormatterTest {

    @Test
    void formatsLargeValuesAsBillions() {
        String out = AnalyticsValueFormatter.formatValue(132_607_667_020.09, "CZK");
        assertTrue(out.contains("mld"));
        assertFalse(out.contains("132607667020"));
    }

    @Test
    void formatsPercentWithoutCompactScaling() {
        assertEquals("2,4 %", AnalyticsValueFormatter.formatValue(2.4, "%"));
    }

    @Test
    void formatsIsoPeriodToCzechDate() {
        assertEquals("31. 3. 2026", AnalyticsValueFormatter.formatPeriod("2026-03-31"));
    }

    @Test
    void prefersHumanSeriesNameOverTechnicalId() {
        assertEquals(
                "Zisk z finanční a provozní činnosti",
                AnalyticsValueFormatter.humanSeriesLabel(
                        "Zisk z finanční a provozní činnosti", "arad:1022:XYZ"));
    }
}
