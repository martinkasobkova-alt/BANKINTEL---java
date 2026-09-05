import {
  applyIndustryLinkedFilters,
  pairedCpaFromIndUse,
  pickPrimaryIndustryField,
} from "./sourcePreviewIndustryFilters";

describe("sourcePreviewIndustryFilters", () => {
  const dims = {
    cpa2_1: {
      sample_options: [
        { code: "CPA_T", label: "Total" },
        { code: "CPA_G45", label: "Motor vehicles" },
      ],
    },
    ceparema: {
      sample_options: [{ code: "TOT_CEPA", label: "Total" }],
    },
  };

  it("pairs CPA from ind_use", () => {
    expect(pairedCpaFromIndUse("G45", dims)).toBe("CPA_G45");
    expect(pairedCpaFromIndUse("T", dims)).toBe("CPA_T");
  });

  it("applies linked cpa2_1 when ind_use changes", () => {
    const out = applyIndustryLinkedFilters({ ind_use: "G45", geo: "CZ" }, dims, "naio_10_cp1620");
    expect(out.cpa2_1).toBe("CPA_G45");
    expect(out.geo).toBe("CZ");
  });

  it("defaults ind_use (and its paired cpa2_1) for naio_10_cp input-output tables when none chosen", () => {
    const out = applyIndustryLinkedFilters({ geo: "CZ" }, dims, "naio_10_cp1620");
    expect(out.ind_use).toBe("G45");
    expect(out.cpa2_1).toBe("CPA_G45");
  });

  it("applies the same default to the naio_10_pyp sibling family (previous year's prices)", () => {
    // Zivy nalez: naio_10_pyp* zrcadli naio_10_cp* tabulka za tabulkou (stejne kategorie,
    // jen v cenach predchoziho roku) a ma stejnou dimenzi ind_use/cpa2_1 (overeno primo proti
    // Eurostat API - naio_10_pyp1620 ma 121 hodnot ind_use, presne jako sesterky z naio_10_cp).
    // Puvodni kod defaultoval ind_use jen pro "naio_10_cp" predponu, tuhle sesterskou rodinu
    // vynechal.
    const out = applyIndustryLinkedFilters({ geo: "CZ" }, dims, "naio_10_pyp1620");
    expect(out.ind_use).toBe("G45");
    expect(out.cpa2_1).toBe("CPA_G45");
  });

  it("picks single primary industry field", () => {
    expect(
      pickPrimaryIndustryField(["ceparema", "nace_r2", "ind_use"], {
        datasetId: "sbs_env_dom_r2",
        availableDimensions: {
          nace_r2: { sample_options: [{ code: "C", label: "Manufacturing" }, { code: "B", label: "Mining" }] },
        },
      }),
    ).toBe("nace_r2");
    expect(
      pickPrimaryIndustryField(["ind_use", "cpa2_1"], {
        datasetId: "naio_10_cp1620",
        availableDimensions: {
          ind_use: { sample_options: [{ code: "G45", label: "Trade" }, { code: "T", label: "Total" }] },
        },
      }),
    ).toBe("ind_use");
  });
});
