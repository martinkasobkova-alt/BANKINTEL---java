import { useEffect, useRef } from "react";
import { useLocation } from "react-router-dom";

/**
 * Po načtení stránky najde `#widget-{id}` z URL a posune hlavní scroll k dlaždici,
 * krátce zvýrazní rámeček (aby uživatel viděl cíl z výsledku hledání).
 */
export function useScrollToWidgetFromHash({ loading, widgetSignature }) {
  const location = useLocation();
  const doneRef = useRef("");

  useEffect(() => {
    if (loading) return;
    const raw = location.hash ? String(location.hash).replace(/^#/, "") : "";
    if (!raw.startsWith("widget-")) return;
    const wid = decodeURIComponent(raw.slice("widget-".length)).trim();
    if (!wid) return;
    const key = `${location.pathname}|${raw}|${widgetSignature || ""}`;
    if (doneRef.current === key) return;

    let cancelled = false;
    let attempts = 0;

    const pulse = (el) => {
      const prev = el.style.outline;
      const prevW = el.style.outlineOffset;
      el.style.outline = "2px solid hsl(199 89% 48%)";
      el.style.outlineOffset = "2px";
      window.setTimeout(() => {
        if (!el) return;
        el.style.outline = prev;
        el.style.outlineOffset = prevW;
      }, 2600);
    };

    const tick = () => {
      if (cancelled) return;
      const el = document.getElementById(`widget-${wid}`);
      if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "center" });
        pulse(el);
        doneRef.current = key;
        return;
      }
      attempts += 1;
      if (attempts < 50) {
        window.requestAnimationFrame(() => window.setTimeout(tick, 80));
      }
    };

    const t = window.setTimeout(tick, 200);
    return () => {
      cancelled = true;
      window.clearTimeout(t);
    };
  }, [loading, location.pathname, location.hash, widgetSignature]);
}
