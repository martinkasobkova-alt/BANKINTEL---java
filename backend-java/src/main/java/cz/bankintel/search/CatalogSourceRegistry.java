package cz.bankintel.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CatalogSourceRegistry {

    public static final Set<String> FTS_PILOT_SOURCES =
            Set.of("eurostat", "imf", "ecb2", "data360", "fred", "arad", "csu", "bis", "oecd4", "commodities");

    public static final List<String> SUGGEST_FTS_SOURCES =
            List.of("eurostat", "fred", "ecb2", "imf", "data360", "arad", "csu", "bis", "oecd4", "commodities");

    public static final List<String> NON_FTS_SOURCES = List.of();

    public static final Set<String> BIG_FTS_SOURCES = Set.of("ecb2", "fred");

    /*
     * Named per-call-site source pools — single source of truth for the source lists that used to
     * be duplicated (with subtly different order/membership) across several classes. Each constant
     * below is a "view" for one specific call site; where the order or membership intentionally
     * differs from another view, that's preserved here rather than unified, to avoid behavior
     * changes. Call sites delegate to these constants instead of declaring their own copies.
     */

    /** {@code CatalogAvailabilityService} — sources tracked by the health/availability endpoint. */
    public static final List<String> AVAILABILITY_ALL_SOURCES =
            List.of("eurostat", "fred", "ecb2", "imf", "data360", "arad", "csu", "bis", "oecd", "commodities");

    /** {@code CatalogLikelySources} — fallback source order when no per-query rule matched. */
    public static final List<String> LIKELY_SOURCES_DEFAULT_ORDER =
            List.of("arad", "csu", "ecb2", "eurostat", "bis", "imf", "oecd4", "fred", "data360");

    /** {@code CatalogFollowupQuerySupport} — default source pool for contextual follow-up queries. */
    public static final List<String> FOLLOWUP_DEFAULT_SOURCE_POOL =
            List.of("arad", "csu", "eurostat", "ecb2", "bis", "imf", "oecd4", "fred", "data360");

    /** {@code CatalogSearchMetadataSidecar} — sources eligible for the Czech-query metadata rescue. */
    public static final Set<String> METADATA_SIDECAR_ALLOWED_SOURCES = Set.of(
            "arad", "ecb2", "eurostat", "csu", "imf", "bis", "oecd4", "fred", "data360", "commodities");

    /** {@code ExploreDiscoveryService} — sources probed for manager Explorer sector/macro discovery. */
    public static final List<String> EXPLORE_DISCOVERY_DEFAULT_SOURCES =
            List.of("arad", "ecb", "eurostat", "oecd4", "imf", "fred", "csu", "data360", "worldbank");

    /** {@code ExploreSectionBucketService} — sources bucketed as "macro" in the Explorer section split. */
    public static final Set<String> EXPLORE_MACRO_SOURCES = Set.of(
            "worldbank", "world_bank", "world_bank_data360", "imf", "ecb", "ecb2", "fred", "bis", "eurostat",
            "oecd", "csu", "arad");

    /** {@code CatalogRelatedSeriesService} — default sources when suggesting related series. */
    public static final List<String> RELATED_SERIES_DEFAULT_SOURCES =
            List.of("arad", "ecb", "eurostat", "oecd4", "imf", "worldbank", "data360", "csu");

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("arad", "ČNB - ARAD"),
            Map.entry("csu", "ČSÚ"),
            Map.entry("eurostat", "Eurostat"),
            Map.entry("ecb", "ECB"),
            Map.entry("ecb2", "ECB 2"),
            Map.entry("fred", "FRED"),
            Map.entry("worldbank", "World Bank"),
            Map.entry("data360", "World Bank Data360"),
            Map.entry("bis", "BIS"),
            Map.entry("imf", "IMF"),
            Map.entry("imf2", "IMF 2"),
            Map.entry("oecd", "OECD Data API"),
            Map.entry("oecd4", "OECD Data API"),
            Map.entry("stocks", "Akcie"),
            Map.entry("yahoo_finance", "Yahoo Finance — akcie / indexy"),
            Map.entry("alphavantage", "ALPHA VANTAGE — akcie / indexy"),
            Map.entry("tradingeconomics", "TradingEconomics"),
            Map.entry("commodities", "Komodity"));

    private static final Map<String, Integer> SOURCE_BONUS = Map.ofEntries(
            Map.entry("eurostat", 30),
            Map.entry("fred", 30),
            Map.entry("arad", 30),
            Map.entry("csu", 30),
            Map.entry("ecb2", 25),
            Map.entry("bis", 25),
            Map.entry("commodities", 20),
            Map.entry("stocks", 20),
            Map.entry("imf", 15),
            Map.entry("oecd", 15),
            Map.entry("data360", 0));

    private CatalogSourceRegistry() {}

    public static String label(String source) {
        if (source == null) {
            return "";
        }
        return LABELS.getOrDefault(source.toLowerCase(), source.toUpperCase());
    }

    /**
     * Je to zdroj, který katalogové vyhledávání zná?
     *
     * <p>{@link #normalizeSearchSource(String)} neznámý řetězec nechává být, takže dotaz na
     * neexistující zdroj dřív tiše vracel 0 výsledků místo hlášky „Neznámý zdroj…". Očekává
     * už normalizovanou hodnotu.
     */
    public static boolean isKnownSearchSource(String normalizedSource) {
        return normalizedSource != null && LABELS.containsKey(normalizedSource.trim().toLowerCase());
    }

    public static int sourceBonus(String source) {
        return SOURCE_BONUS.getOrDefault(source == null ? "" : source.toLowerCase(), 0);
    }

    public static String normalizeSearchSource(String source) {
        if (source == null) {
            return "";
        }
        String src = source.trim().toLowerCase();
        if ("ecb".equals(src)) {
            return "ecb2";
        }
        if ("world_bank_data360".equals(src)) {
            return "data360";
        }
        if ("oecd".equals(src)) {
            return "oecd4";
        }
        return src;
    }

    public static List<Map<String, Object>> statusSources() {
        return List.of(
                statusSource("arad", "ČNB - ARAD", "GET /api/arad/catalog", true, "full"),
                statusSource("csu", "ČSÚ", "GET /api/csu/catalog", true, "full"),
                statusSource("eurostat", "Eurostat", "GET /api/eurostat/catalog", true, "full"),
                statusSource("ecb", "ECB", "GET /api/ecb/browse-tree", false, "full"),
                statusSource("ecb2", "ECB 2", "GET /api/ecb2/browse-tree", false, "full"),
                statusSource("fred", "FRED", "GET /api/fred/catalog", true, "limited"),
                statusSource("worldbank", "World Bank", "GET /api/worldbank/catalog", true, "limited"),
                statusSource("data360", "World Bank Data360", "GET /api/data360/catalog", true, "none"),
                statusSource("bis", "BIS", "GET /api/bis/catalog", true, "limited"),
                statusSource("imf2", "IMF 2", "GET /api/imf2/browse-tree", false, "full"),
                statusSource("imf", "IMF", "GET /api/imf/browse-tree", true, "full"),
                statusSource("oecd", "OECD Data API", "GET /api/oecd/catalog", true, "partial"),
                statusSource("yahoo_finance", "Yahoo Finance — akcie / indexy", "GET /api/yahoo_finance/catalog", true, "full"));
    }

    private static Map<String, Object> statusSource(
            String id, String label, String endpoint, boolean searchSupported, String browseSupported) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("label", label);
        row.put("catalog_endpoint", endpoint);
        row.put("available", true);
        row.put("beta", !List.of("arad", "csu", "eurostat").contains(id));
        row.put("requires_api_key", "fred".equals(id));
        row.put("browse_supported", browseSupported);
        row.put("search_supported", searchSupported);
        row.put("preview_supported", true);
        return row;
    }
}
