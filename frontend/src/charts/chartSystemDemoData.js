/**
 * Vzorová ekonomická data pro Chart System demo playground.
 */

import { createEmptyChartContract, normalizeChartPoint } from "./chartDataContract";
import { CHART_TYPES, CHART_DATA_MODES } from "./chartTypes";
import { applyChartTransform } from "./chartTransforms";
import { spreadSeries, ratioSeries, correlation } from "./chartAnalytics";
import { applyIndexBase100 } from "@/lib/managerChartTransforms";

const GEOS = [
  { code: "CZ", label: "Česko" },
  { code: "DE", label: "Německo" },
  { code: "PL", label: "Polsko" },
];

/** 2020-Q1 … 2025-Q4 (24 čtvrtletí). */
export function demoQuarters() {
  const out = [];
  for (let year = 2020; year <= 2025; year += 1) {
    for (let q = 1; q <= 4; q += 1) {
      out.push(`${year}-Q${q}`);
    }
  }
  return out;
}

function seededValue(seed, periodIdx, base, amplitude = 8, trend = 0.4) {
  const wave = Math.sin(periodIdx / 2.2 + seed) * amplitude;
  const drift = periodIdx * trend;
  return Math.round((base + wave + drift) * 10) / 10;
}

const META = {
  frequency: "quarterly",
  source: "Eurostat",
  dataset: "STS_INPR_M",
  unit: "index 2015=100",
};

/** Průmyslová výroba — CZ / DE / PL, 2020-Q1–2025-Q4. */
export function buildIndustrialProductionContract() {
  const periods = demoQuarters();
  const series = GEOS.map((g) => ({
    id: g.code,
    key: g.code,
    label: `${g.label} — průmyslová výroba`,
    unit: META.unit,
    geo: g.code,
    geo_label: g.label,
  }));

  const bases = { CZ: 102, DE: 98, PL: 105 };
  const data = [];
  periods.forEach((period, idx) => {
    GEOS.forEach((g) => {
      data.push(
        normalizeChartPoint({
          period,
          period_label: period,
          geo: g.code,
          geo_label: g.label,
          series_id: g.code,
          series_label: `${g.label} — průmyslová výroba`,
          value_raw: seededValue(g.code.charCodeAt(0), idx, bases[g.code], 6, 0.35),
          unit: META.unit,
          frequency: META.frequency,
          source: META.source,
          dataset: META.dataset,
          transformation: "none",
        })
      );
    });
  });

  return createEmptyChartContract({
    chart_id: "demo_industrial_production",
    chart_type: CHART_TYPES.LINE,
    title: "Průmyslová výroba (index 2015=100)",
    subtitle: "CZ · DE · PL · Eurostat STS_INPR_M",
    series,
    data,
    metadata: { data_mode: CHART_DATA_MODES.TIME_SERIES, frequency: "quarterly", ...META },
    source: { name: META.source, dataset: META.dataset },
  });
}

/** Jedna řada pro area demo (CZ průmyslová výroba). */
export function buildAreaDemoContract() {
  const full = buildIndustrialProductionContract();
  const czSeries = full.series.find((s) => s.id === "CZ") || full.series[0];
  return {
    ...full,
    chart_id: "demo_area_cz",
    chart_type: CHART_TYPES.AREA,
    title: "Průmyslová výroba — Česko (area)",
    subtitle: "Plošný graf · stejná data jako line",
    series: [czSeries],
    data: full.data.filter((d) => d.series_id === czSeries.id),
  };
}

/** LatestDataMode — inflace poslední měsíc (kategorie na ose X). */
export function buildLatestInflationContract() {
  const countries = [
    { geo: "CZ", label: "Česko", value: 2.4 },
    { geo: "DE", label: "Německo", value: 2.1 },
    { geo: "PL", label: "Polsko", value: 4.8 },
    { geo: "AT", label: "Rakousko", value: 3.2 },
    { geo: "SK", label: "Slovensko", value: 3.0 },
    { geo: "HU", label: "Maďarsko", value: 5.1 },
    { geo: "FR", label: "Francie", value: 1.9 },
    { geo: "IT", label: "Itálie", value: 1.2 },
    { geo: "ES", label: "Španělsko", value: 2.8 },
    { geo: "NL", label: "Nizozemsko", value: 3.5 },
    { geo: "BE", label: "Belgie", value: 2.0 },
    { geo: "SE", label: "Švédsko", value: 1.5 },
  ];
  const series = [{ id: "inflation", key: "inflation", label: "Inflace HICP", unit: "%" }];
  const data = countries.map((c) =>
    normalizeChartPoint({
      period: c.label,
      period_label: c.label,
      geo: c.geo,
      geo_label: c.label,
      series_id: "inflation",
      series_label: "Inflace HICP",
      value_raw: c.value,
      unit: "%",
      frequency: "monthly",
      source: "Eurostat",
      dataset: "HICP_M",
      transformation: "none",
    })
  );
  return createEmptyChartContract({
    chart_id: "demo_latest_inflation",
    chart_type: CHART_TYPES.BAR,
    title: "Inflace HICP — latestDataMode",
    subtitle: "Poslední měsíc · kategorie na ose X · bar scroll >10",
    series,
    data,
    metadata: { data_mode: CHART_DATA_MODES.LATEST, latest_mode: true },
    source: { name: "Eurostat", dataset: "HICP_M" },
  });
}

export function buildRoeBarComparisonContract() {
  const countries = [
    { geo: "DE", label: "Německo", value: 5.6 },
    { geo: "FR", label: "Francie", value: 6.85 },
    { geo: "CZ", label: "Česko", value: 7.2 },
    { geo: "MT", label: "Malta", value: 8.1 },
    { geo: "EA", label: "Eurozóna", value: 13.71 },
    { geo: "NL", label: "Nizozemsko", value: 20.56 },
  ];

  const series = [{ id: "roe", key: "roe", label: "ROE bank", unit: "%" }];
  const data = countries.map((c) =>
    normalizeChartPoint({
      period: c.label,
      period_label: c.label,
      geo: c.geo,
      geo_label: c.label,
      series_id: "roe",
      series_label: "ROE bank",
      value_raw: c.value,
      unit: "%",
      frequency: "quarterly",
      source: "ECB",
      dataset: "ROE_BANKS",
      transformation: "none",
    })
  );

  return createEmptyChartContract({
    chart_id: "demo_roe_bar",
    chart_type: CHART_TYPES.BAR,
    title: "ROE bank — srovnání zemí (2025-Q3)",
    subtitle: "Poslední dostupné období · ECB",
    series,
    data,
    metadata: { data_mode: CHART_DATA_MODES.LATEST, latest_mode: true },
    source: { name: "ECB", dataset: "ROE_BANKS" },
  });
}

/** Úroková sazba vs stavební produkce — relationship dataset. */
export function buildRelationshipContract() {
  const periods = demoQuarters().slice(0, 20);
  const series = [
    { id: "rate", key: "rate", label: "Úroková sazba ECB", unit: "%", axis: "left" },
    { id: "construction", key: "construction", label: "Stavební produkce", unit: "index", axis: "right" },
  ];

  const data = [];
  periods.forEach((period, idx) => {
    const rate = Math.round((1.5 + idx * 0.12 + Math.sin(idx / 3) * 0.3) * 100) / 100;
    const construction = Math.round((95 + idx * 0.8 + Math.cos(idx / 2.5) * 4) * 10) / 10;
    data.push(
      normalizeChartPoint({
        period,
        series_id: "rate",
        series_label: series[0].label,
        value_raw: rate,
        unit: "%",
        frequency: "quarterly",
        source: "ECB / Eurostat",
        dataset: "MIR / STS_CONS",
      }),
      normalizeChartPoint({
        period,
        series_id: "construction",
        series_label: series[1].label,
        value_raw: construction,
        unit: "index",
        frequency: "quarterly",
        source: "ECB / Eurostat",
        dataset: "MIR / STS_CONS",
      })
    );
  });

  return createEmptyChartContract({
    chart_id: "demo_relationship",
    chart_type: CHART_TYPES.LINE,
    title: "Úroková sazba vs stavební produkce",
    subtitle: "Dual-axis pouze při různých jednotkách",
    series,
    data,
    metadata: { data_mode: CHART_DATA_MODES.TIME_SERIES, frequency: "quarterly" },
    source: { name: "ECB / Eurostat" },
  });
}

function rowsForSeries(contract, seriesId) {
  return (contract.data || [])
    .filter((pt) => pt.series_id === seriesId)
    .sort((a, b) => demoQuarters().indexOf(a.period) - demoQuarters().indexOf(b.period))
    .map((pt) => ({ x: pt.period, y: pt.value_raw, period: pt.period }));
}

/** Aplikuje transformaci na celý contract (demo helper). */
export function transformDemoContract(contract, transformId) {
  if (!contract || transformId === "raw" || transformId === "none") {
    return { contract, transformLabel: "Raw" };
  }

  const seriesList = contract.series || [];
  if (!seriesList.length) return { contract, transformLabel: transformId };

  if (transformId === "index_100") {
    const newData = [];
    for (const s of seriesList) {
      const sid = s.id || s.key;
      const rows = rowsForSeries(contract, sid);
      const result = applyIndexBase100([rows]);
      if (!result.ok) continue;
      const indexed = result.transformed_series[0] || [];
      indexed.forEach((r) => {
        newData.push(
          normalizeChartPoint({
            period: r.x,
            series_id: sid,
            series_label: s.label || s.name,
            value_raw: r.y,
            unit: "index 100",
            transformation: "index_base_100",
            source: contract.source?.name,
            dataset: contract.metadata?.dataset,
            frequency: contract.metadata?.frequency,
          })
        );
      });
    }
    return {
      contract: {
        ...contract,
        chart_id: `${contract.chart_id}_idx100`,
        data: newData,
        transformations: [...(contract.transformations || []), { type: "index_100", at: new Date().toISOString() }],
      },
      transformLabel: "Index 100",
    };
  }

  if (transformId === "spread" || transformId === "ratio") {
    if (seriesList.length < 2) return { contract, transformLabel: transformId };
    const a = rowsForSeries(contract, seriesList[0].id || seriesList[0].key);
    const b = rowsForSeries(contract, seriesList[1].id || seriesList[1].key);
    const rows = transformId === "spread" ? spreadSeries(a, b) : ratioSeries(a, b);
    const label = transformId === "spread" ? "Spread (A − B)" : "Ratio (A / B)";
    const newData = rows.map((r) =>
      normalizeChartPoint({
        period: r.x,
        series_id: "transformed",
        series_label: label,
        value_raw: r.y,
        unit: transformId === "ratio" ? "ratio" : contract.series[0].unit || "",
        transformation: transformId,
        source: contract.source?.name,
      })
    );
    return {
      contract: {
        ...contract,
        chart_id: `${contract.chart_id}_${transformId}`,
        chart_type: CHART_TYPES.LINE,
        series: [{ id: "transformed", key: "transformed", label, unit: newData[0]?.unit || "" }],
        data: newData,
        transformations: [...(contract.transformations || []), { type: transformId, at: new Date().toISOString() }],
      },
      transformLabel: label,
    };
  }

  if (transformId === "rolling_average" || transformId === "rolling_median") {
    const window = 4;
    const newData = [];
    for (const s of seriesList) {
      const sid = s.id || s.key;
      const rows = rowsForSeries(contract, sid);
      const result = applyChartTransform(transformId, rows, { window });
      if (!result.ok) continue;
      (result.transformed_series[0] || []).forEach((r) => {
        newData.push(
          normalizeChartPoint({
            period: r.x,
            series_id: sid,
            series_label: s.label,
            value_raw: r.y,
            unit: s.unit,
            transformation: transformId,
            source: contract.source?.name,
          })
        );
      });
    }
    return {
      contract: { ...contract, data: newData, transformations: [...(contract.transformations || []), { type: transformId }] },
      transformLabel: transformId === "rolling_average" ? "Rolling average (4Q)" : "Rolling median (4Q)",
    };
  }

  if (transformId === "yoy" || transformId === "mom" || transformId === "qoq") {
    const newData = [];
    for (const s of seriesList) {
      const sid = s.id || s.key;
      const rows = rowsForSeries(contract, sid);
      const result = applyChartTransform(transformId, rows, { freq: "quarterly" });
      if (!result.ok) continue;
      (result.transformed_series[0] || []).forEach((r) => {
        newData.push(
          normalizeChartPoint({
            period: r.x,
            series_id: sid,
            series_label: s.label,
            value_raw: r.y,
            unit: "%",
            transformation: transformId,
            source: contract.source?.name,
          })
        );
      });
    }
    return {
      contract: { ...contract, data: newData, transformations: [...(contract.transformations || []), { type: transformId }] },
      transformLabel: transformId.toUpperCase(),
    };
  }

  return { contract, transformLabel: transformId };
}

export function computeRelationshipCorrelation(contract) {
  const s = contract.series || [];
  if (s.length < 2) return null;
  const a = rowsForSeries(contract, s[0].id || s[0].key).map((r) => r.y);
  const b = rowsForSeries(contract, s[1].id || s[1].key).map((r) => r.y);
  const n = Math.min(a.length, b.length);
  return correlation(a.slice(0, n), b.slice(0, n));
}

export const DEMO_STATUS = {
  line: "works",
  bar: "works",
  area: "works",
  latest_data_mode: "works",
  indexed: "works",
  yoy: "works",
  mom: "works",
  rolling: "works",
  spread: "works",
  ratio: "works",
  sticky_y_axis: "works_line_scroll",
  bar_scroll: "works",
  scatter: "prepared_no_renderer",
  correlation: "analytics_only",
  dual_axis: "works",
  csv_export: "works",
  xlsx_export: "works_with_backend",
  clipboard: "works",
  png_export: "works",
  toolbar: "works",
  data_table: "works",
  size_variants: "works",
  fullscreen: "works",
  pie: "missing_p2",
  composed: "missing_p2",
};
