import {
  buildGeoMapRowsFromChartRows,
  buildGeoMapRowsFromMultiSeries,
  geographyIso2,
  multiSeriesLooksGeographic,
  rowLabelToIso,
} from "./chartGeoMapData";

describe("rowLabelToIso", () => {
  it("parses country names embedded in long series titles", () => {
    expect(rowLabelToIso("HICP — housing (CZ)")).toBe("CZ");
    expect(rowLabelToIso("Inflation - Germany")).toBe("DE");
    expect(rowLabelToIso("HICP — Česko")).toBe("CZ");
  });

  it("resolves world-atlas numeric country ids", () => {
    expect(geographyIso2({ id: "276", properties: { name: "Germany" } })).toBe("DE");
    expect(geographyIso2({ id: "203", properties: { name: "Czechia" } })).toBe("CZ");
    expect(geographyIso2({ id: "352", properties: { name: "Iceland" } })).toBe("IS");
  });
});

describe("multiSeriesLooksGeographic", () => {
  it("returns true for at least two country series", () => {
    expect(
      multiSeriesLooksGeographic([
        { key: "s0", name: "Česko" },
        { key: "s1", name: "Německo" },
        { key: "s2", name: "Polsko" },
      ]),
    ).toBe(true);
  });

  it("returns false for non-geographic series names", () => {
    expect(
      multiSeriesLooksGeographic([
        { key: "s0", name: "Potraviny" },
        { key: "s1", name: "Bydlení" },
      ]),
    ).toBe(false);
  });
});

describe("buildGeoMapRowsFromMultiSeries", () => {
  it("maps latest period values per country series", () => {
    const rows = buildGeoMapRowsFromMultiSeries(
      [
        { x: "2023", s0: 100, s1: 110 },
        { x: "2024", s0: 102, s1: 115 },
      ],
      [
        { key: "s0", name: "CZ" },
        { key: "s1", name: "DE" },
      ],
    );
    expect(rows).toHaveLength(2);
    expect(rowLabelToIso(rows[0].x)).toBe("CZ");
    expect(rows[0].y).toBe(102);
    expect(rowLabelToIso(rows[1].x)).toBe("DE");
    expect(rows[1].y).toBe(115);
  });

  it("uses geo hints when series names are opaque", () => {
    const rows = buildGeoMapRowsFromMultiSeries(
      [{ x: "2024", s0: 100, s1: 110 }],
      [
        { key: "s0", name: "Primary" },
        { key: "s1", name: "Compare" },
      ],
      { geoHints: ["CZ", "DE"] },
    );
    expect(rows).toHaveLength(2);
    expect(rowLabelToIso(rows[0].x)).toBe("CZ");
    expect(rowLabelToIso(rows[1].x)).toBe("DE");
  });

  it("resolves ISO from series id when name is widget title", () => {
    const rows = buildGeoMapRowsFromMultiSeries(
      [{ x: "2024-Q4", s0: 5.6, s1: 7.1 }],
      [
        { key: "s0", id: "DE", name: "ROE evropských bank" },
        { key: "s1", id: "FR", name: "ROE evropských bank" },
      ],
    );
    expect(rows).toHaveLength(2);
    expect(rows[0].geo).toBe("DE");
    expect(rows[1].geo).toBe("FR");
  });
});

describe("buildGeoMapRowsFromChartRows", () => {
  it("maps single-series latest rows by country label on x axis", () => {
    const rows = buildGeoMapRowsFromChartRows(
      [
        { x: "Německo", y: 7.2 },
        { x: "CZ", y: 5.6 },
      ],
      null,
    );
    expect(rows).toHaveLength(2);
    expect(rows.find((r) => r.geo === "DE")?.y).toBe(7.2);
    expect(rows.find((r) => r.geo === "CZ")?.y).toBe(5.6);
  });
});
