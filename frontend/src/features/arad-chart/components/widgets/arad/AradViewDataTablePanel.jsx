import React from "react";
import { fmtCompact, fmtNumber, fmtPeriodLabel, parseNumber } from "@/lib/format";

export default function AradViewDataTablePanel({
  besideChart = false,
  fsExpand,
  chartCompact,
  tableBodyHeight,
  chartTableTransposed,
  isMultiSeries,
  chartTheme,
  unit,
  latestDataMode,
  seriesList,
  seriesTableLabels,
  tableRows,
}) {
    return (
      <div
        className={`min-h-0 overflow-x-auto overflow-y-auto ${
          besideChart
            ? "w-[40%] min-w-[128px] max-w-[46%] shrink-0 border-l border-border/35 bg-white/50"
            : "flex-1 w-full"
        }`}
        style={besideChart ? undefined : { maxHeight: fsExpand || chartCompact ? undefined : tableBodyHeight }}
        data-testid={besideChart ? "arad-view-table-split" : "arad-view-table-full"}
      >
        <table
          className={`data-table w-full border-separate border-spacing-0 [&_tbody_td]:align-middle [&_thead_th]:align-bottom [&_thead_th]:border-r [&_tbody_td]:border-r [&_thead_th:last-child]:border-r-0 [&_tbody_td:last-child]:border-r-0 [&_thead_th]:border-border/55 [&_tbody_td]:border-border/35 ${
            chartTableTransposed && isMultiSeries
              ? "[&_tbody_td:first-child]:whitespace-normal [&_thead_th:first-child]:whitespace-normal [&_tbody_td:not(:first-child)]:whitespace-nowrap [&_thead_th:not(:first-child)]:whitespace-nowrap [&_thead_th:first-child]:sticky [&_thead_th:first-child]:left-0 [&_thead_th:first-child]:z-30 [&_tbody_td:first-child]:sticky [&_tbody_td:first-child]:left-0 [&_tbody_td:first-child]:z-10 [&_tbody_td:first-child]:shadow-[1px_0_0_0_hsl(var(--foreground)/0.07)] "
              : "[&_tbody_td]:whitespace-nowrap "
          }${
            chartCompact
              ? "text-[9px] [&_thead_th]:!px-1.5 [&_thead_th]:!py-1 [&_tbody_td]:!px-1.5 [&_tbody_td]:!py-1"
              : "text-[11px] [&_thead_th]:!px-2.5 [&_thead_th]:!py-1.5 [&_tbody_td]:!px-2.5 [&_tbody_td]:!py-1.5"
          }`}
        >
          <thead className="sticky top-0 z-20" style={{ background: chartTheme.tableHeaderBg }}>
            <tr>
              {isMultiSeries && chartTableTransposed ? (
                <>
                  <th
                    className="text-left min-w-[10rem] max-w-[18rem] whitespace-normal break-words leading-tight"
                    style={{ background: chartTheme.tableHeaderBg, borderColor: chartTheme.border, color: chartTheme.accent }}
                  >
                    Ukazatel{unit ? ` (${unit})` : ""}
                  </th>
                  {tableRows.map((r, ti) => (
                    <th
                      key={`${r.period}-${ti}`}
                      className="num min-w-[5.25rem]"
                      style={{ background: chartTheme.tableHeaderBg, borderColor: chartTheme.border, color: chartTheme.accent }}
                    >
                      {fmtPeriodLabel(r.period)}
                    </th>
                  ))}
                </>
              ) : (
                <>
                  <th className="text-left min-w-[5.5rem]" style={{ background: chartTheme.tableHeaderBg, borderColor: chartTheme.border, color: chartTheme.accent }}>
                    {isMultiSeries ? "Období" : latestDataMode ? "Položka / řada" : "Období"}
                  </th>
                  {isMultiSeries ? (
                    seriesList.map((s, idx) => {
                      const full = seriesTableLabels.fullLabels[idx] ?? "";
                      const short = seriesTableLabels.displayLabels[idx] ?? full;
                      return (
                        <th
                          key={s.key || idx}
                          className="num min-w-[9.5rem] max-w-[18rem] !whitespace-normal break-words leading-tight"
                          title={full !== short ? full : undefined}
                          style={{ background: chartTheme.tableHeaderBg, borderColor: chartTheme.border, color: chartTheme.accent }}
                        >
                          <span className="mb-0.5 block text-[8px] uppercase tracking-[0.08em] text-slate-400">Řada {idx + 1}:</span>
                          <span className="block">{short}{unit ? ` (${unit})` : ""}</span>
                        </th>
                      );
                    })
                  ) : (
                    <>
                      <th className="num min-w-[6.5rem]" style={{ background: chartTheme.tableHeaderBg, borderColor: chartTheme.border, color: chartTheme.accent }}>
                        Hodnota{unit ? ` (${unit})` : ""}
                      </th>
                      <th className="num min-w-[6.5rem]" style={{ background: chartTheme.tableHeaderBg, borderColor: chartTheme.border, color: chartTheme.accent }}>
                        Kompaktně
                      </th>
                    </>
                  )}
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {isMultiSeries
              ? chartTableTransposed
                ? seriesList.map((s, si) => {
                    const full = seriesTableLabels.fullLabels[si] ?? "";
                    const short = seriesTableLabels.displayLabels[si] ?? full;
                    return (
                      <tr key={s.key || si}>
                        <td
                          className="text-left align-top min-w-[10rem] max-w-[18rem] whitespace-normal break-words leading-snug"
                          style={{ background: "hsl(var(--card))", borderColor: chartTheme.border }}
                          title={full !== short ? full : undefined}
                        >
                          <span className="text-slate-800">{short}</span>
                        </td>
                        {tableRows.map((r, ri) => {
                          const n = s.key ? parseNumber(r[s.key]) : null;
                          return (
                            <td key={`${s.key || si}-${r.period}-${ri}`} className="num mono">
                              {n !== null ? fmtNumber(n) : "—"}
                            </td>
                          );
                        })}
                      </tr>
                    );
                  })
                : tableRows.map((r, i) => (
                    <tr key={i}>
                      <td className="mono">{fmtPeriodLabel(r.period)}</td>
                      {seriesList.map((s, j) => {
                        const n = s.key ? parseNumber(r[s.key]) : null;
                        return (
                          <td key={s.key || j} className="num mono">
                            {n !== null ? fmtNumber(n) : "—"}
                          </td>
                        );
                      })}
                    </tr>
                  ))
              : tableRows.map((r, i) => {
                  const nv = parseNumber(r.value);
                  return (
                    <tr key={i}>
                      <td className="mono">{fmtPeriodLabel(r.period)}</td>
                      <td className="num mono">{nv !== null ? fmtNumber(nv) : "—"}</td>
                      <td className="num mono text-slate-500">
                        {nv !== null ? fmtCompact(nv) : "—"}
                        {unit ? ` ${unit}` : ""}
                      </td>
                    </tr>
                  );
                })}
          </tbody>
        </table>
      </div>
    );
  }
