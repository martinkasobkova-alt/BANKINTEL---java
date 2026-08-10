/**
 * Parse chart period strings into Date for sorting, filtering, and aggregation.
 * Source-agnostic: ISO (YYYY, YYYY-MM, YYYY-MM-DD, YYYY-Qn), legacy Q labels,
 * Czech month labels from CSU, compact digits.
 */

const CZ_MONTH_NUMBERS = {
  leden: 1,
  unor: 2,
  brezen: 3,
  duben: 4,
  kveten: 5,
  cervenec: 7,
  cerven: 6,
  srpen: 8,
  zari: 9,
  rijen: 10,
  listopad: 11,
  prosinec: 12,
};

function normalizePeriodText(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

export function parseChartPeriod(period) {
  const p = String(period || "").trim();
  if (!p) return null;

  const isoDay = p.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (isoDay) {
    const dt = new Date(parseInt(isoDay[1], 10), parseInt(isoDay[2], 10) - 1, parseInt(isoDay[3], 10));
    return Number.isNaN(dt.getTime()) ? null : dt;
  }

  const isoMonth = p.match(/^(\d{4})-(\d{2})$/);
  if (isoMonth) {
    const dt = new Date(parseInt(isoMonth[1], 10), parseInt(isoMonth[2], 10) - 1, 1);
    return Number.isNaN(dt.getTime()) ? null : dt;
  }

  const isoQuarter = p.match(/^(\d{4})-Q([1-4])$/i);
  if (isoQuarter) {
    const year = parseInt(isoQuarter[1], 10);
    const q = parseInt(isoQuarter[2], 10);
    return new Date(year, (q - 1) * 3, 1);
  }

  const compactQuarter = p.match(/^(\d{4})[.\-/]?[Qq]([1-4])$/i);
  if (compactQuarter) {
    const year = parseInt(compactQuarter[1], 10);
    const q = parseInt(compactQuarter[2], 10);
    return new Date(year, (q - 1) * 3, 1);
  }

  const romanQuarter = p.match(/^([IVXLC]+)\s*\.?\s*[Qq]\s*(\d{4})$/i);
  if (romanQuarter) {
    const roman = romanQuarter[1].toUpperCase();
    const year = parseInt(romanQuarter[2], 10);
    const qByRoman = { I: 1, II: 2, III: 3, IV: 4 };
    const q = qByRoman[roman];
    if (q) return new Date(year, (q - 1) * 3, 1);
  }

  const digits = p.replace(/\D+/g, "");
  if (digits.length >= 8) {
    const year = parseInt(digits.slice(0, 4), 10);
    const month = parseInt(digits.slice(4, 6), 10) || 1;
    const day = parseInt(digits.slice(6, 8), 10) || 1;
    const dt = new Date(year, month - 1, day);
    return Number.isNaN(dt.getTime()) ? null : dt;
  }
  if (digits.length === 6) {
    const year = parseInt(digits.slice(0, 4), 10);
    const month = parseInt(digits.slice(4, 6), 10) || 1;
    const dt = new Date(year, month - 1, 1);
    return Number.isNaN(dt.getTime()) ? null : dt;
  }
  if (/^\d{4}$/.test(digits)) {
    return new Date(parseInt(digits, 10), 0, 1);
  }

  const qMatch = p.match(/(\d)\.?\s*Q\s*(\d{4})/i);
  if (qMatch) {
    const q = parseInt(qMatch[1], 10);
    const year = parseInt(qMatch[2], 10);
    return new Date(year, (q - 1) * 3, 1);
  }

  const normalized = normalizePeriodText(p);
  const yearFromText = normalized.match(/\b(19\d{2}|20\d{2})\b/)?.[1];
  if (yearFromText) {
    for (const [monthName, month] of Object.entries(CZ_MONTH_NUMBERS)) {
      if (normalized.includes(monthName)) {
        return new Date(parseInt(yearFromText, 10), month - 1, 1);
      }
    }
  }

  const yMatch = p.match(/(\d{4})/);
  if (yMatch) return new Date(parseInt(yMatch[1], 10), 0, 1);
  return null;
}

/** @deprecated alias */
export const parsePeriod = parseChartPeriod;
