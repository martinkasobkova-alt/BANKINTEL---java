import React from "react";
import { LayoutGrid, Table2 } from "lucide-react";

export default function CatalogResultsToolbar({
  title = "Výsledky hledání",
  count,
  viewMode = "cards",
  onViewModeChange,
  sortMode = "relevance",
  onSortChange,
  extra,
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
      <div className="min-w-0">
        <h2 className="text-base sm:text-lg font-semibold text-foreground">{title}</h2>
        {typeof count === "number" ? (
          <p className="text-[12px] text-muted-foreground mt-0.5">
            {count} {count === 1 ? "výsledek" : count >= 2 && count <= 4 ? "výsledky" : "výsledků"}
          </p>
        ) : null}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {extra}
        <div className="inline-flex rounded-lg border border-border/80 bg-card p-0.5 shadow-sm">
          <button
            type="button"
            onClick={() => onViewModeChange?.("cards")}
            className={`inline-flex items-center gap-1.5 h-8 px-2.5 text-[12px] rounded-md ${
              viewMode === "cards" ? "bg-sky-100 text-sky-950 font-medium" : "text-muted-foreground hover:bg-muted/50"
            }`}
          >
            <LayoutGrid className="h-3.5 w-3.5" />
            Karty
          </button>
          <button
            type="button"
            onClick={() => onViewModeChange?.("table")}
            className={`inline-flex items-center gap-1.5 h-8 px-2.5 text-[12px] rounded-md ${
              viewMode === "table" ? "bg-sky-100 text-sky-950 font-medium" : "text-muted-foreground hover:bg-muted/50"
            }`}
          >
            <Table2 className="h-3.5 w-3.5" />
            Tabulka
          </button>
        </div>
        <select
          className="h-8 px-2 text-[12px] rounded-lg border border-border/80 bg-card shadow-sm"
          value={sortMode}
          onChange={(e) => onSortChange?.(e.target.value)}
          aria-label="Řazení výsledků"
        >
          <option value="relevance">Relevance</option>
          <option value="name">Název A–Z</option>
          <option value="catalog">Katalog</option>
        </select>
      </div>
    </div>
  );
}
