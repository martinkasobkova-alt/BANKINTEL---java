import React from "react";
import ChartRenderer from "@/charts/ChartRenderer";
import { CHART_SIZE_VARIANTS } from "@/charts/chartTypes";

/**
 * Explore / Manager chart — thin wrapper around unified ChartRenderer.
 * Preserves legacy props { merged, series, height, unit, dualAxis, ... }.
 */
export default function ExploreReportChart({
  merged,
  series,
  height = 200,
  unit = "",
  secondaryUnit = "",
  dualAxis = false,
  showTrendLine = true,
  compact = false,
  contract,
  size,
}) {
  const resolvedSize = size ?? (compact ? CHART_SIZE_VARIANTS.COMPACT : CHART_SIZE_VARIANTS.STANDARD);

  return (
    <ChartRenderer
      contract={contract}
      merged={merged}
      series={series}
      height={height}
      unit={unit}
      secondaryUnit={secondaryUnit}
      dualAxis={dualAxis}
      showTrendLine={showTrendLine}
      compact={compact}
      size={resolvedSize}
    />
  );
}
