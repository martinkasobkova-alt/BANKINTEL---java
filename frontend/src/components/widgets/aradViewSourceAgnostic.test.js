/**
 * Source-agnostic contract tests — AradView data shapes from ARAD, Eurostat, ECB, IMF/OECD, catalog.
 */

import { parseChartPeriod } from "@/lib/chartPeriodParse";
import { contractFromAradViewState, validateAradViewContract } from "@/charts/contractFromAradViewState";
import { buildClipboardTextWide } from "@/charts/chartExport";
import { inferNativeFrequencyFromChartRows } from "@/lib/chartFrequencyInfer";
import { mapCatalogPreviewRowsToArad } from "@/lib/mapCatalogPreviewToArad";
import { sanitizeExportValueRaw } from "@/charts/chartExportSanitize";

const SOURCE_FIXTURES = {
  arad: {
    title: "ROA českých bank",
    unit: "%",
    frequency: "Y",
    rows: [
      { period: "2008", value: 1.16 },
      { period: "2009", value: 1.46 },
      { period: "2010", value: 1.34 },
    ],
    widget: { engine_type: "arad_view", config: { source: "ARAD" } },
    data: { source: "ARAD · ČNB", dataset: "arad:1018" },
  },
  eurostat: {
    title: "HICP inflation",
    unit: "%",
    frequency: "M",
    rows: [
      { period: "2020-01", value: 2.1 },
      { period: "2020-02", value: 2.3 },
    ],
    widget: { engine_type: "eurostat_view" },
    data: { source: "Eurostat", dataset: "catalog:eurostat:prc_hicp" },
  },
  ecb: {
    title: "ECB rate",
    unit: "%",
    frequency: "M",
    rows: [
      { period: "2020-01", value: 0.0 },
      { period: "2020-02", value: 0.0 },
    ],
    widget: { engine_type: "ecb_view" },
    data: { source: "ECB", dataset: "catalog:ecb:interest" },
  },
  imf_oecd: {
    title: "GDP growth",
    unit: "%",
    frequency: "Q",
    rows: [
      { period: "2020-Q1", value: -3.2 },
      { period: "2020-Q2", value: -8.1 },
    ],
    widget: { engine_type: "imf_view" },
    data: { source: "IMF", dataset: "catalog:imf:ngdp" },
  },
  catalogSingle: {
    title: "Katalogová řada",
    unit: "index",
    rows: mapCatalogPreviewRowsToArad([
      { TIME_PERIOD: "2019", OBS_VALUE: 100 },
      { TIME_PERIOD: "2020", OBS_VALUE: 105 },
    ]),
    widget: { engine_type: "external_catalog_chart", config: { catalog: "csu" } },
    data: { source: "ČSÚ", dataset: "catalog:csu:demo" },
  },
  multiCountry: {
    title: "ROE comparison",
    isMultiSeries: true,
    seriesList: [
      { key: "de", name: "Německo – ROE" },
      { key: "pl", name: "Polsko – ROE" },
      { key: "cz", name: "Česko – ROE" },
    ],
    rows: [
      { period: "2020-Q1", de: 10.2, pl: 9.8, cz: 8.7 },
    ],
    widget: { engine_type: "eurostat_view" },
    data: { source: "Eurostat", dataset: "catalog:eurostat:roe", multi_series: true },
  },
};

function buildContract(fixture) {
  return contractFromAradViewState({
    title: fixture.title,
    unit: fixture.unit,
    chartKind: "line",
    latestDataMode: false,
    isMultiSeries: Boolean(fixture.isMultiSeries),
    seriesList: fixture.seriesList || [],
    chartRows: (fixture.rows || []).map((r) =>
      fixture.isMultiSeries
        ? { x: r.period, ...r }
        : { x: r.period, y: r.value }
    ),
    data: fixture.data,
    widget: fixture.widget,
    frequency: fixture.frequency || "",
    transformId: "raw",
  });
}

describe("AradView source-agnostic data contract", () => {
  for (const [sourceKey, fixture] of Object.entries(SOURCE_FIXTURES)) {
    test(`${sourceKey} produces valid export contract`, () => {
      const contract = buildContract(fixture);
      const v = validateAradViewContract(contract);
      expect(v.ok).toBe(true);
      expect(contract.data.length).toBeGreaterThan(0);
      for (const pt of contract.data) {
        expect(typeof pt.value_raw).toBe("number");
        expect(pt.period).toBeTruthy();
        expect(String(pt.value_raw)).not.toMatch(/^[IVXLC]+$/i);
      }
    });
  }

  test("wide clipboard has no technical columns for Eurostat fixture", () => {
    const contract = buildContract(SOURCE_FIXTURES.eurostat);
    const text = buildClipboardTextWide(contract, { locale: "cs-CZ" });
    expect(text.split("\n")[0]).toContain("Období");
    expect(text).not.toContain("series_id");
    expect(text).not.toContain("value_raw");
  });
});

describe("period parsing (render pipeline)", () => {
  test("annual, quarterly, monthly, daily ISO periods", () => {
    expect(parseChartPeriod("2008")?.getFullYear()).toBe(2008);
    expect(parseChartPeriod("2020-Q1")?.getFullYear()).toBe(2020);
    expect(parseChartPeriod("2020-01")?.getMonth()).toBe(0);
    expect(parseChartPeriod("2020-01-31")?.getDate()).toBe(31);
  });

  test("ARAD roman month normalizes to parseable period via contract", () => {
    const contract = contractFromAradViewState({
      title: "ARAD monthly",
      chartRows: [{ x: "2022 01.IX", y: 1.2 }],
      frequency: "M",
    });
    expect(contract.data[0].period).toBe("2022-09");
    expect(contract.data[0].value_raw).toBe(1.2);
  });
});

describe("frequency inference", () => {
  test("does not default to D for annual rows", () => {
    expect(inferNativeFrequencyFromChartRows([{ period: "2008" }, { period: "2009" }], false)).toBe("Y");
    expect(inferNativeFrequencyFromChartRows([{ period: "2008" }, { period: "2009" }], true)).toBe("Y");
  });

  test("infers Q from ISO quarter periods in multi-series rows", () => {
    expect(
      inferNativeFrequencyFromChartRows(
        [{ period: "2020-Q1", de: 1, pl: 2 }, { period: "2020-Q2", de: 1, pl: 2 }],
        true
      )
    ).toBe("Q");
  });
});

describe("value_raw sanitization", () => {
  test("rejects period labels and roman months", () => {
    expect(sanitizeExportValueRaw("01.IX")).toBeNull();
    expect(sanitizeExportValueRaw("2020-Q1")).toBeNull();
    expect(sanitizeExportValueRaw("2008")).toBeNull();
    expect(sanitizeExportValueRaw(1.16)).toBe(1.16);
  });
});
