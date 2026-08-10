package cz.bankintel.search;

import cz.bankintel.connector.AsyncCancellableFetch;
import cz.bankintel.connector.BaseConnector;
import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.connector.ConnectorFetchResult;
import cz.bankintel.connector.InMemorySourceBuilder;
import cz.bankintel.sources.arad.AradSetIndicatorsService;
import cz.bankintel.sources.yahoo.YahooFinanceCatalogService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrace náhledu katalogové řady — POST /api/catalog/preview.
 *
 * <p>Tok: validace payload → {@link InMemorySourceBuilder} → {@link ConnectorFactory} → fetch →
 * parse → {@link CatalogSeriesFilter} → {@link PreviewResponseBuilder}.
 */
@Service
@RequiredArgsConstructor
public class CatalogPreviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CatalogPreviewOrchestrator.class);

    private static final String YAHOO_SOURCE_TYPE = "yahoo_finance";

    private final InMemorySourceBuilder sourceBuilder;
    private final ConnectorFactory connectorFactory;
    private final CatalogIndexStore indexStore;
    private final AradSetIndicatorsService aradSetIndicatorsService;
    private final YahooFinanceCatalogService yahooFinanceCatalogService;

    public Map<String, Object> preview(Map<String, Object> payload) {
        Map<String, Object> normalized = normalizePayload(payload);
        String sourceType = stringField(normalized, "source_type");
        String setId = stringField(normalized, "set_id");

        if (YAHOO_SOURCE_TYPE.equalsIgnoreCase(sourceType)) {
            return previewYahooFinance(normalized, setId);
        }

        if (!connectorFactory.isSupported(sourceType)) {
            return buildIndexFallback(normalized, sourceType, setId);
        }

        Map<String, Object> source = buildSource(sourceType, normalized);
        BaseConnector connector = connectorFactory.get(sourceType);
        ConnectorFetchResult fetchResult = connector.fetch(source);

        if (!fetchResult.isSuccess()) {
            log.warn(
                    "catalog preview fetch failed source_type={} set_id={} status={}",
                    sourceType,
                    setId,
                    fetchResult.httpStatus());
            Object raw = fetchResult.raw();
            Map<String, Object> errorBody =
                    raw instanceof Map<?, ?> map ? castMap(map) : Map.of("error", String.valueOf(raw));
            return PreviewResponseBuilder.buildError(normalized, source, fetchResult.httpStatus(), errorBody);
        }

        List<Map<String, Object>> records = connector.parse(fetchResult.raw(), source);
        String fetchSetId = firstNonBlank(stringField(source, "set_id"), setId);
        enrichAradIndicatorNames(records, sourceType, fetchSetId);
        CatalogSeriesFilter.FilterResult filter = CatalogSeriesFilter.apply(records, normalized);
        Map<String, Object> response =
                PreviewResponseBuilder.buildSuccess(normalized, source, filter.records(), filter);

        enrichFromIndex(response, sourceType, setId, normalized);
        log.info(
                "catalog preview ok source_type={} set_id={} rows={} indicators={}",
                sourceType,
                setId,
                filter.records().size(),
                filter.indicators().size());
        return response;
    }

    /**
     * Genuinely cancellable async counterpart of {@link #preview}, used by the Search V2 preview hot
     * path behind {@code SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED}. Returns empty when the
     * resolved connector does not implement {@link AsyncCancellableFetch} — the caller should fall
     * back to the synchronous {@link #preview} path in that case. Reuses the exact same
     * normalization/enrichment/filter/response-building helpers as {@link #preview}, so the response
     * shape is byte-identical; only the transport is async.
     */
    public Optional<AsyncPreviewHandle> previewAsyncIfSupported(Map<String, Object> payload) {
        Map<String, Object> normalized = normalizePayload(payload);
        String sourceType = stringField(normalized, "source_type");
        String setId = stringField(normalized, "set_id");

        if (!connectorFactory.isSupported(sourceType)) {
            return Optional.empty();
        }
        BaseConnector connector = connectorFactory.get(sourceType);
        if (!(connector instanceof AsyncCancellableFetch asyncConnector)) {
            return Optional.empty();
        }

        Map<String, Object> source = buildSource(sourceType, normalized);
        AsyncCancellableFetch.AsyncFetchHandle fetchHandle = asyncConnector.fetchAsync(source);
        CompletableFuture<Map<String, Object>> responseFuture = fetchHandle.resultFuture().thenApply(fetchResult -> {
            if (!fetchResult.isSuccess()) {
                log.warn(
                        "catalog preview fetch failed (async) source_type={} set_id={} status={}",
                        sourceType,
                        setId,
                        fetchResult.httpStatus());
                Object raw = fetchResult.raw();
                Map<String, Object> errorBody =
                        raw instanceof Map<?, ?> map ? castMap(map) : Map.of("error", String.valueOf(raw));
                return PreviewResponseBuilder.buildError(normalized, source, fetchResult.httpStatus(), errorBody);
            }
            List<Map<String, Object>> records = connector.parse(fetchResult.raw(), source);
            String fetchSetId = firstNonBlank(stringField(source, "set_id"), setId);
            enrichAradIndicatorNames(records, sourceType, fetchSetId);
            CatalogSeriesFilter.FilterResult filter = CatalogSeriesFilter.apply(records, normalized);
            Map<String, Object> response =
                    PreviewResponseBuilder.buildSuccess(normalized, source, filter.records(), filter);
            enrichFromIndex(response, sourceType, setId, normalized);
            log.info(
                    "catalog preview ok (async) source_type={} set_id={} rows={} indicators={}",
                    sourceType,
                    setId,
                    filter.records().size(),
                    filter.indicators().size());
            return response;
        });
        return Optional.of(new AsyncPreviewHandle(fetchHandle.transportFuture(), responseFuture));
    }

    /**
     * Pairs the raw transport future (cancel this to abort the real network operation — see
     * {@link AsyncCancellableFetch} for why cancelling {@code responseFuture} alone is not enough)
     * with the future for the fully parsed/filtered preview response.
     */
    public record AsyncPreviewHandle(
            CompletableFuture<?> transportFuture, CompletableFuture<Map<String, Object>> responseFuture) {}

    /** Fetch filtered records for export — same pipeline as preview without response envelope. */
    public List<Map<String, Object>> fetchRecords(Map<String, Object> payload) {
        Map<String, Object> normalized = normalizePayload(payload);
        String sourceType = stringField(normalized, "source_type");
        String setId = stringField(normalized, "set_id");

        if (YAHOO_SOURCE_TYPE.equalsIgnoreCase(sourceType)) {
            return fetchYahooRecords(normalized, setId);
        }

        if (!connectorFactory.isSupported(sourceType)) {
            return List.of();
        }

        Map<String, Object> source = buildSource(sourceType, normalized);
        BaseConnector connector = connectorFactory.get(sourceType);
        ConnectorFetchResult fetchResult = connector.fetch(source);
        if (!fetchResult.isSuccess()) {
            log.warn(
                    "catalog records fetch failed source_type={} set_id={} status={}",
                    sourceType,
                    setId,
                    fetchResult.httpStatus());
            return List.of();
        }
        List<Map<String, Object>> records = connector.parse(fetchResult.raw(), source);
        String fetchSetId = firstNonBlank(stringField(source, "set_id"), setId);
        enrichAradIndicatorNames(records, sourceType, fetchSetId);
        return CatalogSeriesFilter.apply(records, normalized).records();
    }

    /**
     * {@code yahoo_finance} candidates (stock/ETF/index tickers from {@link
     * cz.bankintel.sources.stocks.StockSearchService}) are not registered in {@link
     * ConnectorFactory} - {@link YahooFinanceCatalogService} already fetches and parses their OHLCV
     * data directly (no {@link BaseConnector} wrapper exists for it), so this bypasses the
     * connector/{@code fetch}/{@code parse} steps entirely and feeds its records straight into the
     * same {@link CatalogSeriesFilter}/{@link PreviewResponseBuilder} pipeline every other source
     * uses, so the response shape (rows/columns/dimensions) is identical. See the yahoo_finance
     * preview-wiring diagnosis (2026-07-31).
     */
    private Map<String, Object> previewYahooFinance(Map<String, Object> normalized, String setId) {
        String ticker = yahooTicker(normalized, setId);
        Map<String, Object> yahooResult = fetchYahooRaw(normalized, ticker);
        List<Map<String, Object>> records = recordsOf(yahooResult.get("records"));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_type", YAHOO_SOURCE_TYPE);
        source.put("set_id", ticker);
        source.put("name", stringField(yahooResult, "name"));

        CatalogSeriesFilter.FilterResult filter = CatalogSeriesFilter.apply(records, normalized);
        Map<String, Object> response = PreviewResponseBuilder.buildSuccess(normalized, source, filter.records(), filter);
        enrichFromIndex(response, YAHOO_SOURCE_TYPE, ticker, normalized);
        log.info("catalog preview ok source_type=yahoo_finance set_id={} rows={}", ticker, filter.records().size());
        return response;
    }

    /**
     * Same yahoo_finance bypass as {@link #previewYahooFinance}, but for {@link #fetchRecords} (used
     * by {@link cz.bankintel.search.forecast.ForecastDataAssemblerService} and CSV/XLSX export) -
     * without this, both silently saw zero records for every stock ticker (the {@code
     * ConnectorFactory.isSupported} guard rejected {@code yahoo_finance} before ever calling {@link
     * YahooFinanceCatalogService}), which is exactly what produced forecast's misleading
     * {@code insufficient_target_data} - the target series was never actually fetched, not genuinely
     * data-less. See the yahoo_finance forecast/export wiring diagnosis (2026-07-31).
     */
    private List<Map<String, Object>> fetchYahooRecords(Map<String, Object> normalized, String setId) {
        String ticker = yahooTicker(normalized, setId);
        Map<String, Object> yahooResult = fetchYahooRaw(normalized, ticker);
        return CatalogSeriesFilter.apply(recordsOf(yahooResult.get("records")), normalized).records();
    }

    private static String yahooTicker(Map<String, Object> normalized, String setId) {
        Map<String, Object> queryParams = firstMap(normalized.get("query_params"));
        return firstNonBlank(stringField(queryParams, "ticker"), setId);
    }

    private Map<String, Object> fetchYahooRaw(Map<String, Object> normalized, String ticker) {
        Map<String, Object> queryParams = firstMap(normalized.get("query_params"));
        String startDate = firstNonBlank(stringField(queryParams, "start_date"), "1990-01-01");
        String endDate = stringField(queryParams, "end_date");
        String interval = firstNonBlank(stringField(queryParams, "interval"), "1d");
        return yahooFinanceCatalogService.preview(ticker, startDate, endDate.isBlank() ? null : endDate, interval);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recordsOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(castMap(map));
            }
        }
        return out;
    }

    private static Map<String, Object> firstMap(Object value) {
        return value instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> normalized = new LinkedHashMap<>(payload != null ? payload : Map.of());
        String sourceType = stringField(normalized, "source_type");
        String setId = stringField(normalized, "set_id");
        if (sourceType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing source_type");
        }
        if (setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing set_id");
        }
        return normalized;
    }

    private Map<String, Object> buildSource(String sourceType, Map<String, Object> normalized) {
        Map<String, Object> source = sourceBuilder.build(sourceType, normalized);
        if (stringField(normalized, "selected_indicator").isBlank()
                && !stringField(source, "selected_indicator").isBlank()) {
            normalized.put("selected_indicator", stringField(source, "selected_indicator"));
        }
        return source;
    }

    private Map<String, Object> buildIndexFallback(Map<String, Object> payload, String sourceType, String setId) {
        Optional<Map<String, Object>> indexed = indexStore.lookupRow(sourceType, setId);
        String title = stringField(payload, "name");
        if (title.isBlank() && indexed.isPresent()) {
            title = CatalogTextUtils.rowTitle(indexed.get());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", Map.of("source_type", sourceType, "set_id", setId, "name", title));
        out.put("dataset_id", setId);
        out.put("title", title);
        out.put("rows", List.of());
        out.put("fields", List.of());
        out.put("columns", List.of());
        out.put(
                "message",
                "Náhled pro zdroj '"
                        + sourceType
                        + "' zatím není v Java backendu implementován. Podporované typy: "
                        + String.join(", ", connectorFactory.supportedTypes()));
        out.put("preview_state", "unsupported");
        out.put("sync_state", "unsupported");
        out.put(
                "metadata",
                Map.of(
                        "row_count", 0,
                        "index_hit", indexed.isPresent(),
                        "catalog_row", indexed.orElse(Map.of()),
                        "supported_types", connectorFactory.supportedTypes()));
        return out;
    }

    private static void enrichFromIndex(
            Map<String, Object> response, String sourceType, String setId, Map<String, Object> payload) {
        if (!stringField(payload, "name").isBlank()) {
            return;
        }
        // title already set in builder; keep hook for future metadata merge
        Object title = response.get("title");
        if (title == null || String.valueOf(title).isBlank()) {
            response.put("title", setId);
            Object sourceObj = response.get("source");
            if (sourceObj instanceof Map<?, ?> sourceMap) {
                Map<String, Object> patched = new LinkedHashMap<>(castMap(sourceMap));
                patched.put("name", setId);
                response.put("source", patched);
            }
        }
    }

    private void enrichAradIndicatorNames(List<Map<String, Object>> records, String sourceType, String setId) {
        if (!"arad".equalsIgnoreCase(sourceType) || records.isEmpty()) {
            return;
        }
        Map<String, String> labels = loadAradIndicatorLabels(setId);
        if (labels.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : records) {
            String indicatorId = stringField(row, "indicator_id");
            if (indicatorId.isBlank()) {
                continue;
            }
            String label = labels.get(indicatorId.toUpperCase(Locale.ROOT));
            if (label == null || label.isBlank()) {
                continue;
            }
            if (shouldReplaceIndicatorLabel(row.get("indicator_name"), indicatorId)) {
                row.put("indicator_name", label);
            }
            if (shouldReplaceIndicatorLabel(row.get("indicator_label"), indicatorId)) {
                row.put("indicator_label", label);
            }
        }
    }

    private static boolean shouldReplaceIndicatorLabel(Object current, String indicatorId) {
        String value = current == null ? "" : String.valueOf(current).trim();
        if (value.isBlank()) {
            return true;
        }
        if (value.indexOf('\uFFFD') >= 0) {
            return true;
        }
        return !indicatorId.isBlank() && value.equalsIgnoreCase(indicatorId);
    }

    private Map<String, String> loadAradIndicatorLabels(String setId) {
        try {
            Map<String, Object> payload = aradSetIndicatorsService.getSetIndicators(setId);
            Object rawIndicators = payload.get("indicators");
            if (!(rawIndicators instanceof List<?> indicators)) {
                return Map.of();
            }
            Map<String, String> labels = new LinkedHashMap<>();
            for (Object item : indicators) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> indicator = castMap(raw);
                String id = stringField(indicator, "indicator_id");
                String label = firstNonBlank(
                        stringField(indicator, "name"),
                        stringField(indicator, "indicator_name"),
                        stringField(indicator, "label"));
                if (!id.isBlank() && !label.isBlank()) {
                    labels.put(id.toUpperCase(Locale.ROOT), label);
                }
            }
            return labels;
        } catch (Exception ex) {
            log.debug("ARAD indicator label enrichment skipped for set_id={}: {}", setId, ex.getMessage());
            return Map.of();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
