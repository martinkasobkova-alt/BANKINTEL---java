import React from "react";
import { Link } from "react-router-dom";
import { Lock } from "lucide-react";

/**
 * Small inline or block notice for locked premium features. Keeps the control visible (caller disables it).
 */
export default function FeatureLock({ message, className = "", compact = false, children }) {
  const text = message || "Tato funkce je určena pro předplatitele.";
  if (compact) {
    return (
      <span className={`inline-flex items-center gap-1 text-[10px] text-slate-500 ${className}`}>
        <Lock className="h-3 w-3 shrink-0 text-amber-600" aria-hidden />
        <span>{text}</span>
        <Link to="/predplatne" className="text-[hsl(var(--primary))] underline font-medium whitespace-nowrap">
          Zjistit více o předplatném
        </Link>
        {children}
      </span>
    );
  }
  return (
    <div
      className={`rounded-lg border border-amber-200/80 bg-amber-50/90 px-3 py-2 text-[11px] text-slate-700 ${className}`}
      role="status"
    >
      <div className="flex items-start gap-2">
        <Lock className="h-4 w-4 shrink-0 text-amber-600 mt-0.5" aria-hidden />
        <div className="min-w-0 space-y-1">
          <p className="leading-snug">{text}</p>
          <Link
            to="/predplatne"
            className="inline-block text-[hsl(var(--primary))] font-semibold underline text-[12px]"
          >
            Zjistit více o předplatném
          </Link>
        </div>
      </div>
      {children}
    </div>
  );
}
