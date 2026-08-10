import {
  applyEurostatCountrySelection,
  extractCountryCodesFromFilters,
  filterFigaroOriginCountryOptions,
  formatFigaroCountryContext,
  isFigaroLikeDataset,
  pickDefaultFigaroOriginCountries,
  resolvePrimaryCountryDimensionKey,
} from "./sourcePreviewCountry";

describe("sourcePreviewCountry", () => {
  test("resolvePrimaryCountryDimensionKey prefers geo when present", () => {
    expect(resolvePrimaryCountryDimensionKey({ geo: { size: 10 }, c_orig: { size: 5 } })).toBe("geo");
  });

  test("resolvePrimaryCountryDimensionKey uses c_orig when no geo", () => {
    expect(resolvePrimaryCountryDimensionKey({ c_orig: { size: 5 }, c_dest: { size: 5 } })).toBe("c_orig");
  });

  test("applyEurostatCountrySelection writes c_orig and fixes c_dest to EU", () => {
    const qp = applyEurostatCountrySelection(
      {},
      ["DE"],
      {
        c_orig: {
          sample_options: [
            { code: "CZ", label: "Česko" },
            { code: "DE", label: "Německo" },
          ],
        },
        c_dest: {
          sample_options: [
            { code: "EU27_2020", label: "EU27" },
            { code: "CZ", label: "Česko" },
          ],
        },
      },
    );
    expect(qp.c_orig).toBe("DE");
    expect(qp.c_dest).toBe("EU27_2020");
    expect(qp.geo).toBeUndefined();
  });

  test("extractCountryCodesFromFilters reads c_orig", () => {
    expect(extractCountryCodesFromFilters({ c_orig: "DE", freq: "A" })).toEqual(["DE"]);
  });

  test("formatFigaroCountryContext builds labels", () => {
    const ctx = formatFigaroCountryContext(
      { c_orig: "CZ", c_dest: "EU27_2020" },
      {
        c_orig: "Country of origin: Czechia",
        c_dest: "Country of destination: European Union - 27 countries (from 2020)",
      },
      { c_orig: {}, c_dest: {} },
      "c_orig",
    );
    expect(ctx.isFigaro).toBe(true);
    expect(ctx.primaryLabel).toContain("Czechia");
    expect(ctx.destLabel).toMatch(/EU|unie/i);
  });

  test("filterFigaroOriginCountryOptions drops world aggregates and non-EU", () => {
    const filtered = filterFigaroOriginCountryOptions([
      { value: "WRL_REST", label: "All countries of the world", rowCount: 0 },
      { value: "AR", label: "Argentina", rowCount: 0 },
      { value: "AU", label: "Australia", rowCount: 0 },
      { value: "CZ", label: "Czechia", rowCount: 0 },
      { value: "DE", label: "Germany", rowCount: 0 },
    ]);
    expect(filtered.map((o) => o.value)).toEqual(["CZ", "DE"]);
    expect(pickDefaultFigaroOriginCountries(filtered, 1)).toEqual(["CZ"]);
  });

  test("isFigaroLikeDataset detects env_ac", () => {
    expect(isFigaroLikeDataset({ c_orig: {}, c_dest: {} }, "env_ac_ghgfp")).toBe(true);
    expect(isFigaroLikeDataset({ geo: {}, c_orig: {} }, "naio_10_x")).toBe(false);
  });

});
