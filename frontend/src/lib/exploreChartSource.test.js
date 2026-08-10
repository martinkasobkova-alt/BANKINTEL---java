import { formatExploreChartSource } from "./exploreChartSource";

describe("formatExploreChartSource", () => {
  it("formats IMF series with label and dataset id", () => {
    const out = formatExploreChartSource({
      source: "imf",
      sourceLabel: "IMF (World Economic Outlook)",
      setId: "IMF|IMF.RES|WEO|9.0.0|JPN.NGDP_RPCH",
    });
    expect(out.label).toContain("IMF");
    expect(out.datasetId).toContain("JPN.NGDP_RPCH");
    expect(out.line).toContain("·");
  });

  it("always returns a label", () => {
    const out = formatExploreChartSource({ source: "", setId: "" });
    expect(out.label).toBeTruthy();
  });
});
