import { describe, expect, it } from "vitest";
import {
  appliedChartActionMessage,
  resultAfterAutomaticApply,
  shouldAutoApplyChartActions,
  uniqueResearchCitations,
} from "./chartActionExecution";

describe("chart action execution", () => {
  const annotations = [
    { type: "annotate_period", label: "První vláda", from: "2000", to: "2002" },
    { type: "annotate_period", label: "Druhá vláda", from: "2002", to: "2006" },
  ];

  it("automatically applies only complete annotation action sets", () => {
    expect(shouldAutoApplyChartActions(annotations)).toBe(true);
    expect(shouldAutoApplyChartActions([{ type: "clear_period_annotations" }])).toBe(true);
    expect(shouldAutoApplyChartActions([{ type: "open_catalog_search" }])).toBe(false);
    expect(shouldAutoApplyChartActions([])).toBe(false);
  });

  it("replaces planner discussion with a concise execution confirmation", () => {
    expect(appliedChartActionMessage(annotations)).toContain("2 ověřené anotace");
    expect(resultAfterAutomaticApply({
      answer_cz: "Dlouhá odpověď",
      warnings: ["Nezávazná výhrada"],
      chart_actions: annotations,
    })).toMatchObject({
      answer_cz: "Přidáno do grafu: 2 ověřené anotace.",
      warnings: [],
      action_execution: "applied",
    });
    expect(resultAfterAutomaticApply({
      answer_cz: "Dlouhá odpověď",
      chart_actions: [{ type: "clear_period_annotations", query: "oddělej ty vlády" }],
    })).toMatchObject({
      answer_cz: "Anotace byly odstraněny z grafu.",
      warnings: [],
      action_execution: "applied",
    });
  });

  it("deduplicates repeated source links", () => {
    expect(uniqueResearchCitations([
      { url: "https://example.test/a" },
      { url: "https://example.test/a" },
      { url: "https://example.test/b" },
    ])).toHaveLength(2);
  });
});
