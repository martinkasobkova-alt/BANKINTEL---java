import { applyData360GeoQueryParams } from "./exploreCompareGeo";

describe("exploreCompareGeo Data360 helpers", () => {
  test("applyData360GeoQueryParams passes geo without forcing REF_AREA", () => {
    const qp = applyData360GeoQueryParams({ DATABASE_ID: "WB_WDI" }, ["MA", "CZ"]);
    expect(qp.geo).toEqual(["MA", "CZ"]);
    expect(qp.REF_AREA).toBeUndefined();
    expect(qp.DATABASE_ID).toBe("WB_WDI");
  });

  test("applyData360GeoQueryParams leaves query unchanged without geo selection", () => {
    const qp = applyData360GeoQueryParams({ DATABASE_ID: "WB_WDI", INDICATOR: "X" }, []);
    expect(qp).toEqual({ DATABASE_ID: "WB_WDI", INDICATOR: "X" });
  });
});
