/**
 * Widget `engine_type` / `type` values routed to the shared time-series view (`AradView.jsx`)
 * via `WidgetRenderer` (generic `records` pipeline, `_resolve_dataset_view` on the backend).
 */
export const DATASET_CHART_ENGINE_TYPES = new Set([
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
  "oecd_view",
  "external_catalog_view",
  "external_catalog_chart",
]);
