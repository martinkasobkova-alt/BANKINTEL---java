/**
 * Čistá logika datového zoomu grafu (osa X = indexy v `chartRows`, bez DOM transform).
 * Kolečko mění jen výřez; periodicita, agregace a jednotky zůstávají v AradView nad tímto modulem.
 */
import { parseNumber } from "./format";

function computeNiceTickStep(range, targetTickCount) {
  if (!Number.isFinite(range) || range <= 0) return 1;
  const rough = range / Math.max(1, (Number(targetTickCount) || 4) - 1);
  const exponent = rough > 0 ? Math.floor(Math.log10(rough)) : 0;
  const fraction = rough / 10 ** exponent;
  let niceFraction;
  if (fraction <= 1) niceFraction = 1;
  else if (fraction <= 2) niceFraction = 2;
  else if (fraction <= 2.5) niceFraction = 2.5;
  else if (fraction <= 5) niceFraction = 5;
  else niceFraction = 10;
  return niceFraction * 10 ** exponent;
}

/** Čitelné ticky (0, 1, 2 … místo 1,09 / 2,18) v rozsahu [min, max]. */
function buildNiceTicks(min, max, targetTickCount) {
  const lo = Number(min);
  const hi = Number(max);
  if (!Number.isFinite(lo) || !Number.isFinite(hi)) return [0, 1];
  if (hi === lo) return [lo, hi];
  const range = hi - lo;
  const step = computeNiceTickStep(range, targetTickCount);
  let tickStart = lo >= 0 && lo < step * 0.001 ? 0 : Math.floor(lo / step) * step;
  if (tickStart > lo) tickStart -= step;
  const ticks = [];
  for (let t = tickStart; t <= hi + step * 0.001; t += step) {
    ticks.push(parseFloat(t.toPrecision(12)));
    if (ticks.length > 8) break;
  }
  if (!ticks.length || ticks[0] > lo + step * 0.001) {
    ticks.unshift(parseFloat(lo.toPrecision(12)));
  }
  const last = ticks[ticks.length - 1];
  if (last < hi - step * 0.001) {
    ticks.push(parseFloat(hi.toPrecision(12)));
  }
  return [...new Set(ticks)].sort((a, b) => a - b);
}

export function buildSafeNumericAxis(values, targetTickCount = 4, options = {}) {
  const headroomRatio = Math.max(0, Number(options.headroomRatio ?? 0.06));
  const forceZeroBaseline = options.forceZeroBaseline !== false;
  const nums = (values || [])
    .map((v) => (typeof v === "number" ? v : parseNumber(v)))
    .filter((v) => typeof v === "number" && Number.isFinite(v));
  if (!nums.length) {
    return { min: 0, max: 1, ticks: [0, 0.5, 1] };
  }
  const dataMin = Math.min(...nums);
  const dataMax = Math.max(...nums);
  if (!Number.isFinite(dataMin) || !Number.isFinite(dataMax)) {
    return { min: 0, max: 1, ticks: [0, 0.5, 1] };
  }

  // Osa hodnot má procházet nulou, pokud jsou data jen na jedné straně (kladná / záporná).
  // U smíšených řad ponecháme plný rozsah dat — nula je uvnitř.
  let min;
  let max;
  if (forceZeroBaseline && dataMin >= 0) {
    min = 0;
    max = dataMax;
  } else if (forceZeroBaseline && dataMax <= 0) {
    min = dataMin;
    max = 0;
  } else {
    min = dataMin;
    max = dataMax;
  }

  if (min === max) {
    const pad = Math.max(1, Math.abs(min || max || 1) * 0.05);
    if (forceZeroBaseline && min >= 0) {
      min = 0;
      max += pad;
    } else if (forceZeroBaseline && max <= 0) {
      max = 0;
      min -= pad;
    } else {
      min -= pad;
      max += pad;
    }
  }

  const rangeBeforeHeadroom = max - min;
  if (headroomRatio > 0 && Number.isFinite(rangeBeforeHeadroom) && rangeBeforeHeadroom > 0) {
    if (forceZeroBaseline && dataMin >= 0) {
      max += rangeBeforeHeadroom * headroomRatio;
    } else if (forceZeroBaseline && dataMax <= 0) {
      min -= rangeBeforeHeadroom * headroomRatio;
    } else {
      const half = rangeBeforeHeadroom * (headroomRatio / 2);
      max += half;
      min -= half;
    }
  }

  const range = max - min;
  if (!Number.isFinite(range) || range <= 0) {
    return { min: 0, max: 1, ticks: [0, 0.5, 1] };
  }
  const count = Math.max(2, Math.min(6, Number(targetTickCount) || 4));
  const ticks = buildNiceTicks(min, max, count);
  return { min, max, ticks };
}

/**
 * Doména osy hodnot podle typu grafu.
 * Sloupcový graf: baseline 0 pro kladná / záporná / smíšená data.
 * Čárový / plošný: stávající auto doména z dat.
 */
export function getYAxisDomainForChart(chartType, values, options = {}) {
  const kind = String(chartType || "line").toLowerCase();
  const tickCount = options.tickCount ?? 5;
  const nums = (values || [])
    .map((v) => (typeof v === "number" ? v : coerceChartNumeric(v)))
    .filter((v) => typeof v === "number" && Number.isFinite(v));

  const isBar = kind === "bar" || kind === "column";
  let axisInput;
  if (!nums.length) {
    axisInput = [0];
  } else if (isBar && nums.every((v) => v >= 0)) {
    axisInput = [0, ...nums];
  } else if (isBar && nums.every((v) => v <= 0)) {
    axisInput = [...nums, 0];
  } else {
    axisInput = nums;
  }

  const axis = buildSafeNumericAxis(axisInput, tickCount, options);
  return {
    axis,
    domain: buildRechartsValueDomain(axis, nums),
    useZeroBaselineShape: isBar && nums.length > 0 && nums.every((v) => v >= 0),
  };
}

/** Props pro numerickou osu sloupcového grafu (Y vertikálně, X horizontálně). */
export function barChartValueAxisProps(values, tickCount = 4, options = {}) {
  const spec = getYAxisDomainForChart("bar", values, { ...options, tickCount });
  return {
    domain: spec.domain,
    ticks: spec.axis.ticks,
    ...BAR_VALUE_AXIS,
  };
}

/**
 * Doména sloupcového grafu vždy z celé sady řádků (ne z X zoom výřezu).
 * Sticky Y osa a bars musí sdílet stejný max — jinak grid ukazuje 20,56 a bary škálují k ~13,71.
 */
export function getBarChartValueAxisSpec(allRows, overlayValues = [], tickCount = 5, options = {}) {
  const rowValues = (Array.isArray(allRows) ? allRows : [])
    .map((row) => coerceChartNumeric(row?.y))
    .filter((v) => typeof v === "number" && Number.isFinite(v));
  const extra = (Array.isArray(overlayValues) ? overlayValues : [])
    .map((v) => coerceChartNumeric(v))
    .filter((v) => typeof v === "number" && Number.isFinite(v));
  return getYAxisDomainForChart("bar", [...rowValues, ...extra], { ...options, tickCount });
}

/** Osa Y pro line/area — může reagovat na viditelný výřez (zoom). */
export function getLineChartValueAxisSpec(visibleRows, overlayValues = [], tickCount = 5, options = {}) {
  const keys = ["y"];
  const fallbackValues = (Array.isArray(visibleRows) ? visibleRows : [])
    .map((row) => coerceChartNumeric(row?.y))
    .filter((v) => v !== null);
  const extra = (Array.isArray(overlayValues) ? overlayValues : [])
    .map((v) => coerceChartNumeric(v))
    .filter((v) => v !== null);
  const fallback = buildSafeNumericAxis([...fallbackValues, ...extra], tickCount, options);
  return getSafeYDomain(visibleRows, keys, fallback, tickCount, extra);
}

/** Numerická doména pro Recharts — u kladných sloupců vždy [0, max]. */
export function buildRechartsValueDomain(axisSpec, values) {
  const nums = (values || []).filter((v) => typeof v === "number" && Number.isFinite(v));
  const axisMin = Number(axisSpec?.min);
  const axisMax = Number(axisSpec?.max);
  if (!Number.isFinite(axisMax)) {
    return [0, 1];
  }
  if (nums.length && nums.every((v) => v >= 0)) {
    return [0, Math.max(axisMax, ...nums)];
  }
  if (nums.length && nums.every((v) => v <= 0)) {
    const floor = Number.isFinite(axisMin) ? Math.min(axisMin, ...nums) : Math.min(...nums);
    return [floor, 0];
  }
  if (!Number.isFinite(axisMin)) {
    return [0, axisMax];
  }
  return [axisMin, axisMax];
}

/** Hodnota sloupce — Recharts u `[0, y]` vrací pole, jinak číslo. */
export function chartBarPointValue(value) {
  if (Array.isArray(value)) {
    const end = value[value.length - 1];
    if (typeof end === "number" && Number.isFinite(end)) return end;
    return coerceChartNumeric(end);
  }
  return coerceChartNumeric(value);
}

/** Recharts 3: explicitní `[0, y]` obchází getBaseValueOfBar (který bere dataMin místo 0). */
export function chartRowsWithZeroBaselineBars(rows) {
  return (rows || []).map((row) => {
    const y = coerceChartNumeric(row?.y);
    if (y == null || !Number.isFinite(y)) return row;
    return { ...row, y: [0, y] };
  });
}

/** Recharts 3: u hodnotové osy sloupců vynutit doménu včetně nuly (extendDomain, ne dataMin). */
export const BAR_VALUE_AXIS = { allowDataOverflow: false, niceTicks: "none" };

export function clampNum(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}

/**
 * Plný rozsah osy X jako indexy `[0, n-1]` (časová řada i kategorie „poslední data“).
 */
export function getFullXDomain(data) {
  const n = Array.isArray(data) ? data.length : 0;
  return n > 0 ? [0, n - 1] : [0, 0];
}

/** Viditelná data = výřez pole podle `visibleXDomain` (kontrakt visibleTimeRange jako indexy). */
export function getVisibleData(data, visibleXDomain) {
  if (!Array.isArray(data) || data.length === 0) return [];
  if (!Array.isArray(visibleXDomain) || visibleXDomain.length !== 2) return data;
  const [fullStart, fullEnd] = getFullXDomain(data);
  const start = clampNum(Math.floor(Number(visibleXDomain[0])), fullStart, fullEnd);
  const end = clampNum(Math.ceil(Number(visibleXDomain[1])), start, fullEnd);
  const visible = data.slice(start, end + 1);
  return visible.length ? visible : data;
}

export function coerceChartNumeric(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  let n = parseNumber(value);
  if (n !== null && Number.isFinite(n)) return n;
  const s = String(value ?? "").trim();
  if (!s) return null;
  const firstToken = s.split(/\s+/)[0];
  n = parseNumber(firstToken);
  return n !== null && Number.isFinite(n) ? n : null;
}

/** Hodnoty referenčních čar (průměr, medián, trend) pro výpočet rozsahu osy Y. */
export function collectOverlayYValues(overlaySpec) {
  if (!overlaySpec || typeof overlaySpec !== "object") return [];
  const out = [];
  if (Number.isFinite(overlaySpec.average)) out.push(overlaySpec.average);
  if (Number.isFinite(overlaySpec.median)) out.push(overlaySpec.median);
  const segments = [overlaySpec.trendSegment, overlaySpec.manualTrendSegment];
  for (const seg of segments) {
    if (seg && typeof seg === "object") {
      if (Number.isFinite(seg.yStart)) out.push(seg.yStart);
      if (Number.isFinite(seg.yEnd)) out.push(seg.yEnd);
    }
  }
  if (Array.isArray(overlaySpec.series)) {
    for (const item of overlaySpec.series) {
      if (!item || typeof item !== "object") continue;
      out.push(...collectOverlayYValues(item));
    }
  }
  return out;
}

/** Osa Y jen z raw numerických hodnot viditelných řádků (ignoruje null/NaN/nečísla). */
export function getSafeYDomain(
  visibleData,
  activeSeriesKeys,
  fallbackDomain,
  targetTickCount = 4,
  extraValues = []
) {
  const keys = Array.isArray(activeSeriesKeys) ? activeSeriesKeys.filter(Boolean) : [];
  const rowValues = (Array.isArray(visibleData) ? visibleData : []).flatMap((row) =>
    keys
      .map((key) => coerceChartNumeric(row?.[key]))
      .filter((value) => typeof value === "number" && Number.isFinite(value))
  );
  const extraNums = (Array.isArray(extraValues) ? extraValues : [])
    .map((v) => coerceChartNumeric(v))
    .filter((value) => typeof value === "number" && Number.isFinite(value));
  const rawValues = [...rowValues, ...extraNums];
  const axis = buildSafeNumericAxis(rawValues, targetTickCount);
  if (rawValues.length) return axis;
  return fallbackDomain && Array.isArray(fallbackDomain.ticks) ? fallbackDomain : axis;
}

/**
 * Výřez osy X podle kolečka (deltaY < 0 = přiblížit). null = celá řada.
 * `clientX` + šířka slouží k zarovnání zoomu k pozici kurzoru.
 */
export function computeNextXZoom(prev, n, deltaY, clientX, rectLeft, rectWidth) {
  if (n < 10) return prev;
  const minSpan = clampNum(Math.floor(n * 0.06), 6, Math.max(6, n - 1));
  const a0 = prev?.start ?? 0;
  const b0 = prev?.end ?? n - 1;
  const L = b0 - a0 + 1;
  const zoomIn = deltaY < 0;
  const frac = rectWidth > 1 ? clampNum((clientX - rectLeft) / rectWidth, 0, 1) : 0.5;
  const focal = L <= 1 ? a0 : a0 + frac * (L - 1);

  if (zoomIn) {
    if (L <= minSpan) return prev;
    const newL = Math.max(minSpan, Math.floor(L * 0.86));
    const newA = clampNum(Math.round(focal - frac * (newL - 1)), 0, n - newL);
    const newB = newA + newL - 1;
    if (newA <= 0 && newB >= n - 1) return null;
    return { start: newA, end: newB };
  }
  const newL = Math.min(n, Math.ceil(L * 1.14));
  if (newL >= n) return null;
  const newA = clampNum(Math.round(focal - frac * (newL - 1)), 0, n - newL);
  const newB = newA + newL - 1;
  if (newA <= 0 && newB >= n - 1) return null;
  return { start: newA, end: newB };
}
