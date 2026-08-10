/** Stejné pravidlo jako dřív v `SourcePreview` – čárka jako desetinný oddělovač. */
export function parseNumber(v) {
  if (v === null || v === undefined) return null;
  if (typeof v === "number" && Number.isFinite(v)) return v;
  const n = Number(String(v).replace(/\s/g, "").replace(",", "."));
  return Number.isFinite(n) ? n : null;
}

export function fmtNumber(n, { digits = 2 } = {}) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return "—";
  return Number(n).toLocaleString("cs-CZ", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function fmtInt(n) {
  if (n === null || n === undefined) return "—";
  return Number(n).toLocaleString("cs-CZ");
}

export function fmtCurrency(n, currency = "USD") {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return "—";
  return Number(n).toLocaleString("cs-CZ", {
    style: "currency",
    currency,
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  });
}

export function fmtCompact(n, { digits = 2 } = {}) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return "—";
  const num = Number(n);
  const abs = Math.abs(num);
  const fmt = (v, suffix) =>
    v.toLocaleString("cs-CZ", { minimumFractionDigits: 0, maximumFractionDigits: digits }) + suffix;
  if (abs >= 1e12) return fmt(num / 1e12, " bil.");
  if (abs >= 1e9) return fmt(num / 1e9, " mld.");
  if (abs >= 1e6) return fmt(num / 1e6, " mil.");
  if (abs >= 1e3) return fmt(num / 1e3, " tis.");
  return num.toLocaleString("cs-CZ", { maximumFractionDigits: digits });
}

/**
 * Čitelné období (ČNB/ARAD často YYYYMMDD / YYYYMM / jen rok). Pro osu grafu
 * krátké labely, do tabulky a tooltipu plnější tvar.
 * @param {"axis"|"full"} variant
 */
export function fmtPeriod(period, { variant = "full" } = {}) {
  if (period === null || period === undefined) return "—";
  const s0 = String(period).trim();
  if (!s0) return "—";
  const s = s0;
  const digits = s.replace(/\D+/g, "");

  if (digits.length >= 8) {
    const y = parseInt(digits.slice(0, 4), 10);
    const m = parseInt(digits.slice(4, 6), 10) || 1;
    const d = parseInt(digits.slice(6, 8), 10) || 1;
    if (y < 1000 || y > 3000) return s0;
    const dt = new Date(y, m - 1, d);
    if (Number.isNaN(dt.getTime())) return s0;
    if (variant === "axis") {
      // MM/YY — konzistentní formát, zabrání míchání s Q-formátem
      // Q-formát ("Q1 09") se používá jen přes fmtPeriodAxisTickFreqAware s freqCode="Q"
      return `${String(m).padStart(2, "0")}/${String(y).slice(2)}`;
    }
    return dt.toLocaleDateString("cs-CZ", {
      day: "numeric",
      month: "numeric",
      year: "numeric",
    });
  }

  if (digits.length === 6) {
    const y = parseInt(digits.slice(0, 4), 10);
    const m = parseInt(digits.slice(4, 6), 10) || 1;
    if (y < 1000) return s0;
    const dt = new Date(y, m - 1, 1);
    if (Number.isNaN(dt.getTime())) return s0;
    if (variant === "axis") {
      return `${String(m).padStart(2, "0")}/${String(y).slice(2)}`;
    }
    return dt.toLocaleDateString("cs-CZ", { month: "numeric", year: "numeric" });
  }

  if (/^\d{4}$/.test(digits)) {
    return digits;
  }

  const qm = s.match(/(\d)\s*\.?\s*Q\s*(\d{4})/i);
  if (qm) {
    return variant === "axis" ? `Q${qm[1]} ${qm[2].slice(2)}` : `Q${qm[1]} ${qm[2]}`;
  }

  return s0;
}

/** Osa X v grafech: převést číselné YYYYMMDD i ISO "YYYY-MM(-DD)" na krátký label. */
export function fmtPeriodAxisTick(v) {
  const s = String(v ?? "").trim();
  if (!s) return "—";
  if (/^\d{6,10}$/.test(s)) {
    return fmtPeriod(s, { variant: "axis" });
  }
  // ISO formát YYYY-MM nebo YYYY-MM-DD → odstraň pomlčky a formátuj stejně
  if (/^\d{4}-\d{2}(-\d{2})?$/.test(s)) {
    return fmtPeriod(s.replace(/-/g, ""), { variant: "axis" });
  }
  return s;
}

/**
 * Formát osy X s vědomím frekvence dat — všechny popisky v jednom grafu budou
 * jednotné (jen Q, jen MM/YY atd.), bez míchání formátů.
 * freqCode: "Y" | "H" | "Q" | "M" | "W" | "D"
 */
export function fmtPeriodAxisTickFreqAware(v, freqCode) {
  const s = String(v ?? "").trim();
  if (!s) return "—";
  const digits = s.replace(/\D+/g, "");
  if (digits.length < 4) return fmtPeriodAxisTick(v);
  const y = parseInt(digits.slice(0, 4), 10);
  if (y < 1900 || y > 3000) return fmtPeriodAxisTick(v);
  const yy = String(y).slice(2);
  if (freqCode === "Y" || digits.length === 4) return String(y);
  if (digits.length >= 6) {
    const m = parseInt(digits.slice(4, 6), 10) || 1;
    if (freqCode === "Q") return `Q${Math.ceil(m / 3)} ${yy}`;
    if (freqCode === "H") return `H${m <= 6 ? 1 : 2} ${yy}`;
    // M, W, D → MM/YY
    return `${String(m).padStart(2, "0")}/${yy}`;
  }
  return String(y);
}

/** Tooltip / tabulka: čitelné období jen u číselného YYYYMMDD; jinak původní řetězec. */
export function fmtPeriodLabel(v) {
  const s = String(v ?? "").trim();
  if (!s) return "—";
  if (/^\d{6,10}$/.test(s)) {
    return fmtPeriod(s, { variant: "full" });
  }
  return s;
}

export function fmtDateTime(iso) {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString("cs-CZ", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return String(iso);
  }
}
