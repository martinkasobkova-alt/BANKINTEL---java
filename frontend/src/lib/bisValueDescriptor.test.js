import { inferBisValueDescriptorFromRows, resolveCatalogValueDescriptor } from "./bisValueDescriptor";

describe("bisValueDescriptor", () => {
  test("resolveCatalogValueDescriptor prefers row then preview metadata", () => {
    expect(
      resolveCatalogValueDescriptor({
        fromRow: "Tisíce · v americké dolary",
        fromPreview: "z API",
        rows: [],
      })
    ).toBe("Tisíce · v americké dolary");
    expect(
      resolveCatalogValueDescriptor({
        fromRow: "",
        fromPreview: "Držby měn bankami · tisíce",
        rows: [],
      })
    ).toBe("Držby měn bankami · tisíce");
  });

  test("inferBisValueDescriptorFromRows reads UNIT_MULT", () => {
    const text = inferBisValueDescriptorFromRows(
      [{ UNIT_MULT: "3", OBS_VALUE: 100 }],
      ["UNIT_MULT", "OBS_VALUE"]
    );
    expect(text).toContain("Tisíce");
  });
});
