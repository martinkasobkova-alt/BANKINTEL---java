import { sanitizeEurostatQueryParams } from "@/lib/eurostatAddSourcePayload";

/** Řádky v katalogu Komodity mohou mít jiný `source_type` než definice katalogu (IMF, BIS). */
const ROW_SOURCE_DEF_OVERRIDES = {
  imf: {
    id: "imf",
    sourceType: "imf",
    label: "IMF",
    addPath: "/imf/add-source",
  },
  bis: {
    id: "bis",
    sourceType: "bis",
    label: "BIS",
    addPath: "/bis/catalog/add-source",
  },
  yahoo_finance: {
    id: "yahoo_finance",
    sourceType: "yahoo_finance",
    label: "Yahoo Finance",
    addPath: "/yahoo_finance/catalog/add-source",
  },
};

/**
 * Pro hybridní katalogy (např. Komodity) vrátí definici dle skutečného typu řady.
 * @param {{ sourceType?: string, addPath?: string, id?: string, label?: string }} def
 * @param {{ source_type?: string, provider?: string }} row
 */
export function resolveCatalogRowDef(def, row) {
  if (!def || !row) return def;
  const rowSt = String(row.source_type || row.provider || "").trim().toLowerCase();
  const defSt = String(def.sourceType || "").trim().toLowerCase();
  if (rowSt && rowSt !== defSt) {
    const override = ROW_SOURCE_DEF_OVERRIDES[rowSt];
    if (override) return { ...def, ...override };
  }
  return def;
}

/** @deprecated alias */
export const resolveCatalogPreviewDef = resolveCatalogRowDef;

/**
 * Kanonické set_id pro preview / uložení widgetu.
 * Sidecar vyhledávání (Eurostat) často vrací series_id delší než dataset_id — preview i widget musí použít dataset.
 */
export function resolveCatalogRowSetId(row, previewData = null) {
  if (!row || typeof row !== "object") return "";
  const setId = String(row.set_id ?? row.code ?? row.series_id ?? "").trim();
  const datasetId = String(row.dataset_id || row.dataset_code || "").trim();
  const fromPreview = String(
    previewData?.dataset_id || previewData?.source?.set_id || previewData?.metadata?.dataset_id || "",
  ).trim();

  if (setId.includes("/") && setId.includes(".")) {
    return setId;
  }

  if (fromPreview && fromPreview !== setId) {
    const pl = fromPreview.toLowerCase();
    const sl = setId.toLowerCase();
    if (!setId || sl.startsWith(`${pl}_`) || sl.startsWith(`${pl}:`)) {
      return fromPreview;
    }
  }

  if (datasetId && setId && datasetId.toLowerCase() !== setId.toLowerCase()) {
    const dl = datasetId.toLowerCase();
    const sl = setId.toLowerCase();
    if (sl.startsWith(`${dl}_`) || sl.startsWith(`${dl}:`)) {
      return datasetId;
    }
  }

  return setId || datasetId;
}

function isEcbFlowSeriesSetId(setId) {
  const sid = String(setId || "").trim();
  return sid.includes("/") && sid.includes(".");
}

function nestedRowString(row, key) {
  return String(
    row?.[key]
      || row?.query_params?.[key]
      || row?.preview_request_payload?.[key]
      || row?.preview_request_payload?.query_params?.[key]
      || row?.preview_payload?.[key]
      || row?.preview_payload?.query_params?.[key]
      || "",
  ).trim();
}

function isEcbCuratedAliasSetId(setId) {
  return /^ecb_[a-z0-9_]{3,}$/i.test(String(setId || "").trim());
}

/**
 * Tělo požadavku pro /api/catalog/preview a /api/catalog/download (živý náhled z katalogu).
 */

export function buildCatalogPreviewBody(def, row, wbCountry = "CZE") {
  const effectiveDef = resolveCatalogRowDef(def, row);
  const canonicalSetId = resolveCatalogRowSetId(row);
  const body = {
    source_type: effectiveDef.sourceType,
    set_id: canonicalSetId,
    name: row.name,
  };
  if (effectiveDef.sourceType === "worldbank") {
    const c = String(row.wb_country || row.query_params?.country || "").trim();
    if (c) body.country = c;
  }
  if (def.needsCountry && !String(body.country || "").trim()) body.country = wbCountry;
  if (effectiveDef.sourceType === "ecb") {
    if (!isEcbFlowSeriesSetId(canonicalSetId)) {
      let country = (nestedRowString(row, "ecb_country") || nestedRowString(row, "country")).toUpperCase();
      let indicator = String(
        row.ecb_indicator_id || row.query_params?.ecb_indicator_id || row.query_params?.indicator || ""
      ).trim();
      const curatedMatch = /^ecb:([A-Z0-9]{2}):(.+)$/i.exec(String(row.set_id ?? "").trim());
      if (curatedMatch) {
        country = country || curatedMatch[1].toUpperCase();
        indicator = indicator || curatedMatch[2];
      }
      if (country && indicator) {
        body.set_id = `ecb:${country}:${indicator}`;
        body.country = country;
        body.query_params = { country, ecb_indicator_id: indicator, indicator };
        return body;
      }
      if (country && isEcbCuratedAliasSetId(canonicalSetId)) {
        body.country = country;
        body.query_params = {
          ...(row.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)
            ? row.query_params
            : {}),
          country,
        };
        return body;
      }
    }
    const qp = row.query_params || row.ecb_query_params;
    if (qp && typeof qp === "object" && !Array.isArray(qp)) {
      body.query_params = { ...qp };
    }
    const ep = row.ecb_params || row.params;
    if (ep && typeof ep === "object" && !Array.isArray(ep)) {
      body.ecb_params = { ...ep };
    }
    if (row.flowRef && row.seriesKey) {
      body.flowRef = String(row.flowRef);
      body.seriesKey = String(row.seriesKey);
    }
  }
  if (effectiveDef.sourceType === "eurostat") {
    // Předáme query_params z výsledku vyhledávání (geo, dimenze detekované AI).
    // buildCatalogPreviewRequestBody pak při změně výběru toto přepíše přes applyEurostatCountrySelection.
    const qp = row.query_params;
    if (qp && typeof qp === "object" && !Array.isArray(qp)) {
      body.query_params = sanitizeEurostatQueryParams({ ...qp });
    }
  }
  if (effectiveDef.sourceType === "oecd") {
    const qp = row.query_params;
    if (qp && typeof qp === "object" && !Array.isArray(qp)) {
      body.query_params = { ...qp };
    }
    if (Array.isArray(row.oecd_alternative_set_ids) && row.oecd_alternative_set_ids.length) {
      body.oecd_alternative_set_ids = row.oecd_alternative_set_ids
        .map((x) => String(x || "").trim())
        .filter(Boolean);
    }
    if (Array.isArray(row.oecd_all_set_ids) && row.oecd_all_set_ids.length) {
      body.oecd_all_set_ids = row.oecd_all_set_ids
        .map((x) => String(x || "").trim())
        .filter(Boolean);
    }
  }
  if (effectiveDef.sourceType === "world_bank_data360" && row.query_params && typeof row.query_params === "object") {
    body.query_params = { ...row.query_params };
  }
  if (effectiveDef.sourceType === "csu") {
    // Předáme kód datové sady (sada) — backend konektor pak použije POST endpoint
    // sady s prázdnými filtry a získá VŠECHNA dostupná časová období.
    const dc = String(row.dataset_code || "").trim();
    if (dc) body.dataset_code = dc;
    const qp = row.query_params;
    if (qp && typeof qp === "object" && !Array.isArray(qp)) {
      body.query_params = { ...qp };
    }
    if (Array.isArray(row.csu_filters) && row.csu_filters.length) {
      body.query_params = {
        ...(body.query_params && typeof body.query_params === "object" ? body.query_params : {}),
        csu_filters: row.csu_filters,
      };
    }
    const ind = String(row.selected_indicator || "").trim();
    if (ind) body.selected_indicator = ind;
    if (Array.isArray(row.selected_indicators) && row.selected_indicators.length) {
      body.selected_indicators = row.selected_indicators.map((x) => String(x || "").trim()).filter(Boolean);
    }
  }
  if (effectiveDef.sourceType === "arad") {
    const ind = String(row.indicator_id || row.selected_indicator || "").trim();
    if (ind) body.selected_indicator = ind;
    const label = String(row.title || row.name || "").trim();
    if (label) body.name = label;
  }
  if (effectiveDef.sourceType === "yahoo_finance") {
    const qp = {
      ...(row.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)
        ? row.query_params
        : {}),
    };
    const sid = String(row.set_id ?? row.ticker ?? "").trim();
    if (sid && !String(qp.ticker ?? "").trim()) {
      qp.ticker = sid;
    }
    if (!String(qp.interval ?? "").trim()) {
      qp.interval = "1d";
    }
    if (!String(qp.start_date ?? "").trim()) {
      qp.start_date = "1990-01-01";
    }
    if (Object.keys(qp).length) {
      body.query_params = qp;
    }
  }
  if (effectiveDef.sourceType === "alphavantage") {
    const sid = String(row.set_id ?? "").trim();
    const qp = {
      ...(row.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)
        ? row.query_params
        : {}),
    };
    const m = /^__av_sym__([A-Za-z0-9.-]{1,20})$/i.exec(sid);
    if (m && !String(qp.symbol ?? "").trim()) {
      qp.symbol = m[1].toUpperCase();
    }
    const fn = String(row.av_function || row.function || "").trim();
    if (fn && !String(qp.function ?? "").trim()) {
      qp.function = fn.toUpperCase();
    }
    if (Object.keys(qp).length) {
      body.query_params = qp;
    }
  }
  if (effectiveDef.sourceType === "tradingeconomics") {
    const qp = row.query_params;
    if (qp && typeof qp === "object" && !Array.isArray(qp)) {
      body.query_params = { ...qp };
    }
  }
  if (effectiveDef.sourceType === "eurostat") {
    const qp = sanitizeEurostatQueryParams(row.query_params);
    if (Object.keys(qp).length) body.query_params = qp;
    const geo = String(row.eurostat_geo || "").trim().toUpperCase();
    if (geo) body.geo = geo;
    const geoScope = String(row.geo_scope || row.eurostat_geo_scope || "").trim();
    if (geoScope) body.geo_scope = geoScope;
  }
  if (effectiveDef.sourceType === "imf") {
    const qp = {
      ...(row.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)
        ? row.query_params
        : {}),
    };
    const cc = String(row.imf_country || qp.imf_country || "").trim();
    const flow = String(row.imf_flow || qp.imf_flow || "").trim();
    const ind = String(row.imf_indicator || qp.imf_indicator || "").trim();
    if (cc) qp.imf_country = cc;
    if (flow) qp.imf_flow = flow;
    if (ind) qp.imf_indicator = ind;
    const freq = String(row.imf_frekvence || qp.imf_frekvence || row.frekvence || "").trim();
    if (freq) qp.imf_frekvence = freq;
    if (Object.keys(qp).length) body.query_params = qp;
  }
  if (effectiveDef.sourceType === "worldbank_pink_sheet") {
    const sid = String(row.set_id || "").trim();
    const kind =
      row.query_params?.kind ||
      (sid.startsWith("FCST|") ? "forecast" : "actual");
    body.query_params = {
      kind,
      pink_sheet_code: sid,
      commodity_code: sid,
    };
  }
  return body;
}
