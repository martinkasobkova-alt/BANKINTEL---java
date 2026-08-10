import {
  buildAradDataFromCatalogPreview,
  canRenderAradCatalogChart,
  mapCatalogPreviewRowsToArad,
  resolveCatalogPreviewNativeFrequency,
} from "./mapCatalogPreviewToArad";

describe("mapCatalogPreviewToArad", () => {
  it("maps single-series preview rows", () => {
    const rows = mapCatalogPreviewRowsToArad([
      { period: "20260428", value: 1 },
      { period: "20260430", value: 2 },
    ]);
    expect(rows).toHaveLength(2);
    expect(rows[1].period).toBe("20260430");
  });

  it("infers daily native frequency for ARAD-style preview rows", () => {
    const preview = {
      rows: [
        { period: "20260428", value: 1 },
        { period: "20260429", value: 2 },
        { period: "20260430", value: 3 },
      ],
    };
    expect(resolveCatalogPreviewNativeFrequency(preview)).toBe("D");
    expect(buildAradDataFromCatalogPreview(preview, "Test").frequency).toBe("D");
  });

  it("normalizes annual frequency code A to Y", () => {
    expect(resolveCatalogPreviewNativeFrequency({ chart_frequency: "A", rows: [] })).toBe("Y");
  });

  it("builds multi-series arad payload from grouped preview", () => {
    const data = buildAradDataFromCatalogPreview(
      {
        group_field: "indicator_id",
        selected_indicators: ["A", "B"],
        indicators: [
          { id: "A", name: "Řada A" },
          { id: "B", name: "Řada B" },
        ],
        rows: [
          { period: "2024-01", indicator_id: "A", value: 1 },
          { period: "2024-01", indicator_id: "B", value: 2 },
          { period: "2024-02", indicator_id: "A", value: 3 },
          { period: "2024-02", indicator_id: "B", value: 4 },
        ],
        chart_frequency: "M",
      },
      "Test",
    );
    expect(data.multi_series).toBe(true);
    expect(data.series).toHaveLength(2);
    expect(data.rows).toHaveLength(2);
    expect(data.rows[0].A).toBe(1);
    expect(data.rows[0].B).toBe(2);
  });

  it("names multi-series from indicators and resolves geo codes to country names", () => {
    const data = buildAradDataFromCatalogPreview(
      {
        group_field: "COUNTRY",
        selected_indicators: ["CZE", "AUT"],
        indicators: [
          { id: "CZE", name: "CZE" },
          { id: "AUT", name: "AUT" },
        ],
        rows: [
          { period: "2024", COUNTRY: "CZE", value: 1 },
          { period: "2024", COUNTRY: "AUT", value: 2 },
          { period: "2025", COUNTRY: "CZE", value: 3 },
          { period: "2025", COUNTRY: "AUT", value: 4 },
        ],
        chart_frequency: "A",
      },
      "Test",
    );
    expect(data.multi_series).toBe(true);
    // AradView čte `name` — nikdy nesmí chybět, jinak legenda ukáže „Řada 1“.
    for (const s of data.series) {
      expect(String(s.name || "").trim()).not.toBe("");
      expect(s.label).toBe(s.name);
    }
    // Geo skupina: kódy zemí se překládají na názvy (CZE → Česko/Czechia dle ICU).
    expect(data.series.map((s) => s.name)).not.toEqual(["CZE", "AUT"]);
  });

  it("auto-pivots geo rows without explicit selected_indicators (ROE regression)", () => {
    const data = buildAradDataFromCatalogPreview(
      {
        group_field: "geo",
        title: "Return on equity of banks",
        unit: "PC",
        rows: [
          { date: "2024", geo: "CZ", value: 14 },
          { date: "2024", geo: "FR", value: 6 },
          { date: "2024", geo: "PL", value: 14 },
          { date: "2023", geo: "CZ", value: 13 },
          { date: "2023", geo: "FR", value: 7 },
          { date: "2023", geo: "PL", value: 12 },
        ],
        chart_frequency: "A",
      },
      "Return on equity of banks",
    );
    expect(data.multi_series).toBe(true);
    expect(data.series).toHaveLength(3);
    expect(data.rows.find((r) => r.period === "2024")?.CZ).toBe(14);
    expect(data.rows.find((r) => r.period === "2024")?.FR).toBe(6);
    expect(data.rows.find((r) => r.period === "2023")?.PL).toBe(12);
  });

  it("canRenderAradCatalogChart rejects needs_filters", () => {
    expect(canRenderAradCatalogChart({ status: "needs_filters", rows: [] })).toBe(false);
    // 1 řádek nestačí na graf — platí i pro IMF (zdroj už není z AradView vyloučený).
    expect(canRenderAradCatalogChart({ rows: [{ period: "2024-01", value: 1 }] }, "", "imf")).toBe(false);
    expect(
      canRenderAradCatalogChart({
        rows: [
          { period: "2024-01", value: 1 },
          { period: "2024-02", value: 2 },
        ],
      }),
    ).toBe(true);
  });
});
