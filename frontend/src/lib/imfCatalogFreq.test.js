import {
  imfFreqFromSetId,
  imfFreqOptionsFromRow,
  resolveImfFreqOptions,
} from "./imfCatalogFreq";

describe("imfCatalogFreq", () => {
  it("parses annual frequency from IMF set_id suffix", () => {
    expect(imfFreqFromSetId("IMF|IMF.RES|WEO|7.0.0|CYP.NGDP_RPCH.A")).toBe("A");
  });

  it("returns only row frequency before preview when WEO is annual", () => {
    const opts = imfFreqOptionsFromRow({
      set_id: "IMF|IMF.RES|WEO|7.0.0|CYP.NGDP_RPCH.A",
      query_params: { imf_frekvence: "A" },
    });
    expect(opts).toEqual([{ frekvence: "A", frekvence_label: "Ročně" }]);
  });

  it("uses preview API frequency options when loaded", () => {
    const opts = resolveImfFreqOptions({
      previewData: {
        imf_available_frequencies: ["A"],
        imf_frequency_options: [{ frekvence: "A", frekvence_label: "Ročně" }],
      },
      row: { set_id: "IMF|IMF.RES|WEO|7.0.0|CYP.NGDP_RPCH.A" },
    });
    expect(opts).toHaveLength(1);
    expect(opts[0].frekvence).toBe("A");
  });

  it("returns multiple options when API reports M and Q", () => {
    const opts = resolveImfFreqOptions({
      previewData: {
        imf_available_frequencies: ["M", "Q"],
      },
      row: null,
    });
    expect(opts.map((o) => o.frekvence)).toEqual(["M", "Q"]);
  });
});
