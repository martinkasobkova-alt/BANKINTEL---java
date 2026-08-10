import React from "react";

const SIZE_CLS = {
  xs: "h-3 w-3 border-[1.5px]",
  sm: "h-4 w-4 border-2",
  md: "h-6 w-6 border-2",
  lg: "h-9 w-9 border-[3px]",
};

/**
 * Jemný kruhový spinner — barvy z CSS (`--primary`).
 * U `motion-reduce: reduce` rotace ustane, jen mírná průhlednost.
 */
export default function LoadingSpinner({
  size = "md",
  className = "",
  "aria-label": ariaLabel = "Načítám…",
  suppressAria = false,
}) {
  const sz = SIZE_CLS[size] || SIZE_CLS.md;
  return (
    <span
      role={suppressAria ? undefined : "status"}
      aria-label={suppressAria ? undefined : ariaLabel}
      aria-live={suppressAria ? undefined : "polite"}
      aria-busy={suppressAria ? undefined : "true"}
      aria-hidden={suppressAria ? true : undefined}
      className={`inline-block shrink-0 rounded-full ${sz} border-slate-200/95 border-t-[hsl(var(--primary))] border-l-transparent animate-spin motion-reduce:animate-none motion-reduce:opacity-75 motion-reduce:border-[hsl(var(--primary)/0.42)] ${className || ""}`.trim()}
    />
  );
}
