/** Uživatelsky volitelné Eurostat dimenze v náhledu (odvětví, typ pohonu, …). */

import {
  readOptionsFromDimensionMeta,
  resolveHumanValueLabel,
  buildLabelLookupFromDimensionMeta,
} from "./sourcePreviewDimensionMeta";
import {
  INDUSTRY_FIELD_LABEL_CS,
  industryMaxOptionCount,
  isIndustryDimensionKey,
  isIndustryDimensionSelectable,
  isIndustryLinkedHiddenKey,
  pickPrimaryIndustryField,
} from "./sourcePreviewIndustryFilters";

const SKIP_DIMENSION_KEYS = new Set([
  "geo",
  "ref_area",
  "country",
  "time",
  "time_period",
  "period",
  "date",
  "datum",
  "year",
  "rok",
  "roky",
  "obdobi",
  "období",
  "c_orig",
  "c_dest",
  "c_imp",
  "c_exp",
  "query_mode",
  "geo_scope",
]);

[
  "month",
  "months",
  "mesic",
  "mesice",
  "quarter",
  "quarters",
  "ctvrtleti",
  "day",
  "days",
  "den",
  "dny",
  "value",
  "amount",
  "hodnota",
  "hodnoty",
  "obs_value",
  "raw_value",
  "value_num",
  "observation_value_raw",
  "y",
].forEach((key) => SKIP_DIMENSION_KEYS.add(key));

/** Záložní české popisky, když metadata dimenze chybí. */
const DIMENSION_FIELD_LABELS_CS = {
  currency: "Měna",
  freq: "Frekvence",
  mot_nrg: "Typ pohonu",
  engine: "Objem motoru",
  statinfo: "Typ hodnoty",
  unit: "Jednotka",
  indicator_id: "Ukazatel",
};

const EUROSTAT_GENERIC_USER_DIMENSIONS = new Set([
  "currency",
  "freq",
  "statinfo",
  "unit",
]);

const GENERAL_USER_DIMENSION_MAX_OPTIONS = 500;

const TECHNICAL_USER_DIMENSION_KEYS = new Set([
  "key",
  "source",
  "source_type",
  "dataset",
  "dataset_id",
  "title",
  "name",
  "series_id",
  "variable",
]);

[
  "agg_method",
  "comp_breakdown_1",
  "comp_breakdown_2",
  "comp_breakdown_3",
  "database_id",
  "latest_data",
  "obs_conf",
  "obs_status",
  "time_format",
  "unit_measure",
  "unit_mult",
  "set_id",
  "snapshot_id",
  "indicator_name",
  "series_name",
  "full_path",
  "catalog_path",
  "catalog_id",
  "catalog_label",
  "path",
].forEach((key) => TECHNICAL_USER_DIMENSION_KEYS.add(key));

function dimKey(value) {
  return String(value ?? "")
    .trim()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

export { resolveHumanValueLabel };

function isSkippedUserDimensionKey(key) {
  const lk = dimKey(key);
  return SKIP_DIMENSION_KEYS.has(lk)
    || TECHNICAL_USER_DIMENSION_KEYS.has(lk)
    || lk.endsWith("_label")
    || lk.endsWith("_date")
    || lk.endsWith("_period")
    || lk.endsWith("_time")
    || lk.endsWith("_value");
}

function humanizeDimensionKey(key) {
  const raw = String(key || "").trim();
  if (!raw) return "Dimenze";
  return raw
    .replace(/[_./-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

export function enrichDimensionOptions(field, options, availableDimensions) {
  const meta = availableDimensions?.[field] || availableDimensions?.[dimKey(field)];
  const labelMap = buildLabelLookupFromDimensionMeta(meta);
  const enriched = (options || [])
    .map((opt) => {
      const value = String(opt?.value ?? "").trim();
      if (!value) return null;
      const human = resolveHumanValueLabel(value, labelMap[value] || opt?.label);
      return { value, label: human, rowCount: opt?.rowCount || 0 };
    })
    .filter(Boolean);
  const seen = new Set();
  const unique = [];
  for (const row of enriched) {
    if (seen.has(row.value)) continue;
    seen.add(row.value);
    unique.push(row);
  }
  unique.sort((a, b) => a.label.localeCompare(b.label, "cs"));
  return unique;
}

export function isUserSelectableDimensionKey(key, ctx = {}) {
  if (isIndustryLinkedHiddenKey(key)) return false;
  if (isIndustryDimensionSelectable(key, ctx)) return true;
  const lk = dimKey(key);
  const count = Number(ctx.optionCount || 0);
  if (isSkippedUserDimensionKey(lk)) return false;
  if (EUROSTAT_GENERIC_USER_DIMENSIONS.has(lk)) {
    return count >= 2 && count <= 500;
  }
  if (lk === "mot_nrg" || lk === "engine") {
    return count >= 2 && count <= industryMaxOptionCount();
  }
  return count >= 2 && count <= GENERAL_USER_DIMENSION_MAX_OPTIONS;
}

export { readOptionsFromDimensionMeta };

export function getUserChoiceDimensionLabel(key, availableDimensions, fallbackLabel = "") {
  const lk = dimKey(key);
  if (DIMENSION_FIELD_LABELS_CS[lk]) return DIMENSION_FIELD_LABELS_CS[lk];
  if (isIndustryDimensionSelectable(key, { optionCount: 2 })) {
    return INDUSTRY_FIELD_LABEL_CS;
  }
  const fromFallback = String(fallbackLabel || "").trim();
  if (fromFallback) return fromFallback;
  const fromMeta = availableDimensions?.[key]?.label || availableDimensions?.[lk]?.label;
  if (fromMeta && String(fromMeta).trim()) return String(fromMeta).trim();
  return humanizeDimensionKey(key);
}

function buildDimensionEntry(field, options, applied, availableDimensions, rowLabel = "") {
  const appliedRaw = applied[field];
  const selected = Array.isArray(appliedRaw)
    ? String(appliedRaw[0] ?? "").trim()
    : String(appliedRaw ?? "").trim();
  const fallbackSelected = selected || String(options[0]?.value || "").trim();
  return {
    field,
    label: getUserChoiceDimensionLabel(field, availableDimensions, rowLabel),
    options,
    selected: fallbackSelected,
    selectedLabel: resolveHumanValueLabel(
      fallbackSelected,
      options.find((o) => o.value === fallbackSelected)?.label,
    ),
    isIndustry: isIndustryDimensionKey(field),
  };
}

/**
 * @param {Record<string, unknown>} availableDimensions
 * @param {{ datasetId?: string, appliedFilters?: Record<string, unknown>, selectableDimensions?: Array<{field?: string, values?: string[], options?: Array<{code?: string, label?: string}>, label?: string}> }} opts
 */
export function buildUserChoiceDimensions(availableDimensions, opts = {}) {
  const datasetId = String(opts.datasetId || "").trim();
  const applied = opts.appliedFilters && typeof opts.appliedFilters === "object" ? opts.appliedFilters : {};
  const selectable = Array.isArray(opts.selectableDimensions) ? opts.selectableDimensions : [];
  const items = [];

  for (const row of selectable) {
    if (!row || typeof row !== "object") continue;
    const field = String(row.field || "").trim();
    if (!field || isIndustryLinkedHiddenKey(field) || isSkippedUserDimensionKey(field)) continue;

    let options = [];
    if (Array.isArray(row.options) && row.options.length) {
      options = row.options
        .map((o) => {
          const value = String(o?.code ?? o?.value ?? "").trim();
          if (!value) return null;
          return { value, label: resolveHumanValueLabel(value, o?.label), rowCount: 0 };
        })
        .filter(Boolean);
    } else if (Array.isArray(row.values)) {
      options = row.values
        .map((code) => ({ value: String(code).trim(), label: String(code).trim(), rowCount: 0 }))
        .filter((o) => o.value);
    }
    options = enrichDimensionOptions(field, options, availableDimensions);
    if (options.length < 2) continue;
    if (
      isIndustryDimensionKey(field) &&
      !isIndustryDimensionSelectable(field, { datasetId, optionCount: options.length })
    ) {
      continue;
    }
    if (
      !isIndustryDimensionKey(field) &&
      !isUserSelectableDimensionKey(field, { datasetId, optionCount: options.length })
    ) {
      continue;
    }
    items.push(
      buildDimensionEntry(field, options, applied, availableDimensions, String(row.label || "")),
    );
  }

  if (!items.length && availableDimensions && typeof availableDimensions === "object") {
    for (const [field, meta] of Object.entries(availableDimensions)) {
      if (isSkippedUserDimensionKey(field) || isIndustryLinkedHiddenKey(field)) continue;
      const options = readOptionsFromDimensionMeta(meta);
      if (!isUserSelectableDimensionKey(field, { datasetId, optionCount: options.length })) continue;
      items.push(buildDimensionEntry(field, options, applied, availableDimensions));
    }
  }

  const industryItems = items.filter((item) => item.isIndustry);
  if (industryItems.length > 1) {
    const keep = pickPrimaryIndustryField(
      industryItems.map((item) => item.field),
      { datasetId, availableDimensions },
    );
    if (keep) {
      return items.filter((item) => !item.isIndustry || item.field === keep);
    }
  }

  return items.sort((a, b) => {
    if (a.isIndustry !== b.isIndustry) return a.isIndustry ? -1 : 1;
    return String(a.label).localeCompare(String(b.label), "cs");
  });
}

export function userChoiceDimensionsSectionTitle(items) {
  const list = Array.isArray(items) ? items : [];
  if (!list.length) return INDUSTRY_FIELD_LABEL_CS;
  if (list.length === 1 && list[0]?.isIndustry) return INDUSTRY_FIELD_LABEL_CS;
  return "Dimenze";
}

export function userChoiceDimensionsHelpText(items) {
  const list = Array.isArray(items) ? items : [];
  const hasIndustry = list.some((item) => item?.isIndustry);
  const hasTransport = list.some((item) => dimKey(item?.field) === "mot_nrg" || dimKey(item?.field) === "engine");
  if (hasIndustry && hasTransport) {
    return "Ostatní technické osy (frekvence, jednotka, produkt…) se doplní automaticky na pozadí.";
  }
  if (hasTransport) {
    return "Zvolte typ pohonu nebo objem motoru; frekvence a jednotka zůstávají doplněné automaticky.";
  }
  return "Produkt, doména CEPA a další technické osy se doplní automaticky na pozadí.";
}

export function formatUserChoiceOptionLabel(opt) {
  const value = String(opt?.value || "").trim();
  const label = String(opt?.label || "").trim();
  return resolveHumanValueLabel(value, label);
}

export function resolveDimensionValueLabel(field, code, availableDimensions) {
  const value = String(code ?? "").trim();
  if (!value) return "";
  const meta = availableDimensions?.[field] || availableDimensions?.[dimKey(field)];
  const labelMap = buildLabelLookupFromDimensionMeta(meta);
  return resolveHumanValueLabel(value, labelMap[value]);
}

export { industryMaxOptionCount };
