/** @jest-environment jsdom */
import fs from "fs";
import path from "path";
import React from "react";
import { createRoot } from "react-dom/client";
import { act } from "react-dom/test-utils";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from "recharts";
import { mergeRechartsTooltipProps } from "@/lib/rechartsTooltipShared";
import { ChartRenderErrorBoundary } from "@/lib/SafeRechartsContainer";
import { buildSingleSeriesFromRows } from "@/lib/chartTimeSeriesPivot";
import { parseChartPeriod } from "@/lib/chartPeriodParse";
import { buildAradDataFromCatalogPreview } from "@/lib/mapCatalogPreviewToArad";

function buildEcbPreviewRows() {
  const rows = [];
  for (let y = 2008; y <= 2023; y += 1) {
    for (let q = 1; q <= 4; q += 1) {
      if (y === 2023 && q > 3) break;
      if (y === 2009 && q < 4) continue;
      rows.push({ period: `${y}-Q${q}`, value: 8 + q + (y % 5) });
    }
  }
  return rows;
}

function aggregateQuarterlyLikeAradView(rows) {
  const buckets = new Map();
  for (const r of rows) {
    const date = parseChartPeriod(r.period);
    if (!(date instanceof Date) || Number.isNaN(date.getTime())) continue;
    const y = date.getFullYear();
    const m = date.getMonth();
    const q = Math.floor(m / 3) + 1;
    const roman = ["I", "II", "III", "IV"][q - 1];
    const key = `${y}-Q${q}`;
    buckets.set(key, { x: `${roman}.Q ${y}`, y: r.value });
  }
  return [...buckets.values()];
}

describe("ECB quarterly chart render", () => {
  test("pipeline produces chart rows", () => {
    const mapped = buildSingleSeriesFromRows(buildEcbPreviewRows());
    expect(mapped.length).toBeGreaterThan(10);
    const chartRows = aggregateQuarterlyLikeAradView(mapped);
    expect(chartRows.length).toBeGreaterThan(10);
    expect(chartRows[chartRows.length - 1].x).toMatch(/III\.Q 2023/);
  });

  test("Recharts LineChart renders roman quarter labels", () => {
    const chartRows = aggregateQuarterlyLikeAradView(buildSingleSeriesFromRows(buildEcbPreviewRows()));
    const container = document.createElement("div");
    container.style.width = "480px";
    container.style.height = "320px";
    document.body.appendChild(container);

    let boundaryErr = null;
    const origDidCatch = ChartRenderErrorBoundary.prototype.componentDidCatch;
    ChartRenderErrorBoundary.prototype.componentDidCatch = function didCatch(err) {
      boundaryErr = err;
      if (origDidCatch) origDidCatch.call(this, err);
    };

    const root = createRoot(container);
    act(() => {
      root.render(
        <ResponsiveContainer width="100%" height="100%">
          <ChartRenderErrorBoundary>
            <LineChart data={chartRows} margin={{ top: 16, right: 24, left: 8, bottom: 24 }}>
              <CartesianGrid vertical={false} />
              <XAxis type="category" dataKey="x" interval={0} />
              <YAxis type="number" />
              <Tooltip {...mergeRechartsTooltipProps()} />
              <Line type="linear" dataKey="y" stroke="#2563eb" dot={false} />
            </LineChart>
          </ChartRenderErrorBoundary>
        </ResponsiveContainer>
      );
    });

    expect(boundaryErr).toBeNull();
    expect(container.textContent).not.toMatch(/Graf se nepodařilo vykreslit/);
    root.unmount();
    document.body.removeChild(container);
    ChartRenderErrorBoundary.prototype.componentDidCatch = origDidCatch;
  });

  test("ARAD preview (292 monthly YYYYMMDD rows) renders with tilted X axis", () => {
    const previewPath = path.resolve(__dirname, "../../../../arad_preview.json");
    if (!fs.existsSync(previewPath)) return;
    const preview = JSON.parse(fs.readFileSync(previewPath, "utf8"));
    const arad = buildAradDataFromCatalogPreview(preview, "ARAD test");
    const chartRows = arad.rows.map((r) => ({ x: r.period, y: r.value }));
    expect(chartRows.length).toBeGreaterThan(100);

    const n = chartRows.length;
    const mxc = n > 80 ? 7 : n > 50 ? 8 : n > 24 ? 9 : 10;
    const xTickValues = [];
    for (let j = 0; j < mxc; j += 1) {
      const idx = Math.round((j * (n - 1)) / Math.max(1, mxc - 1));
      xTickValues.push(chartRows[idx].x);
    }

    const container = document.createElement("div");
    container.style.width = "640px";
    container.style.height = "380px";
    document.body.appendChild(container);

    let boundaryErr = null;
    const origDidCatch = ChartRenderErrorBoundary.prototype.componentDidCatch;
    ChartRenderErrorBoundary.prototype.componentDidCatch = function didCatch(err) {
      boundaryErr = err;
      if (origDidCatch) origDidCatch.call(this, err);
    };

    const root = createRoot(container);
    act(() => {
      root.render(
        <ResponsiveContainer width="100%" height="100%">
          <ChartRenderErrorBoundary>
            <LineChart data={chartRows} margin={{ top: 22, right: 30, left: 10, bottom: 28 }}>
              <CartesianGrid vertical={false} />
              <XAxis
                type="category"
                dataKey="x"
                ticks={xTickValues}
                interval={0}
                angle={-30}
                textAnchor="end"
                height={30}
              />
              <YAxis type="number" />
              <Tooltip {...mergeRechartsTooltipProps()} />
              <Line type="linear" dataKey="y" stroke="#2563eb" dot={false} />
            </LineChart>
          </ChartRenderErrorBoundary>
        </ResponsiveContainer>
      );
    });

    expect(boundaryErr).toBeNull();
    expect(container.textContent).not.toMatch(/Graf se nepodařilo vykreslit/);
    root.unmount();
    document.body.removeChild(container);
    ChartRenderErrorBoundary.prototype.componentDidCatch = origDidCatch;
  });

  // Deterministic (non-visual) guard for the "chart renders only a truncated stub of the line
  // instead of the whole time series" regression. jsdom has no layout, so we give the LineChart
  // EXPLICIT width/height (bypassing ResponsiveContainer/SafeRechartsContainer, which would measure
  // 0×0 here) — recharts then computes real point coordinates in pure JS. We parse the rendered
  // line path's `d` and assert it spans (almost) the whole plotting width and has one vertex per
  // data point, i.e. the line covers the full x-range, not a fraction of it.
  // NB: this asserts the chart GEOMETRY/domain is correct; the intermittent real-browser stub the
  // user saw is a ResizeObserver sizing race (guarded separately by SafeRechartsContainer's
  // post-mount remeasure) and can only be reproduced in a headed browser, not jsdom.
  test("rendered line covers the full x-range (not a truncated stub)", () => {
    const N = 30;
    const chartRows = Array.from({ length: N }, (_, i) => ({ x: String(1996 + i), y: 95 + Math.sin(i / 3) * 8 }));
    const width = 640;
    const height = 360;
    const margin = { top: 16, right: 24, left: 12, bottom: 24 };

    const container = document.createElement("div");
    document.body.appendChild(container);

    let boundaryErr = null;
    const origDidCatch = ChartRenderErrorBoundary.prototype.componentDidCatch;
    ChartRenderErrorBoundary.prototype.componentDidCatch = function didCatch(err) {
      boundaryErr = err;
      if (origDidCatch) origDidCatch.call(this, err);
    };

    const root = createRoot(container);
    act(() => {
      root.render(
        <ChartRenderErrorBoundary>
          <LineChart width={width} height={height} data={chartRows} margin={margin}>
            <XAxis type="category" dataKey="x" interval={0} />
            <YAxis type="number" />
            <Line type="linear" dataKey="y" stroke="#2563eb" dot={false} isAnimationActive={false} />
          </LineChart>
        </ChartRenderErrorBoundary>
      );
    });

    expect(boundaryErr).toBeNull();
    const linePath =
      container.querySelector("path.recharts-line-curve") || container.querySelector("path.recharts-curve");
    expect(linePath).toBeTruthy();
    const d = linePath.getAttribute("d") || "";
    const nums = (d.match(/-?\d+(?:\.\d+)?/g) || []).map(Number);
    const xs = nums.filter((_, i) => i % 2 === 0); // linear path is "M x,y L x,y …" → x are even indices
    expect(xs.length).toBe(N); // one plotted vertex per data point (no dropped tail)
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);
    const plotWidth = width - margin.left - margin.right;
    // The line must span (almost) the whole plotting width, not a small fraction of it. A truncated
    // stub (e.g. locked to a tiny container width) would show maxX−minX far below the plot width.
    expect(maxX - minX).toBeGreaterThan(plotWidth * 0.8);

    root.unmount();
    document.body.removeChild(container);
    ChartRenderErrorBoundary.prototype.componentDidCatch = origDidCatch;
  });
});
