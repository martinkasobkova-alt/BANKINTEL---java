package cz.bankintel.sources.eurostat;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Global cap on concurrent outbound Eurostat HTTP calls, shared across every caller that talks
 * to Eurostat - not just {@link EurostatDimensionService}'s own metadata/probe traffic (its
 * previous home before this was extracted), but also the Manager Explorer cache-refresh job,
 * which can make thousands of Eurostat calls per run and would otherwise compete uncoordinated
 * with live interactive requests for the same external rate limit.
 */
@Component
public class EurostatRateLimiter {

    private static final int MAX_CONCURRENT_EUROSTAT_CALLS = 6;

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_EUROSTAT_CALLS);

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return semaphore.tryAcquire(timeout, unit);
    }

    public void release() {
        semaphore.release();
    }
}
