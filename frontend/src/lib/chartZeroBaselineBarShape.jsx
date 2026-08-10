import React from "react";
import { fmtCompact, parseNumber } from "./format";

/** Výpočet výšky sloupce od nulové baseline — pro testy a custom shape. */
export function computeZeroBaselineBarGeometry(domainMin, domainMax, value, plotHeight) {
  const min = Number(domainMin);
  const max = Number(domainMax);
  const span = max - min;
  const plotH = Number(plotHeight);
  if (!Number.isFinite(span) || span <= 0 || !Number.isFinite(plotH) || plotH <= 0) {
    return null;
  }
  let v = typeof value === "number" ? value : barNumericValue(value);
  if (v == null || !Number.isFinite(v)) return null;
  const clamped = Math.max(min, Math.min(max, v));
  const ratio = (clamped - min) / span;
  const barHeight = ratio * plotH;
  return {
    barHeight,
    barY: plotH - barHeight,
    ratio,
    baselineY: plotH,
  };
}

function barNumericValue(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (Array.isArray(value)) return barNumericValue(value[value.length - 1]);
  let n = parseNumber(value);
  if (n !== null && Number.isFinite(n)) return n;
  const s = String(value ?? "").trim();
  if (!s) return null;
  n = parseNumber(s.split(/\s+/)[0]);
  return n !== null && Number.isFinite(n) ? n : null;
}

/**
 * Vlastní tvar sloupce: výška z [domainMin, domainMax] a spodní okraj plot area (background),
 * nezávisle na Recharts getBaseValueOfBar (který u kladných dat občas bere dataMin místo 0).
 */
export function createZeroBaselineBarShape(domainMin, domainMax, options = {}) {
  const min = Number(domainMin);
  const max = Number(domainMax);
  const span = max - min;
  const radius = options.radius ?? 6;
  const showLabel = Boolean(options.showLabel);
  const labelStyle = options.labelStyle || {};
  const debugMeta = options.debugMeta || null;

  return function ZeroBaselineBarShape(props) {
    const { x, width, value, fill, background, payload } = props;
    if (typeof window !== "undefined" && window.__BANKO_CHART_DEBUG__) {
      console.log("ZERO_BASELINE_SHAPE_RENDERED", debugMeta?.chartTitle, payload?.x, value, {
        domainMin: min,
        domainMax: max,
        hasBackground: background != null,
      });
    }
    if (
      !Number.isFinite(span) ||
      span <= 0 ||
      x == null ||
      width == null ||
      !Number.isFinite(Number(x)) ||
      !Number.isFinite(Number(width))
    ) {
      return null;
    }

    let v = barNumericValue(value);
    if (v == null) return null;

    let plotBottom;
    let plotHeight;
    if (background != null) {
      plotBottom = background.y + background.height;
      plotHeight = background.height;
    } else if (options.plotTop != null && options.plotHeight != null) {
      const plotTop = Number(options.plotTop);
      plotHeight = Number(options.plotHeight);
      plotBottom = plotTop + plotHeight;
    } else {
      return null;
    }

    const geom = computeZeroBaselineBarGeometry(min, max, v, plotHeight);
    if (!geom || geom.barHeight <= 0.5) return null;

    const { barHeight } = geom;
    const barY = plotBottom - barHeight;
    const barX = Number(x);
    const w = Math.max(0, Number(width));
    const r = Math.min(radius, w / 2, barHeight);

    let barNode;
    if (r > 0.5) {
      const path = [
        `M ${barX} ${plotBottom}`,
        `L ${barX} ${barY + r}`,
        `Q ${barX} ${barY} ${barX + r} ${barY}`,
        `L ${barX + w - r} ${barY}`,
        `Q ${barX + w} ${barY} ${barX + w} ${barY + r}`,
        `L ${barX + w} ${plotBottom}`,
        "Z",
      ].join(" ");
      barNode = <path d={path} fill={fill} />;
    } else {
      barNode = <rect x={barX} y={barY} width={w} height={barHeight} fill={fill} />;
    }

    return (
      <g>
        {barNode}
        {showLabel ? (
          <text
            x={barX + w / 2}
            y={barY - 4}
            textAnchor="middle"
            dominantBaseline="auto"
            {...labelStyle}
          >
            {fmtCompact(v)}
          </text>
        ) : null}
      </g>
    );
  };
}

export function createZeroBaselineHorizontalBarShape(domainMin, domainMax, options = {}) {
  const min = Number(domainMin);
  const max = Number(domainMax);
  const span = max - min;
  const radius = options.radius ?? 6;
  const showLabel = Boolean(options.showLabel);
  const labelStyle = options.labelStyle || {};

  return function ZeroBaselineHorizontalBarShape(props) {
    const { y, height, value, fill, background } = props;
    if (
      background == null ||
      !Number.isFinite(span) ||
      span <= 0 ||
      y == null ||
      height == null
    ) {
      return null;
    }

    let v = barNumericValue(value);
    if (v == null) return null;

    const plotLeft = background.x;
    const plotWidth = background.width;
    const clamped = Math.max(min, Math.min(max, v));
    const ratio = (clamped - min) / span;
    const barWidth = ratio * plotWidth;
    if (barWidth <= 0.5) return null;

    const barX = plotLeft;
    const barY = Number(y);
    const h = Math.max(0, Number(height));
    const r = Math.min(radius, h / 2, barWidth);

    let barNode;
    if (r > 0.5) {
      const right = barX + barWidth;
      const path = [
        `M ${plotLeft} ${barY}`,
        `L ${right - r} ${barY}`,
        `Q ${right} ${barY} ${right} ${barY + r}`,
        `L ${right} ${barY + h - r}`,
        `Q ${right} ${barY + h} ${right - r} ${barY + h}`,
        `L ${plotLeft} ${barY + h}`,
        "Z",
      ].join(" ");
      barNode = <path d={path} fill={fill} />;
    } else {
      barNode = <rect x={barX} y={barY} width={barWidth} height={h} fill={fill} />;
    }

    return (
      <g>
        {barNode}
        {showLabel ? (
          <text
            x={barX + barWidth + 4}
            y={barY + h / 2}
            textAnchor="start"
            dominantBaseline="middle"
            {...labelStyle}
          >
            {fmtCompact(v)}
          </text>
        ) : null}
      </g>
    );
  };
}
