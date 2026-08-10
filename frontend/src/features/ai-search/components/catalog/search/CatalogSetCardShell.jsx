import React from "react";

export function SavedVisualCatalogCard({ title, setId, actions }) {
  return (
    <article
      className="catalog-result-card rounded-lg border border-border/80 bg-card px-3 py-2 shadow-sm transition-colors min-w-0 h-full hover:border-sky-200/70 hover:bg-muted/20"
      data-testid="saved-visual-search-card"
    >
      <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px] min-w-0">
        <span className="text-[10px] uppercase tracking-wider px-1.5 py-0.5 rounded-md bg-muted text-foreground/90 font-semibold shrink-0">
          Uložený graf
        </span>
      </div>
      <h3 className="text-[13px] font-medium text-foreground leading-snug line-clamp-2 mt-1">
        {title}
      </h3>
      {setId ? (
        <p className="text-[10px] text-muted-foreground font-mono truncate mt-1">{setId}</p>
      ) : null}
      <p className="text-[11px] text-muted-foreground mt-2 leading-snug line-clamp-2">
        Graf je uložený na dashboardu. Otevřete ho pro plný náhled a práci s vizualizací.
      </p>
      <div className="mt-2 pt-1.5 border-t border-border/40">{actions}</div>
    </article>
  );
}

export function CatalogSetDeepSearchHeader({
  showRank,
  rankLabel,
  sourceLabel,
  tierBadge,
  matchQuality,
  setId,
}) {
  return (
    <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px] min-w-0">
      {showRank ? (
        <span className="text-[11px] font-semibold text-foreground shrink-0">#{rankLabel}</span>
      ) : null}
      <span className="font-semibold text-foreground/75 shrink-0">{sourceLabel}</span>
      {tierBadge}
      {matchQuality ? (
        <span className="font-medium uppercase tracking-wide shrink-0 text-muted-foreground">
          Shoda: {matchQuality}
        </span>
      ) : null}
      {setId ? (
        <span className="font-mono text-muted-foreground truncate min-w-0">
          {setId}
        </span>
      ) : null}
    </div>
  );
}
