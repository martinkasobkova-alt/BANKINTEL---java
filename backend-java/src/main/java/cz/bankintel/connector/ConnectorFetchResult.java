package cz.bankintel.connector;

import java.util.Map;

/**
 * Výsledek HTTP volání konektoru — status kód + surové tělo (JSON, CSV text, …).
 */
public record ConnectorFetchResult(int httpStatus, Object raw, Map<String, Object> sourceMeta) {

    public static ConnectorFetchResult ok(Object raw, Map<String, Object> sourceMeta) {
        return new ConnectorFetchResult(200, raw, sourceMeta);
    }

    public static ConnectorFetchResult error(int status, Object raw, Map<String, Object> sourceMeta) {
        return new ConnectorFetchResult(status, raw, sourceMeta);
    }

    public boolean isSuccess() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
