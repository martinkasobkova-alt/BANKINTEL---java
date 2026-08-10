import { fmtCompact, fmtNumber, fmtPeriodAxisTick, fmtPeriodLabel } from "@/lib/format";

export function formatAxisTick(value, { digits = 1 } = {}) {
  if (value == null || !Number.isFinite(Number(value))) return "";
  return fmtCompact(Number(value), { digits });
}

/** Popisek osy X u časových řad — stejná logika jako AradView (fmtPeriodAxisTick). */
export function formatTimeAxisTick(value) {
  const raw = String(value ?? "").trim();
  if (!raw) return "";
  if (/^\d{6,10}$/.test(raw) || /^\d{4}(-|Q)/i.test(raw) || /Q\d/i.test(raw)) {
    return fmtPeriodAxisTick(raw);
  }
  // Čtyřmístné celé číslo na časové ose je VŽDY rok (datum), ne veličina — i historie
  // („1850") nebo projekce za 2100 („2150"). Bez tohoto by fmtCompact udělal z „1850"
  // nesmyslné „2 tis." na spodní ose. Žádné omezení rozsahu: rok je rok.
  if (/^\d{4}$/.test(raw)) {
    return raw;
  }
  if (Number.isFinite(Number(raw)) && raw.length <= 4) {
    return formatAxisTick(raw, { digits: 0 });
  }
  return fmtPeriodAxisTick(raw);
}

export function formatTooltipValue(value, unit = "") {
  if (value == null || Number.isNaN(Number(value))) return "—";
  const formatted = fmtCompact(Number(value));
  return unit ? `${formatted} ${unit}`.trim() : formatted;
}

export function formatTooltipLabel(period) {
  return `Období: ${fmtPeriodLabel(period)}`;
}

export function formatLatestValueLabel(value, unit = "") {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const n = fmtNumber(Number(value), { digits: 2 });
  return unit ? `${n} ${unit}` : n;
}

export function formatPercentChange(value) {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const sign = value > 0 ? "+" : "";
  return `${sign}${fmtNumber(Number(value), { digits: 2 })} %`;
}

export function formatTransformationLabel(transformType) {
  const map = {
    none: "Absolutní hodnota",
    index_base_100: "Index 100",
    yoy_change: "Meziroční změna (YoY)",
    period_change: "Meziobdobí (MoM/QoQ)",
    rolling_average: "Klouzavý průměr",
    rolling_median: "Klouzavý medián",
    spread: "Spread",
    ratio: "Poměr",
    percent_gap: "Procentní rozdíl",
  };
  return map[transformType] || transformType;
}
