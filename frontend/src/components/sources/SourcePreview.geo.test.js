import React from "react";
import { act } from "react";
import { createRoot } from "react-dom/client";
import { vi } from "vitest";
import {
  buildGeoChartRows,
  capGeoList,
  buildGeoOptionsFromRows,
  collectGeoOptions,
  detectGeoKeys,
  geoCodesWithChartData,
  geoSeriesNeedsPointMarkers,
  isGeoCrossSectionSnapshot,
  isSparseGeoCompare,
  isGeoDimensionKeyCandidate,
  mergeSelectedGeoRows,
  readGeoDimensionOptions,
  readGeoOptionsFromGeoDimensions,
  readGeoOptionsFromSelectableDimensions,
} from "../../lib/sourcePreviewGeo";
import SourcePreview from "./SourcePreview";

vi.mock("recharts", async (importOriginal) => {
  const actual = await importOriginal();
  const ReactLib = await import("react");
  const Div = ({ children }) => ReactLib.createElement("div", null, children);
  return {
    ...actual,
    Bar: Div,
    BarChart: Div,
    CartesianGrid: Div,
    Cell: Div,
    LabelList: Div,
    Legend: Div,
    Line: Div,
    LineChart: Div,
    ResponsiveContainer: Div,
    Tooltip: Div,
    XAxis: Div,
    YAxis: Div,
  };
});

vi.mock("@/components/catalog/CatalogChartShareButtons", async () => {
  const ReactLib = await import("react");
  return { default: () => ReactLib.createElement("div") };
});

vi.mock("@/components/myDashboard/MySeriesInlineActions", async () => {
  const ReactLib = await import("react");
  return { default: () => ReactLib.createElement("div") };
});

vi.mock("@/lib/mySeriesFromWidget", () => ({
  buildCompareLeftRefFromWidget: () => null,
  buildMySeriesSavePayloadFromWidget: () => null,
}));

vi.mock("@/components/ui/DataLoadIndicator", async () => {
  const ReactLib = await import("react");
  return { DataLoadIndicator: () => ReactLib.createElement("div", null, "loading") };
});

vi.mock("@/lib/format", async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    fmtCompact: (v) => String(v),
    fmtNumber: (v) => String(v),
    fmtPeriod: (v) => String(v),
    fmtPeriodAxisTick: (v) => String(v),
    fmtPeriodLabel: (v) => String(v),
  };
});

vi.mock("@/lib/rechartsTooltipShared", async (importOriginal) => ({
  ...(await importOriginal()),
  mergeRechartsTooltipProps: (x) => x,
}));

// Živě zjištěno: rychlé ODVĚTVÍ menu teď na Eurostat datasetech samo dotahuje živou dostupnost
// (viz applyLiveAvailabilityToDimensionOptions v SourcePreview.jsx) - beze mocku by šel skutečný
// síťový POST. Výchozí prázdná odpověď nic neoznačí (žádný test kromě toho níže se na to nedívá);
// konkrétní test si chování přepíše sám.
const apiMocks = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("@/lib/api", () => ({
  __esModule: true,
  default: { post: apiMocks.post },
}));

function flushMicrotasks() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function renderPreview(preview, props = {}) {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => {
    root.render(React.createElement(SourcePreview, { preview, ...props }));
    await flushMicrotasks();
  });
  return { container, root };
}

describe("SourcePreview geo helpers", () => {
  test("readGeoDimensionOptions uses metadata country names", () => {
    const opts = readGeoDimensionOptions(
      {
        REF_AREA: [
          { id: "GAB", name: "Gabon" },
          { id: "AGO", name: "Angola" },
        ],
      },
      "REF_AREA",
    );
    expect(opts.map(({ value, label, rowCount }) => ({ value, label, rowCount }))).toEqual([
      { value: "AGO", label: "Angola", rowCount: 0 },
      { value: "GAB", label: "Gabon", rowCount: 0 },
    ]);
  });

  test("keeps ČSÚ textual kraj names from metadata (not just short codes)", () => {
    // Dřív isPlausibleGeoCode textové názvy krajů zahodil → krajský picker u ČSÚ zůstal prázdný,
    // i když sada kraje má. Teď je metadata cesta pustí (kódy i názvy).
    const fromDims = readGeoDimensionOptions(
      {
        "Území-Kraj": [
          { id: "Středočeský kraj", name: "Středočeský kraj" },
          { id: "Hlavní město Praha", name: "Hlavní město Praha" },
        ],
      },
      "Území-Kraj",
    );
    expect(fromDims.map((o) => o.label)).toEqual(["Hlavní město Praha", "Středočeský kraj"]);

    const fromSelectable = readGeoOptionsFromSelectableDimensions([
      {
        field: "Území-Kraj",
        value_labels: {
          "Jihomoravský kraj": "Jihomoravský kraj",
          "Moravskoslezský kraj": "Moravskoslezský kraj",
        },
      },
    ]);
    expect(fromSelectable.map((o) => o.label)).toEqual(["Jihomoravský kraj", "Moravskoslezský kraj"]);
  });

  test("detects one GEO country and formats active summary option", () => {
    const rows = [
      { TIME_PERIOD: "2024", GEO: "HU", GEO_LABEL: "Hungary", value: 6.1 },
      { TIME_PERIOD: "2025", GEO: "HU", GEO_LABEL: "Hungary", value: 5.2 },
    ];
    const fields = ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"];
    const { geoKey, geoLabelKey } = detectGeoKeys(fields, rows);
    const opts = buildGeoOptionsFromRows(rows, geoKey, geoLabelKey);

    expect(geoKey).toBe("GEO");
    expect(geoLabelKey).toBe("GEO_LABEL");
    expect(opts.map(({ value, label, rowCount }) => ({ value, label, rowCount }))).toEqual([
      { value: "HU", label: "Hungary", rowCount: 2 },
    ]);
  });

  test("capGeoList limits to max and prefers higher rowCount", () => {
    const codes = ["AFG", "ALB", "CZ", "DE", "FR", "US", "BG", "PL", "IT", "ES"];
    const opts = [
      { value: "CZ", rowCount: 100 },
      { value: "DE", rowCount: 90 },
      { value: "FR", rowCount: 80 },
      { value: "AFG", rowCount: 1 },
    ];
    expect(capGeoList(codes, opts, 3)).toEqual(["CZ", "DE", "FR"]);
  });

  test("geoCodesWithChartData returns only geos present in pivoted rows", () => {
    const chartRows = [
      { x: "2020", CZ: 3.2, DE: 0.4 },
      { x: "2021", CZ: 3.8, DE: 3.2 },
    ];
    expect(geoCodesWithChartData(chartRows, ["CZ", "DE", "HU"])).toEqual(["CZ", "DE"]);
  });

  test("current geo selection overrides a stale supplemental cross-section", () => {
    const currentRows = [
      { TIME_PERIOD: "2020", GEO: "BG", value: 4.9 },
      { TIME_PERIOD: "2020", GEO: "CZ", value: 6.7 },
      { TIME_PERIOD: "2020", GEO: "HR", value: 4.7 },
    ];
    const staleSupplement = [
      { TIME_PERIOD: "2020", GEO: "BG", value: 4.9 },
      { TIME_PERIOD: "2020", GEO: "CZ", value: 6.7 },
    ];

    const merged = mergeSelectedGeoRows(
      currentRows,
      staleSupplement,
      ["BG", "CZ", "HR"],
      "GEO",
    );

    expect(merged.map((row) => row.GEO).sort()).toEqual(["BG", "CZ", "HR"]);
  });

  test("geoSeriesNeedsPointMarkers detects single-point country series", () => {
    const chartRows = [
      { x: "2013", AFG: 655357 },
      { x: "2015", CZE: 7325789, BRB: 117104 },
      { x: "2016", ALB: 563106 },
    ];
    expect(geoSeriesNeedsPointMarkers(chartRows, ["AFG", "ALB", "BRB", "CZE"])).toBe(true);
    expect(isSparseGeoCompare(chartRows, ["AFG", "ALB", "BRB", "CZE"])).toBe(true);
  });

  test("isGeoCrossSectionSnapshot detects one observation per country", () => {
    const rows = [
      { time_period: "2013", geo: "AFG", obs_value: 655357 },
      { time_period: "2016", geo: "ALB", obs_value: 563106 },
      { time_period: "2015", geo: "CZE", obs_value: 7325789 },
    ];
    expect(isGeoCrossSectionSnapshot(rows, "geo")).toBe(true);
    expect(
      isGeoCrossSectionSnapshot(
        [
          ...rows,
          { time_period: "2016", geo: "CZE", obs_value: 7400000 },
        ],
        "geo",
      ),
    ).toBe(false);
  });

  test("detects CZ+DE and builds two geo chart series", () => {
    const rows = [
      { TIME_PERIOD: "2020", GEO: "CZ", GEO_LABEL: "Czechia", value: 3.2 },
      { TIME_PERIOD: "2020", GEO: "DE", GEO_LABEL: "Germany", value: 0.4 },
      { TIME_PERIOD: "2021", GEO: "CZ", GEO_LABEL: "Czechia", value: 3.8 },
      { TIME_PERIOD: "2021", GEO: "DE", GEO_LABEL: "Germany", value: 3.2 },
    ];
    const chartRows = buildGeoChartRows(rows, "TIME_PERIOD", "value", "GEO");

    expect(chartRows).toEqual([
      { x: "2020", CZ: 3.2, DE: 0.4 },
      { x: "2021", CZ: 3.8, DE: 3.2 },
    ]);
    expect(Object.keys(chartRows[0])).toContain("CZ");
    expect(Object.keys(chartRows[0])).toContain("DE");
    expect(Object.keys(chartRows[0])).not.toContain("value");
  });

  test("merges metadata geo availability even when rows only contain HU", () => {
    const rowOpts = [{ value: "HU", label: "Hungary", rowCount: 30 }];
    const out = collectGeoOptions(rowOpts, ["CZ", "DE", "HU"]);

    expect(out.find((x) => x.value === "HU")?.label).toBe("Hungary");
    expect(out.map((x) => x.value).sort()).toEqual(["CZ", "DE", "HU"]);
  });

  test("reads geo options from selectable dimensions and generic geo-like keys", () => {
    expect(isGeoDimensionKeyCandidate("LOCATION")).toBe(true);
    expect(isGeoDimensionKeyCandidate("ÚZEMÍ-Kraj")).toBe(true);
    expect(isGeoDimensionKeyCandidate("unit")).toBe(false);

    const fromSelectable = readGeoOptionsFromSelectableDimensions([
      {
        field: "geo",
        options: [
          { value: "AT", label: "Austria" },
          { value: "NO", label: "Norway" },
        ],
      },
    ]);
    expect(fromSelectable.map((x) => x.value).sort()).toEqual(["AT", "NO"]);

    const fromLocation = readGeoOptionsFromGeoDimensions({
      LOCATION: {
        values: [
          { code: "DE", label: "Germany" },
          { code: "FR", label: "France" },
        ],
      },
    });
    expect(fromLocation.map((x) => x.value).sort()).toEqual(["DE", "FR"]);
  });
});

describe("SourcePreview Eurostat filter UX", () => {
  let mounted = [];

  beforeEach(() => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = true;
    mounted = [];
    apiMocks.post.mockReset();
    apiMocks.post.mockResolvedValue({ data: {} });
  });

  afterEach(() => {
    for (const { root, container } of mounted) {
      act(() => root.unmount());
      container.remove();
    }
  });

  async function mount(preview, props) {
    const rendered = await renderPreview(preview, props);
    mounted.push(rendered);
    return rendered;
  }

  function findButton(container, matcher) {
    return Array.from(container.querySelectorAll("button")).find((button) =>
      matcher.test([
        button.textContent,
        button.getAttribute("title"),
        button.getAttribute("aria-label"),
      ].filter(Boolean).join(" "))
    );
  }

  async function clickElement(element) {
    expect(element).toBeTruthy();
    await act(async () => {
      element.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      await flushMicrotasks();
    });
  }

  async function openGeoPicker(container) {
    const button = findButton(
      container,
      /Přidat \/ odebrat země|Srovnat s dalšími zeměmi|Vybrat \/ porovnat/,
    );
    await clickElement(button);
    return container.querySelector(
      '[data-testid="catalog-unified-geo-picker"], [data-testid="source-preview-geo-picker"]',
    ) || container;
  }

  function findGeoSelect(container, code) {
    return Array.from(container.querySelectorAll("select")).find((select) =>
      Array.from(select.options || []).some((option) => option.value === code)
    );
  }

  async function selectGeo(container, code) {
    const select = findGeoSelect(container, code);
    expect(select).toBeTruthy();
    await act(async () => {
      const valueSetter = Object.getOwnPropertyDescriptor(
        HTMLSelectElement.prototype,
        "value",
      )?.set;
      valueSetter.call(select, code);
      select.dispatchEvent(new Event("change", { bubbles: true }));
      await flushMicrotasks();
    });
  }

  test("renders one deduplicated geo selector from rows and metadata", async () => {
    const { container } = await mount({
      source: { source_type: "eurostat", name: "Consumer price index - ENP-South countries" },
      rows: [
        { TIME_PERIOD: "2024", GEO: "IL", GEO_LABEL: "Israel", value: 1 },
        { TIME_PERIOD: "2024", GEO: "JO", GEO_LABEL: "Jordan", value: 2 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      group_field: "geo",
      indicators: [
        { id: "IL", name: "Israel", count: 1 },
        { id: "JO", name: "Jordan", count: 1 },
      ],
      available_dimensions: { geo: { size: 2, sample_values: ["IL", "JO"] } },
    });

    const panel = await openGeoPicker(container);
    expect(panel).toBeTruthy();
    const geoSelect = findGeoSelect(panel, "IL");
    const countryValues = Array.from(geoSelect.options)
      .map((option) => option.value)
      .filter(Boolean);
    expect(new Set(countryValues)).toEqual(new Set(["IL", "JO"]));
  });

  test("compact catalog toolbar opens geo selector and applies selectable geo metadata", async () => {
    const onDimensionFiltersApply = vi.fn();
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP monthly data" },
      rows: [
        { TIME_PERIOD: "2025-12", GEO: "AT", GEO_LABEL: "Austria", value: 3.8 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      requested_filters: { geo: ["AT"] },
      selectable_dimensions: [
        {
          field: "geo",
          options: [
            { value: "AT", label: "Austria" },
            { value: "NO", label: "Norway" },
          ],
        },
      ],
    }, {
      liveCatalogPreview: true,
      onDimensionFiltersApply,
    });

    const panel = await openGeoPicker(container);
    expect(panel).toBeTruthy();
    expect(panel.textContent).toContain("Norway");
    await selectGeo(panel, "NO");
    await clickElement(findButton(panel, /Použít výběr/));

    expect(onDimensionFiltersApply).toHaveBeenCalledTimes(1);
    expect(onDimensionFiltersApply.mock.calls[0][0].geo).toEqual(expect.arrayContaining(["AT", "NO"]));
  });

  test("keeps selected countries when changing a user-choice dimension", async () => {
    const onDimensionFiltersApply = vi.fn();
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP monthly data" },
      rows: [
        { TIME_PERIOD: "2024-01", GEO: "AT", GEO_LABEL: "Austria", value: 3.2 },
        { TIME_PERIOD: "2024-01", GEO: "NO", GEO_LABEL: "Norway", value: 4.1 },
        { TIME_PERIOD: "2024-02", GEO: "AT", GEO_LABEL: "Austria", value: 3.4 },
        { TIME_PERIOD: "2024-02", GEO: "NO", GEO_LABEL: "Norway", value: 4.0 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      requested_filters: { geo: ["AT", "NO"], coicop: "CP00" },
      selectable_dimensions: [
        {
          field: "coicop",
          options: [
            { value: "CP00", label: "All-items HICP" },
            { value: "CP01", label: "Food" },
          ],
        },
      ],
      available_dimensions: {
        geo: {
          values: [
            { code: "AT", label: "Austria" },
            { code: "NO", label: "Norway" },
          ],
        },
        coicop: {
          values: [
            { code: "CP00", label: "All-items HICP" },
            { code: "CP01", label: "Food" },
          ],
        },
      },
    }, {
      liveCatalogPreview: true,
      onDimensionFiltersApply,
    });

    const select = Array.from(container.querySelectorAll("select")).find((el) =>
      Array.from(el.options || []).some((opt) => opt.value === "CP01")
    );
    expect(select).toBeTruthy();

    await act(async () => {
      select.value = "CP01";
      select.dispatchEvent(new Event("change", { bubbles: true }));
      await flushMicrotasks();
    });

    expect(onDimensionFiltersApply).toHaveBeenCalledTimes(1);
    const filters = onDimensionFiltersApply.mock.calls[0][0];
    expect(filters.coicop).toBe("CP01");
    expect(filters.geo).toEqual(["AT", "NO"]);
  });

  test("marks a live-verified no-data value as disabled instead of letting the user pick it", async () => {
    // Živě zjištěno na naio_10_pyp1620/CZ: ind_use=T (spárované s tehdejším cpa2_1) nemělo žádná
    // data, ale rychlé ODVĚTVÍ menu to nijak neoznačilo - appka dovolila kombinaci vybrat naslepo.
    apiMocks.post.mockImplementation((url, body) => {
      if (String(url).includes("/dimension-availability") && body?.target_dimension === "ind_use") {
        return Promise.resolve({
          data: {
            options: [{ code: "T" }, { code: "G45" }],
            invalid_removed: ["T"],
            complete: true,
          },
        });
      }
      return Promise.resolve({ data: {} });
    });

    const { container } = await mount({
      dataset_id: "naio_10_pyp1620",
      source: { source_type: "eurostat", name: "Obchodní a dopravní marže" },
      rows: [{ TIME_PERIOD: "2023", GEO: "CZ", value: 1157.8 }],
      fields: ["TIME_PERIOD", "GEO", "value"],
      requested_filters: { geo: ["CZ"], ind_use: "G45" },
      selectable_dimensions: [
        {
          field: "ind_use",
          options: [
            { value: "T", label: "Households as employers" },
            { value: "G45", label: "Wholesale and retail trade" },
          ],
        },
      ],
      available_dimensions: {
        geo: { values: [{ code: "CZ", label: "Czechia" }] },
        ind_use: {
          values: [
            { code: "T", label: "Households as employers" },
            { code: "G45", label: "Wholesale and retail trade" },
          ],
        },
      },
    }, { liveCatalogPreview: true, onDimensionFiltersApply: vi.fn() });

    await act(async () => {
      await flushMicrotasks();
    });

    const select = Array.from(container.querySelectorAll("select")).find((el) =>
      Array.from(el.options || []).some((opt) => opt.value === "T"),
    );
    expect(select).toBeTruthy();
    const optionT = Array.from(select.options).find((opt) => opt.value === "T");
    const optionG45 = Array.from(select.options).find((opt) => opt.value === "G45");
    expect(optionT.disabled).toBe(true);
    expect(optionT.text).toContain("(bez dat)");
    expect(optionG45.disabled).toBe(false);
    expect(optionG45.text).not.toContain("(bez dat)");
  });

  test("uses dimension labels instead of technical indicator codes", async () => {
    const { container } = await mount({
      source: { source_type: "ecb", name: "Interest rates Sweden" },
      rows: [
        {
          date: "2025-01",
          value: 3.1,
          indicator_id: "MF-3MI-RT",
          indicator_name: "Three-month money market rate",
        },
        {
          date: "2025-01",
          value: 2.8,
          indicator_id: "MF-DDI-RT",
          indicator_name: "Overnight deposit rate",
        },
        {
          date: "2025-02",
          value: 3.0,
          indicator_id: "MF-3MI-RT",
          indicator_name: "Three-month money market rate",
        },
        {
          date: "2025-02",
          value: 2.7,
          indicator_id: "MF-DDI-RT",
          indicator_name: "Overnight deposit rate",
        },
      ],
      fields: ["date", "value", "indicator_id", "indicator_name"],
      group_field: "indicator_id",
      selected_indicator: "MF-3MI-RT",
      selected_indicators: ["MF-3MI-RT", "MF-DDI-RT"],
      indicators: [
        { id: "MF-3MI-RT", name: "MF-3MI-RT", count: 2 },
        { id: "MF-DDI-RT", name: "MF-DDI-RT", count: 2 },
      ],
      available_dimensions: {
        indicator_id: {
          label: "Ukazatel",
          values: ["MF-3MI-RT", "MF-DDI-RT"],
          value_labels: {
            "MF-3MI-RT": "Three-month money market rate",
            "MF-DDI-RT": "Overnight deposit rate",
          },
        },
      },
    });

    const indicatorSelect = Array.from(container.querySelectorAll("select")).find((select) =>
      Array.from(select.options || []).some((opt) => opt.value === "MF-3MI-RT")
    );
    expect(indicatorSelect).toBeTruthy();
    const optionLabels = Array.from(indicatorSelect.options).map((opt) => opt.textContent);
    expect(optionLabels).toContain("Three-month money market rate");
    expect(optionLabels).toContain("Overnight deposit rate");
    expect(optionLabels).not.toContain("MF-3MI-RT");
    expect(optionLabels).not.toContain("MF-DDI-RT");
  });

  test("adapts the shared catalog chart slot to a multi-country time series", async () => {
    let renderedChartData = null;
    const ChartSlotProbe = ({ data }) => {
      renderedChartData = data;
      return React.createElement("div", { "data-testid": "external-chart-slot" }, "external");
    };
    const chartSlot = React.createElement(ChartSlotProbe, { data: {} });
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP monthly data" },
      rows: [
        { TIME_PERIOD: "2024-01", GEO: "AT", GEO_LABEL: "Austria", value: 3.2 },
        { TIME_PERIOD: "2024-01", GEO: "NO", GEO_LABEL: "Norway", value: 4.1 },
        { TIME_PERIOD: "2024-02", GEO: "AT", GEO_LABEL: "Austria", value: 3.4 },
        { TIME_PERIOD: "2024-02", GEO: "NO", GEO_LABEL: "Norway", value: 4.0 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      requested_filters: { geo: ["AT", "NO"] },
      available_dimensions: {
        geo: {
          values: [
            { code: "AT", label: "Austria" },
            { code: "NO", label: "Norway" },
          ],
        },
      },
    }, {
      liveCatalogPreview: true,
      catalogChartSize: "detail",
      chartSlot,
    });

    expect(container.querySelector('[data-testid="catalog-chart-slot"]')).toBeTruthy();
    expect(container.querySelector('[data-testid="external-chart-slot"]')).toBeTruthy();
    expect(renderedChartData?.multi_series).toBe(true);
    expect(new Set(renderedChartData?.series?.map((series) => series.key))).toEqual(
      new Set(["AT", "NO"]),
    );
    expect(renderedChartData?.rows?.every((row) => row.period)).toBe(true);
  });

  test("keeps sparse multi-country snapshots out of the time-series chart mode", async () => {
    const { container } = await mount({
      source: { source_type: "eurostat", name: "Bank profitability" },
      rows: [
        { TIME_PERIOD: "2025", GEO: "CZ", GEO_LABEL: "Czechia", value: 16.1 },
        { TIME_PERIOD: "2025", GEO: "BG", GEO_LABEL: "Bulgaria", value: 12.1 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      requested_filters: { geo: ["CZ", "BG"] },
      available_dimensions: {
        geo: {
          values: [
            { code: "CZ", label: "Czechia" },
            { code: "BG", label: "Bulgaria" },
          ],
        },
      },
    }, {
      liveCatalogPreview: true,
      catalogChartSize: "detail",
    });

    expect(container.querySelector('[data-testid="catalog-time-series-history-missing"]')).toBeTruthy();
    expect(container.querySelector('[data-testid="catalog-latest-values-chart"]')).toBeFalsy();

    const compareButton = Array.from(container.querySelectorAll("button")).find((el) =>
      /Srovn/.test(el.textContent || "") && /hodnot/.test(el.textContent || "")
    );
    expect(compareButton).toBeTruthy();
    await act(async () => {
      compareButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      await flushMicrotasks();
    });

    expect(container.querySelector('[data-testid="catalog-latest-values-chart"]')).toBeTruthy();
    expect(container.querySelector('[data-testid="catalog-time-series-history-missing"]')).toBeFalsy();
  });

  test("shows single geo summary and active chart summary", async () => {
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP Hungary" },
      rows: [
        { TIME_PERIOD: "2024", GEO: "HU", GEO_LABEL: "Hungary", value: 6.1 },
        { TIME_PERIOD: "2025", GEO: "HU", GEO_LABEL: "Hungary", value: 5.2 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
    });

    expect(container.textContent).toContain("Země: Hungary");
    expect(container.textContent).toContain("V grafu: 1 země (HU)");
  });

  test("offers clear country comparison action and quick compare chips", async () => {
    const onDimensionFiltersApply = vi.fn();
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP Italy" },
      rows: [
        { TIME_PERIOD: "2024", GEO: "IT", GEO_LABEL: "Italy", value: 1.1 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      requested_filters: { geo: ["IT"] },
      available_dimensions: { geo: { sample_values: ["IT", "CZ", "DE", "AT"] } },
    }, { onDimensionFiltersApply });

    expect(container.textContent).toContain("Srovnat s dalšími zeměmi");
    expect(container.textContent).toContain("Rychle porovnat");
    const deButton = Array.from(container.querySelectorAll("button")).find((el) =>
      /\(DE\)|\+ DE|\+ Germany|\+ Německo/.test(el.textContent || "")
    );
    expect(deButton).toBeTruthy();

    await act(async () => {
      deButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      await flushMicrotasks();
    });

    expect(onDimensionFiltersApply).toHaveBeenCalledTimes(1);
    expect(onDimensionFiltersApply.mock.calls[0][0].geo).toEqual(["IT", "DE"]);
  });

  test("renders needs-filters panel with friendly COICOP control", async () => {
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP contributions" },
      status: "needs_filters",
      rows: [],
      fields: [],
      missing_filters: ["coicop"],
      available_dimensions: {
        coicop: {
          size: 2,
          values: [
            { code: "CP00", label: "All-items HICP" },
            { code: "CP01", label: "Food and non-alcoholic beverages" },
          ],
        },
      },
    });

    expect(container.textContent).toContain("Dataset potřebuje doplnit filtry");
    expect(container.textContent).toContain("Kategorie / COICOP");
    expect(container.textContent).toContain("All-items HICP");
    expect(container.textContent).not.toContain("available dimensions:");
  });

  test("renders dropped-filter warning as friendly text", async () => {
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP contributions" },
      status: "needs_filters",
      rows: [],
      fields: [],
      missing_filters: ["coicop"],
      dropped_filters: { coicop: { value: "CP00", reason: "invalid_value" } },
      available_dimensions: { coicop: { sample_values: ["CP01"] } },
    });

    expect(container.textContent).toContain("Původně zvolený filtr CP00 není v tomto datasetu dostupný.");
  });

  test("applies selected coicop and geo filters without indicator_id", async () => {
    const onDimensionFiltersApply = vi.fn();
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP contributions" },
      status: "needs_filters",
      rows: [],
      fields: [],
      missing_filters: ["coicop"],
      available_dimensions: {
        coicop: { values: [{ code: "CP00", label: "All-items HICP" }, { code: "CP01", label: "Food" }] },
        geo: { sample_values: ["CZ", "DE"] },
      },
    }, { onDimensionFiltersApply });

    const select = container.querySelector("select");
    await act(async () => {
      select.value = "CP01";
      select.dispatchEvent(new Event("change", { bubbles: true }));
      await flushMicrotasks();
    });
    const button = Array.from(container.querySelectorAll("button")).find((el) =>
      /Načíst náhled s vybranými filtry/.test(el.textContent || "")
    );
    await act(async () => {
      button.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      await flushMicrotasks();
    });

    expect(onDimensionFiltersApply).toHaveBeenCalledTimes(1);
    const filters = onDimensionFiltersApply.mock.calls[0][0];
    expect(filters.coicop).toBe("CP01");
    expect(filters.geo).toEqual(["CZ", "DE"]);
    expect(filters.indicator_id).toBeUndefined();
  });

  test("reopens geo selector after applying CY and HU and keeps change action visible", async () => {
    const onDimensionFiltersApply = vi.fn();
    const preview = {
      source: { source_type: "eurostat", name: "HICP monthly data" },
      rows: [
        { TIME_PERIOD: "2024", GEO: "HU", GEO_LABEL: "Hungary", value: 5.2 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      available_dimensions: { geo: { sample_values: ["CY", "HU", "AT"] } },
    };
    const { container } = await mount(preview, { onDimensionFiltersApply });

    const panel = await openGeoPicker(container);
    await selectGeo(panel, "CY");
    await clickElement(findButton(panel, /Použít výběr/));

    expect(onDimensionFiltersApply).toHaveBeenCalledTimes(1);
    expect(onDimensionFiltersApply.mock.calls[0][0].geo).toEqual(expect.arrayContaining(["CY", "HU"]));
    expect(container.textContent).toContain("Přidat / odebrat země");
  });

  test("keeps metadata geo option AT after refetch returns only selected CY and HU rows", async () => {
    const onDimensionFiltersApply = vi.fn();
    const { container, root } = await mount({
      source: { source_type: "eurostat", name: "HICP monthly data" },
      rows: [
        { TIME_PERIOD: "2024", GEO: "HU", GEO_LABEL: "Hungary", value: 5.2 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      available_dimensions: { geo: { sample_values: ["CY", "HU", "AT"] } },
    }, { onDimensionFiltersApply });

    await act(async () => {
      root.render(React.createElement(SourcePreview, {
        preview: {
          source: { source_type: "eurostat", name: "HICP monthly data" },
          rows: [
            { TIME_PERIOD: "2024", GEO: "CY", GEO_LABEL: "Cyprus", value: 2.1 },
            { TIME_PERIOD: "2024", GEO: "HU", GEO_LABEL: "Hungary", value: 5.2 },
          ],
          fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
          requested_filters: { geo: ["CY", "HU"] },
          available_dimensions: { geo: { sample_values: ["AT"] } },
        },
        onDimensionFiltersApply,
      }));
      await flushMicrotasks();
    });

    const panel = await openGeoPicker(container);
    expect(findButton(panel, /Odebrat (Cyprus|Kypr)/)).toBeTruthy();
    expect(findButton(panel, /Odebrat (Hungary|Maďarsko)/)).toBeTruthy();
    const atSelect = findGeoSelect(panel, "AT");
    expect(atSelect).toBeTruthy();
    expect(atSelect.disabled).toBe(false);
  });

  test("max geo selection disables adding countries but keeps removal actions enabled", async () => {
    const codes = ["AT", "BE", "CY", "DE", "ES", "FR", "HU", "IT", "PL"];
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP monthly data" },
      rows: codes.slice(0, 8).map((code) => ({ TIME_PERIOD: "2024", GEO: code, value: 1 })),
      fields: ["TIME_PERIOD", "GEO", "value"],
      requested_filters: { geo: codes.slice(0, 8) },
      available_dimensions: { geo: { sample_values: codes } },
    });

    const panel = await openGeoPicker(container);
    const countrySelect = findGeoSelect(panel, "PL");
    expect(countrySelect).toBeTruthy();
    expect(countrySelect.disabled).toBe(true);
    expect(panel.textContent).toContain("Maximum vybraných zemí dosaženo");
    const removalActions = Array.from(panel.querySelectorAll('button[aria-label^="Odebrat "]'));
    expect(removalActions).toHaveLength(codes.slice(0, -1).length);
    expect(removalActions.every((button) => !button.disabled)).toBe(true);
  });

  test("unselects CY and applies only HU", async () => {
    const onDimensionFiltersApply = vi.fn();
    const { container } = await mount({
      source: { source_type: "eurostat", name: "HICP monthly data" },
      rows: [
        { TIME_PERIOD: "2024", GEO: "CY", GEO_LABEL: "Cyprus", value: 2.1 },
        { TIME_PERIOD: "2024", GEO: "HU", GEO_LABEL: "Hungary", value: 5.2 },
      ],
      fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
      requested_filters: { geo: ["CY", "HU"] },
      available_dimensions: { geo: { sample_values: ["CY", "HU", "AT"] } },
    }, { onDimensionFiltersApply });

    const panel = await openGeoPicker(container);
    await clickElement(findButton(panel, /Odebrat (Cyprus|Kypr)/));
    await clickElement(findButton(panel, /Použít výběr/));

    expect(onDimensionFiltersApply).toHaveBeenCalledTimes(1);
    expect(onDimensionFiltersApply.mock.calls[0][0].geo).toEqual(["HU"]);
    expect(container.textContent).toContain("V grafu: 1 země (HU)");
  });

  test("uses REF_AREA key when dataset geo field is REF_AREA", async () => {
    const onDimensionFiltersApply = vi.fn();
    const { container } = await mount({
      source: { source_type: "ecb", name: "CBD2" },
      rows: [
        { TIME_PERIOD: "2024-Q1", REF_AREA: "SE", REF_AREA_LABEL: "Sweden", value: 44 },
        { TIME_PERIOD: "2024-Q1", REF_AREA: "DE", REF_AREA_LABEL: "Germany", value: 32 },
      ],
      fields: ["TIME_PERIOD", "REF_AREA", "REF_AREA_LABEL", "value"],
      requested_filters: { REF_AREA: ["SE"] },
      available_dimensions: {
        REF_AREA: [{ id: "SE", name: "Sweden" }, { id: "DE", name: "Germany" }],
        geo: [{ id: "SE", name: "Sweden" }, { id: "DE", name: "Germany" }],
      },
    }, { onDimensionFiltersApply });

    const panel = await openGeoPicker(container);
    await selectGeo(panel, "DE");
    await clickElement(findButton(panel, /Použít výběr/));

    expect(onDimensionFiltersApply).toHaveBeenCalledTimes(1);
    const filters = onDimensionFiltersApply.mock.calls[0][0];
    expect(filters.REF_AREA).toEqual(expect.arrayContaining(["SE", "DE"]));
    expect(filters.geo).toBeUndefined();
  });

  test("normalizes World Bank Data360 selected countries to ISO3 for multi-country charts", async () => {
    const { container } = await mount({
      source: { source_type: "world_bank_data360", name: "Inflation" },
      rows: [
        { TIME_PERIOD: "2024", geo: "HUN", REF_AREA: "HUN", amount: 4.4 },
        { TIME_PERIOD: "2024", geo: "AUT", REF_AREA: "AUT", amount: 3.5 },
        { TIME_PERIOD: "2024", geo: "DEU", REF_AREA: "DEU", amount: 2.2 },
        { TIME_PERIOD: "2025", geo: "HUN", REF_AREA: "HUN", amount: 4.1 },
        { TIME_PERIOD: "2025", geo: "AUT", REF_AREA: "AUT", amount: 3.2 },
        { TIME_PERIOD: "2025", geo: "DEU", REF_AREA: "DEU", amount: 2.0 },
      ],
      fields: ["TIME_PERIOD", "geo", "REF_AREA", "amount"],
      requested_filters: { geo: ["HU", "AT", "DE"] },
      available_dimensions: { geo: { sample_values: ["HUN", "AUT", "DEU"] } },
    });

    expect(container.textContent).toContain("V grafu: 3 země");
  });

  test("WHO-like global snapshot renders cross-section bars", async () => {
    const rows = Array.from({ length: 12 }, (_, i) => ({
      time_period: String(2010 + (i % 5)),
      geo: `C${String(i).padStart(2, "0")}`,
      obs_value: 100000 + i * 1000,
    }));
    const { container } = await mount({
      source: { source_type: "world_bank_data360", name: "Number of registered vehicles" },
      rows,
      fields: ["time_period", "geo", "obs_value"],
      group_field: "geo",
      indicators: rows.map((r) => ({ id: r.geo, name: r.geo, count: 1 })),
    });
    expect(container.textContent).toContain("Průřezová data");
    expect(container.textContent).not.toContain("málo bodů pro graf");
  });
});
