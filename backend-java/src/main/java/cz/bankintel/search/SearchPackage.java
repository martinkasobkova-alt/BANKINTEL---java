package cz.bankintel.search;

/**
 * Katalogové vyhledávání a náhledy — Java port Python modulů v {@code backend/routes/catalog_*} a
 * {@code backend/services/catalog_*}.
 *
 * <h2>Vyhledávání</h2>
 * <ul>
 *   <li>{@link CatalogIndexStore} — SQLite FTS5 + JSONL indexy z disku
 *   <li>{@link CatalogSuggestService} — GET /api/catalog/suggest (autocomplete)
 *   <li>{@link CatalogClassicSearchService} — POST /api/catalog/search
 *   <li>{@link CatalogDeepSearchService} — POST /api/catalog/deep-search (AI)
 *   <li>{@link CatalogStatusService} — GET /api/catalog/status
 * </ul>
 *
 * <h2>Náhled grafu (preview)</h2>
 * <ul>
 *   <li>{@link CatalogPreviewService} — tenká fasáda pro POST /api/catalog/preview
 *   <li>{@link CatalogPreviewOrchestrator} — sestaví source, zavolá konektor, vrátí rows
 *   <li>{@link cz.bankintel.connector.InMemorySourceBuilder} — dočasný source dict z payloadu
 *   <li>{@link CatalogSeriesFilter} — filtr ukazatelů / geo (jako Python {@code _apply_catalog_series_filter})
 *   <li>{@link PreviewResponseBuilder} — tvar JSON odpovědi pro frontend
 * </ul>
 */
public final class SearchPackage {
    private SearchPackage() {}
}
