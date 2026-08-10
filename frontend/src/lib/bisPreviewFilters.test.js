import {
  applyBisDimensionFiltersToQueryParams,
  extractGeoValuesFromDimensionFilters,
} from "./bisPreviewFilters";

describe("bisPreviewFilters", () => {
  test("maps TIME_PERIOD to startPeriod and endPeriod", () => {
    const out = applyBisDimensionFiltersToQueryParams({}, { TIME_PERIOD: "2023-Q1" });
    expect(out.startPeriod).toBe("2023-Q1");
    expect(out.endPeriod).toBe("2023-Q1");
  });

  test("extracts geo from REF_AREA", () => {
    expect(extractGeoValuesFromDimensionFilters({ REF_AREA: "CZ" })).toEqual(["CZ"]);
    expect(extractGeoValuesFromDimensionFilters({ geo: ["cz", "de"] })).toEqual(["CZ", "DE"]);
  });
});
