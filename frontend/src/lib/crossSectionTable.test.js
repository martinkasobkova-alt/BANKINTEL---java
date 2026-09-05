/**
 * Průřezový režim („Srovnání hodnot") měl v tabulce pod grafem časovou řadu místo
 * kategorií — čísla neodpovídala sloupcům v grafu a chyběly popisky. Tenhle test hlídá
 * tvar mapování, které SourcePreview používá pro `crossSectionTable`.
 */

/** Stejná transformace jako v SourcePreview.jsx (crossSectionTable). */
function buildCrossSectionTable(crossSectionRows, dimField, valueField) {
  if (!Array.isArray(crossSectionRows) || crossSectionRows.length < 2) return null;
  const dimLabel = String(dimField || "").trim() || "kategorie";
  const valueLabel = String(valueField || "").trim() || "value";
  return {
    fields: [dimLabel, valueLabel],
    rows: crossSectionRows.map((r) => ({
      [dimLabel]: r?.x ?? "",
      [valueLabel]: r?.y ?? null,
    })),
  };
}

describe("tabulka v průřezovém režimu", () => {
  const rows = [
    { x: "Doprava", y: 134.6 },
    { x: "Zdraví", y: 153.7 },
    { x: "Úhrn", y: 155 },
  ];

  test("sloupce jsou kategorie a hodnota, ne období", () => {
    const t = buildCrossSectionTable(rows, "CZ-COICOP", "value");
    expect(t.fields).toEqual(["CZ-COICOP", "value"]);
    expect(t.rows[0]).toEqual({ "CZ-COICOP": "Doprava", value: 134.6 });
    expect(t.rows).toHaveLength(3);
  });

  test("popisek kategorie se neztratí", () => {
    const t = buildCrossSectionTable(rows, "CZ-COICOP", "value");
    expect(t.rows.map((r) => r["CZ-COICOP"])).toEqual(["Doprava", "Zdraví", "Úhrn"]);
  });

  test("bez názvu dimenze se použije náhrada", () => {
    expect(buildCrossSectionTable(rows, "", "").fields).toEqual(["kategorie", "value"]);
  });

  test("jediná kategorie není průřez — tabulka zůstane na časové řadě", () => {
    expect(buildCrossSectionTable([{ x: "Doprava", y: 1 }], "CZ-COICOP", "value")).toBeNull();
    expect(buildCrossSectionTable([], "CZ-COICOP", "value")).toBeNull();
  });
});
