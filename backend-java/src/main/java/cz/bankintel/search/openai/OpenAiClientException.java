package cz.bankintel.search.openai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class OpenAiClientException extends RuntimeException {

    private final OpenAiErrorType errorType;
    private final int httpStatus;
    private Map<String, Object> trace = Map.of();

    public OpenAiClientException(OpenAiErrorType errorType, String message) {
        this(errorType, 0, message, null);
    }

    public OpenAiClientException(OpenAiErrorType errorType, int httpStatus, String message) {
        this(errorType, httpStatus, message, null);
    }

    public OpenAiClientException(OpenAiErrorType errorType, String message, Throwable cause) {
        this(errorType, 0, message, cause);
    }

    public OpenAiClientException(OpenAiErrorType errorType, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType == null ? OpenAiErrorType.LLM_UNKNOWN_ERROR : errorType;
        this.httpStatus = httpStatus;
    }

    public OpenAiErrorType errorType() {
        return errorType;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> trace() {
        return trace;
    }

    public OpenAiClientException withTrace(Map<String, Object> trace) {
        // OpenAI diagnostics intentionally use null for fields that were not observed (for
        // example http_status on a request timeout). Map.copyOf rejects null values and used to
        // replace the original provider error with an unrelated NullPointerException.
        this.trace = trace == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(trace));
        return this;
    }
}
