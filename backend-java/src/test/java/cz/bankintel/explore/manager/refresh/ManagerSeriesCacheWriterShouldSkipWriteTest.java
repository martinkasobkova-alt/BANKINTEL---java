package cz.bankintel.explore.manager.refresh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers only the pure no-regression comparison ({@link ManagerSeriesCacheWriter#shouldSkipWrite})
 * - the actual {@code bulkWrite} mechanics need a live Mongo connection and are verified manually
 * during the Phase 1 shadow-collection rollout step instead, matching this codebase's existing
 * convention of never unit-testing the Mongo driver itself (see {@code ManagerSeriesCacheReader},
 * which also has no unit tests).
 */
class ManagerSeriesCacheWriterShouldSkipWriteTest {

    @Test
    void skipsWhenIncomingPeriodIsOlderThanExisting() {
        assertTrue(ManagerSeriesCacheWriter.shouldSkipWrite("2026-06", "2026-05"));
    }

    @Test
    void doesNotSkipWhenIncomingPeriodIsNewerOrEqual() {
        assertFalse(ManagerSeriesCacheWriter.shouldSkipWrite("2026-05", "2026-06"));
        assertFalse(ManagerSeriesCacheWriter.shouldSkipWrite("2026-06", "2026-06"));
    }

    @Test
    void neverSkipsWhenNoExistingDocument() {
        assertFalse(ManagerSeriesCacheWriter.shouldSkipWrite(null, "2026-06"));
        assertFalse(ManagerSeriesCacheWriter.shouldSkipWrite("", "2026-06"));
    }

    @Test
    void skipsWhenIncomingPeriodIsMissingButExistingHasOne() {
        assertTrue(ManagerSeriesCacheWriter.shouldSkipWrite("2026-06", null));
        assertTrue(ManagerSeriesCacheWriter.shouldSkipWrite("2026-06", ""));
    }

    @Test
    void neverBlocksOnUnparseablePeriods() {
        assertFalse(ManagerSeriesCacheWriter.shouldSkipWrite("not-a-period", "2026-06"));
        assertFalse(ManagerSeriesCacheWriter.shouldSkipWrite("2026-06", "not-a-period"));
    }

    @Test
    void comparesQuartersAndYearsAcrossDifferentPeriodShapes() {
        // 2026-Q2 -> month index year*12 + (q-1)*3 + 1 = 2026*12+4; 2026-06 -> 2026*12+6. Newer wins.
        assertFalse(ManagerSeriesCacheWriter.shouldSkipWrite("2026-Q2", "2026-06"));
        assertTrue(ManagerSeriesCacheWriter.shouldSkipWrite("2026-06", "2026-Q1"));
    }
}
