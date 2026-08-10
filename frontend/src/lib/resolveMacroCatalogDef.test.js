import { CATALOGS } from "@/lib/catalogDefinitions";
import {
  isMacroComparisonPreviewRow,
  resolveMacroCatalogDef,
} from "@/lib/resolveMacroCatalogDef";

describe("resolveMacroCatalogDef", () => {
  const catalogById = new Map(CATALOGS.map((c) => [c.id, c]));

  test("resolves ecb alias to ecb2", () => {
    expect(resolveMacroCatalogDef(catalogById, { catalog_id: "ecb" })?.id).toBe("ecb2");
  });

  test("macro comparison row is previewable without pipe set_id", () => {
    const row = {
      topic_id: "populace",
      catalog_id: "data360",
      set_id: "SP.POP.TOTL",
      geo: "DE",
    };
    expect(isMacroComparisonPreviewRow(row)).toBe(true);
    expect(resolveMacroCatalogDef(catalogById, row)?.id).toBe("data360");
  });
});
