/**
 * Plot margin / label helpers — převzato z AradView renderChart.
 */

import React from "react";

export function ellipsizeLabel(value, max = 16) {
  const s = String(value ?? "").trim();
  if (!s || s.length <= max) return s;
  return `${s.slice(0, Math.max(1, max - 1)).trimEnd()}…`;
}

/**
 * Rozdělí text na max. 2 řádky — láme na mezerách, druhý řádek zkrátí pokud je třeba.
 * Vrací [line1, line2 | null].
 */
function splitToTwoLines(text, maxPerLine) {
  if (!text || text.length <= maxPerLine) return [text, null];
  const words = text.split(" ");
  let line1 = "";
  let i = 0;
  while (i < words.length && (line1 + (line1 ? " " : "") + words[i]).length <= maxPerLine) {
    line1 += (line1 ? " " : "") + words[i];
    i++;
  }
  if (!line1) {
    line1 = ellipsizeLabel(text, maxPerLine);
    return [line1, null];
  }
  const rest = words.slice(i).join(" ");
  const line2 = rest ? ellipsizeLabel(rest, maxPerLine) : null;
  return [line1, line2];
}

/** Max. znaků popisku osy X u kategoriálního sloupce (podle počtu sloupců / šířky slotu). */
export function categoryAxisLabelMax({ compact = false, n = 0, latestBarMode = false } = {}) {
  if (!latestBarMode) return compact ? 10 : 14;
  if (n > 12) return compact ? 7 : 9;
  if (n > 8) return compact ? 8 : 10;
  return compact ? 10 : 12;
}

/**
 * Recharts custom tick — max. 2 řádky textu + plný popisek v nativním tooltipu (<title>).
 * Při sklonu (angle) zůstává jednořádkový (zkrácený), u rovného textu se zalomí na 2 řádky.
 */
export function makeCategoryAxisTick({
  formatter,
  maxLen = 12,
  angle = 0,
  textAnchor = "middle",
  dy = 0,
  fill = "#5878A0",
  fontSize = 10,
  fontFamily = "JetBrains Mono",
} = {}) {
  function CategoryAxisTick({ x, y, payload }) {
    const raw = String(payload?.value ?? "").trim();
    const formatted = formatter ? formatter(raw) : raw;
    const textProps = { fill, fontSize, fontFamily, textAnchor };
    const lineHeight = fontSize + 2;

    if (angle) {
      const display = ellipsizeLabel(formatted, maxLen);
      const truncated = display !== raw;
      return (
        <g transform={`translate(${x},${y})`}>
          <text {...textProps} dy={dy} transform={`rotate(${angle})`}>
            {truncated ? <title>{raw}</title> : null}
            {display}
          </text>
        </g>
      );
    }

    const [line1, line2] = splitToTwoLines(formatted, maxLen);
    const hasMore = line2 ? (line2.endsWith("…") || formatted !== `${line1} ${line2}`.trim()) : formatted !== line1;
    const totalLines = line2 ? 2 : 1;
    const startDy = dy - ((totalLines - 1) * lineHeight) / 2;

    return (
      <text x={x} y={y} {...textProps}>
        {hasMore ? <title>{raw}</title> : null}
        <tspan x={x} dy={startDy}>
          {line1}
        </tspan>
        {line2 ? (
          <tspan x={x} dy={lineHeight}>
            {line2}
          </tspan>
        ) : null}
      </text>
    );
  }

  CategoryAxisTick.displayName = "CategoryAxisTick";
  return CategoryAxisTick;
}

export function chartAreaTopMargin({ showBarLabels = false, compact = false } = {}) {
  if (showBarLabels) return compact ? 20 : 24;
  return compact ? 16 : 22;
}

/** Sklon a výška osy X u kategoriálního sloupcového grafu (latest / value compare). */
/**
 * @param {object} opts
 * @param {boolean} [opts.tight] Plocha grafu je tak nízká, že by na sloupce nezbylo místo.
 *   Nakloněné názvy kategorií si berou pevnou výšku bez ohledu na to, kolik jí graf má:
 *   u 27 zemí ve widgetu na dashboardu spotřebovaly 70 ze 77 pixelů a nevykreslil se
 *   ani jeden sloupec. V takové situaci je lepší popisky zkrátit než graf neukázat.
 */
export function latestBarCategoryAxisLayout({
  n = 0,
  compact = false,
  latestBarMode = false,
  tight = false,
} = {}) {
  // Nakloněné popisky si berou 48 px výšky. V nízké ploše (widget na dashboardu) je to
  // celý graf — sloupce se pak nevykreslí vůbec. Vodorovný posuvník už každému sloupci
  // dává 68 px šířky, takže na plocho se popisek vejde taky a na sloupce zbude místo.
  const useTilt = latestBarMode && n > 4 && !tight;
  const angle = useTilt ? (n > 10 ? -40 : n > 6 ? -32 : -24) : 0;
  const tiltHeight = compact ? 40 : 48;
  const tiltMargin = compact ? 38 : 48;
  return {
    useTilt,
    angle,
    textAnchor: useTilt ? "end" : "middle",
    dy: useTilt ? 6 : 0,
    axisHeight: latestBarMode
      ? useTilt
        ? tiltHeight
        : tight
          ? 16
          : compact
            ? 24
            : 28
      : compact
        ? 20
        : 18,
    bottomMargin: latestBarMode
      ? useTilt
        ? tiltMargin
        : tight
          ? 14
          : compact
            ? 22
            : 28
      : null,
    ellipsizeMax: tight
      ? Math.min(8, categoryAxisLabelMax({ compact, n, latestBarMode }))
      : categoryAxisLabelMax({ compact, n, latestBarMode }),
    scrollPerBar: compact ? 56 : 68,
    scrollThreshold: { mobile: 5, desktop: 6 },
  };
}

/** Kdy zapnout horizontální scroll u svislého sloupcového grafu. */
export function barChartHorizontalScrollEnabled(
  pointCount,
  { latestBarMode = false, mobile = false } = {}
) {
  const n = Number(pointCount) || 0;
  if (n < 1) return false;
  if (latestBarMode) return n >= (mobile ? 5 : 6);
  return n > (mobile ? 8 : 10);
}

/** Sdílené okraje plot area — musí sedět mezi Recharts margin a FrozenYAxisGutter. */
export function computeChartPlotMargins({
  latestBarMode = false,
  compact = false,
  n = 0,
  tight = false,
} = {}) {
  const useTilt = latestBarMode ? n > 4 : n > 8;
  const latestLayout = latestBarMode ? latestBarCategoryAxisLayout({ n, compact, latestBarMode, tight }) : null;
  return {
    top: chartAreaTopMargin({ compact }),
    bottom: latestLayout?.bottomMargin ?? (useTilt ? (compact ? 26 : 28) : compact ? 16 : 24),
    useTilt,
  };
}

export function latestBarXTickProps({ n, compact, latestBarMode, tight = false }) {
  const layout = latestBarCategoryAxisLayout({ n, compact, latestBarMode, tight });
  return {
    angle: layout.angle,
    textAnchor: layout.textAnchor,
    dy: layout.dy,
    height: layout.axisHeight,
    interval: 0,
    minTickGap: latestBarMode ? 0 : compact ? 28 : 16,
  };
}

/** Min width pro horizontální scroll (bar / line time series). */
export function chartScrollMinWidth(
  pointCount,
  { compact = false, isBar = false, latestBarMode = false, mobile = false } = {}
) {
  const layout = latestBarMode ? latestBarCategoryAxisLayout({ n: pointCount, compact, latestBarMode: true }) : null;
  if (isBar && !barChartHorizontalScrollEnabled(pointCount, { latestBarMode, mobile })) return null;
  if (!isBar && pointCount <= 10) return null;
  const perPoint = isBar
    ? layout?.scrollPerBar ?? (compact ? 42 : 52)
    : compact
      ? 36
      : 44;
  return Math.max(mobile ? 560 : 720, pointCount * perPoint);
}
