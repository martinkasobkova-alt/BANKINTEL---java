import React, { useMemo, useState } from "react";
import { fmtCompact, fmtPeriodLabel } from "@/lib/format";
import { isPointerInsidePlotViewBox } from "@/lib/rechartsPlotTooltip";

const MAX_VISIBLE = 6;
const MAX_VISIBLE_COMPACT = 4;
const MAX_VISIBLE_FULL_CATALOG = 10;

function isTrendKey(key) {
  return String(key || "").includes("__trend");
}

function formatValue(value, unit) {
  if (value == null || Number.isNaN(Number(value))) return "—";
  const n = Array.isArray(value) ? value[value.length - 1] : value;
  const formatted = fmtCompact(Number(n));
  return unit ? `${formatted} ${unit}`.trim() : formatted;
}

export function buildTooltipEntries(payload, { showTrendSeries, seriesCatalog }) {
  if (Array.isArray(seriesCatalog) && seriesCatalog.length) {
    const byKey = new Map();
    for (const p of payload || []) {
      if (p?.dataKey != null) byKey.set(String(p.dataKey), p);
    }
    const rows = seriesCatalog
      .filter((s) => s?.key && (showTrendSeries || !isTrendKey(s.key)))
      .map((s) => {
        const hit = byKey.get(String(s.key));
        const raw = hit?.value;
        const hasValue = raw != null && !Number.isNaN(Number(raw));
        return {
          dataKey: s.key,
          name: String(hit?.name || s.name || s.key).trim(),
          value: hasValue ? raw : null,
          color: hit?.color || s.color,
          hasValue,
        };
      });
    rows.sort((a, b) => {
      if (a.hasValue !== b.hasValue) return a.hasValue ? -1 : 1;
      if (a.hasValue && b.hasValue) return Number(b.value) - Number(a.value);
      return String(a.name).localeCompare(String(b.name), "cs");
    });
    return rows;
  }
  return (payload || [])
    .filter((p) => p.value != null && !Number.isNaN(Number(p.value)))
    .filter((p) => showTrendSeries || !isTrendKey(p.dataKey));
}

/**
 * Kompaktní dashboard tooltip — nepřekrývá polovinu grafu.
 * Trend řady (__trend) se nezobrazují, pokud nejsou explicitně povoleny.
 *
 * `seriesCatalog` — všechny řady grafu (i bez hodnoty v daném období → „—“).
 * Bez katalogu Recharts posílá jen řady s hodnotou (výchozí chování).
 */
export default function ChartTooltip({
  active,
  payload,
  label,
  coordinate,
  viewBox,
  unit = "",
  showTrendSeries = false,
  latestDataMode = false,
  seriesCatalog = null,
  compact = false,
}) {
  const [expanded, setExpanded] = useState(false);

  const entries = useMemo(
    () => buildTooltipEntries(payload, { showTrendSeries, seriesCatalog }),
    [payload, showTrendSeries, seriesCatalog]
  );

  const maxVisible =
    compact
      ? MAX_VISIBLE_COMPACT
      : Array.isArray(seriesCatalog) && seriesCatalog.length
        ? MAX_VISIBLE_FULL_CATALOG
        : MAX_VISIBLE;

  if (!active || !entries.length) return null;
  if (!isPointerInsidePlotViewBox(coordinate, viewBox)) return null;

  const periodLabel = latestDataMode ? String(label ?? "") : fmtPeriodLabel(label);
  const visible = expanded ? entries : entries.slice(0, maxVisible);
  const hiddenCount = entries.length - visible.length;

  return (
    <div className={`chart-system-tooltip rounded-lg border border-[hsl(205_45%_84%)] bg-white shadow-[0_4px_16px_hsl(218_55%_30%_/0.14)] pointer-events-auto ${
      compact ? "max-w-[min(76vw,18rem)]" : ""
    }`}>
      <div className={`${compact ? "px-2.5 py-1.5" : "px-3 py-2"} border-b border-border/30`}>
        <div className={`${compact ? "text-[9px]" : "text-[10px]"} uppercase tracking-wider text-slate-400`}>
          {latestDataMode ? "Řada" : "Období"}
        </div>
        <div className={`${compact ? "text-[11px]" : "text-[12px]"} font-semibold text-[hsl(218_30%_18%)] leading-snug mt-0.5`}>
          {periodLabel || "—"}
        </div>
      </div>
      <div className={`chart-system-tooltip-body ${compact ? "px-2.5 py-1.5 space-y-1 max-h-[132px]" : "px-3 py-2 space-y-1.5 max-h-[180px]"} overflow-y-auto`}>
        {visible.map((entry) => (
          <div key={entry.dataKey} className="flex items-start gap-2 min-w-0">
            <span
              className={`${compact ? "mt-1 h-1.5 w-1.5" : "mt-1.5 h-2 w-2"} shrink-0 rounded-full`}
              style={{ backgroundColor: entry.color || "hsl(202 90% 52%)" }}
            />
            <div className="min-w-0 flex-1">
              <div className={`${compact ? "text-[9px]" : "text-[10px]"} text-slate-500 leading-tight truncate`} title={entry.name}>
                {entry.name}
              </div>
              <div
                className={`${compact ? "text-[12px]" : "text-[13px]"} font-semibold tabular-nums leading-tight ${
                  entry.value == null || Number.isNaN(Number(entry.value))
                    ? "text-slate-400"
                    : "text-[hsl(218_30%_18%)]"
                }`}
              >
                {formatValue(entry.value, unit)}
              </div>
            </div>
          </div>
        ))}
        {hiddenCount > 0 && !expanded ? (
          <button
            type="button"
            className="text-[10px] text-[hsl(202_90%_40%)] hover:underline pt-0.5"
            onClick={(e) => {
              e.stopPropagation();
              setExpanded(true);
            }}
          >
            + {hiddenCount} další
          </button>
        ) : null}
      </div>
    </div>
  );
}
