package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchV2CacheServiceTest {

    @Test
    void rejectedTransientResultDoesNotPoisonCache() {
        SearchV2CacheService cache = new SearchV2CacheService();
        AtomicInteger calls = new AtomicInteger();

        String degraded = cache.getOrComputeIf(
                "retrieval", Duration.ofMinutes(30), () -> {
                    calls.incrementAndGet();
                    return "timeout";
                }, "ok"::equals);
        String recovered = cache.getOrComputeIf(
                "retrieval", Duration.ofMinutes(30), () -> {
                    calls.incrementAndGet();
                    return "ok";
                }, "ok"::equals);
        String cached = cache.getOrComputeIf(
                "retrieval", Duration.ofMinutes(30), () -> {
                    calls.incrementAndGet();
                    return "unexpected";
                }, "ok"::equals);

        assertThat(degraded).isEqualTo("timeout");
        assertThat(recovered).isEqualTo("ok");
        assertThat(cached).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }
}
