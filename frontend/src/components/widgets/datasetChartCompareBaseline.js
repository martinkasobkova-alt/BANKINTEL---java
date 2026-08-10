/**
 * Baseline metadata for chart compare modals — katalogové widgety používají různé klíče v configu.
 */

export function resolveDatasetChartCompareBaseline(widget, data) {
  const c = widget?.config && typeof widget.config === "object" ? widget.config : {};
  let sid = String(c.source_id || "").trim();
  if (!sid) {
    const ds = String(data?.dataset || c.dataset || "").trim();
    if (ds && !ds.toLowerCase().startsWith("catalog:")) sid = ds;
  }

  const dfs = c.dimension_filters && typeof c.dimension_filters === "object" ? c.dimension_filters : {};
  let sf = String(c.series_field || "").trim();
  if (!sf) {
    sf = String(c.group_field || data?.group_field || "").trim();
  }
  if (!sf) {
    const dfsKeys = Object.keys(dfs);
    if (dfsKeys.length === 1) sf = dfsKeys[0];
  }

  let sv = String(c.series_value || c.selected_indicator || data?.selected_indicator || "").trim();
  if (!sv && sf && dfs[sf] != null) {
    sv = String(dfs[sf]).trim();
  }

  return { sid, sf, sv };
}

export function resolveExternalCatalogCompareBaseline(widget, data) {
  const c = widget?.config && typeof widget.config === "object" ? widget.config : {};
  const catalog = String(c.catalog || c.source_type || "").trim().toLowerCase();
  const setId = String(c.set_id || c.dataset_id || "").trim();
  const mainIndicator = String(
    c.selected_indicator || c.series_value || data?.selected_indicator || ""
  ).trim();
  const groupField = String(c.group_field || data?.group_field || "").trim();
  const mainLabel = String(
    c.selected_indicator_label || data?.selected_indicator_label || mainIndicator
  ).trim();

  return {
    catalog,
    setId,
    mainIndicator,
    groupField,
    mainLabel,
    ready: Boolean(catalog && setId),
  };
}

export function isExternalCatalogWidgetEngine(engine) {
  const e = String(engine || "").toLowerCase();
  return e === "external_catalog_chart" || e === "external_catalog_view";
}
