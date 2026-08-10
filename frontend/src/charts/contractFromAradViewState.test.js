import {
  contractFromAradViewState,
  validateAradViewContract,
} from "./contractFromAradViewState";
import { applyAradViewDisplayTransform } from "./applyAradViewDisplayTransform";
import { buildClipboardText, buildChartExportSheets, validateExportRows } from "./chartExport";
import { applyChartTransform } from "./chartTransforms";
import { CHART_DATA_MODES } from "./chartTypes";

describe("contractFromAradViewState", () => {
  const baseRows = [
    { x: "2024-Q1", y: 102.5 },
    { x: "2024-Q2", y: 104.2 },
    { x: "2024-Q3", y: 103.1 },
  ];

  test("creates valid contract with numeric value_raw", () => {
    const contract = contractFromAradViewState({
      title: "Test indikátor",
      unit: "%",
      chartKind: "line",
      chartRows: baseRows,
      frequency: "Q",
    });
    const v = validateAradViewContract(contract);
    expect(v.ok).toBe(true);
    expect(contract.data).toHaveLength(3);
    expect(contract.data[0].value_raw).toBe(102.5);
    expect(typeof contract.data[0].value_raw).toBe("number");
  });

  test("latest mode sets metadata", () => {
    const contract = contractFromAradViewState({
      title: "Inflace",
      latestDataMode: true,
      chartKind: "bar",
      chartRows: [
        { x: "Česko", y: 2.4 },
        { x: "Německo", y: 2.1 },
      ],
    });
    expect(contract.metadata.data_mode).toBe(CHART_DATA_MODES.LATEST);
  });

  test("multi-series maps each series key", () => {
    const contract = contractFromAradViewState({
      title: "Multi",
      isMultiSeries: true,
      seriesList: [
        { key: "a", name: "Series A" },
        { key: "b", name: "Series B" },
      ],
      chartRows: [{ x: "2024-Q1", a: 1, b: 2 }],
    });
    expect(contract.data).toHaveLength(2);
    expect(contract.data.find((p) => p.series_id === "a")?.value_raw).toBe(1);
  });
});

describe("chart export from AradView contract", () => {
  test("clipboard is wide tab-delimited without nested JSON or technical columns", () => {
    const contract = contractFromAradViewState({
      title: "Export test",
      unit: "index",
      chartRows: [{ x: "2024-Q1", y: 100 }],
      frequency: "Q",
      data: { source: "Eurostat", dataset: "STS" },
    });
    const text = buildClipboardText(contract);
    expect(text.split("\n")[0]).toContain("\t");
    expect(text.split("\n")[0]).toContain("Období");
    expect(text.split("\n")[0]).not.toContain("value_raw");
    expect(text).not.toContain("{");
    const exportCheck = validateExportRows(buildChartExportSheets(contract).Data_Long.rows);
    expect(exportCheck.ok).toBe(true);
  });

  test("annual data does not keep frequency D from widget default", () => {
    const contract = contractFromAradViewState({
      title: "Roční",
      chartRows: [
        { x: "2008", y: 1.16 },
        { x: "2009", y: 1.46 },
      ],
      frequency: "D",
    });
    expect(contract.metadata.frequency).toBe("Y");
    expect(contract.data[0].frequency).toBe("Y");
  });

  test("XLSX sheets include Data_Long and Metadata", () => {
    const contract = contractFromAradViewState({
      title: "XLSX test",
      chartRows: [{ x: "2024-Q1", y: 1 }],
    });
    const sheets = buildChartExportSheets(contract);
    expect(sheets.Data_Long.rows.length).toBeGreaterThan(0);
    expect(sheets.Metadata.rows.some((r) => r.key === "chart_id")).toBe(true);
  });
});

describe("applyAradViewDisplayTransform", () => {
  test("index 100 uses chartTransforms", () => {
    const rows = [
      { x: "2020-Q1", y: 100 },
      { x: "2020-Q2", y: 110 },
      { x: "2020-Q3", y: 105 },
    ];
    const out = applyAradViewDisplayTransform(rows, "index_100", { frequency: "Q" });
    expect(out.length).toBeGreaterThan(0);
    expect(out[0].y).toBe(100);
    const direct = applyChartTransform("index_100", rows.map((r) => ({ x: r.x, y: r.y })));
    expect(direct.ok).toBe(true);
  });

  test("raw returns same rows", () => {
    const rows = [{ x: "a", y: 1 }];
    expect(applyAradViewDisplayTransform(rows, "raw")).toEqual(rows);
  });
});
