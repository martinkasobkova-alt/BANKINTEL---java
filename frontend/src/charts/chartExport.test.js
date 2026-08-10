import {
  buildChartExportSheets,
  buildWideRows,
  buildUserWideTable,
  buildClipboardText,
  buildClipboardTextWide,
  validateExportRows,
  TECHNICAL_CLIPBOARD_COLUMNS,
  detectFrequencyFromPeriods,
  EXPORT_LONG_COLUMNS,
  formatExportNumber,
} from "./chartExport";
import { createEmptyChartContract, normalizeChartPoint } from "./chartDataContract";
import { sanitizeExportValueRaw } from "./chartExportSanitize";

describe("chartExport", () => {
  const contract = createEmptyChartContract({
    chart_id: "export_test",
    chart_type: "line",
    title: "Test Chart",
    series: [
      { id: "de", key: "de", label: "Germany", unit: "%" },
      { id: "fr", key: "fr", label: "France", unit: "%" },
    ],
    data: [
      normalizeChartPoint({ period: "2020", value_raw: 5.6, series_id: "de", series_label: "Germany", unit: "%", source: "ECB", dataset: "ROE" }),
      normalizeChartPoint({ period: "2020", value_raw: 6.85, series_id: "fr", series_label: "France", unit: "%", source: "ECB", dataset: "ROE" }),
      normalizeChartPoint({ period: "2021", value_raw: 8.1, series_id: "de", series_label: "Germany", unit: "%", transformation: "none" }),
      normalizeChartPoint({ period: "2021", value_raw: 7.2, series_id: "fr", series_label: "France", unit: "%" }),
    ],
    transformations: [{ type: "none", at: "2026-01-01T00:00:00Z" }],
    metadata: { dataset: "ROE" },
    source: { name: "ECB" },
  });

  test("buildChartExportSheets has required XLSX sheet names", () => {
    const sheets = buildChartExportSheets(contract, { query: "ROE EU" });
    expect(Object.keys(sheets)).toEqual(
      expect.arrayContaining(["Data_Long", "Data_Wide", "Metadata", "Transformations", "Sources"])
    );
  });

  test("buildChartExportSheets includes Dimensions sheet when dimensions_meta present", () => {
    const withDims = {
      ...contract,
      dimensions_meta: { geo: "CZ", freq: "A" },
    };
    const sheets = buildChartExportSheets(withDims);
    expect(sheets.Dimensions).toBeDefined();
    expect(sheets.Dimensions.rows).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ dimension: "geo", value: "CZ" }),
        expect.objectContaining({ dimension: "freq", value: "A" }),
      ])
    );
  });

  test("XLSX sheet order — Data_Wide first, Data_Long second", () => {
    const sheets = buildChartExportSheets(contract);
    const names = Object.keys(sheets);
    expect(names[0]).toBe("Data_Wide");
    expect(names[1]).toBe("Data_Long");
  });

  test("CSV long format — value_raw is numeric", () => {
    const sheets = buildChartExportSheets(contract);
    for (const row of sheets.Data_Long.rows) {
      expect(typeof row.value_raw).toBe("number");
    }
    expect(validateExportRows(sheets.Data_Long.rows).ok).toBe(true);
  });

  test("no nested JSON in export rows", () => {
    const sheets = buildChartExportSheets(contract);
    for (const row of sheets.Data_Long.rows) {
      for (const v of Object.values(row)) {
        expect(typeof v).not.toBe("object");
      }
    }
  });

  test("wide format uses human-readable headers", () => {
    const wide = buildWideRows(contract);
    expect(wide.columns[0]).toBe("Období");
    expect(wide.columns).toContain("Germany (%)");
    expect(wide.rows[0]["Germany (%)"]).toBe(5.6);
  });

  test("period, geo, unit, source, dataset, transformation preserved in long export", () => {
    const sheets = buildChartExportSheets(contract);
    const row = sheets.Data_Long.rows[0];
    expect(row.period).toBe("2020");
    expect(row.unit).toBe("%");
    expect(row.source).toBe("ECB");
    expect(row.dataset).toBe("ROE");
    expect(row.transformation).toBe("none");
  });

  test("clipboard wide is tab-delimited without technical columns", () => {
    const text = buildClipboardText(contract);
    const header = text.split("\n")[0];
    expect(header).toContain("\t");
    expect(header).toContain("Období");
    expect(header).not.toContain("series_id");
    expect(header).not.toContain("value_raw");
    expect(text).toContain("2020");
    for (const col of TECHNICAL_CLIPBOARD_COLUMNS) {
      expect(header).not.toContain(col);
    }
  });

  test("copy wide single series", () => {
    const single = createEmptyChartContract({
      title: "Poměr zisku",
      series: [{ id: "main", key: "main", label: "Poměr zisku vůči aktivům českých bank (ROA v %)" }],
      data: [
        normalizeChartPoint({
          period: "2008",
          value_raw: 1.16,
          series_id: "main",
          series_label: "Poměr zisku vůči aktivům českých bank (ROA v %)",
        }),
        normalizeChartPoint({
          period: "2009",
          value_raw: 1.46,
          series_id: "main",
          series_label: "Poměr zisku vůči aktivům českých bank (ROA v %)",
        }),
      ],
    });
    const text = buildClipboardTextWide(single, { locale: "cs-CZ" });
    expect(text.split("\n")[0]).toBe(
      "Období\tPoměr zisku vůči aktivům českých bank (ROA v %)"
    );
    expect(text).toContain("2008\t1,16");
    expect(text).toContain("2009\t1,46");
    expect(text).not.toContain("series_id");
    expect(text).not.toContain("dataset");
    expect(text).not.toContain("chart_type");
    expect(text).not.toContain("transformation");
    expect(text).not.toContain("source");
    expect(text).not.toContain("frequency");
  });

  test("copy wide multi-series", () => {
    const multi = createEmptyChartContract({
      series: [
        { id: "de", key: "de", label: "Německo – ROE" },
        { id: "pl", key: "pl", label: "Polsko – ROE" },
        { id: "cz", key: "cz", label: "Česko – ROE" },
      ],
      data: [
        normalizeChartPoint({ period: "2020-Q1", value_raw: 10.2, series_id: "de", series_label: "Německo – ROE" }),
        normalizeChartPoint({ period: "2020-Q1", value_raw: 9.8, series_id: "pl", series_label: "Polsko – ROE" }),
        normalizeChartPoint({ period: "2020-Q1", value_raw: 8.7, series_id: "cz", series_label: "Česko – ROE" }),
      ],
    });
    const text = buildClipboardTextWide(multi, { locale: "cs-CZ" });
    expect(text.split("\n")[0]).toBe("Období\tNěmecko – ROE\tPolsko – ROE\tČesko – ROE");
    const { headers, rows } = buildUserWideTable(multi);
    expect(headers).toEqual(["Období", "Německo – ROE", "Polsko – ROE", "Česko – ROE"]);
    expect(rows[0]["Německo – ROE"]).toBe(10.2);
    expect(rows[0]["Polsko – ROE"]).toBe(9.8);
  });

  test("Czech decimal comma in clipboard", () => {
    const values = [1.2, 1.08, 1.09];
    for (const v of values) {
      expect(formatExportNumber(v, "cs-CZ")).not.toContain(".");
    }
    expect(formatExportNumber(1.2, "cs-CZ")).toBe("1,2");
    expect(formatExportNumber(1.08, "cs-CZ")).toBe("1,08");
    expect(formatExportNumber(1.09, "cs-CZ")).toBe("1,09");
  });

  test("Advanced CSV still includes technical columns", () => {
    const sheets = buildChartExportSheets(contract);
    const cols = sheets.Data_Long.columns;
    for (const key of ["period", "series_id", "value_raw", "source", "dataset", "chart_type"]) {
      expect(cols).toContain(key);
    }
    expect(EXPORT_LONG_COLUMNS).toEqual(
      expect.arrayContaining(["period", "series_id", "value_raw", "source", "dataset", "chart_type"])
    );
  });

  test("value_raw sanitization rejects Roman month labels", () => {
    const bad = createEmptyChartContract({
      series: [{ id: "main", key: "main", label: "Test" }],
      data: [
        { period: "2008", series_id: "main", series_label: "Test", value_raw: "01.IX" },
        normalizeChartPoint({ period: "2009", value_raw: 1.2, series_id: "main", series_label: "Test" }),
      ],
    });
    const sheets = buildChartExportSheets(bad);
    expect(sheets.Data_Long.rows.every((r) => r.value_raw !== "01.IX")).toBe(true);
    expect(sheets.Data_Long.rows.every((r) => typeof r.value_raw === "number")).toBe(true);
    expect(sanitizeExportValueRaw("01.IX")).toBeNull();
  });

  test("frequency detection for annual periods", () => {
    expect(detectFrequencyFromPeriods(["2008", "2009", "2010"])).toBe("Y");
  });

  test("percentages stored as numeric not string", () => {
    const sheets = buildChartExportSheets(contract);
    for (const row of sheets.Data_Long.rows) {
      expect(typeof row.value_raw).toBe("number");
      expect(String(row.value_raw)).not.toContain("%");
    }
  });
});
