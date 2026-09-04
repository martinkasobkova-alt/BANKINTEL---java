package cz.bankintel.service.homepage;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.service.homepage.resolver.AdminChartWidgetResolver;
import cz.bankintel.service.homepage.resolver.CatalogLiveWidgetResolver;
import cz.bankintel.service.homepage.resolver.ComputedViewWidgetResolver;
import cz.bankintel.service.homepage.resolver.ExternalCatalogChartWidgetResolver;
import cz.bankintel.service.homepage.resolver.KpiWidgetResolver;
import cz.bankintel.service.homepage.resolver.RssMonitoringWidgetResolver;
import cz.bankintel.service.homepage.resolver.SourceRecordsWidgetResolver;
import cz.bankintel.service.homepage.resolver.UserUploadChartWidgetResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class WidgetRenderService {

    private static final Set<String> DATASET_VIEWS = Set.of(
            "dataset_view",
            "eurostat_view",
            "csu_view",
            "ecb_view",
            "fred_view",
            "alphavantage_view",
            "worldbank_view",
            "world_bank_data360_view",
            "bis_view",
            "imf_view",
            "oecd_view");

    private final KpiWidgetResolver kpiWidgetResolver;
    private final SourceRecordsWidgetResolver sourceRecordsWidgetResolver;
    private final ComputedViewWidgetResolver computedViewWidgetResolver;
    private final AdminChartWidgetResolver adminChartWidgetResolver;
    private final UserUploadChartWidgetResolver userUploadChartWidgetResolver;
    private final CatalogLiveWidgetResolver catalogLiveWidgetResolver;
    private final ExternalCatalogChartWidgetResolver externalCatalogChartWidgetResolver;
    private final RssMonitoringWidgetResolver rssMonitoringWidgetResolver;

    public WidgetRenderService(
            KpiWidgetResolver kpiWidgetResolver,
            SourceRecordsWidgetResolver sourceRecordsWidgetResolver,
            ComputedViewWidgetResolver computedViewWidgetResolver,
            AdminChartWidgetResolver adminChartWidgetResolver,
            UserUploadChartWidgetResolver userUploadChartWidgetResolver,
            CatalogLiveWidgetResolver catalogLiveWidgetResolver,
            ExternalCatalogChartWidgetResolver externalCatalogChartWidgetResolver,
            RssMonitoringWidgetResolver rssMonitoringWidgetResolver) {
        this.kpiWidgetResolver = kpiWidgetResolver;
        this.sourceRecordsWidgetResolver = sourceRecordsWidgetResolver;
        this.computedViewWidgetResolver = computedViewWidgetResolver;
        this.adminChartWidgetResolver = adminChartWidgetResolver;
        this.userUploadChartWidgetResolver = userUploadChartWidgetResolver;
        this.catalogLiveWidgetResolver = catalogLiveWidgetResolver;
        this.externalCatalogChartWidgetResolver = externalCatalogChartWidgetResolver;
        this.rssMonitoringWidgetResolver = rssMonitoringWidgetResolver;
    }

    public Map<String, Object> resolve(String widgetType, Map<String, Object> config, UserEntity user) {
        String type = widgetType != null ? widgetType : "";
        Map<String, Object> cfg = config != null ? config : Map.of();
        String userId = user != null ? user.getId() : null;

        return switch (type) {
            // "markdown" (homepage editor) a "text"/"note" (osobní dashboard) jsou config-only
            // textové widgety — obsah je v config.content, žádná data se neresolvují. Bez "text"/
            // "note" tady spadly do defaultu a vracely chybu "Data widgetu zatím nejsou dostupná".
            case "markdown", "text", "note" -> Map.of("content", stringOrEmpty(cfg.get("content")));
            case "ad" -> adPayload(cfg);
            case "kpi_active_sources", "kpi_datasets", "kpi_records", "kpi_sync_errors" -> kpiWidgetResolver.resolve(type);
            case "arad_view" -> sourceRecordsWidgetResolver.resolveAradView(cfg, userId);
            case "computed_view" -> computedViewWidgetResolver.resolve(cfg, user);
            case "chart_net_result", "chart_dataset_distribution", "table_recent_syncs", "dataset_table", "dataset_chart", "formula_chart" ->
                    adminChartWidgetResolver.resolve(type, cfg);
            case "user_upload_chart", "uploaded_data_chart" -> userUploadChartWidgetResolver.resolve(cfg, user);
            case "external_catalog_chart" -> externalCatalogChartWidgetResolver.resolve(cfg, user);
            case "rss_monitoring" -> rssMonitoringWidgetResolver.resolve(cfg, user);
            default -> resolveDatasetFamily(type, cfg, user);
        };
    }

    private Map<String, Object> resolveDatasetFamily(String type, Map<String, Object> cfg, UserEntity user) {
        if (DATASET_VIEWS.contains(type)) {
            Map<String, Object> stored = sourceRecordsWidgetResolver.resolveDatasetView(cfg, type, user);
            if (!stored.containsKey("error")) {
                return stored;
            }
            Map<String, Object> live = catalogLiveWidgetResolver.tryLivePreview(cfg, type);
            if (!live.isEmpty()) {
                return live;
            }
            return stored;
        }
        return Map.of("error", "Data widgetu zatím nejsou dostupná v Java backendu.");
    }

    public Map<String, Object> buildRenderedWidget(
            String id,
            String widgetType,
            String title,
            String width,
            Integer rowSpan,
            Map<String, Object> config,
            UserEntity user) {
        String normalizedType = "uploaded_data_chart".equals(widgetType) ? "user_upload_chart" : widgetType;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", normalizedType);
        out.put("title", title != null ? title : "");
        out.put("width", width != null ? width : "full");
        out.put("rowSpan", rowSpan);
        out.put("config", config != null ? config : Map.of());
        out.put("data", resolve(widgetType, config, user));
        return out;
    }

    public Map<String, Object> renderTransientWidget(Map<String, Object> payload, UserEntity user) {
        String type = stringOrEmpty(payload.get("type"));
        Map<String, Object> cfg = payload.get("config") instanceof Map<?, ?> m ? castMap(m) : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", stringOrEmpty(payload.get("id")).isBlank() ? "ad-hoc" : stringOrEmpty(payload.get("id")));
        out.put("type", type);
        out.put("title", stringOrEmpty(payload.get("title")));
        out.put("width", stringOrEmpty(payload.get("width")).isBlank() ? "full" : stringOrEmpty(payload.get("width")));
        out.put("rowSpan", payload.get("rowSpan"));
        out.put("config", cfg);
        Map<String, Object> data = resolve(type, cfg, user);
        out.put("data", data);
        out.put("from_snapshot", false);
        out.put("is_stale", false);
        if (data instanceof Map<?, ?> map && map.containsKey("error")) {
            out.put("refresh_error", String.valueOf(map.get("error")));
        }
        return out;
    }

    private static Map<String, Object> adPayload(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", stringOrEmpty(cfg.get("ad_kind")).isEmpty() ? "image" : stringOrEmpty(cfg.get("ad_kind")));
        out.put("image_url", stringOrEmpty(cfg.get("image_url")));
        out.put("link_url", stringOrEmpty(cfg.get("link_url")));
        out.put("alt", stringOrEmpty(cfg.get("alt")));
        out.put("content", stringOrEmpty(cfg.get("content")));
        out.put("html", stringOrEmpty(cfg.get("html")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String stringOrEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
