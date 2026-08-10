import React from "react";
import { ClipboardCopy, Database } from "lucide-react";
import ChartVisualConfigControls from "@/components/charts/ChartVisualConfigControls";
import ExportMenu from "@/components/widgets/ExportMenu";
import { ALL_FREQS, FREQ_RANK } from "@/components/widgets/aradViewChartFreq";
import { normalizeIconOrientation } from "@/lib/chartKindCatalog";
import { fmtCompact } from "@/lib/format";
import { fmtTrendSlopePerStep } from "@/lib/aradViewUtils";

export default function AradViewAdvancedControlsPanel({
  includeExport = false,
  mobile = {},
  layout = {},
  period = {},
  chart = {},
  visual = {},
  overlay = {},
  actions = {},
}) {
  const {
    mobileLargeChartView,
    isMobileChartUi,
    mobileFsLayout,
    setMobileFsLayout,
  } = mobile;
  const {
    activeControlStyle,
    renderViewLayoutControls,
    renderCompareToolbarControl,
    renderTransformToolbarControls,
    renderManualTrendControls,
    toolbarSlot,
    geoControlNode,
    dimensionValueControlNode,
    showChartPanel,
  } = layout;
  const {
    currentFreq,
    targetFreq,
    lockPeriod,
    fePeriod = {},
    setTargetFreq,
    isAggregated,
    agg,
    setAgg,
    hasDates,
    rowsRawCount = 0,
    timeframes = [],
    timeframe,
    lockTimeRange,
    feTimeRange = {},
    setTimeframe,
    customRangeControls,
  } = period;
  const {
    showChartKind,
    chartKindOptions = [],
    handleChartKindChange,
    lockChartType,
    feChartType = {},
    chartKind,
    isMultiSeries,
    barOrientations = [],
    barOrientation,
    setBarOrientation,
    barMultiColor,
    setBarMultiColor,
    pieVariants = [],
    pieVariant,
    setPieVariant,
  } = chart;
  const {
    mapRegion,
    setMapRegion,
    pictogramIcon,
    setPictogramIcon,
    pictogramUnit,
    setPictogramUnit,
    defaultChartIcon,
    setDefaultChartIcon,
    iconOrientation,
    setIconOrientation,
    persistChartVisualConfig,
  } = visual;
  const {
    showChartOverlayControls,
    avgLineEnabled,
    setOverlayAvg,
    medianLineEnabled,
    setOverlayMedian,
    trendLineEnabled,
    setOverlayTrend,
    legendHidden,
    setLegendHidden,
    singleSeriesOverlaySpec,
    unit,
    highlightLatestEnabled,
    setHighlightLatestEnabled,
    highlightExtremaEnabled,
    setHighlightExtremaEnabled,
  } = overlay;
  const {
    miniChartMode,
    showInteractiveControls,
    chartDataPanelOpen,
    setChartDataPanelOpen,
    chartDataPanelMode,
    setChartDataPanelMode,
    sourceDataLocked,
    canExportSourceData,
    handleCopyChartData,
    handleOpenOlapCube,
    heading,
    subtitle,
    exportColumns,
    exportRows,
    chartCaptureRef,
    aradChartContract,
    widget,
  } = actions;

  return (
    <>
      {mobileLargeChartView && isMobileChartUi ? (
        <div className="mb-2">
          <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Orientace obrazovky</div>
          <div className="flex flex-wrap gap-1">
            <button
              type="button"
              onClick={() => setMobileFsLayout("landscape")}
              aria-pressed={mobileFsLayout === "landscape"}
              title="Na šířku — širší graf s více místem pro popisky osy X"
              className={`h-6 px-2 text-[10px] rounded border font-mono ${
                mobileFsLayout === "landscape"
                  ? "chip-mint border-transparent font-semibold"
                  : "border-border/60 text-slate-600"
              }`}
              style={mobileFsLayout === "landscape" ? activeControlStyle : undefined}
              data-testid="arad-mobile-orientation-landscape"
            >
              Na šířku
            </button>
            <button
              type="button"
              onClick={() => setMobileFsLayout("portrait")}
              aria-pressed={mobileFsLayout === "portrait"}
              title="Na výšku — klasické svislé zobrazení telefonu"
              className={`h-6 px-2 text-[10px] rounded border font-mono ${
                mobileFsLayout === "portrait"
                  ? "chip-mint border-transparent font-semibold"
                  : "border-border/60 text-slate-600"
              }`}
              style={mobileFsLayout === "portrait" ? activeControlStyle : undefined}
              data-testid="arad-mobile-orientation-portrait"
            >
              Na výšku
            </button>
          </div>
        </div>
      ) : null}
      <div className="mb-2">
        <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Zobrazení</div>
        <div className="flex flex-wrap gap-1">
          {renderViewLayoutControls({ sheet: true, testIdSuffix: "-sheet" })}
        </div>
      </div>
      {showChartPanel ? renderCompareToolbarControl({ sheet: true, testIdSuffix: "-sheet" }) : null}
      {showChartPanel && geoControlNode ? <div className="mt-1">{geoControlNode}</div> : null}
      {(toolbarSlot || dimensionValueControlNode) && showChartPanel ? (
        <div className="mb-2">
          <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Rozdělení řad</div>
          <div className="flex flex-wrap items-center gap-1">
            {toolbarSlot}
            {dimensionValueControlNode}
          </div>
        </div>
      ) : null}
      {renderTransformToolbarControls()}
      {currentFreq && (
        <div className="mb-2">
          <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Periodicita</div>
          <div className="flex flex-wrap gap-1">
            {ALL_FREQS.map((f) => {
              const isActive = f.code === targetFreq;
              const isAvailable =
                FREQ_RANK[f.code] !== undefined &&
                FREQ_RANK[currentFreq] !== undefined &&
                FREQ_RANK[f.code] >= FREQ_RANK[currentFreq];
              return (
                <button
                  key={f.code}
                  type="button"
                  onClick={() => isAvailable && !lockPeriod && setTargetFreq(f.code)}
                  disabled={!isAvailable || lockPeriod}
                  title={lockPeriod ? fePeriod.message || f.title : f.title}
                  className={`h-6 min-w-8 px-2 text-[10px] rounded border font-mono ${
                    isActive ? "chip-mint border-transparent font-semibold" : isAvailable ? "border-border/60 text-slate-600" : "border-border/40 text-slate-300"
                  } ${lockPeriod ? "opacity-60" : ""}`}
                  style={isActive ? activeControlStyle : undefined}
                >
                  {f.label}
                </button>
              );
            })}
          </div>
        </div>
      )}
      {isAggregated && (
        <div className="mb-2">
          <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Agregace</div>
          <div className="flex flex-wrap gap-1">
            {[
              ["sum", "Σ"],
              ["avg", "ø"],
              ["last", "konec"],
            ].map(([id, label]) => (
              <button
                key={id}
                type="button"
                onClick={() => setAgg(id)}
                className={`h-6 px-2 text-[10px] rounded border font-mono ${agg === id ? "chip-mint border-transparent" : "border-border/60 text-slate-600"}`}
                style={agg === id ? activeControlStyle : undefined}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      )}
      {hasDates && rowsRawCount > 0 && (
        <div className="mb-2">
          <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Období</div>
          <div className="flex flex-wrap gap-1">
            {timeframes.map((tfBtn) => (
              <button
                key={tfBtn.id}
                type="button"
                onClick={() => !lockTimeRange && setTimeframe(tfBtn.id)}
                disabled={lockTimeRange}
                title={lockTimeRange ? feTimeRange.message || tfBtn.label : tfBtn.label}
                className={`h-6 px-2 text-[10px] rounded border font-mono ${timeframe === tfBtn.id ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"} ${lockTimeRange ? "opacity-60" : ""}`}
                style={timeframe === tfBtn.id ? activeControlStyle : undefined}
              >
                {tfBtn.label}
              </button>
            ))}
          </div>
          {customRangeControls(true)}
        </div>
      )}
      {showChartKind && (
        <div className="mb-1">
          <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Typ grafu</div>
          <div className="flex flex-wrap gap-1">
            {chartKindOptions.map(({ id, label, Icon, title: tTitle }) => (
              <button
                key={id}
                type="button"
                onClick={() => handleChartKindChange(id)}
                disabled={lockChartType}
                title={lockChartType ? feChartType.message || tTitle : tTitle}
                className={`flex items-center gap-1 h-6 px-2 text-[9px] rounded-full border ${
                  lockChartType
                    ? "border-border/40 bg-slate-50/70 text-slate-400 cursor-not-allowed"
                    : chartKind === id
                      ? "chip-mint border-transparent font-medium"
                      : "border-border/60 text-slate-600"
                }`}
                style={!lockChartType && chartKind === id ? activeControlStyle : undefined}
              >
                <Icon className="h-3 w-3" /> {label}
              </button>
            ))}
          </div>
          {chartKind === "bar" && !isMultiSeries && (
            <div className="flex flex-wrap items-center gap-2 mt-2">
              <span className="text-[9px] text-slate-500 font-mono">Směr:</span>
              {barOrientations.map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  onClick={() => setBarOrientation(opt.id)}
                  className={`h-5 px-2 text-[9px] rounded border font-mono ${barOrientation === opt.id ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                  style={barOrientation === opt.id ? activeControlStyle : undefined}
                  title={opt.id === "horizontal" ? "Vodorovné pruhy" : "Svislé sloupce"}
                >
                  {opt.label}
                </button>
              ))}
              <span className="text-[9px] text-slate-500 font-mono ml-1">Barvy:</span>
              <button
                type="button"
                onClick={() => setBarMultiColor(true)}
                className={`h-5 px-2 text-[9px] rounded border font-mono ${barMultiColor ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                style={barMultiColor ? activeControlStyle : undefined}
                title="Každý sloupec jinou barvou"
              >
                Barevné
              </button>
              <button
                type="button"
                onClick={() => setBarMultiColor(false)}
                className={`h-5 px-2 text-[9px] rounded border font-mono ${!barMultiColor ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                style={!barMultiColor ? activeControlStyle : undefined}
                title="Všechny sloupce stejnou barvou"
              >
                Jednobarevné
              </button>
            </div>
          )}
          {chartKind === "pie" && !isMultiSeries && (
            <div className="flex flex-wrap items-center gap-2 mt-2">
              <span className="text-[9px] text-slate-500 font-mono">Tvar koláče:</span>
              {pieVariants.map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  onClick={() => setPieVariant(opt.id)}
                  className={`h-5 px-2 text-[9px] rounded border font-mono ${pieVariant === opt.id ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                  style={pieVariant === opt.id ? activeControlStyle : undefined}
                  title={opt.id === "full" ? "Plný koláč bez otvoru" : "Kolečko s otvorem uprostřed"}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          )}
          <ChartVisualConfigControls
            chartKind={chartKind}
            mapRegion={mapRegion}
            onMapRegionChange={(v) => {
              setMapRegion(v);
              void persistChartVisualConfig({ chart_map_region: v });
            }}
            pictogramIcon={pictogramIcon}
            onPictogramIconChange={(v) => {
              setPictogramIcon(v);
              void persistChartVisualConfig({ chart_pictogram_icon: v });
            }}
            pictogramUnit={pictogramUnit}
            onPictogramUnitChange={(v) => {
              setPictogramUnit(v);
              void persistChartVisualConfig({ chart_pictogram_unit: v });
            }}
            defaultIcon={defaultChartIcon}
            onDefaultIconChange={(v) => {
              setDefaultChartIcon(v);
              void persistChartVisualConfig({ chart_icon_default: v });
            }}
            iconOrientation={iconOrientation}
            onIconOrientationChange={(v) => {
              setIconOrientation(normalizeIconOrientation(v));
              void persistChartVisualConfig({ chart_icon_orientation: normalizeIconOrientation(v) });
            }}
            activeControlStyle={activeControlStyle}
          />
          {showChartOverlayControls && (
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5 mt-2">
              <span className="text-[9px] text-slate-500 font-mono">Linky:</span>
              <span className="inline-flex flex-wrap items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setOverlayAvg(!avgLineEnabled)}
                  className={`h-5 px-2 text-[9px] rounded border font-mono inline-flex items-center justify-center leading-tight ${avgLineEnabled ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                  style={avgLineEnabled ? activeControlStyle : undefined}
                  title={
                    avgLineEnabled && singleSeriesOverlaySpec && Number.isFinite(singleSeriesOverlaySpec.average)
                      ? `Průměr zobrazených hodnot: ${fmtCompact(singleSeriesOverlaySpec.average)}${(unit || "").trim() ? ` ${String(unit).trim()}` : ""} (hodnota je u čáry v grafu)`
                      : "Průměrná linka — číslo se zobrazí u čáry v grafu"
                  }
                >
                  průměr
                </button>
              </span>
              <span className="inline-flex flex-wrap items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setOverlayMedian(!medianLineEnabled)}
                  className={`h-5 px-2 text-[9px] rounded border font-mono inline-flex items-center justify-center leading-tight ${medianLineEnabled ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                  style={medianLineEnabled ? activeControlStyle : undefined}
                  title={
                    medianLineEnabled && singleSeriesOverlaySpec && Number.isFinite(singleSeriesOverlaySpec.median)
                      ? `Medián zobrazených hodnot: ${fmtCompact(singleSeriesOverlaySpec.median)}${(unit || "").trim() ? ` ${String(unit).trim()}` : ""} (hodnota je u čáry v grafu)`
                      : "Mediánová linka — číslo se zobrazí u čáry v grafu"
                  }
                >
                  medián
                </button>
              </span>
              <span className="inline-flex flex-wrap items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setOverlayTrend(!trendLineEnabled)}
                  className={`h-5 px-2 text-[9px] rounded border font-mono inline-flex items-center justify-center leading-tight ${trendLineEnabled ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                  style={trendLineEnabled ? activeControlStyle : undefined}
                  title={
                    trendLineEnabled &&
                    singleSeriesOverlaySpec &&
                    singleSeriesOverlaySpec.trendSlopePerStep != null &&
                    Number.isFinite(singleSeriesOverlaySpec.trendSlopePerStep)
                      ? `Sklon trendu (změna hodnoty na jeden krok řady): ${fmtTrendSlopePerStep(singleSeriesOverlaySpec.trendSlopePerStep)}${(unit || "").trim() ? ` ${String(unit).trim()}` : ""} / krok (uvedeno i u čáry v grafu)`
                      : "Trendová linka (lineární aproximace v pořadí bodů) — sklon u čáry v grafu"
                  }
                >
                  trend
                </button>
              </span>
              {renderManualTrendControls({ inline: false })}
              <span className="inline-flex flex-wrap items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setLegendHidden?.(!legendHidden)}
                  className={`h-5 px-2 text-[9px] rounded border font-mono inline-flex items-center justify-center leading-tight ${!legendHidden ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                  style={!legendHidden ? activeControlStyle : undefined}
                  title={legendHidden ? "Zobrazit legendu grafu" : "Skrýt legendu grafu"}
                >
                  legenda
                </button>
              </span>
              {!isMultiSeries && chartKind !== "pie" ? (
                <>
                  <span className="text-[9px] text-slate-500 font-mono ml-1">Značky:</span>
                  <button
                    type="button"
                    onClick={() => setHighlightLatestEnabled((v) => !v)}
                    className={`h-5 px-2 text-[9px] rounded border font-mono ${highlightLatestEnabled ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                    style={highlightLatestEnabled ? activeControlStyle : undefined}
                    title="Zvýraznit poslední hodnotu"
                  >
                    poslední
                  </button>
                  <button
                    type="button"
                    onClick={() => setHighlightExtremaEnabled((v) => !v)}
                    className={`h-5 px-2 text-[9px] rounded border font-mono ${highlightExtremaEnabled ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"}`}
                    style={highlightExtremaEnabled ? activeControlStyle : undefined}
                    title="Zvýraznit maximum a minimum"
                  >
                    min/max
                  </button>
                </>
              ) : null}
            </div>
          )}
        </div>
      )}
      {includeExport && (!miniChartMode || showInteractiveControls) ? (
        <div className="mt-4 pt-3 border-t border-border/50">
          <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-2">Akce</div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => {
                setChartDataPanelMode?.("data");
                setChartDataPanelOpen((v) => (chartDataPanelMode === "data" ? !v : true));
              }}
              data-testid="arad-view-data-toggle-panel"
              className={`h-7 px-3 text-[10px] uppercase tracking-[0.1em] rounded-full border ${
                chartDataPanelOpen && chartDataPanelMode === "data"
                  ? "chip-mint border-transparent font-medium"
                  : "border-border/60 text-slate-600"
              }`}
              style={chartDataPanelOpen && chartDataPanelMode === "data" ? activeControlStyle : undefined}
            >
              Data
            </button>
            {canExportSourceData ? (
              <button
                type="button"
                onClick={handleCopyChartData}
                data-testid="arad-view-copy-panel"
                title="Kopírovat data pro Excel"
                className="h-7 px-3 text-[10px] uppercase tracking-[0.1em] rounded-full border border-border/60 text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
              >
                <ClipboardCopy className="h-3.5 w-3.5 inline mr-1" />
                Copy
              </button>
            ) : null}
            {canExportSourceData ? (
              <button
                type="button"
                onClick={handleOpenOlapCube}
                data-testid="arad-view-olap-toggle-panel"
                title="Převést aktuální pozorování grafu do OLAP/star-schema formátu"
                className={`h-7 px-3 text-[10px] uppercase tracking-[0.1em] rounded-full border ${
                  chartDataPanelOpen && chartDataPanelMode === "olap"
                    ? "border-transparent font-medium"
                    : "border-violet-200 bg-violet-50 text-violet-800 hover:bg-violet-100"
                }`}
                style={chartDataPanelOpen && chartDataPanelMode === "olap" ? activeControlStyle : undefined}
              >
                <Database className="h-3.5 w-3.5 inline mr-1" />
                OLAP
              </button>
            ) : null}
            <ExportMenu
              compact={false}
              title={`${heading || "ARAD"}${unit ? ` (${unit})` : ""}`}
              subtitle={subtitle && subtitle !== heading ? subtitle : ""}
              columns={exportColumns}
              rows={exportRows}
              chartTargetRef={chartCaptureRef}
              enableChartImageExport={showChartPanel}
              lockSourceData={sourceDataLocked}
              chartContract={aradChartContract}
              meCatalogWidgetId={
                widget?.config?.source_type === "external_catalog" && widget?.id ? widget.id : null
              }
            />
          </div>
        </div>
      ) : null}
    </>
  );
}
