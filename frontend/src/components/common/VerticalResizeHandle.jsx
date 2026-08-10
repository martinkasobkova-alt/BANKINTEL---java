import React from "react";

export default function VerticalResizeHandle({
  onPointerDown,
  label = "Změnit poměr grafu a panelu",
}) {
  return (
    <div
      role="separator"
      aria-orientation="horizontal"
      aria-label={label}
      tabIndex={0}
      onPointerDown={onPointerDown}
      className="group relative z-[2] mx-2 flex h-3 shrink-0 cursor-ns-resize items-center justify-center touch-none select-none"
      data-testid="vertical-resize-handle"
      data-export-ignore="true"
    >
      <div className="pointer-events-none h-1 w-14 rounded-full border border-border/40 bg-border/60 shadow-sm transition group-hover:border-sky-300 group-hover:bg-sky-300/70 group-active:bg-sky-500/80" />
    </div>
  );
}
