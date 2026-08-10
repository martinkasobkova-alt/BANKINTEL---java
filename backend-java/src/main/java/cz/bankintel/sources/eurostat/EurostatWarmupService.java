package cz.bankintel.sources.eurostat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Warms {@link EurostatDimensionService}'s metadata/preview-selection caches at startup for a
 * curated list of common macro-indicator datasets and countries, so that the first live user
 * deep-search query does not pay the full cold-start dimension-resolution cost (kolo 6, #3b —
 * Zjištění C: cold-start Eurostat dimension resolution was the actual root cause of "nezaměstnanost
 * Slovensko" timing out, not scoring). The dataset/country list lives in a JSON resource, not in
 * code, so it stays a pure performance/caching concern and is never consulted for ranking.
 */
@Service
@RequiredArgsConstructor
public class EurostatWarmupService {

    private static final Logger log = LoggerFactory.getLogger(EurostatWarmupService.class);
    private static final String WARMUP_RESOURCE = "catalog/eurostat_warmup_datasets.json";

    private final EurostatDimensionService eurostatDimensionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService warmupExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void shutdownExecutors() {
        warmupExecutor.shutdown();
    }

    public void triggerWarmup() {
        WarmupSpec spec = loadSpec();
        if (spec.datasets().isEmpty()) {
            log.info("eurostat warmup skipped — no datasets configured in {}", WARMUP_RESOURCE);
            return;
        }
        int total = spec.datasets().size() * Math.max(1, spec.countries().size());
        log.info(
                "eurostat warmup starting — {} dataset(s) x {} countr(y/ies) = {} combination(s)",
                spec.datasets().size(),
                spec.countries().size(),
                total);
        long started = System.currentTimeMillis();
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (String datasetId : spec.datasets()) {
            List<String> countries = spec.countries().isEmpty() ? List.of("") : spec.countries();
            for (String country : countries) {
                futures.add(java.util.concurrent.CompletableFuture.runAsync(
                        () -> warmOne(datasetId, country), warmupExecutor));
            }
        }
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((ignored, err) -> log.info(
                        "eurostat warmup finished in {} ms ({} combination(s))",
                        System.currentTimeMillis() - started,
                        total));
    }

    private void warmOne(String datasetId, String country) {
        try {
            eurostatDimensionService.resolvePreviewQueryParams(datasetId, country);
        } catch (Exception ex) {
            log.debug("eurostat warmup failed for {}/{}: {}", datasetId, country, ex.getMessage());
        }
    }

    private WarmupSpec loadSpec() {
        try (InputStream in = new ClassPathResource(WARMUP_RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<String> datasets = new ArrayList<>();
            for (JsonNode node : root.path("datasets")) {
                String value = node.asText("").trim();
                if (!value.isBlank()) {
                    datasets.add(value);
                }
            }
            List<String> countries = new ArrayList<>();
            for (JsonNode node : root.path("countries")) {
                String value = node.asText("").trim();
                if (!value.isBlank()) {
                    countries.add(value);
                }
            }
            return new WarmupSpec(datasets, countries);
        } catch (Exception ex) {
            log.warn("failed to load eurostat warmup spec from {}: {}", WARMUP_RESOURCE, ex.getMessage());
            return new WarmupSpec(List.of(), List.of());
        }
    }

    private record WarmupSpec(List<String> datasets, List<String> countries) {}
}
