package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Direct unit tests of the PR-10 per-connector circuit breaker, independent of the verifier/executor. */
class SearchV2PreviewCircuitBreakerTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED");
        System.clearProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_ECB2");
    }

    // ---- Regression safety: disabled by default --------------------------------------------------

    @Test
    void disabledByDefaultAlwaysAllowsEvenAfterManyFailures() {
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        for (int i = 0; i < 20; i++) {
            breaker.recordFailure("fred");
        }
        assertThat(breaker.allowRequest("fred")).isTrue();
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
    }

    // ---- CLOSED -> OPEN on threshold ----------------------------------------------------------

    @Test
    void opensAfterReachingTheConfiguredConsecutiveFailureThreshold() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "3");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();

        breaker.recordFailure("fred");
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
        breaker.recordFailure("fred");
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
        breaker.recordFailure("fred");

        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest("fred")).as("open breaker must fail fast").isFalse();
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCountSoItNeverOpensFromIntermittentFailures() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "3");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();

        breaker.recordFailure("fred");
        breaker.recordFailure("fred");
        breaker.recordSuccess("fred");
        breaker.recordFailure("fred");
        breaker.recordFailure("fred");

        assertThat(breaker.stateOf("fred"))
                .as("the intervening success must have reset the streak - 2 failures since then is not enough")
                .isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
    }

    // ---- OPEN -> HALF_OPEN after cooldown ------------------------------------------------------

    @Test
    void staysOpenUntilTheCooldownElapsesThenAllowsExactlyOneHalfOpenTrial() throws Exception {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED", "150");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();

        breaker.recordFailure("fred");
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest("fred")).as("still within cooldown").isFalse();

        Thread.sleep(200);

        assertThat(breaker.allowRequest("fred"))
                .as("cooldown elapsed - exactly one trial request must be let through")
                .isTrue();
        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.allowRequest("fred"))
                .as("a second concurrent caller must not get its own trial while one is already in flight")
                .isFalse();
    }

    @Test
    void aSuccessfulHalfOpenTrialClosesTheBreaker() throws Exception {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED", "100");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();

        breaker.recordFailure("fred");
        Thread.sleep(150);
        assertThat(breaker.allowRequest("fred")).isTrue(); // claims the trial

        breaker.recordSuccess("fred");

        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest("fred")).isTrue();
    }

    @Test
    void aFailedHalfOpenTrialReopensAndRestartsTheCooldown() throws Exception {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED", "100");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();

        breaker.recordFailure("fred");
        Thread.sleep(150);
        assertThat(breaker.allowRequest("fred")).isTrue(); // claims the trial

        breaker.recordFailure("fred"); // trial itself fails

        assertThat(breaker.stateOf("fred")).isEqualTo(SearchV2PreviewCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest("fred")).as("cooldown timer must have restarted").isFalse();
    }

    // ---- Per-source isolation -------------------------------------------------------------------

    @Test
    void oneSourcesOpenBreakerDoesNotAffectAnotherSource() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();

        breaker.recordFailure("fred");

        assertThat(breaker.allowRequest("fred")).isFalse();
        assertThat(breaker.allowRequest("ecb2"))
                .as("a completely different source's breaker must be unaffected")
                .isTrue();
        assertThat(breaker.stateOf("ecb2")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
    }

    // ---- FileUploadConnector is never breaker-tracked --------------------------------------------

    @Test
    void fileUploadSourceIsNeverTrackedByTheBreakerRegardlessOfFailures() {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD", "1");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();

        for (int i = 0; i < 10; i++) {
            breaker.recordFailure("file_upload");
        }

        assertThat(breaker.allowRequest("file_upload")).isTrue();
        assertThat(breaker.stateOf("file_upload")).isEqualTo(SearchV2PreviewCircuitBreaker.State.CLOSED);
    }

    // ---- Concurrency: exactly one half-open trial admitted under contention -----------------------

    @Test
    void underConcurrentLoadDuringHalfOpenExactlyOneCallerIsAdmitted() throws Exception {
        System.setProperty("SEARCH_PREVIEW_CIRCUIT_BREAKER_ENABLED", "true");
        System.setProperty("SEARCH_PREVIEW_BREAKER_FAILURE_THRESHOLD_FRED", "1");
        System.setProperty("SEARCH_PREVIEW_BREAKER_COOLDOWN_MS_FRED", "100");
        SearchV2PreviewCircuitBreaker breaker = new SearchV2PreviewCircuitBreaker();
        breaker.recordFailure("fred");
        Thread.sleep(150); // cooldown elapsed - breaker is ready to transition to HALF_OPEN

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(attempts);
        java.util.List<Boolean> admittedFlags = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    await(startGate);
                    admittedFlags.add(breaker.allowRequest("fred"));
                    doneGate.countDown();
                });
            }
            startGate.countDown();
            assertThat(doneGate.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long admittedCount = admittedFlags.stream().filter(Boolean::booleanValue).count();
        assertThat(admittedCount).as("exactly one caller may claim the half-open trial").isEqualTo(1);
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
