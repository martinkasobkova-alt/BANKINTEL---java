package cz.bankintel.connector;

/**
 * Konektory datových zdrojů — Java port Python balíčku {@code backend/connectors/}.
 *
 * <p>Každý konektor stahuje data z externího API (fetch) a normalizuje je na {@code List<Map>}
 * (parse). Používají je náhledy katalogu ({@link cz.bankintel.search.CatalogPreviewOrchestrator})
 * i synchronizace ({@link cz.bankintel.service.sync.SyncService}).
 *
 * <h2>Společná vrstva</h2>
 * <ul>
 *   <li>{@link BaseConnector} — rozhraní fetch + parse
 *   <li>{@link ConnectorFactory} — výběr konektoru podle {@code source_type}
 *   <li>{@link InMemorySourceBuilder} — dočasný „source“ dict z preview payloadu (jako Python
 *       {@code _build_in_memory_source})
 *   <li>{@link ConnectorHttpSupport} — HTTP GET/POST, CSV, JSON
 *   <li>{@link ConnectorFetchResult} — výsledek HTTP volání (status + raw tělo)
 *   <li>{@link ConnectorParseSupport} — sdílené parsování BIS/IMF/OECD/Data360
 *   <li>{@link EurostatJsonStatParser} — JSON-stat 2.0 → flat řádky
 * </ul>
 *
 * <h2>Konektory podle zdroje</h2>
 * <ul>
 *   <li>{@link AradConnector} — ČNB ARAD REST ({@code ARAD_API_KEY})
 *   <li>{@link FredConnector} — FRED observations API ({@code FRED_API_KEY})
 *   <li>{@link EurostatConnector} — Eurostat JSON-stat API (veřejné)
 *   <li>{@link CsuConnector} — ČSÚ DataStat CSV (veřejné)
 *   <li>{@link BisConnector} — BIS Stats API
 *   <li>{@link ImfConnector} — IMF SDMX 3.0 ({@code IMF_API_KEY})
 *   <li>{@link OecdConnector} — OECD SDMX / legacy CSV
 *   <li>{@link Data360Connector} — World Bank Data360 API
 *   <li>{@link EcbConnector} — ECB SDW CSV
 * </ul>
 *
 * <p>Originál (read-only): {@code Bankoapp-main/backend/connectors/}
 */
public final class ConnectorPackage {

    private ConnectorPackage() {}
}
