import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import ChartValueCompareSummary from "@/charts/ChartValueCompareSummary";
import { computeValueCompareStats } from "@/charts/chartValueCompareStats";
import { fmtCompact } from "@/lib/format";
import { chartIconGlyph } from "@/lib/chartIconPresets";
import { MAP_PROJECTIONS, buildGeoValueMap, colorScale, geographyIso2 } from "@/lib/chartGeoMapData";
import {
  computeFeaturesViewBox,
  computeSideLegendMapLayout,
  featureFillColor,
  featureToSvgPath,
  filterFeaturesByRegion,
  loadWorldAtlasTopology,
  shouldUseGeoMapSideLegend,
  topologyToCountryFeatures,
} from "@/lib/geoMapRenderer";
import { macroGeoLabelCs } from "@/lib/macroGeoLabels";
import { LoadingSpinner } from "@/components/ui/loading";

const MAX_PICTOGRAM_ICONS = 24;
const MAX_ICON_CHART_ICONS = 20;

function coerceY(row) {
  const v = Number(row?.y);
  return Number.isFinite(v) ? v : null;
}

/** Horizontální nebo svislý icon / emotikon graf. */
export function IconChartView({
  rows = [],
  unit = "",
  seriesIcons = {},
  defaultIcon = "chart",
  compact = false,
  orientation = "horizontal",
}) {
  const items = useMemo(() => {
    return (rows || [])
      .map((row) => ({ label: String(row?.x ?? ""), value: coerceY(row) }))
      .filter((r) => r.label && r.value != null);
  }, [rows]);

  const maxVal = useMemo(() => Math.max(1, ...items.map((r) => r.value)), [items]);
  const vertical = String(orientation || "").toLowerCase() === "vertical";

  if (!items.length) {
    return (
      <div className="flex h-full min-h-[160px] items-center justify-center px-4 text-center text-xs text-slate-500">
        Pro ikonový graf potřebujete kategorie s hodnotami (např. režim „Poslední hodnoty“ nebo upload).
      </div>
    );
  }

  return (
    <div className={`h-full overflow-y-auto ${compact ? "p-2 space-y-2" : "p-3 space-y-3"}`}>
      {items.map((item) => {
        const glyph = chartIconGlyph(seriesIcons[item.label] || defaultIcon);
        const count = Math.max(1, Math.round((item.value / maxVal) * MAX_ICON_CHART_ICONS));
        return (
          <div
            key={item.label}
            className={`flex gap-x-2 gap-y-1 ${vertical ? "flex-col items-start" : "flex-wrap items-center"}`}
          >
            <span className={`${compact ? "w-20" : "w-28"} shrink-0 truncate text-xs text-slate-700`} title={item.label}>
              {item.label}
            </span>
            <span
              className={`flex gap-0.5 text-base leading-none ${vertical ? "flex-col items-start" : "flex-1 flex-row flex-wrap items-center"}`}
              aria-hidden
            >
              {Array.from({ length: count }, (_, i) => (
                <span key={i}>{glyph}</span>
              ))}
            </span>
            <span className="shrink-0 font-mono text-[11px] text-slate-600">
              {fmtCompact(item.value)}
              {unit ? ` ${unit}` : ""}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/** Pictogram — opakované ikony s pevným měřítkem (1 ikona = N jednotek). */
export function PictogramChartView({
  rows = [],
  unit = "",
  pictogramIcon = "person",
  pictogramUnit = 1000,
  compact = false,
  orientation = "horizontal",
}) {
  const items = useMemo(() => {
    return (rows || [])
      .map((row) => ({ label: String(row?.x ?? ""), value: coerceY(row) }))
      .filter((r) => r.label && r.value != null);
  }, [rows]);

  const unitSize = Math.max(1, Number(pictogramUnit) || 1);
  const glyph = chartIconGlyph(pictogramIcon, "👤");
  const vertical = String(orientation || "").toLowerCase() === "vertical";

  if (!items.length) {
    return (
      <div className="flex h-full min-h-[160px] items-center justify-center px-4 text-center text-xs text-slate-500">
        Pro pictogram potřebujete kategorie s hodnotami.
      </div>
    );
  }

  return (
    <div className={`h-full overflow-y-auto ${compact ? "p-2 space-y-2" : "p-3 space-y-3"}`}>
      <p className="text-[10px] text-slate-500 mb-1">
        1 ikona = {fmtCompact(unitSize)}
        {unit ? ` ${unit}` : ""}
      </p>
      {items.map((item) => {
        const rawCount = Math.ceil(item.value / unitSize);
        const count = Math.min(MAX_PICTOGRAM_ICONS, Math.max(1, rawCount));
        const truncated = rawCount > MAX_PICTOGRAM_ICONS;
        return (
          <div key={item.label} className="space-y-1">
            <div className="flex items-center justify-between gap-2">
              <span className={`truncate text-xs font-medium text-slate-800 ${compact ? "max-w-[40%]" : ""}`}>
                {item.label}
              </span>
              <span className="font-mono text-[11px] text-slate-600 shrink-0">
                {fmtCompact(item.value)}
                {unit ? ` ${unit}` : ""}
              </span>
            </div>
            <div className="flex flex-wrap gap-0.5 text-base leading-none">
              <span
                className={`flex gap-0.5 ${vertical ? "flex-col items-start" : "flex-row flex-wrap"}`}
              >
                {Array.from({ length: count }, (_, i) => (
                  <span key={i}>{glyph}</span>
                ))}
              </span>
              {truncated ? (
                <span className="text-[10px] text-slate-400 self-center ml-1">+{rawCount - MAX_PICTOGRAM_ICONS}</span>
              ) : null}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** Barevná škála mapy (gradient) — statistiky jsou v KPI kartách nad mapou. */
function GeoMapScaleLegend({ min, max, median, unit, baseRgb, vertical = false, inline = false }) {
  const gradient = useMemo(() => {
    const steps = 12;
    if (!Number.isFinite(min) || !Number.isFinite(max)) return "linear-gradient(to right, #e2e8f0, #e2e8f0)";
    if (min === max) {
      const solid = colorScale(min, min, max, baseRgb);
      return vertical
        ? `linear-gradient(to bottom, ${solid}, ${solid})`
        : `linear-gradient(to right, ${solid}, ${solid})`;
    }
    const parts = [];
    for (let i = 0; i <= steps; i += 1) {
      const t = i / steps;
      const v = min + t * (max - min);
      const stop = vertical ? 100 - t * 100 : t * 100;
      parts.push(`${colorScale(v, min, max, baseRgb)} ${stop.toFixed(1)}%`);
    }
    return vertical
      ? `linear-gradient(to bottom, ${parts.join(", ")})`
      : `linear-gradient(to right, ${parts.join(", ")})`;
  }, [min, max, baseRgb, vertical]);

  const verticalColorSteps = useMemo(() => {
    const steps = 14;
    if (!Number.isFinite(min) || !Number.isFinite(max)) {
      return Array.from({ length: steps }, () => "#e2e8f0");
    }
    if (min === max) {
      const solid = colorScale(min, min, max, baseRgb);
      return Array.from({ length: steps }, () => solid);
    }
    return Array.from({ length: steps }, (_, i) => {
      const t = 1 - i / (steps - 1);
      return colorScale(min + t * (max - min), min, max, baseRgb);
    });
  }, [min, max, baseRgb]);

  const unitLabel = String(unit || "").trim();
  const medianPct =
    Number.isFinite(median) && Number.isFinite(min) && Number.isFinite(max) && min !== max
      ? Math.max(0, Math.min(100, ((median - min) / (max - min)) * 100))
      : null;

  const scaleBarStyle = vertical
    ? { backgroundImage: gradient, backgroundColor: colorScale(min, min, max, baseRgb) }
    : { background: gradient };

  return (
    <div
      className={
        vertical
          ? inline
            ? "geo-map-legend geo-map-legend--vertical pointer-events-none relative z-10 shrink-0 translate-x-0.5 rounded-md border border-slate-200/90 bg-white/95 px-1 py-1.5 shadow-sm"
            : "geo-map-legend geo-map-legend--vertical pointer-events-none absolute right-0 top-1/2 z-10 -translate-y-1/2 rounded-md border border-slate-200/90 bg-white/95 px-1 py-1.5 shadow-sm"
          : "geo-map-legend pointer-events-none absolute bottom-2 right-2 z-10 max-w-[min(11rem,38vw)] rounded-md border border-slate-200/90 bg-white/95 px-2 py-1.5 shadow-sm"
      }
      aria-label={`Škála mapy ${fmtCompact(min)} až ${fmtCompact(max)}${unitLabel ? ` ${unitLabel}` : ""}`}
    >
      {vertical ? (
        <div className="flex items-stretch gap-1">
          <div className="relative flex h-24 w-2.5 shrink-0 flex-col overflow-hidden rounded-sm border border-slate-200/70">
            {verticalColorSteps.map((fill, idx) => (
              <div
                key={`geo-legend-step-${idx}`}
                className="min-h-0 flex-1"
                style={{ backgroundColor: fill }}
                aria-hidden
              />
            ))}
            {medianPct != null ? (
              <div
                className="absolute left-0 right-0 h-[2px] -translate-y-1/2 rounded-full bg-slate-700/80"
                style={{ top: `${100 - medianPct}%` }}
                aria-hidden
              />
            ) : null}
          </div>
          <div className="flex h-24 flex-col justify-between py-0.5 text-[10px] font-mono leading-none text-slate-700">
            <span>{fmtCompact(max)}</span>
            <span>{fmtCompact(min)}</span>
          </div>
        </div>
      ) : (
        <>
          <div className="relative">
            <div
              className="h-2.5 w-full min-w-[5rem] rounded-sm border border-slate-200/70"
              style={scaleBarStyle}
              aria-hidden
            />
            {medianPct != null ? (
              <div
                className="absolute -top-0.5 bottom-[-2px] w-[2px] -translate-x-1/2 rounded-full bg-slate-700/75"
                style={{ left: `${medianPct}%` }}
                aria-hidden
              />
            ) : null}
          </div>
          <div className="mt-1 flex items-baseline justify-between gap-2 text-[10px] font-mono leading-none text-slate-700">
            <span>{fmtCompact(min)}</span>
            <span>{fmtCompact(max)}</span>
          </div>
        </>
      )}
      {unitLabel ? (
        <div
          className={`truncate text-center text-[9px] leading-tight text-slate-500 ${vertical ? "mt-1 max-w-[3.5rem]" : "mt-0.5"}`}
          title={unitLabel}
        >
          {unitLabel}
        </div>
      ) : null}
      <div className={`flex items-center gap-1 text-[9px] text-slate-500 ${vertical ? "mt-0.5 justify-center" : "mt-1"}`}>
        <span className="inline-block h-2 w-2 shrink-0 rounded-[2px] border border-slate-300 bg-[#e2e8f0]" aria-hidden />
        <span>bez dat</span>
      </div>
    </div>
  );
}

/** Choropleth mapa — ČR / Evropa / svět (SVG, bez react-simple-maps). */
export function GeoMapChartView({
  rows = [],
  region = "europe",
  unit = "",
  primaryColor = "#1f8cdb",
  chartCompact = false,
  miniChartMode = false,
  veryNarrowWidget = false,
  fsExpand = false,
  kpiMode = "full",
}) {
  const valueByIso = useMemo(() => buildGeoValueMap(rows), [rows]);
  const values = useMemo(() => [...valueByIso.values()].filter(Number.isFinite), [valueByIso]);
  const min = values.length ? Math.min(...values) : 0;
  const max = values.length ? Math.max(...values) : 1;
  const compareStats = useMemo(() => {
    const fromRows = computeValueCompareStats(rows);
    if (fromRows) return fromRows;
    const pts = [...valueByIso.entries()]
      .filter(([, v]) => Number.isFinite(v))
      .map(([iso, v]) => ({ x: macroGeoLabelCs(iso, iso) || iso, y: v }));
    return computeValueCompareStats(pts);
  }, [rows, valueByIso]);
  const effectiveKpiMode = useMemo(() => {
    if (!compareStats) return "hidden";
    if (kpiMode !== "hidden") return kpiMode;
    if (fsExpand) return "full";
    if (miniChartMode || veryNarrowWidget) return "compact";
    return "compact";
  }, [compareStats, kpiMode, fsExpand, miniChartMode, veryNarrowWidget]);
  const projCfg = MAP_PROJECTIONS[region] || MAP_PROJECTIONS.europe;
  const [features, setFeatures] = useState([]);
  const [mapError, setMapError] = useState("");
  const [loading, setLoading] = useState(true);
  const matchedCount = valueByIso.size;
  const mapPlotRef = useRef(null);
  const [plotSize, setPlotSize] = useState({ w: 0, h: 0 });
  const [hover, setHover] = useState(null);

  const useSideLegend = useMemo(
    () =>
      shouldUseGeoMapSideLegend({
        plotWidth: plotSize.w,
        veryNarrowWidget,
      }),
    [plotSize.w, veryNarrowWidget]
  );

  const clampTooltipPos = useCallback((x, y) => {
    const root = mapPlotRef.current;
    if (!root) return { x, y };
    const pad = 8;
    const maxX = Math.max(pad, root.clientWidth - 196);
    return {
      x: Math.min(Math.max(pad, x), maxX),
      y: Math.max(pad, y),
    };
  }, []);

  const showCountryHover = useCallback((iso, val, nativeEvent) => {
    const root = mapPlotRef.current;
    if (!root || !iso || !nativeEvent) return;
    const rect = root.getBoundingClientRect();
    const pos = clampTooltipPos(
      nativeEvent.clientX - rect.left + 12,
      nativeEvent.clientY - rect.top - 10
    );
    setHover({
      iso,
      label: macroGeoLabelCs(iso, iso),
      value: val,
      ...pos,
    });
  }, [clampTooltipPos]);

  const moveCountryHover = useCallback((nativeEvent) => {
    const root = mapPlotRef.current;
    if (!root || !nativeEvent) return;
    setHover((prev) => {
      if (!prev) return prev;
      const rect = root.getBoundingClientRect();
      const pos = clampTooltipPos(
        nativeEvent.clientX - rect.left + 12,
        nativeEvent.clientY - rect.top - 10
      );
      return { ...prev, ...pos };
    });
  }, [clampTooltipPos]);

  const clearCountryHover = useCallback(() => setHover(null), []);

  useLayoutEffect(() => {
    const el = mapPlotRef.current;
    if (!el) return undefined;
    const sync = () => {
      const w = Math.floor(el.clientWidth || 0);
      const h = Math.floor(el.clientHeight || 0);
      if (w <= 0 || h <= 0) return;
      setPlotSize((prev) => (prev.w === w && prev.h === h ? prev : { w, h }));
    };
    sync();
    const ro = new ResizeObserver(sync);
    ro.observe(el);
    return () => ro.disconnect();
  }, [loading, mapError, features.length, useSideLegend]);

  const mapFrame = useMemo(
    () => computeFeaturesViewBox(features, projCfg, { region }),
    [features, projCfg, region]
  );

  const sideMapLayout = useMemo(() => {
    if (!useSideLegend) return null;
    return computeSideLegendMapLayout({
      viewBox: mapFrame.viewBox,
      plotWidth: plotSize.w,
      plotHeight: plotSize.h,
    });
  }, [useSideLegend, mapFrame.viewBox, plotSize.w, plotSize.h]);

  const baseRgb = useMemo(() => {
    const hex = String(primaryColor || "#1f8cdb").replace("#", "");
    if (hex.length === 6) {
      return [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16)];
    }
    return [31, 140, 219];
  }, [primaryColor]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setMapError("");
    loadWorldAtlasTopology()
      .then((topology) => {
        if (cancelled) return;
        const all = topologyToCountryFeatures(topology);
        setFeatures(filterFeaturesByRegion(all, region));
      })
      .catch(() => {
        if (!cancelled) setMapError("Mapu se nepodařilo načíst.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [region]);

  if (loading) {
    return (
      <div className="flex min-h-[240px] w-full items-center justify-center">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  if (mapError) {
    return (
      <div className="flex min-h-[240px] w-full items-center justify-center px-4 text-center text-xs text-red-600">
        {mapError}
      </div>
    );
  }

  if (!features.length) {
    return (
      <div className="flex min-h-[240px] w-full items-center justify-center px-4 text-center text-xs text-slate-500">
        Geometrii mapy se nepodařilo načíst. Obnovte stránku nebo zkuste jiný region (Evropa / svět).
      </div>
    );
  }

  return (
    <div
      className="geo-map-chart-root relative flex h-full min-h-0 w-full flex-1 flex-col gap-1"
      data-geo-map-chart="1"
      data-geo-side-legend={useSideLegend ? "1" : "0"}
    >
      {compareStats && effectiveKpiMode !== "hidden" ? (
        <div className="w-full shrink-0" data-testid="geo-map-value-compare-summary">
          <ChartValueCompareSummary
            stats={compareStats}
            unit={unit}
            miniChartMode={miniChartMode}
            veryNarrowWidget={veryNarrowWidget}
            chartCompact={chartCompact}
            fsExpand={fsExpand}
            kpiMode={effectiveKpiMode}
          />
        </div>
      ) : null}
      <div ref={mapPlotRef} className="relative flex min-h-0 flex-1 flex-col">
      {useSideLegend && sideMapLayout ? (
        <div className="flex h-full min-h-0 w-full items-center justify-center gap-1.5">
          <div
            className="relative h-full max-h-full shrink-0 -translate-x-0.5"
            style={{ width: sideMapLayout.mapWidth }}
          >
            <svg
              width="100%"
              height="100%"
              viewBox={mapFrame.viewBox}
              className="geo-map-chart-svg block h-full w-full"
              preserveAspectRatio="xMidYMid meet"
              overflow="hidden"
              role="img"
              aria-label="Mapa zemí"
              style={{ background: "#f8fafc" }}
              onMouseLeave={clearCountryHover}
            >
              {features.map((feat, idx) => {
                const iso = geographyIso2(feat);
                const val = iso ? valueByIso.get(iso) : undefined;
                const d = featureToSvgPath(feat, projCfg, region);
                if (!d) return null;
                const hasValue = Number.isFinite(val);
                const isHovered = Boolean(iso && hover?.iso === iso);
                const fill = featureFillColor(feat, valueByIso, min, max, baseRgb);
                const title =
                  iso && hasValue
                    ? `${macroGeoLabelCs(iso, iso)}: ${fmtCompact(val)}${unit ? ` ${unit}` : ""}`
                    : iso
                      ? macroGeoLabelCs(iso, iso)
                      : "";
                return (
                  <path
                    key={`${iso || "x"}-${idx}`}
                    d={d}
                    fill={hasValue ? fill : "#e2e8f0"}
                    stroke={isHovered ? "#1d4ed8" : "#94a3b8"}
                    strokeWidth={isHovered ? 1.4 : 0.6}
                    className={iso ? "geo-map-country" : undefined}
                    style={{
                      cursor: iso ? "pointer" : "default",
                      filter: isHovered ? "brightness(0.93) saturate(1.08)" : undefined,
                      transition: "filter 120ms ease, stroke-width 120ms ease",
                    }}
                    onMouseEnter={iso ? (e) => showCountryHover(iso, val, e.nativeEvent) : undefined}
                    onMouseMove={iso ? (e) => moveCountryHover(e.nativeEvent) : undefined}
                    onMouseLeave={iso ? clearCountryHover : undefined}
                  >
                    <title>{title}</title>
                  </path>
                );
              })}
            </svg>
          </div>
          {matchedCount > 0 ? (
            <GeoMapScaleLegend
              min={min}
              max={max}
              median={compareStats?.median}
              unit={unit}
              baseRgb={baseRgb}
              vertical
              inline
            />
          ) : null}
        </div>
      ) : (
      <svg
        width={plotSize.w > 0 ? plotSize.w : "100%"}
        height={plotSize.h > 0 ? plotSize.h : "100%"}
        viewBox={mapFrame.viewBox}
        className="geo-map-chart-svg block h-full max-h-full min-h-0 w-full max-w-full flex-1"
        preserveAspectRatio="xMidYMid meet"
        overflow="hidden"
        role="img"
        aria-label="Mapa zemí"
        style={{ background: "#f8fafc" }}
        onMouseLeave={clearCountryHover}
      >
        {features.map((feat, idx) => {
          const iso = geographyIso2(feat);
          const val = iso ? valueByIso.get(iso) : undefined;
          const d = featureToSvgPath(feat, projCfg, region);
          if (!d) return null;
          const hasValue = Number.isFinite(val);
          const isHovered = Boolean(iso && hover?.iso === iso);
          const fill = featureFillColor(feat, valueByIso, min, max, baseRgb);
          const title =
            iso && hasValue
              ? `${macroGeoLabelCs(iso, iso)}: ${fmtCompact(val)}${unit ? ` ${unit}` : ""}`
              : iso
                ? macroGeoLabelCs(iso, iso)
                : "";
          return (
            <path
              key={`${iso || "x"}-${idx}`}
              d={d}
              fill={hasValue ? fill : "#e2e8f0"}
              stroke={isHovered ? "#1d4ed8" : "#94a3b8"}
              strokeWidth={isHovered ? 1.4 : 0.6}
              className={iso ? "geo-map-country" : undefined}
              style={{
                cursor: iso ? "pointer" : "default",
                filter: isHovered ? "brightness(0.93) saturate(1.08)" : undefined,
                transition: "filter 120ms ease, stroke-width 120ms ease",
              }}
              onMouseEnter={iso ? (e) => showCountryHover(iso, val, e.nativeEvent) : undefined}
              onMouseMove={iso ? (e) => moveCountryHover(e.nativeEvent) : undefined}
              onMouseLeave={iso ? clearCountryHover : undefined}
            >
              <title>{title}</title>
            </path>
          );
        })}
      </svg>
      )}
      {hover ? (
        <div
          className="geo-map-tooltip pointer-events-none absolute z-20 max-w-[220px] rounded-md border border-slate-200/90 bg-white/95 px-2.5 py-1.5 text-[11px] leading-snug text-slate-800 shadow-md"
          style={{ left: hover.x, top: hover.y }}
          role="tooltip"
        >
          <div className="font-semibold text-slate-900">{hover.label}</div>
          <div className="mt-0.5 font-mono text-slate-700">
            {Number.isFinite(hover.value) ? (
              <>
                {fmtCompact(hover.value)}
                {unit ? ` ${unit}` : ""}
              </>
            ) : (
              <span className="text-slate-500">bez dat</span>
            )}
          </div>
        </div>
      ) : null}
      {matchedCount === 0 ? (
        <div className="pointer-events-none absolute inset-x-0 top-1/2 z-[1] -translate-y-1/2 px-4 text-center text-xs text-slate-600">
          Pro mapu přidejte data se zeměmi (ISO kód nebo název, např. CZ, DE, Německo) — ideálně režim
          porovnání zemí / poslední hodnoty.
        </div>
      ) : null}
      {matchedCount > 0 && !useSideLegend ? (
        <GeoMapScaleLegend
          min={min}
          max={max}
          median={compareStats?.median}
          unit={unit}
          baseRgb={baseRgb}
        />
      ) : null}
      </div>
    </div>
  );
}

export default function SpecialChartView({
  kind,
  rows = [],
  unit = "",
  compact = false,
  visualConfig = {},
  miniChartMode = false,
  veryNarrowWidget = false,
  fsExpand = false,
  kpiMode = "full",
}) {
  const k = String(kind || "").toLowerCase();
  if (k === "icon_chart") {
    return (
      <IconChartView
        rows={rows}
        unit={unit}
        compact={compact}
        defaultIcon={visualConfig.defaultIcon || "chart"}
        seriesIcons={visualConfig.seriesIcons || {}}
        orientation={visualConfig.iconOrientation || "horizontal"}
      />
    );
  }
  if (k === "pictogram") {
    return (
      <PictogramChartView
        rows={rows}
        unit={unit}
        compact={compact}
        pictogramIcon={visualConfig.pictogramIcon || "person"}
        pictogramUnit={visualConfig.pictogramUnit ?? 1000}
        orientation={visualConfig.iconOrientation || "horizontal"}
      />
    );
  }
  if (k === "geo_map") {
    return (
      <GeoMapChartView
        rows={rows}
        unit={unit}
        region={visualConfig.mapRegion || "europe"}
        primaryColor={visualConfig.primaryColor}
        chartCompact={compact}
        miniChartMode={miniChartMode}
        veryNarrowWidget={veryNarrowWidget}
        fsExpand={fsExpand}
        kpiMode={kpiMode}
      />
    );
  }
  return null;
}
