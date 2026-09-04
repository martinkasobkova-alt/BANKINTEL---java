/** Jedna uživatelská dimenze odvětví — ostatní technické osy se doplní na pozadí. */

import { readOptionsFromDimensionMeta } from "./sourcePreviewDimensionMeta";

export const INDUSTRY_DIMENSION_KEYS = ["nace_r2", "nace_r1", "ind_use"];

/** Skryté dimenze doplněné automaticky při výběru odvětví. */
export const INDUSTRY_LINKED_HIDDEN_KEYS = new Set([
  "cpa2_1",
  "ceparema",
  "env_econ",
  "env_domain",
  "env_prot",
]);

export const INDUSTRY_FIELD_LABEL_CS = "Odvětví";

const INDUSTRY_MAX_OPTIONS = 150;

const TOTAL_CODES = new Set(["T", "TOTAL", "ALL", "TOT", "_T", "TOT_CEPA"]);

function dimKey(value) {
  return String(value ?? "").trim().toLowerCase();
}

function dimValuesFromMeta(availableDimensions, key) {
  if (!availableDimensions?.[key]) return [];
  return readOptionsFromDimensionMeta(availableDimensions[key]).map((o) => o.value);
}

function pickFirstAvailable(candidates, validSet) {
  for (const code of candidates) {
    const c = String(code || "").trim();
    if (!c) continue;
    if (validSet.has(c)) return c;
    const upper = c.toUpperCase();
    for (const v of validSet) {
      if (String(v).toUpperCase() === upper) return v;
    }
  }
  return "";
}

export function isIndustryDimensionKey(key) {
  return INDUSTRY_DIMENSION_KEYS.includes(dimKey(key));
}

export function isIndustryLinkedHiddenKey(key) {
  return INDUSTRY_LINKED_HIDDEN_KEYS.has(dimKey(key));
}

export function industryMaxOptionCount() {
  return INDUSTRY_MAX_OPTIONS;
}

export function isIndustryDimensionSelectable(key, { optionCount = 0 } = {}) {
  const lk = dimKey(key);
  if (!INDUSTRY_DIMENSION_KEYS.includes(lk)) return false;
  if (optionCount < 2 || optionCount > INDUSTRY_MAX_OPTIONS) return false;
  return true;
}

/** V datasetu max. jedna dimenze odvětví (priorita NACE rev.2 > rev.1 > ind_use). */
export function pickPrimaryIndustryField(fields, { datasetId = "", availableDimensions = {} } = {}) {
  const present = new Set(
    (fields || []).map((f) => dimKey(f)).filter((f) => INDUSTRY_DIMENSION_KEYS.includes(f)),
  );
  for (const candidate of INDUSTRY_DIMENSION_KEYS) {
    if (!present.has(candidate)) continue;
    const exact = (fields || []).find((f) => dimKey(f) === candidate) || candidate;
    const count = dimValuesFromMeta(availableDimensions, exact).length;
    if (isIndustryDimensionSelectable(exact, { datasetId, optionCount: count || 2 })) {
      return exact;
    }
  }
  return "";
}

export function pairedCpaFromIndUse(indUse, availableDimensions) {
  const code = String(indUse ?? "").trim().toUpperCase();
  const valid = new Set(dimValuesFromMeta(availableDimensions, "cpa2_1"));
  if (!valid.size) return "";
  if (!code || TOTAL_CODES.has(code)) {
    return (
      pickFirstAvailable(["CPA_T", "CPA_TOTAL", "CPA_TOTAL", "T"], valid) ||
      pickFirstAvailable(["CPA_T"], valid)
    );
  }
  const candidates = [
    `CPA_${code}`,
    `CPA_${code.replace(/-/g, "_")}`,
    `CPA_${code.replace(/_/g, "-")}`,
    "CPA_TOTAL",
    "CPA_T",
  ];
  return pickFirstAvailable(candidates, valid) || pickFirstAvailable(["CPA_G45", "CPA_T"], valid);
}

export function defaultCeparemaForIndustry(availableDimensions) {
  const valid = new Set(dimValuesFromMeta(availableDimensions, "ceparema"));
  if (!valid.size) return "";
  return pickFirstAvailable(["TOT_CEPA", "TOTAL", "TOT", "T"], valid);
}

/** Doplní skryté dimenze (CPA, CEPA celkem…) podle zvoleného odvětví. */
export function applyIndustryLinkedFilters(queryParams, availableDimensions, datasetId = "") {
  const out = { ...(queryParams || {}) };
  const ds = String(datasetId || "").toLowerCase();

  const indUse = String(out.ind_use ?? out.IND_USE ?? "").trim();
  if (indUse && dimValuesFromMeta(availableDimensions, "cpa2_1").length) {
    const paired = pairedCpaFromIndUse(indUse, availableDimensions);
    if (paired) out.cpa2_1 = paired;
  }

  const nace = String(out.nace_r2 ?? out.nace_r1 ?? "").trim();
  if (nace && dimValuesFromMeta(availableDimensions, "ceparema").length) {
    const cepa = defaultCeparemaForIndustry(availableDimensions);
    if (cepa) out.ceparema = cepa;
  }

  if (ds.startsWith("naio_10_cp") && !indUse) {
    out.ind_use = out.ind_use || "G45";
    const paired = pairedCpaFromIndUse(out.ind_use, availableDimensions);
    if (paired) out.cpa2_1 = paired;
  }

  for (const hidden of INDUSTRY_LINKED_HIDDEN_KEYS) {
    if (hidden === "cpa2_1" && out.cpa2_1) continue;
    if (hidden === "ceparema" && out.ceparema) continue;
  }

  return out;
}
