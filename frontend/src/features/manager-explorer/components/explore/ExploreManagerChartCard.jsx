import React, { useMemo } from "react";
import ExploreReportChart from "@/components/explore/ExploreReportChart";
import { mergeChartLinesForExplore } from "@/lib/chartSeriesMerge";

export default function ExploreManagerChartCard({ renderPayload, compact = false }) {
  const payload = renderPayload || {};
  const chartPlot = useMemo(() => {
    const lines = (payload.series || []).map((row) => ({
      name: row.label,
      rows: row.values,
      axis: row.axis === "right" ? "right" : "left",
    }));
    return mergeChartLinesForExplore(lines);
  }, [payload.series]);

  if (!chartPlot.merged.length || !chartPlot.series.length) return null;

  const leftUnit = payload.series?.find((s) => s.axis !== "right")?.unit || "";
  const rightUnit = payload.series?.find((s) => s.axis === "right")?.unit || "";
  const note = [...(payload.data_quality_notes || [])].filter(Boolean).join(" · ");

  return (
    <div
      className="explore-dashboard-chart-card widget-panel-white widget-infographic-light flex flex-col overflow-hidden h-full"
      data-explore-chart-export
      data-chart-title={payload.title}
      data-chart-note={note}
    >
      <div className="px-3 py-2.5 border-b shrink-0 bg-white/80">
        <h4
          className="text-[11px] sm:text-xs font-extrabold leading-snug tracking-wide uppercase line-clamp-3 break-words text-[hsl(218_65%_28%)]"
          title={payload.title}
        >
          {payload.title}
        </h4>
        {payload.purpose ? (
          <p className="text-[10px] text-slate-600 leading-snug mt-1">{payload.purpose}</p>
        ) : null}
        {payload.manager_message ? (
          <p className="text-[10px] font-medium text-slate-700 leading-snug mt-1">{payload.manager_message}</p>
        ) : null}
        {note ? (
          <p className="text-[10px] text-slate-500 leading-snug mt-1 italic" data-testid="manager-chart-alignment-note">
            {note}
          </p>
        ) : null}
      </div>
      <div className="px-2 py-2 flex-1 min-h-[180px]">
        <ExploreReportChart
          merged={chartPlot.merged}
          series={chartPlot.series}
          height={compact ? 160 : 200}
          unit={leftUnit}
          secondaryUnit={rightUnit}
          dualAxis={Boolean(payload.dual_axis)}
          showTrendLine={payload.series?.length === 1}
          compact={compact}
        />
      </div>
    </div>
  );
}
