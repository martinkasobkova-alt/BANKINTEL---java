import React from "react";
import { X as XIcon, Info as InfoIcon } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import ChartValueCompareSummary from "@/charts/ChartValueCompareSummary";
import ChartTimeSeriesSummary from "@/charts/ChartTimeSeriesSummary";
import {
  fmtStatsNumber,
  fmtStatsSigned,
  fmtStatsCorrelation,
  fmtStatsLeadLag,
} from "@/lib/aradViewUtils";

function ellipsizeLabel(value, max = 16) {
  const s = String(value ?? "").trim();
  if (!s || s.length <= max) return s;
  return `${s.slice(0, Math.max(1, max - 1)).trimEnd()}\u2026`;
}

export default function AradViewChartInsightsPanel({
  showValueCompareSummaryInChart,
  valueCompareStats,
  showTimeSeriesSummaryInChart,
  timeSeriesStats,
  unit,
  miniChartMode,
  veryNarrowWidget,
  chartCompact,
  fsExpand,
  effectiveKpiSummaryMode,
  statistics,
}) {
  return (
    <>
      {showValueCompareSummaryInChart ? (
        <ChartValueCompareSummary
          stats={valueCompareStats}
          unit={unit}
          miniChartMode={miniChartMode}
          veryNarrowWidget={veryNarrowWidget}
          chartCompact={chartCompact}
          fsExpand={fsExpand}
          kpiMode={effectiveKpiSummaryMode}
        />
      ) : null}
      {showTimeSeriesSummaryInChart ? (
        <ChartTimeSeriesSummary
          stats={timeSeriesStats}
          unit={unit}
          miniChartMode={miniChartMode}
          veryNarrowWidget={veryNarrowWidget}
          chartCompact={chartCompact}
          fsExpand={fsExpand}
          kpiMode={effectiveKpiSummaryMode}
        />
      ) : null}
      <AradViewSeriesStatisticsPanel {...statistics} />
    </>
  );
}

function AradViewStatisticsInfoButton() {
    return (
      <Popover>
        <PopoverTrigger asChild>
          <button
            type="button"
            className="inline-flex h-5 w-5 items-center justify-center rounded-full border border-sky-100 bg-white/85 text-sky-700 shadow-sm hover:bg-white hover:text-sky-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-400/40"
            title="Vysvětlit statistiky"
            aria-label="Vysvětlit statistiky"
            data-testid="arad-statistics-info"
            onClick={(e) => e.stopPropagation()}
          >
            <InfoIcon className="h-3 w-3" aria-hidden />
          </button>
        </PopoverTrigger>
        <PopoverContent
          align="end"
          side="bottom"
          className="z-[10000] w-[min(25rem,calc(100vw-1.5rem))] max-h-[min(72vh,34rem)] overflow-y-auto p-3 text-[11px] leading-relaxed text-slate-700"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="space-y-2.5">
            <div>
              <div className="text-[10px] font-semibold uppercase tracking-wide text-sky-800/80">
                Co panel počítá
              </div>
              <p className="mt-1 text-slate-700">
                Statistiky se počítají z aktuálně zobrazeného grafu: po filtru období, sjednocení frekvence a zapnuté transformaci.
                Když zvolíš <strong>Stac. %</strong>, počítají se nad procentními změnami místo původních hodnot.
              </p>
            </div>

            <div className="grid gap-1.5 sm:grid-cols-2">
              <div className="rounded border border-slate-200/80 bg-slate-50/80 px-2 py-1.5">
                <div className="font-semibold text-slate-900">r / roll / lag</div>
                <p><strong>r</strong> je korelace od -1 do +1. <strong>roll</strong> je korelace v klouzavém okně. <strong>lag</strong> ukazuje posun období, při kterém je vztah nejsilnější.</p>
              </div>
              <div className="rounded border border-slate-200/80 bg-slate-50/80 px-2 py-1.5">
                <div className="font-semibold text-slate-900">β / R² / spread</div>
                <p><strong>β</strong> je sklon lineární regrese druhé řady vůči první. <strong>R²</strong> říká, kolik variability regrese vysvětluje. <strong>spread</strong> je aktuální rozdíl řad.</p>
              </div>
              <div className="rounded border border-slate-200/80 bg-slate-50/80 px-2 py-1.5">
                <div className="font-semibold text-slate-900">Kolinearita</div>
                <p>Ukazuje nejsilnější absolutní korelaci mezi řadami. Vysoká hodnota znamená, že dvě řady nesou velmi podobnou informaci.</p>
              </div>
              <div className="rounded border border-slate-200/80 bg-slate-50/80 px-2 py-1.5">
                <div className="font-semibold text-slate-900">Pořadí / průměr</div>
                <p>Pořadí bere poslední společné období, seřadí řady podle hodnoty a ukáže odchylku od jejich průměru.</p>
              </div>
            </div>

            <div className="rounded border border-sky-100 bg-sky-50/80 px-2 py-1.5 text-sky-950">
              <strong>Pozor na interpretaci:</strong> korelace ani regrese samy o sobě neznamenají příčinu. U různých frekvencí se jemnější řady převedou na nejhrubší periodu pomocí poslední dostupné hodnoty v období.
            </div>
          </div>
        </PopoverContent>
      </Popover>
    );
  }

function AradViewSeriesStatisticsPanel({
  showSeriesStatisticsPanel,
  isMultiSeries,
  singleSeriesStatistics,
  statsActions,
  chartStatsFocus,
  isMobileChartUi,
  onCloseStatistics,
  multiSeriesStatistics,
  multiFrequencyAlignmentLabel,
  chartRowsZoomedCount,
}) {
    if (!showSeriesStatisticsPanel) return null;
    if (!isMultiSeries) {
      const item = singleSeriesStatistics?.series;
      if (!singleSeriesStatistics?.ok || !item) return null;
      const activeAction = statsActions.find(([id]) => id === chartStatsFocus);
      return (
        <div
          className={`min-h-0 overflow-hidden rounded-md border border-sky-100/80 bg-sky-50/55 px-2 py-1.5 text-slate-700 shadow-[inset_0_1px_0_rgba(255,255,255,0.65)] ${
            isMobileChartUi ? "text-[9px] leading-tight" : "text-[10px] leading-snug"
          }`}
          data-testid="arad-statistics-panel"
        >
          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
            <span className="font-semibold text-slate-800">{activeAction?.[1] || "Statistiky"}</span>
            <span className="font-mono text-slate-500">{item.count} bodů</span>
            <span className="font-mono text-slate-500">poslední {item.latestPeriod}</span>
            <div className="ml-auto flex items-center gap-1">
              <AradViewStatisticsInfoButton />
              <button
                type="button"
                onClick={onCloseStatistics}
                className="inline-flex h-5 w-5 items-center justify-center rounded border border-sky-100 bg-white/80 text-slate-500 hover:text-slate-800"
                title="Skrýt statistiky"
                aria-label="Skrýt statistiky"
              >
                <XIcon className="h-3 w-3" aria-hidden />
              </button>
            </div>
          </div>
          <div className={`mt-1 grid gap-1 ${isMobileChartUi ? "grid-cols-1" : "grid-cols-4"}`}>
            <div className="min-w-0 rounded border border-white/70 bg-white/65 px-1.5 py-1">
              <div className="truncate font-semibold text-slate-700">Poslední / změny</div>
              <div className="font-mono text-slate-600">
                {fmtStatsNumber(item.latestValue)} · {fmtStatsSigned(item.changePct, { digits: 1, suffix: " %" })} · YoY {fmtStatsSigned(item.yoyPct, { digits: 1, suffix: " %" })}
              </div>
            </div>
            <div className="min-w-0 rounded border border-white/70 bg-white/65 px-1.5 py-1">
              <div className="truncate font-semibold text-slate-700">Min/max/průměr</div>
              <div className="font-mono text-slate-600">
                {fmtStatsNumber(item.min)} / {fmtStatsNumber(item.max)} · Ø {fmtStatsNumber(item.mean)}
              </div>
            </div>
            <div className="min-w-0 rounded border border-white/70 bg-white/65 px-1.5 py-1">
              <div className="truncate font-semibold text-slate-700">Medián / volatilita</div>
              <div className="font-mono text-slate-600">
                med {fmtStatsNumber(item.median)} · σ {fmtStatsNumber(item.stdDev)} · vol {fmtStatsNumber(item.volatility, { digits: 2, compact: false })} %
              </div>
            </div>
            <div className="min-w-0 rounded border border-white/70 bg-white/65 px-1.5 py-1">
              <div className="truncate font-semibold text-slate-700">Index / z-score</div>
              <div className="font-mono text-slate-600">
                i100 {fmtStatsNumber(item.index100, { digits: 1, compact: false })} · z {fmtStatsNumber(item.zScore, { digits: 2, compact: false })} · anom. {item.anomalyCount}
              </div>
            </div>
          </div>
          <div className="mt-1 flex min-w-0 flex-wrap gap-1">
            <span className="rounded border border-white/70 bg-white/60 px-1.5 py-0.5 font-mono text-slate-600">
              klouzavý Ø {item.movingAverageWindow}: {fmtStatsNumber(item.movingAverage)}
            </span>
            <span className="rounded border border-white/70 bg-white/60 px-1.5 py-0.5 font-mono text-slate-600">
              rozdíl: {fmtStatsSigned(item.change)}
            </span>
            <span className="rounded border border-white/70 bg-white/60 px-1.5 py-0.5 font-mono text-slate-600">
              směrodatná odchylka: {fmtStatsNumber(item.stdDev)}
            </span>
          </div>
        </div>
      );
    }
    if (!multiSeriesStatistics?.ok) return null;
    const pairs = Array.isArray(multiSeriesStatistics.pairs) ? multiSeriesStatistics.pairs : [];
    const strongestPair =
      pairs
        .filter((pair) => pair?.correlation != null)
        .sort((a, b) => Math.abs(b.correlation) - Math.abs(a.correlation))[0] || pairs[0] || null;
    const col = multiSeriesStatistics.collinearity;
    const ranking = multiSeriesStatistics.ranking;
    const rankingTop = ranking?.items?.[0] || null;
    const rankingLast = ranking?.items?.length ? ranking.items[ranking.items.length - 1] : null;
    const previewSeries = (multiSeriesStatistics.series || []).slice(0, isMobileChartUi ? 2 : 4);
    const moreSeriesCount = Math.max(0, (multiSeriesStatistics.series || []).length - previewSeries.length);
    const pairTitle = strongestPair
      ? `${strongestPair.aLabel} / ${strongestPair.bLabel}`
      : "Nejsou společná období";

    return (
      <div
        className={`min-h-0 overflow-hidden rounded-md border border-sky-100/80 bg-sky-50/55 px-2 py-1.5 text-slate-700 shadow-[inset_0_1px_0_rgba(255,255,255,0.65)] ${
          isMobileChartUi ? "text-[9px] leading-tight" : "text-[10px] leading-snug"
        }`}
        data-testid="arad-statistics-panel"
      >
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
          <span className="font-semibold text-slate-800">Statistiky</span>
          {multiFrequencyAlignmentLabel ? (
            <span className="font-mono text-slate-500">{multiFrequencyAlignmentLabel}</span>
          ) : null}
          <span className="font-mono text-slate-500">{chartRowsZoomedCount} bodů</span>
          <div className="ml-auto flex items-center gap-1">
            <AradViewStatisticsInfoButton />
            <button
              type="button"
              onClick={onCloseStatistics}
              className="inline-flex h-5 w-5 items-center justify-center rounded border border-sky-100 bg-white/80 text-slate-500 hover:text-slate-800"
              title="Skrýt statistiky"
              aria-label="Skrýt statistiky"
            >
              <XIcon className="h-3 w-3" aria-hidden />
            </button>
          </div>
        </div>
        <div className={`mt-1 grid gap-1 ${isMobileChartUi ? "grid-cols-1" : "grid-cols-3"}`}>
          <div className="min-w-0 rounded border border-white/70 bg-white/65 px-1.5 py-1">
            <div className="truncate font-semibold text-slate-700" title={pairTitle}>
              {ellipsizeLabel(strongestPair?.aLabel || "Řada A", 18)} / {ellipsizeLabel(strongestPair?.bLabel || "Řada B", 18)}
            </div>
            <div className="font-mono text-slate-600">
              r {fmtStatsCorrelation(strongestPair?.correlation)} · roll {fmtStatsCorrelation(strongestPair?.rollingCorrelation)} · lag {fmtStatsLeadLag(strongestPair?.leadLag)}
            </div>
          </div>
          <div className="min-w-0 rounded border border-white/70 bg-white/65 px-1.5 py-1">
            <div className="truncate font-semibold text-slate-700">
              Regrese / rozdíl
            </div>
            <div className="font-mono text-slate-600">
              β {fmtStatsNumber(strongestPair?.regression?.beta, { digits: 2, compact: false })} · R² {fmtStatsNumber(strongestPair?.regression?.r2, { digits: 2, compact: false })} · spread {fmtStatsNumber(strongestPair?.latestSpread)}
            </div>
          </div>
          <div className="min-w-0 rounded border border-white/70 bg-white/65 px-1.5 py-1">
            <div className="truncate font-semibold text-slate-700">
              Kolinearita / pořadí
            </div>
            <div className="font-mono text-slate-600">
              max |r| {fmtStatsCorrelation(col?.maxAbsCorrelation)}{col?.high ? " vysoká" : ""} · top {ellipsizeLabel(rankingTop?.label || "—", 14)}
            </div>
          </div>
        </div>
        <div className="mt-1 flex min-w-0 flex-wrap gap-1">
          {previewSeries.map((item) => (
            <span
              key={item.key}
              className="min-w-0 rounded border border-white/70 bg-white/60 px-1.5 py-0.5 font-mono text-slate-600"
              title={`Poslední: ${fmtStatsNumber(item.latestValue)} | změna: ${fmtStatsSigned(item.changePct, { digits: 1, suffix: " %" })} | YoY: ${fmtStatsSigned(item.yoyPct, { digits: 1, suffix: " %" })} | min/max: ${fmtStatsNumber(item.min)} / ${fmtStatsNumber(item.max)} | průměr/medián: ${fmtStatsNumber(item.mean)} / ${fmtStatsNumber(item.median)} | směrodatná odchylka: ${fmtStatsNumber(item.stdDev)} | volatilita: ${fmtStatsNumber(item.volatility, { digits: 2, compact: false })} % | klouzavý průměr: ${fmtStatsNumber(item.movingAverage)} | index 100: ${fmtStatsNumber(item.index100, { digits: 1, compact: false })} | z-score: ${fmtStatsNumber(item.zScore, { digits: 2, compact: false })} | anomálie: ${item.anomalyCount}`}
            >
              {ellipsizeLabel(item.label, 16)}: {fmtStatsNumber(item.latestValue)} · {fmtStatsSigned(item.changePct, { digits: 1, suffix: " %" })}
            </span>
          ))}
          {moreSeriesCount > 0 ? (
            <span className="rounded border border-white/70 bg-white/60 px-1.5 py-0.5 font-mono text-slate-500">
              +{moreSeriesCount}
            </span>
          ) : null}
          {rankingTop && rankingLast ? (
            <span
              className="min-w-0 rounded border border-white/70 bg-white/60 px-1.5 py-0.5 font-mono text-slate-600"
              title={`Průměr: ${fmtStatsNumber(ranking.average)} | ${rankingTop.label}: ${fmtStatsSigned(rankingTop.deviation, { digits: 2 })} od průměru | ${rankingLast.label}: ${fmtStatsSigned(rankingLast.deviation, { digits: 2 })} od průměru`}
            >
              průměr {fmtStatsNumber(ranking.average)} · {ellipsizeLabel(rankingTop.label, 12)} {fmtStatsSigned(rankingTop.deviationPct, { digits: 1, suffix: " %" })}
            </span>
          ) : null}
        </div>
      </div>
    );
  }
