import { pickPreviewFields } from "@/lib/pickPreviewFields";
import { sanitizeEurostatQueryParams } from "@/lib/eurostatAddSourcePayload";
import {
  mapCompareCountryToImfCode,
  mapCompareCountryToWorldBankCode,
} from "@/lib/exploreCompareGeo";

const ECB_TO_EUROSTAT_GEO = {
  U6: "EU27_2020",
  U2: "EA20",
  EU: "EU27_2020",
  EA: "EA20",
  EU27: "EU27_2020",
  EU28: "EU27_2020",
  EA19: "EA20",
  EA20: "EA20",
};

export function mapEcbCountryCodeToEurostatGeo(code) {
  const normalized = String(code || "").trim().toUpperCase();
  if (!normalized) return "";
  return ECB_TO_EUROSTAT_GEO[normalized] || normalized;
}

function periodSortValue(value) {
  const s = String(value ?? "").trim();
  if (!s) return 0;
  const ym = s.match(/^(\d{4})[-/.](\d{1,2})$/);
  if (ym) return Number(`${ym[1]}${String(ym[2]).padStart(2, "0")}00`);
  const yq = s.match(/^(\d{4})[- ]?Q([1-4])$/i) || s.match(/^Q([1-4])[- ]?(\d{4})$/i);
  if (yq) {
    const year = yq[2]?.length === 4 ? yq[2] : yq[1];
    const q = yq[2]?.length === 4 ? yq[1] : yq[2];
    return Number(`${year}${String(Number(q) * 3).padStart(2, "0")}00`);
  }
  const y = s.match(/^(\d{4})$/);
  if (y) return Number(`${y[1]}0000`);
  const t = Date.parse(s);
  if (!Number.isNaN(t)) return t;
  return s.toLowerCase();
}

export function compareChartPeriods(a, b) {
  const av = periodSortValue(a);
  const bv = periodSortValue(b);
  if (typeof av === "number" && typeof bv === "number") return av - bv;
  return String(av).localeCompare(String(bv), "cs");
}

/**
 * Tělo pro /api/catalog/preview — stejný indikátor, jiná země (jako „+ srovnat“ v katalogu).
 */
export function buildExploreComparePreviewBody(compareRef, countryCode, options = {}) {
  if (!compareRef || typeof compareRef !== "object") return null;
  const code = String(countryCode || "").trim().toUpperCase();
  if (!code) return null;

  const src = String(compareRef.source_type || "").trim().toLowerCase();
  const mode = String(compareRef.compare_mode || "").trim().toLowerCase();
  const body = {
    source_type: src,
    set_id: String(compareRef.set_id || "").trim(),
    query_params: { ...(compareRef.query_params || {}) },
  };

  if (src === "eurostat" || mode === "eurostat") {
    const qp = sanitizeEurostatQueryParams({ ...(compareRef.query_params || {}) });
    const eurostatGeo = String(
      options.eurostatGeo ||
        compareRef?.eurostat_geo_by_code?.[code] ||
        mapEcbCountryCodeToEurostatGeo(code)
    )
      .trim()
      .toUpperCase();
    if (!eurostatGeo) return null;
    qp.geo = eurostatGeo;
    delete qp.REF_AREA;
    delete qp.ref_area;
    body.query_params = qp;
    body.geo = eurostatGeo;
    return body;
  }

  if (mode === "imf" || src === "imf") {
    const imfCode = mapCompareCountryToImfCode(code);
    if (!imfCode) return null;
    const flow = String(compareRef.imf_flow || body.query_params.imf_flow || "WEO").trim().toUpperCase();
    const freq = String(compareRef.imf_frekvence || body.query_params.imf_frekvence || "A").trim().toUpperCase();
    const suffix = String(
      compareRef.imf_indicator_suffix
        || compareRef.imf_indicator
        || body.query_params.imf_indicator
        || ""
    )
      .trim()
      .toUpperCase();
    const prefix = String(compareRef.imf_set_prefix || "").trim();
    let setId = String(body.set_id || "").trim();
    if (suffix) {
      const indicatorPart = suffix.includes(".") ? suffix.split(".").slice(1).join(".") : suffix;
      const sdmxKey = `${imfCode}.${indicatorPart}`;
      setId = prefix ? `${prefix}|${sdmxKey}` : setId.replace(/\|[^|]+$/, `|${sdmxKey}`);
    } else if (setId.includes("|")) {
      const parts = setId.split("|");
      const tail = parts[parts.length - 1] || "";
      if (tail.includes(".")) {
        const indicatorPart = tail.split(".").slice(1).join(".");
        parts[parts.length - 1] = `${imfCode}.${indicatorPart}`;
        setId = parts.join("|");
      }
    }
    body.set_id = setId;
    body.query_params = {
      ...body.query_params,
      imf_country: imfCode,
      imf_flow: flow,
      imf_indicator: suffix.includes(".") ? suffix.split(".").slice(1).join(".") : suffix,
      imf_frekvence: freq,
    };
    return body;
  }

  if (mode === "ecb" || src === "ecb" || src === "ecb2") {
    const curated = String(body.set_id || "").match(/^ecb:([A-Z0-9]{2}):(.+)$/i);
    const ind = String(
      compareRef.ecb_indicator_id
        || body.query_params.ecb_indicator_id
        || body.query_params.indicator
        || (curated ? curated[2] : "")
        || (!String(body.set_id || "").includes(":") ? body.set_id : "")
        || ""
    ).trim();
    if (!ind) return null;
    body.set_id = `ecb:${code}:${ind}`;
    body.country = code;
    body.query_params = { country: code, ecb_indicator_id: ind, indicator: ind };
    return body;
  }

  if (mode === "wb_country" || src === "worldbank" || src === "world_bank" || src === "world_bank_data360") {
    const wbCode = mapCompareCountryToWorldBankCode(code);
    if (!wbCode) return null;
    body.country = wbCode;
    body.query_params = { ...body.query_params, country: wbCode };
    return body;
  }

  body.query_params = {
    ...body.query_params,
    geo: code,
    REF_AREA: code,
    ref_area: code,
    country: code,
  };
  return body;
}

export function buildExploreFxOverlayPreviewBody(fxPair) {
  if (!fxPair || typeof fxPair !== "object") return null;
  const setId = String(fxPair.set_id || "").trim();
  if (!setId) return null;
  return {
    source_type: String(fxPair.source_type || "ecb").trim().toLowerCase(),
    set_id: setId,
    query_params: {},
  };
}

/** Index 100 = první bod — pro overlay FX u ukazatelů v jiných jednotkách. */
export function indexChartRows(rows) {
  const sorted = (Array.isArray(rows) ? rows : [])
    .filter((r) => r && r.x && !Number.isNaN(Number(r.y)))
    .sort((a, b) => compareChartPeriods(a.x, b.x));
  if (!sorted.length) return [];
  const base = Number(sorted[0].y);
  if (!base || Number.isNaN(base)) return sorted;
  return sorted.map((r) => ({
    x: r.x,
    y: (Number(r.y) / base) * 100,
  }));
}

export function previewToChartRows(normalized) {
  const rows = Array.isArray(normalized?.rows) ? normalized.rows : [];
  if (!rows.length) return [];
  const columns = Array.isArray(normalized?.columns) ? normalized.columns : [];
  const fields = columns.map((c) => String(c?.key || "").trim()).filter(Boolean);
  const fieldList = fields.length ? fields : Object.keys(rows[0] || {});
  const { timeField, valueField } = pickPreviewFields(fieldList, rows);
  if (!timeField || !valueField) return [];
  return rows
    .map((r) => ({
      x: String(r?.[timeField] ?? "").trim(),
      y: Number(r?.[valueField]),
    }))
    .filter((p) => p.x && !Number.isNaN(p.y))
    .sort((a, b) => compareChartPeriods(a.x, b.x));
}
