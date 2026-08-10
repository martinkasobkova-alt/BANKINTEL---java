package cz.bankintel.connector;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Optional capability a {@link BaseConnector} can implement to expose a genuinely cancellable async
 * fetch path, used by the Search V2 preview hot path (behind
 * {@code SEARCH_V2_PREVIEW_ASYNC_CANCELLATION_ENABLED}).
 *
 * <p><b>Why both a "transport" and a "result" future are returned:</b> cancelling a
 * {@code CompletableFuture} created via {@code thenApply}/{@code handle}/etc. does NOT propagate
 * upstream to cancel the future it was derived from — only the reverse direction (upstream
 * completing/cancelling notifies the downstream stage) works automatically. So the caller needs a
 * direct handle on the innermost future that actually represents the network operation
 * ({@link AsyncFetchHandle#transportFuture()}, typically straight from
 * {@code ConnectorHttpSupport#getAsync}) in order to cancel the real HTTP exchange. The already-parsed
 * {@link AsyncFetchHandle#resultFuture()} is a downstream stage of that same transport future, so it
 * still completes (with a failure result) once the transport is cancelled — callers should await
 * {@code resultFuture()}, not {@code transportFuture()}, for the actual outcome.
 *
 * <p>Connectors that do not implement this interface are used through the existing synchronous
 * {@link BaseConnector#fetch(Map)} path only; see
 * {@code docs/search-v2-preview-cancellation-investigation.md} section 9 for exactly which
 * connectors implement this and why the remaining ones do not (yet).
 */
public interface AsyncCancellableFetch {

    record AsyncFetchHandle(CompletableFuture<?> transportFuture, CompletableFuture<ConnectorFetchResult> resultFuture) {

        /** For synchronous shortcuts (validation errors, mocked/offline branches) with no real network call to cancel. */
        public static AsyncFetchHandle completed(ConnectorFetchResult result) {
            return new AsyncFetchHandle(CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(result));
        }
    }

    AsyncFetchHandle fetchAsync(Map<String, Object> source);
}
