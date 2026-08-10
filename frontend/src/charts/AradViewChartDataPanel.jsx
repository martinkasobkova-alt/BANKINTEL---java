import React from "react";
import ChartDataTable from "./ChartDataTable";

const FACT_COLUMNS = [
  ["period_id", "period_id"],
  ["period_label", "period_label"],
  ["series_label", "series_label"],
  ["series_id", "series_id"],
  ["geo_label", "geo_label"],
  ["value", "value"],
  ["unit", "unit"],
  ["frequency", "frequency"],
  ["transformation", "transformation"],
];

function comparePeriodDesc(a, b) {
  const ap = String(a?.period_id || "");
  const bp = String(b?.period_id || "");
  const ay = Number((ap.match(/\d{4}/) || [])[0]);
  const by = Number((bp.match(/\d{4}/) || [])[0]);
  if (Number.isFinite(ay) && Number.isFinite(by) && ay !== by) return by - ay;
  if (ap !== bp) return bp.localeCompare(ap, "cs");
  return String(a?.series_label || a?.series_id || "").localeCompare(String(b?.series_label || b?.series_id || ""), "cs");
}

function enrichFactRows(tables) {
  const facts = Array.isArray(tables?.fact_values) ? tables.fact_values : [];
  const periods = new Map((tables?.dim_period || []).map((row) => [String(row.period_id || ""), row]));
  const series = new Map((tables?.dim_series || []).map((row) => [String(row.series_id || ""), row]));
  const geos = new Map((tables?.dim_geo || []).map((row) => [String(row.geo_id || ""), row]));
  const sources = new Map((tables?.dim_source || []).map((row) => [String(row.source_id || ""), row]));
  return facts.map((row) => {
    const p = periods.get(String(row.period_id || "")) || {};
    const s = series.get(String(row.series_id || "")) || {};
    const g = geos.get(String(row.geo_id || "")) || {};
    const src = sources.get(String(row.source_id || "")) || {};
    return {
      ...row,
      period_label: p.period_label || row.period_id || "",
      series_label: s.series_label || row.series_id || "",
      geo_label: g.geo_label || row.geo_id || "",
      source_label: src.source || row.source_id || "",
    };
  });
}

function OlapMiniTable({ columns, rows }) {
  const sample = [...(rows || [])].sort(comparePeriodDesc).slice(0, 40);
  if (!sample.length) return null;
  return (
    <div className="overflow-x-auto rounded-lg border border-violet-100 bg-white" data-testid="arad-view-olap-fact-table">
      <table className="w-full border-collapse text-[10.5px]">
        <thead>
          <tr className="border-b border-violet-100 bg-violet-50/80 text-violet-900">
            {columns.map(([key, label]) => (
              <th key={key} className="whitespace-nowrap px-2 py-1.5 text-left font-semibold">
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sample.map((row, idx) => (
            <tr key={row.fact_id || idx} className="border-b border-slate-100 last:border-0">
              {columns.map(([key]) => (
                <td
                  key={key}
                  className={`whitespace-nowrap px-2 py-1.5 tabular-nums text-slate-700 ${
                    key === "series_label" ? "max-w-[28rem] overflow-hidden text-ellipsis" : ""
                  }`}
                  title={row[key] == null ? "" : String(row[key])}
                >
                  {row[key] ?? ""}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function OlapCubePanel({ olapPackage }) {
  const tables = olapPackage?.tables || {};
  const factRows = Array.isArray(tables.fact_values) ? tables.fact_values : [];
  if (!factRows.length) return null;
  const enrichedFactRows = enrichFactRows(tables);
  const dimCards = [
    ["fact_values", factRows.length],
    ["dim_period", tables.dim_period?.length || 0],
    ["dim_series", tables.dim_series?.length || 0],
    ["dim_geo", tables.dim_geo?.length || 0],
    ["dim_source", tables.dim_source?.length || 0],
  ];
  return (
    <div className="space-y-2" data-testid="arad-view-olap-panel">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-violet-800">
            OLAP formát
          </div>
          <div className="text-[11px] text-slate-600">
            Pozorování jsou převedená do star-schema. Náhled je seřazený od nejnovějšího období.
          </div>
        </div>
        <div className="flex flex-wrap gap-1">
          {dimCards.map(([label, count]) => (
            <span
              key={label}
              className="rounded-full border border-violet-100 bg-violet-50 px-2 py-0.5 text-[10px] font-medium text-violet-900"
            >
              {label}: {count}
            </span>
          ))}
        </div>
      </div>
      <OlapMiniTable columns={FACT_COLUMNS} rows={enrichedFactRows} />
    </div>
  );
}

/**
 * Tabulka dat pod AradView grafem — defaultně skrytá, zapíná se tlačítkem Data.
 */
export default function AradViewChartDataPanel({ contract, compact = false, mode = "data", olapPackage = null, besideChart = false }) {
  if (!contract?.data?.length) return null;
  const isOlap = mode === "olap";
  return (
    <div
      className={`arad-view-data-panel min-h-0 overflow-auto bg-white/50 ${
        besideChart
          ? "w-[40%] min-w-[128px] max-w-[46%] shrink-0 border-l border-border/35 px-2 py-2"
          : "shrink-0 border-t border-border/30 px-2 py-2 max-h-[220px]"
      }`}
      data-testid="arad-view-data-panel"
    >
      {isOlap ? (
        <OlapCubePanel olapPackage={olapPackage} />
      ) : (
        <ChartDataTable contract={contract} maxRows={compact ? 8 : 15} />
      )}
    </div>
  );
}
