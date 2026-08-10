import { compareChartPeriods } from "@/lib/exploreChartCompare";
import { parseChartPeriod } from "@/lib/chartPeriodParse";

export const CHART_FREQUENCY_RANK = Object.freeze({
  D: 0,
  W: 1,
  M: 2,
  Q: 3,
  H: 4,
  Y: 5,
  A: 5,
});

export const CHART_FREQUENCY_LABEL_CS = Object.freeze({
  D: "denni",
  W: "tydenni",
  M: "mesicni",
  Q: "ctvrtletni",
  H: "pololetni",
  Y: "rocni",
});

function pad2(value) {
  return String(value).padStart(2, "0");
}

function finiteNumber(value) {
  if (value == null) return null;
  if (typeof value === "string" && !value.trim()) return null;
  const n = typeof value === "number" ? value : Number(String(value ?? "").replace(/\s/g, "").replace(",", "."));
  return Number.isFinite(n) ? n : null;
}

function normalizeFreq(freq) {
  const f = String(freq || "").trim().toUpperCase();
  if (f === "A") return "Y";
  return CHART_FREQUENCY_RANK[f] != null ? f : "";
}

function isoWeekStart(year, week) {
  const jan4 = new Date(year, 0, 4);
  const day = jan4.getDay() || 7;
  const monday = new Date(jan4);
  monday.setDate(jan4.getDate() - day + 1 + (week - 1) * 7);
  return monday;
}

function isoWeekInfo(date) {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  const day = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  const week = Math.ceil(((d - yearStart) / 86400000 + 1) / 7);
  return { year: d.getUTCFullYear(), week };
}

export function parseChartPeriodInfo(period) {
  const raw = String(period ?? "").trim();
  if (!raw) return null;

  let m = raw.match(/^(\d{4})[-/.]?[Ww](\d{1,2})$/);
  if (m) {
    const year = Number(m[1]);
    const week = Number(m[2]);
    if (week >= 1 && week <= 53) {
      const date = isoWeekStart(year, week);
      return { raw, freq: "W", year, week, month: date.getMonth() + 1, date };
    }
  }

  m = raw.match(/^(\d{4})[-/.]?[Hh]([12])$/) || raw.match(/^[Hh]([12])[-/. ]?(\d{4})$/);
  if (m) {
    const year = m[2]?.length === 4 ? Number(m[2]) : Number(m[1]);
    const half = m[2]?.length === 4 ? Number(m[1]) : Number(m[2]);
    return { raw, freq: "H", year, half, quarter: half === 1 ? 2 : 4, month: half === 1 ? 6 : 12, date: new Date(year, half === 1 ? 5 : 11, 1) };
  }

  m = raw.match(/^(\d{4})[-/.]?[Qq]([1-4])$/) || raw.match(/^[Qq]([1-4])[-/. ]?(\d{4})$/);
  if (m) {
    const year = m[2]?.length === 4 ? Number(m[2]) : Number(m[1]);
    const quarter = m[2]?.length === 4 ? Number(m[1]) : Number(m[2]);
    return { raw, freq: "Q", year, quarter, half: quarter <= 2 ? 1 : 2, month: quarter * 3, date: new Date(year, quarter * 3 - 1, 1) };
  }

  m = raw.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (m) {
    const year = Number(m[1]);
    const month = Number(m[2]);
    const day = Number(m[3]);
    return { raw, freq: "D", year, month, day, quarter: Math.ceil(month / 3), half: month <= 6 ? 1 : 2, date: new Date(year, month - 1, day) };
  }

  m = raw.match(/^(\d{4})[-/.](\d{1,2})$/);
  if (m) {
    const year = Number(m[1]);
    const month = Number(m[2]);
    if (month >= 1 && month <= 12) {
      return { raw, freq: "M", year, month, quarter: Math.ceil(month / 3), half: month <= 6 ? 1 : 2, date: new Date(year, month - 1, 1) };
    }
  }

  const digits = raw.replace(/\D+/g, "");
  if (digits.length >= 8) {
    const year = Number(digits.slice(0, 4));
    const month = Number(digits.slice(4, 6));
    const day = Number(digits.slice(6, 8));
    if (year >= 1900 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
      return { raw, freq: "D", year, month, day, quarter: Math.ceil(month / 3), half: month <= 6 ? 1 : 2, date: new Date(year, month - 1, day) };
    }
  }
  if (digits.length === 6) {
    const year = Number(digits.slice(0, 4));
    const month = Number(digits.slice(4, 6));
    if (year >= 1900 && month >= 1 && month <= 12) {
      return { raw, freq: "M", year, month, quarter: Math.ceil(month / 3), half: month <= 6 ? 1 : 2, date: new Date(year, month - 1, 1) };
    }
  }
  if (/^\d{4}$/.test(digits)) {
    const year = Number(digits);
    return { raw, freq: "Y", year, month: 12, quarter: 4, half: 2, date: new Date(year, 0, 1) };
  }

  const parsed = parseChartPeriod(raw);
  if (!parsed || Number.isNaN(parsed.getTime())) return null;
  const year = parsed.getFullYear();
  const month = parsed.getMonth() + 1;
  const guessedFreq = raw.includes("-") && raw.length >= 10 ? "D" : raw.includes("Q") ? "Q" : "Y";
  return { raw, freq: guessedFreq, year, month, quarter: Math.ceil(month / 3), half: month <= 6 ? 1 : 2, date: parsed };
}

export function bucketPeriodForFrequency(period, targetFrequency) {
  const target = normalizeFreq(targetFrequency);
  const info = parseChartPeriodInfo(period);
  if (!target || !info) return "";
  const year = info.year;
  if (!year) return "";
  if (target === "Y") return String(year);
  if (target === "H") return `${year}-H${info.half || (Number(info.month || 1) <= 6 ? 1 : 2)}`;
  if (target === "Q") return `${year}-Q${info.quarter || Math.ceil(Number(info.month || 1) / 3)}`;
  if (target === "M") return `${year}-${pad2(info.month || 1)}`;
  if (target === "W") {
    const weekInfo = info.week ? { year: info.year, week: info.week } : isoWeekInfo(info.date);
    return `${weekInfo.year}-W${pad2(weekInfo.week)}`;
  }
  if (target === "D") {
    const d = info.date;
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  }
  return "";
}

function periodOrderValue(period) {
  const info = parseChartPeriodInfo(period);
  if (info?.date && !Number.isNaN(info.date.getTime())) return info.date.getTime();
  return compareChartPeriods(period, "1900") + new Date(1900, 0, 1).getTime();
}

function rowsForSeries(wideRows, key) {
  return (Array.isArray(wideRows) ? wideRows : [])
    .map((row) => {
      const x = String(row?.x ?? row?.period ?? "").trim();
      const y = finiteNumber(row?.[key]);
      return x && y != null ? { x, y } : null;
    })
    .filter(Boolean)
    .sort((a, b) => compareChartPeriods(a.x, b.x));
}

function inferSeriesFrequency(rows, explicitFrequency = "") {
  const explicit = normalizeFreq(explicitFrequency);
  if (explicit) return explicit;
  const ranks = [];
  for (const row of rows || []) {
    const freq = normalizeFreq(parseChartPeriodInfo(row.x)?.freq);
    const rank = CHART_FREQUENCY_RANK[freq];
    if (rank != null) ranks.push(rank);
  }
  if (!ranks.length) return "";
  ranks.sort((a, b) => a - b);
  const medianRank = ranks[Math.floor(ranks.length / 2)];
  return Object.keys(CHART_FREQUENCY_RANK).find((freq) => freq !== "A" && CHART_FREQUENCY_RANK[freq] === medianRank) || "";
}

function pickCoarsestFrequency(frequencies) {
  let best = "";
  let bestRank = -1;
  for (const freq of frequencies) {
    const f = normalizeFreq(freq);
    const rank = CHART_FREQUENCY_RANK[f];
    if (rank != null && rank > bestRank) {
      best = f;
      bestRank = rank;
    }
  }
  return best;
}

function seriesNeedsBucketAggregation(rows, targetFrequency) {
  const seen = new Set();
  for (const row of rows) {
    const bucket = bucketPeriodForFrequency(row.x, targetFrequency);
    if (!bucket) return false;
    if (bucket !== row.x) return true;
    if (seen.has(bucket)) return true;
    seen.add(bucket);
  }
  return false;
}

function aggregateSeriesRowsLast(rows, targetFrequency) {
  const buckets = new Map();
  for (const row of rows) {
    const bucket = bucketPeriodForFrequency(row.x, targetFrequency);
    if (!bucket) continue;
    const current = buckets.get(bucket);
    const order = periodOrderValue(row.x);
    if (!current || order >= current.order) {
      buckets.set(bucket, { x: bucket, y: row.y, order });
    }
  }
  return [...buckets.values()]
    .map(({ x, y }) => ({ x, y }))
    .sort((a, b) => compareChartPeriods(a.x, b.x));
}

export function alignMultiSeriesRowsToCoarsestFrequency(wideRows, seriesList, options = {}) {
  const keys = (Array.isArray(seriesList) ? seriesList : [])
    .map((s) => String(s?.key || s?.id || "").trim())
    .filter(Boolean);
  if (keys.length < 2 || !Array.isArray(wideRows) || wideRows.length < 2) {
    return { rows: Array.isArray(wideRows) ? wideRows : [], aligned: false, targetFrequency: "", sourceFrequencies: {} };
  }

  const explicitByKey = options.explicitFrequencyByKey || {};
  const seriesRowsByKey = new Map();
  const sourceFrequencies = {};
  for (const key of keys) {
    const rows = rowsForSeries(wideRows, key);
    seriesRowsByKey.set(key, rows);
    sourceFrequencies[key] = inferSeriesFrequency(rows, explicitByKey[key]);
  }

  const knownFrequencies = Object.values(sourceFrequencies).filter(Boolean);
  const coarsestNativeFrequency = pickCoarsestFrequency(knownFrequencies);
  if (!coarsestNativeFrequency) {
    return { rows: wideRows, aligned: false, targetFrequency: "", sourceFrequencies };
  }

  // Uzivatel muze v periodicite grafu zvolit hrubsi granularitu, nez je prirozeny
  // prusecik zdrojovych rad (napr. vsechny denni -> Rocni srovnani). Jemnejsi volbu,
  // nez podporuje nejhrubsi rada ve srovnani, nelze splnit - v tom pripade se drzime
  // prirozeneho prusecikoveho targetu (stejne jako drive).
  const userTarget = normalizeFreq(options.userTargetFrequency);
  const nativeRank = CHART_FREQUENCY_RANK[coarsestNativeFrequency];
  const userRank = userTarget ? CHART_FREQUENCY_RANK[userTarget] : -1;
  const targetFrequency = userRank > nativeRank ? userTarget : coarsestNativeFrequency;

  const ranks = knownFrequencies.map((f) => CHART_FREQUENCY_RANK[normalizeFreq(f)]).filter((rank) => rank != null);
  const mixedFrequency = new Set(ranks).size > 1;
  const needsAggregation =
    targetFrequency !== coarsestNativeFrequency ||
    mixedFrequency ||
    keys.some((key) => seriesNeedsBucketAggregation(seriesRowsByKey.get(key), targetFrequency));
  if (!needsAggregation) {
    return { rows: wideRows, aligned: false, targetFrequency, sourceFrequencies };
  }

  const alignedByKey = new Map();
  const allPeriods = new Set();
  for (const key of keys) {
    const rows = aggregateSeriesRowsLast(seriesRowsByKey.get(key), targetFrequency);
    alignedByKey.set(key, rows);
    rows.forEach((row) => allPeriods.add(row.x));
  }

  const sortedPeriods = [...allPeriods].sort(compareChartPeriods);
  const maps = new Map();
  for (const key of keys) {
    maps.set(key, new Map((alignedByKey.get(key) || []).map((row) => [row.x, row.y])));
  }

  const rows = sortedPeriods.map((period) => {
    const out = { period, x: period };
    for (const key of keys) {
      const value = maps.get(key)?.get(period);
      if (Number.isFinite(value)) out[key] = value;
    }
    return out;
  });

  return {
    rows,
    aligned: true,
    targetFrequency,
    sourceFrequencies,
    method: "last_observation_in_bucket",
  };
}
