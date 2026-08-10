import React, { useLayoutEffect, useRef, useState } from "react";
import { ResponsiveContainer } from "recharts";

/** Zaokrouhlí rozměr na fyzické CSS px podle DPR — méně subpixelového „rozmazání“ SVG u Recharts na HiDPI. */
function alignToDevicePixels(n, dpr) {
  const r = Number.isFinite(dpr) && dpr > 0 ? dpr : 1;
  const raw = Math.max(1, n);
  return Math.max(1, Math.round(raw * r) / r);
}

/**
 * Chyba uvnitř Recharts nesmí shodit celý dashboard (jen lokální fallback).
 */
export class ChartRenderErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { err: null };
  }

  static getDerivedStateFromError(err) {
    return { err };
  }

  componentDidCatch(err, info) {
    // eslint-disable-next-line no-console
    console.error("[Chart]", err, info?.componentStack);
  }

  render() {
    if (this.state.err) {
      const devDetail =
        process.env.NODE_ENV !== "production" && this.state.err?.message
          ? String(this.state.err.message).slice(0, 160)
          : null;
      return (
        <div className="flex h-full min-h-[72px] flex-col items-center justify-center gap-1 px-2 text-center text-[11px] text-slate-500">
          <span>Graf se nepodařilo vykreslit.</span>
          {devDetail ? (
            <span className="max-w-full break-words font-mono text-[9px] text-red-500/90">{devDetail}</span>
          ) : null}
          <button
            type="button"
            className="font-mono text-[10px] text-slate-600 underline"
            onClick={() => this.setState({ err: null })}
          >
            Zkusit znovu
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

/**
 * Měří rodiče a předává Recharts jen kladné px rozměry — eliminuje width(-1)/height(-1)
 * při flex/resize, kdy parent ještě nemá vypočtenou velikost.
 */
export function SafeRechartsContainer({
  children,
  height = "100%",
  minHeight = 72,
  className = "",
  style = {},
  debounce = 0,
  ...props
}) {
  const hostRef = useRef(null);
  const [box, setBox] = useState({ w: 0, h: 0 });

  useLayoutEffect(() => {
    const el = hostRef.current;
    if (!el) return undefined;
    const minHLocal = typeof minHeight === "number" ? minHeight : 72;
    let raf = 0;
    const timers = [];
    const measure = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        const rect = el.getBoundingClientRect();
        const dpr = typeof window !== "undefined" && window.devicePixelRatio ? window.devicePixelRatio : 1;
        const w = alignToDevicePixels(rect.width, dpr);
        const h = alignToDevicePixels(Math.max(minHLocal, rect.height), dpr);
        setBox((prev) => (prev.w === w && prev.h === h ? prev : { w, h }));
      });
    };
    measure();
    // Mount-time race guard: the flex/grid track that sizes this chart often reaches its final
    // width a frame or two AFTER the first layout pass. Without these follow-up measures the chart
    // could lock onto a transitional (tiny) width and — because the ResizeObserver below watches
    // only the host element — never redraw until an unrelated layout change (e.g. opening the
    // Analytika/Forecast panel) happened to resize the host. Re-measuring shortly after mount
    // catches the settled layout; setBox is idempotent, so these are no-ops when the first measure
    // was already correct.
    [60, 250, 600].forEach((ms) => timers.push(setTimeout(measure, ms)));
    const clearTimers = () => {
      cancelAnimationFrame(raf);
      timers.forEach(clearTimeout);
    };
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", measure);
      return () => {
        clearTimers();
        window.removeEventListener("resize", measure);
      };
    }
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    // Also observe the parent track: a sibling/track resize that changes the host's available
    // width does not always refire the host-only observation in time on first layout.
    if (el.parentElement) ro.observe(el.parentElement);
    return () => {
      clearTimers();
      ro.disconnect();
    };
  }, [minHeight]);

  const minH = typeof minHeight === "number" ? minHeight : 72;
  const wPx = Math.max(1, box.w);
  const hPx = Math.max(minH, box.h);
  const chartOk = box.w >= 1 && box.h >= 1;

  return (
    <div
      ref={hostRef}
      className={`h-full w-full min-w-[1px] ${className}`}
      style={{ minHeight, height, ...style }}
    >
      {chartOk ? (
        <ResponsiveContainer width={wPx} height={hPx} minWidth={1} minHeight={minH} debounce={debounce} {...props}>
          <ChartRenderErrorBoundary>{children}</ChartRenderErrorBoundary>
        </ResponsiveContainer>
      ) : (
        <div
          className="flex h-full min-h-[72px] w-full items-center justify-center rounded-sm border border-dashed border-slate-200 bg-slate-50 text-[10px] text-slate-400"
          aria-hidden
        >
          ...
        </div>
      )}
    </div>
  );
}
