package cz.bankintel.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * Fine-grained failure classification for {@link Data360Connector}, replacing the previous single
 * catch-all that collapsed every failure mode (network error, timeout, HTTP 4xx/5xx, malformed
 * response) into the same generic "network or timeout" message and status 0 - which meant a
 * malformed-query 400 was indistinguishable from a genuine World Bank Data360 outage, and a real
 * timeout was indistinguishable from an upstream application error (e.g. the {@code EA500001} /
 * "Data retrieval failed" envelope World Bank Data360 itself returns for a broken indicator).
 *
 * <p>Deliberately Data360-local: {@link ConnectorFetchResult}'s schema and the shared preview
 * classification pipeline ({@code PreviewResponseBuilder}, {@code SearchV2PreviewOutcome}, {@code
 * SearchV2PreviewCircuitBreaker}) are used by every connector, not just this one, so this class only
 * enriches the {@code error}/{@code sourceMeta} map {@link Data360Connector} already builds - it does
 * not touch how that shared pipeline classifies or counts failures (see this class's own tests and
 * the technical report for why {@code retryable} here is diagnostic-only, not wired into the circuit
 * breaker).
 */
public final class Data360ErrorClassifier {

    public static final String CONNECT_TIMEOUT = "connect_timeout";
    public static final String READ_TIMEOUT = "read_timeout";
    public static final String CONNECTION_FAILURE = "connection_failure";
    public static final String HTTP_4XX = "http_4xx";
    public static final String HTTP_5XX = "http_5xx";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String UPSTREAM_APPLICATION_ERROR = "upstream_application_error";
    public static final String PARSER_ERROR = "parser_error";
    public static final String INTERNAL_ERROR = "internal_error";

    private static final int UPSTREAM_MESSAGE_MAX_LEN = 300;

    private Data360ErrorClassifier() {}

    public record Classification(
            String category, boolean retryable, int httpStatus, String upstreamCode, String upstreamMessage) {}

    /**
     * Classifies a completed, non-2xx HTTP response. {@code parsedBody} is whatever {@code
     * Data360Connector} managed to parse from the response body as JSON, or {@code null} if the body
     * was not parseable JSON at all (e.g. an HTML error page from an intermediary proxy).
     *
     * <p>An upstream JSON error envelope (World Bank Data360's own {@code {"code": "...", "message":
     * "..."}} shape - confirmed live for the {@code EA500001}/"Data retrieval failed" IMF_FSI outage)
     * takes priority over a plain HTTP-status bucket: it is strictly more informative, regardless of
     * which specific 4xx/5xx status happened to carry it.
     */
    public static Classification classifyStatus(int status, Map<String, Object> parsedBody) {
        String upstreamCode = stringField(parsedBody, "code");
        String upstreamMessage = truncate(stringField(parsedBody, "message"));
        boolean hasUpstreamEnvelope = !upstreamCode.isBlank() && !upstreamMessage.isBlank();
        if (status == 429) {
            return new Classification(RATE_LIMITED, true, status, upstreamCode, upstreamMessage);
        }
        if (hasUpstreamEnvelope) {
            return new Classification(UPSTREAM_APPLICATION_ERROR, true, status, upstreamCode, upstreamMessage);
        }
        if (status >= 500) {
            return new Classification(HTTP_5XX, true, status, upstreamCode, upstreamMessage);
        }
        if (status >= 400) {
            return new Classification(HTTP_4XX, false, status, upstreamCode, upstreamMessage);
        }
        // Non-2xx, non-4xx/5xx (e.g. an unexpected 1xx/3xx a proxy passed through) - no evidence
        // either way, so treated as our own inability to handle the response rather than the API's fault.
        return new Classification(INTERNAL_ERROR, false, status, upstreamCode, upstreamMessage);
    }

    /**
     * Classifies a thrown exception from the connect/read/parse path. Order matters: {@link
     * HttpConnectTimeoutException} extends {@link HttpTimeoutException}, so it is checked first, and
     * {@link JsonProcessingException} extends {@link java.io.IOException}, so it is also checked
     * before the generic {@code IOException} fallback.
     */
    public static Classification classifyException(Throwable ex) {
        if (ex instanceof HttpConnectTimeoutException) {
            return new Classification(CONNECT_TIMEOUT, true, 0, "", truncate(safeMessage(ex)));
        }
        if (ex instanceof HttpTimeoutException) {
            return new Classification(READ_TIMEOUT, true, 0, "", truncate(safeMessage(ex)));
        }
        if (ex instanceof JsonProcessingException) {
            return new Classification(PARSER_ERROR, false, 0, "", truncate(safeMessage(ex)));
        }
        if (ex instanceof UnknownHostException
                || ex instanceof SSLException
                || ex instanceof ConnectException
                || ex instanceof java.io.IOException) {
            return new Classification(CONNECTION_FAILURE, true, 0, "", truncate(safeMessage(ex)));
        }
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new Classification(INTERNAL_ERROR, false, 0, "", truncate(safeMessage(ex)));
        }
        return new Classification(INTERNAL_ERROR, false, 0, "", truncate(safeMessage(ex)));
    }

    /** User-facing Czech text per category - safe (no stack traces, no raw upstream payloads). */
    public static String detailCsFor(String category) {
        return switch (category) {
            case CONNECT_TIMEOUT ->
                    "Nepodařilo se navázat spojení s World Bank Data360 API (timeout připojení). Zkuste to prosím znovu později.";
            case READ_TIMEOUT ->
                    "World Bank Data360 API neodpovědělo včas (timeout odpovědi). Zkuste to prosím znovu později.";
            case CONNECTION_FAILURE ->
                    "Nepodařilo se připojit k World Bank Data360 API (síťová chyba). Zkuste to prosím znovu později.";
            case HTTP_4XX -> "World Bank Data360 API odmítlo požadavek (neplatné parametry dotazu).";
            case HTTP_5XX ->
                    "World Bank Data360 API má aktuálně technické potíže (chyba serveru). Zkuste to prosím znovu později.";
            case RATE_LIMITED ->
                    "World Bank Data360 API dočasně odmítá požadavky (příliš mnoho dotazů). Zkuste to prosím znovu později.";
            case UPSTREAM_APPLICATION_ERROR ->
                    "World Bank Data360 API nahlásilo chybu při zpracování dat. Zkuste to prosím znovu později.";
            case PARSER_ERROR -> "Odpověď z World Bank Data360 API nešla zpracovat (neočekávaný formát dat).";
            default -> "Nepodařilo se zpracovat požadavek na World Bank Data360 API.";
        };
    }

    private static String stringField(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > UPSTREAM_MESSAGE_MAX_LEN ? trimmed.substring(0, UPSTREAM_MESSAGE_MAX_LEN) : trimmed;
    }
}
