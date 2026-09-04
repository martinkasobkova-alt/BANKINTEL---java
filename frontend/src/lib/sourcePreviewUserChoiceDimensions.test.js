import {
  buildUserChoiceDimensions,
  formatUserChoiceOptionLabel,
  getUserChoiceDimensionLabel,
  isUserSelectableDimensionKey,
  resolveHumanValueLabel,
  userChoiceDimensionsHelpText,
  userChoiceDimensionsSectionTitle,
} from "./sourcePreviewUserChoiceDimensions";
import { pickPrimaryIndustryField } from "./sourcePreviewIndustryFilters";

describe("sourcePreviewUserChoiceDimensions", () => {
  it("detects nace_r2 for env/sbs datasets", () => {
    expect(
      isUserSelectableDimensionKey("nace_r2", { datasetId: "sbs_env_dom_r2", optionCount: 34 }),
    ).toBe(true);
    expect(
      isUserSelectableDimensionKey("nace_r2", { datasetId: "nama_10_gdp", optionCount: 34 }),
    ).toBe(true);
  });

  it("hides cpa2_1 and ceparema from user choice", () => {
    expect(isUserSelectableDimensionKey("cpa2_1", { optionCount: 10 })).toBe(false);
    expect(isUserSelectableDimensionKey("ceparema", { optionCount: 5 })).toBe(false);
  });

  it("exposes general source dimensions but not time/value fields", () => {
    expect(isUserSelectableDimensionKey("sector", { optionCount: 12 })).toBe(true);
    expect(isUserSelectableDimensionKey("ICP_ITEM", { optionCount: 20 })).toBe(true);
    expect(isUserSelectableDimensionKey("\u00dazem\u00ed-Kraj", { optionCount: 14 })).toBe(true);
    expect(isUserSelectableDimensionKey("indicator_id", { optionCount: 30 })).toBe(true);
    expect(isUserSelectableDimensionKey("TIME_PERIOD", { optionCount: 200 })).toBe(false);
    expect(isUserSelectableDimensionKey("M\u011bs\u00edce", { optionCount: 12 })).toBe(false);
    expect(isUserSelectableDimensionKey("Hodnota", { optionCount: 120 })).toBe(false);
    expect(isUserSelectableDimensionKey("OBS_VALUE", { optionCount: 200 })).toBe(false);
    expect(isUserSelectableDimensionKey("KEY", { optionCount: 200 })).toBe(false);
    expect(isUserSelectableDimensionKey("indicator_name", { optionCount: 30 })).toBe(false);
  });

  it("uses metadata or readable key labels for non-industry dimensions", () => {
    expect(getUserChoiceDimensionLabel("sector", { sector: { label: "Sector" } })).toBe("Sector");
    expect(getUserChoiceDimensionLabel("indicator_id", {})).toBe("Ukazatel");
    expect(getUserChoiceDimensionLabel("ICP_ITEM", {})).toBe("ICP ITEM");
  });

  it("prefers Czech human labels over codes", () => {
    expect(resolveHumanValueLabel("CEPA2", "CEPA2")).toBe("Čištění odpadních vod");
    expect(formatUserChoiceOptionLabel({ value: "CEPA1", label: "Ochrana ovzduší a klimatu" })).toBe(
      "Ochrana ovzduší a klimatu",
    );
  });

  it("returns only one industry dimension", () => {
    const items = buildUserChoiceDimensions(
      {
        ind_use: {
          sample_options: [
            { code: "T", label: "Total" },
            { code: "G45", label: "Trade" },
          ],
        },
        cpa2_1: {
          sample_options: [
            { code: "CPA_T", label: "Total CPA" },
            { code: "CPA_G45", label: "Cars" },
          ],
        },
      },
      {
        datasetId: "naio_10_cp1620",
        appliedFilters: { ind_use: "G45", cpa2_1: "CPA_G45" },
        selectableDimensions: [
          { field: "ind_use", options: [{ code: "G45", label: "Trade" }, { code: "T", label: "Total" }] },
          { field: "cpa2_1", options: [{ code: "CPA_G45", label: "Cars" }] },
        ],
      },
    );
    expect(items).toHaveLength(1);
    expect(items[0].field).toBe("ind_use");
    expect(items[0].label).toBe("Odvětví");
  });

  it("exposes nace_r2 for plain by-industry datasets, not just env/sbs ones", () => {
    // Živě zjištěno: "nama_10_a64_e" (Employment by detailed industry, NACE Rev.2) mělo
    // ve výběru zemi/ukazatel/jednotku, ale žádné odvětví - přestože nace_r2 v datasetu
    // reálně existuje se 96 hodnotami. isIndustryDimensionSelectable dřív nace_r2 povolovala
    // jen pro datasety odpovídající environmentálnímu vzoru (sbs_env/env_/cepa), takže tenhle
    // úplně běžný "podle odvětví" dataset spadl skrz.
    const items = buildUserChoiceDimensions(
      {
        nace_r2: {
          sample_options: [
            { code: "TOTAL", label: "Total" },
            { code: "C", label: "Manufacturing" },
          ],
        },
      },
      {
        datasetId: "nama_10_a64_e",
        appliedFilters: { nace_r2: "TOTAL" },
        selectableDimensions: [
          { field: "nace_r2", options: [{ code: "TOTAL", label: "Total" }, { code: "C", label: "Manufacturing" }] },
        ],
      },
    );
    expect(items).toHaveLength(1);
    expect(items[0].field).toBe("nace_r2");
    expect(items[0].label).toBe("Odvětví");
  });

  it("picks nace over ceparema for sbs_env", () => {
    const items = buildUserChoiceDimensions(
      {
        nace_r2: {
          sample_options: [
            { code: "C", label: "Manufacturing" },
            { code: "B", label: "Mining" },
          ],
        },
        ceparema: {
          sample_options: [
            { code: "TOT_CEPA", label: "Total" },
            { code: "CEPA1", label: "Air" },
          ],
        },
      },
      {
        datasetId: "sbs_env_dom_r2",
        appliedFilters: { nace_r2: "C", ceparema: "TOT_CEPA" },
        selectableDimensions: [
          { field: "ceparema", options: [{ code: "TOT_CEPA", label: "Total" }] },
          { field: "nace_r2", options: [{ code: "C", label: "Manufacturing" }, { code: "B", label: "Mining" }] },
        ],
      },
    );
    expect(items).toHaveLength(1);
    expect(items[0].field).toBe("nace_r2");
  });

  it("exposes mot_nrg for road_eqs_carpda", () => {
    expect(isUserSelectableDimensionKey("mot_nrg", { optionCount: 8 })).toBe(true);
    const items = buildUserChoiceDimensions(
      {},
      {
        datasetId: "road_eqs_carpda",
        appliedFilters: { mot_nrg: "TOTAL" },
        selectableDimensions: [
          {
            field: "mot_nrg",
            label: "Type of motor energy",
            options: [
              { code: "TOTAL", label: "Total" },
              { code: "PETROL", label: "Petrol" },
              { code: "DIESEL", label: "Diesel" },
              { code: "ELECTRIC", label: "Electric" },
            ],
          },
        ],
      },
    );
    expect(items).toHaveLength(1);
    expect(items[0].field).toBe("mot_nrg");
    expect(items[0].label).toBe("Typ pohonu");
    expect(items[0].selected).toBe("TOTAL");
    expect(userChoiceDimensionsSectionTitle(items)).toBe("Dimenze");
    expect(userChoiceDimensionsHelpText(items)).toMatch(/typ pohonu/i);
  });

  it("exposes mot_nrg and engine for road_eqs_carmot", () => {
    const items = buildUserChoiceDimensions(
      {},
      {
        datasetId: "road_eqs_carmot",
        appliedFilters: { mot_nrg: "PETROL", engine: "TOTAL" },
        selectableDimensions: [
          {
            field: "mot_nrg",
            label: "Type of motor energy",
            options: [
              { code: "TOTAL", label: "Total" },
              { code: "PETROL", label: "Petrol" },
              { code: "DIESEL", label: "Diesel" },
            ],
          },
          {
            field: "engine",
            label: "Engine size",
            options: [
              { code: "TOTAL", label: "Total" },
              { code: "LE1000", label: "<=1000 cm3" },
              { code: "GT1000LE1400", label: ">1000 <=1400 cm3" },
            ],
          },
        ],
      },
    );
    expect(items.map((item) => item.field)).toEqual(["engine", "mot_nrg"]);
    expect(userChoiceDimensionsSectionTitle(items)).toBe("Dimenze");
  });
});

describe("pickPrimaryIndustryField", () => {
  it("prefers nace_r2", () => {
    expect(
      pickPrimaryIndustryField(["ind_use", "nace_r2"], {
        datasetId: "sbs_env_dom_r2",
        availableDimensions: {
          nace_r2: { sample_options: [{ code: "C", label: "X" }, { code: "B", label: "Y" }] },
        },
      }),
    ).toBe("nace_r2");
  });
});
