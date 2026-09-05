import { parseChartPeriod } from "@/lib/chartPeriodParse";

/**
 * Přepínač rozsahu grafu v Manager Exploreru. `count` je počet MĚSÍCŮ, ne posledních
 * pozorování — dřív dělal `filterChartRows` `rows.slice(-count)`, takže „36" u měsíční řady
 * bylo skutečně 36 měsíců, ale u čtvrtletní devět let a u roční 36 let, a ve sdruženém grafu
 * (víc řad různé frekvence) měla každá čára jiné časové okno, i když chip sliboval jedno
 * společné. Okno se teď počítá kalendářně a je stejné pro všechny čáry v grafu.
 */
export const CHART_PERIODS = [
  { id: "12", label: "12", count: 12, title: "Posledních 12 měsíců" },
  { id: "36", label: "36", count: 36, title: "Posledních 36 měsíců" },
  { id: "120", label: "120", count: 120, title: "Posledních 120 měsíců" },
  { id: "all", label: "Vše", count: null, title: "Celá dostupná historie" },
];

/** Nejnovější rozpoznatelné datum napříč všemi čarami — společná kotva okna pro celý graf. */
export function overallLatestChartDate(lines) {
  let latest = null;
  for (const line of lines || []) {
    for (const row of line.rows || []) {
      const d = parseChartPeriod(row.x);
      if (d && (!latest || d > latest)) latest = d;
    }
  }
  return latest;
}

export function filterChartRows(rows, periodId, anchorDate) {
  const period = CHART_PERIODS.find((p) => p.id === periodId);
  if (!period || period.count == null || !anchorDate) return rows;
  // -1, aby "12" bylo posledních 12 kalendářních měsíců VČETNĚ měsíce kotvy, ne 13.
  const cutoff = new Date(anchorDate.getFullYear(), anchorDate.getMonth() - period.count + 1, 1);
  return rows.filter((row) => {
    const d = parseChartPeriod(row.x);
    // Nerozpoznané období radši necháme být, než abychom ho tiše zahodili.
    return d ? d >= cutoff : true;
  });
}
