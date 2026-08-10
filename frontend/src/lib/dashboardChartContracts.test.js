import {
  buildDashboardChartContract,
  getPresentableDashboardWidgets,
  parseDashboardWidgetReferenceNumbers,
} from "./dashboardChartContracts";

describe("dashboardChartContracts", () => {
  test("builds one series from x/y widget rows", () => {
    const contract = buildDashboardChartContract(
      [
        {
          id: "w1",
          type: "chart",
          engine_type: "dataset_chart",
          title: "Revenue",
          data: { view: "chart", rows: [{ x: "2024", y: 10 }, { x: "2025", y: 12 }] },
        },
      ],
      { pageTitle: "Board" },
    );

    expect(contract.series).toHaveLength(1);
    expect(contract.data).toHaveLength(2);
    expect(contract.data[0]).toMatchObject({ period: "2024", value_raw: 10 });
  });

  test("builds multiple series from wide numeric rows", () => {
    const contract = buildDashboardChartContract([
      {
        id: "w2",
        engine_type: "external_catalog_chart",
        title: "Macro",
        data: {
          view: "chart",
          rows: [
            { period: "2023", gdp: 100, cpi: 4.2 },
            { period: "2024", gdp: 103, cpi: 3.1 },
          ],
        },
      },
    ]);

    expect(contract.series.map((s) => s.label)).toEqual(["Macro: gdp", "Macro: cpi"]);
    expect(contract.data).toHaveLength(4);
  });

  test("marks uploaded dashboard data as strict private", () => {
    const contract = buildDashboardChartContract([
      {
        id: "private",
        type: "uploaded_data_chart",
        engine_type: "user_upload_chart",
        title: "Company KPI",
        config: { user_upload_id: "u1" },
        data: { view: "chart", rows: [{ period: "2024", value: 7 }] },
      },
    ]);

    expect(contract.metadata.contains_private_series).toBe(true);
    expect(contract.metadata.privacy_mode).toBe("strict_private");
    expect(contract.series[0]).toMatchObject({ privacy: "private", source_type: "user_upload" });
    expect(contract.data[0].metadata).toMatchObject({ privacy: "private", source_type: "user_upload" });
  });

  test("filters dashboard slides to chart-like widgets", () => {
    const widgets = getPresentableDashboardWidgets([
      { id: "txt", type: "text", data: { content: "note" } },
      { id: "chart", engine_type: "arad_view", data: { rows: [{ period: "2024", value: 1 }] } },
    ]);

    expect(widgets.map((w) => w.id)).toEqual(["chart"]);
  });

  test("parses dashboard graph references from natural Czech questions", () => {
    expect(parseDashboardWidgetReferenceNumbers("jen jaky vztah mezi grafem 4 a 5?", 5)).toEqual([4, 5]);
    expect(parseDashboardWidgetReferenceNumbers("porovnej grafy 2-4", 5)).toEqual([2, 3, 4]);
    expect(parseDashboardWidgetReferenceNumbers("co rika graf 7?", 5)).toEqual([]);
    expect(parseDashboardWidgetReferenceNumbers("co rika graf za poslednich 5 let?", 5)).toEqual([]);
  });

  test("prefixes dashboard series with graph number when present", () => {
    const contract = buildDashboardChartContract([
      {
        id: "w4",
        engine_type: "dataset_chart",
        __dashboard_graph_index: 4,
        title: "Liabilities",
        data: { view: "chart", rows: [{ period: "2024", value: 100 }] },
      },
    ]);

    expect(contract.series[0].label).toBe("Graf 4: Liabilities");
    expect(contract.data[0]).toMatchObject({
      series_label: "Graf 4: Liabilities",
      metadata: { dashboard_graph_index: 4 },
    });
  });
});
