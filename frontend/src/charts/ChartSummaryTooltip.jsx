import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

export default function ChartSummaryTooltip({ text, children, className = "" }) {
  const ref = useRef(null);
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState({ left: 0, top: 0 });

  const updatePosition = useCallback(() => {
    const el = ref.current?.firstElementChild || ref.current;
    if (!el || typeof window === "undefined") return;
    const rect = el.getBoundingClientRect();
    const left = clamp(rect.left + rect.width / 2, 12, window.innerWidth - 12);
    const top = Math.max(10, rect.top - 8);
    setPos({ left, top });
  }, []);

  const show = useCallback(() => {
    if (!text) return;
    updatePosition();
    setOpen(true);
  }, [text, updatePosition]);

  const hide = useCallback(() => setOpen(false), []);

  const toggle = useCallback((event) => {
    if (!text) return;
    event.stopPropagation();
    updatePosition();
    setOpen((prev) => !prev);
  }, [text, updatePosition]);

  useEffect(() => {
    if (!open) return undefined;
    const close = () => setOpen(false);
    const reposition = () => updatePosition();
    window.addEventListener("scroll", reposition, true);
    window.addEventListener("resize", reposition);
    window.addEventListener("pointerdown", close);
    window.addEventListener("keydown", close);
    return () => {
      window.removeEventListener("scroll", reposition, true);
      window.removeEventListener("resize", reposition);
      window.removeEventListener("pointerdown", close);
      window.removeEventListener("keydown", close);
    };
  }, [open, updatePosition]);

  return (
    <div
      ref={ref}
      className={`min-w-0 ${className}`}
      title={text || undefined}
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
      onClick={toggle}
    >
      {children}
      {open && text && typeof document !== "undefined"
        ? createPortal(
            <div
              className="pointer-events-none fixed z-[9999] max-w-[min(320px,calc(100vw-24px))] -translate-x-1/2 -translate-y-full rounded-lg border border-slate-200 bg-slate-950 px-3 py-2 text-xs font-medium leading-snug text-white shadow-xl"
              style={{ left: pos.left, top: pos.top }}
              role="tooltip"
            >
              {text}
            </div>,
            document.body
          )
        : null}
    </div>
  );
}
