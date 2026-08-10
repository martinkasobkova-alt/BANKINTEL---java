import { buildSourcePreviewParams } from "./previewRequestParams";

describe("buildSourcePreviewParams", () => {
  test("uses indicator_id for non-eurostat", () => {
    const out = buildSourcePreviewParams({
      sourceType: "arad",
      limit: 500,
      indicatorId: "ABC",
      groupField: "indicator_id",
    });
    expect(out).toEqual({ limit: 500, indicator_id: "ABC" });
  });

  test("uses dimension_filters for eurostat geo selection", () => {
    const out = buildSourcePreviewParams({
      sourceType: "eurostat",
      limit: 1000,
      indicatorId: "CZ",
      groupField: "geo",
    });
    expect(out.limit).toBe(1000);
    expect(out.dimension_filters).toBe('{"geo":"CZ"}');
    expect(out.indicator_id).toBeUndefined();
  });

  test("uses indicator_ids for multi-select", () => {
    const out = buildSourcePreviewParams({
      sourceType: "eurostat",
      indicatorIds: ["CZ", "NL", "CZ", "PL"],
      limit: 1000,
      groupField: "geo",
    });
    expect(out).toEqual({ limit: 1000, indicator_ids: "CZ,NL,PL" });
  });

  test("prefers geo array for eurostat geo filtering", () => {
    const out = buildSourcePreviewParams({
      sourceType: "eurostat",
      limit: 1000,
      indicatorId: "SHOULD_NOT_BE_USED",
      indicatorIds: ["A", "B"],
      groupField: "geo",
      geoValues: ["CZ", "DE", "CZ"],
    });
    expect(out).toEqual({
      limit: 1000,
      dimension_filters: '{"geo":["CZ","DE"]}',
    });
    expect(out.indicator_id).toBeUndefined();
    expect(out.indicator_ids).toBeUndefined();
  });

  test("uses arbitrary eurostat dimension filters and never indicator_id", () => {
    const out = buildSourcePreviewParams({
      sourceType: "eurostat",
      limit: 500,
      indicatorId: "SHOULD_NOT_BE_USED",
      dimensionFilters: {
        geo: ["CZ", "DE"],
        coicop: "CP01",
        indicator_id: "BAD",
      },
    });

    expect(out).toEqual({
      limit: 500,
      dimension_filters: '{"geo":["CZ","DE"],"coicop":"CP01"}',
    });
    expect(out.indicator_id).toBeUndefined();
  });

  test("keeps non-eurostat dimension filters together with indicator_id", () => {
    const out = buildSourcePreviewParams({
      sourceType: "ecb",
      limit: 500,
      indicatorId: "SSI",
      dimensionFilters: {
        REF_AREA: ["LV", "DE"],
        FREQ: "A",
      },
    });

    expect(out).toEqual({
      limit: 500,
      indicator_id: "SSI",
      dimension_filters: '{"REF_AREA":["LV","DE"],"FREQ":"A"}',
    });
  });
});
