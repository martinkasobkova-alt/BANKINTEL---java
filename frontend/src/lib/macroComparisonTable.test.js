import {
  comparisonCellDirection,
  extractPreviewObservationPair,
} from "./macroComparisonTable";

describe("extractPreviewObservationPair", () => {
  it("returns direction up when latest value rose", () => {
    const pair = extractPreviewObservationPair({
      rows: [
        { date: "2023", value: 2 },
        { date: "2024", value: 3 },
      ],
    });
    expect(pair.value).toBe(3);
    expect(pair.previousValue).toBe(2);
    expect(pair.direction).toBe("up");
  });

  it("returns direction down when latest value fell", () => {
    const pair = extractPreviewObservationPair({
      rows: [
        { date: "2023", value: 5 },
        { date: "2024", value: 4.2 },
      ],
    });
    expect(pair.direction).toBe("down");
  });
});

describe("comparisonCellDirection", () => {
  it("maps up and down for coloring", () => {
    expect(comparisonCellDirection({ direction: "up", value: 1 })).toBe("up");
    expect(comparisonCellDirection({ direction: "down", value: 1 })).toBe("down");
    expect(comparisonCellDirection({ value: null })).toBe("muted");
  });
});
