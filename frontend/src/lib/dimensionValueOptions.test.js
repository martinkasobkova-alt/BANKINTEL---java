import { describe, expect, it } from "vitest";
import { buildDimensionValueOptions, dimensionValueLabel } from "./dimensionValueOptions";

/** Náhled tak, jak ho staví PreviewResponseBuilder: ploché kódy + popisky vedle v metadatech. */
function preview({ values, sampleOptions, valueLabels, label } = {}) {
  return {
    extra_dimensions: [{ field: "geo", label: label || "geo", values: values || ["AT", "BE", "CZ"] }],
    metadata: {
      dimensions: {
        geo: {
          label: label || "Země",
          ...(sampleOptions ? { sample_options: sampleOptions } : {}),
          ...(valueLabels ? { value_labels: valueLabels } : {}),
        },
      },
    },
  };
}

describe("buildDimensionValueOptions", () => {
  it("spáruje kódy s čitelnými názvy ze sample_options", () => {
    const dims = buildDimensionValueOptions(
      preview({
        sampleOptions: [
          { code: "AT", label: "Rakousko" },
          { code: "BE", label: "Belgie" },
          { code: "CZ", label: "Česko" },
        ],
      })
    );

    expect(dims).toHaveLength(1);
    expect(dims[0].label).toBe("Země");
    expect(dims[0].values).toEqual([
      { code: "AT", label: "Rakousko" },
      { code: "BE", label: "Belgie" },
      { code: "CZ", label: "Česko" },
    ]);
  });

  it("vezme názvy i z value_labels, když sample_options chybí", () => {
    const dims = buildDimensionValueOptions(preview({ valueLabels: { AT: "Rakousko", BE: "Belgie", CZ: "Česko" } }));
    expect(dims[0].values.map((v) => v.label)).toEqual(["Rakousko", "Belgie", "Česko"]);
  });

  it("bez názvů nechá kódy — prázdné položky by nešly vybrat", () => {
    const dims = buildDimensionValueOptions(preview());
    expect(dims[0].values.map((v) => v.label)).toEqual(["AT", "BE", "CZ"]);
  });

  it("opakující se názvy rozliší, ať žádná hodnota nezmizí", () => {
    // Zdroje občas pošlou pro víc kódů týž název; bez rozlišení by šlo vybrat jen jeden.
    const dims = buildDimensionValueOptions(
      preview({
        values: ["TOTAL_A", "TOTAL_B"],
        valueLabels: { TOTAL_A: "Celkem", TOTAL_B: "Celkem" },
      })
    );

    const labels = dims[0].values.map((v) => v.label);
    expect(new Set(labels).size).toBe(2);
    expect(labels.every((l) => l.startsWith("Celkem"))).toBe(true);
  });

  it("jednohodnotové dimenze nenabízí — rozdělení podle nich dá jednu řadu", () => {
    const data = preview();
    data.extra_dimensions.push({ field: "freq", label: "freq", values: ["A"] });
    const dims = buildDimensionValueOptions(data);
    expect(dims.map((d) => d.field)).toEqual(["geo"]);
  });

  it("na náhled bez dimenzí nespadne", () => {
    expect(buildDimensionValueOptions(null)).toEqual([]);
    expect(buildDimensionValueOptions({})).toEqual([]);
    expect(buildDimensionValueOptions({ extra_dimensions: [{ values: ["A", "B"] }] })).toEqual([]);
  });
});

describe("dimensionValueLabel", () => {
  it("vrátí název, a když ho nezná, tak kód", () => {
    const dim = { values: [{ code: "CZ", label: "Česko" }] };
    expect(dimensionValueLabel(dim, "CZ")).toBe("Česko");
    expect(dimensionValueLabel(dim, "SK")).toBe("SK");
  });
});
