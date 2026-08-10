import {
  guessFrequencyCodeFromPeriodSample,
  inferNativeFrequencyFromChartRows,
  nativeFrequencyLabelCs,
  resolveChartFrequencyLabel,
  resolveNativeFrequencyCode,
} from "./chartFrequencyInfer";
import { contractFromAradViewState } from "@/charts/contractFromAradViewState";

describe("chartFrequencyInfer", () => {
  test("guessFrequencyCodeFromPeriodSample detects quarterly and annual", () => {
    expect(guessFrequencyCodeFromPeriodSample("2023-Q1")).toBe("Q");
    expect(guessFrequencyCodeFromPeriodSample("2023")).toBe("Y");
  });

  test("annual periods infer Y not Q or D", () => {
    const rows = [{ period: "2008" }, { period: "2009" }, { period: "2010" }];
    expect(inferNativeFrequencyFromChartRows(rows)).toBe("Y");
    expect(resolveNativeFrequencyCode({ rows })).toBe("Y");
    expect(resolveNativeFrequencyCode({ rows }).toUpperCase()).not.toBe("Q");
    expect(resolveNativeFrequencyCode({ rows }).toUpperCase()).not.toBe("D");
  });

  test("quarterly periods infer Q", () => {
    const rows = [{ period: "2020-Q1" }, { period: "2020-Q2" }];
    expect(inferNativeFrequencyFromChartRows(rows)).toBe("Q");
    expect(resolveNativeFrequencyCode({ rows })).toBe("Q");
  });

  test("monthly periods infer M", () => {
    const rows = [{ period: "2020-01" }, { period: "2020-02" }];
    expect(inferNativeFrequencyFromChartRows(rows)).toBe("M");
    expect(resolveNativeFrequencyCode({ rows })).toBe("M");
  });

  test("daily periods infer D", () => {
    const rows = [{ period: "2020-01-01" }, { period: "2020-01-02" }];
    expect(inferNativeFrequencyFromChartRows(rows)).toBe("D");
    expect(resolveNativeFrequencyCode({ rows })).toBe("D");
  });

  test("multi-series rows infer frequency from shared periods", () => {
    const rows = [
      { period: "2008", de: 1, pl: 2 },
      { period: "2009", de: 1.1, pl: 2.1 },
    ];
    expect(inferNativeFrequencyFromChartRows(rows, true)).toBe("Y");
    expect(resolveNativeFrequencyCode({ rows, isMultiSeries: true })).toBe("Y");
  });

  test("explicit frequency from source wins over inference", () => {
    const rows = [{ period: "2008" }, { period: "2009" }];
    expect(resolveNativeFrequencyCode({ explicitFrequency: "Q", rows })).toBe("Q");
    expect(resolveNativeFrequencyCode({ explicitFrequency: "A", rows })).toBe("Y");
  });

  test("export contract uses inferred frequency when source omits it", () => {
    const contract = contractFromAradViewState({
      title: "Annual export",
      chartRows: [
        { x: "2008", y: 1.16 },
        { x: "2009", y: 1.46 },
      ],
    });
    expect(contract.metadata.frequency).toBe("Y");
    expect(contract.data[0].frequency).toBe("Y");
  });

  test("export contract keeps explicit frequency over inference", () => {
    const contract = contractFromAradViewState({
      title: "Explicit Q",
      frequency: "Q",
      chartRows: [
        { x: "2008", y: 1.16 },
        { x: "2009", y: 1.46 },
      ],
    });
    expect(contract.metadata.frequency).toBe("Q");
  });

  test("resolveChartFrequencyLabel prefers catalog label", () => {
    expect(
      resolveChartFrequencyLabel({
        catalogFreqLabel: "Čtvrtletní",
        chartRows: [{ x: "2020" }],
      })
    ).toBe("Čtvrtletní");
  });

  test("resolveChartFrequencyLabel infers from chart periods", () => {
    expect(
      resolveChartFrequencyLabel({
        chartRows: [{ x: "2021-Q1" }, { x: "2021-Q2" }],
      })
    ).toBe("Čtvrtletní");
    expect(nativeFrequencyLabelCs("A")).toBe("Roční");
  });

  test("resolveChartFrequencyLabel prefers period column over ISO date axis", () => {
    expect(
      resolveChartFrequencyLabel({
        fields: ["date", "period", "value"],
        rows: [
          { date: "2019-09-01", period: "2019M09", value: 1 },
          { date: "2019-10-01", period: "2019M10", value: 2 },
        ],
        chartRows: [{ x: "2019-09-01" }, { x: "2019-10-01" }],
      })
    ).toBe("Měsíční");
  });

  test("inferNativeFrequencyFromChartRows detects monthly ISO dates", () => {
    expect(
      resolveChartFrequencyLabel({
        chartRows: [
          { x: "2019-09-01" },
          { x: "2019-10-01" },
          { x: "2019-11-01" },
        ],
      })
    ).toBe("Měsíční");
    expect(
      resolveChartFrequencyLabel({
        chartRows: [{ x: "2024-01-01" }, { x: "2025-01-01" }, { x: "2026-01-01" }],
      })
    ).toBe("Roční");
  });
});
