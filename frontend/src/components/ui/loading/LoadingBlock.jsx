import React from "react";
import { BouncingDots } from "@/components/ui/DataLoadIndicator";
import LoadingSpinner from "./LoadingSpinner.jsx";

/** Několik pulzujících řádků při načítání seznamu (ne agresivní). */
export function SkeletonList({ rows = 4, className = "" }) {
  return (
    <div
      className={`space-y-2 w-full ${className}`.trim()}
      aria-hidden="true"
    >
      {Array.from({ length: rows }).map((_, i) => (
        <div
          // eslint-disable-next-line react/no-array-index-key
          key={i}
          className="h-8 sm:h-9 rounded-lg bg-gradient-to-r from-slate-200/85 via-slate-100/95 to-slate-200/85 animate-pulse motion-reduce:animate-none motion-reduce:opacity-80"
          style={{ animationDuration: "1.15s", maxWidth: `${88 - i * 6}%` }}
        />
      ))}
    </div>
  );
}

/**
 * Vyšší blok s popiskem + volitelnou kostrou — katalogové seznamy, prázdné střední pásy.
 */
export function LoadingBlock({
  label,
  sublabel,
  minHeightClass = "min-h-[120px]",
  showSkeletonLines = false,
  skeletonRows = 4,
  className = "",
}) {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={label}
      className={`rounded-2xl border border-border/80 bg-slate-50/60 px-5 py-8 flex flex-col items-center justify-center gap-4 w-full max-w-full box-border ${minHeightClass} ${className}`.trim()}
    >
      <div className="flex flex-col items-center gap-2 flex-wrap justify-center text-center px-2">
        <div className="flex items-center gap-3 flex-wrap justify-center">
          <LoadingSpinner suppressAria />
          <span className="text-sm text-slate-700 font-mono">{label}</span>
        </div>
        <BouncingDots className="mt-0.5" />
      </div>
      {sublabel ? (
        <p className="text-[11px] text-slate-600 font-sans text-center leading-snug max-w-prose">{sublabel}</p>
      ) : null}
      {showSkeletonLines ? (
        <div className="w-full max-w-xl mt-2">
          <SkeletonList rows={skeletonRows} />
        </div>
      ) : null}
    </div>
  );
}
