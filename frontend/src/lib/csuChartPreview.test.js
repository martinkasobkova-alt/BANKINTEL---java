import { buildCsuAradDataFromCatalogPreview } from "./csuChartPreview";
import { buildAradDataFromCatalogPreview } from "./mapCatalogPreviewToArad";

describe("csuChartPreview", () => {
  it("builds price time series for WCEN04T01-style preview", () => {
    const data = buildCsuAradDataFromCatalogPreview(
      {
        source: { source_type: "csu", name: "Kupní ceny" },
        group_field: "Ukazatel",
        indicators: [
          { id: "Indexy cen bytů", name: "Indexy cen bytů (meziroční index)" },
          { id: "Průměrná kupní ceny bytů (Kč za m2)", name: "Průměrná kupní ceny bytů (Kč za m2)" },
        ],
        rows: [
          { Ukazatel: "Indexy cen bytů (meziroční index)", Roky: "2019", Hodnota: "109", value: 109 },
          { Ukazatel: "Průměrná kupní ceny bytů (Kč za m2)", Roky: "2019", Hodnota: "39306", value: 39306 },
          { Ukazatel: "Indexy cen bytů (meziroční index)", Roky: "2020", Hodnota: "113", value: 113 },
          { Ukazatel: "Průměrná kupní ceny bytů (Kč za m2)", Roky: "2020", Hodnota: "44082", value: 44082 },
        ],
      },
      "Průměrné kupní ceny nemovitostí",
    );

    expect(data?.multi_series).toBeFalsy();
    expect(data?.rows).toHaveLength(2);
    expect(data?.rows[0].value).toBe(39306);
    expect(data?.rows[1].value).toBe(44082);
    expect(data?.rows.map((r) => r.period)).toEqual(["2019", "2020"]);
  });

  it("builds regional latest bar for CEN0402T03-style preview", () => {
    const data = buildCsuAradDataFromCatalogPreview(
      {
        source: { source_type: "csu" },
        group_field: "ÚZEMÍ-Kraj",
        rows: [
          {
            "Druh nemovitosti": "Byty [Kč/m2]",
            "ÚZEMÍ-Kraj": "Hlavní město Praha",
            "Počet obyvatel obce": "Celkem",
            Roky: "2023",
            Hodnota: "103693",
            value: 103693,
          },
          {
            "Druh nemovitosti": "Byty [Kč/m2]",
            "ÚZEMÍ-Kraj": "Jihomoravský kraj",
            "Počet obyvatel obce": "Celkem",
            Roky: "2023",
            Hodnota: "77827",
            value: 77827,
          },
          {
            "Druh nemovitosti": "Rodinné domy [Kč/m2]",
            "ÚZEMÍ-Kraj": "Hlavní město Praha",
            "Počet obyvatel obce": "Celkem",
            Roky: "2023",
            Hodnota: "111087",
            value: 111087,
          },
        ],
      },
      "Cena bytů za m² podle krajů",
    );

    expect(data?.chart_data_mode).toBe("latest");
    expect(data?.rows).toHaveLength(2);
    expect(data?.rows.find((r) => r.x.includes("Praha"))?.y).toBe(103693);
  });

  it("builds multi-series regional time pivot when multiple periods exist", () => {
    const data = buildCsuAradDataFromCatalogPreview(
      {
        source: { source_type: "csu" },
        group_field: "ÚZEMÍ-Kraj",
        rows: [
          { "Druh nemovitosti": "Byty [Kč/m2]", "ÚZEMÍ-Kraj": "Praha", "Tříleté období": "2019-2021", Hodnota: "80000", value: 80000 },
          { "Druh nemovitosti": "Byty [Kč/m2]", "ÚZEMÍ-Kraj": "Brno", "Tříleté období": "2019-2021", Hodnota: "50000", value: 50000 },
          { "Druh nemovitosti": "Byty [Kč/m2]", "ÚZEMÍ-Kraj": "Praha", "Tříleté období": "2020-2022", Hodnota: "85000", value: 85000 },
          { "Druh nemovitosti": "Byty [Kč/m2]", "ÚZEMÍ-Kraj": "Brno", "Tříleté období": "2020-2022", Hodnota: "52000", value: 52000 },
        ],
      },
      "Ceny bytů podle krajů",
    );

    expect(data?.multi_series).toBe(true);
    expect(data?.series).toHaveLength(2);
    expect(data?.rows).toHaveLength(2);
    expect(data?.rows[0].Praha).toBe(80000);
  });

  it("does not mix annual real-estate base index with year-on-year index", () => {
    const data = buildCsuAradDataFromCatalogPreview(
      {
        source: { source_type: "csu" },
        group_field: "Druh nemovitosti",
        rows: [
          {
            Ukazatel: "Index cen nemovitostí (%)",
            "Druh nemovitosti": "Byty",
            "Typ údaje": "Meziroční index",
            "Území-Kraj": "Pardubický kraj",
            Roky: "2024",
            Hodnota: "105.4",
            value: 105.4,
          },
          {
            Ukazatel: "Index cen nemovitostí (%)",
            "Druh nemovitosti": "Byty",
            "Typ údaje": "Bazický index (2010 = 100)",
            "Území-Kraj": "Pardubický kraj",
            Roky: "2024",
            Hodnota: "246.5",
            value: 246.5,
          },
          {
            Ukazatel: "Index cen nemovitostí (%)",
            "Druh nemovitosti": "Byty",
            "Typ údaje": "Bazický index (2015 = 100)",
            "Území-Kraj": "Pardubický kraj",
            Roky: "2024",
            Hodnota: "261.1",
            value: 261.1,
          },
          {
            Ukazatel: "Index cen nemovitostí (%)",
            "Druh nemovitosti": "Byty",
            "Typ údaje": "Bazický index (2015 = 100)",
            "Území-Kraj": "Kraj Vysočina",
            Roky: "2024",
            Hodnota: "282.6",
            value: 282.6,
          },
        ],
      },
      "Index cen nemovitostí (%)",
    );

    expect(data?.chart_data_mode).toBe("latest");
    expect(data?.rows.find((r) => r.x === "Pardubický kraj")?.y).toBe(261.1);
    expect(data?.rows.find((r) => r.x === "Kraj Vysočina")?.y).toBe(282.6);
  });

  it("builds a single-bar chart when CSU preview has only cumulative-month observation", () => {
    const data = buildCsuAradDataFromCatalogPreview(
      {
        source: { source_type: "csu" },
        group_field: "Ukazatel",
        selected_indicator: "Dokončené byty",
        rows: [
          {
            Ukazatel: "Dokončené byty",
            "Kumulace měsíců": "01-03 2026",
            Kraje: "Jihomoravský kraj",
            Hodnota: "920",
            value: 920,
          },
        ],
      },
      "Dokončené byty - Jihomoravský kraj",
    );

    expect(data?.chart_data_mode).toBe("latest");
    expect(data?.chart_type).toBe("bar");
    expect(data?.rows).toEqual([{ period: "01-03 2026", value: 920, x: "01-03 2026", y: 920 }]);
  });

  it("prefers broad base-year CPI index over short monthly rebasing", () => {
    const data = buildCsuAradDataFromCatalogPreview(
      {
        source: { source_type: "csu" },
        group_field: "Ukazatel",
        rows: [
          {
            Ukazatel: "Index spotřebitelských cen",
            "Typ indexu": "Bazický index (prosinec 2025 = 100)",
            Měsíce: "leden 2026",
            Hodnota: "100",
            value: 100,
          },
          {
            Ukazatel: "Index spotřebitelských cen",
            "Typ indexu": "Bazický index (2025 = 100)",
            Měsíce: "leden 2015",
            Hodnota: "74.3",
            value: 74.3,
          },
          {
            Ukazatel: "Index spotřebitelských cen",
            "Typ indexu": "Bazický index (2025 = 100)",
            Měsíce: "leden 2026",
            Hodnota: "101",
            value: 101,
          },
        ],
      },
      "CPI COICOP",
    );

    expect(data?.rows.map((row) => row.period)).toEqual(["leden 2015", "leden 2026"]);
    expect(data?.rows.map((row) => row.value)).toEqual([74.3, 101]);
  });
});

describe("mapCatalogPreviewToArad CSU integration", () => {
  it("routes csu preview through csu chart builder", () => {
    const data = buildAradDataFromCatalogPreview(
      {
        source: { source_type: "csu" },
        group_field: "Ukazatel",
        rows: [
          { Ukazatel: "Průměrná kupní ceny bytů (Kč za m2)", Roky: "2023", Hodnota: "60000", value: 60000 },
          { Ukazatel: "Průměrná kupní ceny bytů (Kč za m2)", Roky: "2024", Hodnota: "63521", value: 63521 },
        ],
      },
      "Kupní ceny bytů",
    );
    expect(data.rows).toHaveLength(2);
    expect(data.rows[1].value).toBe(63521);
  });
});
