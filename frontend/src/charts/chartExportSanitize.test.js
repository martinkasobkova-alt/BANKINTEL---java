import {
  sanitizeExportValueRaw,
  detectFrequencyFromPeriods,
  resolveExportFrequency,
  normalizeExportPeriod,
  prepareContractForExport,
} from "./chartExportSanitize";
import { createEmptyChartContract, normalizeChartPoint } from "./chartDataContract";

describe("sanitizeExportValueRaw", () => {
  test("accepts finite numbers", () => {
    expect(sanitizeExportValueRaw(1.16)).toBe(1.16);
    expect(sanitizeExportValueRaw("10.2")).toBe(10.2);
    expect(sanitizeExportValueRaw("1,16")).toBe(1.16);
  });

  test("rejects Roman month labels", () => {
    expect(sanitizeExportValueRaw("01.IX")).toBeNull();
    expect(sanitizeExportValueRaw("01.II")).toBeNull();
    expect(sanitizeExportValueRaw("01.VIII")).toBeNull();
  });

  test("rejects period-like strings", () => {
    expect(sanitizeExportValueRaw("2020-Q1")).toBeNull();
    expect(sanitizeExportValueRaw("2008")).toBeNull();
    expect(sanitizeExportValueRaw("Q1")).toBeNull();
  });
});

describe("detectFrequencyFromPeriods", () => {
  test("annual periods", () => {
    expect(detectFrequencyFromPeriods(["2008", "2009", "2010"])).toBe("Y");
  });

  test("quarterly periods", () => {
    expect(detectFrequencyFromPeriods(["2020-Q1", "2020-Q2"])).toBe("Q");
  });

  test("monthly periods", () => {
    expect(detectFrequencyFromPeriods(["2020-01", "2020-02"])).toBe("M");
  });

  test("daily periods", () => {
    expect(detectFrequencyFromPeriods(["2020-01-01", "2020-01-02"])).toBe("D");
  });
});

describe("resolveExportFrequency", () => {
  test("overrides wrong daily default for annual data", () => {
    expect(resolveExportFrequency("D", ["2008", "2009", "2010"])).toBe("Y");
  });
});

describe("normalizeExportPeriod", () => {
  test("parses Czech Roman month period", () => {
    const out = normalizeExportPeriod("2022 01.IX");
    expect(out.period).toBe("2022-09");
    expect(out.period_label).toBe("2022 01.IX");
  });
});

describe("prepareContractForExport", () => {
  test("drops invalid value_raw rows", () => {
    const contract = createEmptyChartContract({
      series: [{ id: "main", key: "main", label: "Test" }],
      data: [
        normalizeChartPoint({ period: "2008", value_raw: 1.16, series_id: "main" }),
        { period: "2009", series_id: "main", value_raw: "01.IX", series_label: "Test" },
      ],
    });
    const prepared = prepareContractForExport(contract);
    expect(prepared.data).toHaveLength(1);
    expect(prepared.data[0].value_raw).toBe(1.16);
    expect(prepared.data.some((p) => p.value_raw === "01.IX")).toBe(false);
  });
});
