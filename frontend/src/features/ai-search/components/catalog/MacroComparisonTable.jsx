import React, { useCallback, useEffect, useMemo, useState } from "react";
import { ArrowDown, ArrowUp, Table2 } from "lucide-react";

import api, { formatApiErrorFromAxios } from "@/lib/api";
import { LoadingSpinner } from "@/components/ui/loading";
import { formatComparisonCellValue, comparisonCellDirection } from "@/lib/macroComparisonTable";
import { formatSnapshotGeneratedAt } from "@/lib/macroTopicSnapshot";
import { countryDisplayLabel, countryDisplayLabelCompact, isAbbreviationCountry } from "@/lib/macroGeoLabels";
import { useIsMobileDashboard } from "@/hooks/useMediaQuery";

const TONE_CLASS = {
  up: "text-sky-600 font-semibold",
  down: "text-rose-600 font-semibold",
  neutral: "text-foreground",
  muted: "text-muted-foreground",
};

/**
 * Srovnávací tabulka zemí × ukazatelů — hodnoty ze nočního snapshotu
 * (/comparison-table?include_values=1), bez live stahování při návštěvě.
 */
export default function MacroComparisonTable({ onPreviewSeries, embedded = true }) {
  const isMobile = useIsMobileDashboard();
  const [payload, setPayload] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [regionScope, setRegionScope] = useState("eu");
  const [activeGroup, setActiveGroup] = useState("all");
  const [requestScope, setRequestScope] = useState("eu");

  const loadTable = useCallback(async () => {
    const scope = regionScope;
    setRequestScope(scope);
    setLoading(true);
    setError("");
    setPayload(null);
    try {
      const { data } = await api.get("/catalog/macro-topics/comparison-table", {
        params: {
          only_complete: false,
          min_columns: 2,
          scope,
          include_values: true,
          _ts: Date.now(),
        },
        headers: { "Cache-Control": "no-cache" },
        timeout: scope === "world" ? 180_000 : 60_000,
      });
      if (String(data?.scope || scope).toLowerCase() !== scope) {
        throw new Error("Server vrátil tabulku pro jiný rozsah zemí.");
      }
      setPayload(data);
    } catch (e) {
      setError(formatApiErrorFromAxios(e));
      setPayload(null);
    } finally {
      setLoading(false);
    }
  }, [regionScope]);

  useEffect(() => {
    setActiveGroup("all");
    setPayload(null);
  }, [regionScope]);

  useEffect(() => {
    void loadTable();
  }, [loadTable]);

  const columns = payload?.columns || [];
  const columnGroups = payload?.column_groups || [];
  const displayColumns = useMemo(() => {
    if (activeGroup === "all") return columns;
    const filtered = columns.filter((col) => col.group_id === activeGroup);
    return filtered.length ? filtered : columns;
  }, [activeGroup, columns]);
  const tableMinWidth = isMobile
    ? Math.max(280, displayColumns.length * 68 + 76)
    : Math.max(920, displayColumns.length * 116 + 190);
  const countryGroups = useMemo(() => {
    return (payload?.country_groups || [])
      .map((group) => ({
        ...group,
        countries: (group.countries || [])
          .filter((c) => !isAbbreviationCountry(c))
          .map((c) => ({ ...c, label_cs: countryDisplayLabel(c) })),
      }))
      .filter((group) => group.countries.length);
  }, [payload?.country_groups]);

  const handleCellClick = useCallback(
    (cell, col) => {
      if (!cell?.series || !onPreviewSeries) return;
      onPreviewSeries({
        ...cell.series,
        topic_id: cell.topic_id || cell.series.topic_id || col?.id,
        comparison_value_mode: cell.series.comparison_value_mode || cell.value_mode || undefined,
        comparison_calculation_label_cs:
          cell.calculation_label_cs || cell.series.comparison_calculation_label_cs,
      });
    },
    [onPreviewSeries],
  );

  const wrapperClass = embedded
    ? "space-y-3"
    : "rounded-2xl border border-violet-200/70 bg-gradient-to-br from-violet-50/40 via-card to-sky-50/25 p-4 sm:p-5 space-y-3 shadow-sm";

  if (loading || !payload || String(payload.scope || requestScope).toLowerCase() !== regionScope) {
    return (
      <div className={wrapperClass}>
        <div className="flex items-center gap-2 text-sm text-muted-foreground py-4">
          <LoadingSpinner className="h-4 w-4" />
          {regionScope === "world"
            ? "Načítám světovou srovnávací tabulku (IMF / World Bank / FRED / OECD)…"
            : "Načítám srovnávací tabulku…"}
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className={wrapperClass}>
        <p className="text-sm text-destructive">{error}</p>
        <button
          type="button"
          onClick={() => void loadTable()}
          className="text-xs font-medium text-sky-700 hover:underline"
        >
          Zkusit znovu
        </button>
      </div>
    );
  }

  const snapshotLabel = formatSnapshotGeneratedAt(payload?.snapshot_generated_at);

  return (
    <div className={wrapperClass} data-testid="macro-comparison-table">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
            <Table2 className="h-4 w-4 text-violet-600 shrink-0" />
            Makro srovnání zemí
          </h3>
          <p className="text-[12px] text-muted-foreground mt-1 max-w-2xl leading-snug">
            {payload?.country_count ?? 0} oblastí · {displayColumns.length}/{payload?.column_count ?? 0} ukazatelů.
            {snapshotLabel ? (
              <>
                {" "}
                · Data k {snapshotLabel}
              </>
            ) : null}
            .
            {regionScope === "world" ? (
              <>
                {" "}
                Světový výběr — data z IMF, World Bank, FRED a OECD.
              </>
            ) : (
              <>
                <span className="text-sky-600 font-medium"> Modrá</span> = růst oproti předchozímu období,
                <span className="text-rose-600 font-medium"> červená</span> = pokles.
              </>
            )}
            {" "}
            Kliknutím na hodnotu nebo období otevřete graf. Hlavička tabulky zůstává viditelná při scrollu.
          </p>
        </div>
      </div>

      <div
        className="flex flex-wrap items-center gap-1.5"
        role="tablist"
        aria-label="Rozsah zemí v tabulce"
      >
        <button
          type="button"
          role="tab"
          aria-selected={regionScope === "eu"}
          onClick={() => setRegionScope("eu")}
          className={`h-9 rounded-xl border px-3 text-[12px] font-semibold transition-colors ${
            regionScope === "eu"
              ? "border-violet-400 bg-violet-100 text-violet-950 shadow-sm"
              : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
          }`}
        >
          EU
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={regionScope === "world"}
          onClick={() => setRegionScope("world")}
          className={`h-9 rounded-xl border px-3 text-[12px] font-semibold transition-colors ${
            regionScope === "world"
              ? "border-sky-400 bg-sky-100 text-sky-950 shadow-sm"
              : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
          }`}
        >
          Svět
        </button>
      </div>

      {columnGroups.length ? (
        <div className="flex flex-wrap items-center gap-1.5" aria-label="Skupiny ukazatelů">
          <button
            type="button"
            onClick={() => setActiveGroup("all")}
            className={`h-8 rounded-lg border px-2.5 text-[11px] font-semibold transition-colors ${
              activeGroup === "all"
                ? "border-violet-300 bg-violet-100 text-violet-950"
                : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
            }`}
          >
            Vše
          </button>
          {columnGroups.map((group) => (
            <button
              key={group.id}
              type="button"
              onClick={() => setActiveGroup(group.id)}
              className={`h-8 rounded-lg border px-2.5 text-[11px] font-semibold transition-colors ${
                activeGroup === group.id
                  ? "border-violet-300 bg-violet-100 text-violet-950"
                  : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
              }`}
              title={group.label_cs}
            >
              {group.label_cs}
            </button>
          ))}
        </div>
      ) : null}

      <div
        className={`relative w-full max-w-full rounded-xl border border-border/80 bg-card shadow-sm ${
          isMobile
            ? "overflow-x-auto overflow-y-visible overscroll-x-contain"
            : "max-h-[min(75vh,920px)] overflow-auto"
        }`}
        data-testid="macro-comparison-table-scroll"
      >
        <table
          className={`w-full text-left leading-snug ${
            isMobile ? "text-[10px]" : "text-[12px]"
          }`}
          style={{ minWidth: tableMinWidth }}
        >
          <thead className="sticky top-0 z-20">
            <tr className="border-b border-border/70 bg-violet-50 text-muted-foreground shadow-[inset_0_-1px_0_hsl(var(--border)/0.55),0_3px_6px_-3px_rgba(0,0,0,0.08)]">
              <th
                className={`sticky left-0 top-0 z-40 bg-violet-50 font-semibold text-foreground shadow-[4px_0_8px_-4px_rgba(0,0,0,0.08)] ${
                  isMobile
                    ? "min-w-[4.25rem] max-w-[5rem] w-[5rem] px-1.5 py-1.5"
                    : "min-w-[168px] px-3 py-2.5"
                }`}
              >
                Země
              </th>
              {displayColumns.map((col) => (
                <th
                  key={col.id}
                  className={`sticky top-0 z-30 bg-violet-50 font-semibold whitespace-nowrap text-right text-foreground ${
                    isMobile ? "min-w-[4.25rem] max-w-[5.5rem] px-1 py-1.5" : "px-3 py-2.5"
                  }`}
                  title={col.calculation_label_cs ? `${col.label_cs} · ${col.calculation_label_cs}` : col.label_cs}
                >
                  {col.short_label_cs || col.label_cs}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {countryGroups.map((group) => (
              <React.Fragment key={group.id}>
                <tr className="bg-muted/35">
                  <td
                    colSpan={displayColumns.length + 1}
                    className={`font-semibold uppercase tracking-wide text-muted-foreground ${
                      isMobile ? "px-1.5 py-1 text-[9px]" : "px-3 py-1.5 text-[10px]"
                    }`}
                  >
                    {group.label_cs}
                  </td>
                </tr>
                {(group.countries || []).map((country) => {
                  const countryLabel = countryDisplayLabel(country);
                  const countryLabelMobile = countryDisplayLabelCompact(country);
                  return (
                  <tr
                    key={country.code}
                    className="border-b border-border/50 hover:bg-violet-50/35 transition-colors"
                  >
                    <td
                      className={`sticky left-0 z-10 bg-card font-medium text-foreground shadow-[4px_0_8px_-4px_rgba(0,0,0,0.06)] ${
                        isMobile
                          ? "min-w-[4.25rem] max-w-[5rem] w-[5rem] px-1.5 py-1 leading-tight whitespace-normal break-words"
                          : "px-3 py-2 whitespace-nowrap"
                      }`}
                      title={countryLabel}
                    >
                      {isMobile ? countryLabelMobile : countryLabel}
                      {!country.is_complete && country.column_hits < country.column_total ? (
                        <span className={`ml-0.5 text-muted-foreground font-normal ${isMobile ? "block text-[8px]" : "text-[10px]"}`}>
                          ({country.column_hits}/{country.column_total})
                        </span>
                      ) : null}
                    </td>
                    {displayColumns.map((col) => {
                      const cell = country.cells?.[col.id];
                      const hasSeries = Boolean(cell?.series);
                      const value = cell?.value ?? null;
                      const tone = comparisonCellDirection({
                        value,
                        direction: cell?.direction,
                      });
                      const effectiveCol = {
                        ...col,
                        value_kind_override: cell?.value_kind_override || col.value_kind_override,
                      };
                      const formatted = formatComparisonCellValue(value, effectiveCol);
                      const calculationHint = cell?.calculation_label_cs || col.calculation_label_cs || "";
                      const periodHint =
                        cell?.period && cell?.previous_period
                          ? `${cell.period} vs ${cell.previous_period}`
                          : cell?.period || "";
                      const titleParts = [col.label_cs, calculationHint, periodHint].filter(Boolean);

                      return (
                        <td key={col.id} className={`text-right tabular-nums ${isMobile ? "px-1 py-1" : "px-3 py-2"}`}>
                          {hasSeries ? (
                            <button
                              type="button"
                              title={
                                titleParts.length
                                  ? titleParts.join(" · ")
                                  : "Kliknutím otevřete náhled řady"
                              }
                              onClick={() => handleCellClick(cell, col)}
                              className={`inline-flex flex-col items-end gap-0.5 hover:underline underline-offset-2 max-w-full ${
                                TONE_CLASS[tone] || TONE_CLASS.neutral
                              }`}
                            >
                              <span className="inline-flex items-center justify-end gap-0.5">
                                {tone === "up" ? (
                                  <ArrowUp className={`shrink-0 opacity-80 ${isMobile ? "h-2.5 w-2.5" : "h-3 w-3"}`} aria-hidden />
                                ) : null}
                                {tone === "down" ? (
                                  <ArrowDown className={`shrink-0 opacity-80 ${isMobile ? "h-2.5 w-2.5" : "h-3 w-3"}`} aria-hidden />
                                ) : null}
                                <span>{formatted}</span>
                              </span>
                              {cell?.period ? (
                                <span className={`font-normal text-muted-foreground leading-none ${isMobile ? "text-[8px]" : "text-[10px]"}`}>
                                  {cell.period}
                                </span>
                              ) : null}
                            </button>
                          ) : (
                            <span className={TONE_CLASS.muted}>—</span>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                  );
                })}
              </React.Fragment>
            ))}
          </tbody>
        </table>
      </div>

      {!countryGroups.length ? (
        <p className="text-xs text-muted-foreground">Pro zvolený filtr nejsou dostupné žádné země.</p>
      ) : null}
    </div>
  );
}
