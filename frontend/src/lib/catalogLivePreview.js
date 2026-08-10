import api from "@/lib/api";
import { buildCatalogPreviewBody, resolveCatalogRowDef } from "@/lib/catalogPreviewBody";
import { sanitizeEurostatQueryParams } from "@/lib/eurostatAddSourcePayload";
import { applyData360GeoQueryParams } from "@/lib/exploreCompareGeo";
import { normalizePreviewPayload } from "@/lib/previewNormalizer";
import {
  applyEurostatCountrySelection,
  applyEurostatFigaroFixedFilters,
  extractCountryCodesFromFilters,
} from "@/lib/sourcePreviewCountry";
import { applyIndustryLinkedFilters } from "@/lib/sourcePreviewIndustryFilters";

export function normalizeSelectedIndicators(input) {
  if (!Array.isArray(input)) return [];
  const out = [];
  const seen = new Set();
  for (const raw of input) {
    const id = String(raw ?? "").trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

function isPreviewMetadataFilterKey(key) {
  const normalized = String(key || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "");
  return normalized === "querymode" || normalized === "geoscope" || normalized === "lasttimeperiod";
}

export function buildCatalogPreviewRequestBody({
  def,
  row,
  previewData = null,
  dimensionFilters = null,
  geoValues = [],
  indicatorId = null,
  indicatorIds = [],
}) {
  const effectiveDef = resolveCatalogRowDef(def, row);
  const body = buildCatalogPreviewBody(effectiveDef, row);
  const selectedGeo = Array.isArray(geoValues)
    ? [...new Set(geoValues.map((x) => String(x || "").trim().toUpperCase()).filter(Boolean))]
    : [];
  const selectedDimensionFilters =
    dimensionFilters && typeof dimensionFilters === "object" && !Array.isArray(dimensionFilters)
      ? Object.fromEntries(
          Object.entries(dimensionFilters).filter(
            ([k, v]) => k && v != null && k !== "indicator_id" && !isPreviewMetadataFilterKey(k)
          )
        )
      : {};
  const hasSelectedDimensionFilters = Object.keys(selectedDimensionFilters).length > 0;
  const normalizedIds = normalizeSelectedIndicators(indicatorIds);
  const selectedPrimary = String(
    (indicatorId != null && String(indicatorId).trim() !== "" ? indicatorId : null) ||
      body?.selected_indicator ||
      row?.indicator_id ||
      row?.selected_indicator ||
      normalizedIds[0] ||
      ""
  ).trim();
  const bodySelectedMany = normalizeSelectedIndicators(body?.selected_indicators);
  const selectedMany = normalizedIds.length
    ? normalizedIds
    : bodySelectedMany.length
      ? bodySelectedMany
      : selectedPrimary
        ? [selectedPrimary]
        : [];

  const eurostatDims =
    previewData?.available_dimensions ||
    previewData?.metadata?.available_dimensions ||
    row?.available_dimensions ||
    {};

  if (effectiveDef.sourceType === "eurostat" && selectedGeo.length > 0) {
    body.query_params = applyEurostatCountrySelection(
      body.query_params,
      selectedGeo,
      eurostatDims,
      selectedDimensionFilters
    );
  } else if (
    (effectiveDef.sourceType === "ecb" ||
      effectiveDef.id === "ecb2" ||
      effectiveDef.sourceType === "world_bank_data360") &&
    selectedGeo.length > 0
  ) {
    if (effectiveDef.sourceType === "world_bank_data360") {
      body.query_params = applyData360GeoQueryParams(body.query_params, selectedGeo);
    } else {
      const qp = {
        ...(body.query_params && typeof body.query_params === "object" ? body.query_params : {}),
        geo: selectedGeo,
      };
      qp.REF_AREA = selectedGeo.length === 1 ? selectedGeo[0] : selectedGeo;
      body.query_params = qp;
    }
  } else if (effectiveDef.sourceType === "imf" && selectedGeo.length > 0) {
    // Srovnání zemí u IMF — backend přepíše COUNTRY část SDMX klíče (CZE+AUT.…).
    body.geo = selectedGeo;
  } else if (effectiveDef.sourceType === "oecd" && selectedGeo.length > 0) {
    // Srovnání zemí u OECD (offline mirror) — backend načte stejný ukazatel pro každou zemi.
    body.query_params = {
      ...(body.query_params && typeof body.query_params === "object" ? body.query_params : {}),
      geo: selectedGeo,
    };
  } else if (effectiveDef.sourceType === "worldbank" && selectedGeo.length > 0) {
    // World Bank: country je samostatný parametr (ne query_params)
    body.country = selectedGeo[0];
  } else if (effectiveDef.sourceType === "bis") {
    // Srovnání zemí u BIS — backend přepíše ref-area část series key pro každou zemi.
    // Bez výběru pošleme zemi řádku, aby backend orazítkoval řádky polem `geo`.
    const bisGeo = selectedGeo.length
      ? selectedGeo
      : [String(row?.ref_area || "").trim().toUpperCase()].filter(Boolean);
    if (bisGeo.length) {
      body.query_params = {
        ...(body.query_params && typeof body.query_params === "object" ? body.query_params : {}),
        geo: bisGeo,
      };
    }
  }

  if (hasSelectedDimensionFilters) {
    body.dimension_filters = selectedDimensionFilters;
  }

  if (
    hasSelectedDimensionFilters &&
    effectiveDef.sourceType !== "csu" &&
    effectiveDef.sourceType !== "worldbank"
  ) {
    body.query_params = {
      ...(body.query_params && typeof body.query_params === "object" ? body.query_params : {}),
      ...selectedDimensionFilters,
    };
    delete body.query_params.indicator_id;
    if (effectiveDef.sourceType === "eurostat") {
      body.query_params = applyIndustryLinkedFilters(
        applyEurostatFigaroFixedFilters(body.query_params, eurostatDims),
        eurostatDims,
        String(row?.set_id || body.set_id || "").trim()
      );
    }
  }

  if (effectiveDef.sourceType === "csu" && hasSelectedDimensionFilters) {
    body.dimension_filters = selectedDimensionFilters;
  }

  if (effectiveDef.sourceType === "eurostat" && selectedGeo.length > 0) {
    delete body.geo_scope;
    if (body.query_params && typeof body.query_params === "object") {
      delete body.query_params.geo_scope;
    }
  }

  if (effectiveDef.sourceType === "eurostat") {
    body.query_params = sanitizeEurostatQueryParams(body.query_params);
  }

  if (selectedPrimary) body.selected_indicator = selectedPrimary;
  if (selectedMany.length) body.selected_indicators = selectedMany;
  return body;
}

/** Výchozí geo pro makro řadu — z query_params, browse kontextu nebo pole geo na řádku. */
export function resolveInitialGeoFromRow(row) {
  const fromFilters = extractCountryCodesFromFilters(row?.query_params);
  if (fromFilters.length) return fromFilters;
  const d360 = String(row?.data360_country || "").trim().toUpperCase();
  if (d360) return [d360];
  const single = String(row?.geo || row?.eurostat_geo || row?.ref_area || "").trim().toUpperCase();
  return single ? [single] : [];
}

export async function fetchCatalogLivePreview(options) {
  const effectiveDef = resolveCatalogRowDef(options.def, options.row);
  if (!effectiveDef?.sourceType || !String(options.row?.set_id ?? "").trim()) {
    throw new Error("Chybí identifikace katalogové řady pro živý náhled.");
  }
  const geoValues =
    Array.isArray(options.geoValues) && options.geoValues.length
      ? options.geoValues
      : resolveInitialGeoFromRow(options.row);
  const body = buildCatalogPreviewRequestBody({ ...options, geoValues });
  const { data } = await api.post("/catalog/preview", body, { timeout: 45_000 });
  return normalizePreviewPayload(data, effectiveDef.sourceType || "");
}

export { extractCountryCodesFromFilters };
