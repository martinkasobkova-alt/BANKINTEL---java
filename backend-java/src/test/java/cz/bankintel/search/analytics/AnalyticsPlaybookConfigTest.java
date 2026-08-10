package cz.bankintel.search.analytics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalyticsPlaybookConfigTest {

    @Test
    void loadsInflationPlaybookWithForecastAndComparison() {
        AnalyticsPlaybookConfig config = AnalyticsPlaybookConfig.get();
        var playbook = config.playbookForDomain("price_inflation");
        assertTrue(playbook.isPresent());
        assertTrue(playbook.get().calculationTypes().contains("basic_metrics"));
        assertTrue(playbook.get().calculationTypes().contains("forecast"));
        assertTrue(playbook.get().benchmarkGroup() != null);
    }

    @Test
    void geoGroupV4HasFourMembers() {
        assertFalse(AnalyticsPlaybookConfig.get().geoGroupMembers("v4").isEmpty());
        assertTrue(AnalyticsPlaybookConfig.get().geoGroupMembers("v4").size() >= 4);
    }
}
