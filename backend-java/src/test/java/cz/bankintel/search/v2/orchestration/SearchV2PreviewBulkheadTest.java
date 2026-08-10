package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests of the PR-8 bulkhead: one {@link java.util.concurrent.Semaphore} per source,
 * independent of any HTTP or the shared {@code SearchV2PreviewVerifier} executor.
 */
class SearchV2PreviewBulkheadTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_DEFAULT_LIMIT");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED");
        System.clearProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_ECB2");
    }

    // ---- Regression safety: disabled by default -------------------------------------------------

    @Test
    void disabledByDefaultAlwaysAdmitsImmediatelyRegardlessOfHowManyAreInFlight() {
        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();

        for (int i = 0; i < 50; i++) {
            Optional<Runnable> admission = bulkhead.tryAdmit("fred", 1000);
            assertThat(admission).isPresent();
            // Deliberately never released - proves the disabled path never actually tracks a permit.
        }
    }

    // ---- Per-source concurrency cap ------------------------------------------------------------

    @Test
    void aSourceCannotExceedItsOwnConfiguredLimit() throws Exception {
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "2");
        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();

        Optional<Runnable> first = bulkhead.tryAdmit("fred", 200);
        Optional<Runnable> second = bulkhead.tryAdmit("fred", 200);
        assertThat(first).isPresent();
        assertThat(second).isPresent();

        long start = System.currentTimeMillis();
        Optional<Runnable> third = bulkhead.tryAdmit("fred", 200);
        long waited = System.currentTimeMillis() - start;

        assertThat(third)
                .as("a third concurrent attempt against a limit-of-2 source must be refused, not silently admitted")
                .isEmpty();
        assertThat(waited).as("must have actually waited out the full budget before giving up").isGreaterThanOrEqualTo(180);
    }

    @Test
    void releasingAPermitFreesCapacityForASubsequentAttemptOnTheSameSource() {
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();

        Optional<Runnable> first = bulkhead.tryAdmit("fred", 200);
        assertThat(first).isPresent();
        assertThat(bulkhead.tryAdmit("fred", 50)).as("saturated at limit 1").isEmpty();

        first.get().run(); // release

        assertThat(bulkhead.tryAdmit("fred", 200))
                .as("released permit must become available to a new attempt")
                .isPresent();
    }

    // ---- Per-source isolation: one busy source must not affect another --------------------------

    @Test
    void oneSaturatedSourceDoesNotBlockAdmissionForADifferentSource() {
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_ECB2", "1");
        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();

        Optional<Runnable> fredHeld = bulkhead.tryAdmit("fred", 200);
        assertThat(fredHeld).isPresent();
        assertThat(bulkhead.tryAdmit("fred", 50)).as("fred is fully saturated").isEmpty();

        long start = System.currentTimeMillis();
        Optional<Runnable> ecbAdmission = bulkhead.tryAdmit("ecb2", 5000);
        long waited = System.currentTimeMillis() - start;

        assertThat(ecbAdmission)
                .as("a completely different source's own quota must be untouched by fred's saturation")
                .isPresent();
        assertThat(waited).as("must not have waited on fred's exhausted permits at all").isLessThan(500);
    }

    // ---- Concurrency correctness: many concurrent callers, exact admitted count -----------------

    @Test
    void underConcurrentLoadExactlyTheConfiguredLimitIsAdmittedAtOnce() throws Exception {
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "3");
        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Boolean> admittedFlags = new CopyOnWriteArrayList<>();
        CountDownLatch doneGate = new CountDownLatch(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    await(startGate);
                    Optional<Runnable> admission = bulkhead.tryAdmit("fred", 300);
                    admittedFlags.add(admission.isPresent());
                    doneGate.countDown();
                    // Deliberately hold the permit (never release) to keep all admitted attempts
                    // concurrently in-flight for the duration of the other 9 attempts.
                });
            }
            startGate.countDown();
            assertThat(doneGate.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long admittedCount = admittedFlags.stream().filter(Boolean::booleanValue).count();
        assertThat(admittedCount)
                .as("exactly the configured limit of 3 may be concurrently in flight for one source")
                .isEqualTo(3);
    }

    // ---- Configuration ----------------------------------------------------------------------------

    @Test
    void perSourceLimitOverridesTheSharedDefault() {
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_DEFAULT_LIMIT", "5");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();

        assertThat(bulkhead.configuredLimit("fred")).isEqualTo(1);
        assertThat(bulkhead.configuredLimit("ecb2"))
                .as("a source with no explicit override falls back to the shared default")
                .isEqualTo(5);
    }

    @Test
    void sourceNameMatchingIsCaseAndWhitespaceInsensitive() {
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BULKHEAD_LIMIT_FRED", "1");
        SearchV2PreviewBulkhead bulkhead = new SearchV2PreviewBulkhead();

        assertThat(bulkhead.tryAdmit(" FRED ", 200)).isPresent();
        assertThat(bulkhead.tryAdmit("fred", 50))
                .as("' FRED ' and 'fred' must share the same underlying semaphore")
                .isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
