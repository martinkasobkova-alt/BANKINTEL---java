import React, { useMemo } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  Legend,
  Line,
  LineChart,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { SafeRechartsContainer } from "@/lib/SafeRechartsContainer";
import { fmtCompact } from "@/lib/format";
import { mergeRechartsTooltipProps } from "@/lib/rechartsTooltipShared";
import {
  contractFromExploreLegacy,
  contractToRechartsSeries,
  contractToRechartsWide,
  contractToSingleSeriesRows,
  isLatestDataMode,
  shouldUseDualAxis,
} from "./chartDataContract";
import { resolveChartSize } from "./chartDimensions";
import { formatAxisTick, formatTimeAxisTick } from "./chartFormatters";
import { enrichMergedWithTrends } from "./chartAnnotations";
import { CHART_SIZE_VARIANTS } from "./chartTypes";
import {
  getBarChartValueAxisSpec,
  getLineChartValueAxisSpec,
  BAR_VALUE_AXIS,
  chartBarPointValue,
  chartRowsWithZeroBaselineBars,
  coerceChartNumeric,
  buildStickyYAxisSpec,
  buildRechartsValueDomain,
  getSafeYDomain,
  buildSafeNumericAxis,
} from "./chartScales";
import {
  CHART_BODY_SLOT_CLASS,
  CHART_PLOT_CLASS,
  dashboardPlotMargin,
  estimateLegendHeight,
  getChartTheme,
} from "./chartDashboardStyle";
import { DASHBOARD_SERIES_COLORS } from "@/lib/dashboardChartStyle";
import ChartTooltip from "./ChartTooltip";
import FrozenYAxisGutter from "./FrozenYAxisGutter";
import {
  barChartHorizontalScrollEnabled,
  categoryAxisLabelMax,
  computeChartPlotMargins,
  chartScrollMinWidth,
  ellipsizeLabel,
  latestBarXTickProps,
  makeCategoryAxisTick,
} from "./chartPlotHelpers";
import { isPointerInsidePlotViewBox } from "@/lib/rechartsPlotTooltip";

function ChartTooltipBody(props) {
  if (!props.active) return null;
  if (!isPointerInsidePlotViewBox(props.coordinate, props.viewBox)) return null;
  return <ChartTooltip {...props} />;
}

const BAR_CATEGORY_COLORS = DASHBOARD_SERIES_COLORS;

export default function ChartRenderer({
  contract,
  merged,
  series,
  title = "",
  unit = "",
  secondaryUnit = "",
  height,
  size = CHART_SIZE_VARIANTS.STANDARD,
  compact: compactProp,
  dualAxis: dualAxisProp,
  showTrendLine = false,
  showLegend: showLegendProp,
  chartType: chartTypeProp,
  latestDataMode: latestDataModeProp,
}) {
  const sizeSpec = useMemo(() => resolveChartSize(size), [size]);
  const compact = compactProp ?? size === CHART_SIZE_VARIANTS.COMPACT;
  const chartTheme = useMemo(() => getChartTheme(null), []);

  const normalizedContract = useMemo(() => {
    if (contract?.data) return contract;
    return contractFromExploreLegacy({ merged, series, title, unit });
  }, [contract, merged, series, title, unit]);

  const isLatest = latestDataModeProp ?? isLatestDataMode(normalizedContract);
  const resolvedChartType =
    chartTypeProp || normalizedContract.chart_type || "line";
  const kind = String(resolvedChartType).toLowerCase();

  const lines = useMemo(() => contractToRechartsSeries(normalizedContract), [normalizedContract]);
  const coloredLines = useMemo(
    () =>
      lines.map((line, idx) => ({
        ...line,
        color: line.color || DASHBOARD_SERIES_COLORS[idx % DASHBOARD_SERIES_COLORS.length],
      })),
    [lines]
  );

  const isSingleSeries = coloredLines.length <= 1;
  const useSingleSeriesRows =
    isLatest ||
    (kind === "bar" && isSingleSeries);

  const singleSeriesRows = useMemo(() => {
    if (!useSingleSeriesRows) return null;
    return contractToSingleSeriesRows(normalizedContract);
  }, [useSingleSeriesRows, normalizedContract]);

  const plotData = useMemo(() => {
    if (useSingleSeriesRows && singleSeriesRows?.length) {
      if (kind === "bar" || isLatest) {
        return singleSeriesRows.map((r) => ({ x: r.x, y: r.y, period: r.period ?? r.x }));
      }
    }
    return contractToRechartsWide(normalizedContract);
  }, [useSingleSeriesRows, singleSeriesRows, normalizedContract, kind, isLatest]);

  const dataWithTrends = useMemo(() => {
    if (!showTrendLine || useSingleSeriesRows) return plotData;
    return enrichMergedWithTrends(plotData, coloredLines);
  }, [plotData, coloredLines, showTrendLine, useSingleSeriesRows]);

  const dataLines = useMemo(() => coloredLines.filter((line) => !line.isTrend), [coloredLines]);

  const useDualAxis = useMemo(() => {
    if (dualAxisProp) return true;
    if (useSingleSeriesRows) return false;
    return shouldUseDualAxis(normalizedContract);
  }, [dualAxisProp, normalizedContract, useSingleSeriesRows]);

  const trendLines = useMemo(() => {
    if (!showTrendLine || useSingleSeriesRows) return [];
    return coloredLines
      .filter((line) => !line.isTrend)
      .map((line) => ({
        key: `${line.key}__trend`,
        name: `${line.name} · trend`,
        color: line.color,
        strokeDasharray: "6 4",
      }));
  }, [coloredLines, showTrendLine, useSingleSeriesRows]);

  const showLegend = showLegendProp ?? (dataLines.length > 1 && sizeSpec.showLegendDefault);
  const legendLabels = useMemo(() => dataLines.map((line) => line.name), [dataLines]);
  const legendHeight = useMemo(
    () => estimateLegendHeight(legendLabels, { compact }),
    [legendLabels, compact]
  );

  const gridStroke = chartTheme.grid;
  const axisStroke = chartTheme.grid;
  const axisColor = chartTheme.axis;
  const tickStyle = {
    fontSize: sizeSpec.tickFontSize,
    fill: axisColor,
    fontFamily: "JetBrains Mono, ui-monospace, monospace",
  };

  const leftUnit = unit || normalizedContract.series?.find((s) => s.axis !== "right")?.unit || "";
  const rightUnit =
    secondaryUnit || normalizedContract.series?.find((s) => s.axis === "right")?.unit || "";

  const useBarChart = kind === "bar";
  const useAreaChart = kind === "area";

  const latestBarMode = useBarChart && isLatest;
  const nPoints = dataWithTrends?.length || 0;
  const lineTimeSeriesTilt = !useBarChart && !latestBarMode && nPoints > 8;
  const barScrollable =
    useBarChart &&
    isSingleSeries &&
    barChartHorizontalScrollEnabled(nPoints, { latestBarMode: isLatest, mobile: false });
  // Dense time series remain responsive and let Recharts thin axis labels. A
  // scrollbar is useful only for exceptionally long series; enabling it for a
  // normal 2-3 year monthly chart clipped the right and bottom axes in cards.
  const lineScrollable = !useBarChart && !useAreaChart && nPoints > 72;
  const chartScrollable = barScrollable || lineScrollable;
  const scrollMinWidth = chartScrollable
    ? chartScrollMinWidth(nPoints, { compact, isBar: useBarChart, latestBarMode })
    : null;

  const plotMargins = useMemo(
    () =>
      computeChartPlotMargins({
        latestBarMode,
        compact,
        n: nPoints,
      }),
    [latestBarMode, compact, nPoints]
  );

  const barYAxisSpec = useMemo(() => {
    if (!useBarChart) return null;
    const rows = (singleSeriesRows || dataWithTrends).map((r) => ({
      y: coerceChartNumeric(r.y ?? r[dataLines[0]?.key]),
    }));
    return getBarChartValueAxisSpec(rows, [], compact ? 4 : 5);
  }, [useBarChart, singleSeriesRows, dataWithTrends, dataLines, compact]);

  const lineYAxisSpec = useMemo(() => {
    if (useBarChart) return null;
    const tickCount = compact ? 4 : 5;
    if (useSingleSeriesRows) {
      return getLineChartValueAxisSpec(
        (singleSeriesRows || []).map((r) => ({ y: r.y })),
        [],
        tickCount
      );
    }
    const leftKeys = dataLines
      .filter((line) => line.axis !== "right" && line.yAxisId !== "right")
      .map((line) => line.key);
    const keys = leftKeys.length ? leftKeys : dataLines.map((line) => line.key);
    const fallbackValues = (dataWithTrends || []).flatMap((row) =>
      keys.map((key) => coerceChartNumeric(row[key])).filter((v) => v != null)
    );
    const fallback = buildSafeNumericAxis(fallbackValues, tickCount);
    return getSafeYDomain(dataWithTrends, keys, fallback, tickCount);
  }, [useBarChart, useSingleSeriesRows, singleSeriesRows, dataWithTrends, dataLines, compact]);

  const stickyYAxisSpec = useMemo(() => {
    if (!lineScrollable || useBarChart || !lineYAxisSpec) return null;
    const lineValues = useSingleSeriesRows
      ? (singleSeriesRows || []).map((r) => r.y).filter((v) => v != null)
      : (dataWithTrends || []).flatMap((row) =>
          dataLines.map((line) => coerceChartNumeric(row[line.key])).filter((v) => v != null)
        );
    return buildStickyYAxisSpec({
      values: lineValues,
      chartType: "line",
      plotMargin: plotMargins,
      axisGutterWidth: sizeSpec.axisGutterWidth,
      tickFontSize: sizeSpec.tickFontSize,
      tickCount: compact ? 4 : 5,
    });
  }, [
    lineScrollable,
    useBarChart,
    lineYAxisSpec,
    singleSeriesRows,
    dataWithTrends,
    dataLines,
    useSingleSeriesRows,
    plotMargins,
    sizeSpec,
    compact,
  ]);

  const margin = useMemo(() => {
    const base = dashboardPlotMargin({
      multiSeries: dataLines.length > 1,
      compact,
      legendExtra: showLegend ? legendHeight - 28 : 0,
    });
    const tiltBottom = lineTimeSeriesTilt ? (compact ? 32 : 40) : null;
    return {
      ...base,
      top: useBarChart
        ? Math.max(plotMargins.top ?? base.top ?? 8, 22)
        : plotMargins.top ?? base.top,
      bottom: tiltBottom ?? plotMargins.bottom ?? base.bottom,
    };
  }, [dataLines.length, compact, showLegend, legendHeight, plotMargins, lineTimeSeriesTilt, useBarChart]);

  const tooltipProps = mergeRechartsTooltipProps({
    content: (props) => (
      <ChartTooltipBody {...props} unit={leftUnit} latestDataMode={isLatest} />
    ),
    cursor: { stroke: gridStroke, strokeWidth: 1, strokeDasharray: "3 3" },
  });

  const hasData = useSingleSeriesRows
    ? (singleSeriesRows?.length || 0) > 0
    : (dataWithTrends?.length || 0) > 0 && dataLines.length > 0;

  const latestXProps = latestBarXTickProps({ n: nPoints, compact, latestBarMode });
  const categoryLabelMax = categoryAxisLabelMax({ compact, n: nPoints, latestBarMode });
  const formatCategoryTick = (v) =>
    latestBarMode ? ellipsizeLabel(v, categoryLabelMax) : formatTimeAxisTick(v);
  const timeSeriesXTickProps = lineTimeSeriesTilt
    ? {
        angle: nPoints > 24 ? -36 : -28,
        textAnchor: "end",
        dy: 8,
        height: compact ? 36 : 44,
      }
    : {
        height: compact ? 24 : 32,
      };
  const categoryAxisTick = useMemo(
    () =>
      latestBarMode
        ? makeCategoryAxisTick({
            maxLen: categoryLabelMax,
            angle: latestXProps.angle ?? 0,
            textAnchor: latestXProps.textAnchor ?? "middle",
            dy: latestXProps.dy ?? 0,
            fill: axisColor,
            fontSize: sizeSpec.tickFontSize,
            fontFamily: "JetBrains Mono, ui-monospace, monospace",
          })
        : null,
    [latestBarMode, categoryLabelMax, latestXProps, axisColor, sizeSpec.tickFontSize]
  );

  if (!hasData) return null;

  const plotHeight = height ?? sizeSpec.containerHeight;
  const primary = chartTheme.accent;
  const primarySoft = chartTheme.accentSoft || "hsl(208 75% 48%)";

  const xAxisCommon = {
    dataKey: "x",
    tickLine: false,
    axisLine: { stroke: axisStroke },
    padding: { left: compact ? 4 : 8, right: compact ? 8 : 14 },
    tickMargin: compact ? 6 : 8,
    ...(latestBarMode
      ? { tick: categoryAxisTick, ...latestXProps }
      : { tick: tickStyle, tickFormatter: formatCategoryTick, ...timeSeriesXTickProps }),
  };

  const xAxisTimeSeries = (
    <XAxis
      {...xAxisCommon}
      interval={latestBarMode ? 0 : "preserveStartEnd"}
      minTickGap={latestBarMode ? 0 : compact ? 20 : 28}
    />
  );

  const hidePlotYAxis = Boolean(stickyYAxisSpec);
  const yAxisWidth = hidePlotYAxis ? 0 : sizeSpec.axisGutterWidth;

  const yAxisDomain = useBarChart
    ? barYAxisSpec?.domain
    : buildRechartsValueDomain(
        lineYAxisSpec,
        useSingleSeriesRows
          ? (singleSeriesRows || []).map((r) => r.y)
          : (dataWithTrends || []).flatMap((row) =>
              dataLines.map((line) => coerceChartNumeric(row[line.key])).filter((v) => v != null)
            )
      );

  const yAxisTicks = useBarChart ? barYAxisSpec?.axis?.ticks : lineYAxisSpec?.ticks;

  const yAxis = (
    <YAxis
      yAxisId="left"
      tick={tickStyle}
      tickLine={false}
      axisLine={{ stroke: axisStroke }}
      width={yAxisWidth}
      domain={yAxisDomain}
      ticks={yAxisTicks}
      tickFormatter={(v) => formatAxisTick(v, { digits: 1 })}
      {...(useBarChart ? BAR_VALUE_AXIS : {})}
      hide={hidePlotYAxis}
    />
  );

  const secondaryYAxis = useDualAxis ? (
    <YAxis
      yAxisId="right"
      orientation="right"
      tick={tickStyle}
      tickLine={false}
      axisLine={{ stroke: axisStroke }}
      width={sizeSpec.axisGutterWidth}
      tickFormatter={(v) => formatAxisTick(v, { digits: 1 })}
    />
  ) : null;

  const lineYAxisId = (line) => (line.axis === "right" || line.yAxisId === "right" ? "right" : "left");

  const trendLineNodes = trendLines.map((line) => (
    <Line
      key={line.key}
      type="linear"
      dataKey={line.key}
      name={line.name}
      yAxisId="left"
      stroke={line.color}
      strokeWidth={1.5}
      strokeDasharray={line.strokeDasharray}
      dot={false}
      activeDot={false}
      connectNulls
      legendType="none"
    />
  ));

  const legendNode = showLegend ? (
    <Legend
      verticalAlign="bottom"
      align="center"
      iconType="circle"
      iconSize={8}
      height={legendHeight}
      wrapperStyle={{
        fontSize: compact ? 10 : 11,
        fontFamily: "Inter, system-ui, sans-serif",
        paddingTop: 8,
        lineHeight: 1.4,
        width: "100%",
        left: 0,
      }}
      formatter={(name) => (
        <span className="explore-chart-legend-label text-slate-600">{name}</span>
      )}
    />
  ) : null;

  const barMultiColor = useBarChart && (isLatest || nPoints > 1);
  const barRowsRaw = useSingleSeriesRows ? dataWithTrends : dataWithTrends;
  const barAllPositive =
    barRowsRaw.length > 0 &&
    barRowsRaw.every((r) => {
      const v = coerceChartNumeric(r.y ?? r[dataLines[0]?.key]);
      return v != null && v >= 0;
    });
  const barPlotRows = barAllPositive ? chartRowsWithZeroBaselineBars(barRowsRaw) : barRowsRaw;
  const barDataKey = useSingleSeriesRows ? "y" : dataLines[0]?.key;

  const chartInner = useBarChart ? (
    <BarChart data={barPlotRows} margin={margin}>
      <CartesianGrid vertical={false} stroke={gridStroke} strokeDasharray="2 4" />
      {xAxisTimeSeries}
      {yAxis}
      <Tooltip {...tooltipProps} />
      <Bar
        dataKey={barDataKey}
        name={dataLines[0]?.name || normalizedContract.series?.[0]?.label || "Hodnota"}
        yAxisId="left"
        fill={primarySoft}
        radius={[6, 6, 0, 0]}
        maxBarSize={compact ? 36 : 48}
        isAnimationActive={false}
      >
        {latestBarMode ? (
          <LabelList
            dataKey={barDataKey}
            position="top"
            offset={4}
            formatter={(v) => fmtCompact(chartBarPointValue(v) ?? v)}
            style={{
              fill: axisColor,
              fontFamily: "JetBrains Mono, ui-monospace, monospace",
              fontSize: sizeSpec.tickFontSize,
              fontWeight: 600,
            }}
          />
        ) : null}
        {barMultiColor
          ? barPlotRows.map((_, i) => (
              <Cell key={i} fill={BAR_CATEGORY_COLORS[i % BAR_CATEGORY_COLORS.length]} />
            ))
          : null}
      </Bar>
    </BarChart>
  ) : useAreaChart ? (
    <AreaChart data={dataWithTrends} margin={margin}>
      <defs>
        <linearGradient id="chart-system-area-gradient" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={primarySoft} stopOpacity={0.45} />
          <stop offset="100%" stopColor={primarySoft} stopOpacity={0.02} />
        </linearGradient>
      </defs>
      <CartesianGrid vertical={false} stroke={gridStroke} strokeDasharray="2 4" />
      {xAxisTimeSeries}
      {yAxis}
      <Tooltip {...tooltipProps} />
      <Area
        type="linear"
        dataKey={dataLines[0]?.key}
        name={dataLines[0]?.name}
        yAxisId="left"
        stroke={primary}
        strokeWidth={2.5}
        fill="url(#chart-system-area-gradient)"
        dot={false}
        activeDot={{ r: 4, fill: primary, stroke: "#fff", strokeWidth: 2 }}
        connectNulls
        isAnimationActive={false}
      />
    </AreaChart>
  ) : (
    <LineChart
      data={dataWithTrends}
      margin={{
        ...margin,
        bottom: showLegend ? Math.max(margin.bottom, legendHeight + 4) : margin.bottom,
      }}
    >
      <CartesianGrid vertical={false} stroke={gridStroke} strokeDasharray="2 4" />
      {xAxisTimeSeries}
      {yAxis}
      {secondaryYAxis}
      {legendNode}
      <Tooltip {...tooltipProps} />
      {useSingleSeriesRows ? (
        <Line
          type="linear"
          dataKey="y"
          name={dataLines[0]?.name || "Hodnota"}
          yAxisId="left"
          stroke={primary}
          strokeWidth={2.5}
          dot={false}
          activeDot={{ r: 4, stroke: "#fff", strokeWidth: 2 }}
          connectNulls
          isAnimationActive={false}
        />
      ) : (
        dataLines.map((line) => (
          <Line
            key={line.key || line.name}
            type="linear"
            dataKey={line.key}
            name={line.name}
            yAxisId={lineYAxisId(line)}
            stroke={line.color}
            strokeWidth={2.5}
            dot={false}
            activeDot={{ r: 4, stroke: "#fff", strokeWidth: 2 }}
            connectNulls
            isAnimationActive={false}
          />
        ))
      )}
      {trendLineNodes}
    </LineChart>
  );

  return (
    <div
      className={CHART_PLOT_CLASS}
      style={{ height: plotHeight === "100%" ? "100%" : plotHeight, minHeight: sizeSpec.plotMinHeight }}
    >
      <div
        className={`flex min-h-0 ${chartScrollable ? "overflow-x-auto overflow-y-hidden overscroll-x-contain [scrollbar-gutter:stable]" : "overflow-hidden"}`}
        style={{ height: "100%" }}
      >
        {stickyYAxisSpec ? (
          <FrozenYAxisGutter spec={stickyYAxisSpec} chartTheme={chartTheme} />
        ) : null}
        <div
          className="min-w-0 flex-1"
          style={
            scrollMinWidth ? { minWidth: scrollMinWidth, width: scrollMinWidth } : undefined
          }
        >
          <SafeRechartsContainer width="100%" height="100%">
            {chartInner}
          </SafeRechartsContainer>
        </div>
      </div>
      {leftUnit || rightUnit ? (
        <div className="text-[10px] text-slate-500 text-right mt-1 pr-1">
          {leftUnit ? `Levá osa: ${leftUnit}` : null}
          {leftUnit && rightUnit ? " · " : null}
          {rightUnit ? `Pravá osa: ${rightUnit}` : null}
        </div>
      ) : null}
    </div>
  );
}

export { CHART_BODY_SLOT_CLASS };
