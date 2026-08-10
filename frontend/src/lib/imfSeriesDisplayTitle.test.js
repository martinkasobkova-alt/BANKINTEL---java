import { imfSeriesDisplayTitle, imfSeriesHasHumanTitle } from "./imfSeriesDisplayTitle";

describe("imfSeriesDisplayTitle", () => {
  it("prefers imf_indicator_name over code-like row.name", () => {
    const row = {
      name: "NGDP_R",
      imf_indicator: "NGDP_R",
      imf_indicator_name: "Gross domestic product (GDP), Constant prices, Domestic currency",
    };
    expect(imfSeriesDisplayTitle(row)).toContain("Gross domestic product");
  });

  it("falls back to code when no human label", () => {
    expect(imfSeriesDisplayTitle({ name: "NGDP_R", imf_indicator: "NGDP_R" })).toBe("NGDP_R");
  });

  it("detects human title", () => {
    expect(
      imfSeriesHasHumanTitle({
        name: "NGDP_R",
        imf_indicator: "NGDP_R",
        imf_indicator_name: "GDP constant prices",
      }),
    ).toBe(true);
    expect(imfSeriesHasHumanTitle({ name: "NGDP_R", imf_indicator: "NGDP_R" })).toBe(false);
  });
});
