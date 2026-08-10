/**
 * Pure utility funkce pro AradView.
 *
 * Žádné React hooky, žádný component state, žádné DOM side-effecty.
 * Extrahováno z AradView.jsx beze změny logiky.
 */
import { fmtCompact, fmtNumber } from "@/lib/format";
import { clampNum, coerceChartNumeric } from "@/lib/chartZoomHelpers";
import { isAdditiveMetric } from "@/lib/chartTimeSeriesPivot";

// ---------------------------------------------------------------------------
// X-osa: bucketing a tick selection
// ---------------------------------------------------------------------------

export function bucketKey(date, targetFreq) {
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return null;
  const y = date.getFullYear();
  const m = date.getMonth();
  const d = date.getDate();
  switch (targetFreq) {
    case "Y":
      return { key: `${y}`, label: `${y}`, sort: y * 10000 };
    case "H": {
      const h = m < 6 ? 1 : 2;
      return { key: `${y}-H${h}`, label: `${h}.H ${y}`, sort: y * 10000 + h * 100 };
    }
    case "Q": {
      const q = Math.floor(m / 3) + 1;
      const roman = ["I", "II", "III", "IV"][q - 1];
      return { key: `${y}-Q${q}`, label: `${roman}.Q ${y}`, sort: y * 10000 + q * 100 };
    }
    case "M":
      return { key: `${y}-${String(m + 1).padStart(2, "0")}`, label: `${y}-${String(m + 1).padStart(2, "0")}`, sort: y * 10000 + (m + 1) * 100 };
    case "W": {
      const onejan = new Date(y, 0, 1);
      const week = Math.ceil(((date - onejan) / 86400000 + onejan.getDay() + 1) / 7);
      return { key: `${y}-W${String(week).padStart(2, "0")}`, label: `${y} týden ${week}`, sort: y * 10000 + week };
    }
    default:
      return { key: `${y}-${String(m + 1).padStart(2, "0")}-${String(d).padStart(2, "0")}`, label: `${y}-${String(m + 1).padStart(2, "0")}-${String(d).padStart(2, "0")}`, sort: y * 10000 + (m + 1) * 100 + d };
  }
}

export function selectEvenlySpacedXTicksArad(rows, maxTicks) {
  const n = rows.length;
  if (n === 0 || maxTicks < 1) return null;
  const xs = rows.map((r) => r.x);
  if (n <= maxTicks) return xs;
  const picked = [];
  for (let j = 0; j < maxTicks; j++) {
    const idx = Math.round((j * (n - 1)) / Math.max(1, maxTicks - 1));
    picked.push(xs[idx]);
  }
  return [...new Set(picked)];
}

export function maxXTicksCountArad(n, compact, veryNarrow) {
  if (n <= 1) return null;
  if (veryNarrow) return n <= 4 ? n : 4;
  if (compact) return n <= 5 ? n : Math.min(5, n);
  // Malé série (vč. 12 měsíců) ukáží VŠECHNY ticky — jinak nepravidelné zaokrouhlení
  // vynechá pár měsíců (08, 01) a popisky osy přestanou sedět s body grafu.
  if (n <= 14) return n;
  return n > 80 ? 7 : n > 50 ? 8 : n > 24 ? 9 : 12;
}

// ---------------------------------------------------------------------------
// Formátování čísel pro statistiky / trend
// ---------------------------------------------------------------------------

/** Sklon lineární regrese: změna hodnoty Y na jeden krok v pořadí zobrazených bodů (ne nutně kalendářní čas). */
export function fmtTrendSlopePerStep(slope) {
  if (slope == null || !Number.isFinite(slope)) return "";
  const a = Math.abs(slope);
  const digits = a < 1e-6 ? 8 : a < 1e-3 ? 6 : a < 0.01 ? 5 : a < 1 ? 4 : a < 100 ? 2 : 1;
  return fmtNumber(slope, { digits: Math.min(8, Math.max(1, digits)) });
}

export function fmtStatsNumber(value, { digits = 2, compact = true, suffix = "" } = {}) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const formatted = compact ? fmtCompact(Number(value), { digits }) : fmtNumber(Number(value), { digits });
  return suffix ? `${formatted}${suffix}` : formatted;
}

export function fmtStatsSigned(value, { digits = 2, suffix = "" } = {}) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const sign = Number(value) > 0 ? "+" : "";
  return `${sign}${fmtStatsNumber(value, { digits, compact: false })}${suffix}`;
}

export function fmtStatsCorrelation(value) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  return fmtNumber(Number(value), { digits: 2 });
}

export function fmtStatsLeadLag(leadLag) {
  if (!leadLag || leadLag.correlation == null) return "—";
  const lag = Number(leadLag.lag) || 0;
  const prefix = lag === 0 ? "0" : lag > 0 ? `+${lag}` : String(lag);
  return `${prefix} (${fmtStatsCorrelation(leadLag.correlation)})`;
}

// ---------------------------------------------------------------------------
// Normalizace chart config hodnot
// ---------------------------------------------------------------------------

export function normalizeRollingWindow(value, fallback = 4) {
  const n = Math.round(Number(value));
  if (!Number.isFinite(n)) return fallback;
  return clampNum(n, 1, 120);
}

export function normalizeManualTrendLine(value) {
  if (!value || typeof value !== "object") return null;
  const start = value.start && typeof value.start === "object" ? value.start : null;
  const end = value.end && typeof value.end === "object" ? value.end : null;
  const startX = start?.x ?? start?.period;
  const endX = end?.x ?? end?.period;
  const startY = coerceChartNumeric(start?.y);
  const endY = coerceChartNumeric(end?.y);
  if (startX == null || endX == null || startY == null || endY == null) return null;
  return {
    start: { x: String(startX), y: startY },
    end: { x: String(endX), y: endY },
  };
}

// ---------------------------------------------------------------------------
// Statistiky časové řady
// ---------------------------------------------------------------------------

export function medianOfNumbers(values) {
  const nums = (Array.isArray(values) ? values : [])
    .map((value) => coerceChartNumeric(value))
    .filter((value) => value != null && Number.isFinite(value))
    .sort((a, b) => a - b);
  if (!nums.length) return null;
  const mid = Math.floor(nums.length / 2);
  return nums.length % 2 ? nums[mid] : (nums[mid - 1] + nums[mid]) / 2;
}

export function trendSegmentForPoints(points) {
  const pts = (Array.isArray(points) ? points : []).filter(
    (point) => point?.x != null && point?.x !== "" && point?.y != null && Number.isFinite(point.y)
  );
  if (pts.length < 2) return { trendSegment: null, trendSlopePerStep: null };
  const n = pts.length;
  const values = pts.map((point) => point.y);
  const meanX = (n - 1) / 2;
  const meanY = values.reduce((sum, value) => sum + value, 0) / n;
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    const dx = i - meanX;
    num += dx * (values[i] - meanY);
    den += dx * dx;
  }
  const slope = den === 0 ? 0 : num / den;
  const intercept = meanY - slope * meanX;
  return {
    trendSegment: {
      xStart: pts[0].x,
      yStart: intercept,
      xEnd: pts[n - 1].x,
      yEnd: intercept + slope * (n - 1),
    },
    trendSlopePerStep: slope,
  };
}

// ---------------------------------------------------------------------------
// Normalizátory počátečního stavu AradView
// ---------------------------------------------------------------------------

export function resolveInitialDataView(data, widget) {
  const pref = String(widget?.config?.default_data_view || "").toLowerCase();
  if (pref === "table") return "table";
  if (pref === "chart") return "chart";
  return data?.view === "table" ? "table" : "chart";
}

// Default aggregation strategy hint based on the indicator unit.
// Non-additive (% / index / rate) → avg or last; explicit flows → sum; else safe last.
function guessAgg(unit) {
  const u = (unit || "").toLowerCase();
  if (!u) return "last";
  if (u.includes("%") || u.includes("sazba") || u.includes("rate") || u.includes("index") || u === "pc") {
    return "avg";
  }
  if (isAdditiveMetric({ unit })) return "sum";
  return "last";
}

export function normalizeAgg(value, unit) {
  const v = String(value || "").trim().toLowerCase();
  return ["sum", "avg", "last", "first", "max", "min", "count"].includes(v) ? v : guessAgg(unit);
}

export function normalizeBarOrientation(value) {
  return String(value || "").toLowerCase() === "horizontal" ? "horizontal" : "vertical";
}

export function normalizePieVariant(value) {
  const raw = String(value || "").toLowerCase();
  return raw === "full" || raw === "pie" ? "full" : "donut";
}
