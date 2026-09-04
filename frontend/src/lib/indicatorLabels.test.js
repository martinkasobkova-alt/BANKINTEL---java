import {
  addCleanIndicatorLabel,
  cleanIndicatorLabel,
  indicatorSelectOptions,
  resolveIndicatorLabel,
  withDistinctLabels,
} from "./indicatorLabels";

describe("popisky ukazatelů", () => {
  test("rozliší opakující se popisky odříznutím společné části id", () => {
    // Reálný případ: ARAD vrátil 22 řad se shodným popiskem „USD".
    const out = withDistinctLabels(
      [
        { id: "SDR01M01USD", name: "USD" },
        { id: "SDR01M02USD", name: "USD" },
        { id: "SDR01M11USD", name: "USD" },
      ],
      { getId: (i) => i.id, getLabel: (i) => i.name }
    );
    expect(out.map((e) => e.label)).toEqual(["USD · 01", "USD · 02", "USD · 11"]);
  });

  test("žádná řada se neztratí", () => {
    const items = Array.from({ length: 12 }, (_, i) => ({ id: `X${i}`, name: "Běžné ceny" }));
    const out = withDistinctLabels(items, { getId: (i) => i.id, getLabel: (i) => i.name });
    expect(out).toHaveLength(12);
    expect(new Set(out.map((e) => e.label)).size).toBe(12);
  });

  test("unikátní popisky zůstanou beze změny", () => {
    const out = withDistinctLabels(
      [{ id: "a", name: "Inflace" }, { id: "b", name: "Nezaměstnanost" }],
      { getId: (i) => i.id, getLabel: (i) => i.name }
    );
    expect(out.map((e) => e.label)).toEqual(["Inflace", "Nezaměstnanost"]);
  });

  test("jediná položka se nerozlišuje", () => {
    const out = withDistinctLabels([{ id: "a", name: "USD" }], { getId: (i) => i.id, getLabel: (i) => i.name });
    expect(out[0].label).toBe("USD");
  });

  test("shodná id spadnou na pořadové číslo", () => {
    const out = withDistinctLabels(
      [{ id: "same", name: "USD" }, { id: "same", name: "USD" }],
      { getId: (i) => i.id, getLabel: (i) => i.name }
    );
    expect(out.map((e) => e.label)).toEqual(["USD · 1", "USD · 2"]);
  });

  test("prázdný popisek nahradí rozlišovač", () => {
    const out = withDistinctLabels(
      [{ id: "AA1", name: "" }, { id: "AA2", name: "" }],
      { getId: (i) => i.id, getLabel: (i) => i.name }
    );
    expect(out.map((e) => e.label)).toEqual(["1", "2"]);
  });

  test("prázdný seznam nespadne", () => {
    expect(withDistinctLabels(null, { getId: (i) => i.id, getLabel: (i) => i.name })).toEqual([]);
  });
});

describe("resolveIndicatorLabel", () => {
  test("mapa ze zdroje má přednost před vlastním názvem", () => {
    expect(resolveIndicatorLabel({ id: "x", name: "USD" }, { x: "Devizové rezervy" }))
      .toBe("Devizové rezervy");
  });

  test("bez mapy se použije název", () => {
    expect(resolveIndicatorLabel({ id: "x", name: "USD" })).toBe("USD");
  });

  test("název shodný s id se nepoužije", () => {
    expect(resolveIndicatorLabel({ id: "x", name: "x" })).toBe("x");
  });

  test("rozbité kódování se zahodí", () => {
    expect(cleanIndicatorLabel("Bě�né ceny")).toBe("");
    expect(resolveIndicatorLabel({ id: "x", name: "Bě�né ceny" })).toBe("x");
  });
});

describe("addCleanIndicatorLabel", () => {
  test("nepřepíše dobrý popisek horším, ale rozbitý ano", () => {
    const out = {};
    addCleanIndicatorLabel(out, "x", "Inflace");
    addCleanIndicatorLabel(out, "x", "Jiný");
    expect(out.x).toBe("Inflace");

    const broken = { y: "Bě�né" };
    addCleanIndicatorLabel(broken, "y", "Běžné ceny");
    expect(broken.y).toBe("Běžné ceny");
  });

  test("popisek shodný s id se neukládá", () => {
    const out = {};
    addCleanIndicatorLabel(out, "x", "x");
    expect(out.x).toBeUndefined();
  });
});

describe("indicatorSelectOptions", () => {
  test("spojí obojí — mapu názvů i rozlišení duplicit", () => {
    const out = indicatorSelectOptions(
      [{ id: "A1", name: "USD" }, { id: "A2", name: "USD" }],
      { A1: "Běžné ceny" }
    );
    expect(out.map((e) => e.label)).toEqual(["Běžné ceny", "USD"]);
  });
});
