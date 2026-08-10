package cz.bankintel.connector;

import java.util.List;
import java.util.Map;

/**
 * Společné rozhraní pro všechny datové konektory.
 *
 * <p>Obdoba Python {@code connectors/base.py BaseConnector}:
 * {@link #fetch} stáhne raw data z API, {@link #parse} je převede na normalizované řádky.
 */
public interface BaseConnector {

    String sourceType();

    ConnectorFetchResult fetch(Map<String, Object> source);

    List<Map<String, Object>> parse(Object raw, Map<String, Object> source);
}
