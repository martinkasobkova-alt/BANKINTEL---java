import { buildCatalogDimension, mergeAvailableSplitDimensions } from "./catalogDimensions";

describe("mergeAvailableSplitDimensions", () => {
  test("merges config metadata with live API dimensions", () => {
    const merged = mergeAvailableSplitDimensions(
      {
        available_split_dimensions: [{ field: "ÚZEMÍ-KRAJ", values: ["Praha"] }],
      },
      {
        available_split_dimensions: [
          {
            field: "ÚZEMÍ-KRAJ",
            values: ["Jihomoravský kraj"],
          },
          {
            field: "Ukazatel",
            values: ["Byty", "Domy"],
          },
        ],
      }
    );
    expect(merged).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          field: "ÚZEMÍ-KRAJ",
          values: expect.arrayContaining(["Praha", "Jihomoravský kraj"]),
        }),
        expect.objectContaining({
          field: "Ukazatel",
          values: ["Byty", "Domy"],
        }),
      ])
    );
  });

  test("includes ECB REF_AREA from config when response is filtered", () => {
    const merged = mergeAvailableSplitDimensions(
      {
        catalog: "ecb",
        available_split_dimensions: [
          { field: "REF_AREA", values: ["CZ", "DE", "FR"] },
          { field: "FREQ", values: ["M", "A"] },
        ],
      },
      { rows: [{ x: "2024-01", y: 1 }] }
    );
    expect(merged.find((d) => d.field === "REF_AREA")?.values).toEqual(["CZ", "DE", "FR"]);
  });

  test("nenabídne chart_series_dim, kterou data nepotvrdí (národní sada bez krajů)", () => {
    // Reálný případ: widget má uloženo chart_series_dim=ÚZEMÍ-KRAJ, ale sada je národní
    // (jen Stát=Česko) → kraje v datech nejsou. Mrtvá dimenze se nesmí nabízet.
    const merged = mergeAvailableSplitDimensions(
      {
        available_split_dimensions: [
          { field: "Ukazatel", values: ["Byty", "Domy", "Index bytů", "Index domů"] },
          { field: "Území-Stát", values: ["Česko"] },
        ],
      },
      {
        source_type: "csu",
        chart_series_dim: "ÚZEMÍ-KRAJ",
        chart_series_mode: "multi",
      }
    );
    const fields = merged.map((d) => d.field);
    expect(fields).not.toContain("ÚZEMÍ-KRAJ");
    expect(fields).toContain("Ukazatel");
    expect(fields).not.toContain("Území-Stát"); // 1 hodnota → vyřazeno
  });

  test("ponechá chart_series_dim, když ji data reálně mají (krajská sada)", () => {
    const merged = mergeAvailableSplitDimensions(
      {
        available_split_dimensions: [
          { field: "ÚZEMÍ-KRAJ", values: ["Hlavní město Praha", "Jihomoravský kraj", "Zlínský kraj"] },
          { field: "Ukazatel", values: ["Byty", "Domy"] },
        ],
      },
      { source_type: "csu", chart_series_dim: "ÚZEMÍ-KRAJ", chart_series_mode: "multi" }
    );
    expect(merged.map((d) => d.field)).toContain("ÚZEMÍ-KRAJ");
  });
});

describe("výchozí hodnota dimenze", () => {
  // ČSÚ vrací CZ-COICOP abecedně; bez preference souhrnu naskočil graf na
  // „Alkoholické nápoje" proti ukazateli „Úhrn" a zbyl v něm jediný bod.
  const coicop = [
    "Alkoholické nápoje, tabák a narkotika",
    "Bydlení, voda, energie, paliva",
    "Doprava",
    "Úhrn",
    "Zdraví",
  ];

  test("souhrn vyhraje nad prvním v abecedě", () => {
    const dim = buildCatalogDimension("CZ-COICOP", { values: coicop });
    expect(dim.selected).toBe("Úhrn");
    expect(dim.default_value).toBe("Úhrn");
  });

  test("bez souhrnu zůstává první hodnota", () => {
    const dim = buildCatalogDimension("CZ-COICOP", { values: ["Doprava", "Zdraví"] });
    expect(dim.selected).toBe("Doprava");
  });

  test("výslovný výběr má přednost před souhrnem", () => {
    const dim = buildCatalogDimension("CZ-COICOP", { values: coicop }, "Doprava");
    expect(dim.selected).toBe("Doprava");
  });

  test("rozpozná i Celkem / Total", () => {
    expect(buildCatalogDimension("d", { values: ["Praha", "Celkem"] }).selected).toBe("Celkem");
    expect(buildCatalogDimension("d", { values: ["DE", "Total"] }).selected).toBe("Total");
  });

  test("prázdná dimenze nespadne", () => {
    expect(buildCatalogDimension("d", { values: [] }).selected).toBeNull();
  });
});
