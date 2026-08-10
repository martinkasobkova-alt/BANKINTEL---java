import React from "react";
import { ChevronRight } from "lucide-react";
import { buildCatalogPathSegments } from "@/lib/catalogSearchPathNav";

export { buildCatalogPathPrefixes, splitCatalogPath } from "@/lib/catalogSearchPathNav";

/**
 * Klikací breadcrumb cesty v katalogu — otevře příslušnou větev ve stromu níže.
 */
export default function CatalogSearchPathNav({
  catalogPath,
  categoryPaths = null,
  onOpenPath,
  matchHintCs = "",
  showOpenAll = true,
  className = "",
}) {
  const path = String(catalogPath || "").trim();
  if (!path) return null;

  const segments = buildCatalogPathSegments(path, categoryPaths);
  if (!segments.length) return null;

  const clickableSegments = segments.filter((s) => s.clickable);
  const deepestFolder = clickableSegments.length ? clickableSegments[clickableSegments.length - 1].prefix : "";

  const handleOpen = (prefix, event) => {
    event?.preventDefault?.();
    event?.stopPropagation?.();
    if (typeof onOpenPath === "function" && prefix) onOpenPath(prefix);
  };

  return (
    <div
      className={`space-y-1.5 ${className}`.trim()}
      data-testid="catalog-search-path-nav"
      onClick={(e) => e.stopPropagation()}
    >
      <div className="text-[10px] uppercase tracking-wide text-muted-foreground font-semibold">
        Cesta v katalogu
      </div>
      {matchHintCs ? (
        <p className="text-[11px] text-amber-900/90 canvas-dark:text-amber-100/90 leading-snug">{matchHintCs}</p>
      ) : null}
      <nav
        aria-label="Cesta v katalogu"
        className="flex flex-wrap items-center gap-0.5 text-[11px] leading-snug"
      >
        {segments.map((seg, index) => (
          <React.Fragment key={`${seg.prefix}-${index}`}>
            {index > 0 ? (
              <ChevronRight className="h-3 w-3 shrink-0 text-muted-foreground/70" aria-hidden="true" />
            ) : null}
            {seg.clickable ? (
              <button
                type="button"
                title={seg.prefix}
                className={`px-1.5 py-0.5 rounded-md border text-left transition-colors max-w-full truncate ${
                  seg.isLast && seg.clickable
                    ? "border-border/80 bg-muted/40 text-foreground font-medium"
                    : "border-border/60 bg-card hover:bg-muted/50 text-foreground/90"
                }`}
                onClick={(e) => handleOpen(seg.prefix, e)}
                data-testid={`catalog-search-path-segment-${index}`}
              >
                {seg.label}
              </button>
            ) : (
              <span
                className="px-1.5 py-0.5 rounded-md border border-dashed border-border/70 bg-muted/20 text-muted-foreground max-w-full truncate"
                title={`Název datasetu (není složka ve stromu): ${seg.label}`}
                data-testid={`catalog-search-path-leaf-${index}`}
              >
                {seg.label}
              </span>
            )}
          </React.Fragment>
        ))}
      </nav>
      {showOpenAll && typeof onOpenPath === "function" && deepestFolder ? (
        <button
          type="button"
          className="h-7 px-2.5 text-[11px] rounded-md border border-border/70 bg-white canvas-dark:bg-card text-slate-700 canvas-dark:text-foreground hover:bg-muted/50 canvas-dark:hover:bg-muted/50"
          onClick={(e) => handleOpen(deepestFolder, e)}
          data-testid="catalog-search-path-open-tree"
        >
          Otevřít ve stromu
        </button>
      ) : null}
    </div>
  );
}
