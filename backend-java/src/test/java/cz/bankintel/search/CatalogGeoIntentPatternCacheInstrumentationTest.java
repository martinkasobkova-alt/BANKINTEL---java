package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Perf fix (geo-detection regex-compilation storm): proves the precompiled alias/pattern index is
 * built exactly once, at class-init, and the request path (detectGeoIntent) never triggers a new
 * {@code Pattern.compile()} for a standard country/EU/global alias afterward - the actual property
 * the whole optimization exists to guarantee.
 */
class CatalogGeoIntentPatternCacheInstrumentationTest {

    @Test
    void compiledAliasesIsBuiltOnceAndNeverGrows() {
        long firstSize = CatalogCountryAliasRegistry.compiledAliases().size();
        long firstCompiledCount = CatalogCountryAliasRegistry.compiledPatternCountForTest();

        CatalogGeoIntent.detectGeoIntent("Germany vs France comparison of GDP and inflation");
        CatalogGeoIntent.detectGeoIntent("inflace ceska republika");
        CatalogGeoIntent.detectGeoIntent("random text with no country at all");

        assertThat(CatalogCountryAliasRegistry.compiledAliases().size()).isEqualTo(firstSize);
        assertThat(CatalogCountryAliasRegistry.compiledPatternCountForTest()).isEqualTo(firstCompiledCount);
    }

    @Test
    void repeatedDetectGeoIntentCallsNeverIncreaseCompiledPatternCount() {
        long before = CatalogCountryAliasRegistry.compiledPatternCountForTest();
        for (int i = 0; i < 200; i++) {
            CatalogGeoIntent.detectGeoIntent("HDP Nemecko " + i + " a Francie " + i);
        }
        long after = CatalogCountryAliasRegistry.compiledPatternCountForTest();
        assertThat(after).as("no new Pattern.compile calls for standard alias matching after class-init")
                .isEqualTo(before);
    }

    @Test
    void detectGeoIntentCallCountIncreasesByExactlyTheNumberOfCalls() {
        long before = CatalogGeoIntent.detectGeoIntentCallCountForTest();
        for (int i = 0; i < 17; i++) {
            CatalogGeoIntent.detectGeoIntent("query " + i);
        }
        assertThat(CatalogGeoIntent.detectGeoIntentCallCountForTest() - before).isEqualTo(17);
    }

    @Test
    void matcherExecutionCountIncreasesConfirmingMatchersStillActuallyRun() {
        long before = CatalogGeoIntent.matcherExecutionCountForTest();
        CatalogGeoIntent.detectGeoIntent("Germany France comparison");
        long after = CatalogGeoIntent.matcherExecutionCountForTest();
        assertThat(after).as("matching must still execute against precompiled patterns, not be skipped")
                .isGreaterThan(before);
    }

    @Test
    void compiledAliasesListIsImmutable() {
        List<CatalogCountryAliasRegistry.CompiledCountryAlias> aliases = CatalogCountryAliasRegistry.compiledAliases();
        assertThatThrownBy(() -> aliases.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyCompiledAliasHasAtLeastOneNonNullPattern() {
        for (CatalogCountryAliasRegistry.CompiledCountryAlias compiled : CatalogCountryAliasRegistry.compiledAliases()) {
            assertThat(compiled.foldedPattern() != null || compiled.rawPattern() != null)
                    .as("alias=%s country=%s", compiled.originalAlias(), compiled.countryCode())
                    .isTrue();
        }
    }

    @Test
    void parallelDetectGeoIntentCallsReturnConsistentResultsAndNeverGrowCompiledPatternCount()
            throws InterruptedException {
        long beforeCompiledCount = CatalogCountryAliasRegistry.compiledPatternCountForTest();
        int threadCount = 16;
        int callsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger mismatches = new AtomicInteger();
        String query = "Germany vs France vs Czech Republic economic comparison";
        Map<String, Object> expected = CatalogGeoIntent.detectGeoIntent(query);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        Map<String, Object> actual = CatalogGeoIntent.detectGeoIntent(query);
                        if (!actual.get("country_codes").equals(expected.get("country_codes"))
                                || !actual.get("type").equals(expected.get("type"))) {
                            mismatches.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).as("all threads must finish promptly").isTrue();
        executor.shutdown();

        assertThat(mismatches.get()).as("no thread must observe a different result under concurrency").isZero();
        assertThat(CatalogCountryAliasRegistry.compiledPatternCountForTest())
                .as("concurrent calls must not trigger new pattern compilation")
                .isEqualTo(beforeCompiledCount);
    }
}
