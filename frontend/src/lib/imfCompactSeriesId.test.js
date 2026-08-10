import {
  isImfCompactSeriesPreviewable,
  isImfSdmx3SeriesPreviewable,
} from "./imfCompactSeriesId";

describe("imfCompactSeriesId", () => {
  it("accepts SDMX3 pipe set_id from browse tree", () => {
    expect(
      isImfSdmx3SeriesPreviewable("IMF|IMF.RES|WEO|9.0.0|CZE.TM_RPCH"),
    ).toBe(true);
    expect(isImfCompactSeriesPreviewable("IMF|IMF.RES|WEO|9.0.0|CZE.TM_RPCH")).toBe(true);
  });

  it("rejects incomplete pipe set_id", () => {
    expect(isImfSdmx3SeriesPreviewable("IMF|IMF.RES|WEO")).toBe(false);
  });

  it("accepts legacy CompactData shape", () => {
    expect(isImfCompactSeriesPreviewable("CPI/M.US.PCPI_IX")).toBe(true);
  });
});
