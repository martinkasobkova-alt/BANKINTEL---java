import React, { useLayoutEffect, useRef, useState } from "react";
import { fmtCompact } from "@/lib/format";
import { CHART_THEME_DEFAULT } from "@/lib/chartTheme";

/**
 * Sticky Y-axis gutter — extrahováno z AradView (stejná logika, sdílené pro Chart System).
 * Musí sdílet doménu s hlavním plotem (buildStickyYAxisSpec).
 */
export default function FrozenYAxisGutter({ spec, chartTheme }) {
  const rootRef = useRef(null);
  const [height, setHeight] = useState(0);

  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el) return undefined;
    const sync = () => setHeight(el.clientHeight || 0);
    sync();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", sync);
      return () => window.removeEventListener("resize", sync);
    }
    const ro = new ResizeObserver(sync);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  if (!spec || !Array.isArray(spec.ticks) || spec.ticks.length === 0) return null;
  const min = Number(spec.min);
  const max = Number(spec.max);
  if (!Number.isFinite(min) || !Number.isFinite(max) || max === min) return null;

  const plotTop = Number(spec.top) || 0;
  const plotBottom = Number(spec.bottom) || 0;
  const axisTop = plotTop;
  const axisHeight = Math.max(0, height - plotTop - plotBottom);
  const axisColor = chartTheme?.grid || CHART_THEME_DEFAULT.grid;
  const tickColor = chartTheme?.axis || CHART_THEME_DEFAULT.axis;
  const maskBleed = 4;

  return (
    <div
      ref={rootRef}
      className="relative h-full shrink-0 self-stretch"
      style={{
        width: spec.width + maskBleed,
        background: "hsl(var(--card))",
      }}
      aria-hidden
    >
      <div className="absolute inset-0" style={{ left: 0, right: maskBleed }}>
        <div
          className="absolute right-0 w-px"
          style={{ top: axisTop, height: axisHeight, backgroundColor: axisColor }}
        />
        {spec.ticks.map((tick) => {
          const ratio = (Number(tick) - min) / (max - min);
          const rawY = axisTop + (1 - ratio) * axisHeight;
          const labelPad = Math.ceil((Number(spec.fontSize) || 10) * 0.65);
          const tickNum = Number(tick);
          const span = Math.abs(max - min) || 1;
          const isMaxTick = tickNum >= max - span * 1e-9;
          const isMinTick = tickNum <= min + span * 1e-9;
          let y = rawY;
          let transform = "translateY(-50%)";
          if (isMaxTick) {
            y = Math.max(rawY, axisTop + labelPad);
            transform = "translateY(-100%)";
          } else if (isMinTick) {
            y = height - plotBottom;
            transform = "translateY(-100%)";
          }
          return (
            <div
              key={tick}
              className="absolute left-0 right-1 flex items-center justify-end pr-1 font-mono"
              style={{
                top: y,
                transform,
                color: tickColor,
                fontSize: spec.fontSize,
              }}
            >
              {fmtCompact(tick)}
            </div>
          );
        })}
      </div>
    </div>
  );
}
