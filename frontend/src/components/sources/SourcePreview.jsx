import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import MySeriesInlineActions from "@/components/myDashboard/MySeriesInlineActions";
import PreviewGroupCompareDropdown from "@/components/sources/PreviewGroupCompareDropdown";
import { buildCompareLeftRefFromWidget, buildMySeriesSavePayloadFromWidget } from "@/lib/mySeriesFromWidget";
import { ChevronDown, ChevronRight, Activity, BarChart3, Network, Play, RefreshCw, Search, Send, Sparkles, X } from "lucide-react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { DataLoadIndicator } from "@/components/ui/DataLoadIndicator";
import CatalogChartShareButtons from "@/components/catalog/CatalogChartShareButtons";
import { fmtCompact, fmtNumber, fmtPeriod, fmtPeriodAxisTick, fmtPeriodLabel, parseNumber } from "@/lib/format";
import { resolveChartFrequencyLabel } from "@/lib/chartFrequencyInfer";
import {
  computeTimeSeriesStats,
  formatAbsoluteChange,
  formatRelativeChange,
  formatSeriesStatValue,
} from "@/charts/chartTimeSeriesStats";
import { resolveCatalogValueDescriptor } from "@/lib/bisValueDescriptor";
import {
  fetchSeriesConceptExplanation,
  fetchSeriesConceptFollowup,
  inferFindInDataQuery,
  seriesConceptExplainSourceNote,
} from "@/lib/catalogSeriesConceptExplain";
import { fetchRelatedSeries } from "@/lib/catalogRelatedSeries";
import api from "@/lib/api";
import { enrichChartExplainMeta } from "@/lib/chartDataQuality";
import { resolvePreviewAxisFields } from "@/lib/pickPreviewFields";
import { mergeRechartsTooltipProps } from "@/lib/rechartsTooltipShared";
import {
  chartBarPointValue,
  coerceChartNumeric,
  getYAxisDomainForChart,
  chartRowsWithZeroBaselineBars,
} from "@/lib/chartZoomHelpers";
import {
  buildTimeSeriesPivotFromRows,
  distinctSeriesIdsInRows,
  isCategoricalGroupField,
} from "@/lib/chartTimeSeriesPivot";
import {
  asGeoCode,
  buildGeoChartRows,
  buildGeoLabelLookup,
  buildGeoOptionsFromRows,
  capGeoList,
  collectGeoOptions,
  detectGeoKeys,
  geoCodesWithChartData,
  geoDisplayLabel,
  geoSeriesNeedsPointMarkers,
  isGeoDimensionKeyCandidate,
  isGeoCrossSectionSnapshot,
  isPlausibleGeoCode,
  isSparseGeoCompare,
  mergeSelectedGeoRows,
  readGeoDimensionOptions,
  readGeoOptionsFromGeoDimensions,
  readGeoOptionsFromDimensionMeta,
  readGeoOptionsFromSelectableDimensions,
  readGeoValuesFromAny,
  toNormalizedToken,
} from "@/lib/sourcePreviewGeo";
import {
  applyEurostatFigaroFixedFilters,
  buildCountryLabelLookup,
  countryDimensionLabel,
  extractCountryCodesFromFilters,
  filterFigaroOriginCountryOptions,
  formatFigaroCountryContext,
  isCountryDimensionKey,
  pickDefaultFigaroOriginCountries,
  readCountryOptionsFromDimensions,
  resolvePrimaryCountryDimensionKey,
} from "@/lib/sourcePreviewCountry";
import { applyIndustryLinkedFilters } from "@/lib/sourcePreviewIndustryFilters";
import { mapCompareCountryToImfCode, mapCompareCountryToWorldBankCode } from "@/lib/exploreCompareGeo";
import {
  buildUserChoiceDimensions,
  userChoiceDimensionsHelpText,
  userChoiceDimensionsSectionTitle,
  formatUserChoiceOptionLabel,
  getUserChoiceDimensionLabel,
  isUserSelectableDimensionKey,
  resolveDimensionValueLabel,
} from "@/lib/sourcePreviewUserChoiceDimensions";
import EurostatCascadingDimensionPicker from "@/components/sources/EurostatCascadingDimensionPicker";
import { formatPreviewMessage } from "@/lib/previewNormalizer";

/** @deprecated import z `@/lib/format` */
export { parseNumber };

function previewText(value, fallback = "") {
  return formatPreviewMessage(value, fallback);
}

const CROSS_SECTION_FIELD_BLACKLIST = new Set([
  "value", "amount", "hodnota", "obs_value", "raw_value", "y",
  "date", "datum", "time", "time_period", "period", "year", "rok", "roky", "obdobi", "období",
]);
/** Barvy pro porovnání více zemí / ukazatelů v jednom grafu (Eurostat, ARAD, …). */
[
  "hodnoty",
  "value_num",
  "observation_value_raw",
  "month",
  "months",
  "mesic",
  "mesice",
  "quarter",
  "quarters",
  "ctvrtleti",
  "day",
  "days",
  "den",
  "dny",
  "key",
  "set_id",
  "snapshot_id",
  "indicator_name",
  "series_name",
  "full_path",
  "catalog_path",
  "catalog_id",
  "catalog_label",
  "path",
].forEach((key) => CROSS_SECTION_FIELD_BLACKLIST.add(key));

const MULTI_LINE_STROKES = [
  "hsl(202 90% 42%)",
  "hsl(280 65% 45%)",
  "hsl(145 55% 38%)",
  "hsl(32 92% 45%)",
  "hsl(340 75% 48%)",
  "hsl(200 55% 52%)",
  "hsl(48 88% 42%)",
  "hsl(265 70% 55%)",
  "hsl(12 80% 50%)",
  "hsl(175 60% 40%)",
  "hsl(220 70% 58%)",
  "hsl(85 55% 42%)",
];

function normalizePreviewSeriesKeys(preview) {
  const raw = Array.isArray(preview?.selected_indicators) ? preview.selected_indicators : [];
  return [...new Set(raw.map((x) => String(x ?? "").trim()).filter(Boolean))];
}

function normalizeSeriesIdList(input) {
  const raw = Array.isArray(input) ? input : input != null ? [input] : [];
  return [...new Set(raw.map((x) => String(x ?? "").trim()).filter(Boolean))];
}

function seriesStrokeAt(index) {
  return MULTI_LINE_STROKES[index % MULTI_LINE_STROKES.length];
}

function seriesLabelForChart(indicators, id) {
  const f = indicators.find((i) => i.id === id);
  if (f?.name && String(f.name).trim() && f.name !== id) return String(f.name).trim();
  return id;
}

function resolveChartTooltipSeriesLabel(name, { indicators, geoLabelByCode } = {}) {
  const raw = String(name || "").trim();
  if (!raw) return "Řada";
  const id = raw.replace(/^s_/, "");
  return geoLabelByCode?.[id] || seriesLabelForChart(indicators || [], id) || id || raw;
}

function formatSourceTableCell(fieldName, value) {
  const raw = value == null ? "" : String(value);
  if (raw === "") return "";
  const fn = (fieldName || "").toLowerCase();
  const isTime =
    /^(date|time|period|obdob)/i.test(fn) || fn === "rok" || fn.endsWith("_date");
  const digitsOnly = raw.replace(/\D+/g, "");
  if (isTime && digitsOnly.length >= 6) {
    return fmtPeriod(raw, { variant: "full" });
  }
  const n = parseNumber(value);
  if (n !== null) {
    if (Number.isInteger(n) && n >= 1900 && n <= 2200 && /rok|year/i.test(fn)) {
      return String(n);
    }
    return Number.isInteger(n) ? fmtNumber(n, { digits: 0 }) : fmtNumber(n);
  }
  return raw;
}

// Mapování Eurostat kódů jednotek na čitelné popisky.
const EUROSTAT_UNIT_LABELS = {
  PC: "%", PC_GDP: "% HDP", PC_HAB: "% (na obyvatele)", PC_EU27_2020: "% (EU27=100)",
  PC_ACT: "% aktivních", PC_EMP: "% zaměstnaných", PC_POP: "% populace",
  MIO_EUR: "mil. EUR", MIO_NAT: "mil. (nár. měna)", MIO_USD: "mil. USD",
  MIO_T: "mil. tun", MIO_NR: "mil. počet", MIO_PPS: "mil. PPS",
  MIO_PPS_EU27_2020: "mil. PPS (EU27=100)",
  THS_EUR: "tis. EUR", THS_T: "tis. tun", THS_NR: "tis. počet", THS: "tis.",
  EUR: "EUR", EUR_HAB: "EUR/obyvatele", EUR_MEUR: "mil. EUR",
  USD: "USD", NR: "počet",
  I05: "index 2005=100", I10: "index 2010=100", I15: "index 2015=100",
  I16: "index 2016=100", I20: "index 2020=100",
  PPS: "PPS", PPS_HAB: "PPS/obyvatele", PPS_EU27_2020_HAB: "PPS (EU27=100)/obyvatele",
  CLV_PCH_PRE: "% změna (stálé ceny)", CLV_MEUR: "mil. EUR (stálé ceny)",
  CP_MEUR: "mil. EUR (běžné ceny)", CP_MNAC: "mil. (běžné ceny, nár. měna)",
  GWH: "GWh", TJ: "TJ", KTOE: "ktoe", MIO_TOE: "mil. toe",
  RT: "%, meziroční tempo", PCH_PRE: "% změna", PCH_SM: "% změna (stejné období)",
  INX_A_AVG: "index (průměr)", MPSD_EUR: "EUR/MWh",
};

const DIMENSION_LABELS = {
  geo: "Země",
  ref_area: "Země",
  country: "Země",
  coicop: "Kategorie / COICOP",
  unit: "Jednotka",
  freq: "Frekvence",
  s_adj: "Sezónní očištění",
  sex: "Pohlaví",
  age: "Věk",
  na_item: "Ukazatel národních účtů",
  time: "Období",
  currency: "Měna",
  nrg_cons: "Spotřeba energie",
  nrg_prc: "Složka ceny energie",
  nrg_d: "Energetický produkt",
  crops: "Plodina",
  meat: "Druh masa",
  meatitem: "Položka masa",
  animals: "Druh zvířete",
  dairyprod: "Mléčný produkt",
  milkitem: "Položka mléka",
  product: "Produkt / vstup",
  partner: "Partnerská země",
  nace_r2: "Odvětví (NACE)",
  nace_r1: "Odvětví (NACE, starší revize)",
  ceparema: "Doména životního prostředí (CEPA)",
  env_domain: "Environmentální doména",
  env_prot: "Ochrana životního prostředí",
  env_econ: "Ekonomická činnost (env.)",
  ind_use: "Využití / odvětví",
  cpa2_1: "Produkt (CPA)",
  c_orig: "Země původu",
  c_dest: "Země určení",
  c_imp: "Země dovozu",
  c_exp: "Země vývozu",
  stk_flow: "Typ pohybu zásob",
  rawmat: "Surovina",
  tra_meas: "Typ dopravního ukazatele",
  indic: "Indikátor",
};

const GEO_DIM_KEYS = new Set(["geo", "ref_area", "country"]);
const EMPTY_ARRAY = [];

function catalogChartShellClass(catalogChartSize, { placeholder = false, split = false } = {}) {
  if (catalogChartSize === "fullscreen") {
    return placeholder
      ? "relative max-md:flex-1 max-md:min-h-[8rem] max-md:h-auto md:min-h-[min(52vh,640px)] md:h-[min(62dvh,720px)] min-w-0 flex items-center justify-center rounded-xl border border-dashed border-amber-200 bg-amber-50/40 px-4 text-center text-[11px] text-amber-950 leading-snug"
      : "relative max-md:flex-1 max-md:min-h-0 max-md:h-full md:min-h-[min(52vh,640px)] md:h-[min(62dvh,720px)] min-w-0 overflow-x-hidden max-md:overflow-hidden flex flex-col";
  }
  if (catalogChartSize === "detail-expanded") {
    if (split) {
      return placeholder
        ? "relative flex-1 min-h-[min(64svh,38rem)] md:min-h-0 h-full min-w-0 flex items-center justify-center rounded-xl border border-dashed border-amber-200 bg-amber-50/40 px-4 text-center text-[11px] text-amber-950 leading-snug"
        : "relative flex-1 min-h-[min(64svh,38rem)] md:min-h-0 h-full min-w-0 overflow-hidden";
    }
    return placeholder
      ? "relative min-h-[min(48vh,26rem)] h-[min(52vh,28rem)] min-w-0 flex items-center justify-center rounded-xl border border-dashed border-amber-200 bg-amber-50/40 px-4 text-center text-[11px] text-amber-950 leading-snug"
      : "relative min-h-[min(48vh,26rem)] h-[min(52vh,28rem)] min-w-0 overflow-x-hidden";
  }
  if (catalogChartSize === "detail") {
    if (split) {
      return placeholder
        ? "relative flex-1 min-h-[min(58svh,34rem)] md:min-h-0 h-full min-w-0 flex items-center justify-center rounded-xl border border-dashed border-amber-200 bg-amber-50/40 px-4 text-center text-[11px] text-amber-950 leading-snug"
        : "relative flex-1 min-h-[min(58svh,34rem)] md:min-h-0 h-full min-w-0 overflow-hidden";
    }
    return placeholder
      ? "relative min-h-[14rem] h-[min(40vh,22rem)] min-w-0 flex items-center justify-center rounded-xl border border-dashed border-amber-200 bg-amber-50/40 px-4 text-center text-[11px] text-amber-950 leading-snug"
      : "relative min-h-[14rem] h-[min(40vh,22rem)] min-w-0 overflow-x-hidden";
  }
  return placeholder
    ? "relative min-h-[14rem] h-[min(45vh,20rem)] min-w-0 flex items-center justify-center rounded-xl border border-dashed border-amber-200 bg-amber-50/40 px-4 text-center text-[11px] text-amber-950 leading-snug"
    : "relative min-h-[14rem] h-[min(45vh,20rem)] min-w-0 overflow-x-hidden";
}
const MAX_GEO_SELECTION = 8;
const QUICK_COMPARE_GEOS = [
  "CZ", "DE", "AT", "FR", "ES", "PL", "IT", "NL", "BE", "SK", "HU", "RO",
  "BG", "SI", "HR", "LT", "LV", "EE", "FI", "IE", "LU", "PT", "GR", "SE", "DK",
  "EU27_2020", "EA20", "EA",
];
const COUNTRY_PICKER_FALLBACK_GEOS = [
  ...QUICK_COMPARE_GEOS,
  "NO", "CH", "IS", "LI", "GB", "US", "CA", "JP", "CN", "KR", "AU", "NZ", "BR", "IN", "MX", "TR",
];

function dimKey(value) {
  return String(value ?? "")
    .trim()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

function isGeoDimensionField(field) {
  return GEO_DIM_KEYS.has(dimKey(field)) || isCountryDimensionKey(field) || isGeoDimensionKeyCandidate(field);
}

function isTimeOrMeasureDimensionField(field, timeField = "") {
  const raw = String(field || "").trim();
  const key = dimKey(raw);
  const tf = dimKey(timeField);
  if (!key) return false;
  if (tf && key === tf) return true;
  return CROSS_SECTION_FIELD_BLACKLIST.has(key)
    || key.endsWith("_date")
    || key.endsWith("_period")
    || key.endsWith("_time")
    || key.endsWith("_value");
}

function getDimensionLabel(dim, availableDimensions) {
  const key = dimKey(dim);
  const fromMeta = availableDimensions?.[dim]?.label || availableDimensions?.[key]?.label;
  if (fromMeta && String(fromMeta).trim()) return String(fromMeta).trim();
  if (isCountryDimensionKey(key)) return countryDimensionLabel(key);
  return DIMENSION_LABELS[key] || String(dim || "").trim() || "Filtr";
}

function normalizeFilterValues(value, { geo = false } = {}) {
  const arr = Array.isArray(value) ? value : value == null || value === "" ? [] : [value];
  const out = [];
  const seen = new Set();
  for (const raw of arr) {
    let code = geo ? asGeoCode(raw) : String(raw ?? "").trim();
    if (geo && code && !isPlausibleGeoCode(code)) continue;
    if (!code || seen.has(code)) continue;
    seen.add(code);
    out.push(code);
  }
  return out;
}

function sameStringArray(a, b) {
  if (!Array.isArray(a) || !Array.isArray(b)) return false;
  if (a.length !== b.length) return false;
  return a.every((v, i) => v === b[i]);
}

function geoOptionsEqual(a, b) {
  if (a === b) return true;
  if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
  return a.every((opt, i) => {
    const other = b[i];
    return (
      opt?.value === other?.value
      && String(opt?.label || "") === String(other?.label || "")
      && Number(opt?.rowCount || 0) === Number(other?.rowCount || 0)
    );
  });
}

function dimFiltersEqual(a, b) {
  if (a === b) return true;
  if (!a || !b || typeof a !== "object" || typeof b !== "object") return false;
  const keysA = Object.keys(a);
  const keysB = Object.keys(b);
  if (keysA.length !== keysB.length) return false;
  return keysA.every((key) => String(a[key] ?? "") === String(b[key] ?? ""));
}

function readDimensionOptions(raw, { geo = false } = {}) {
  const out = [];
  const push = (item) => {
    if (item == null) return;
    const obj = item && typeof item === "object" ? item : null;
    const rawCode = obj ? obj.code ?? obj.id ?? obj.value : item;
    const code = geo ? asGeoCode(rawCode) : String(rawCode ?? "").trim();
    if (!code) return;
    const label = obj ? String(obj.label || obj.name || code).trim() : code;
    out.push({ value: code, label: label || code, rowCount: 0 });
  };
  if (Array.isArray(raw)) raw.forEach(push);
  else if (raw && typeof raw === "object") {
    if (Array.isArray(raw.sample_options)) raw.sample_options.forEach(push);
    else if (Array.isArray(raw.values)) raw.values.forEach(push);
    else if (Array.isArray(raw.sample_values)) raw.sample_values.forEach(push);
    else if (raw.category?.index && typeof raw.category.index === "object") {
      const labels = raw.category.label && typeof raw.category.label === "object" ? raw.category.label : {};
      Object.keys(raw.category.index).forEach((code) => push({ code, label: labels[code] || code }));
    } else {
      Object.entries(raw).forEach(([code, value]) => {
        if (["size", "label", "description", "sample_values", "sample", "type"].includes(dimKey(code))) return;
        if (value && typeof value === "object") push({ code, label: value.label || value.name || code });
        else push(code);
      });
    }
  }
  const seen = new Set();
  return out.filter((opt) => {
    if (!opt.value || seen.has(opt.value)) return false;
    seen.add(opt.value);
    return true;
  });
}

function formatOptionLabel(opt) {
  const label = String(opt?.label || "").trim();
  const value = String(opt?.value || "").trim();
  if (!value) return label;
  if (!label || label === value) return value;
  return label;
}

function hasReplacementChar(value) {
  return String(value ?? "").includes("\uFFFD");
}

function cleanHumanLabel(value) {
  const text = String(value ?? "").trim();
  return text && !hasReplacementChar(text) ? text : "";
}

function addCleanIndicatorLabel(out, id, label) {
  const key = String(id ?? "").trim();
  const clean = cleanHumanLabel(label);
  if (!key || !clean || clean === key) return;
  if (!out[key] || hasReplacementChar(out[key])) out[key] = clean;
}

function addIndicatorLabelsFromDimension(out, meta) {
  if (!meta || typeof meta !== "object") return;
  readDimensionOptions(meta).forEach((opt) => addCleanIndicatorLabel(out, opt.value, opt.label));
  const valueLabels = meta.value_labels && typeof meta.value_labels === "object" ? meta.value_labels : {};
  Object.entries(valueLabels).forEach(([id, label]) => addCleanIndicatorLabel(out, id, label));
}

function isCountryLevelGeoCode(code) {
  const c = asGeoCode(code);
  if (!c) return false;
  if (/^[A-Z]{2,3}$/.test(c)) return true;
  return /^(EU|EA)\d/.test(c) || /^EU\d+_\d{4}$/.test(c);
}

function dimensionMetaForKey(availableDimensions, key) {
  const raw = String(key ?? "").trim();
  if (!raw || !availableDimensions || typeof availableDimensions !== "object") return null;
  if (availableDimensions[raw]) return availableDimensions[raw];
  const folded = dimKey(raw);
  if (availableDimensions[folded]) return availableDimensions[folded];
  return Object.entries(availableDimensions).find(([candidate]) => dimKey(candidate) === folded)?.[1] || null;
}

function isIndicatorLikeDimensionKey(key) {
  return /indicator|ukazatel|series|measure|metric|instrument|variable/i.test(String(key || ""));
}

function rowValueIgnoreCase(row, key) {
  if (!row || typeof row !== "object") return "";
  const raw = String(key ?? "").trim();
  if (!raw) return "";
  if (row[raw] != null) return row[raw];
  const folded = dimKey(raw);
  const found = Object.entries(row).find(([candidate]) => dimKey(candidate) === folded);
  return found ? found[1] : "";
}

function firstCleanLabelForId(id, ...values) {
  const key = String(id ?? "").trim();
  for (const value of values) {
    const clean = cleanHumanLabel(value);
    if (clean && clean !== key) return clean;
  }
  return "";
}

function buildIndicatorLabelLookup(availableDimensions, rows, remoteLabels, groupField = "") {
  const out = {};
  Object.entries(remoteLabels || {}).forEach(([id, label]) => addCleanIndicatorLabel(out, id, label));

  addIndicatorLabelsFromDimension(out, dimensionMetaForKey(availableDimensions, groupField));
  for (const key of ["indicator_id", "indicator", "series_id", "series"]) {
    addIndicatorLabelsFromDimension(out, dimensionMetaForKey(availableDimensions, key));
  }
  Object.entries(availableDimensions || {}).forEach(([key, meta]) => {
    if (isIndicatorLikeDimensionKey(key)) {
      addIndicatorLabelsFromDimension(out, meta);
    }
  });

  (rows || []).forEach((row) => {
    const id =
      rowValueIgnoreCase(row, groupField)
      || row?.indicator_id
      || row?.indicator
      || row?.series_id
      || row?.series;
    if (!id) return;
    const labelFromDimension = groupField
      ? resolveDimensionValueLabel(groupField, id, availableDimensions)
      : "";
    addCleanIndicatorLabel(
      out,
      id,
      firstCleanLabelForId(
        id,
        labelFromDimension,
        rowValueIgnoreCase(row, `${groupField}_label`),
        row?.indicator_label,
        row?.indicator_name,
        row?.series_label,
        row?.series_name,
        row?.variable_label,
        row?.variable_name,
        row?.name,
      ),
    );
  });
  return out;
}

function resolveIndicatorDisplayName(indicator, labelLookup) {
  const id = String(indicator?.id ?? "").trim();
  const fromLookup = cleanHumanLabel(labelLookup?.[id]);
  if (fromLookup) return fromLookup;
  const fromName = cleanHumanLabel(indicator?.name);
  if (fromName && fromName !== id) return fromName;
  return id;
}

/** Stejný název u více ID (ARAD dimenze) — v selectu jen jednou, první výskyt vyhrává. */
function dedupeIndicatorsForSelect(indicators, labelLookup) {
  const seenLabels = new Set();
  const seenIds = new Set();
  const out = [];
  for (const ind of indicators || []) {
    const id = String(ind?.id ?? "").trim();
    if (!id || seenIds.has(id)) continue;
    const label = resolveIndicatorDisplayName(ind, labelLookup).trim().toLowerCase();
    if (label && seenLabels.has(label)) continue;
    seenIds.add(id);
    if (label) seenLabels.add(label);
    out.push(ind);
  }
  return out;
}

function formatIndicatorSelectLabel(indicator) {
  const id = String(indicator?.id ?? "").trim();
  const name = String(indicator?.name ?? "").trim();
  return name && name !== id ? name : id;
}

function filterCatalogChartSlotBySeries(chartSlot, seriesIds) {
  if (!chartSlot || !React.isValidElement(chartSlot)) return chartSlot;
  const selected = normalizeSeriesIdList(seriesIds);
  if (!selected.length) return chartSlot;

  const data = chartSlot.props?.data;
  const series = Array.isArray(data?.series) ? data.series : [];
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  if (!data?.multi_series || series.length <= 1 || !rows.length) return chartSlot;

  const allKeys = new Set(series.map((item) => String(item?.key || "").trim()).filter(Boolean));
  const visibleIds = selected.filter((id) => allKeys.has(id));
  if (!visibleIds.length || visibleIds.length === series.length) return chartSlot;

  const visible = new Set(visibleIds);
  const filteredSeries = series.filter((item) => visible.has(String(item?.key || "").trim()));
  const filteredRows = rows.map((row) => {
    if (!row || typeof row !== "object" || Array.isArray(row)) return row;
    const next = { ...row };
    allKeys.forEach((key) => {
      if (!visible.has(key)) delete next[key];
    });
    return next;
  });

  const widget = chartSlot.props?.widget || {};
  const config = widget.config || {};
  return React.cloneElement(chartSlot, {
    data: {
      ...data,
      rows: filteredRows,
      series: filteredSeries,
      selected_indicators: visibleIds,
    },
    widget: {
      ...widget,
      config: {
        ...config,
        selected_indicators: visibleIds,
        chart_series_mode: visibleIds.length > 1 ? "multi" : "single",
      },
    },
  });
}

function resolveFilterValueLabel(dimKey, rawValue, availableDimensions) {
  const code = String(rawValue ?? "").trim();
  if (!code) return "";
  const dimMeta = availableDimensions?.[dimKey];
  const fromMap = dimMeta?.value_labels?.[code];
  if (fromMap && String(fromMap).trim()) return String(fromMap).trim();
  const option = readDimensionOptions(dimMeta).find((opt) => opt.value === code);
  if (option?.label && option.label !== code) return option.label;
  return code;
}

function formatAppliedFilters(filters, availableDimensions, filterDisplayLabels) {
  if (filterDisplayLabels && typeof filterDisplayLabels === "object") {
    const human = Object.values(filterDisplayLabels).map((x) => String(x || "").trim()).filter(Boolean);
    if (human.length) return human.join(" · ");
  }
  if (!filters || typeof filters !== "object") return "";
  const skip = new Set(["query_mode", "geo_scope", "lasttimeperiod", "lastTimePeriod"]);
  return Object.entries(filters)
    .filter(([key, value]) => !skip.has(String(key || "").trim()) && value != null && String(value).trim() !== "")
    .map(([key, value]) => {
      const dimLabel = getDimensionLabel(key, availableDimensions);
      const values = Array.isArray(value) ? value : [value];
      const rendered = values
        .map((item) => resolveFilterValueLabel(key, item, availableDimensions))
        .filter(Boolean);
      return `${dimLabel}: ${rendered.join(", ")}`;
    })
    .join(" · ");
}

function mergeGeoOptionLists(...lists) {
  const byCode = new Map();
  for (const list of lists) {
    for (const opt of list || []) {
      const value = asGeoCode(opt?.value);
      if (!value) continue;
      const label = String(opt?.label || value).trim() || value;
      const rowCount = Number(opt?.rowCount || 0);
      const fromMetadata = opt?.fromMetadata === true || opt?.availabilityKnown === false;
      const availabilityKnown = opt?.availabilityKnown === true || rowCount > 0 || !fromMetadata;
      const prev = byCode.get(value);
      if (!prev) {
        byCode.set(value, { value, label, rowCount, fromMetadata, availabilityKnown });
        continue;
      }
      const nextRowCount = Math.max(Number(prev.rowCount || 0), rowCount);
      byCode.set(value, {
        value,
        label: prev.label && prev.label !== prev.value ? prev.label : label,
        rowCount: nextRowCount,
        fromMetadata: Boolean(prev.fromMetadata && fromMetadata && nextRowCount <= 0),
        availabilityKnown: Boolean(prev.availabilityKnown || availabilityKnown || nextRowCount > 0),
      });
    }
  }
  return Array.from(byCode.values()).sort((a, b) => a.label.localeCompare(b.label, "cs"));
}

function mergeDimensionFilters(...filters) {
  const out = {};
  for (const src of filters) {
    if (!src || typeof src !== "object" || Array.isArray(src)) continue;
    for (const [k, v] of Object.entries(src)) {
      const key = String(k || "").trim();
      if (!key || key === "indicator_id") continue;
      const normalizedKey = key.toLowerCase().replace(/[^a-z0-9]+/g, "");
      if (normalizedKey === "querymode" || normalizedKey === "lasttimeperiod") continue;
      if (Array.isArray(v)) {
        const vals = normalizeFilterValues(v, { geo: isGeoDimensionField(key) });
        if (vals.length) out[key] = vals;
      } else if (v != null && String(v).trim()) {
        out[key] = isGeoDimensionField(key) ? asGeoCode(v) : String(v).trim();
      }
    }
  }
  const hasExplicitGeo =
    normalizeFilterValues(out.geo, { geo: true }).length > 0 ||
    normalizeFilterValues(out.REF_AREA, { geo: true }).length > 0;
  if (hasExplicitGeo) {
    delete out.geo_scope;
  }
  return out;
}

function replaceDimensionFilter(baseFilters, key, value) {
  const out = mergeDimensionFilters(baseFilters);
  const normalizedKey = String(key || "").trim();
  if (!normalizedKey || normalizedKey === "indicator_id") return out;
  if (Array.isArray(value)) {
    out[normalizedKey] = normalizeFilterValues(value, { geo: isGeoDimensionField(normalizedKey) });
  } else if (value == null || String(value).trim() === "") {
    delete out[normalizedKey];
  } else {
    out[normalizedKey] = isGeoDimensionField(normalizedKey) ? asGeoCode(value) : String(value).trim();
  }
  return out;
}

function hasGeoFilterSelection(filters) {
  if (!filters || typeof filters !== "object" || Array.isArray(filters)) return false;
  return Object.entries(filters).some(([key, value]) =>
    isGeoDimensionField(key) && normalizeFilterValues(value, { geo: true }).length > 0
  );
}

function droppedFilterWarnings(dropped) {
  const out = [];
  if (!dropped || typeof dropped !== "object" || Array.isArray(dropped)) return out;
  for (const [dim, raw] of Object.entries(dropped)) {
    const values = Array.isArray(raw) ? raw : [raw];
    for (const item of values) {
      const obj = item && typeof item === "object" ? item : null;
      const value = obj ? obj.value || obj.code || obj.invalid_value : item;
      const reason = obj ? obj.reason || obj.error || "" : "";
      const valueText = String(value ?? "").trim();
      if (/invalid/i.test(String(reason)) || valueText) {
        out.push(`Původně zvolený filtr ${valueText || dim} není v tomto datasetu dostupný.`);
      }
    }
  }
  return out;
}

export { pickPreviewFields } from "@/lib/pickPreviewFields";

function pickCrossSectionField(fields, rows, { timeField, valueField }) {
  const candidates = (fields || []).filter((f) => {
    if (!f) return false;
    if (f === timeField || f === valueField) return false;
    const key = String(f).trim().toLowerCase();
    if (!key || CROSS_SECTION_FIELD_BLACKLIST.has(key)) return false;
    return rows.some((r) => {
      const raw = r?.[f];
      return raw != null && String(raw).trim() !== "";
    });
  });
  if (!candidates.length) return null;

  const scored = candidates
    .map((f) => {
      const unique = new Set();
      for (const r of rows) {
        const raw = r?.[f];
        if (raw == null) continue;
        const v = String(raw).trim();
        if (v) unique.add(v);
      }
      const uniq = unique.size;
      if (uniq < 2) return null;
      const lc = String(f).toLowerCase();
      const uniqueValsLc = Array.from(unique).map((v) => String(v).toLowerCase());
      const boolLike = uniqueValsLc.every((v) => v === "true" || v === "false" || v === "0" || v === "1");
      if (boolLike) return null;
      let score = 0;
      if (/^(geo|ref_area|country|region|kraj|land)$/i.test(f)) score += 100;
      if (/uzemi|územ|zem|state|stat|kraj|region|country|oblast/i.test(lc)) score += 60;
      if (/ukazatel|indicator|typ|category|sex|age/i.test(lc)) score += 45;
      if (uniq <= 25) score += 12;
      if (uniq <= 50) score += 6;
      return { field: f, uniq, score };
    })
    .filter(Boolean)
    .sort((a, b) => (b.score - a.score) || (a.uniq - b.uniq));

  return scored[0]?.field || null;
}

function listCrossSectionCandidates(fields, rows, { timeField, valueField }) {
  const out = [];
  for (const f of fields || []) {
    if (!f || f === timeField || f === valueField) continue;
    const key = String(f).trim().toLowerCase();
    if (!key || CROSS_SECTION_FIELD_BLACKLIST.has(key)) continue;
    const unique = new Set();
    for (const r of rows || []) {
      const raw = r?.[f];
      if (raw == null) continue;
      const v = String(raw).trim();
      if (v) unique.add(v);
    }
    if (unique.size < 2) continue;
    const uniqueValsLc = Array.from(unique).map((v) => String(v).toLowerCase());
    const boolLike = uniqueValsLc.every((v) => v === "true" || v === "false" || v === "0" || v === "1");
    if (boolLike) continue;
    out.push(String(f));
  }
  return out;
}

/**
 * Náhled dat zdroje — zobrazí spojnicový graf + tabulku.
 * Sdílený komponent pro stránky `/sources` (klik na řádek) i `/sources/:id`
 * (formulář pro úpravu zdroje).
 *
 * Pokud zdroj obsahuje více řad (např. ARAD set s desítkami `indicator_id`),
 * v hlavičce se objeví dropdown pro výběr konkrétního ukazatele. Při změně
 * volá `onIndicatorChange(id)`, aby si rodič mohl znovu načíst preview pro
 * vybranou řadu.
 */
export default function SourcePreview({
  preview,
  loading,
  onClose,
  compact = false,
  onIndicatorChange,
  onIndicatorSelectionChange,
  onGeoSelectionChange,
  onDimensionFiltersApply,
  /** Katalog BIS: země je už v názvu řádky — neopakovat „Země: …“ v náhledu. */
  catalogCountryLabel = null,
  /** Katalog BIS: ISO kód země řádky (např. CL) — výchozí výběr v grafu. */
  catalogCountryCode = null,
  /** Katalog BIS: aktivní frekvence náhledu (Čtvrtletní / Roční…) — zobrazit v grafu. */
  catalogFreqLabel = null,
  catalogFreqCode = null,
  /** BIS katalog: lidský popis jednotky / typu hodnoty (miliony USD, %, …). */
  catalogValueDescriptor = null,
  /** Krátká jednotka z katalogového řádku (FRED units, IMF/OECD unit) — fallback pro osu Y. */
  catalogRowUnit = null,
  /** Akce u grafu z globálního katalogu (sync / osobní dashboard). */
  catalogChartActions = null,
  /** Živý náhled z /api/catalog/preview — neukládaný zdroj v DB. */
  liveCatalogPreview = false,
  /** catalog detail panel: detail = kompaktní, detail-expanded = větší graf */
  catalogChartSize = undefined,
  /** Spustí AI/katalogové hledání (např. „Najít v datech“ z follow-up chatu). */
  onFindInCatalogSearch = null,
  /** Volitelný graf (např. AradView z katalogu) — filtry a tabulka zůstanou ze SourcePreview. */
  chartSlot = null,
  /** Výška tabulky dat (Tailwind třída). */
  catalogTableMaxHeightClass = "max-h-[310px]",
  /**
   * Řady se stejnou periodicitou/agregací, jakou má aktuálně vybraný `chartSlot` graf (viz
   * AradView.jsx chartDisplayState -> CatalogChartPreview.jsx) - {period, value}[]. Když je
   * zadané, tabulka (a export) ukazuje TOTO misto syrových `preview.rows`, aby periodicita
   * vybraná v grafu (Denní/Týdenní/Měsíční…) skutečně odpovídala tomu, co vidí uživatel v tabulce.
   */
  periodicityOverrideRows = null,
}) {
  const previewHasError = Boolean(String(preview?.error || "").trim());
  const [conceptExplainOpen, setConceptExplainOpen] = useState(false);
  const [conceptExplainLoading, setConceptExplainLoading] = useState(false);
  const [conceptExplainData, setConceptExplainData] = useState(null);
  const [conceptExplainError, setConceptExplainError] = useState("");
  const [conceptFollowupQuestion, setConceptFollowupQuestion] = useState("");
  const [conceptFollowupLoading, setConceptFollowupLoading] = useState(false);
  const [conceptFollowupError, setConceptFollowupError] = useState("");
  const [conceptFollowupThread, setConceptFollowupThread] = useState([]);
  const [conceptFindInDataQuery, setConceptFindInDataQuery] = useState("");
  const [relatedLoading, setRelatedLoading] = useState(false);
  const [relatedData, setRelatedData] = useState(null);
  const [relatedError, setRelatedError] = useState("");
  const [remoteAradIndicatorLabels, setRemoteAradIndicatorLabels] = useState({});
  const rows = previewHasError ? EMPTY_ARRAY : Array.isArray(preview?.rows) ? preview.rows : EMPTY_ARRAY;
  const previewSourceType = String(
    preview?.source_type || preview?.source?.source_type || preview?.metadata?.source_type || "",
  )
    .trim()
    .toLowerCase();
  const normalizeGeoCodesForPreviewSource = useCallback(
    (geoCodes) => {
      const seen = new Set();
      return normalizeFilterValues(geoCodes, { geo: true })
        .map((code) => {
          if (previewSourceType === "imf") return mapCompareCountryToImfCode(code) || code;
          if (previewSourceType === "world_bank_data360") return mapCompareCountryToWorldBankCode(code) || code;
          return code;
        })
        .map((code) => asGeoCode(code))
        .filter((code) => {
          if (!code || seen.has(code)) return false;
          seen.add(code);
          return true;
        });
    },
    [previewSourceType],
  );
  const previewDatasetId = String(
    preview?.dataset_id || preview?.metadata?.dataset_id || preview?.source?.dataset_id || "",
  ).trim();
  const isFullscreenChartPreview = catalogChartSize === "fullscreen";
  // Detail řady v katalogu už má velký hlavní název — v hlavičce náhledu ho neopakovat.
  const isCatalogDetailPreview =
    catalogChartSize === "detail" || catalogChartSize === "detail-expanded";
  /** Rozbalený náhled v mřížce výsledků hledání — bez druhé „soft-card“ kolem grafu. */
  const isCatalogInlineSearchPreview =
    liveCatalogPreview && compact && !isCatalogDetailPreview && !isFullscreenChartPreview;
  const catalogDetailSplitLayout =
    isCatalogDetailPreview && Boolean(chartSlot) && rows.length > 0 && !previewHasError;
  const catalogMobileChartFirst =
    liveCatalogPreview && (catalogDetailSplitLayout || isFullscreenChartPreview);
  const hideCatalogTableOnMobile = liveCatalogPreview;
  const hideCatalogChartMetaOnMobile = liveCatalogPreview || catalogDetailSplitLayout;
  const catalogSeriesSummaryClass = catalogMobileChartFirst
    ? "px-3 py-1.5 md:px-5 md:py-2 border-b border-border/60 bg-slate-50/70 text-[10px] md:text-[11px] text-slate-700 leading-tight md:leading-snug max-md:max-h-[2.4rem] max-md:overflow-hidden"
    : "px-5 py-2 border-b border-border/60 bg-slate-50/70 text-[11px] text-slate-700 leading-snug";
  const catalogDisplayModeClass = catalogMobileChartFirst
    ? "px-2.5 py-1.5 md:px-5 md:py-2 border-b border-border/60 bg-slate-50/60 flex items-center gap-1.5 md:gap-2 flex-nowrap md:flex-wrap overflow-x-auto"
    : "px-5 py-2 border-b border-border/60 bg-slate-50/60 flex items-center gap-2 flex-wrap";
  const catalogDisplayButtonBase = catalogMobileChartFirst
    ? "h-7 w-7 md:h-6 md:w-auto shrink-0 px-0 md:px-2 text-[10px] rounded border font-mono inline-grid place-items-center md:inline-flex md:items-center md:justify-center"
    : "h-6 px-2 text-[10px] rounded border font-mono";
  const fields = useMemo(() => {
    if (previewHasError) return EMPTY_ARRAY;
    const direct = Array.isArray(preview?.fields) ? preview.fields.filter(Boolean) : [];
    if (direct.length) return direct;
    const cols = Array.isArray(preview?.columns) ? preview.columns : [];
    const fromCols = cols
      .map((col) => (typeof col === "string" ? col : String(col?.key || col?.field || "").trim()))
      .filter(Boolean);
    if (fromCols.length) return fromCols;
    if (rows.length > 0 && rows[0] && typeof rows[0] === "object") {
      return Object.keys(rows[0]);
    }
    return EMPTY_ARRAY;
  }, [previewHasError, preview?.fields, preview?.columns, rows]);
  const indicators = Array.isArray(preview?.indicators) ? preview.indicators : EMPTY_ARRAY;
  const selectedIndicator = preview?.selected_indicator || "";
  const eurostatSummary =
    preview?.source?.source_type === "eurostat" && preview?.eurostat_summary && typeof preview.eurostat_summary === "object"
      ? preview.eurostat_summary
      : null;
  const rawGroupField = preview?.group_field || null;
  const extraDims = Array.isArray(preview?.extra_dimensions) ? preview.extra_dimensions : [];
  const { timeField, valueField, geoField } = useMemo(
    () => resolvePreviewAxisFields(preview, fields, rows),
    [preview, fields, rows],
  );
  const groupField = isTimeOrMeasureDimensionField(rawGroupField, timeField) ? null : rawGroupField;
  const groupLabel =
    String(groupField || "").toLowerCase() === "geo" || String(groupField || "").toLowerCase() === "ref_area"
      ? "Země"
      : "Ukazatel";
  const shownFields = useMemo(() => {
    const prefer = [valueField, timeField, geoField, "OBS_VALUE", "value", "amount"].filter(Boolean);
    const out = [];
    const seen = new Set();
    for (const f of prefer) {
      if (!fields.includes(f) || seen.has(f)) continue;
      seen.add(f);
      out.push(f);
    }
    for (const f of fields) {
      if (seen.has(f) || out.length >= 8) break;
      seen.add(f);
      out.push(f);
    }
    return out.slice(0, 8);
  }, [fields, valueField, timeField, geoField]);
  const catalogDetailTableFields = useMemo(() => {
    if (!catalogDetailSplitLayout) return shownFields;
    return shownFields.slice(0, 5);
  }, [catalogDetailSplitLayout, shownFields]);
  const filteredExtraDims = useMemo(() => {
    const gf = String(groupField || "").trim().toLowerCase();
    const tf = String(timeField || "").trim().toLowerCase();
    return extraDims.filter((dim) => {
      const f = String(dim?.field || "").trim().toLowerCase();
      if (!f) return false;
      const optionCount = Array.isArray(dim?.values) ? dim.values.filter((v) => String(v ?? "").trim()).length : 0;
      if (!isUserSelectableDimensionKey(f, { datasetId: previewDatasetId, optionCount })) return false;
      // Hide label shadow for primary grouping dimension (geo + geo_label, etc.).
      // Otherwise users can create contradictory filters and accidentally hide all rows.
      if (gf && (f === gf || f === `${gf}_label`)) return false;
      // Bez serverového apply: filtr na časové ose jen rozbije graf (1 bod).
      if (!onDimensionFiltersApply && tf && f === tf) return false;
      return true;
    });
  }, [extraDims, groupField, timeField, onDimensionFiltersApply, previewDatasetId]);
  const [crossSectionFieldOverride, setCrossSectionFieldOverride] = useState("");
  const [crossSectionPeriodOverride, setCrossSectionPeriodOverride] = useState("");
  // Plná data pro "Srovnání hodnot" — backend vrátí všechny řádky nejnovějšího období (null = ještě nenačteno)
  const [crossSectionBaseRows, setCrossSectionBaseRows] = useState(null);
  const [crossSectionFetching, setCrossSectionFetching] = useState(false);
  const [dimFilters, setDimFilters] = useState({});
  // „Auto" oficiálně zrušeno — chovalo se identicky jako „time_series" (viz přepínač Zobrazení).
  const [displayMode, setDisplayMode] = useState("time_series");
  const [catalogSettingsOpen, setCatalogSettingsOpen] = useState(false);
  const [showAdvancedFilters, setShowAdvancedFilters] = useState(false);
  const [seriesGroupDim, setSeriesGroupDim] = useState("");
  const [seriesSelection, setSeriesSelection] = useState([]); // explicit series picker
  const [selectedSeriesIds, setSelectedSeriesIds] = useState([]);
  const [selectedGeo, setSelectedGeo] = useState([]);
  const [draftGeo, setDraftGeo] = useState([]);
  const draftGeoRef = useRef([]);
  draftGeoRef.current = draftGeo;
  const [localAppliedGeo, setLocalAppliedGeo] = useState(null);
  const [knownGeoOptions, setKnownGeoOptions] = useState([]);
  const [selectedDimensionFilters, setSelectedDimensionFilters] = useState({});
  const [showGeoPicker, setShowGeoPicker] = useState(false);
  // "region" = pivot chart by region dim; null = default (pivot by group_field / single line)
  const [pivotMode, setPivotMode] = useState(null);

  // Computed from displayMode — must be declared before any useEffect that references it.
  const forceCrossSection = displayMode === "bars_latest";

  // Reset filtrů při změně zdroje (funguje jak pro DB zdroje s id, tak pro katalogové položky se set_id)
  const _resetKey = preview?.source?.id || preview?.source?.set_id || preview?.source?.name || "";
  useEffect(() => {
    setDimFilters({});
    setPivotMode(null);
    setDisplayMode("time_series");
    setShowAdvancedFilters(false);
    setSelectedDimensionFilters({});
    setShowGeoPicker(false);
    setKnownGeoOptions([]);
    setDraftGeo([]);
    setLocalAppliedGeo(null);
    setCatalogSettingsOpen(false);
    setCrossSectionBaseRows(null);
    setCrossSectionFetching(false);
    setSeriesGroupDim("");
    setSeriesSelection([]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [_resetKey]);

  // Při přepnutí do "Srovnání hodnot" donačte všechny hodnoty nejnovějšího období
  useEffect(() => {
    if (!forceCrossSection) return;
    if (crossSectionBaseRows !== null) return; // already fetched or fetching
    const sourceType = String(preview?.source?.source_type || preview?.source_type || preview?.metadata?.source_type || "").trim();
    const setId = String(preview?.source?.set_id || preview?.set_id || preview?.dataset_id || "").trim();
    if (!sourceType || !setId) return;
    let cancelled = false;
    setCrossSectionFetching(true);
    const body = {
      source_type: sourceType,
      set_id: setId,
      name: String(preview?.source?.name || preview?.title || "").trim() || undefined,
      cross_section_mode: true,
      // Přenést query_params z aktuálního preview (filtry zdroje, stát, indikátor apod.)
      ...(preview?.applied_filters && Object.keys(preview.applied_filters).length
        ? { query_params: preview.applied_filters }
        : {}),
    };
    api.post("/catalog/preview", body, { timeout: 30000 })
      .then((res) => {
        if (!cancelled) setCrossSectionBaseRows(Array.isArray(res.data?.rows) ? res.data.rows : []);
      })
      .catch(() => {
        if (!cancelled) setCrossSectionBaseRows([]); // fallback to empty → useMemo falls back to geoFilteredRows
      })
      .finally(() => {
        if (!cancelled) setCrossSectionFetching(false);
      });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [forceCrossSection, crossSectionBaseRows, preview?.source?.set_id, preview?.set_id]);

  useEffect(() => {
    setDimFilters((prev) => (Object.keys(prev).length === 0 ? prev : {}));
  }, [selectedIndicator, groupField]);

  // Při vstupu do krajového módu bez vybraného indikátoru auto-načteme první indikátor
  // (backend pak vrátí 500 řádků jen pro tento 1 indikátor = dostatek dat pro všechny kraje)
  useEffect(() => {
    if (pivotMode === "region" && !selectedIndicator && indicators.length > 0 && onIndicatorChange) {
      onIndicatorChange(String(indicators[0].id));
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pivotMode]);

  // Detekuj dimenzi vhodnou pro pivot po regionech (ÚZEMÍ-KRAJ, kraj, region…)
  const regionExtraDim = useMemo(
    () => filteredExtraDims.find((d) => /kraj|region|uzemi.kraj/i.test(d.field.replace(/[^a-zA-Z]/g, ""))),
    [filteredExtraDims]
  );

  // ČSÚ: více let × více krajů → výchozí pivot po krajích (každý kraj = čára).
  useEffect(() => {
    const st = String(preview?.source?.source_type || "").toLowerCase();
    const csd = String(preview?.chart_series_dim || "").trim();
    if (!regionExtraDim) return;
    if (st !== "csu" && !csd) return;
    if (csd && csd !== regionExtraDim.field && !/kraj|region|uzemi/i.test(csd)) return;
    if (!timeField) return;
    const periods = new Set(
      rows
        .map((r) => String(r?.[timeField] ?? "").trim())
        .filter(Boolean),
    );
    if (periods.size > 1) {
      setPivotMode("region");
    }
  }, [_resetKey, preview?.source?.source_type, preview?.chart_series_dim, regionExtraDim, timeField, rows]);

  const previewSeriesKeys = useMemo(() => normalizePreviewSeriesKeys(preview), [preview?.selected_indicators]);
  useEffect(() => {
    const one = String(selectedIndicator || "").trim();
    const fromPreview = previewSeriesKeys.length ? previewSeriesKeys : (one ? [one] : []);
    const next = fromPreview.slice(0, 8);
    setSelectedSeriesIds((prev) => (sameStringArray(prev, next) ? prev : next));
  }, [previewSeriesKeys, selectedIndicator, _resetKey]);
  const seriesKeys = useMemo(
    () => (selectedSeriesIds.length ? selectedSeriesIds : previewSeriesKeys),
    [selectedSeriesIds, previewSeriesKeys]
  );
  const loadedGroupIds = useMemo(() => {
    const out = new Set();
    if (!groupField) return out;
    rows.forEach((row) => {
      const id = String(row?.[groupField] ?? "").trim();
      if (id) out.add(id.toUpperCase());
    });
    return out;
  }, [rows, groupField]);
  const handleCompareIndicatorSelectionChange = useCallback(
    (indicatorIds) => {
      const next = normalizeSeriesIdList(indicatorIds);
      if (!next.length) return;
      const allAlreadyLoaded =
        Boolean(groupField) &&
        next.every((id) => loadedGroupIds.has(String(id || "").trim().toUpperCase()));

      if (allAlreadyLoaded) {
        setSelectedSeriesIds((prev) => (sameStringArray(prev, next) ? prev : next));
        return;
      }

      onIndicatorSelectionChange?.(next);
    },
    [groupField, loadedGroupIds, onIndicatorSelectionChange],
  );
  const gfLc = String(groupField || "").toLowerCase();
  const isGeoGroup = gfLc === "geo" || gfLc === "ref_area" || gfLc === "country";
  const showMobileMergedDisplayRow =
    catalogMobileChartFirst &&
    indicators.length > 1 &&
    groupField &&
    !isGeoGroup;
  const geoSnapshotDataset = useMemo(
    () =>
      isGeoGroup
      && indicators.length >= 10
      && indicators.every((ind) => Number(ind?.count || 0) <= 1),
    [isGeoGroup, indicators],
  );
  const { geoKey, geoLabelKey } = useMemo(() => detectGeoKeys(fields, rows), [fields, rows]);
  const diagnostic = useMemo(
    () =>
      preview?.metadata?.diagnostic && typeof preview.metadata.diagnostic === "object"
        ? preview.metadata.diagnostic
        : null,
    [preview?.metadata?.diagnostic],
  );
  const availableDimensions = useMemo(
    () => ({
      ...(preview?.metadata?.dimensions && typeof preview.metadata.dimensions === "object" ? preview.metadata.dimensions : {}),
      ...(diagnostic?.available_dimensions && typeof diagnostic.available_dimensions === "object" ? diagnostic.available_dimensions : {}),
      ...(preview?.available_dimensions && typeof preview.available_dimensions === "object" ? preview.available_dimensions : {}),
    }),
    [preview?.metadata?.dimensions, diagnostic, preview?.available_dimensions]
  );
  const requestedFilters = useMemo(
    () => mergeDimensionFilters(
      preview?.metadata?.filters_applied,
      diagnostic?.requested_filters,
      preview?.requested_filters
    ),
    [preview?.metadata?.filters_applied, diagnostic, preview?.requested_filters]
  );
  const suggestedFilters = useMemo(
    () => mergeDimensionFilters(
      preview?.suggested_filters,
      preview?.metadata?.suggested_filters,
      diagnostic?.suggested_filters
    ),
    [preview?.suggested_filters, preview?.metadata?.suggested_filters, diagnostic]
  );
  const aradCatalogSetId = String(
    preview?.source?.set_id || preview?.set_id || preview?.metadata?.set_id || preview?.dataset_id || "",
  ).trim();
  const aradIndicatorFetchKey = previewSourceType === "arad" && aradCatalogSetId ? aradCatalogSetId : "";
  useEffect(() => {
    if (!aradIndicatorFetchKey) {
      setRemoteAradIndicatorLabels((prev) => (Object.keys(prev).length ? {} : prev));
      return undefined;
    }
    let cancelled = false;
    setRemoteAradIndicatorLabels({});
    api
      .get("/arad/catalog/set-indicators", { params: { set_id: aradIndicatorFetchKey }, timeout: 20000 })
      .then(({ data }) => {
        if (cancelled) return;
        const labels = {};
        (Array.isArray(data?.indicators) ? data.indicators : []).forEach((item) => {
          const id = item?.indicator_id || item?.id || item?.code || item?.value;
          const label = item?.name || item?.label || item?.indicator_name || item?.full_name;
          addCleanIndicatorLabel(labels, id, label);
        });
        setRemoteAradIndicatorLabels(labels);
      })
      .catch(() => {
        if (!cancelled) setRemoteAradIndicatorLabels({});
      });
    return () => {
      cancelled = true;
    };
  }, [aradIndicatorFetchKey]);
  const indicatorLabelLookup = useMemo(
    () => buildIndicatorLabelLookup(availableDimensions, rows, remoteAradIndicatorLabels, groupField),
    [availableDimensions, rows, remoteAradIndicatorLabels, groupField]
  );
  const displayIndicators = useMemo(
    () =>
      dedupeIndicatorsForSelect(
        indicators.map((ind) => {
          const name = resolveIndicatorDisplayName(ind, indicatorLabelLookup);
          return name === ind?.name ? ind : { ...ind, name };
        }),
        indicatorLabelLookup,
      ),
    [indicators, indicatorLabelLookup],
  );
  useEffect(() => {
    if (previewHasError || !onDimensionFiltersApply) return;
    const applied = requestedFilters || {};
    const next = {};
    for (const dim of filteredExtraDims) {
      const field = dim.field;
      const lk = String(field || "").trim().toLowerCase();
      let raw = applied[field];
      if (!raw && (lk === "time_period" || lk === "time" || lk === "period")) {
        raw = applied.startPeriod || applied.endPeriod;
      }
      if (!raw) continue;
      next[field] = Array.isArray(raw) ? String(raw[0] ?? "").trim() : String(raw).trim();
    }
    setDimFilters((prev) => (dimFiltersEqual(prev, next) ? prev : next));
    setSeriesGroupDim("");
  }, [_resetKey, requestedFilters, filteredExtraDims, onDimensionFiltersApply, previewHasError]);

  const missingFilters = useMemo(() => {
    const raw = [
      ...(Array.isArray(preview?.missing_filters) ? preview.missing_filters : []),
      ...(Array.isArray(preview?.metadata?.missing_filters) ? preview.metadata.missing_filters : []),
      ...(Array.isArray(diagnostic?.missing_filters) ? diagnostic.missing_filters : []),
    ];
    return [...new Set(raw.map((x) => String(x || "").trim()).filter(Boolean))];
  }, [preview?.missing_filters, preview?.metadata?.missing_filters, diagnostic]);
  const droppedFilters = preview?.dropped_filters || diagnostic?.dropped_filters || {};
  const filterWarnings = useMemo(() => droppedFilterWarnings(droppedFilters), [droppedFilters]);
  const geoLabelLookup = useMemo(
    () => ({
      ...buildGeoLabelLookup(availableDimensions, geoKey),
      ...buildCountryLabelLookup(availableDimensions, geoKey),
    }),
    [availableDimensions, geoKey]
  );
  const labelForGeo = (code, hint = "") => geoDisplayLabel(code, hint, geoLabelLookup);
  const metadataGeoAvailable = useMemo(() => {
    const selectableDimensions = Array.isArray(preview?.selectable_dimensions)
      ? preview.selectable_dimensions
      : Array.isArray(preview?.metadata?.selectable_dimensions)
        ? preview.metadata.selectable_dimensions
        : [];
    const fromDims = readGeoDimensionOptions(
      availableDimensions,
      geoKey,
      "geo",
      "REF_AREA",
      "ref_area",
    );
    const fromSelectable = readGeoOptionsFromSelectableDimensions(selectableDimensions, geoKey, "geo", "REF_AREA", "ref_area");
    const fromAllGeoDims = readGeoOptionsFromGeoDimensions(availableDimensions, geoKey, "geo", "REF_AREA", "ref_area");
    const directGeoOptions = mergeGeoOptionLists(fromDims, fromSelectable, fromAllGeoDims);
    if (directGeoOptions.length) return directGeoOptions;
    const dimKeys = [
      geoKey,
      "c_orig",
      "c_dest",
      "c_imp",
      "c_exp",
      "geo",
      "REF_AREA",
      "ref_area",
      "country",
    ].filter(Boolean);
    const seenKey = new Set();
    for (const key of dimKeys) {
      const norm = dimKey(key);
      if (seenKey.has(norm)) continue;
      seenKey.add(norm);
      const fromMeta =
        toNormalizedToken(key) === "c_orig"
          ? readCountryOptionsFromDimensions(availableDimensions, key)
          : readGeoOptionsFromDimensionMeta(availableDimensions?.[key]);
      if (fromMeta.length) return fromMeta;
    }
    const codes = [
      ...readGeoValuesFromAny(availableDimensions.geo),
      ...(geoKey ? readGeoValuesFromAny(availableDimensions[geoKey]) : []),
    ];
    const seen = new Set();
    return codes
      .filter((c) => {
        if (!c || seen.has(c)) return false;
        seen.add(c);
        return true;
      })
      .map((c) => ({ value: c, label: labelForGeo(c), rowCount: 0, fromMetadata: true, availabilityKnown: false }));
  }, [availableDimensions, geoKey, geoLabelLookup, preview?.selectable_dimensions, preview?.metadata?.selectable_dimensions]);
  const rowsGeoOptions = useMemo(() => buildGeoOptionsFromRows(rows, geoKey, geoLabelKey), [rows, geoKey, geoLabelKey]);
  const indicatorGeoOptions = useMemo(
    () => isGeoGroup
      ? displayIndicators.map((ind) => ({
          value: asGeoCode(ind?.id),
          label: labelForGeo(ind?.id, ind?.name),
          rowCount: Number(ind?.count || 0),
          fromMetadata: false,
          availabilityKnown: true,
        })).filter((x) => x.value)
      : [],
    [isGeoGroup, displayIndicators]
  );
  const rowsGeoDistinct = useMemo(() => rowsGeoOptions.map((x) => x.value), [rowsGeoOptions]);
  const incomingGeoOptions = useMemo(
    () => collectGeoOptions([...rowsGeoOptions, ...indicatorGeoOptions], metadataGeoAvailable),
    [rowsGeoOptions, indicatorGeoOptions, metadataGeoAvailable]
  );
  useEffect(() => {
    if (!incomingGeoOptions.length) return;
    setKnownGeoOptions((prev) => {
      const merged = mergeGeoOptionLists(prev, incomingGeoOptions);
      return geoOptionsEqual(prev, merged) ? prev : merged;
    });
  }, [incomingGeoOptions]);
  useEffect(() => {
    if (!Object.keys(geoLabelLookup).length) return;
    setKnownGeoOptions((prev) => {
      if (!prev.length) return prev;
      let changed = false;
      const next = prev.map((opt) => {
        const label = geoDisplayLabel(opt.value, opt.label, geoLabelLookup);
        if (label === opt.label) return opt;
        changed = true;
        return { ...opt, label };
      });
      return changed ? next : prev;
    });
  }, [geoLabelLookup]);
  const availableGeoOptions = knownGeoOptions.length ? knownGeoOptions : incomingGeoOptions;
  const geoDimensionKey = useMemo(() => {
    const dimKeys = Object.keys(availableDimensions || {});
    const resolved = resolvePrimaryCountryDimensionKey(availableDimensions, requestedFilters);
    if (resolved && Object.prototype.hasOwnProperty.call(requestedFilters || {}, resolved)) {
      return resolved;
    }
    if (resolved && dimKeys.some((k) => dimKey(k) === dimKey(resolved))) {
      const exact = dimKeys.find((k) => dimKey(k) === dimKey(resolved));
      return exact || resolved;
    }
    // Pokud máme geo sloupec v řádcích, preferujeme stejnou geo dimenzi z metadata
    if (geoKey) {
      const geoNorm = dimKey(geoKey);
      const matchingDimKey = dimKeys.find((k) => dimKey(k) === geoNorm);
      if (matchingDimKey) return matchingDimKey;
      return geoKey;
    }
    const firstGeoLikeDim = dimKeys.find((k) => isGeoDimensionField(k));
    if (firstGeoLikeDim) return firstGeoLikeDim;
    if (isGeoGroup && gfLc) return gfLc;
    return resolved || "geo";
  }, [availableDimensions, geoKey, isGeoGroup, gfLc, requestedFilters]);
  const countryPickerLabel = useMemo(
    () => countryDimensionLabel(geoDimensionKey),
    [geoDimensionKey]
  );
  const withEurostatDimensionFilters = useCallback((merged) =>
    applyIndustryLinkedFilters(
      applyEurostatFigaroFixedFilters(merged, availableDimensions),
      availableDimensions,
      previewDatasetId,
    ), [availableDimensions, previewDatasetId]);
  const buildDimensionFiltersWithCurrentGeo = useCallback((...extraFilters) => {
    let merged = mergeDimensionFilters(suggestedFilters, requestedFilters, ...extraFilters);
    const extraHasGeo = extraFilters.some(hasGeoFilterSelection);
    const currentGeo = normalizeFilterValues(selectedGeo, { geo: true });
    if (!extraHasGeo && currentGeo.length > 0 && geoDimensionKey) {
      merged = replaceDimensionFilter(merged, geoDimensionKey, currentGeo);
    }
    return merged;
  }, [suggestedFilters, requestedFilters, selectedGeo, geoDimensionKey]);
  const finalizeDimensionFilters = useCallback(
    (merged) => withEurostatDimensionFilters(merged),
    [withEurostatDimensionFilters],
  );
  const figaroCountryContext = useMemo(
    () =>
      formatFigaroCountryContext(
        preview?.metadata?.filters_applied || requestedFilters,
        preview?.filter_display_labels || preview?.metadata?.filter_display_labels,
        availableDimensions,
        geoDimensionKey
      ),
    [
      preview?.metadata?.filters_applied,
      preview?.filter_display_labels,
      preview?.metadata?.filter_display_labels,
      requestedFilters,
      availableDimensions,
      geoDimensionKey,
    ]
  );
  const countryPickerFallbackOptions = useMemo(() => {
    const hasOnlyCurrentCountry =
      availableGeoOptions.length > 0 &&
      availableGeoOptions.length <= 1 &&
      availableGeoOptions.some((opt) => isCountryLevelGeoCode(opt?.value));
    const dimensionLooksCountry = isCountryDimensionKey(geoDimensionKey) || isGeoDimensionField(geoDimensionKey);
    if (!hasOnlyCurrentCountry || !dimensionLooksCountry) return [];
    return COUNTRY_PICKER_FALLBACK_GEOS.map((code) => ({
      value: normalizeGeoCodesForPreviewSource([code])[0] || asGeoCode(code),
      label: geoDisplayLabel(code, "", geoLabelLookup),
      rowCount: 0,
      fromMetadata: true,
      availabilityKnown: false,
    }));
  }, [availableGeoOptions, geoDimensionKey, geoLabelLookup, normalizeGeoCodesForPreviewSource]);
  const countryPickerOptions = useMemo(() => {
    const base = countryPickerFallbackOptions.length
      ? mergeGeoOptionLists(availableGeoOptions, countryPickerFallbackOptions)
      : availableGeoOptions;
    if (!figaroCountryContext.isFigaro) return base;
    return filterFigaroOriginCountryOptions(base);
  }, [availableGeoOptions, countryPickerFallbackOptions, figaroCountryContext.isFigaro]);
  const catalogCountryCtx = String(catalogCountryLabel || "").trim();
  const catalogCountryCodeNorm = normalizeGeoCodesForPreviewSource([catalogCountryCode])[0] || "";
  const catalogValueDescriptorText = useMemo(() => {
    // Eurostat: kód dimenze „unit" → čitelný popisek (fallback pro zdroje bez unit_label_cs)
    const eurostatUnitCode = String(requestedFilters?.unit || requestedFilters?.UNIT || "").trim().toUpperCase();
    const eurostatUnitFallback = eurostatUnitCode
      ? (EUROSTAT_UNIT_LABELS[eurostatUnitCode]
          ? `Jednotka: ${EUROSTAT_UNIT_LABELS[eurostatUnitCode]}`
          : eurostatUnitCode.length <= 20 ? `Jednotka: ${eurostatUnitCode}` : "")
      : "";
    // FRED/IMF/OECD: jednotka z katalogového řádku (konektor ji nepřenese do preview)
    const rowUnit = String(catalogRowUnit || "").trim();
    const rowUnitFallback = rowUnit
      ? `Jednotka: ${EUROSTAT_UNIT_LABELS[rowUnit.toUpperCase()] || rowUnit}`
      : "";
    return resolveCatalogValueDescriptor({
      fromRow: catalogValueDescriptor,
      fromPreview:
        (preview?.metadata?.ecb_value_descriptor
        ?? preview?.ecb_value_descriptor
        ?? preview?.metadata?.bis_value_descriptor
        ?? preview?.bis_value_descriptor
        ?? preview?.data360_indicator_info?.measurement_unit
        ?? preview?.metadata?.data360_indicator_info?.measurement_unit
        ?? preview?.unit_label_cs
        ?? preview?.metadata?.unit_label_cs)
        || (preview?.unit ? `Jednotka: ${preview.unit}` : "")
        || eurostatUnitFallback
        || rowUnitFallback
        || "",
      rows,
      fields,
    });
  }, [
    catalogValueDescriptor,
    catalogRowUnit,
    preview?.metadata?.ecb_value_descriptor,
    preview?.ecb_value_descriptor,
    preview?.metadata?.bis_value_descriptor,
    preview?.bis_value_descriptor,
    preview?.data360_indicator_info?.measurement_unit,
    preview?.metadata?.data360_indicator_info?.measurement_unit,
    preview?.unit_label_cs,
    preview?.metadata?.unit_label_cs,
    preview?.unit,
    requestedFilters,
    rows,
    fields,
  ]);

  const chartYAxisUnit = useMemo(() => {
    // 1. data360 measurement_unit (nejvyšší priorita)
    const fromMeta = String(
      preview?.data360_indicator_info?.measurement_unit
      || preview?.metadata?.data360_indicator_info?.measurement_unit
      || ""
    ).trim();
    if (fromMeta) return fromMeta;

    // 2. Explicitní unit_label_cs / unit z metadat (World Bank, Yahoo, BIS, ECB, Data360…)
    const fromPreviewRaw = String(
      preview?.unit_label_cs
      || preview?.metadata?.unit_label_cs
      || preview?.unit
      || preview?.metadata?.unit
      || ""
    ).trim();
    if (fromPreviewRaw) {
      // Surový kód (velká písmena, krátký, žádné mezery) → pokus o překlad
      const upper = fromPreviewRaw.toUpperCase();
      if (EUROSTAT_UNIT_LABELS[upper]) return EUROSTAT_UNIT_LABELS[upper];
      return fromPreviewRaw.replace(/^\((.+)\)$/, "$1").trim() || fromPreviewRaw;
    }

    // 3. Eurostat: kód jednotky z aplikovaných filtrů dimenze „unit"
    const unitDimCode = String(
      requestedFilters?.unit
      || requestedFilters?.UNIT
      || ""
    ).trim().toUpperCase();
    if (unitDimCode) {
      const mapped = EUROSTAT_UNIT_LABELS[unitDimCode];
      if (mapped) return mapped;
      // neznámý kód — vrátíme surový kód alespoň jako fallback
      if (unitDimCode.length <= 20) return unitDimCode;
    }

    // 4. Skenujeme řádky dat — různé názvy sloupce napříč zdroji
    for (const r of rows.slice(0, 20)) {
      const u = String(
        r?.unit ?? r?.UNIT ?? r?.unit_of_measure ?? r?.unit_label ?? r?.UNIT_LABEL ?? ""
      ).trim();
      if (u && u.length <= 60) return u.replace(/^\((.+)\)$/, "$1").trim() || u;
    }

    // 5. Jednotka z katalogového řádku (FRED/IMF/OECD ji mají v metadatech katalogu,
    //    ale konektor ji do preview řádků nepřenese — proto fallback z původní řady).
    const fromRow = String(catalogRowUnit || "").trim();
    if (fromRow && fromRow.length <= 60) {
      const upper = fromRow.toUpperCase();
      if (EUROSTAT_UNIT_LABELS[upper]) return EUROSTAT_UNIT_LABELS[upper];
      return fromRow.replace(/^\((.+)\)$/, "$1").trim() || fromRow;
    }
    return "";
  }, [
    preview?.data360_indicator_info?.measurement_unit,
    preview?.metadata?.data360_indicator_info?.measurement_unit,
    preview?.unit_label_cs,
    preview?.metadata?.unit_label_cs,
    preview?.unit,
    preview?.metadata?.unit,
    requestedFilters,
    rows,
    catalogRowUnit,
  ]);

  const effectiveCatalogFreqLabel = String(
    catalogFreqLabel || preview?.chart_frequency_label || preview?.metadata?.chart_frequency_label || ""
  ).trim();
  const effectiveCatalogFreqCode = String(
    catalogFreqCode || preview?.chart_frequency || preview?.metadata?.chart_frequency || ""
  ).trim();

  useEffect(() => {
    if (previewHasError || loading) return;
    if (geoSnapshotDataset) {
      setSelectedGeo((prev) => (prev.length ? [] : prev));
      if (!showGeoPicker) {
        setDraftGeo((prev) => (prev.length ? [] : prev));
      }
      return;
    }
    if (Array.isArray(localAppliedGeo)) {
      const next = normalizeGeoCodesForPreviewSource(localAppliedGeo);
      setSelectedGeo((prev) => sameStringArray(prev, next) ? prev : next);
      if (!showGeoPicker) {
        setDraftGeo((prev) => sameStringArray(prev, next) ? prev : next);
      }
      return;
    }
    const fromCountryFilters = extractCountryCodesFromFilters(requestedFilters);
    const requestedGeo =
      fromCountryFilters.length > 0
        ? normalizeGeoCodesForPreviewSource(fromCountryFilters)
        : normalizeGeoCodesForPreviewSource(
            readGeoValuesFromAny(requestedFilters.geo ?? requestedFilters[geoDimensionKey])
          );
    const allowed = new Set(countryPickerOptions.map((o) => o.value));
    const fromRequested = capGeoList(
      requestedGeo.filter((v) => allowed.has(v)),
      availableGeoOptions,
      MAX_GEO_SELECTION,
    );
    if (fromRequested.length > 0) {
      setSelectedGeo((prev) => sameStringArray(prev, fromRequested) ? prev : fromRequested);
      if (!showGeoPicker) {
        setDraftGeo((prev) => sameStringArray(prev, fromRequested) ? prev : fromRequested);
      }
      return;
    }
    const fromRowsRaw = rowsGeoDistinct.filter((v) => allowed.has(v));
    if (fromRowsRaw.length > 0) {
      let next = catalogCountryCtx && fromRowsRaw.length > 1
        ? fromRowsRaw.filter((v) => v === catalogCountryCodeNorm).length
          ? fromRowsRaw.filter((v) => v === catalogCountryCodeNorm)
          : [fromRowsRaw[0]]
        : fromRowsRaw;
      if (!catalogCountryCtx && next.length > MAX_GEO_SELECTION) {
        const prefer = catalogCountryCodeNorm
          ? [catalogCountryCodeNorm, ...QUICK_COMPARE_GEOS]
          : QUICK_COMPARE_GEOS;
        const preferred = prefer.filter((c) => next.includes(c));
        next = capGeoList(
          preferred.length ? preferred : next,
          availableGeoOptions.length ? availableGeoOptions : rowsGeoOptions,
          MAX_GEO_SELECTION,
        );
      } else {
        next = capGeoList(next, availableGeoOptions, MAX_GEO_SELECTION);
      }
      setSelectedGeo((prev) => sameStringArray(prev, next) ? prev : next);
      if (!showGeoPicker) {
        setDraftGeo((prev) => sameStringArray(prev, next) ? prev : next);
      }
      return;
    }
    if (catalogCountryCtx && catalogCountryCodeNorm && allowed.has(catalogCountryCodeNorm)) {
      const next = [catalogCountryCodeNorm];
      setSelectedGeo((prev) => sameStringArray(prev, next) ? prev : next);
      if (!showGeoPicker) {
        setDraftGeo((prev) => sameStringArray(prev, next) ? prev : next);
      }
      return;
    }
    if (catalogCountryCtx) {
      return;
    }
    if (countryPickerOptions.length > 0) {
      const next = figaroCountryContext.isFigaro
        ? pickDefaultFigaroOriginCountries(countryPickerOptions, 1)
        : countryPickerOptions.slice(0, 3).map((o) => o.value);
      setSelectedGeo((prev) => sameStringArray(prev, next) ? prev : next);
      if (!showGeoPicker) {
        setDraftGeo((prev) => sameStringArray(prev, next) ? prev : next);
      }
      return;
    }
    setSelectedGeo((prev) => prev.length ? [] : prev);
    if (!showGeoPicker) {
      setDraftGeo((prev) => prev.length ? [] : prev);
    }
  }, [
    _resetKey,
    preview?.request_id,
    requestedFilters,
    geoDimensionKey,
    rowsGeoDistinct,
    availableGeoOptions,
    countryPickerOptions,
    figaroCountryContext.isFigaro,
    showGeoPicker,
    localAppliedGeo,
    catalogCountryCtx,
    catalogCountryCodeNorm,
    geoSnapshotDataset,
    previewHasError,
    loading,
    normalizeGeoCodesForPreviewSource,
  ]);
  // Pivot po regionech: dostupný kdykoli je k dispozici dim krajů (ne jen při výběru 1 indikátoru)
  const canPivotByRegion = Boolean(regionExtraDim);
  const activePivotByRegion = canPivotByRegion && pivotMode === "region";

  // Aplikuj filtry dimenzí na řádky — v region-pivot módu vynecháme filtr pro kraj,
  // protože chceme všechny kraje jako série, ne jen vybraný
  const filteredRows = useMemo(() => {
    if (onDimensionFiltersApply) return rows;
    const skipField = activePivotByRegion && regionExtraDim ? regionExtraDim.field : null;
    const activeFilters = Object.entries(dimFilters).filter(
      ([field, v]) => v && String(v).trim() && field !== skipField
    );
    if (!activeFilters.length) return rows;
    return rows.filter((r) =>
      activeFilters.every(([field, val]) => String(r?.[field] ?? "").trim() === val)
    );
  }, [rows, dimFilters, activePivotByRegion, regionExtraDim, onDimensionFiltersApply]);
  const hasActiveDimFilters = useMemo(
    () => Object.values(dimFilters).some((v) => String(v || "").trim()),
    [dimFilters]
  );
  const selectedGeoSet = useMemo(
    () => new Set((selectedGeo || []).map((x) => asGeoCode(x)).filter(Boolean)),
    [selectedGeo]
  );
  const geoFilteredRows = useMemo(() => {
    if (!geoKey || selectedGeoSet.size === 0) return filteredRows;
    const withGeo = filteredRows.filter((r) => {
      const code = asGeoCode(r?.[geoKey]);
      return code && selectedGeoSet.has(code);
    });
    if (withGeo.length > 0) return withGeo;
    const hasGeoColumn = filteredRows.some((r) => asGeoCode(r?.[geoKey]));
    if (!hasGeoColumn && filteredRows.length > 0) {
      return filteredRows;
    }
    return withGeo;
  }, [filteredRows, geoKey, selectedGeoSet]);
  // Pro cross-section: plná data nejnovějšího období (bez geo filtru, s dimFilter)
  const crossSectionFilteredRows = useMemo(() => {
    if (!crossSectionBaseRows || crossSectionBaseRows.length === 0) return null;
    const skipField = activePivotByRegion && regionExtraDim ? regionExtraDim.field : null;
    const activeFilters = Object.entries(dimFilters).filter(
      ([field, v]) => v && String(v).trim() && field !== skipField
    );
    if (!activeFilters.length) return crossSectionBaseRows;
    const filtered = crossSectionBaseRows.filter((r) =>
      activeFilters.every(([field, val]) => String(r?.[field] ?? "").trim() === val)
    );
    return filtered.length > 0 ? filtered : crossSectionBaseRows;
  }, [crossSectionBaseRows, dimFilters, activePivotByRegion, regionExtraDim]);

  // User-chosen dimension for multi-series time series (overrides backend group_field)
  const effectivePivotField = seriesGroupDim || groupField;
  const allSeriesForDim = useMemo(() => {
    if (!seriesGroupDim || !effectivePivotField) return [];
    return distinctSeriesIdsInRows(geoFilteredRows, effectivePivotField);
  }, [seriesGroupDim, effectivePivotField, geoFilteredRows]);
  const pivotSeriesKeys = useMemo(() => {
    // Explicit user choice always wins — skip indicator-count early return
    if (seriesGroupDim && effectivePivotField) {
      const fromRows = allSeriesForDim;
      if (seriesSelection.length > 0) {
        const selSet = new Set(seriesSelection);
        const valid = fromRows.filter((id) => selSet.has(id));
        return valid.length > 0 ? valid : fromRows.slice(0, 12);
      }
      return fromRows.length > 1 ? fromRows.slice(0, 12) : seriesKeys;
    }
    if (seriesKeys.length > 1) return seriesKeys;
    if (!effectivePivotField) return seriesKeys;
    if (!isCategoricalGroupField(effectivePivotField)) return seriesKeys;
    const fromRows = distinctSeriesIdsInRows(geoFilteredRows, effectivePivotField);
    return fromRows.length > 1 ? fromRows.slice(0, 12) : seriesKeys;
  }, [seriesKeys, effectivePivotField, seriesGroupDim, seriesSelection, allSeriesForDim, geoFilteredRows]);
  const multiLinePivot = pivotSeriesKeys.length > 1 && Boolean(effectivePivotField);
  const tableRows = useMemo(
    () => {
      if (Array.isArray(periodicityOverrideRows) && periodicityOverrideRows.length > 0) {
        // Graf (chartSlot) je zdroj pravdy pro vybranou periodicitu - viz AradView.jsx
        // chartDisplayState. Namapujeme na skutečné klíče sloupců (timeField/valueField), aby
        // tabulka pořád vykreslila přes stejné `shownFields`/`formatSourceTableCell` jako dřív.
        const mapped = periodicityOverrideRows.map((r) => ({
          [timeField || "date"]: r.period,
          [valueField || "value"]: r.value,
        }));
        return mapped.length > 1 ? [...mapped].reverse() : mapped;
      }
      const base = geoFilteredRows.length > 0 ? geoFilteredRows : filteredRows.length > 0 ? filteredRows : rows;
      // Tabulka ukazuje jen prvních ~30 řádků (slice níže). Data ze zdroje jdou
      // chronologicky vzestupně (nejstarší první) → bez otočení by tabulka u dlouhých
      // řad ukazovala JEN staré období (např. 2022) a nikdy aktuální data. Otočíme na
      // NEJNOVĚJŠÍ NAHORU. Reverse (ne string-sort) je robustní pro VŠECHNY formáty data
      // — ISO i české měsíce ("říjen 2025") u ČSÚ. Graf nedotčen (má vlastní řazené
      // `chartRows`); `allValuesZero` je na pořadí nezávislé. Worst-case (zdroj už
      // sestupně) = tabulka jako dřív, nikdy ne rozbitá data.
      return base.length > 1 ? [...base].reverse() : base;
    },
    [periodicityOverrideRows, timeField, valueField, geoFilteredRows, filteredRows, rows]
  );
  const allValuesZero = useMemo(() => {
    if (!valueField || !tableRows.length) return false;
    let seen = 0;
    for (const r of tableRows) {
      const n = parseNumber(r?.[valueField]);
      if (n === null) continue;
      seen += 1;
      if (n !== 0) return false;
    }
    return seen > 0;
  }, [tableRows, valueField]);
  const previewAllValuesZero = Boolean(
    preview?.all_values_zero
    || preview?.metadata?.all_values_zero
    || preview?.preview_state === "all_zero"
    || allValuesZero
  );
  const chartBlocked = previewAllValuesZero;
  const noRowsAfterDimFilters = hasActiveDimFilters && rows.length > 0 && geoFilteredRows.length === 0;

  // Když je aktivní region pivot a ještě není vybraný konkrétní indikátor, vybereme první automaticky
  const regionPivotIndicator = activePivotByRegion
    ? (selectedIndicator || (displayIndicators[0]?.id ? String(displayIndicators[0].id) : null))
    : null;
  const geoSeriesActive = Boolean(geoKey) && selectedGeo.length > 1;
  const hasCountryPicker =
    geoKey ||
    isGeoGroup ||
    availableGeoOptions.length > 0 ||
    figaroCountryContext.isFigaro;
  const canShowCountrySelector = hasCountryPicker && !figaroCountryContext.isFigaro;
  const catalogSingleGeoMode =
    Boolean(catalogCountryCtx) &&
    Boolean(geoKey) &&
    selectedGeo.length <= 1 &&
    !showGeoPicker &&
    !geoSeriesActive &&
    !figaroCountryContext.isFigaro;
  const showMobileGeoAndDisplayRow =
    catalogMobileChartFirst &&
    catalogSingleGeoMode &&
    Boolean(onGeoSelectionChange) &&
    !showMobileMergedDisplayRow;
  const geoLabelByCode = useMemo(() => {
    const out = { ...geoLabelLookup };
    for (const opt of availableGeoOptions) {
      out[opt.value] = labelForGeo(opt.value, opt.label);
    }
    for (const ind of displayIndicators) {
      const code = asGeoCode(ind?.id);
      if (!code) continue;
      out[code] = labelForGeo(code, ind?.name);
    }
    return out;
  }, [availableGeoOptions, displayIndicators, geoLabelLookup]);
  const openGeoPicker = () => {
    const next = [...selectedGeo];
    draftGeoRef.current = next;
    setDraftGeo(next);
    setShowGeoPicker(true);
  };
  const applyGeoSelection = () => {
    const next = normalizeFilterValues(draftGeoRef.current, { geo: true }).slice(0, MAX_GEO_SELECTION);
    applyGeoCodes(next);
  };
  const applyGeoCodes = (geoCodes) => {
    const next = normalizeGeoCodesForPreviewSource(geoCodes).slice(0, MAX_GEO_SELECTION);
    setSelectedGeo(next);
    setLocalAppliedGeo(next);
    setShowGeoPicker(false);
    if (onDimensionFiltersApply) {
      let merged = replaceDimensionFilter(
        mergeDimensionFilters(suggestedFilters, requestedFilters),
        geoDimensionKey,
        next,
      );
      merged = finalizeDimensionFilters(merged);
      onDimensionFiltersApply?.(merged);
    } else {
      onGeoSelectionChange?.(next);
    }
  };
  const cancelGeoSelection = () => {
    const next = [...selectedGeo];
    draftGeoRef.current = next;
    setDraftGeo(next);
    setShowGeoPicker(false);
  };
  const needsFilterPanel = preview?.status === "needs_filters" || missingFilters.length > 0;
  const catalogDetailSettingsShell = isCatalogDetailPreview && !needsFilterPanel;
  /** Kompaktní jedna lišta — i když API vrátí missing_filters, ale data už jsou načtená. */
  const catalogCompactPreviewChrome =
    Boolean(liveCatalogPreview) &&
    rows.length > 0 &&
    String(preview?.status || "") !== "needs_filters";
  const catalogUnifiedToolbar = catalogCompactPreviewChrome;
  const catalogToolbarClass =
    "px-3 py-1.5 border-b border-border/60 bg-slate-50/70 flex flex-wrap items-center gap-x-2 gap-y-1 min-h-0";
  const catalogSettingsSummary = useMemo(() => {
    if (!catalogDetailSettingsShell) return "";
    const parts = [];
    if (selectedGeo.length > 0) {
      parts.push(
        selectedGeo.length > 1
          ? `${selectedGeo.length} oblasti`
          : geoLabelByCode[selectedGeo[0]] || selectedGeo[0],
      );
    } else if (hasCountryPicker) {
      parts.push(countryPickerLabel || "Země");
    }
    parts.push(displayMode === "bars_latest" ? "Srovnání hodnot" : "Časová řada");
    if (filteredExtraDims.length > 0) {
      parts.push(`${filteredExtraDims.length} filtrů`);
    }
    return parts.join(" · ");
  }, [
    catalogDetailSettingsShell,
    selectedGeo,
    geoLabelByCode,
    hasCountryPicker,
    countryPickerLabel,
    displayMode,
    filteredExtraDims.length,
  ]);
  const isEurostatPreview = previewSourceType === "eurostat";
  const useEurostatCascadePicker =
    isEurostatPreview &&
    liveCatalogPreview &&
    Boolean(onDimensionFiltersApply) &&
    Boolean(previewDatasetId) &&
    (needsFilterPanel || Object.keys(availableDimensions || {}).length > 0);
  const quickCompareGeoOptions = useMemo(() => {
    if (selectedGeo.length !== 1 || availableGeoOptions.length <= 1) return [];
    const selected = new Set(selectedGeo);
    const byCode = new Map(availableGeoOptions.map((opt) => [opt.value, opt]));
    const out = [];
    for (const code of QUICK_COMPARE_GEOS) {
      const opt = byCode.get(code);
      if (!opt || selected.has(code)) continue;
      out.push(opt);
      if (out.length >= 4) break;
    }
    return out;
  }, [availableGeoOptions, selectedGeo]);
  const addDraftGeoCode = useCallback((rawCode) => {
    const nextCode = asGeoCode(rawCode);
    if (!nextCode) return;
    setDraftGeo((prev) => {
      const current = Array.isArray(prev) ? prev : [];
      if (current.includes(nextCode) || current.length >= MAX_GEO_SELECTION) return current;
      const next = [...current, nextCode].slice(0, MAX_GEO_SELECTION);
      draftGeoRef.current = next;
      return next;
    });
  }, []);
  const removeDraftGeoCode = useCallback((rawCode) => {
    const code = asGeoCode(rawCode);
    if (!code) return;
    setDraftGeo((prev) => {
      const next = Array.isArray(prev) ? prev.filter((x) => asGeoCode(x) !== code) : [];
      draftGeoRef.current = next;
      return next;
    });
  }, []);
  const clearDraftGeo = useCallback(() => {
    draftGeoRef.current = [];
    setDraftGeo([]);
  }, []);
  const compactGeoButtonLabel = useMemo(() => {
    if (selectedGeo.length > 1) return `${countryPickerLabel}: ${selectedGeo.length}`;
    const only = selectedGeo[0];
    if (only) return `${countryPickerLabel}: ${geoLabelByCode[only] || only}`;
    return countryPickerLabel || "Země";
  }, [countryPickerLabel, geoLabelByCode, selectedGeo]);
  const dimensionPanelItems = useMemo(() => {
    if (!needsFilterPanel) return [];
    const keys = new Set([...missingFilters, ...Object.keys(availableDimensions || {})]);
    keys.delete("time");
    const items = [];
    for (const dim of keys) {
      const key = String(dim || "").trim();
      if (!key) continue;
      if (isTimeOrMeasureDimensionField(key, timeField)) continue;
      if (dimKey(key) === "c_dest") continue;
      const isGeoDim =
        isGeoDimensionField(key) ||
        dimKey(key) === dimKey(geoDimensionKey);
      const options = isGeoDim
        ? countryPickerOptions
        : readDimensionOptions(availableDimensions[key], { geo: false });
      const initial = buildDimensionFiltersWithCurrentGeo()[key] ?? (isGeoDim ? selectedGeo : "");
      items.push({
        key,
        label: getDimensionLabel(key, availableDimensions),
        isGeo: isGeoDim,
        options,
        selected: normalizeFilterValues(selectedDimensionFilters[key] ?? initial, { geo: isGeoDim }),
        requiredWithoutValues: missingFilters.includes(key) && options.length === 0,
      });
    }
    return items.sort((a, b) => {
      const aMissing = missingFilters.includes(a.key) ? 0 : 1;
      const bMissing = missingFilters.includes(b.key) ? 0 : 1;
      return aMissing - bMissing || a.label.localeCompare(b.label, "cs");
    });
  }, [needsFilterPanel, missingFilters, availableDimensions, countryPickerOptions, buildDimensionFiltersWithCurrentGeo, selectedGeo, selectedDimensionFilters, timeField]);
  const applyAdvancedDimFilterChange = (field, value) => {
    const trimmed = String(value ?? "").trim();
    setDimFilters((prev) => {
      const next = { ...prev };
      if (trimmed) next[field] = trimmed;
      else delete next[field];
      return next;
    });
    if (!onDimensionFiltersApply) return;
    const merged = buildDimensionFiltersWithCurrentGeo();
    if (trimmed) merged[field] = trimmed;
    else delete merged[field];
    onDimensionFiltersApply(finalizeDimensionFilters(merged));
  };
  const userChoiceDimensions = useMemo(
    () =>
      buildUserChoiceDimensions(availableDimensions, {
        datasetId: previewDatasetId,
        appliedFilters: buildDimensionFiltersWithCurrentGeo(),
        selectableDimensions: Array.isArray(preview?.selectable_dimensions)
          ? preview.selectable_dimensions
          : preview?.metadata?.selectable_dimensions,
      }),
    [
      availableDimensions,
      previewDatasetId,
      buildDimensionFiltersWithCurrentGeo,
      preview?.selectable_dimensions,
      preview?.metadata?.selectable_dimensions,
    ],
  );
  const applyUserChoiceDimensionChange = (field, value) => {
    const trimmed = String(value ?? "").trim();
    if (!onDimensionFiltersApply || !trimmed) return;
    const merged = buildDimensionFiltersWithCurrentGeo();
    merged[field] = trimmed;
    onDimensionFiltersApply(finalizeDimensionFilters(merged));
  };

  const applyDimensionFilters = () => {
    const selected = {};
    for (const item of dimensionPanelItems) {
      const vals = normalizeFilterValues(
        selectedDimensionFilters[item.key] ?? item.selected,
        { geo: item.isGeo }
      );
      if (!vals.length) continue;
      selected[item.key] = item.isGeo || vals.length > 1 ? vals : vals[0];
    }
    if (selected[geoDimensionKey]) {
      const geos = normalizeFilterValues(selected[geoDimensionKey], { geo: true });
      selected[geoDimensionKey] = geos;
      setSelectedGeo(geos);
    }
    onDimensionFiltersApply?.(
      finalizeDimensionFilters(
        buildDimensionFiltersWithCurrentGeo(selected),
      ),
    );
  };

  // Pokud máme časové pole, seřadíme řádky chronologicky vzestupně.
  const chartRows = useMemo(() => {
    if (!timeField || !valueField) return [];

    // Pivot podle regionů (kraje jako série) — aktivní když uživatel zvolí přepínač
    if (activePivotByRegion && regionExtraDim) {
      const dimField = regionExtraDim.field;
      const byX = new Map();
      // Filtruj po vybraném indikátoru (nebo prvním)
      const rowsForRegion = regionPivotIndicator && groupField
        ? geoFilteredRows.filter((r) => String(r?.[groupField] ?? "").trim() === regionPivotIndicator)
        : geoFilteredRows;
      for (const r of rowsForRegion) {
        const x = String(r?.[timeField] ?? "").trim();
        const regionVal = String(r?.[dimField] ?? "").trim();
        const y = parseNumber(r?.[valueField]);
        if (!x || !regionVal || y === null) continue;
        let row = byX.get(x);
        if (!row) { row = { x }; byX.set(x, row); }
        row[`s_${regionVal}`] = y;
      }
      return Array.from(byX.values())
        .sort((a, b) => (a.x < b.x ? -1 : a.x > b.x ? 1 : 0))
        .slice(-80);
    }

    if (geoSeriesActive) {
      return buildGeoChartRows(geoFilteredRows, timeField, valueField, geoKey);
    }

    if (multiLinePivot && effectivePivotField) {
      const pivot = buildTimeSeriesPivotFromRows(geoFilteredRows, {
        groupField: String(effectivePivotField),
        seriesIds: pivotSeriesKeys,
        maxSeries: pivotSeriesKeys.length,
      });
      if (pivot.multiSeries) {
        return pivot.rows
          .map((row) => {
            const out = { x: row.period };
            for (const sid of pivot.seriesIds) {
              const y = parseNumber(row[sid]);
              if (y !== null) out[`s_${sid}`] = y;
            }
            return out;
          })
          .slice(-80);
      }
    }
    const byPeriod = new Map();
    for (const r of geoFilteredRows) {
      const x = String(r?.[timeField] ?? "").trim();
      const y = parseNumber(r?.[valueField]);
      if (!x || y === null) continue;
      byPeriod.set(x, y);
    }
    return Array.from(byPeriod.entries())
      .map(([x, y]) => ({ x, y }))
      .sort((a, b) => (a.x < b.x ? -1 : a.x > b.x ? 1 : 0))
      .slice(-80);
  }, [geoFilteredRows, timeField, valueField, geoSeriesActive, geoKey, multiLinePivot, effectivePivotField, pivotSeriesKeys,
      activePivotByRegion, regionExtraDim, regionPivotIndicator, selectedGeo]);
  const geoCodesWithData = useMemo(
    () => (geoSeriesActive ? geoCodesWithChartData(chartRows, selectedGeo) : []),
    [geoSeriesActive, chartRows, selectedGeo]
  );
  const geoSeriesRendered = useMemo(() => {
    if (!geoSeriesActive) return [];
    const pool = geoCodesWithData.length > 0 ? geoCodesWithData : selectedGeo;
    return capGeoList(pool, availableGeoOptions, MAX_GEO_SELECTION);
  }, [geoSeriesActive, geoCodesWithData, selectedGeo, availableGeoOptions]);
  const geoChartTruncated = geoSeriesActive && (
    (geoCodesWithData.length || selectedGeo.length) > MAX_GEO_SELECTION
  );
  const geoSeriesMissingData = useMemo(
    () =>
      geoSeriesActive
        ? selectedGeo.filter((code) => !geoCodesWithData.includes(asGeoCode(code)))
        : [],
    [geoSeriesActive, selectedGeo, geoCodesWithData]
  );
  const sparseGeoCompare = useMemo(
    () => (geoSeriesActive ? isSparseGeoCompare(chartRows, selectedGeo) : false),
    [geoSeriesActive, chartRows, selectedGeo]
  );
  const geoCrossSectionSnapshot = useMemo(
    () => !geoSeriesActive && Boolean(geoKey) && isGeoCrossSectionSnapshot(geoFilteredRows, geoKey),
    [geoSeriesActive, geoKey, geoFilteredRows]
  );
  const geoChartUseDots = useMemo(
    () => (geoSeriesActive ? geoSeriesNeedsPointMarkers(chartRows, geoSeriesRendered) : false),
    [geoSeriesActive, chartRows, geoSeriesRendered]
  );
  const preferTimeSeries = displayMode !== "bars_latest";
  const catalogToolbarSeriesStats = useMemo(() => {
    if (!catalogCompactPreviewChrome || forceCrossSection || chartRows.length < 1) return null;
    return computeTimeSeriesStats(chartRows);
  }, [catalogCompactPreviewChrome, forceCrossSection, chartRows]);
  // Série krajů pro legend + Lines — pouze při region pivot
  const regionSeriesKeys = useMemo(() => {
    if (!activePivotByRegion || !chartRows.length) return [];
    const keys = new Set();
    chartRows.forEach((r) => Object.keys(r).forEach((k) => { if (k !== "x") keys.add(k.replace(/^s_/, "")); }));
    return Array.from(keys).sort();
  }, [activePivotByRegion, chartRows]);

  const crossSectionCandidates = useMemo(
    () => listCrossSectionCandidates(fields, geoFilteredRows, { timeField, valueField }),
    [fields, geoFilteredRows, timeField, valueField]
  );
  useEffect(() => {
    if (!crossSectionFieldOverride) return;
    if (!crossSectionCandidates.includes(crossSectionFieldOverride)) {
      setCrossSectionFieldOverride("");
    }
  }, [crossSectionFieldOverride, crossSectionCandidates]);

  // Fallback: cross-sectional bar chart when time series has too few points.
  // Happens for datasets with only 1 time period (e.g. Eurostat census 2016).
  // Use geo/ref_area or indicator_id as X axis, value as Y.
  // crossSectionFilteredRows = plná data nejnovějšího období z dedicated fetch (bez geo limitu)
  const _csSourceRows = crossSectionFilteredRows ?? geoFilteredRows;
  const crossSection = useMemo(() => {
    // Multi-country time series and latest-value comparison are separate display modes.
    if (!forceCrossSection && geoSeriesActive) return [];
    if (
      !forceCrossSection
      && chartRows.length >= 2
      && !sparseGeoCompare
      && !geoCrossSectionSnapshot
    ) {
      return [];
    }
    if (!valueField) return [];
    if (geoSeriesActive && geoKey) {
      const selectedGeoRows = mergeSelectedGeoRows(
        geoFilteredRows,
        _csSourceRows,
        selectedGeo,
        geoKey,
      );
      let rowsForBar = selectedGeoRows.length > 0 ? selectedGeoRows : _csSourceRows;
      let latestPeriod = "";
      let availablePeriods = [];
      if (timeField) {
        const periods = [...new Set(_csSourceRows.map((r) => String(r?.[timeField] ?? "").trim()).filter(Boolean))];
        if (periods.length > 1) {
          const sorted = periods.sort((a, b) =>
            String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" })
          );
          availablePeriods = sorted;
          latestPeriod = sorted[sorted.length - 1] || "";
          const chosenPeriod = crossSectionPeriodOverride && sorted.includes(crossSectionPeriodOverride)
            ? crossSectionPeriodOverride
            : latestPeriod;
          if (chosenPeriod) {
            const filtered = _csSourceRows.filter((r) => String(r?.[timeField] ?? "").trim() === chosenPeriod);
            if (filtered.length >= 1) { rowsForBar = filtered; latestPeriod = chosenPeriod; }
          }
        }
      }
      const items = [];
      for (const code of selectedGeo) {
        const gc = asGeoCode(code);
        const row = rowsForBar.find((r) => asGeoCode(r?.[geoKey]) === gc);
        const y = row ? parseNumber(row?.[valueField]) : null;
        if (y === null) continue;
        items.push({ x: geoLabelByCode[gc] || gc, y, code: gc });
      }
      if (items.length >= 2) {
        return { rows: items, dimField: geoKey, latestPeriod, availablePeriods };
      }
    }
    const autoDim = pickCrossSectionField(fields, _csSourceRows, { timeField, valueField });
    const dimField =
      crossSectionFieldOverride && crossSectionCandidates.includes(crossSectionFieldOverride)
        ? crossSectionFieldOverride
        : autoDim;
    if (!dimField) return [];
    let rowsForBar = _csSourceRows;
    let latestPeriod = "";
    let availablePeriods = [];
    if (timeField) {
      const periods = [...new Set(_csSourceRows.map((r) => String(r?.[timeField] ?? "").trim()).filter(Boolean))];
      if (periods.length > 1) {
        const sorted = periods.sort((a, b) =>
          String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" })
        );
        availablePeriods = sorted;
        latestPeriod = sorted[sorted.length - 1] || "";
        const chosenPeriod = crossSectionPeriodOverride && sorted.includes(crossSectionPeriodOverride)
          ? crossSectionPeriodOverride
          : latestPeriod;
        if (chosenPeriod) {
          const filtered = _csSourceRows.filter((r) => String(r?.[timeField] ?? "").trim() === chosenPeriod);
          if (filtered.length >= 2) { rowsForBar = filtered; latestPeriod = chosenPeriod; }
        }
      }
    }
    const byGeo = new Map();
    for (const r of rowsForBar) {
      const x = String(r?.[dimField] ?? "").trim();
      const y = parseNumber(r?.[valueField]);
      if (!x || y === null) continue;
      if (!byGeo.has(x)) byGeo.set(x, y);
    }
    const items = Array.from(byGeo.entries())
      .map(([x, y]) => ({ x, y }))
      .sort((a, b) => (a.x < b.x ? -1 : a.x > b.x ? 1 : 0))
      .slice(0, 50);
    if (items.length >= 2) {
      return { rows: items, dimField, latestPeriod, availablePeriods };
    }
    // ČSÚ krajový pivot: poslední období z wide chartRows → sloupce po krajích
    if (forceCrossSection && activePivotByRegion && regionExtraDim && chartRows.length >= 1) {
      const keys = new Set();
      chartRows.forEach((r) =>
        Object.keys(r).forEach((k) => {
          if (k !== "x") keys.add(k.replace(/^s_/, ""));
        }),
      );
      const kraje = Array.from(keys).sort();
      if (kraje.length >= 2) {
        const latest = chartRows[chartRows.length - 1];
        const pivotItems = kraje
          .map((kraj) => {
            const y = parseNumber(latest?.[`s_${kraj}`]);
            return y != null ? { x: kraj, y } : null;
          })
          .filter(Boolean);
        if (pivotItems.length >= 2) {
          return {
            rows: pivotItems,
            dimField: regionExtraDim.field,
            latestPeriod: String(latest?.x ?? "").trim(),
          };
        }
      }
    }
    return { rows: items, dimField, latestPeriod };
  }, [
    chartRows,
    geoFilteredRows,
    crossSectionFilteredRows,
    fields,
    timeField,
    valueField,
    crossSectionFieldOverride,
    crossSectionCandidates,
    forceCrossSection,
    geoSeriesActive,
    geoKey,
    selectedGeo,
    geoLabelByCode,
    sparseGeoCompare,
    geoCrossSectionSnapshot,
    activePivotByRegion,
    regionExtraDim,
    crossSectionPeriodOverride,
  ]);
  const crossSectionRows = Array.isArray(crossSection?.rows) ? crossSection.rows : [];
  const crossSectionBarAxis = useMemo(() => {
    const values = crossSectionRows
      .map((row) => coerceChartNumeric(row?.y))
      .filter((v) => typeof v === "number" && Number.isFinite(v));
    return getYAxisDomainForChart("bar", values, { tickCount: 4 });
  }, [crossSectionRows]);
  const crossSectionBarData = useMemo(() => {
    if (!crossSectionBarAxis.useZeroBaselineShape) return crossSectionRows;
    return chartRowsWithZeroBaselineBars(crossSectionRows);
  }, [crossSectionRows, crossSectionBarAxis.useZeroBaselineShape]);
  const selectedCatalogChartSlot = useMemo(
    () => filterCatalogChartSlotBySeries(chartSlot, seriesKeys),
    [chartSlot, seriesKeys],
  );

  const userSeriesCatalogChartSlot = useMemo(() => {
    if (!selectedCatalogChartSlot || !React.isValidElement(selectedCatalogChartSlot)) return null;
    if (!preferTimeSeries || !seriesGroupDim || !effectivePivotField || !timeField || !valueField) return null;

    const pivot = buildTimeSeriesPivotFromRows(geoFilteredRows, {
      groupField: String(effectivePivotField),
      seriesIds: pivotSeriesKeys,
      maxSeries: Math.max(1, pivotSeriesKeys.length || 12),
    });
    if (!pivot.multiSeries || pivot.rows.length < 1 || pivot.seriesIds.length < 2) return null;

    const baseData = selectedCatalogChartSlot.props.data || {};
    const baseWidget = selectedCatalogChartSlot.props.widget || {};
    const baseConfig = baseWidget.config || {};
    const series = pivot.seriesIds.map((id, idx) => {
      const label =
        resolveDimensionValueLabel(effectivePivotField, id, availableDimensions)
        || geoLabelByCode[id]
        || seriesLabelForChart(displayIndicators, id)
        || id;
      return {
        key: id,
        label,
        name: label,
        color: MULTI_LINE_STROKES[idx % MULTI_LINE_STROKES.length],
      };
    });

    return React.cloneElement(selectedCatalogChartSlot, {
      data: {
        ...baseData,
        title: String(baseData.title || preview?.source?.name || preview?.title || "Graf").trim() || "Graf",
        rows: pivot.rows,
        multi_series: true,
        series,
        group_field: String(effectivePivotField),
        group_field_label:
          getUserChoiceDimensionLabel(effectivePivotField, availableDimensions)
          || getDimensionLabel(effectivePivotField, availableDimensions)
          || String(effectivePivotField),
        selected_indicators: pivot.seriesIds,
        chart_data_mode: "series",
        unit: baseData.unit || chartYAxisUnit || preview?.unit || preview?.metadata?.unit || "",
        frequency: baseData.frequency || effectiveCatalogFreqCode,
        frequency_label: baseData.frequency_label || effectiveCatalogFreqLabel,
      },
      widget: {
        ...baseWidget,
        config: {
          ...baseConfig,
          chart_data_mode: "series",
          chart_series_mode: "multi",
          selected_indicators: pivot.seriesIds,
          chart_series_dim: String(effectivePivotField),
        },
      },
    });
  }, [
    selectedCatalogChartSlot,
    preferTimeSeries,
    seriesGroupDim,
    effectivePivotField,
    timeField,
    valueField,
    geoFilteredRows,
    pivotSeriesKeys,
    availableDimensions,
    geoLabelByCode,
    displayIndicators,
    preview?.source?.name,
    preview?.title,
    preview?.unit,
    preview?.metadata?.unit,
    chartYAxisUnit,
    effectiveCatalogFreqCode,
    effectiveCatalogFreqLabel,
  ]);

  const geoSeriesCatalogChartSlot = useMemo(() => {
    if (!selectedCatalogChartSlot || !React.isValidElement(selectedCatalogChartSlot)) return null;
    if (!preferTimeSeries || !geoSeriesActive || !geoKey || chartRows.length < 2) return null;

    const visibleGeo = geoSeriesRendered.filter((code) =>
      chartRows.some((row) => parseNumber(row?.[code]) !== null),
    );
    if (visibleGeo.length < 2) return null;

    const baseData = selectedCatalogChartSlot.props.data || {};
    const baseWidget = selectedCatalogChartSlot.props.widget || {};
    const baseConfig = baseWidget.config || {};
    const series = visibleGeo.map((code, idx) => {
      const label = geoLabelByCode[code] || code;
      return {
        key: code,
        label,
        name: label,
        color: MULTI_LINE_STROKES[idx % MULTI_LINE_STROKES.length],
      };
    });
    const rows = chartRows.map((row) => {
      const period = String(row?.x ?? "").trim();
      const next = { period, x: period };
      for (const code of visibleGeo) {
        const value = parseNumber(row?.[code]);
        if (value !== null) next[code] = value;
      }
      return next;
    });

    return React.cloneElement(selectedCatalogChartSlot, {
      data: {
        ...baseData,
        rows,
        multi_series: true,
        series,
        group_field: String(geoKey),
        group_field_label: countryPickerLabel,
        selected_indicators: visibleGeo,
        chart_data_mode: "series",
        unit: baseData.unit || chartYAxisUnit || preview?.unit || preview?.metadata?.unit || "",
        frequency: baseData.frequency || effectiveCatalogFreqCode,
        frequency_label: baseData.frequency_label || effectiveCatalogFreqLabel,
      },
      widget: {
        ...baseWidget,
        config: {
          ...baseConfig,
          chart_data_mode: "series",
          chart_series_mode: "multi",
          selected_indicators: visibleGeo,
          chart_series_dim: String(geoKey),
        },
      },
    });
  }, [
    selectedCatalogChartSlot,
    preferTimeSeries,
    geoSeriesActive,
    geoKey,
    chartRows,
    geoSeriesRendered,
    geoLabelByCode,
    countryPickerLabel,
    chartYAxisUnit,
    preview?.unit,
    preview?.metadata?.unit,
    effectiveCatalogFreqCode,
    effectiveCatalogFreqLabel,
  ]);

  /** Katalog s AradView — v režimu „Srovnání hodnot" nepřepínat na interní BarChart (zmizí Typ grafu). */
  const catalogAradChartSlot = useMemo(() => {
    if (userSeriesCatalogChartSlot) return userSeriesCatalogChartSlot;
    if (geoSeriesCatalogChartSlot) return geoSeriesCatalogChartSlot;
    if (!selectedCatalogChartSlot || !React.isValidElement(selectedCatalogChartSlot)) return selectedCatalogChartSlot;
    if (!forceCrossSection || crossSectionRows.length < 2) return selectedCatalogChartSlot;

    const baseData = selectedCatalogChartSlot.props.data || {};
    const baseWidget = selectedCatalogChartSlot.props.widget || {};
    const baseConfig = baseWidget.config || {};
    const latestRows = crossSectionRows.map((r) => ({
      period: String(r.x ?? "").trim(),
      value: r.y,
      x: String(r.x ?? "").trim(),
      y: r.y,
    }));

    return React.cloneElement(selectedCatalogChartSlot, {
      data: {
        ...baseData,
        chart_data_mode: "latest",
        multi_series: false,
        series: undefined,
        group_field: undefined,
        rows: latestRows,
      },
      widget: {
        ...baseWidget,
        config: {
          ...baseConfig,
          chart_data_mode: "latest",
          chart_type: baseConfig.chart_type || "bar",
        },
      },
    });
  }, [selectedCatalogChartSlot, userSeriesCatalogChartSlot, geoSeriesCatalogChartSlot, forceCrossSection, crossSectionRows]);

  const useCatalogAradChartSlot =
    Boolean(selectedCatalogChartSlot)
    && !chartBlocked
    && (
      Boolean(userSeriesCatalogChartSlot)
      || Boolean(geoSeriesCatalogChartSlot)
      || (!seriesGroupDim && !geoSeriesActive && (preferTimeSeries || crossSectionRows.length >= 2))
    );

  const showTimeSeriesChart =
    preferTimeSeries
    && chartRows.length >= 2
    && !chartBlocked
    && !geoCrossSectionSnapshot
    && !sparseGeoCompare;

  const chartFrequencyLabel = useMemo(
    () =>
      resolveChartFrequencyLabel({
        catalogFreqLabel: effectiveCatalogFreqLabel,
        catalogFreqCode: effectiveCatalogFreqCode,
        rows: geoFilteredRows,
        chartRows,
        fields,
      }),
    [effectiveCatalogFreqLabel, effectiveCatalogFreqCode, geoFilteredRows, chartRows, fields]
  );

  const selectedLabel = (() => {
    const found = displayIndicators.find((i) => i.id === selectedIndicator);
    return found ? found.name : selectedIndicator;
  })();

  const conceptExplainKey = useMemo(
    () =>
      [
        preview?.source?.source_type,
        preview?.source?.set_id,
        preview?.source?.id,
        selectedIndicator,
        catalogCountryLabel,
      ]
        .filter(Boolean)
        .join("|"),
    [
      preview?.source?.source_type,
      preview?.source?.set_id,
      preview?.source?.id,
      selectedIndicator,
      catalogCountryLabel,
    ],
  );

  useEffect(() => {
    setConceptExplainOpen(false);
    setConceptExplainData(null);
    setConceptExplainError("");
    setConceptExplainLoading(false);
    setConceptFollowupQuestion("");
    setConceptFollowupLoading(false);
    setConceptFollowupError("");
    setConceptFollowupThread([]);
    setConceptFindInDataQuery("");
  }, [conceptExplainKey]);

  const buildConceptExplainMeta = useCallback(() => {
    const src = preview?.source || {};
    const title =
      String(selectedLabel || src.name || preview?.title || src.set_id || "").trim() || "Datová řada";
    const base = {
      source_type: src.source_type,
      catalog_id: src.source_type,
      set_id: src.set_id || preview?.dataset_id,
      dataset_id: preview?.dataset_id || src.set_id,
      title,
      name: src.name || preview?.title,
      unit: preview?.unit || preview?.metadata?.unit,
      unit_label_cs: preview?.unit_label_cs || preview?.metadata?.unit_label_cs,
      value_descriptor: catalogValueDescriptorText,
      frequency: effectiveCatalogFreqCode,
      frequency_label: effectiveCatalogFreqLabel,
      country_label: catalogCountryCtx || catalogCountryLabel,
      selected_indicator_name: selectedLabel || selectedIndicator,
      indicator_name: selectedIndicator,
      description: src.full_path || src.catalog_path,
      catalog_path: src.catalog_path,
      query_params: src.query_params || preview?.metadata?.query_params,
    };
    return enrichChartExplainMeta(base, rows, { timeField, valueField });
  }, [
    preview,
    selectedLabel,
    selectedIndicator,
    catalogValueDescriptorText,
    effectiveCatalogFreqCode,
    effectiveCatalogFreqLabel,
    catalogCountryCtx,
    catalogCountryLabel,
    rows,
    timeField,
    valueField,
  ]);

  const runConceptExplain = useCallback(async () => {
    if (previewHasError || conceptExplainLoading) return;
    setConceptExplainOpen(true);
    setConceptExplainLoading(true);
    setConceptExplainError("");
    setConceptFollowupQuestion("");
    setConceptFollowupError("");
    setConceptFollowupThread([]);
    setConceptFindInDataQuery("");
    setRelatedData(null);
    setRelatedError("");
    setRelatedLoading(false);
    try {
      const data = await fetchSeriesConceptExplanation(buildConceptExplainMeta());
      if (data?.ok && data.explanation_cz) {
        setConceptExplainData(data);
      } else {
        setConceptExplainError(String(data?.message_cs || "Vysvětlení se nepodařilo načíst."));
      }
    } catch (e) {
      setConceptExplainError(String(e?.message || "Vysvětlení se nepodařilo načíst."));
    } finally {
      setConceptExplainLoading(false);
    }
  }, [
    previewHasError,
    conceptExplainLoading,
    buildConceptExplainMeta,
  ]);

  const runRelated = useCallback(async () => {
    if (relatedLoading) return;
    setRelatedLoading(true);
    setRelatedError("");
    setRelatedData(null);
    try {
      const result = await fetchRelatedSeries(buildConceptExplainMeta());
      if (result?.ok) {
        setRelatedData(result);
      } else {
        setRelatedError(String(result?.messageCs || "Příbuzné ukazatele se nepodařilo načíst."));
      }
    } catch (e) {
      setRelatedError(String(e?.message || "Příbuzné ukazatele se nepodařilo načíst."));
    } finally {
      setRelatedLoading(false);
    }
  }, [relatedLoading, buildConceptExplainMeta]);

  const runConceptFollowup = useCallback(async () => {
    const question = String(conceptFollowupQuestion || "").trim();
    if (!question || question.length < 3 || conceptFollowupLoading || !conceptExplainData?.explanation_cz) return;
    setConceptFollowupLoading(true);
    setConceptFollowupError("");
    setConceptFindInDataQuery("");
    const historyBefore = conceptFollowupThread;
    setConceptFollowupThread((prev) => [...prev, { role: "user", content: question }]);
    setConceptFollowupQuestion("");
    try {
      const data = await fetchSeriesConceptFollowup(
        buildConceptExplainMeta(),
        question,
        conceptExplainData.explanation_cz,
        historyBefore,
      );
      if (data?.ok && data.answer_cz) {
        setConceptFollowupThread((prev) => [...prev, { role: "assistant", content: data.answer_cz }]);
        const searchQ =
          String(data.catalog_search_query || "").trim() ||
          String(
            inferFindInDataQuery(
              buildConceptExplainMeta(),
              question,
              conceptExplainData.explanation_cz,
            ) || "",
          ).trim();
        if (searchQ) setConceptFindInDataQuery(searchQ);
      } else {
        setConceptFollowupError(String(data?.message_cs || "Odpověď se nepodařila načíst."));
        setConceptFollowupThread((prev) => (prev.length ? prev.slice(0, -1) : prev));
        setConceptFollowupQuestion(question);
      }
    } catch (e) {
      setConceptFollowupError(String(e?.message || "Odpověď se nepodařila načíst."));
      setConceptFollowupThread((prev) => (prev.length ? prev.slice(0, -1) : prev));
      setConceptFollowupQuestion(question);
    } finally {
      setConceptFollowupLoading(false);
    }
  }, [
    conceptFollowupQuestion,
    conceptFollowupLoading,
    conceptExplainData,
    conceptFollowupThread,
    buildConceptExplainMeta,
  ]);

  const conceptLiveFindInDataQuery = useMemo(() => {
    const fromState = String(conceptFindInDataQuery || "").trim();
    if (fromState) return fromState;
    const meta = buildConceptExplainMeta();
    return (
      inferFindInDataQuery(meta, conceptFollowupQuestion, conceptExplainData?.explanation_cz) || ""
    );
  }, [
    conceptFindInDataQuery,
    conceptFollowupQuestion,
    conceptExplainData?.explanation_cz,
    buildConceptExplainMeta,
  ]);

  const triggerFindInCatalogSearch = useCallback(
    (queryOverride = "") => {
      const q = String(
        queryOverride || conceptLiveFindInDataQuery || conceptFollowupQuestion || "",
      ).trim();
      if (q.length < 2 || typeof onFindInCatalogSearch !== "function") return;
      onFindInCatalogSearch(q);
    },
    [conceptLiveFindInDataQuery, conceptFollowupQuestion, onFindInCatalogSearch],
  );

  const mySeriesPayload = useMemo(() => {
    if (!preview?.source?.id) return null;
    const sid = preview.source.id;
    const st = preview.source.source_type || "";
    if (selectedIndicator && String(selectedIndicator).trim()) {
      return buildMySeriesSavePayloadFromWidget({
        viewType: `${st}_view`,
        config: {
          source_id: sid,
          source_type: st,
          indicator_id: selectedIndicator,
        },
        data: preview,
        title: `${preview.source.name || sid} · ${selectedLabel || selectedIndicator}`,
      });
    }
    if (timeField && valueField) {
      return buildMySeriesSavePayloadFromWidget({
        viewType: `${st}_view`,
        config: {
          source_id: sid,
          source_type: st,
          x_field: timeField,
          y_field: valueField,
        },
        data: preview,
        title: preview.source.name || sid,
      });
    }
    return null;
  }, [preview, selectedIndicator, selectedLabel, timeField, valueField]);

  const compareLeft = useMemo(
    () =>
      buildCompareLeftRefFromWidget({
        viewType: `${preview?.source?.source_type || ""}_view`,
        config: {
          source_id: preview?.source?.id,
          source_type: preview?.source?.source_type,
          indicator_id: selectedIndicator,
          x_field: timeField,
          y_field: valueField,
        },
        data: preview,
        title: preview?.source?.name,
      }),
    [preview, selectedIndicator, timeField, valueField],
  );

  // "Srovnání hodnot" potřebuje aspoň 2 země/kategorie k porovnání (viz crossSection výše) -
  // bez toho tlačítko dřív mlčky no-opovalo a ukázalo zavádějící "málo bodů pro graf".
  const canCompareValues = geoSeriesActive || crossSectionCandidates.length > 0;

  const renderCatalogDisplayModeControls = (extraClass = "") => (
    <>
      <button
        type="button"
        onClick={() => setDisplayMode("time_series")}
        aria-label="Časová řada"
        title="Časová řada"
        className={`${catalogDisplayButtonBase} ${extraClass} ${
          displayMode === "time_series"
            ? "bg-blue-600 text-white border-blue-600"
            : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
        }`}
      >
        <Activity className="h-3.5 w-3.5 md:hidden" aria-hidden />
        <span className="hidden md:inline">Časová řada</span>
      </button>
      <button
        type="button"
        onClick={() => canCompareValues && setDisplayMode("bars_latest")}
        aria-label="Srovnání hodnot"
        title={
          canCompareValues
            ? "Srovnání hodnot"
            : "Vyžaduje alespoň 2 země nebo kategorie k porovnání - tahle řada má jen jednu."
        }
        disabled={!canCompareValues}
        className={`${catalogDisplayButtonBase} ${extraClass} ${
          displayMode === "bars_latest"
            ? "bg-blue-600 text-white border-blue-600"
            : canCompareValues
              ? "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
              : "bg-slate-50 text-slate-300 border-border/40 cursor-not-allowed"
        }`}
      >
        <BarChart3 className="h-3.5 w-3.5 md:hidden" aria-hidden />
        <span className="hidden md:inline">Srovnání hodnot</span>
      </button>
    </>
  );

  const renderGeoDraftSelector = () => {
    const selectedCodes = Array.isArray(draftGeo) ? draftGeo : [];
    // Dimenze existuje (geoKey/„ÚZEMÍ-KRAJ"), ale sada nenabízí žádné území k porovnání — typicky
    // celostátní/souhrnná sada (ČSÚ „Průměrné ceny nemovitostí" bez krajského členění). Místo
    // mrtvého „Žádné země k výběru" řekneme konkrétně proč a kam pro krajské srovnání jít.
    if (countryPickerOptions.length === 0) {
      return (
        <div className="text-[11px] leading-relaxed text-slate-600">
          Tato datová sada nemá územní členění k porovnání — obsahuje jen souhrnný (celostátní) údaj.
          Pro srovnání krajů zvolte územní/regionální variantu téže sady (např. s označením
          „v krajích" nebo „územní srovnání").
        </div>
      );
    }
    const selectedSet = new Set(selectedCodes);
    const remainingOptions = countryPickerOptions.filter((opt) => opt?.value && !selectedSet.has(opt.value));
    const maxReached = selectedCodes.length >= MAX_GEO_SELECTION;
    const selectOptions =
      selectedCodes.length <= 1
        ? countryPickerOptions
        : remainingOptions.length > 0
          ? remainingOptions
          : countryPickerOptions;
    const selectValue = selectedCodes.length === 1 ? selectedCodes[0] : "";
    const selectPrompt =
      countryPickerOptions.length === 0
        ? "Žádné země k výběru"
        : maxReached
          ? "Maximum vybraných zemí dosaženo"
        : selectedCodes.length > 1
          ? remainingOptions.length > 0 && !maxReached
            ? "Přidat další zemi…"
            : "Všechny dostupné země jsou vybrané"
          : "Vyberte zemi…";
    return (
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={selectValue}
            onChange={(e) => {
              const code = asGeoCode(e.target.value);
              if (!code || selectedSet.has(code) || maxReached) return;
              addDraftGeoCode(code);
            }}
            disabled={countryPickerOptions.length === 0 || maxReached}
            className="h-9 min-w-[240px] max-w-full rounded-md border border-border/70 bg-white px-3 text-[12px] text-slate-800 disabled:opacity-55"
          >
            <option value="">{selectPrompt}</option>
            {selectOptions.map((opt) => (
              <option key={`geo-select-${opt.value}`} value={opt.value}>
                {labelForGeo(opt.value, opt.label)}
                {selectedSet.has(opt.value)
                  ? " · vybráno"
                  : Number(opt?.rowCount || 0) > 0
                    ? " · dostupná data"
                    : ""}
              </option>
            ))}
          </select>
        </div>
        {selectedCodes.length > 0 ? (
          <div className="flex flex-wrap gap-1.5">
            {selectedCodes.map((code) => (
              <span
                key={`geo-chip-${code}`}
                className="inline-flex items-center gap-1.5 rounded-md border border-blue-200 bg-blue-50 px-2 py-1 text-[11px] font-medium text-blue-900"
              >
                {geoLabelByCode[code] || labelForGeo(code)}
                <button
                  type="button"
                  onClick={() => removeDraftGeoCode(code)}
                  className="rounded-sm p-0.5 text-blue-700 hover:bg-blue-100 hover:text-blue-950"
                  aria-label={`Odebrat ${geoLabelByCode[code] || labelForGeo(code)}`}
                >
                  <X className="h-3 w-3" aria-hidden />
                </button>
              </span>
            ))}
          </div>
        ) : (
          <div className="text-[11px] text-slate-500">
            Vyberte jednu nebo více zemí pro graf.
          </div>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={applyGeoSelection}
            className="h-8 px-3 text-[11px] rounded-md bg-blue-600 text-white font-medium"
          >
            Použít výběr
          </button>
          <button
            type="button"
            onClick={cancelGeoSelection}
            className="h-8 px-3 text-[11px] rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50 font-medium"
          >
            Zrušit
          </button>
          <button
            type="button"
            onClick={clearDraftGeo}
            className="h-8 px-3 text-[11px] rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50 font-medium"
          >
            Vymazat výběr
          </button>
        </div>
        <div className="text-[10px] text-slate-500">
          Vybráno: {selectedCodes.length || 0} (max {MAX_GEO_SELECTION} pro graf)
          {countryPickerOptions.length > 0 ? (
            <span>
              {" "}
              · {countryPickerOptions.length} zemí v katalogu
              {countryPickerOptions.some((o) => o.rowCount > 0)
                ? ` (${countryPickerOptions.filter((o) => o.rowCount > 0).length} s daty pro tuto řadu)`
                : ""}
            </span>
          ) : null}
          {selectedCodes.length >= MAX_GEO_SELECTION ? " · Maximum vybraných zemí dosaženo." : ""}
        </div>
      </div>
    );
  };

  const renderGeoPickerPanel = ({ compact = false } = {}) => (
    <div
      className={`${
        compact
          ? "px-3 py-2 border-b border-border/60 bg-blue-50/40"
          : "px-5 py-2.5 border-b border-border/60 bg-slate-50/70"
      } flex items-start gap-3 flex-wrap`}
      data-testid={compact ? "catalog-unified-geo-picker" : "source-preview-geo-picker"}
    >
      <span className="text-[10px] font-semibold uppercase tracking-[0.14em] text-slate-500 pt-2">
        {countryPickerLabel}
      </span>
      <div className="flex-1 min-w-[240px] max-w-[720px]">
        {renderGeoDraftSelector()}
      </div>
    </div>
  );

  return (
    <div
      className={
        isCatalogInlineSearchPreview
          ? "overflow-hidden min-w-0"
          : `soft-card overflow-hidden border-[hsl(var(--primary)/0.22)]${
              isFullscreenChartPreview ? " max-md:flex max-md:flex-col max-md:flex-1 max-md:min-h-0 max-md:h-full" : ""
            }`
      }
      data-testid={isCatalogInlineSearchPreview ? "catalog-inline-search-preview" : undefined}
    >
      {!isFullscreenChartPreview && !isCatalogDetailPreview && !isCatalogInlineSearchPreview ? (
        <div className="px-5 py-3 border-b border-border/60 flex items-center justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="kpi-label">Náhled dat</div>
            {!isCatalogDetailPreview ? (
              <div className="text-sm font-semibold text-slate-800 truncate">
                {catalogCountryCtx && compact ? catalogCountryCtx : preview?.source?.name || "Zdroj"}
              </div>
            ) : null}
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {!previewHasError && rows.length > 0 ? (
              <button
                type="button"
                onClick={() => {
                  if (conceptExplainOpen && conceptExplainData && !conceptExplainLoading) {
                    setConceptExplainOpen(false);
                    return;
                  }
                  void runConceptExplain();
                }}
                disabled={conceptExplainLoading}
                className="inline-flex items-center gap-1.5 h-8 px-2.5 rounded-lg border border-violet-200 bg-violet-50 text-[11px] font-medium text-violet-900 hover:bg-violet-100 disabled:opacity-60"
                title="AI rozloží název ukazatele a vysvětlí, co konkrétně měří — ne hodnotu z grafu"
                data-testid="series-concept-explain-btn"
              >
                {conceptExplainLoading ? (
                  <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden />
                ) : (
                  <Sparkles className="h-3.5 w-3.5 text-violet-700" aria-hidden />
                )}
                Co to ukazuje?
              </button>
            ) : null}
            {onClose && (
              <button
                type="button"
                onClick={onClose}
                className="h-8 w-8 rounded-lg border border-border/70 bg-white hover:bg-slate-50 grid place-items-center"
                title="Zavřít náhled"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
        </div>
      ) : null}
      {conceptExplainOpen ? (
        <div
          className="px-5 py-3 border-b border-violet-200/80 bg-gradient-to-br from-violet-50/95 to-indigo-50/80 text-[12px] text-slate-800 leading-relaxed"
          data-testid="series-concept-explain-panel"
        >
          <div className="text-[10px] font-semibold uppercase tracking-wide text-violet-800 mb-1.5">
            Co tento ukazatel znamená
          </div>
          {conceptExplainLoading ? (
            <p className="text-slate-600">AI připravuje vysvětlení…</p>
          ) : conceptExplainError ? (
            <p className="text-rose-800">{conceptExplainError}</p>
          ) : conceptExplainData?.explanation_cz ? (
            <>
              <p>{conceptExplainData.explanation_cz}</p>
              {conceptExplainData.read_hint_cz ? (
                <p className="mt-2 text-[11px] text-slate-600 border-t border-violet-200/60 pt-2">
                  <span className="font-medium text-slate-700">Jak číst graf: </span>
                  {conceptExplainData.read_hint_cz}
                </p>
              ) : null}
              {seriesConceptExplainSourceNote(conceptExplainData) ? (
                <p className="mt-1.5 text-[10px] text-slate-500">
                  {seriesConceptExplainSourceNote(conceptExplainData)}
                </p>
              ) : null}
              <div className="mt-3 border-t border-violet-200/60 pt-3 space-y-2">
                <button
                  type="button"
                  onClick={() => void runRelated()}
                  disabled={relatedLoading}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-violet-300 bg-white/80 px-2.5 py-1.5 text-[11px] font-medium text-violet-800 hover:bg-violet-50 disabled:opacity-50"
                  data-testid="related-series-button"
                >
                  {relatedLoading ? (
                    <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden />
                  ) : (
                    <Network className="h-3.5 w-3.5" aria-hidden />
                  )}
                  {relatedLoading ? "Hledám příbuzné ukazatele…" : "Načíst příbuzné ukazatele"}
                </button>
                {relatedError ? (
                  <p className="text-[11px] text-rose-800">{relatedError}</p>
                ) : null}
                {relatedData ? (
                  relatedData.items.length > 0 ? (
                    <div className="space-y-1.5">
                      <ul className="space-y-1.5">
                        {relatedData.items.map((it, idx) => {
                          const canOpen = typeof onFindInCatalogSearch === "function";
                          return (
                            <li key={`${it.source}:${it.set_id}:${idx}`}>
                              <button
                                type="button"
                                disabled={!canOpen}
                                onClick={() => canOpen && onFindInCatalogSearch(it.title)}
                                title={canOpen ? `Vyhledat v katalogu: ${it.title}` : it.title}
                                className={
                                  "w-full text-left rounded-lg border border-violet-100 bg-white/80 px-2.5 py-1.5 transition " +
                                  (canOpen
                                    ? "hover:border-violet-300 hover:bg-violet-50 cursor-pointer"
                                    : "cursor-default")
                                }
                                data-testid="related-series-item"
                              >
                                <div className="text-[11.5px] font-medium text-slate-800 leading-snug line-clamp-2">
                                  {it.title}
                                </div>
                                <div className="mt-0.5 flex items-center justify-between gap-2">
                                  <span className="text-[10px] text-slate-500">
                                    {it.source_label || (it.source || "").toUpperCase()}
                                  </span>
                                  {canOpen ? (
                                    <span className="inline-flex items-center gap-0.5 text-[10px] font-medium text-violet-700">
                                      <Search className="h-3 w-3" aria-hidden />
                                      Vyhledat
                                    </span>
                                  ) : null}
                                </div>
                              </button>
                            </li>
                          );
                        })}
                      </ul>
                      <p className="text-[10px] text-slate-500">
                        {`Nalezeno ${relatedData.items.length} ${relatedData.items.length === 1 ? "řada" : relatedData.items.length < 5 ? "řady" : "řad"} ze ${relatedData.distinctSourceCount} ${relatedData.distinctSourceCount === 1 ? "zdroje" : "zdrojů"}.`}
                        {relatedData.aiUsed ? "" : " (bez AI)"}
                      </p>
                    </div>
                  ) : (
                    <p className="text-[11px] text-slate-600">
                      {relatedData.messageCs || "Žádné dostatečně relevantní příbuzné ukazatele jsme nenašli."}
                    </p>
                  )
                ) : null}
              </div>
              <div className="mt-3 border-t border-violet-200/60 pt-3 space-y-2">
                <div className="text-[10px] font-semibold uppercase tracking-wide text-violet-800">
                  Zeptejte se dál
                </div>
                <p className="text-[10px] text-slate-600">
                  Např. „Jak to souvisí s M2?“ nebo „Co znamená růst spolu s devizovými rezervami?“
                </p>
                {conceptFollowupThread.length > 0 ? (
                  <div className="space-y-2 max-h-40 overflow-y-auto pr-1" data-testid="series-concept-followup-thread">
                    {conceptFollowupThread.map((turn, idx) => (
                      <div
                        key={`${turn.role}-${idx}`}
                        className={
                          turn.role === "user"
                            ? "rounded-lg bg-white/80 border border-violet-100 px-2.5 py-1.5 text-[11px] text-slate-800"
                            : "rounded-lg bg-violet-100/70 border border-violet-200/80 px-2.5 py-1.5 text-[11px] text-slate-800"
                        }
                      >
                        <span className="font-semibold text-violet-900">
                          {turn.role === "user" ? "Vy: " : "AI: "}
                        </span>
                        {turn.content}
                      </div>
                    ))}
                  </div>
                ) : null}
                {conceptFollowupError ? (
                  <p className="text-[11px] text-rose-800">{conceptFollowupError}</p>
                ) : null}
                {typeof onFindInCatalogSearch === "function" ? (
                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      disabled={conceptFollowupLoading || conceptLiveFindInDataQuery.length < 2}
                      onClick={() => triggerFindInCatalogSearch()}
                      className="inline-flex items-center gap-1.5 h-8 px-2.5 rounded-lg border border-emerald-300 bg-emerald-50 text-[11px] font-medium text-emerald-900 hover:bg-emerald-100 disabled:opacity-50"
                      title={
                        conceptLiveFindInDataQuery
                          ? `Hledat: ${conceptLiveFindInDataQuery}`
                          : "Napište, jaký ukazatel hledáte (např. obecná nezaměstnanost), nebo se nejdřív zeptejte AI"
                      }
                      data-testid="series-concept-find-in-data-btn"
                    >
                      <Search className="h-3.5 w-3.5" aria-hidden />
                      Najít v datech
                    </button>
                    {conceptLiveFindInDataQuery ? (
                      <span className="text-[10px] text-slate-600 truncate max-w-[min(100%,20rem)]" title={conceptLiveFindInDataQuery}>
                        Dotaz: {conceptLiveFindInDataQuery}
                      </span>
                    ) : (
                      <span className="text-[10px] text-slate-500">
                        Po dotazu typu „chci obecnou nezaměstnanost“ nabídne konkrétní hledání v katalogu.
                      </span>
                    )}
                  </div>
                ) : null}
                <form
                  className="flex items-center gap-2"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void runConceptFollowup();
                  }}
                >
                  <input
                    type="text"
                    value={conceptFollowupQuestion}
                    onChange={(e) => setConceptFollowupQuestion(e.target.value)}
                    placeholder="Jak souvisí s jiným ukazatelem…"
                    className="flex-1 min-w-0 h-8 rounded-lg border border-violet-200 bg-white px-2.5 text-[11px] text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-violet-300/80"
                    data-testid="series-concept-followup-input"
                  />
                  <button
                    type="submit"
                    disabled={
                      conceptFollowupLoading ||
                      String(conceptFollowupQuestion || "").trim().length < 3
                    }
                    className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-violet-300 bg-violet-600 text-white hover:bg-violet-700 disabled:opacity-50"
                    title="Odeslat otázku"
                    data-testid="series-concept-followup-submit"
                  >
                    {conceptFollowupLoading ? (
                      <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden />
                    ) : (
                      <Send className="h-3.5 w-3.5" aria-hidden />
                    )}
                  </button>
                </form>
              </div>
            </>
          ) : null}
        </div>
      ) : null}
      {eurostatSummary ? (
        <div className="px-5 py-2 border-b border-border/60 bg-indigo-50/60 text-[11px] text-slate-700 flex items-center gap-2 flex-wrap">
          <span className="font-semibold text-indigo-700">
            {eurostatSummary.showing_all_countries ? "Showing all countries" : "Showing selected countries"}
          </span>
          {eurostatSummary.indicator ? <span>· indicator: {eurostatSummary.indicator}</span> : null}
          {eurostatSummary.time_range ? <span>· time: {eurostatSummary.time_range}</span> : null}
          <span>· series: {Number(eurostatSummary.series_count || 0)}</span>
          {eurostatSummary.partial && eurostatSummary.warning ? (
            <span className="text-amber-700">· warning: {eurostatSummary.warning}</span>
          ) : null}
        </div>
      ) : null}
      {String(preview?.bis_preview_notice_cs || "").trim() ? (
        <div className="px-5 py-2 border-b border-amber-200/80 bg-amber-50/90 text-[11px] text-amber-950 leading-snug">
          {String(preview.bis_preview_notice_cs).trim()}
        </div>
      ) : null}
      {chartBlocked ? (
        <div className="px-5 py-2 border-b border-amber-200/80 bg-amber-50/80 text-[11px] text-amber-950 leading-snug">
          {previewText(preview?.message) || (
            <>
              Pro tuto kombinaci filtrů nejsou k dispozici nenulová data. U tabulek typu FIGARO / národních
              účtů zkuste v panelu filtrů změnit <strong>ind_use</strong> (odvětví použití) nebo{" "}
              <strong>cpa2_1</strong> (produktová klasifikace) — výchozí „T / CPA_T“ často neobsahuje data pro Česko.
            </>
          )}
          {" "}
          Graf se nezobrazí, dokud nevyberete kombinaci s reálnými hodnotami.
        </div>
      ) : null}
      {catalogUnifiedToolbar ? (
        <div className={catalogToolbarClass} data-testid="catalog-unified-toolbar">
          {catalogValueDescriptorText ? (
            <span
              className="w-full xl:w-auto xl:max-w-[min(100%,22rem)] text-[10px] text-teal-950 leading-snug truncate"
              title={catalogValueDescriptorText}
              data-testid="catalog-value-descriptor"
            >
              <span className="font-semibold uppercase tracking-wide text-teal-800/90 mr-1">
                Co ukazuje:
              </span>
              {catalogValueDescriptorText}
            </span>
          ) : null}
          {canShowCountrySelector ? (
            <button
              type="button"
              onClick={openGeoPicker}
              className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-blue-200 bg-blue-50 px-2 py-1 text-[10px] text-blue-800 hover:bg-blue-100 font-medium h-7 max-w-[min(52vw,18rem)]"
              title={compactGeoButtonLabel}
            >
              <span className="truncate">{compactGeoButtonLabel}</span>
              <span className="hidden sm:inline text-blue-700/75">Vybrat / porovnat</span>
            </button>
          ) : null}
          {displayIndicators.length > 1 && groupField && !isGeoGroup ? (
            <>
              <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-500 shrink-0">
                {groupLabel} ({displayIndicators.length})
              </span>
              <div className="relative min-w-[9rem] max-w-[min(100%,18rem)] flex-1">
                <select
                  value={selectedIndicator}
                  onChange={(e) => onIndicatorChange?.(e.target.value)}
                  disabled={!onIndicatorChange}
                  className="appearance-none h-7 w-full pl-2 pr-7 border border-border rounded-sm bg-white truncate text-[11px]"
                  title={selectedLabel}
                >
                  {displayIndicators.map((ind) => (
                    <option key={ind.id} value={ind.id}>
                      {formatIndicatorSelectLabel(ind)}
                    </option>
                  ))}
                </select>
                <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400 pointer-events-none" />
              </div>
              {onIndicatorSelectionChange ? (
                <PreviewGroupCompareDropdown
                  groupFieldLabel={groupLabel.toLowerCase()}
                  groupField={String(groupField || "")}
                  indicators={displayIndicators}
                  selectedIds={seriesKeys}
                  selectedIndicator={selectedIndicator}
                  onSelectionChange={handleCompareIndicatorSelectionChange}
                  disabled={loading}
                  compactMobile={catalogMobileChartFirst}
                  className="shrink-0"
                />
              ) : null}
            </>
          ) : null}
          <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-500 shrink-0">
            Zobrazení:
          </span>
          {renderCatalogDisplayModeControls()}
          {userChoiceDimensions.length > 0 && onDimensionFiltersApply ? (
            <div className="flex min-w-0 flex-wrap items-center gap-1.5">
              {userChoiceDimensions.map((dim) => (
                <label key={`toolbar-user-choice-${dim.field}`} className="inline-flex min-w-0 items-center gap-1.5">
                  <span className="text-[10px] font-semibold uppercase tracking-[0.08em] text-slate-500">
                    {dim.label}:
                  </span>
                  <select
                    value={dim.selected || dim.options[0]?.value || ""}
                    onChange={(e) => applyUserChoiceDimensionChange(dim.field, e.target.value)}
                    disabled={loading}
                    className="h-7 max-w-[min(52vw,18rem)] rounded-md border border-border/70 bg-white px-2 text-[11px]"
                  >
                    {dim.options.map((opt) => (
                      <option key={`${dim.field}-${opt.value}`} value={opt.value}>
                        {formatUserChoiceOptionLabel(opt)}
                      </option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
          ) : null}
          {filteredExtraDims.length > 0 && !useEurostatCascadePicker ? (
            <button
              type="button"
              onClick={() => setShowAdvancedFilters((v) => !v)}
              className={`${catalogDisplayButtonBase} border-border/70 bg-white text-slate-600 hover:bg-slate-50`}
            >
              {showAdvancedFilters ? "Skrýt filtry" : `Filtry (${filteredExtraDims.length})`}
            </button>
          ) : null}
          {catalogToolbarSeriesStats ? (
            <div
              className="flex min-w-0 max-w-full flex-wrap items-center gap-x-2.5 gap-y-0.5 text-[10px] font-mono text-slate-800 md:shrink-0 md:ml-auto"
              data-testid="catalog-toolbar-kpi"
            >
              <span title="Poslední hodnota">
                <span className="text-[9px] uppercase tracking-wide text-slate-500 mr-0.5">Poslední</span>
                {formatSeriesStatValue(catalogToolbarSeriesStats.lastValue, chartYAxisUnit)}
              </span>
              {catalogToolbarSeriesStats.hasChange ? (
                <span
                  className={
                    catalogToolbarSeriesStats.delta > 0
                      ? "text-emerald-700"
                      : catalogToolbarSeriesStats.delta < 0
                        ? "text-rose-700"
                        : "text-slate-600"
                  }
                  title="Změna oproti předchozímu období"
                >
                  <span className="text-[9px] uppercase tracking-wide text-slate-500 mr-0.5">Změna</span>
                  {[formatAbsoluteChange(catalogToolbarSeriesStats.delta, chartYAxisUnit), formatRelativeChange(
                    catalogToolbarSeriesStats.lastValue,
                    catalogToolbarSeriesStats.prevValue,
                  )]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              ) : null}
              <span title="Poslední období">
                <span className="text-[9px] uppercase tracking-wide text-slate-500 mr-0.5">Období</span>
                {fmtPeriodLabel(catalogToolbarSeriesStats.lastPeriod)}
              </span>
            </div>
          ) : null}
        </div>
      ) : null}
      {catalogUnifiedToolbar && showGeoPicker && canShowCountrySelector ? renderGeoPickerPanel({ compact: true }) : null}
      {catalogUnifiedToolbar && showAdvancedFilters && filteredExtraDims.length > 0 && !useEurostatCascadePicker ? (
        <div className="px-3 py-2 border-b border-border/60 bg-blue-50/50 flex items-center gap-2 flex-wrap">
          {displayMode === "time_series" ? (
            <label className="flex items-center gap-1.5">
              <span className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide">Série:</span>
              <select
                value={seriesGroupDim}
                onChange={(e) => { setSeriesGroupDim(e.target.value); setSeriesSelection([]); }}
                className="h-7 text-[11px] border border-border/70 rounded-md px-2 bg-white"
              >
                <option value="">— jedna řada —</option>
                {filteredExtraDims.map((dim) => (
                  <option key={dim.field} value={dim.field}>
                    {getUserChoiceDimensionLabel(dim.field, availableDimensions) ||
                      getDimensionLabel(dim.field, availableDimensions)}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
          {seriesGroupDim && displayMode === "time_series" && allSeriesForDim.length > 0 ? (
            <div className="w-full border-t border-blue-100 mt-1 pt-1.5">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide">
                  Řady ({seriesSelection.length > 0 ? seriesSelection.length : Math.min(12, allSeriesForDim.length)}/{allSeriesForDim.length}):
                </span>
                <button
                  type="button"
                  onClick={() => setSeriesSelection([...allSeriesForDim])}
                  className="text-[10px] text-blue-600 hover:underline"
                >Vše</button>
              </div>
              <div className="flex flex-wrap gap-1 max-h-24 overflow-y-auto">
                {allSeriesForDim.map((id) => {
                  const checked = seriesSelection.length === 0
                    ? allSeriesForDim.indexOf(id) < 12
                    : seriesSelection.includes(id);
                  return (
                    <label key={id} className="flex items-center gap-0.5 cursor-pointer bg-white rounded px-1.5 py-0.5 border border-border/60 text-[10px]">
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={(ev) => {
                          const effective = seriesSelection.length === 0 ? allSeriesForDim.slice(0, 12) : [...seriesSelection];
                          if (ev.target.checked) setSeriesSelection([...effective, id]);
                          else setSeriesSelection(effective.filter((x) => x !== id));
                        }}
                        className="w-3 h-3"
                      />
                      <span className="max-w-[120px] truncate" title={id}>{id}</span>
                    </label>
                  );
                })}
              </div>
            </div>
          ) : null}
          {filteredExtraDims.filter((dim) => dim.field !== seriesGroupDim || displayMode !== "time_series").map((dim) => (
            <label key={dim.field} className="flex items-center gap-1.5">
              <span className="text-[10px] text-slate-600">
                {getUserChoiceDimensionLabel(dim.field, availableDimensions) ||
                  getDimensionLabel(dim.field, availableDimensions)}:
              </span>
              <select
                value={dimFilters[dim.field] || ""}
                onChange={(e) => applyAdvancedDimFilterChange(dim.field, e.target.value)}
                className="h-7 text-[11px] border border-border/70 rounded-md px-2 bg-white max-w-[280px]"
              >
                <option value="">— vše ({dim.values.length}) —</option>
                {dim.values.map((v) => (
                  <option key={v} value={v}>
                    {resolveDimensionValueLabel(dim.field, v, availableDimensions)}
                  </option>
                ))}
              </select>
            </label>
          ))}
        </div>
      ) : null}
      {catalogDetailSettingsShell && !catalogUnifiedToolbar ? (
        <button
          type="button"
          onClick={() => setCatalogSettingsOpen((open) => !open)}
          className="w-full px-3 py-2 border-b border-border/60 bg-slate-50/50 flex items-center gap-2 text-[11px] text-slate-700 hover:bg-slate-50/90 text-left"
          data-testid="catalog-detail-settings-toggle"
          aria-expanded={catalogSettingsOpen}
        >
          <ChevronRight
            className={`h-3.5 w-3.5 shrink-0 text-slate-500 transition-transform ${
              catalogSettingsOpen ? "rotate-90" : ""
            }`}
          />
          <span className="font-medium text-slate-800 shrink-0">Nastavení grafu</span>
          {catalogSettingsSummary ? (
            <span className="min-w-0 flex-1 truncate text-muted-foreground">{catalogSettingsSummary}</span>
          ) : null}
        </button>
      ) : null}
      {(!catalogDetailSettingsShell || catalogSettingsOpen) && !catalogUnifiedToolbar && (
        <>
      {catalogValueDescriptorText ? (
        <div
          className={`${
            catalogDetailSettingsShell
              ? "px-3 py-2 border-b border-border/50 bg-teal-50/60 text-[10px] text-teal-950 leading-snug"
              : "px-5 py-2.5 border-b border-border/60 bg-teal-50/80 text-[11px] text-teal-950 leading-snug"
          }`}
          data-testid="catalog-value-descriptor"
        >
          <span className="font-semibold uppercase tracking-wide text-[10px] text-teal-800/90 mr-1.5">
            Co ukazuje hodnota:
          </span>
          <span className={catalogDetailSettingsShell ? "line-clamp-2" : ""}>{catalogValueDescriptorText}</span>
        </div>
      ) : null}
      {indicators.length > 1 && groupField && !isGeoGroup ? (
        <div
          className={`${
            catalogMobileChartFirst
              ? "px-2.5 py-1.5 md:px-5 md:py-2.5 border-b border-border/60 bg-slate-50/70 flex items-center gap-1.5 md:gap-2 flex-nowrap md:flex-wrap overflow-x-auto"
              : "px-5 py-2.5 border-b border-border/60 bg-slate-50/70 flex items-center gap-2 flex-wrap"
          }`}
        >
          <span
            className={`text-[10px] font-semibold uppercase tracking-[0.14em] text-slate-500 shrink-0 ${
              catalogMobileChartFirst ? "hidden md:inline" : ""
            }`}
          >
            {groupLabel} ({indicators.length}):
          </span>
          <span
            className={`text-[9px] font-semibold uppercase tracking-[0.12em] text-slate-500 shrink-0 md:hidden ${
              catalogMobileChartFirst ? "" : "hidden"
            }`}
          >
            {groupLabel.slice(0, 3)} ({indicators.length})
          </span>
          <div
            className={`relative flex-1 min-w-0 ${
              catalogMobileChartFirst ? "max-w-none md:max-w-[460px]" : "min-w-[180px] max-w-[460px]"
            }`}
          >
            <select
              value={selectedIndicator}
              onChange={(e) => onIndicatorChange?.(e.target.value)}
              disabled={!onIndicatorChange}
              className={`appearance-none w-full pl-2.5 pr-8 border border-border rounded-sm bg-white truncate ${
                catalogMobileChartFirst ? "h-7 text-[11px] md:h-8 md:text-xs" : "h-8 text-xs"
              }`}
              title={selectedLabel}
            >
              {displayIndicators.map((ind) => (
                <option key={ind.id} value={ind.id}>
                  {formatIndicatorSelectLabel(ind)}
                </option>
              ))}
            </select>
            <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400 pointer-events-none" />
          </div>
          {onIndicatorSelectionChange ? (
            <PreviewGroupCompareDropdown
              groupFieldLabel={groupLabel.toLowerCase()}
              groupField={String(groupField || "")}
              indicators={displayIndicators}
              selectedIds={seriesKeys}
              selectedIndicator={selectedIndicator}
              onSelectionChange={handleCompareIndicatorSelectionChange}
              disabled={loading}
              compactMobile={catalogMobileChartFirst}
              className="shrink-0"
            />
          ) : null}
          {showMobileMergedDisplayRow ? (
            <div className="flex md:hidden items-center gap-1 shrink-0 ml-auto">
              {renderCatalogDisplayModeControls()}
            </div>
          ) : null}
        </div>
      ) : null}
      {catalogSingleGeoMode && (onGeoSelectionChange || onDimensionFiltersApply) ? (
        <div
          className={
            showMobileGeoAndDisplayRow
              ? "px-2.5 py-1.5 md:px-5 md:py-2 border-b border-border/60 bg-slate-50/60 flex items-center gap-1.5 md:gap-2 flex-nowrap md:flex-wrap overflow-x-auto"
              : "px-5 py-2 border-b border-border/60 bg-slate-50/60 flex flex-wrap items-center gap-2"
          }
        >
          <button
            type="button"
            onClick={openGeoPicker}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-blue-200 bg-blue-50 px-2.5 py-1 text-[11px] text-blue-800 hover:bg-blue-100 font-medium max-md:h-7 max-md:px-2 max-md:text-[10px]"
          >
            Srovnat s dalšími zeměmi
          </button>
          {showMobileGeoAndDisplayRow ? (
            <div className="flex md:hidden items-center gap-1 shrink-0 ml-auto">
              {renderCatalogDisplayModeControls()}
            </div>
          ) : null}
        </div>
      ) : null}
      {figaroCountryContext.isFigaro && figaroCountryContext.primaryCode ? (
        <div
          className="px-5 py-2 border-b border-amber-200/80 bg-amber-50/70 text-[11px] text-amber-950 flex flex-wrap items-center gap-x-2 gap-y-1"
          data-testid="figaro-country-context"
        >
          <span className="font-semibold">
            {countryPickerLabel}: {figaroCountryContext.primaryLabel || figaroCountryContext.primaryCode}
          </span>
          <span className="text-[10px] text-amber-900/75">
            Určení: {figaroCountryContext.destLabel || "Evropská unie (EU)"} (výchozí)
            {" · "}Časová řada pro vybranou zemi — změňte níže
          </span>
        </div>
      ) : null}
      {hasCountryPicker && !catalogSingleGeoMode ? (
        <div className="px-5 py-2.5 border-b border-border/60 bg-slate-50/70 flex items-start gap-3 flex-wrap">
          <span className="text-[10px] font-semibold uppercase tracking-[0.14em] text-slate-500 pt-1">
            {countryPickerLabel}
          </span>
          {showGeoPicker ? (
            <div className="flex-1 min-w-[240px] max-w-[560px]">
              {renderGeoDraftSelector()}
            </div>
          ) : (
            <div className="text-[11px] text-slate-700 font-mono flex flex-wrap items-center gap-2">
              {figaroCountryContext.isFigaro &&
              countryPickerOptions.length > 1 &&
              selectedGeo.length <= 1 ? (
                <label className="inline-flex items-center gap-2">
                  <span className="text-[10px] text-slate-500">{countryPickerLabel}:</span>
                  <select
                    value={selectedGeo[0] || ""}
                    onChange={(e) => {
                      const code = asGeoCode(e.target.value);
                      if (code) applyGeoCodes([code]);
                    }}
                    className="h-7 min-w-[180px] text-[11px] border border-border/70 rounded-md px-2 bg-white"
                  >
                    {countryPickerOptions.map((opt) => (
                      <option key={`figaro-origin-${opt.value}`} value={opt.value}>
                        {labelForGeo(opt.value, opt.label)}
                      </option>
                    ))}
                  </select>
                </label>
              ) : (
              <span>
                {selectedGeo.length > 1
                  ? `${countryPickerLabel}: ${selectedGeo.map((code) => geoLabelByCode[code] || code).join(", ")}`
                  : `${countryPickerLabel}: ${geoLabelByCode[selectedGeo[0]] || rowsGeoOptions[0]?.label || selectedGeo[0] || "—"}`}
              </span>
              )}
              {(countryPickerOptions.length > 1 || selectedGeo.length >= 1) ? (
                <button
                  type="button"
                  onClick={openGeoPicker}
                  className="inline-flex items-center gap-1.5 rounded-md border border-blue-200 bg-blue-50 px-2.5 py-1 text-[11px] text-blue-800 hover:bg-blue-100 font-medium"
                  title="Přidat / odebrat země"
                >
                  {selectedGeo.length <= 1 ? "Srovnat s dalšími zeměmi" : "Přidat / odebrat země"}
                  {selectedGeo.length <= 1 ? (
                    <span className="text-[10px] font-normal text-blue-700/75">Přidat / odebrat země</span>
                  ) : null}
                </button>
              ) : null}
              {quickCompareGeoOptions.length > 0 ? (
                <span className="inline-flex items-center gap-1 flex-wrap">
                  <span className="text-[10px] text-slate-500">Rychle porovnat:</span>
                  {quickCompareGeoOptions.map((opt) => (
                    <button
                      key={`quick-geo-${opt.value}`}
                      type="button"
                      onClick={() => applyGeoCodes([...(selectedGeo || []), opt.value])}
                      className="h-6 px-2 text-[10px] rounded border border-border/70 bg-white text-slate-700 hover:bg-slate-50 font-mono"
                    >
                      + {opt.label && opt.label !== opt.value ? `${opt.label} (${opt.value})` : opt.value}
                    </button>
                  ))}
                </span>
              ) : null}
            </div>
          )}
        </div>
      ) : null}
      {activePivotByRegion && regionSeriesKeys.length > 0 ? (
        <div className={catalogSeriesSummaryClass}>
          <span className="font-semibold text-slate-800">V grafu: </span>
          {regionSeriesKeys.length} krajů
          {regionPivotIndicator && (
            <span className="text-blue-600 max-md:hidden"> · ukazatel: {
              displayIndicators.find((i) => i.id === regionPivotIndicator)?.name || regionPivotIndicator
            }</span>
          )}
          {regionSeriesKeys.length <= 5 ? (
            <span className="text-slate-600 max-md:hidden"> ({regionSeriesKeys.join(", ")})</span>
          ) : (
            <span className="text-slate-600 max-md:hidden"> ({regionSeriesKeys.slice(0, 4).join(", ")}… +{regionSeriesKeys.length - 4})</span>
          )}
        </div>
      ) : geoSeriesActive ? (
        <div className={catalogSeriesSummaryClass}>
          <span className="font-semibold text-slate-800">V grafu: </span>
          {geoSeriesRendered.length} země
          {geoChartTruncated ? (
            <span className="text-amber-800/90 max-md:hidden">
              {" "}
              (zobrazeno max. {MAX_GEO_SELECTION} z {geoCodesWithData.length || selectedGeo.length} — upravte výběr přes „Přidat / odebrat země“)
            </span>
          ) : null}
          {geoSeriesRendered.length <= 6 ? (
            <span className="text-slate-600 max-md:hidden">
              {" "}
              ({geoSeriesRendered.join(", ")})
            </span>
          ) : (
            <span className="text-slate-600 max-md:hidden">
              {" "}
              ({geoSeriesRendered.slice(0, 5).join(", ")}
              … +{geoSeriesRendered.length - 5})
            </span>
          )}
          {geoSeriesMissingData.length > 0 && !loading ? (
            <span className="block text-amber-800/90 mt-0.5 max-md:hidden">
              {geoSeriesMissingData.map((code) => geoLabelByCode[code] || code).join(", ")}
              {" "}
              — v načteném náhledu chybí hodnoty (zkuste znovu načíst nebo ověřte dostupnost u zdroje).
            </span>
          ) : null}
        </div>
      ) : seriesKeys.length > 1 ? (
        <div className={catalogSeriesSummaryClass}>
          <span className="font-semibold text-slate-800">V grafu: </span>
          {seriesKeys.length} {groupLabel.toLowerCase()}
          {seriesKeys.length <= 6 ? (
            <span className="text-slate-600 max-md:hidden"> ({seriesKeys.join(", ")})</span>
          ) : (
            <span className="text-slate-600 max-md:hidden"> ({seriesKeys.slice(0, 5).join(", ")}… +{seriesKeys.length - 5})</span>
          )}
        </div>
      ) : selectedGeo.length === 1 && !catalogCountryCtx ? (
        <div className={catalogSeriesSummaryClass}>
          <span className="font-semibold text-slate-800">V grafu: </span>
          1 země ({selectedGeo[0]})
        </div>
      ) : null}
      {userChoiceDimensions.length > 0 && onDimensionFiltersApply && !useEurostatCascadePicker && !catalogUnifiedToolbar ? (
        <div className="px-5 py-3 border-b border-border/60 bg-emerald-50/40 space-y-2">
          <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-emerald-800">
            {userChoiceDimensionsSectionTitle(userChoiceDimensions)}
          </div>
          {userChoiceDimensions.map((dim) => (
            <label key={`user-choice-${dim.field}`} className="block space-y-1">
              {userChoiceDimensions.length > 1 ? (
                <span className="text-[11px] font-medium text-slate-700">{dim.label}</span>
              ) : null}
              <select
                value={dim.selected || dim.options[0]?.value || ""}
                onChange={(e) => applyUserChoiceDimensionChange(dim.field, e.target.value)}
                className="h-8 w-full max-w-md text-[12px] border border-border/70 rounded-md px-2 bg-white"
              >
                {dim.options.map((opt) => (
                  <option key={`${dim.field}-${opt.value}`} value={opt.value}>
                    {formatUserChoiceOptionLabel(opt)}
                  </option>
                ))}
              </select>
            </label>
          ))}
          <p className="text-[10px] text-slate-600 leading-snug">
            {userChoiceDimensionsHelpText(userChoiceDimensions)}
          </p>
        </div>
      ) : null}
      <div
        className={`${catalogDisplayModeClass}${
          showMobileMergedDisplayRow || showMobileGeoAndDisplayRow ? " hidden md:flex" : ""
        }${catalogCompactPreviewChrome ? " hidden" : ""}`}
      >
        <span className={`${catalogMobileChartFirst ? "hidden md:inline" : ""} text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-600`}>
          Zobrazení:
        </span>
        {renderCatalogDisplayModeControls()}
        {filteredExtraDims.length > 0 && !useEurostatCascadePicker ? (
          <button
            type="button"
            onClick={() => setShowAdvancedFilters((v) => !v)}
            className={`${catalogDisplayButtonBase} ${catalogMobileChartFirst ? "ml-0 md:ml-auto" : "ml-auto"} border-border/70 bg-white text-slate-600 hover:bg-slate-50`}
          >
            <span className="md:hidden">{showAdvancedFilters ? "Skrýt" : `Filtry (${filteredExtraDims.length})`}</span>
            <span className="hidden md:inline">
              {showAdvancedFilters ? "Skrýt pokročilé filtry" : `Pokročilé filtry (${filteredExtraDims.length})`}
            </span>
          </button>
        ) : null}
      </div>
      {useEurostatCascadePicker && !needsFilterPanel ? (
        <details className="px-5 py-2 border-b border-border/60 bg-slate-50/80 group">
          <summary className="cursor-pointer list-none text-[11px] font-medium text-slate-700 py-1">
            Upravit dimenze datasetu
          </summary>
          <div className="pb-3 pt-1">
            <EurostatCascadingDimensionPicker
              datasetId={previewDatasetId}
              selectedDimensions={buildDimensionFiltersWithCurrentGeo()}
              userQuery={preview?.user_query || preview?.metadata?.user_query || ""}
              geoIntent={preview?.geo_intent || preview?.metadata?.geo_intent || null}
              disabled={loading}
              onApply={(next) => {
                onDimensionFiltersApply?.(finalizeDimensionFilters(buildDimensionFiltersWithCurrentGeo(next)));
              }}
            />
          </div>
        </details>
      ) : null}
      {useEurostatCascadePicker && !needsFilterPanel && displayMode === "time_series" && filteredExtraDims.length > 0 ? (
        <div className="px-5 py-2 border-b border-border/60 bg-blue-50/50 flex items-center gap-2 flex-wrap">
          <label className="flex items-center gap-1.5">
            <span className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide">Série:</span>
            <select
              value={seriesGroupDim}
              onChange={(e) => { setSeriesGroupDim(e.target.value); setSeriesSelection([]); }}
              className="h-7 text-[11px] border border-border/70 rounded-md px-2 bg-white"
            >
              <option value="">— jedna řada —</option>
              {filteredExtraDims.map((dim) => (
                <option key={dim.field} value={dim.field}>
                  {getUserChoiceDimensionLabel(dim.field, availableDimensions) ||
                    getDimensionLabel(dim.field, availableDimensions)}
                </option>
              ))}
            </select>
          </label>
          {seriesGroupDim && allSeriesForDim.length > 0 ? (
            <div className="w-full border-t border-blue-100 mt-1 pt-1.5">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide">
                  Řady ({seriesSelection.length > 0 ? seriesSelection.length : Math.min(12, allSeriesForDim.length)}/{allSeriesForDim.length}):
                </span>
                <button
                  type="button"
                  onClick={() => setSeriesSelection([...allSeriesForDim])}
                  className="text-[10px] text-blue-600 hover:underline"
                >Vše</button>
              </div>
              <div className="flex flex-wrap gap-1 max-h-24 overflow-y-auto">
                {allSeriesForDim.map((id) => {
                  const checked = seriesSelection.length === 0
                    ? allSeriesForDim.indexOf(id) < 12
                    : seriesSelection.includes(id);
                  return (
                    <label key={id} className="flex items-center gap-0.5 cursor-pointer bg-white rounded px-1.5 py-0.5 border border-border/60 text-[10px]">
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={(ev) => {
                          const effective = seriesSelection.length === 0 ? allSeriesForDim.slice(0, 12) : [...seriesSelection];
                          if (ev.target.checked) setSeriesSelection([...effective, id]);
                          else setSeriesSelection(effective.filter((x) => x !== id));
                        }}
                        className="w-3 h-3"
                      />
                      <span className="max-w-[120px] truncate" title={id}>
                        {resolveDimensionValueLabel(seriesGroupDim, id, availableDimensions) || id}
                      </span>
                    </label>
                  );
                })}
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
      {showAdvancedFilters && filteredExtraDims.length > 0 && !useEurostatCascadePicker ? (
        <div className="px-5 py-2 border-b border-border/60 bg-blue-50/50 flex items-center gap-3 flex-wrap">
          <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-blue-600">
            Filtr dimenzí:
          </span>
          {displayMode === "time_series" ? (
            <label className="flex items-center gap-1.5 border-r border-blue-200 pr-3">
              <span className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide">Série:</span>
              <select
                value={seriesGroupDim}
                onChange={(e) => { setSeriesGroupDim(e.target.value); setSeriesSelection([]); }}
                className="h-7 text-[11px] border border-border/70 rounded-md px-2 bg-white"
              >
                <option value="">— jedna řada —</option>
                {filteredExtraDims.map((dim) => (
                  <option key={dim.field} value={dim.field}>
                    {getUserChoiceDimensionLabel(dim.field, availableDimensions) ||
                      getDimensionLabel(dim.field, availableDimensions)}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
          {seriesGroupDim && displayMode === "time_series" && allSeriesForDim.length > 0 ? (
            <div className="w-full border-t border-blue-100 mt-1 pt-1.5">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-[10px] font-semibold text-blue-700 uppercase tracking-wide">
                  Řady ({seriesSelection.length > 0 ? seriesSelection.length : Math.min(12, allSeriesForDim.length)}/{allSeriesForDim.length}):
                </span>
                <button
                  type="button"
                  onClick={() => setSeriesSelection([...allSeriesForDim])}
                  className="text-[10px] text-blue-600 hover:underline"
                >Vše</button>
              </div>
              <div className="flex flex-wrap gap-1 max-h-24 overflow-y-auto">
                {allSeriesForDim.map((id) => {
                  const checked = seriesSelection.length === 0
                    ? allSeriesForDim.indexOf(id) < 12
                    : seriesSelection.includes(id);
                  return (
                    <label key={id} className="flex items-center gap-0.5 cursor-pointer bg-white rounded px-1.5 py-0.5 border border-border/60 text-[10px]">
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={(ev) => {
                          const effective = seriesSelection.length === 0 ? allSeriesForDim.slice(0, 12) : [...seriesSelection];
                          if (ev.target.checked) setSeriesSelection([...effective, id]);
                          else setSeriesSelection(effective.filter((x) => x !== id));
                        }}
                        className="w-3 h-3"
                      />
                      <span className="max-w-[120px] truncate" title={id}>{id}</span>
                    </label>
                  );
                })}
              </div>
            </div>
          ) : null}
          {filteredExtraDims.filter((dim) => dim.field !== seriesGroupDim || displayMode !== "time_series").map((dim) => (
            <label key={dim.field} className="flex items-center gap-1.5">
              <span className="text-[10px] text-slate-600">
                {getUserChoiceDimensionLabel(dim.field, availableDimensions) ||
                  getDimensionLabel(dim.field, availableDimensions)}
                :
              </span>
              <select
                value={dimFilters[dim.field] || ""}
                onChange={(e) => applyAdvancedDimFilterChange(dim.field, e.target.value)}
                className="h-7 text-[11px] border border-border/70 rounded-md px-2 bg-white max-w-[280px]"
              >
                <option value="">— vše ({dim.values.length}) —</option>
                {dim.values.map((v) => (
                  <option key={v} value={v}>
                    {resolveDimensionValueLabel(dim.field, v, availableDimensions)}
                  </option>
                ))}
              </select>
            </label>
          ))}
          {canPivotByRegion && (
            <span className="flex items-center gap-1.5 ml-2 border-l border-blue-200 pl-3 flex-wrap">
              <span className="text-[10px] font-mono text-slate-600">Série:</span>
              <button
                type="button"
                onClick={() => setPivotMode(null)}
                className={`h-6 px-2 text-[10px] rounded border font-mono ${
                  !activePivotByRegion
                    ? "bg-blue-600 text-white border-blue-600"
                    : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                }`}
              >
                Ukazatele
              </button>
              <button
                type="button"
                onClick={() => setPivotMode("region")}
                className={`h-6 px-2 text-[10px] rounded border font-mono ${
                  activePivotByRegion
                    ? "bg-blue-600 text-white border-blue-600"
                    : "bg-white text-slate-600 border-border/70 hover:bg-slate-50"
                }`}
              >
                Kraje ({regionExtraDim?.values?.length ?? "?"})
              </button>
              {activePivotByRegion && indicators.length > 1 && (
                <select
                  value={regionPivotIndicator || ""}
                  onChange={(e) => onIndicatorChange?.(e.target.value)}
                  className="h-6 text-[10px] border border-border/70 rounded px-1.5 bg-white max-w-[180px]"
                  title="Vyberte indikátor pro zobrazení po krajích"
                >
                  {displayIndicators.map((ind) => (
                    <option key={ind.id} value={ind.id}>
                      {formatIndicatorSelectLabel(ind)}
                    </option>
                  ))}
                </select>
              )}
            </span>
          )}
          {Object.values(dimFilters).some((v) => v) && (
            <button
              type="button"
              onClick={() => {
                setDimFilters({});
                if (onDimensionFiltersApply) {
                  const geoOnly = {};
                  const currentGeo = normalizeFilterValues(selectedGeo, { geo: true });
                  if (currentGeo.length && geoDimensionKey) {
                    geoOnly[geoDimensionKey] = currentGeo;
                  } else {
                    for (const [k, v] of Object.entries(requestedFilters || {})) {
                      if (isGeoDimensionField(k)) geoOnly[k] = v;
                    }
                  }
                  onDimensionFiltersApply(finalizeDimensionFilters(geoOnly));
                }
              }}
              className="text-[10px] text-blue-600 hover:text-blue-800 underline font-mono"
            >
              Zrušit filtry
            </button>
          )}
        </div>
      ) : null}
        </>
      )}
      {needsFilterPanel ? (
        <div className="px-5 py-4 border-b border-amber-200 bg-amber-50/70 space-y-3">
          {useEurostatCascadePicker ? (
            <EurostatCascadingDimensionPicker
              datasetId={previewDatasetId}
              selectedDimensions={buildDimensionFiltersWithCurrentGeo()}
              userQuery={preview?.user_query || preview?.metadata?.user_query || ""}
              geoIntent={preview?.geo_intent || preview?.metadata?.geo_intent || null}
              disabled={loading}
              onApply={(next) => {
                onDimensionFiltersApply?.(finalizeDimensionFilters(buildDimensionFiltersWithCurrentGeo(next)));
              }}
            />
          ) : (
            <>
              <div>
                <div className="text-sm font-semibold text-amber-950">Dataset potřebuje doplnit filtry</div>
                {missingFilters.length ? (
                  <div className="mt-1 text-[11px] text-amber-900 font-mono">
                    Chybí: {missingFilters.map((dim) => getDimensionLabel(dim, availableDimensions)).join(", ")}
                  </div>
                ) : null}
              </div>
              {filterWarnings.length ? (
                <div className="space-y-1">
                  {filterWarnings.map((w, idx) => (
                    <div key={`${w}-${idx}`} className="text-[12px] text-amber-900 bg-white/65 border border-amber-200 rounded-md px-2 py-1">
                      {w}
                    </div>
                  ))}
                </div>
              ) : null}
              {dimensionPanelItems.length ? (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  {dimensionPanelItems.map((item) => (
                    <div key={item.key} className="rounded-lg border border-amber-200 bg-white p-3 space-y-2">
                      <div className="text-[11px] font-semibold text-slate-800">
                        {item.label}
                        <span className="ml-1 text-[10px] font-mono text-slate-500">({item.key})</span>
                      </div>
                      {item.requiredWithoutValues ? (
                        <div className="text-[12px] text-amber-900 leading-snug">
                          Tento filtr je povinný, ale backend neposlal hodnoty. Nelze načíst náhled bez doplnění metadata values.
                        </div>
                      ) : item.isGeo || item.options.length > 8 ? (
                        <div className="max-h-[132px] overflow-auto border border-border/60 rounded-md p-2 space-y-1">
                          {item.options.map((opt) => {
                            const checked = item.selected.includes(opt.value);
                            return (
                              <label key={opt.value} className="flex items-center gap-2 text-[11px] text-slate-700">
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  onChange={(e) => {
                                    const nextSet = new Set(item.selected);
                                    if (e.target.checked) nextSet.add(opt.value);
                                    else nextSet.delete(opt.value);
                                    const next = Array.from(nextSet);
                                    setSelectedDimensionFilters((prev) => ({ ...prev, [item.key]: next }));
                                    if (item.isGeo) setSelectedGeo(next);
                                  }}
                                  className="h-3.5 w-3.5"
                                />
                                <span>{formatOptionLabel(opt)}</span>
                              </label>
                            );
                          })}
                        </div>
                      ) : (
                        <select
                          value={item.selected[0] || ""}
                          onChange={(e) => {
                            const next = e.target.value ? [e.target.value] : [];
                            setSelectedDimensionFilters((prev) => ({ ...prev, [item.key]: next }));
                          }}
                          className="h-8 w-full text-[12px] border border-border/70 rounded-md px-2 bg-white"
                        >
                          <option value="">Vyberte hodnotu</option>
                          {item.options.map((opt) => (
                            <option key={opt.value} value={opt.value}>
                              {formatOptionLabel(opt)}
                            </option>
                          ))}
                        </select>
                      )}
                    </div>
                  ))}
                </div>
              ) : null}
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={applyDimensionFilters}
                  disabled={!onDimensionFiltersApply}
                  className="h-8 px-3 text-xs rounded-md bg-blue-600 text-white disabled:opacity-50"
                >
                  Načíst náhled s vybranými filtry
                </button>
                <details className="text-[11px] text-slate-600 font-mono">
                  <summary className="cursor-pointer text-slate-700">Technické detaily</summary>
                  <pre className="mt-2 max-h-52 overflow-auto rounded-md bg-white border border-border/60 p-2 whitespace-pre-wrap">
                    {JSON.stringify({
                      dataset_id: preview?.dataset_id || diagnostic?.dataset_id,
                      request_id: preview?.request_id || diagnostic?.request_id,
                      dropped_filters: droppedFilters,
                      estimated_points: preview?.estimated_points || diagnostic?.estimated_points,
                      available_dimensions: availableDimensions,
                      upstream_body_preview: preview?.upstream_body_preview || diagnostic?.upstream_body_preview,
                    }, null, 2)}
                  </pre>
                </details>
              </div>
            </>
          )}
        </div>
      ) : null}
      {loading ? (
        <DataLoadIndicator compact={compact} />
      ) : preview?.error ? (
        <div className="p-5 text-sm text-red-700 bg-red-50 whitespace-pre-wrap">
          {previewText(preview.error, "Nahled dat se nepodarilo nacist.")}
        </div>
      ) : rows.length === 0 ? (
        <div className="p-6 text-sm text-slate-600 font-mono space-y-3">
          <div>
            {needsFilterPanel
              ? "Vyberte filtry výše a načtěte náhled."
              : previewText(preview?.message) ||
                (liveCatalogPreview
                  ? "Nepodařilo se načíst data z API katalogu. Zkuste obnovit stránku nebo upravit filtry země."
                  : "Pro tento zdroj zatím nejsou uložená data. Spusťte synchronizaci.")}
          </div>
          {!needsFilterPanel && diagnostic ? (
            <div className="rounded-lg border border-amber-200 bg-amber-50/70 p-3 text-[12px] leading-relaxed space-y-1">
              {(preview?.dataset_id || diagnostic?.dataset_id) ? (
                <div><span className="font-semibold">dataset_id:</span> {preview?.dataset_id || diagnostic?.dataset_id}</div>
              ) : null}
              {(preview?.request_id || diagnostic?.request_id) ? (
                <div><span className="font-semibold">request_id:</span> {preview?.request_id || diagnostic?.request_id}</div>
              ) : null}
              {preview?.status === "needs_filters" && Array.isArray(preview?.missing_filters) && preview.missing_filters.length ? (
                <div><span className="font-semibold">missing filters:</span> {preview.missing_filters.join(", ")}</div>
              ) : null}
              {diagnostic?.error_code ? (
                <div><span className="font-semibold">error:</span> {diagnostic.error_code}</div>
              ) : null}
              {diagnostic?.upstream_status ? (
                <div><span className="font-semibold">upstream_status:</span> {String(diagnostic.upstream_status)}</div>
              ) : null}
              {(preview?.requested_filters && Object.keys(preview.requested_filters).length > 0) ||
              (diagnostic?.requested_filters && Object.keys(diagnostic.requested_filters).length > 0) ? (
                <div>
                  <span className="font-semibold">requested filters:</span>{" "}
                  {JSON.stringify(preview?.requested_filters || diagnostic?.requested_filters)}
                </div>
              ) : null}
              {(preview?.dropped_filters && Object.keys(preview.dropped_filters).length > 0) ||
              (diagnostic?.dropped_filters && Object.keys(diagnostic.dropped_filters).length > 0) ? (
                <div>
                  <span className="font-semibold">dropped filters:</span>{" "}
                  {JSON.stringify(preview?.dropped_filters || diagnostic?.dropped_filters)}
                </div>
              ) : null}
              {Array.isArray(preview?.warnings) && preview.warnings.length ? (
                <div>
                  <span className="font-semibold">warnings:</span> {preview.warnings.join(" | ")}
                </div>
              ) : null}
              {(preview?.available_dimensions && Object.keys(preview.available_dimensions).length > 0) ||
              (diagnostic?.available_dimensions && Object.keys(diagnostic.available_dimensions).length > 0) ? (
                <div>
                  <span className="font-semibold">available dimensions:</span>{" "}
                  {JSON.stringify(preview?.available_dimensions || diagnostic?.available_dimensions)}
                </div>
              ) : null}
              {(preview?.upstream_body_preview || diagnostic?.upstream_body_preview) ? (
                <div className="break-all">
                  <span className="font-semibold">upstream detail:</span>{" "}
                  {typeof (preview?.upstream_body_preview || diagnostic?.upstream_body_preview) === "string"
                    ? (preview?.upstream_body_preview || diagnostic?.upstream_body_preview)
                    : JSON.stringify(preview?.upstream_body_preview || diagnostic?.upstream_body_preview)}
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : (
        <div
          className={
            catalogDetailSplitLayout
              ? "catalog-detail-split grid grid-cols-1 md:grid-cols-[minmax(0,1.35fr)_minmax(11rem,0.68fr)] gap-0 min-h-[min(72svh,38rem)] md:min-h-[min(52vh,32rem)]"
              : `grid grid-cols-1 ${compact ? "" : "xl:grid-cols-2"} gap-0${
                  isFullscreenChartPreview
                    ? " max-md:flex max-md:flex-col max-md:flex-1 max-md:min-h-0"
                    : ""
                }`
          }
        >
          <div
            className={
              catalogDetailSplitLayout
                ? "min-w-0 p-2 md:p-4 min-h-[min(68svh,36rem)] md:min-h-0 md:h-full flex flex-col overflow-hidden border-b md:border-b-0 md:border-r border-border/60"
                : compact
                  ? isFullscreenChartPreview
                    ? "p-2 md:p-4 flex flex-col flex-1 min-h-0 overflow-hidden border-b border-border/60"
                    : isCatalogInlineSearchPreview
                      ? "p-0 min-w-0 flex flex-col min-h-[min(50vh,400px)] flex-1"
                      : "p-4 border-b border-border/60"
                  : "p-4 border-b xl:border-b-0 xl:border-r border-border/60"
            }
          >
            {catalogChartActions?.show && !isFullscreenChartPreview ? (
              <div
                className={`${
                  isCatalogDetailPreview
                    ? "mb-2 flex flex-wrap items-center gap-1.5 border-b border-border/40 pb-2"
                    : "mb-3 space-y-2 border-b border-border/50 pb-3"
                }`}
              >
                <div className="flex flex-wrap items-center gap-1.5">
                  {catalogChartActions.canSync &&
                  !catalogChartActions.canAddToDashboard &&
                  !isCatalogDetailPreview ? (
                    <button
                      type="button"
                      onClick={catalogChartActions.onSync}
                      disabled={
                        catalogChartActions.syncing
                        || catalogChartActions.loading
                        || chartBlocked
                      }
                      className="inline-flex items-center gap-1.5 h-7 px-2.5 text-[11px] rounded-lg border border-border/70 bg-card hover:bg-muted/50 disabled:opacity-50"
                      title={catalogChartActions.syncTitle || "Uložit zdroj do systému a stáhnout data"}
                    >
                      {catalogChartActions.syncing ? (
                        <RefreshCw className="h-3 w-3 animate-spin" />
                      ) : (
                        <Play className="h-3 w-3" />
                      )}
                      Synchronizovat
                    </button>
                  ) : null}
                  {catalogChartActions.canAddToDashboard ? (
                    <button
                      type="button"
                      onClick={() => catalogChartActions.onAddToDashboard?.({ seriesGroupDim, seriesSelection })}
                      disabled={
                        catalogChartActions.addingToDash ||
                        catalogChartActions.dashboardLoading ||
                        catalogChartActions.loading ||
                        catalogChartActions.previewError ||
                        !catalogChartActions.hasPreviewData ||
                        chartBlocked
                      }
                      className="inline-flex items-center gap-1.5 h-7 px-3 text-[11px] font-semibold rounded-lg border border-transparent bg-[hsl(var(--primary))] text-[hsl(var(--primary-foreground))] shadow-sm hover:bg-[hsl(var(--primary-deep))] disabled:opacity-50"
                      title={
                        catalogChartActions.dashboardTitle
                        || "Přidat na osobní dashboard — načte se celá časová řada z API"
                      }
                    >
                      {catalogChartActions.addingToDash ? (
                        <RefreshCw className="h-3 w-3 animate-spin" />
                      ) : (
                        <Sparkles className="h-3 w-3" />
                      )}
                      Na můj dashboard
                    </button>
                  ) : null}
                  {catalogChartActions.share?.setId ? (
                    <CatalogChartShareButtons
                      catalogId={catalogChartActions.share.catalogId}
                      sourceType={catalogChartActions.share.sourceType}
                      setId={catalogChartActions.share.setId}
                      title={catalogChartActions.share.title}
                      indicatorId={catalogChartActions.share.indicatorId}
                      disabled={
                        catalogChartActions.loading ||
                        catalogChartActions.previewError ||
                        !catalogChartActions.hasPreviewData ||
                        chartBlocked
                      }
                    />
                  ) : null}
                </div>
                {!isCatalogDetailPreview ? (
                  <p className="text-[10px] text-slate-500 leading-snug">
                    {catalogChartActions.hint
                      || "Náhled v tabulce může být zkrácený; na dashboardu se načte celá dostupná historie řady."}
                  </p>
                ) : null}
              </div>
            ) : null}
            {useCatalogAradChartSlot ? (
              <div
                key={`catalog-chart-slot-${catalogChartSize || "default"}-${forceCrossSection ? "latest" : "series"}`}
                className={`${catalogChartShellClass(catalogChartSize, { split: catalogDetailSplitLayout })} flex flex-col min-h-0 overflow-hidden`}
                data-testid="catalog-chart-slot"
              >
                {catalogAradChartSlot}
              </div>
            ) : showTimeSeriesChart ? (
              <div
                key={`catalog-chart-${catalogChartSize || "default"}`}
                className={catalogChartShellClass(catalogChartSize, { split: catalogDetailSplitLayout })}
                data-testid="catalog-time-series-chart"
              >
                {chartFrequencyLabel ? (
                  <div
                    className="absolute top-1.5 left-2 z-10 rounded-md border border-teal-200/80 bg-white/95 px-2 py-0.5 text-[10px] font-semibold text-teal-900 shadow-sm"
                    data-testid="chart-frequency-badge"
                  >
                    {chartFrequencyLabel}
                  </div>
                ) : null}
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart
                    data={chartRows}
                    margin={{
                      top: chartFrequencyLabel ? 26 : 8,
                      right: (multiLinePivot || activePivotByRegion) ? 8 : 16,
                      left: chartYAxisUnit ? 12 : 4,
                      bottom: (multiLinePivot || activePivotByRegion) ? 52 : 36,
                    }}
                  >
                    <CartesianGrid vertical={false} stroke="#D4E6F7" strokeDasharray="2 4" />
                    <XAxis
                      dataKey="x"
                      tick={{ fontSize: 10, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      angle={-25}
                      textAnchor="end"
                      height={34}
                      interval="preserveStartEnd"
                      tickFormatter={fmtPeriodAxisTick}
                      label={
                        chartFrequencyLabel
                          ? {
                              value: `${chartFrequencyLabel} období`,
                              position: "insideBottom",
                              offset: -2,
                              fontSize: 10,
                              fill: "#5878A0",
                              fontFamily: "JetBrains Mono",
                            }
                          : undefined
                      }
                    />
                    <YAxis
                      width={chartYAxisUnit ? 64 : 56}
                      tick={{ fontSize: 10, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) => fmtCompact(v)}
                      label={
                        chartYAxisUnit
                          ? {
                              value: chartYAxisUnit,
                              angle: -90,
                              position: "insideLeft",
                              offset: 4,
                              fontSize: 9,
                              fill: "#5878A0",
                              fontFamily: "JetBrains Mono",
                            }
                          : undefined
                      }
                    />
                    <Tooltip
                      {...mergeRechartsTooltipProps({
                        contentStyle: { fontSize: 11 },
                        formatter: (v, name) => {
                          let formatted;
                          if (typeof v === "number" && !Number.isNaN(v)) formatted = fmtNumber(v);
                          else {
                            const n = parseNumber(v);
                            formatted = n !== null ? fmtNumber(n) : String(v ?? "");
                          }
                          const seriesLabel = resolveChartTooltipSeriesLabel(name, {
                            indicators: displayIndicators,
                            geoLabelByCode,
                          });
                          return [formatted, seriesLabel];
                        },
                        labelFormatter: (l) =>
                          chartFrequencyLabel
                            ? `${chartFrequencyLabel} · ${fmtPeriodLabel(l)}`
                            : `Období: ${fmtPeriodLabel(l)}`,
                      })}
                    />
                    {activePivotByRegion ? (
                      <>
                        <Legend wrapperStyle={{ fontSize: 10, paddingTop: 4 }} formatter={(v) => String(v || "").replace(/^s_/, "")} />
                        {regionSeriesKeys.map((kraj, idx) => (
                          <Line
                            key={kraj}
                            type="monotone"
                            dataKey={`s_${kraj}`}
                            name={kraj}
                            stroke={seriesStrokeAt(idx)}
                            strokeWidth={1.75}
                            dot={false}
                            connectNulls
                          />
                        ))}
                      </>
                    ) : geoSeriesActive ? (
                      <>
                        {geoSeriesRendered.length <= 6 ? (
                          <Legend
                            wrapperStyle={{ fontSize: 10, paddingTop: 4 }}
                            formatter={(value) => geoLabelByCode[String(value || "")] || String(value || "")}
                          />
                        ) : null}
                        {geoSeriesRendered.map((geoCode, idx) => (
                          <Line
                            key={geoCode}
                            type="monotone"
                            dataKey={geoCode}
                            name={geoLabelByCode[geoCode] || geoCode}
                            stroke={seriesStrokeAt(idx)}
                            strokeWidth={1.75}
                            dot={geoChartUseDots ? { r: 3 } : false}
                            connectNulls
                          />
                        ))}
                      </>
                    ) : multiLinePivot ? (
                      <>
                        <Legend
                          wrapperStyle={{ fontSize: 10, paddingTop: 4 }}
                          formatter={(value) => {
                            const id = String(value || "").replace(/^s_/, "");
                            return seriesLabelForChart(displayIndicators, id);
                          }}
                        />
                        {pivotSeriesKeys.map((sid, idx) => (
                          <Line
                            key={sid}
                            type="monotone"
                            dataKey={`s_${sid}`}
                            name={
                              seriesLabelForChart(displayIndicators, sid) ||
                              geoLabelByCode[sid] ||
                              sid
                            }
                            stroke={seriesStrokeAt(idx)}
                            strokeWidth={1.75}
                            dot={false}
                            connectNulls
                          />
                        ))}
                      </>
                    ) : (
                      <Line type="monotone" dataKey="y" stroke="hsl(202 90% 42%)" strokeWidth={2} dot={false} />
                    )}
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : chartBlocked && showTimeSeriesChart ? (
              <div
                className={catalogChartShellClass(catalogChartSize, { placeholder: true })}
                data-testid="chart-all-zero-placeholder"
              >
                Graf není k dispozici — pro zvolené filtry nejsou nenulová data. Upravte filtry v panelu výše.
              </div>
            ) : forceCrossSection && crossSectionFetching ? (
              <div className={catalogChartShellClass(catalogChartSize, { placeholder: true })} data-testid="cross-section-loading">
                <RefreshCw className="h-5 w-5 animate-spin text-slate-400" />
                <span className="mt-2 text-xs text-slate-500">Načítám všechna data…</span>
              </div>
            ) : !useCatalogAradChartSlot && crossSectionRows.length >= 2 ? (
              <div
                key={`catalog-chart-bars-${catalogChartSize || "default"}`}
                className={catalogChartShellClass(catalogChartSize)}
                data-testid="catalog-latest-values-chart"
              >
                {chartFrequencyLabel ? (
                  <div
                    className="absolute top-1.5 left-2 z-10 rounded-md border border-teal-200/80 bg-white/95 px-2 py-0.5 text-[10px] font-semibold text-teal-900 shadow-sm"
                    data-testid="chart-frequency-badge"
                  >
                    {chartFrequencyLabel}
                    {crossSection?.latestPeriod ? ` · ${fmtPeriodLabel(crossSection.latestPeriod)}` : ""}
                  </div>
                ) : null}
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={crossSectionBarData}
                    margin={{ top: chartFrequencyLabel ? 30 : 22, right: 8, left: 4, bottom: 34 }}
                  >
                    <CartesianGrid vertical={false} stroke="#D4E6F7" strokeDasharray="2 4" />
                    <XAxis
                      dataKey="x"
                      tick={{ fontSize: 9, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      angle={-35}
                      textAnchor="end"
                      height={40}
                      interval={0}
                    />
                    <YAxis
                      width={52}
                      tick={{ fontSize: 10, fill: "#5878A0", fontFamily: "JetBrains Mono" }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) => fmtCompact(v)}
                      domain={crossSectionBarAxis.domain}
                      ticks={crossSectionBarAxis.axis.ticks}
                      allowDataOverflow={false}
                      niceTicks="none"
                    />
                    <Tooltip
                      {...mergeRechartsTooltipProps({
                        contentStyle: { fontSize: 11 },
                        formatter: (v) => {
                          const n = chartBarPointValue(v);
                          return n != null ? fmtCompact(n) : String(v ?? "");
                        },
                      })}
                    />
                    <Bar dataKey="y" radius={[2, 2, 0, 0]} minPointSize={0}>
                      <LabelList
                        dataKey="y"
                        position="top"
                        offset={4}
                        formatter={(v) => {
                          const n = chartBarPointValue(v);
                          return n != null ? fmtCompact(n) : "";
                        }}
                        style={{
                          fill: "#5878A0",
                          fontFamily: "JetBrains Mono",
                          fontSize: 9,
                          fontWeight: 600,
                        }}
                      />
                      {crossSectionRows.map((_, idx) => (
                        <Cell key={idx} fill={MULTI_LINE_STROKES[idx % MULTI_LINE_STROKES.length]} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <div
                className={`${catalogChartShellClass(catalogChartSize, { placeholder: true })} grid place-items-center text-sm text-slate-500 font-mono border border-dashed border-border rounded-xl bg-slate-50/70 px-4 text-center`}
                data-testid={
                  preferTimeSeries && geoSeriesActive && sparseGeoCompare
                    ? "catalog-time-series-history-missing"
                    : undefined
                }
              >
                {preferTimeSeries && geoSeriesActive && sparseGeoCompare
                  ? "Pro časovou řadu je načtené jen poslední období. Obnovte výběr zemí nebo přepněte na Srovnání hodnot."
                  : noRowsAfterDimFilters
                  ? "Aktivní filtry nevrátily žádná data. Zrušte filtry a zkuste to znovu."
                  : forceCrossSection
                    ? "Srovnání hodnot vyžaduje alespoň 2 země nebo kategorie k porovnání - tahle řada má jen jednu. Přepněte zpět na Časovou řadu."
                    : chartFrequencyLabel
                      ? `${chartFrequencyLabel} řada — málo bodů pro graf, zobrazujeme tabulku.`
                      : "Data neobsahují časovou řadu – zobrazujeme tabulku."}
              </div>
            )}
            {(forceCrossSection || chartRows.length < 2) && crossSectionCandidates.length > 1 ? (
              <div className="mt-2 flex items-center gap-2">
                <span className="text-[11px] text-slate-600 font-mono">Proměnná (osa X):</span>
                <select
                  value={crossSection?.dimField || crossSectionFieldOverride || ""}
                  onChange={(e) => setCrossSectionFieldOverride(String(e.target.value || "").trim())}
                  className="h-7 text-xs border border-border/70 rounded-md px-2 bg-white"
                >
                  {crossSectionCandidates.map((f) => (
                    <option key={f} value={f}>
                      {f}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            {crossSectionRows.length >= 2 && crossSection?.availablePeriods?.length > 1 ? (
              <div className="mt-2 flex items-center gap-2">
                <span className="text-[11px] text-slate-600 font-mono">Období:</span>
                <select
                  value={crossSection.latestPeriod}
                  onChange={(e) => setCrossSectionPeriodOverride(String(e.target.value || "").trim())}
                  className="h-7 text-xs border border-border/70 rounded-md px-2 bg-white"
                >
                  {crossSection.availablePeriods.map((p) => (
                    <option key={p} value={p}>
                      {fmtPeriodLabel(p)}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            <div className={`mt-2 text-[11px] text-slate-500 font-mono ${hideCatalogChartMetaOnMobile ? "hidden md:block" : ""}`}>
              {showTimeSeriesChart
                ? geoSeriesActive
                  ? `${chartFrequencyLabel ? `${chartFrequencyLabel} · ` : ""}${
                      catalogValueDescriptorText || `${timeField} → ${valueField}`
                    } · ${selectedGeo.length} země`
                  : multiLinePivot
                  ? `${chartFrequencyLabel ? `${chartFrequencyLabel} · ` : ""}${
                      catalogValueDescriptorText || `${timeField} → ${valueField}`
                    } · ${pivotSeriesKeys.length} řad (${seriesGroupDim ? (getUserChoiceDimensionLabel(seriesGroupDim, availableDimensions) || getDimensionLabel(seriesGroupDim, availableDimensions) || seriesGroupDim) : groupLabel})`
                  : `${chartFrequencyLabel ? `${chartFrequencyLabel} · ` : ""}${
                      catalogValueDescriptorText || `${timeField} → ${valueField}`
                    }`
                : crossSectionRows.length >= 2
                  ? `Průřezová data · ${crossSectionRows.length} kategorií`
                  : `${rows.length} řádků · ${fields.length} sloupců`}
            </div>
            {!timeField && geoField && valueField ? (
              <div className={`mt-1 text-[11px] text-slate-500 font-mono ${hideCatalogChartMetaOnMobile ? "hidden md:block" : ""}`}>
                {`Srovnání podle ${geoField} · hodnota ${valueField}`}
              </div>
            ) : null}
            {preview?.metadata?.filters_applied && Object.keys(preview.metadata.filters_applied).length > 0 ? (
              <div className={`mt-1 text-[11px] text-slate-600 ${hideCatalogChartMetaOnMobile ? "hidden md:block" : ""}`}>
                Filtry: {formatAppliedFilters(
                  preview.metadata.filters_applied,
                  preview?.available_dimensions || preview?.metadata?.dimensions,
                  preview?.filter_display_labels || preview?.metadata?.filter_display_labels
                )}
              </div>
            ) : null}
            {crossSectionRows.length >= 2 && crossSection?.dimField ? (
              <div className={`mt-1 text-[11px] text-slate-500 font-mono ${hideCatalogChartMetaOnMobile ? "hidden md:block" : ""}`}>
                {`Osa X: ${crossSection.dimField}`}
                {crossSection?.latestPeriod && !(crossSection?.availablePeriods?.length > 1) ? ` · období ${fmtPeriodLabel(crossSection.latestPeriod)}` : ""}
              </div>
            ) : null}
            {showTimeSeriesChart ? (
              <div className={hideCatalogChartMetaOnMobile ? "hidden md:block" : ""}>
                <MySeriesInlineActions savePayload={mySeriesPayload} compareLeft={compareLeft} compact={compact} />
              </div>
            ) : null}
          </div>
          <div
            className={`min-w-0 overflow-auto ${
              hideCatalogTableOnMobile ? "max-md:hidden" : ""
            } ${
              catalogDetailSplitLayout
                ? "max-h-[min(44vh,24rem)] md:max-h-[min(62vh,34rem)] min-h-0 p-2 md:p-3 bg-muted/10"
                : catalogTableMaxHeightClass
            }`}
            data-testid={catalogDetailSplitLayout ? "catalog-detail-table-panel" : undefined}
          >
            <table className={`data-table ${catalogDetailSplitLayout ? "text-[10px]" : "text-xs"}`}>
              <thead className="sticky top-0 bg-white z-10">
                <tr>
                  {(catalogDetailSplitLayout ? catalogDetailTableFields : shownFields).map((f) => (
                    <th key={f} className="!px-2 !py-2">{f}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {tableRows.slice(0, catalogDetailSplitLayout ? 30 : 40).map((r, i) => (
                  <tr key={i}>
                    {(catalogDetailSplitLayout ? catalogDetailTableFields : shownFields).map((f) => {
                      const cell = r?.[f];
                      const display = formatSourceTableCell(f, cell);
                      return (
                        <td
                          key={f}
                          className={`mono !px-2 !py-1.5 max-w-[180px] truncate ${
                            catalogDetailSplitLayout ? "max-w-[7rem]" : ""
                          }`}
                          title={display}
                        >
                          {display}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
