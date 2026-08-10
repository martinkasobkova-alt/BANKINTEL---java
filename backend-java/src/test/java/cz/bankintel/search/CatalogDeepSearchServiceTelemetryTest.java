package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogDeepSearchServiceTelemetryTest {

    @Test
    void sourceTerminalStatusDistinguishesEveryTerminalOutcome() {
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(3, false, false, false, false))
                .isEqualTo("completed");
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(0, false, false, false, false))
                .isEqualTo("empty");
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(3, true, false, false, false))
                .isEqualTo("budget_exhausted");
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(0, false, true, false, false))
                .isEqualTo("timeout");
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(0, false, false, true, false))
                .isEqualTo("error");
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(0, false, false, false, true))
                .isEqualTo("cancelled");
    }

    @Test
    void failureStatesHavePrecedenceOverPartialCandidates() {
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(5, true, true, true, true))
                .isEqualTo("error");
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(5, true, true, false, true))
                .isEqualTo("timeout");
        assertThat(CatalogDeepSearchService.sourceTerminalStatus(5, true, false, false, true))
                .isEqualTo("cancelled");
    }
}
