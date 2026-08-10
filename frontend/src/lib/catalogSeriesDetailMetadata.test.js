import {
  applyChartDisplayStateToMetadata,
  catalogSeriesDetailMetadata,
  catalogSeriesDetailMetadataItems,
  chartDisplayStatesEqual,
  extractLatestPeriodFromPreview,
  extractLatestValuePointsFromPreview,
  formatCatalogMetadataDate,
  formatCatalogMetadataPctChange,
} from "./catalogSeriesDetailMetadata";

describe("catalogSeriesDetailMetadata", () => {
  const def = { id: "arad", sourceType: "arad", label: "ČNB - ARAD" };
  const row = { kind: "set", set_id: "1110", name: "Fondy kolektivního investování" };

  it("returns dashes from row when preview is missing", () => {
    const meta = catalogSeriesDetailMetadata(def, row, null);
    expect(meta.frequency).toBe("");
    expect(meta.geo).toBe("");
    expect(meta.lastDate).toBe("");
  });

  it("fills metadata from preview payload", () => {
    const previewData = {
      chart_frequency: "M",
      chart_frequency_label: "Měsíční",
      rows: [
        { period: "2024-01-31", value: 1 },
        { period: "2024-02-29", value: 2 },
      ],
    };
    const meta = catalogSeriesDetailMetadata(def, row, previewData);
    expect(meta.frequency).toBe("Měsíční");
    expect(meta.geo).toBe("ČR");
    expect(meta.lastDate).toBe("2024-02-29");
  });

  it("extractLatestPeriodFromPreview picks max period", () => {
    expect(
      extractLatestPeriodFromPreview({
        rows: [{ datum: "2023-12-01" }, { datum: "2024-06-01" }],
      }),
    ).toBe("2024-06-01");
  });

  it("formats ARAD YYYYMMDD period for display", () => {
    expect(formatCatalogMetadataDate("20260430")).toMatch(/30\.?\s*4\.?\s*2026/);
  });

  it("infers daily frequency from ARAD-style preview without chart_frequency", () => {
    const previewData = {
      group_field: "indicator_id",
      indicators: [{ id: "1" }, { id: "2" }],
      rows: [
        { period: "20260428", value: 1 },
        { period: "20260429", value: 2 },
        { period: "20260430", value: 3 },
      ],
    };
    const items = catalogSeriesDetailMetadataItems(def, row, previewData);
    expect(items[0].value).toBe("Denní");
    expect(items[2].value).toMatch(/30\.?\s*4\.?\s*2026/);
    expect(items[3].value).toBe("3");
    expect(items[4].value).toBe("+50 %");
  });

  it("extractLatestValuePointsFromPreview sorts chronologically", () => {
    const points = extractLatestValuePointsFromPreview({
      rows: [
        { period: "2024-02", value: 20 },
        { period: "2024-01", value: 10 },
      ],
    });
    expect(points.map((p) => p.value)).toEqual([10, 20]);
  });

  it("formatCatalogMetadataPctChange handles negative change", () => {
    expect(formatCatalogMetadataPctChange(90, 100)).toBe("-10 %");
  });

  it("chartDisplayStatesEqual ignores object identity when values match", () => {
    expect(
      chartDisplayStatesEqual(
        { frequencyCode: "Q", lastValue: 1.2, lastPeriod: "2024-Q1" },
        { frequencyCode: "Q", lastValue: 1.2, lastPeriod: "2024-Q1" },
      ),
    ).toBe(true);
  });

  it("applyChartDisplayStateToMetadata overrides preview metadata from chart state", () => {
    const baseMeta = {
      frequency: "Čtvrtletní",
      lastDate: "2023-09",
      lastValue: "12,60 %",
      valueChangePct: "-51,38 %",
    };
    const synced = applyChartDisplayStateToMetadata(baseMeta, {
      frequencyCode: "Y",
      frequencyLabel: "Roční",
      lastPeriod: "2023",
      lastValue: 12.48,
      prevValue: 15.05,
      unit: "%",
    });
    expect(synced.frequency).toBe("Roční");
    expect(synced.lastDate).toBe("2023");
    expect(synced.lastValue).toBe("12,48 %");
    expect(synced.valueChangePct).toBe("-17,08 %");
  });

  it("catalogSeriesDetailMetadataItems uses chart display state in detail panel", () => {
    const previewData = {
      chart_frequency: "Q",
      chart_frequency_label: "Čtvrtletní",
      rows: [
        { period: "2023-Q3", value: 12.6 },
        { period: "2023-Q4", value: 12.48 },
      ],
    };
    const items = catalogSeriesDetailMetadataItems(def, row, previewData, null, {
      frequencyCode: "Y",
      frequencyLabel: "Roční",
      lastPeriod: "2023",
      lastValue: 12.48,
      prevValue: 15.05,
      unit: "%",
    });
    expect(items[0].value).toBe("Roční");
    expect(items[2].value).toBe("2023");
    expect(items[3].value).toBe("12,48 %");
  });
});
