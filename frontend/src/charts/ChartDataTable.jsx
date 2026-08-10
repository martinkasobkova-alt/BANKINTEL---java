import React from "react";
import { contractToLongRows } from "./chartDataContract";
import { CHART_SIZE_VARIANTS } from "./chartTypes";

const DISPLAY_COLUMNS = [
  { key: "period_label", label: "Období" },
  { key: "series_label", label: "Řada" },
  { key: "value_raw", label: "Hodnota" },
  { key: "unit", label: "Jednotka" },
  { key: "transformation", label: "Transformace" },
];

/**
 * Tabulka pod grafem — dashboard styl, sans-serif.
 */
export default function ChartDataTable({ contract, maxRows, size = CHART_SIZE_VARIANTS.STANDARD }) {
  const sizePreset = size === CHART_SIZE_VARIANTS.COMPACT ? 6 : maxRows ?? 10;
  const rows = (contract ? contractToLongRows(contract) : []).slice(0, sizePreset);

  if (!rows.length) return null;

  return (
    <div className="chart-data-table overflow-x-auto" data-testid="chart-data-table">
      <table className="w-full text-[11px] border-collapse">
        <thead>
          <tr className="text-slate-500 border-b border-border/50">
            {DISPLAY_COLUMNS.map((col) => (
              <th key={col.key} className="text-left py-1.5 pr-3 font-medium">
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, idx) => (
            <tr key={`${row.period}-${row.series_id}-${idx}`} className="border-b border-border/30 last:border-0">
              {DISPLAY_COLUMNS.map((col) => (
                <td key={col.key} className="py-1.5 pr-3 text-slate-700 tabular-nums">
                  {row[col.key] ?? ""}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
