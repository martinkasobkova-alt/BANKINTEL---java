package cz.bankintel.service.sources;

import cz.bankintel.connector.BaseConnector;
import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.connector.ConnectorFetchResult;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.SourceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SourceConnectionTestService {

    private static final long TIMEOUT_SEC = 25;

    private final SourceRepository sourceRepository;
    private final ConnectorFactory connectorFactory;

    @Transactional(readOnly = true)
    public Map<String, Object> testConnection(String sourceId) {
        SourceEntity source = sourceRepository
                .findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));

        Map<String, Object> connectorSource = SourceConnectorMapper.toConnectorSource(source);
        try {
            BaseConnector connector = connectorFactory.get(source.getSourceType());
            ConnectorFetchResult fetchResult = CompletableFuture.supplyAsync(() -> connector.fetch(connectorSource))
                    .get(TIMEOUT_SEC, TimeUnit.SECONDS);

            int httpStatus = fetchResult.httpStatus();
            Object raw = fetchResult.raw();
            List<Map<String, Object>> parsed = connector.parse(raw, connectorSource);
            List<Map<String, Object>> sample = parsed.size() > 3 ? parsed.subList(0, 3) : parsed;

            String errorDetail = null;
            if (raw instanceof Map<?, ?> rawMap) {
                Object err = rawMap.get("error");
                if (err != null) {
                    errorDetail = String.valueOf(err);
                } else if (rawMap.containsKey("raw_text") && parsed.isEmpty()) {
                    errorDetail = String.valueOf(rawMap.get("raw_text"));
                }
            }

            boolean ok = httpStatus >= 200 && httpStatus < 300 && (!sample.isEmpty() || errorDetail == null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", ok);
            out.put("http_status", httpStatus);
            out.put("records_preview", sample);
            out.put("record_count_preview", parsed.size());
            out.put("error", errorDetail);
            return out;
        } catch (TimeoutException ex) {
            return Map.of(
                    "ok",
                    false,
                    "error",
                    "Test připojení překročil timeout (" + TIMEOUT_SEC + " s).");
        } catch (ConnectorFactory.UnsupportedConnectorException ex) {
            return Map.of("ok", false, "error", "Nepodporovaný typ zdroje: " + ex.sourceType());
        } catch (Exception ex) {
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "Test selhal.");
        }
    }
}
