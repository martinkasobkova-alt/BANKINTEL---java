/**
 * Debug měření sloupcového grafu v prohlížeči.
 * Zapnutí: ?chartDebug=1 nebo window.__BANKO_CHART_DEBUG__ = true
 */
export function isChartDebugEnabled() {
  if (typeof window === "undefined") return false;
  if (window.__BANKO_CHART_DEBUG__) return true;
  try {
    return new URLSearchParams(window.location.search).has("chartDebug");
  } catch {
    return false;
  }
}

function parsePathTopY(d) {
  if (!d) return null;
  const nums = d.match(/-?\d*\.?\d+/g);
  if (!nums || nums.length < 4) return null;
  const ys = [];
  for (let i = 1; i < nums.length; i += 2) {
    const y = Number(nums[i]);
    if (Number.isFinite(y)) ys.push(y);
  }
  return ys.length ? Math.min(...ys) : null;
}

function parsePathBottomY(d) {
  if (!d) return null;
  const nums = d.match(/-?\d*\.?\d+/g);
  if (!nums) return null;
  const ys = [];
  for (let i = 1; i < nums.length; i += 2) {
    const y = Number(nums[i]);
    if (Number.isFinite(y)) ys.push(y);
  }
  return ys.length ? Math.max(...ys) : null;
}

function barTopY(el) {
  if (!el) return null;
  if (el.tagName.toLowerCase() === "rect") return Number(el.getAttribute("y"));
  return parsePathTopY(el.getAttribute("d"));
}

function barBottomY(el) {
  if (!el) return null;
  if (el.tagName.toLowerCase() === "rect") {
    const y = Number(el.getAttribute("y"));
    const h = Number(el.getAttribute("height"));
    if (Number.isFinite(y) && Number.isFinite(h)) return y + h;
  }
  return parsePathBottomY(el.getAttribute("d"));
}

/** Jen skutečné sloupce — bez Bar background rectangles. */
function collectDataBarElements(rootEl) {
  if (!rootEl) return [];
  const layer = rootEl.querySelector(".recharts-bar-rectangles");
  if (layer) {
    return Array.from(layer.querySelectorAll("path, rect")).filter((el) => {
      const cls = el.getAttribute("class") || "";
      return !cls.includes("background");
    });
  }
  return Array.from(rootEl.querySelectorAll("path, rect")).filter((el) => {
    const cls = el.getAttribute("class") || "";
    if (cls.includes("background")) return false;
    const parentCls = el.parentElement?.getAttribute("class") || "";
    if (parentCls.includes("background")) return false;
    const fill = (el.getAttribute("fill") || "").toLowerCase();
    if (fill === "none" || fill === "transparent") return false;
    const stroke = el.getAttribute("stroke");
    if (stroke && stroke !== "none") return false;
    const h = Number(el.getAttribute("height"));
    const w = Number(el.getAttribute("width"));
    return Number.isFinite(h) && h > 2 && Number.isFinite(w) && w > 2 && w < 240;
  });
}

function findBarByLabel(rootEl, bars, label) {
  const categoryEls = Array.from(rootEl.querySelectorAll(".recharts-xAxis .recharts-cartesian-axis-tick-value"));
  const idx = categoryEls.findIndex((el) =>
    String(el.textContent || "").trim().toLowerCase().includes(String(label).toLowerCase())
  );
  if (idx < 0 || !bars.length) return null;
  const tickX = categoryEls[idx]?.getBoundingClientRect?.()?.left;
  if (tickX == null) return bars[idx] || null;
  return bars.reduce((best, bar) => {
    const bx = bar.getBoundingClientRect().left;
    const diff = Math.abs(bx - tickX);
    if (!best || diff < best.diff) return { el: bar, diff };
    return best;
  }, null)?.el;
}

function gridLineYForTick(gridLines, tickValue, domainMin, domainMax, plotTop, plotHeight) {
  if (!gridLines.length) return null;
  if (Number.isFinite(domainMin) && Number.isFinite(domainMax) && plotHeight > 0) {
    const ratio = (Number(tickValue) - domainMin) / (domainMax - domainMin);
    return plotTop + (1 - ratio) * plotHeight;
  }
  const sorted = [...gridLines].sort(
    (a, b) => Number(a.getAttribute("y1")) - Number(b.getAttribute("y1"))
  );
  if (tickValue === 0 || tickValue === "0") return Number(sorted[sorted.length - 1]?.getAttribute("y1"));
  return null;
}

/**
 * @param {HTMLElement|null} rootEl data-chart-export-root
 * @param {object} meta
 */
export function measureAndLogBarChartDebug(rootEl, meta = {}) {
  if (!isChartDebugEnabled() || !rootEl) return null;

  const gridLines = Array.from(rootEl.querySelectorAll(".recharts-cartesian-grid-horizontal line"));
  const backgroundRects = Array.from(
    rootEl.querySelectorAll(".recharts-bar-background, .recharts-bar-background-rectangle, [class*='bar-background']")
  );

  const domainMin = Number(meta.domain?.[0]);
  const domainMax = Number(meta.domain?.[1]);

  let plotTop = null;
  let plotBottom = null;
  let plotHeight = null;
  if (gridLines.length >= 2) {
    const ys = gridLines.map((line) => Number(line.getAttribute("y1"))).filter(Number.isFinite);
    if (ys.length) {
      plotTop = Math.min(...ys);
      plotBottom = Math.max(...ys);
      plotHeight = plotBottom - plotTop;
    }
  }

  const bars = collectDataBarElements(rootEl);
  const germanyBarEl = findBarByLabel(rootEl, bars, meta.germanyLabel || "Germany");
  const franceBarEl = findBarByLabel(rootEl, bars, meta.franceLabel || "France");

  const zeroGridlineY = gridLineYForTick(gridLines, 0, domainMin, domainMax, plotTop, plotHeight);
  const tick685GridlineY = gridLineYForTick(gridLines, 6.85, domainMin, domainMax, plotTop, plotHeight);

  const germanyTopY = barTopY(germanyBarEl);
  const germanyBottomY = barBottomY(germanyBarEl);
  const franceTopY = barTopY(franceBarEl);
  const germanyHeightPx =
    germanyTopY != null && germanyBottomY != null ? germanyBottomY - germanyTopY : null;

  const expectedRatio =
    Number.isFinite(meta.germanyValue) && Number.isFinite(domainMax) && domainMax > 0
      ? meta.germanyValue / domainMax
      : null;
  const actualRatio =
    germanyHeightPx != null && plotHeight != null && plotHeight > 0 ? germanyHeightPx / plotHeight : null;

  const barBottomMatchesZero =
    germanyBottomY != null && zeroGridlineY != null
      ? Math.abs(germanyBottomY - zeroGridlineY) <= 1.5
      : null;

  const report = {
    chartTitle: meta.chartTitle,
    component: meta.component || "AradView.jsx / renderChart",
    customShapeUsed: Boolean(meta.customShapeUsed),
    backgroundRectCount: backgroundRects.length,
    yAxisDomain: meta.domain,
    stickyYAxisDomain: meta.stickyDomain,
    domainsMatch:
      JSON.stringify(meta.domain || []) === JSON.stringify(meta.stickyDomain || meta.domain || []),
    plotAreaTop: plotTop,
    plotAreaBottom: plotBottom,
    plotAreaHeight: plotHeight,
    zeroGridlineY,
    tick685GridlineY,
    germanyValue: meta.germanyValue,
    germanyBarTopY: germanyTopY,
    germanyBarBottomY: germanyBottomY,
    franceBarTopY: franceTopY,
    barBottomMatchesZeroTick: barBottomMatchesZero,
    germanyBelowTick685:
      germanyTopY != null && tick685GridlineY != null ? germanyTopY > tick685GridlineY + 0.5 : null,
    expectedHeightRatio: expectedRatio,
    actualHeightRatio: actualRatio,
    germanyBarSvg: germanyBarEl
      ? {
          tag: germanyBarEl.tagName,
          x: germanyBarEl.getAttribute("x"),
          y: germanyBarEl.getAttribute("y"),
          width: germanyBarEl.getAttribute("width"),
          height: germanyBarEl.getAttribute("height"),
          d: germanyBarEl.getAttribute("d"),
          transform: germanyBarEl.getAttribute("transform"),
          fill: germanyBarEl.getAttribute("fill"),
          clipPath: germanyBarEl.getAttribute("clip-path"),
        }
      : null,
    barCount: bars.length,
  };

  console.group(`[chartDebug] ${meta.chartTitle || "Bar chart"}`);
  console.table(report);
  if (backgroundRects.length > 0) {
    console.warn("[chartDebug] Bar background rects still present — should be 0", backgroundRects.length);
  }
  console.groupEnd();

  return report;
}
