import React, { useMemo, useRef } from "react";
import { resolveChartSize, resolveResponsiveHeight } from "./chartDimensions";
import { CHART_SIZE_VARIANTS } from "./chartTypes";
import {
  CHART_CARD_CLASS,
  CHART_HEADER_CLASS,
  CHART_TITLE_CLASS,
  CHART_SUBTITLE_CLASS,
  CHART_TOOLBAR_CLASS,
  CHART_BODY_SLOT_CLASS,
  getChartTheme,
} from "./chartDashboardStyle";

/**
 * Dashboard-style chart card — widget-panel-white + chart-body-slot.
 */
export default function ChartContainer({
  children,
  size = CHART_SIZE_VARIANTS.STANDARD,
  height,
  className = "",
  title,
  subtitle,
  exportRoot = true,
  viewportWidth,
  toolbar,
  table,
  footer,
  testId,
  chartColor = null,
}) {
  const containerRef = useRef(null);
  const sizeSpec = useMemo(() => resolveChartSize(size), [size]);
  const chartTheme = useMemo(() => getChartTheme(chartColor), [chartColor]);
  const resolvedHeight = height ?? resolveResponsiveHeight(sizeSpec, viewportWidth ?? (typeof window !== "undefined" ? window.innerWidth : 1024));

  return (
    <div
      className={`chart-system-container ${CHART_CARD_CLASS} ${className}`}
      data-testid={testId}
    >
      {(title || subtitle) && (
        <div className={CHART_HEADER_CLASS}>
          {title ? (
            <h4 className={CHART_TITLE_CLASS} title={title}>
              {title}
            </h4>
          ) : null}
          {subtitle ? <p className={CHART_SUBTITLE_CLASS}>{subtitle}</p> : null}
        </div>
      )}
      {toolbar ? <div className={CHART_TOOLBAR_CLASS}>{toolbar}</div> : null}
      <div
        ref={containerRef}
        className="flex-1 min-h-0 flex flex-col"
        style={{ background: chartTheme.bodyBg || "transparent" }}
        {...(exportRoot ? { "data-chart-export-root": true } : {})}
      >
        <div
          className={CHART_BODY_SLOT_CLASS}
          style={{
            minHeight: resolvedHeight === "100%" ? sizeSpec.plotMinHeight : resolvedHeight,
          }}
        >
          {children}
        </div>
        {table ? (
          <div className="chart-system-table shrink-0 border-t border-border/40 bg-white/90 px-3 py-2">
            {table}
          </div>
        ) : null}
        {footer ? <div className="chart-system-footer shrink-0 px-3 py-2">{footer}</div> : null}
      </div>
    </div>
  );
}

export function useChartContainerRef() {
  return useRef(null);
}
