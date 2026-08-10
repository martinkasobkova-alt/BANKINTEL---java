import { coerceChartNumeric } from "@/lib/chartZoomHelpers";
import { fmtCompact, fmtNumber } from "@/lib/format";
import { parseChartPeriod } from "@/lib/chartPeriodParse";

export function isPercentUnit(unit) {
  const u = String(unit || "").trim().toLowerCase();
  return u === "%" || u.includes("%") || u === "procent" || u === "procenta" || u === "pct";
}

export function resolveStatDigits(value, unit) {
  if (isPercentUnit(unit)) return 2;
  const abs = Math.abs(Number(value));
  if (!Number.isFinite(abs)) return 2;
  if (abs >= 1000) return 1;
  if (abs >= 100) return 1;
  return 2;
}

export function formatSeriesStatValue(value, unit) {
  const digits = resolveStatDigits(value, unit);
  const abs = Math.abs(Number(value));
  const formatted =
    !isPercentUnit(unit) && Number.isFinite(abs) && abs >= 10000
      ? fmtCompact(value, { digits: 1 })
      : fmtNumber(value, { digits });
  const u = String(unit || "").trim();
  if (!u) return formatted;
  if (isPercentUnit(u)) return `${formatted} %`;
  return `${formatted} ${u}`;
}

export function formatAbsoluteChange(delta, unit) {
  if (delta == null || !Number.isFinite(delta)) return null;
  const digits = resolveStatDigits(delta, unit);
  const sign = delta > 0 ? "+" : "";
  if (isPercentUnit(unit)) {
    return `${sign}${fmtNumber(delta, { digits })} p. b.`;
  }
  const u = String(unit || "").trim();
  const abs = Math.abs(Number(delta));
  const formatted =
    Number.isFinite(abs) && abs >= 10000
      ? fmtCompact(delta, { digits: 1 })
      : fmtNumber(delta, { digits });
  return u ? `${sign}${formatted} ${u}` : `${sign}${formatted}`;
}

export function formatRelativeChange(last, prev) {
  if (prev == null || !Number.isFinite(prev) || prev === 0) return null;
  if (last == null || !Number.isFinite(last)) return null;
  const pct = ((last - prev) / Math.abs(prev)) * 100;
  const sign = pct > 0 ? "+" : "";
  return `${sign}${fmtNumber(pct, { digits: 1 })} %`;
}

/**
 * Poslední hodnota, změna oproti předchozímu bodu a období (časová řada).
 * @param {Array<{ x?: string, y?: number, value?: number, period?: string }>} rows
 */
export function computeTimeSeriesStats(rows) {
  const pts = (Array.isArray(rows) ? rows : [])
    .map((r, idx) => {
      const y = coerceChartNumeric(r?.y ?? r?.value);
      const period = String(r?.period ?? r?.x ?? "").trim();
      const date = parseChartPeriod(period);
      const time = date instanceof Date && !Number.isNaN(date.getTime()) ? date.getTime() : null;
      return { period, y, time, idx };
    })
    .filter((p) => p.period && p.y != null && Number.isFinite(p.y))
    .sort((a, b) => {
      if (a.time != null && b.time != null && a.time !== b.time) return a.time - b.time;
      if (a.time != null && b.time == null) return -1;
      if (a.time == null && b.time != null) return 1;
      return a.idx - b.idx;
    });
  if (!pts.length) return null;

  const last = pts[pts.length - 1];
  const prev = pts.length >= 2 ? pts[pts.length - 2] : null;
  const delta = prev ? last.y - prev.y : null;
  const relativePct = prev ? ((last.y - prev.y) / Math.abs(prev.y)) * 100 : null;

  return {
    lastValue: last.y,
    lastPeriod: last.period,
    prevValue: prev?.y ?? null,
    prevPeriod: prev?.period ?? null,
    delta,
    relativePct,
    hasChange: prev != null,
  };
}
