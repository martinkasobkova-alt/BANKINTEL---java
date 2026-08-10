/**
 * Statistical functions — always operate on raw numeric values.
 */

function toNums(values) {
  return (values || [])
    .map((v) => (typeof v === "number" ? v : Number(v)))
    .filter((v) => Number.isFinite(v));
}

export function mean(values) {
  const nums = toNums(values);
  if (!nums.length) return null;
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}

export function median(values) {
  const nums = [...toNums(values)].sort((a, b) => a - b);
  if (!nums.length) return null;
  const mid = Math.floor(nums.length / 2);
  return nums.length % 2 ? nums[mid] : (nums[mid - 1] + nums[mid]) / 2;
}

export function min(values) {
  const nums = toNums(values);
  return nums.length ? Math.min(...nums) : null;
}

export function max(values) {
  const nums = toNums(values);
  return nums.length ? Math.max(...nums) : null;
}

export function range(values) {
  const lo = min(values);
  const hi = max(values);
  if (lo == null || hi == null) return null;
  return hi - lo;
}

export function variance(values, { sample = true } = {}) {
  const nums = toNums(values);
  if (nums.length < 2) return null;
  const m = mean(nums);
  const sumSq = nums.reduce((acc, v) => acc + (v - m) ** 2, 0);
  return sumSq / (sample ? nums.length - 1 : nums.length);
}

export function standardDeviation(values, options = {}) {
  const v = variance(values, options);
  return v == null ? null : Math.sqrt(v);
}

export function percentile(values, p) {
  const nums = [...toNums(values)].sort((a, b) => a - b);
  if (!nums.length) return null;
  const idx = (p / 100) * (nums.length - 1);
  const lo = Math.floor(idx);
  const hi = Math.ceil(idx);
  if (lo === hi) return nums[lo];
  return nums[lo] + (nums[hi] - nums[lo]) * (idx - lo);
}

export function zScore(value, values) {
  const m = mean(values);
  const sd = standardDeviation(values);
  if (m == null || sd == null || sd === 0) return null;
  return (value - m) / sd;
}

export function simpleGrowthRate(prev, cur) {
  if (prev == null || cur == null || prev === 0) return null;
  return ((cur - prev) / Math.abs(prev)) * 100;
}

export function cagr(startValue, endValue, periods) {
  if (startValue == null || endValue == null || startValue <= 0 || periods <= 0) return null;
  return (Math.pow(endValue / startValue, 1 / periods) - 1) * 100;
}

export function yoyChange(values, lag = 1) {
  const nums = toNums(values);
  if (nums.length <= lag) return null;
  const prev = nums[nums.length - 1 - lag];
  const cur = nums[nums.length - 1];
  return simpleGrowthRate(prev, cur);
}

export function momChange(values) {
  return yoyChange(values, 1);
}

export function qoqChange(values) {
  return yoyChange(values, 1);
}

export function rollingAverage(values, window = 12) {
  const nums = toNums(values);
  const size = Math.max(1, window);
  if (nums.length < size) return null;
  const slice = nums.slice(-size);
  return mean(slice);
}

export function rollingMedian(values, window = 12) {
  const nums = toNums(values);
  const size = Math.max(1, window);
  if (nums.length < size) return null;
  return median(nums.slice(-size));
}

export function rollingStandardDeviation(values, window = 12) {
  const nums = toNums(values);
  const size = Math.max(1, window);
  if (nums.length < size) return null;
  return standardDeviation(nums.slice(-size));
}

export function correlation(seriesA, seriesB) {
  const a = toNums(seriesA);
  const b = toNums(seriesB);
  const n = Math.min(a.length, b.length);
  if (n < 2) return null;
  const ax = a.slice(0, n);
  const bx = b.slice(0, n);
  const meanA = mean(ax);
  const meanB = mean(bx);
  let num = 0;
  let denA = 0;
  let denB = 0;
  for (let i = 0; i < n; i += 1) {
    const da = ax[i] - meanA;
    const db = bx[i] - meanB;
    num += da * db;
    denA += da * da;
    denB += db * db;
  }
  const den = Math.sqrt(denA * denB);
  return den === 0 ? null : num / den;
}

export function rollingCorrelation(seriesA, seriesB, window = 12) {
  const a = toNums(seriesA);
  const b = toNums(seriesB);
  const size = Math.max(2, window);
  if (a.length < size || b.length < size) return null;
  return correlation(a.slice(-size), b.slice(-size));
}

export function linearTrendSlope(values) {
  const nums = toNums(values);
  if (nums.length < 2) return null;
  const n = nums.length;
  const meanX = (n - 1) / 2;
  const meanY = mean(nums);
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    const dx = i - meanX;
    num += dx * (nums[i] - meanY);
    den += dx * dx;
  }
  return den === 0 ? 0 : num / den;
}

export function outlierDetection(values, { threshold = 2.5 } = {}) {
  const nums = toNums(values);
  const m = mean(nums);
  const sd = standardDeviation(nums);
  if (m == null || sd == null || sd === 0) return [];
  return nums
    .map((v, idx) => ({ idx, value: v, z: (v - m) / sd }))
    .filter((item) => Math.abs(item.z) >= threshold);
}

export function latestValue(values) {
  const nums = toNums(values);
  return nums.length ? nums[nums.length - 1] : null;
}

export function latestChange(values) {
  const nums = toNums(values);
  if (nums.length < 2) return null;
  return nums[nums.length - 1] - nums[nums.length - 2];
}

export function distanceFromAverage(value, values) {
  const m = mean(values);
  if (m == null || value == null) return null;
  return value - m;
}

export function distanceFromBenchmark(value, benchmark) {
  if (value == null || benchmark == null) return null;
  return value - benchmark;
}

/** Apply rolling average transform to aligned period rows. */
export function rollingAverageSeries(rows, window = 12) {
  const cleaned = (rows || []).filter((r) => Number.isFinite(Number(r.y ?? r.value_raw)));
  const size = Math.max(1, window);
  if (cleaned.length < size) return [];
  const out = [];
  for (let i = size - 1; i < cleaned.length; i += 1) {
    const slice = cleaned.slice(i - size + 1, i + 1);
    const avg = mean(slice.map((r) => Number(r.y ?? r.value_raw)));
    out.push({
      x: cleaned[i].x ?? cleaned[i].period,
      period: cleaned[i].period ?? cleaned[i].x,
      y: avg,
      value_raw: avg,
    });
  }
  return out;
}

/** Apply rolling median transform. */
export function rollingMedianSeries(rows, window = 12) {
  const cleaned = (rows || []).filter((r) => Number.isFinite(Number(r.y ?? r.value_raw)));
  const size = Math.max(1, window);
  if (cleaned.length < size) return [];
  const out = [];
  for (let i = size - 1; i < cleaned.length; i += 1) {
    const slice = cleaned.slice(i - size + 1, i + 1);
    const med = median(slice.map((r) => Number(r.y ?? r.value_raw)));
    out.push({
      x: cleaned[i].x ?? cleaned[i].period,
      period: cleaned[i].period ?? cleaned[i].x,
      y: med,
      value_raw: med,
    });
  }
  return out;
}

export function indexSeriesTo100(rows, basePeriod = null) {
  const cleaned = (rows || []).filter((r) => Number.isFinite(Number(r.y ?? r.value_raw)));
  if (cleaned.length < 2) return [];
  const baseKey = basePeriod || cleaned[0].x || cleaned[0].period;
  const baseRow = cleaned.find((r) => (r.x || r.period) === baseKey) || cleaned[0];
  const baseVal = Number(baseRow.y ?? baseRow.value_raw);
  if (!baseVal) return [];
  return cleaned.map((r) => {
    const val = Number(r.y ?? r.value_raw);
    return {
      ...r,
      x: r.x ?? r.period,
      period: r.period ?? r.x,
      y: (val / baseVal) * 100,
      value_raw: (val / baseVal) * 100,
      transformation: "index_base_100",
    };
  });
}

export function spreadSeries(rowsA, rowsB) {
  const mapB = new Map((rowsB || []).map((r) => [String(r.x ?? r.period), Number(r.y ?? r.value_raw)]));
  return (rowsA || [])
    .filter((r) => mapB.has(String(r.x ?? r.period)))
    .map((r) => {
      const x = String(r.x ?? r.period);
      const y = Number(r.y ?? r.value_raw) - mapB.get(x);
      return { x, period: x, y, value_raw: y, transformation: "spread" };
    });
}

export function ratioSeries(rowsA, rowsB) {
  const mapB = new Map((rowsB || []).map((r) => [String(r.x ?? r.period), Number(r.y ?? r.value_raw)]));
  return (rowsA || [])
    .filter((r) => {
      const x = String(r.x ?? r.period);
      const denom = mapB.get(x);
      return denom != null && denom !== 0;
    })
    .map((r) => {
      const x = String(r.x ?? r.period);
      const y = Number(r.y ?? r.value_raw) / mapB.get(x);
      return { x, period: x, y, value_raw: y, transformation: "ratio" };
    });
}
