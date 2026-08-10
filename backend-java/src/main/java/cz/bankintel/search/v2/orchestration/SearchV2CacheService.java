package cz.bankintel.search.v2.orchestration;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class SearchV2CacheService {

    private static final int MAX_ENTRIES = 2048;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(String key, Duration ttl, Supplier<T> supplier) {
        return getOrComputeIf(key, ttl, supplier, ignored -> true);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrComputeIf(String key, Duration ttl, Supplier<T> supplier, Predicate<T> cacheWhen) {
        if (key == null || key.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return supplier.get();
        }
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAtMs() > now) {
            return (T) cached.value();
        }
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return (T) existing.join();
        }
        try {
            T value = supplier.get();
            if (cacheWhen == null || cacheWhen.test(value)) {
                putEntry(key, ttl, value);
            }
            created.complete(value);
            return value;
        } catch (RuntimeException | Error error) {
            created.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(key, created);
        }
    }

    public Optional<Object> get(String key) {
        Entry entry = cache.get(key);
        if (entry == null || entry.expiresAtMs() <= System.currentTimeMillis()) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void put(String key, Duration ttl, Object value) {
        if (key == null || key.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative() || value == null) {
            return;
        }
        putEntry(key, ttl, value);
    }

    private void putEntry(String key, Duration ttl, Object value) {
        cache.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
        if (cache.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
        if (cache.size() <= MAX_ENTRIES) {
            return;
        }
        cache.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((left, right) -> Long.compare(left.expiresAtMs(), right.expiresAtMs())))
                .limit(Math.max(1, cache.size() - MAX_ENTRIES))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(cache::remove);
    }

    private record Entry(Object value, long expiresAtMs) {}
}
