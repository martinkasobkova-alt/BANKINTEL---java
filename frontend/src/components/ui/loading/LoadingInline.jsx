import React from "react";
import { BouncingDots } from "@/components/ui/DataLoadIndicator";
import LoadingSpinner from "./LoadingSpinner.jsx";

/** Jednorádkově: spinner + text (náhledy, dílčí stavy). */
export function LoadingInline({
  label = "Načítám…",
  size = "sm",
  muted = false,
  className = "",
}) {
  return (
    <div
      className={`inline-flex items-center gap-2.5 max-w-full min-w-0 flex-wrap ${
        muted ? "text-slate-500" : "text-slate-700"
      } ${className}`.trim()}
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={label}
    >
      <LoadingSpinner suppressAria size={size} aria-label="" className={muted ? "!opacity-80" : ""} />
      <span className="text-[11px] sm:text-xs font-mono truncate" aria-hidden>
        {label}
      </span>
      <BouncingDots className="shrink-0" />
    </div>
  );
}
