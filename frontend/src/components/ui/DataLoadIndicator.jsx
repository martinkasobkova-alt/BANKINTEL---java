import LoadingSpinner from "@/components/ui/loading/LoadingSpinner.jsx";

/** Tři tečky s odstupem v animaci „bounce“ — vypadá to, že se něco děje. */
export function BouncingDots({ className = "" }) {
  return (
    <span
      className={`inline-flex items-center justify-center gap-1 motion-reduce:hidden ${className}`}
      aria-hidden
    >
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="inline-block rounded-full bg-[hsl(var(--primary)/0.55)] animate-bounce"
          style={{
            width: "0.3rem",
            height: "0.3rem",
            animationDuration: "0.55s",
            animationDelay: `${i * 0.12}s`,
          }}
        />
      ))}
    </span>
  );
}

/**
 * Hlavní plocha náhledu (SourcePreview) — rotující kolečko + text + tečky.
 */
export function DataLoadIndicator({ label = "Načítám náhled…", className = "", compact = false }) {
  return (
    <div
      className={`flex flex-col items-center justify-center gap-2 ${compact ? "py-6" : "py-10"} px-4 text-center ${className}`}
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={label}
    >
      <div className="flex items-center justify-center gap-3 text-sm text-slate-600 flex-wrap">
        <LoadingSpinner suppressAria size={compact ? "md" : "lg"} />
        <span className="font-mono text-slate-500" aria-hidden="true">{label}</span>
      </div>
      <BouncingDots className="mt-0.5" />
    </div>
  );
}

/**
 * Jeden řádek (např. pod stav v katalogu) — menší ikona + tečky.
 */
export function DataLoadInline({ label, className = "" }) {
  return (
    <span
      className={`inline-flex items-center gap-2 text-[11px] text-slate-500 font-mono flex-wrap ${className}`}
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={label}
    >
      <LoadingSpinner suppressAria size="xs" />
      <span aria-hidden="true">{label}</span>
      <BouncingDots className="ml-0.5" />
    </span>
  );
}

/**
 * Jeden řádek pro úzké místo ve widget editoru (dříve jen monospaced text).
 */
export function DataLoadRowTight({ label = "Načítám náhled…" }) {
  return (
    <div
      className="flex items-center gap-2 text-xs text-slate-500 font-mono py-2"
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={label}
    >
      <LoadingSpinner suppressAria size="xs" />
      <span aria-hidden="true">{label}</span>
      <BouncingDots />
    </div>
  );
}
