import React from "react";
import { CheckSquare, Square } from "lucide-react";

import { categoryBadge, geoScopeLabel, sourceLabel } from "@/lib/exploreManagerPayload";

export default function ExploreIndicatorCard({ item, checked, onToggle }) {
  const geo = geoScopeLabel(item);
  const badge = categoryBadge(item);
  const source = sourceLabel(item);
  const score = Number(item.confidence_score ?? item.score ?? 0);

  return (
    <button
      type="button"
      onClick={onToggle}
      className={`w-full text-left rounded-xl border px-4 py-3 transition-colors ${
        checked
          ? "border-teal-500/60 bg-teal-50/80 shadow-sm"
          : item.chart_recommended
            ? "border-indigo-300/70 bg-indigo-50/40 hover:bg-indigo-50/70"
            : "border-[hsl(var(--border)/0.75)] bg-card/90 hover:bg-muted/30"
      }`}
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 text-teal-700 shrink-0">
          {checked ? <CheckSquare className="h-4 w-4" /> : <Square className="h-4 w-4 text-muted-foreground" />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-1.5 mb-0.5">
            {item.chart_recommended ? (
              <span className="text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded bg-indigo-600 text-white">
                Doporučeno
              </span>
            ) : null}
            {item.curated ? (
              <span className="text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded bg-teal-700 text-white">
                Kurátorováno
              </span>
            ) : null}
            {item.has_forecast ? (
              <span className="text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded bg-violet-600 text-white">
                {String(item.forecast_source || "").toLowerCase().includes("oecd")
                  ? "OECD výhled"
                  : String(item.forecast_source || "").toLowerCase().includes("weo")
                    ? "WEO"
                    : "Výhled"}
              </span>
            ) : null}
            {badge ? (
              <span className="text-[9px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded border border-slate-300/80 text-slate-600">
                {badge}
              </span>
            ) : null}
            {geo ? (
              <span className="text-[9px] font-medium px-1.5 py-0.5 rounded bg-slate-100 text-slate-600">{geo}</span>
            ) : null}
          </span>
          <span className="block text-sm font-semibold text-slate-900 truncate">
            {item.indicator_name || item.title || item.dataset_id}
          </span>
          <span className="block text-[11px] text-muted-foreground mt-0.5">
            <span className="font-medium text-slate-600">Zdroj:</span> {source}
            {Number.isFinite(score) ? ` · skóre ${score.toFixed(2)}` : ""}
          </span>
          {item.selection_reason ? (
            <span className="block text-[10px] text-slate-500 mt-1 line-clamp-2">{item.selection_reason}</span>
          ) : item.dataset_name && item.dataset_name !== item.indicator_name ? (
            <span className="block text-[10px] text-slate-500 mt-1 truncate">{item.dataset_name}</span>
          ) : null}
        </span>
      </div>
    </button>
  );
}
