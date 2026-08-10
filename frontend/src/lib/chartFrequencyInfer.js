/** Odhad periodicity řady podle vzorků období (když backend nepošle `frequency`). */

const FREQ_RANK_LOCAL = { D: 0, W: 1, M: 2, Q: 3, H: 4, Y: 5 };
const ORDER = ["D", "W", "M", "Q", "H", "Y"];
const CZ_MONTH_HINTS = [
  "leden",
  "unor",
  "brezen",
  "duben",
  "kveten",
  "cervenec",
  "cerven",
  "srpen",
  "zari",
  "rijen",
  "listopad",
  "prosinec",
];

export const NATIVE_FREQUENCY_CODES = Object.freeze(["D", "W", "M", "Q", "H", "Y"]);

function normalizePeriodText(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/** Normalize SDMX/BIS frequency code (A → Y). */
export function normalizeFrequencyCode(code) {
  const c = String(code ?? "")
    .trim()
    .toUpperCase();
  if (!c) return "";
  if (c === "A") return "Y";
  return NATIVE_FREQUENCY_CODES.includes(c) ? c : "";
}

/**
 * Explicit frequency from source wins; otherwise infer from row periods.
 * Never falls back to hardcoded Q/D/M without period evidence.
 */
export function resolveNativeFrequencyCode({
  explicitFrequency = "",
  rows = [],
  isMultiSeries = false,
} = {}) {
  const explicit = normalizeFrequencyCode(explicitFrequency);
  if (explicit) return explicit;
  return normalizeFrequencyCode(inferNativeFrequencyFromChartRows(rows, isMultiSeries));
}

/** @param {string} p Jedno období z řádků grafu (x / period). */
export function guessFrequencyCodeFromPeriodSample(p) {
  const s = String(p || "").trim();
  if (!s) return null;
  if (/\b[IVXLCDM]{1,4}\.{0,1}\s*[Qq]\s*\d{4}/i.test(s)) return "Q";
  if (/[Qq]\s*[1234]|Q[1234]|-\s*[Qq][1234]|[Qq](?:1|[2-4])\s*\d{4}/i.test(s) && /\d{4}/.test(s))
    return "Q";
  if (/\b[Hh][12]\b.*\d{4}|\d{4}[.\-/]?[Hh][12]/i.test(s)) return "H";
  if (/^\d{4}M(0?[1-9]|1[0-2])$/i.test(s)) return "M";
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return "D";
  const dg = s.replace(/\D/g, "");
  if (dg.length >= 8 && !/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    const y = parseInt(dg.slice(0, 4), 10);
    const m = parseInt(dg.slice(4, 6), 10) || 1;
    const d = parseInt(dg.slice(6, 8), 10) || 1;
    if (y >= 1900 && y <= 3000 && m >= 1 && m <= 12) {
      const lastDay = new Date(y, m, 0).getDate();
      if (d === 1 || d === lastDay) return "M";
    }
    return "D";
  }
  if (/^\d{4}-\d{2}$/.test(s)) return "M";
  if (/^\d{6}$/.test(dg)) return "M";
  const normalized = normalizePeriodText(s);
  if (/\b(19\d{2}|20\d{2})\b/.test(normalized) && CZ_MONTH_HINTS.some((month) => normalized.includes(month))) {
    return "M";
  }
  if (/^\d{4}$/.test(s)) return "Y";
  if (dg.length === 4 && /^(19|20)\d{2}$/.test(dg)) return "Y";
  if (/\b[Ww](?:EEK)?[\s_-]?\d{1,2}\b|\d{4}-W\d{2}/i.test(s)) return "W";
  return null;
}

function inferFrequencyFromIsoDateSamples(samples) {
  const iso = samples.filter((s) => /^\d{4}-\d{2}-\d{2}$/.test(s));
  if (iso.length < 2) return null;
  const daySet = new Set();
  const monthSet = new Set();
  const yearSet = new Set();
  for (const s of iso) {
    const [, y, m, d] = s.match(/^(\d{4})-(\d{2})-(\d{2})$/) || [];
    if (!y) continue;
    yearSet.add(y);
    monthSet.add(m);
    daySet.add(d);
  }
  if (daySet.size > 1) return "D";
  if (monthSet.size === 1 && yearSet.size > 1) return "Y";
  if (monthSet.size > 1 || yearSet.size > 1) return "M";
  return null;
}

function rankToNearestCode(rank) {
  let best = "Q";
  let bestDist = 99;
  for (const code of ORDER) {
    const v = FREQ_RANK_LOCAL[code];
    if (v === rank) return code;
    const d = Math.abs(v - rank);
    if (d < bestDist) {
      bestDist = d;
      best = code;
    }
  }
  return best;
}

/**
 * Vrátí jeden nativní kód D/W/M/Q/H/Y z řádků grafu (`period`/`x`).
 * Medián granularit z vzorků – odolnější vůči ojedinělému šumu.
 */
export function inferNativeFrequencyFromChartRows(rowsRaw, isMultiSeries = false) {
  if (!Array.isArray(rowsRaw) || rowsRaw.length === 0) return "";
  void isMultiSeries;
  const samples = [];
  for (const r of rowsRaw.slice(0, 96)) {
    const p = r?.period ?? r?.x ?? "";
    if (p != null && String(p).trim() !== "") samples.push(String(p).trim());
  }
  if (!samples.length) return "";
  const fromIso = inferFrequencyFromIsoDateSamples(samples);
  if (fromIso) return fromIso;
  const ranks = [];
  for (const p of samples) {
    const c = guessFrequencyCodeFromPeriodSample(p);
    if (c && FREQ_RANK_LOCAL[c] !== undefined) ranks.push(FREQ_RANK_LOCAL[c]);
  }
  if (!ranks.length) {
    return "";
  }
  ranks.sort((a, b) => a - b);
  const midRank = ranks[Math.floor(ranks.length / 2)];
  return rankToNearestCode(midRank);
}

const FREQ_LABEL_CS = {
  D: "Denní",
  W: "Týdenní",
  M: "Měsíční",
  Q: "Čtvrtletní",
  H: "Pololetní",
  Y: "Roční",
  A: "Roční",
};

/** Český popisek frekvence (BIS/SDMX kód nebo odhad z období). */
export function nativeFrequencyLabelCs(code) {
  const c = String(code || "").trim().toUpperCase();
  if (!c) return "";
  return FREQ_LABEL_CS[c] || c;
}

/**
 * Popisek frekvence pro graf — explicitní katalogový label, sloupec FREQ, nebo odhad z osy X.
 */
export function resolveChartFrequencyLabel({
  catalogFreqLabel = "",
  catalogFreqCode = "",
  rows = [],
  chartRows = [],
  fields = [],
} = {}) {
  const explicit = String(catalogFreqLabel || "").trim();
  if (explicit) return explicit;
  const codeExplicit = String(catalogFreqCode || "").trim().toUpperCase();
  if (codeExplicit) {
    const fromCode = nativeFrequencyLabelCs(codeExplicit);
    if (fromCode) return fromCode;
  }
  const periodField = (fields || []).find((f) => String(f || "").trim().toLowerCase() === "period");
  if (periodField && Array.isArray(rows) && rows.length >= 2) {
    const periodRows = rows
      .slice(0, 96)
      .map((r) => ({ x: String(r?.[periodField] ?? "").trim() }))
      .filter((r) => r.x);
    if (periodRows.length >= 2) {
      const fromPeriod = inferNativeFrequencyFromChartRows(periodRows, false);
      const lab = nativeFrequencyLabelCs(fromPeriod);
      if (lab) return lab;
    }
  }
  const freqField = (fields || []).find((f) => String(f || "").trim().toUpperCase() === "FREQ");
  if (freqField && Array.isArray(rows) && rows.length) {
    const vals = new Set();
    for (const r of rows.slice(0, 40)) {
      const v = String(r?.[freqField] ?? "").trim().toUpperCase();
      if (v) vals.add(v);
    }
    if (vals.size === 1) {
      const lab = nativeFrequencyLabelCs([...vals][0]);
      if (lab) return lab;
    }
  }
  const inferred = inferNativeFrequencyFromChartRows(chartRows, false);
  return nativeFrequencyLabelCs(inferred) || "";
}
