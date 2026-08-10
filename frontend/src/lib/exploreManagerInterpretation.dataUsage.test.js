import { resolveDataUsageSummary } from "./exploreManagerInterpretation";

describe("resolveDataUsageSummary", () => {
  it("vrátí lidský souhrn počtu řad místo technických warningů", () => {
    const ctx = {
      series_summary: [
        { title: "A" },
        { title: "B", is_forecast: true },
        { title: "C", is_proxy: true },
        { title: "D" },
      ],
      warnings_for_llm: ["WARNING: ecb_mir_sazba_hypoteky is not_applicable for PL"],
      limitations: ["entsoe_bidding_zone_data_used", "bidding_zone_not_country"],
    };
    const out = resolveDataUsageSummary(ctx);
    expect(out.total).toBe(4);
    expect(out.forecastCount).toBe(1);
    expect(out.proxyCount).toBe(1);
    expect(out.text).toContain("Analýza vychází z 4 datových řad.");
    expect(out.text).not.toContain("WARNING");
    expect(out.text).not.toContain("bidding_zone");
  });

  it("bez řad nevrací nic (sekce se nezobrazí)", () => {
    expect(resolveDataUsageSummary({ series_summary: [] })).toBeNull();
    expect(resolveDataUsageSummary({})).toBeNull();
  });

  it("jedna řada má správné skloňování", () => {
    const out = resolveDataUsageSummary({ series_summary: [{ title: "A" }] });
    expect(out.text).toContain("z 1 datové řady");
  });
});
