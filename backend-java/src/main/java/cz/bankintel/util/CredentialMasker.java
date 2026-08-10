package cz.bankintel.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CredentialMasker {

    private CredentialMasker() {}

    public static Map<String, Object> maskCredentials(Map<String, Object> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : credentials.entrySet()) {
            masked.put(entry.getKey(), maskValue(entry.getValue()));
        }
        return masked;
    }

    private static Object maskValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                out.put(String.valueOf(entry.getKey()), maskSecret(String.valueOf(entry.getValue())));
            }
            return out;
        }
        if (value instanceof String str) {
            return maskSecret(str);
        }
        return value;
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
