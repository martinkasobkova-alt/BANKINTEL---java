/**
 * Export sanitization — numeric value_raw, period/frequency helpers.
 */

import { inferNativeFrequencyFromChartRows } from "@/lib/chartFrequencyInfer";

const ROMAN_MONTH_PATTERN = /^\d{1,2}\.[IVXLC]+$/i;
const ROMAN_ONLY = /^[IVXLC]+\.?$/i;

const PERIOD_LIKE_PATTERNS = [
  /^\d{4}$/,
  /^\d{4}-Q[1-4]$/i,
  /^\d{4}-\d{2}$/,
  /^\d{4}-\d{2}-\d{2}$/,
  /^Q[1-4]$/i,
  /^\d{4}Q[1-4]$/i,
];

const CZECH_MONTHS =
  /^(leden|únor|unor|březen|brezen|duben|květen|kveten|červen|cerven|červenec|cervenec|srpen|září|zari|říjen|rijen|listopad|prosinec)$/i;

const ROMAN_TO_MONTH = Object.freeze({
  I: 1,
  II: 2,
  III: 3,
  IV: 4,
  V: 5,
  VI: 6,
  VII: 7,
  VIII: 8,
  IX: 9,
  X: 10,
  XI: 11,
  XII: 12,
});

const FREQ_RANK = Object.freeze({ D: 0, W: 1, M: 2, Q: 3, H: 4, Y: 5, A: 5 });

/** True when a string looks like a period label, not a numeric measure. */
export function isPeriodLikeString(value) {
  const t = String(value ?? "").trim();
  if (!t) return false;
  return PERIOD_LIKE_PATTERNS.some((p) => p.test(t)) || ROMAN_MONTH_PATTERN.test(t);
}

/** True when text must never be exported as value_raw. */
export function isNonNumericValueText(value) {
  const t = String(value ?? "").trim();
  if (!t) return true;
  if (ROMAN_MONTH_PATTERN.test(t)) return true;
  if (ROMAN_ONLY.test(t)) return true;
  if (/^Q[1-4]$/i.test(t)) return true;
  if (CZECH_MONTHS.test(t)) return true;
  return false;
}

/**
 * Accept only finite numbers or strings that parse fully as numbers.
 * Rejects period labels, Roman months, quarter tokens, etc.
 */
export function sanitizeExportValueRaw(value, context = {}) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  const s = String(value ?? "").trim();
  if (!s) return null;
  if (isNonNumericValueText(s) || isPeriodLikeString(s)) {
    if (typeof console !== "undefined" && console.warn) {
      console.warn("[chartExport] rejected non-numeric value_raw", { value: s, ...context });
    }
    return null;
  }
  const normalized = s.replace(/\s/g, "").replace(",", ".");
  if (!/^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(normalized)) {
    if (typeof console !== "undefined" && console.warn) {
      console.warn("[chartExport] rejected value_raw (not numeric string)", { value: s, ...context });
    }
    return null;
  }
  const n = Number(normalized);
  return Number.isFinite(n) ? n : null;
}

/**
 * Detect dominant frequency from period strings.
 * YYYY → Y, YYYY-Qn → Q, YYYY-MM → M, YYYY-MM-DD → D
 */
export function detectFrequencyFromPeriods(periods = []) {
  const rows = (periods || [])
    .map((p) => ({ period: String(p ?? "").trim() }))
    .filter((r) => r.period);
  return inferNativeFrequencyFromChartRows(rows, false);
}

/** Prefer explicit frequency when consistent; never default to D without evidence. */
export function resolveExportFrequency(explicitFreq, periods = []) {
  const detected = detectFrequencyFromPeriods(periods);
  const exp = String(explicitFreq ?? "")
    .trim()
    .toUpperCase()
    .replace(/^A$/, "Y");
  if (!exp) return detected;
  if (!detected) return exp;
  const expRank = FREQ_RANK[exp] ?? 99;
  const detRank = FREQ_RANK[detected] ?? 99;
  if (exp === "D" && detected === "Y" && detRank > expRank) return detected;
  if (expRank !== detRank && exp === "D") return detected;
  return exp;
}

/** Normalize Czech ARAD-style period strings such as "2022 01.IX". */
export function normalizeExportPeriod(rawPeriod) {
  const s = String(rawPeriod ?? "").trim();
  if (!s) return { period: "", period_label: "" };

  const combined = s.match(/^(\d{4})\s+(\d{1,2})\.([IVXLC]+)$/i);
  if (combined) {
    const month = ROMAN_TO_MONTH[combined[3].toUpperCase()];
    if (month) {
      const period = `${combined[1]}-${String(month).padStart(2, "0")}`;
      return { period, period_label: s };
    }
  }

  if (ROMAN_MONTH_PATTERN.test(s)) {
    return { period: s, period_label: s };
  }

  return { period: s, period_label: s };
}

export function formatSeriesColumnHeader(seriesMeta = {}, fallbackUnit = "") {
  const label = String(seriesMeta.label || seriesMeta.name || seriesMeta.id || "Řada").trim();
  const unit = String(seriesMeta.unit || fallbackUnit || "").trim();
  if (unit && !label.includes(unit)) return `${label} (${unit})`;
  return label;
}

export function periodColumnHeader(contract) {
  if (contract?.metadata?.latest_mode || contract?.metadata?.data_mode === "latest") {
    return "Položka";
  }
  return "Období";
}

/** Locale-aware number for clipboard/CSV (cs-CZ → decimal comma for Excel). */
export function formatExportNumber(value, locale = "cs-CZ") {
  if (value === null || value === undefined || value === "") return "";
  if (typeof value !== "number" || !Number.isFinite(value)) return String(value);

  const loc = String(locale || "cs-CZ").toLowerCase();
  const en = value.toLocaleString("en-US", { useGrouping: false, maximumFractionDigits: 20 });
  if (loc.startsWith("cs")) return en.replace(".", ",");
  return en;
}

/** Sanitize contract data before any export path. */
export function prepareContractForExport(contract) {
  if (!contract) return contract;

  const periods = (contract.data || []).map((pt) => pt.period);
  const resolvedFrequency = resolveExportFrequency(contract.metadata?.frequency, periods);

  const sanitizedData = [];
  for (const pt of contract.data || []) {
    const value_raw = sanitizeExportValueRaw(pt.value_raw, {
      period: pt.period,
      series_id: pt.series_id,
    });
    if (value_raw == null) continue;

    const ptFrequency = resolveExportFrequency(pt.frequency, [pt.period]) || resolvedFrequency;
    sanitizedData.push({
      ...pt,
      value_raw,
      frequency: ptFrequency,
    });
  }

  return {
    ...contract,
    data: sanitizedData,
    metadata: {
      ...(contract.metadata || {}),
      frequency: resolvedFrequency || contract.metadata?.frequency || "",
    },
  };
}
