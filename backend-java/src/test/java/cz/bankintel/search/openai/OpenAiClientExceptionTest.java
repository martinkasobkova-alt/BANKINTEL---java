package cz.bankintel.search.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiClientExceptionTest {

    @Test
    void preservesUnavailableTraceFieldsWithoutMaskingOriginalFailure() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("provider", "openai");
        trace.put("http_status", null);
        trace.put("response_latency_ms", null);

        OpenAiClientException error = new OpenAiClientException(
                        OpenAiErrorType.LLM_REQUEST_TIMEOUT, "OpenAI request timed out")
                .withTrace(trace);

        assertThat(error.errorType()).isEqualTo(OpenAiErrorType.LLM_REQUEST_TIMEOUT);
        assertThat(error.getMessage()).isEqualTo("OpenAI request timed out");
        assertThat(error.trace()).containsEntry("provider", "openai");
        assertThat(error.trace()).containsKey("http_status");
        assertThat(error.trace().get("http_status")).isNull();
    }
}
