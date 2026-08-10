import { resolveCatalogDefForSource } from "@/lib/archiveChartLink";
import { resolveCatalogRowDef } from "@/lib/catalogPreviewBody";

function text(value) {
  return String(value ?? "").trim();
}

function objectOrEmpty(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

/**
 * Converts a Manager Explorer chart series back to the canonical catalog row contract.
 * Source-specific preview parameters come from compare_ref, which is created by the backend
 * from the same verified preview request that supplied the report chart.
 */
export function buildExploreInteractiveCatalogContext(series) {
  if (!series || typeof series !== "object") return null;

  const compareRef = objectOrEmpty(series.compareRef || series.compare_ref);
  const sourceType = text(compareRef.source_type || series.source || series.source_type).toLowerCase();
  const baseDef = resolveCatalogDefForSource(sourceType);
  const setId = text(compareRef.set_id || series.setId || series.set_id || series.seriesId || series.series_id);
  if (!baseDef || !setId) return null;

  const queryParams = objectOrEmpty(compareRef.query_params);
  const indicatorId = text(
    compareRef.selected_indicator
      || compareRef.indicator_id
      || compareRef.ecb_indicator_id
      || queryParams.selected_indicator
      || queryParams.indicator_id
      || queryParams.ecb_indicator_id
  );
  const geo = text(
    series.primaryCountryCode
      || series.primary_country_code
      || series.geo
      || compareRef.primary_country_code
      || compareRef.country
      || queryParams.geo
      || queryParams.REF_AREA
      || queryParams.country
  ).toUpperCase();
  const name = text(series.name || series.labelCs || series.label_cs || setId);

  const row = {
    ...compareRef,
    source_type: baseDef.sourceType || sourceType,
    set_id: setId,
    name,
    title: name,
    query_params: { ...queryParams },
    unit: text(series.unit || compareRef.unit),
  };
  if (indicatorId) {
    row.indicator_id = indicatorId;
    row.selected_indicator = indicatorId;
  }
  if (geo) {
    row.geo = geo;
    row.ref_area = geo;
  }

  return {
    def: resolveCatalogRowDef(baseDef, row),
    row,
  };
}

