import { describe, expect, it } from "vitest";

import { isTextWidgetType, TEXT_WIDGET_TYPES } from "./widgetCatalog";

/**
 * Regrese k nálezu V2 z QA reportu 2026-08-30: editor porovnával typ widgetu jen s "markdown",
 * ale osobní dashboard i API ukládají textový widget jako "text" — panel „Upravit" pak textovému
 * widgetu vůbec nenabídl pole Nadpis / Podnadpis / Text.
 */
describe("isTextWidgetType", () => {
  it("accepts every stored spelling of a text widget", () => {
    expect(isTextWidgetType("markdown")).toBe(true);
    expect(isTextWidgetType("text")).toBe(true);
    expect(isTextWidgetType("note")).toBe(true);
  });

  it("rejects chart and ad widgets", () => {
    expect(isTextWidgetType("arad_view")).toBe(false);
    expect(isTextWidgetType("chart")).toBe(false);
    expect(isTextWidgetType("ad")).toBe(false);
    expect(isTextWidgetType("rss_monitoring")).toBe(false);
  });

  it("tolerates missing values", () => {
    expect(isTextWidgetType(undefined)).toBe(false);
    expect(isTextWidgetType(null)).toBe(false);
    expect(isTextWidgetType("")).toBe(false);
  });

  it("keeps markdown in the canonical list so existing widgets keep working", () => {
    expect(TEXT_WIDGET_TYPES).toContain("markdown");
  });
});
