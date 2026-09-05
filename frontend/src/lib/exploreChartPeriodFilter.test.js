/**
 * Přepínač rozsahu grafu v Manager Exploreru musí filtrovat podle SKUTEČNÝCH kalendářních
 * měsíců, ne podle počtu posledních pozorování — jinak "12" u čtvrtletní řady znamenalo tři
 * roky a u roční řady rovnou 12 let, a dvě řady různé frekvence ve stejném grafu měly každá
 * jiné časové okno, i když chip sliboval jedno společné.
 */

import { CHART_PERIODS, filterChartRows, overallLatestChartDate } from "./exploreChartPeriodFilter";

function monthlyRows(fromYear, fromMonth, count) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    const total = fromYear * 12 + (fromMonth - 1) + i;
    const y = Math.floor(total / 12);
    const m = (total % 12) + 1;
    rows.push({ x: `${y}-${String(m).padStart(2, "0")}`, y: i });
  }
  return rows;
}

function quarterlyRows(fromYear, fromQ, count) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    const total = fromYear * 4 + (fromQ - 1) + i;
    const y = Math.floor(total / 4);
    const q = (total % 4) + 1;
    rows.push({ x: `${y}-Q${q}`, y: i });
  }
  return rows;
}

function annualRows(fromYear, count) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    rows.push({ x: String(fromYear + i), y: i });
  }
  return rows;
}

describe("filterChartRows", () => {
  test("měsíční řada s '12' vrátí posledních 12 měsíců (dřív totéž, kontrolní případ)", () => {
    const rows = monthlyRows(2024, 1, 24); // 2024-01 .. 2025-12
    const anchor = overallLatestChartDate([{ rows }]);
    const out = filterChartRows(rows, "12", anchor);
    expect(out).toHaveLength(12);
    expect(out[0].x).toBe("2025-01");
    expect(out.at(-1).x).toBe("2025-12");
  });

  test("čtvrtletní řada s '12' vrátí poslední 4 čtvrtletí (12 měsíců), ne posledních 12 čtvrtletí (3 roky)", () => {
    const rows = quarterlyRows(2023, 1, 12); // 2023-Q1 .. 2025-Q4
    const anchor = overallLatestChartDate([{ rows }]);
    const out = filterChartRows(rows, "12", anchor);
    expect(out).toHaveLength(4);
    expect(out.map((r) => r.x)).toEqual(["2025-Q1", "2025-Q2", "2025-Q3", "2025-Q4"]);
  });

  test("roční řada s '12' vrátí jen poslední rok, ne posledních 12 let", () => {
    const rows = annualRows(2014, 12); // 2014 .. 2025
    const anchor = overallLatestChartDate([{ rows }]);
    const out = filterChartRows(rows, "12", anchor);
    expect(out).toHaveLength(1);
    expect(out[0].x).toBe("2025");
  });

  test("'all' vrátí úplně všechno beze změny", () => {
    const rows = monthlyRows(2010, 1, 60);
    const anchor = overallLatestChartDate([{ rows }]);
    expect(filterChartRows(rows, "all", anchor)).toHaveLength(60);
  });

  test("nerozpoznané období se nezahazuje potichu", () => {
    const rows = [{ x: "nesmysl", y: 1 }, ...monthlyRows(2025, 1, 12)];
    const anchor = overallLatestChartDate([{ rows }]);
    const out = filterChartRows(rows, "12", anchor);
    expect(out.some((r) => r.x === "nesmysl")).toBe(true);
  });

  test("bez kotvy (anchorDate chybí) vrátí řadu beze změny místo pádu", () => {
    const rows = monthlyRows(2025, 1, 12);
    expect(filterChartRows(rows, "12", null)).toBe(rows);
  });
});

describe("overallLatestChartDate + filterChartRows spolu", () => {
  test("měsíční a čtvrtletní čára ve stejném grafu sdílí STEJNÉ okno, i když čtvrtletní data zaostávají", () => {
    const monthly = { rows: monthlyRows(2024, 1, 24) }; // .. až 2025-12
    const quarterly = { rows: quarterlyRows(2023, 1, 6) }; // .. jen do 2024-Q2 (zaostává)
    const anchor = overallLatestChartDate([monthly, quarterly]);

    // Kotva je nejnovější datum NAPŘÍČ oběma čarami, tedy 2025-12 z měsíční řady.
    expect(anchor.getFullYear()).toBe(2025);
    expect(anchor.getMonth()).toBe(11);

    const monthlyOut = filterChartRows(monthly.rows, "12", anchor);
    const quarterlyOut = filterChartRows(quarterly.rows, "12", anchor);

    // Čtvrtletní řada je celá starší než okno 2025-01..2025-12 (poslední bod 2024-Q2 = duben 2024)
    // - dřív by "posledních 12 čtvrtletí" vrátilo data, teď správně nic, protože v okně nic není.
    expect(monthlyOut).toHaveLength(12);
    expect(quarterlyOut).toHaveLength(0);
  });
});

describe("CHART_PERIODS", () => {
  test("popisky odpovídají skutečnému významu (měsíce, ne pozorování)", () => {
    const twelve = CHART_PERIODS.find((p) => p.id === "12");
    expect(twelve.title).toMatch(/měsíc/i);
  });
});
