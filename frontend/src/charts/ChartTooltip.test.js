import { buildTooltipEntries } from "./ChartTooltip";

describe("buildTooltipEntries", () => {
  test("without catalog keeps only payload rows with values", () => {
    const entries = buildTooltipEntries(
      [
        { dataKey: "CZ", name: "Czechia", value: 10 },
        { dataKey: "DE", name: "Germany", value: null },
      ],
      { showTrendSeries: false, seriesCatalog: null }
    );
    expect(entries).toHaveLength(1);
    expect(entries[0].dataKey).toBe("CZ");
  });

  test("with catalog lists all series including missing values", () => {
    const entries = buildTooltipEntries(
      [{ dataKey: "AO", name: "Angola", value: 18.22, color: "#111" }],
      {
        showTrendSeries: false,
        seriesCatalog: [
          { key: "AO", name: "Angola", color: "#111" },
          { key: "AM", name: "Armenia", color: "#222" },
          { key: "BY", name: "Belarus", color: "#333" },
        ],
      }
    );
    expect(entries).toHaveLength(3);
    expect(entries.find((e) => e.dataKey === "AM")?.value).toBeNull();
    expect(entries[0].dataKey).toBe("AO");
  });
});
