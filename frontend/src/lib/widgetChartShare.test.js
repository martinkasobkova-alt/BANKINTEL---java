import {
  buildDashboardWidgetPageUrl,
  buildWidgetShareContext,
  extractCatalogFromWidgetConfig,
  isDashboardWidgetShareable,
} from "./widgetChartShare";

describe("widgetChartShare", () => {
  test("buildDashboardWidgetPageUrl", () => {
    expect(buildDashboardWidgetPageUrl("page-1", "wid-2")).toBe(
      "/my-dashboard?page=page-1#widget-wid-2"
    );
  });

  test("extractCatalogFromWidgetConfig reads external catalog widget", () => {
    expect(
      extractCatalogFromWidgetConfig({
        config: { catalog: "arad", set_id: "12345", selected_indicator: "IND" },
      })
    ).toEqual({
      catalogId: "arad",
      sourceType: "arad",
      setId: "12345",
      indicatorId: "IND",
    });
  });

  test("buildWidgetShareContext prefers catalog for chat when config allows", () => {
    const ctx = buildWidgetShareContext({
      pageId: "p1",
      title: "NEER",
      widget: {
        id: "w1",
        type: "external_catalog_chart",
        config: { catalog: "arad", set_id: "99" },
      },
      origin: "https://app.test",
    });
    expect(ctx.mode).toBe("catalog");
    expect(ctx.copyLink).toContain("/my-dashboard?page=p1");
    expect(ctx.messagesLink).toContain("chart_set_id=99");
    expect(ctx.messagesLink).toContain("chart_source_type=arad");
  });

  test("isDashboardWidgetShareable accepts chart widgets", () => {
    expect(isDashboardWidgetShareable({ type: "chart" })).toBe(true);
    expect(isDashboardWidgetShareable({ type: "markdown" })).toBe(false);
  });
});
