import { buildCatalogChartMessagesShareUrl, buildCatalogShareContext } from "./catalogChartShare";
import { CATALOGS } from "./catalogDefinitions";

const DASHBOARD_SHAREABLE_WIDGET_TYPES = new Set([
  "external_catalog_chart",
  "chart",
  "computed_chart",
  "user_upload_chart",
  "uploaded_data_chart",
  "arad_view",
  "eurostat_view",
  "csu_view",
  "ecb_view",
  "fred_view",
  "imf_view",
  "oecd_view",
  "bis_view",
  "data360_view",
  "dataset_view",
  "computed_view",
]);

export function buildDashboardWidgetPageUrl(pageId, widgetId) {
  const pid = String(pageId || "").trim();
  const wid = String(widgetId || "").trim();
  if (!pid || !wid) return "";
  return `/my-dashboard?page=${encodeURIComponent(pid)}#widget-${encodeURIComponent(wid)}`;
}

export function isDashboardWidgetShareable(widget) {
  const t = String(widget?.engine_type || widget?.type || "")
    .trim()
    .toLowerCase();
  return DASHBOARD_SHAREABLE_WIDGET_TYPES.has(t);
}

/** Katalogová řada z konfigurace widgetu (external_catalog_chart apod.). */
export function extractCatalogFromWidgetConfig(widget) {
  const cfg = widget?.config && typeof widget.config === "object" ? widget.config : {};
  const catalogId = String(cfg.catalog || cfg.catalog_id || "").trim();
  const setId = String(cfg.set_id || "").trim();
  if (!catalogId || !setId) return null;
  const def = CATALOGS.find((c) => c.id === catalogId || c.sourceType === catalogId);
  return {
    catalogId: def?.id || catalogId,
    sourceType: def?.sourceType || catalogId,
    setId,
    indicatorId: String(cfg.selected_indicator || "").trim(),
  };
}

/**
 * Kontext pro tlačítka sdílení u widgetu na osobním dashboardu.
 * copyLink = odkaz na widget; v chatu preferuje katalog (náhled funguje i pro příjemce).
 */
export function buildWidgetShareContext({ widget, pageId, title, origin } = {}) {
  const wid = String(widget?.id || "").trim();
  const pid = String(pageId || "").trim();
  if (!wid || !pid || !isDashboardWidgetShareable(widget)) return null;

  const chartTitle = String(title || widget?.title || "Graf").trim();
  const pageLink = buildDashboardWidgetPageUrl(pid, wid);
  const baseOrigin =
    String(origin || "").trim() ||
    (typeof window !== "undefined" ? window.location.origin : "");
  const absoluteDashboardLink = pageLink && baseOrigin ? `${baseOrigin}${pageLink}` : pageLink;

  const catalog = extractCatalogFromWidgetConfig(widget);
  if (catalog) {
    const catalogCtx = buildCatalogShareContext({
      catalogId: catalog.catalogId,
      sourceType: catalog.sourceType,
      setId: catalog.setId,
      title: chartTitle,
      indicatorId: catalog.indicatorId,
      pageUrl: absoluteDashboardLink,
      origin: baseOrigin,
    });
    return {
      mode: "catalog",
      copyLink: absoluteDashboardLink || pageLink,
      messagesLink: buildCatalogChartMessagesShareUrl({
        title: chartTitle,
        sourceType: catalog.sourceType,
        setId: catalog.setId,
        pageUrl: catalogCtx.absolutePageLink || catalogCtx.pageLink,
      }),
      catalogShare: catalogCtx,
    };
  }

  return {
    mode: "dashboard",
    copyLink: absoluteDashboardLink || pageLink,
    messagesLink: buildCatalogChartMessagesShareUrl({
      title: chartTitle,
      sourceType: "dashboard_widget",
      setId: `dashboard_widget:${wid}`,
      pageUrl: absoluteDashboardLink || pageLink,
    }),
  };
}
