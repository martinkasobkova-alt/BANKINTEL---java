/** Mapování odpovědi /catalog/preview na tvar řad pro AradView (period + value). */

import {
  buildSingleSeriesFromRows,
  buildTimeSeriesPivotFromRows,
  distinctSeriesIdsInRows,
  isCategoricalGroupField,
  shouldPivotTimeSeriesByDimension,
} from "@/lib/chartTimeSeriesPivot";
import { buildCsuAradDataFromCatalogPreview, isCsuCatalogPreview } from "@/lib/csuChartPreview";
import { inferNativeFrequencyFromChartRows } from "@/lib/chartFrequencyInfer";
import { normalizeSelectedIndicators } from "@/lib/catalogLivePreview";
import { geoLabelFromCode } from "@/lib/sourcePreviewGeo";

const GEO_GROUP_FIELDS = new Set(["geo", "ref_area", "country"]);

const NATIVE_FREQ_CODES = new Set(["D", "W", "M", "Q", "H", "Y"]);

function normalizeNativeFrequencyCode(raw) {
  const code = String(raw ?? "")
    .trim()
    .toUpperCase();
  if (!code) return "";
  if (code === "A") return "Y";
  return NATIVE_FREQ_CODES.has(code) ? code : "";
}

/** Nativní periodicita řady z API nebo odhad ze vzorků období (stejná logika jako metadata). */
export function resolveCatalogPreviewNativeFrequency(preview) {
  if (!preview || typeof preview !== "object") return "";

  const explicit = normalizeNativeFrequencyCode(
    preview?.chart_frequency || preview?.metadata?.chart_frequency || "",
  );
  if (explicit) return explicit;

  const mapped = mapCatalogPreviewRowsToArad(preview?.rows);
  return normalizeNativeFrequencyCode(inferNativeFrequencyFromChartRows(mapped, false));
}

export function mapCatalogPreviewRowsToArad(rows) {
  return buildSingleSeriesFromRows(rows);
}

function buildMultiSeriesAradData(preview, title) {
  const groupField = String(preview?.group_field || "").trim();
  const rawRows = Array.isArray(preview?.rows) ? preview.rows : [];
  const indicators = Array.isArray(preview?.indicators) ? preview.indicators : [];
  const unit = String(preview?.unit || preview?.metadata?.unit || preview?.metadata?.unit_label_cs || "").trim();
  const previewTitle = String(title || preview?.source?.name || "").trim();

  if (!groupField || !rawRows.length) return null;
  if (
    !shouldPivotTimeSeriesByDimension({
      rows: rawRows,
      groupField,
      unit,
      unitLabel: preview?.metadata?.unit_label_cs,
      title: previewTitle,
    })
  ) {
    return null;
  }

  let selected = normalizeSelectedIndicators(
    preview?.selected_indicators?.length
      ? preview.selected_indicators
      : preview?.selected_indicator
        ? [preview.selected_indicator]
        : [],
  );
  if (selected.length <= 1) {
    selected = distinctSeriesIdsInRows(rawRows, groupField);
  }
  if (selected.length <= 1) return null;

  const pivot = buildTimeSeriesPivotFromRows(rawRows, {
    groupField,
    seriesIds: selected,
    maxSeries: 12,
  });
  if (!pivot.multiSeries || pivot.rows.length < 2) return null;

  const indicatorById = new Map(
    indicators.map((ind) => [String(ind?.id || "").trim(), ind]).filter(([id]) => id),
  );
  const isGeoGroup =
    GEO_GROUP_FIELDS.has(groupField.toLowerCase()) || isCategoricalGroupField(groupField);
  const series = pivot.seriesIds.map((id) => {
    const ind = indicatorById.get(id);
    let label = String(ind?.name || "").trim();
    if (!label || label === id) {
      label = (isGeoGroup ? geoLabelFromCode(id) : "") || id;
    }
    return { key: id, label, name: label };
  });

  return {
    title: previewTitle || "Graf",
    rows: pivot.rows,
    multi_series: true,
    series,
    group_field: groupField,
    unit,
    frequency: resolveCatalogPreviewNativeFrequency(preview),
    view: "chart",
  };
}

export function buildAradDataFromCatalogPreview(preview, title = "") {
  if (isCsuCatalogPreview(preview)) {
    const csu = buildCsuAradDataFromCatalogPreview(preview, title);
    if (csu) return csu;
  }

  const multi = buildMultiSeriesAradData(preview, title);
  if (multi) return multi;

  const rows = mapCatalogPreviewRowsToArad(preview?.rows);
  const unit = String(preview?.unit || preview?.metadata?.unit || preview?.metadata?.unit_label_cs || "").trim();
  return {
    title: String(title || preview?.source?.name || "Graf").trim() || "Graf",
    rows,
    unit,
    frequency: resolveCatalogPreviewNativeFrequency(preview),
    view: "chart",
  };
}

/** Zdroje vyloučené z AradView grafu — IMF byl dočasně vyřazený, ale funkce
 * grafu (KPI, období, možnosti, fullscreen) mají v náhledech zůstat. */
const ARAD_VIEW_EXCLUDED_SOURCE_TYPES = new Set([]);

/** Lze vykreslit dashboardový AradView (frekvence, typ grafu, fullscreen…). */
export function canRenderAradCatalogChart(preview, previewError = "", sourceType = "") {
  if (!preview || String(previewError || "").trim()) return false;
  const st = String(sourceType || preview?.source?.source_type || "").trim().toLowerCase();
  if (st && ARAD_VIEW_EXCLUDED_SOURCE_TYPES.has(st)) return false;
  if (String(preview?.status || "").trim().toLowerCase() === "needs_filters") return false;
  const data = buildAradDataFromCatalogPreview(preview, "");
  if (data.multi_series) {
    return Array.isArray(data.rows) && data.rows.length >= 2 && Array.isArray(data.series) && data.series.length > 0;
  }
  if (String(data?.chart_data_mode || "").toLowerCase() === "latest") {
    return Array.isArray(data.rows) && data.rows.length >= 2;
  }
  return Array.isArray(data.rows) && data.rows.length >= 2;
}
