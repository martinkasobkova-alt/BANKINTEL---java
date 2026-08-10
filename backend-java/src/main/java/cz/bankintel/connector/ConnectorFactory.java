package cz.bankintel.connector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Registr konektorů podle {@code source_type}.
 *
 * <p>Použití: {@code connectorFactory.get("eurostat")} → {@link EurostatConnector}.
 * Seznam podporovaných typů: {@link #supportedTypes()}.
 */
@Component
public class ConnectorFactory {

    private final Map<String, BaseConnector> byType;

    public ConnectorFactory(
            AradConnector aradConnector,
            FredConnector fredConnector,
            EurostatConnector eurostatConnector,
            CsuConnector csuConnector,
            BisConnector bisConnector,
            ImfConnector imfConnector,
            OecdConnector oecdConnector,
            Data360Connector data360Connector,
            EcbConnector ecbConnector,
            WorldbankPinkSheetConnector worldbankPinkSheetConnector,
            TradingEconomicsConnector tradingEconomicsConnector,
            FileUploadConnector fileUploadConnector) {
        Map<String, BaseConnector> map = new LinkedHashMap<>();
        register(map, aradConnector);
        register(map, fredConnector);
        register(map, eurostatConnector);
        register(map, csuConnector);
        register(map, bisConnector);
        register(map, imfConnector);
        register(map, oecdConnector);
        register(map, data360Connector);
        register(map, ecbConnector);
        register(map, worldbankPinkSheetConnector);
        register(map, tradingEconomicsConnector);
        register(map, fileUploadConnector);
        this.byType = Map.copyOf(map);
    }

    public BaseConnector get(String sourceType) {
        String key = normalizeSourceType(sourceType);
        BaseConnector connector = byType.get(key);
        if (connector == null) {
            throw new UnsupportedConnectorException(key);
        }
        return connector;
    }

    public boolean isSupported(String sourceType) {
        return byType.containsKey(normalizeSourceType(sourceType));
    }

    /** Aliasy {@code source_type} z UI/katalogu na klíče registrovaných konektorů. */
    public static String normalizeSourceType(String sourceType) {
        String key = sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
        return switch (key) {
            case "financial_markets" -> "financial_markets_mirror";
            case "commodities" -> "worldbank_pink_sheet";
            case "data360", "world_bank_data360" -> "world_bank_data360";
            case "ecb2" -> "ecb";
            default -> key;
        };
    }

    public List<String> supportedTypes() {
        return List.copyOf(byType.keySet());
    }

    private static void register(Map<String, BaseConnector> map, BaseConnector connector) {
        map.put(connector.sourceType(), connector);
    }

    public static class UnsupportedConnectorException extends RuntimeException {
        private final String sourceType;

        public UnsupportedConnectorException(String sourceType) {
            super("unsupported source_type: " + sourceType);
            this.sourceType = sourceType;
        }

        public String sourceType() {
            return sourceType;
        }
    }
}
