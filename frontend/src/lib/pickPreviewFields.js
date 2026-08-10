import { parseNumber } from "./format";

const VALUE_FIELD_PREFERENCE = [
  "obs_value",
  "OBS_VALUE",
  "hodnota",
  "Hodnota",
  "value",
  "amount",
  "price",
  "rate",
  "y",
];
const GEO_FIELD_PREFERENCE = [
  "geo",
  "ref_area",
  "country",
  "region",
  "území-kraj",
  "uzemi-kraj",
  "ÚZEMÍ-Kraj",
  "Území-kraj",
];
const VALUE_FIELD_BLACKLIST = new Set([
  "id",
  "snapshot_id",
  "indicator_id",
  "set_id",
  "series_id",
  "code",
  "dataset_id",
  "year",
  "month",
  "day",
  "quarter",
  "frequency",
  "freq",
]);
const DIMENSION_VALUE_FIELD_RX =
  /^(freq|frequency|ref_area|geo|country|l_|cbs_|counterpart|instr_|maturity|adjustment|consolidation|accounting|currency|unit_|valuation|prices|sto|expenditure|transformation|cust_|rem_)/i;

const TIME_FIELD_EXACT_PRIORITY = [
  "time_period",
  "period",
  "date",
  "obdobi",
  "roky",
  "Roky",
  "tříleté období",
  "Tříleté období",
  "year",
  "rok",
];
const TIME_FIELD_SOFT_BLACKLIST = new Set([
  "time_format",
  "unit_mult",
  "obs_status",
  "comment_obs",
  "latest_data",
]);

function fieldHasNumericValues(rows, field) {
  if (!field || !Array.isArray(rows)) return false;
  return rows.some((r) => parseNumber(r?.[field]) !== null);
}

function isDimensionMetadataValueField(field) {
  const lc = String(field || "").trim().toLowerCase();
  if (!lc) return true;
  if (VALUE_FIELD_BLACKLIST.has(lc)) return true;
  return DIMENSION_VALUE_FIELD_RX.test(lc);
}

/** Heuristika pro výběr časové osy a hodnoty z fields/rows. */
export function pickPreviewFields(fields, rows) {
  const pickFieldByPreference = (prefs, { numericOnly = false } = {}) => {
    const lowered = new Map((fields || []).map((f) => [String(f || "").trim().toLowerCase(), f]));
    for (const pref of prefs) {
      const raw = lowered.get(String(pref || "").trim().toLowerCase());
      if (!raw) continue;
      const ok = numericOnly
        ? fieldHasNumericValues(rows, raw)
        : rows.some((r) => parseNumber(r?.[raw]) !== null || String(r?.[raw] ?? "").trim());
      if (ok) return raw;
    }
    return "";
  };
  const normalized = (fields || []).map((f) => ({
    raw: f,
    lc: String(f || "").trim().toLowerCase(),
  }));
  const valueDiversity = (f) => {
    const uniq = new Set();
    for (const r of rows || []) {
      const v = String(r?.[f] ?? "").trim();
      if (v) uniq.add(v);
      if (uniq.size >= 3) break;
    }
    return uniq.size;
  };
  const looksLikePeriod = (v) => {
    const s = String(v ?? "").trim();
    if (!s) return false;
    if (/^\d{4}$/.test(s)) {
      const y = Number(s);
      return y >= 1800 && y <= 2200;
    }
    if (/^\d{4}[-/]\d{1,2}([-/]\d{1,2})?$/.test(s)) return true;
    if (/^\d{4}[-–]\d{4}$/.test(s)) return true;
    if (/^\d{4}Q[1-4]$/i.test(s)) return true;
    if (/^\d{4}M(0?[1-9]|1[0-2])$/i.test(s)) return true;
    if (/^\d{6,8}$/.test(s)) return true;
    return false;
  };

  let timeField =
    normalized.find(({ lc, raw }) => TIME_FIELD_EXACT_PRIORITY.includes(lc) && valueDiversity(raw) >= 2)?.raw ||
    normalized.find(
      ({ lc, raw }) =>
        /(^|_)(time_period|period|date|year|rok|roky|obdobi)($|_)/i.test(lc) &&
        !TIME_FIELD_SOFT_BLACKLIST.has(lc) &&
        valueDiversity(raw) >= 2
    )?.raw ||
    normalized.find(
      ({ lc, raw }) =>
        !TIME_FIELD_SOFT_BLACKLIST.has(lc) && rows.some((r) => looksLikePeriod(r?.[raw]))
    )?.raw ||
    normalized.find(({ raw }) => rows.some((r) => looksLikePeriod(r?.[raw])))?.raw;

  let valueField = pickFieldByPreference(VALUE_FIELD_PREFERENCE, { numericOnly: true });
  if (!valueField) {
    valueField = (fields || []).find(
      (f) =>
        f !== timeField &&
        !isDimensionMetadataValueField(f) &&
        fieldHasNumericValues(rows, f)
    );
  }
  const geoField = pickFieldByPreference(GEO_FIELD_PREFERENCE, { numericOnly: false });
  return { timeField, valueField, geoField };
}

/** API hinty (ČSÚ x_field/y_field) mají přednost před heuristikou. */
export function resolvePreviewAxisFields(preview, fields, rows) {
  const base = pickPreviewFields(fields, rows);
  const available = new Set((fields || []).map((f) => String(f || "").trim()).filter(Boolean));
  const xHint = String(
    preview?.x_field || preview?.metadata?.x_field || "",
  ).trim();
  const yHint = String(
    preview?.y_field || preview?.metadata?.y_field || "",
  ).trim();
  return {
    ...base,
    timeField: xHint && available.has(xHint) ? xHint : base.timeField,
    valueField: yHint && available.has(yHint) ? yHint : base.valueField,
  };
}
