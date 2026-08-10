import React, { useCallback, useMemo, useState } from "react";
import { Globe2, LayoutGrid, Package, Table2 } from "lucide-react";
import { toast } from "sonner";

import MacroExtraTables from "@/components/catalog/MacroExtraTables";
import MacroComparisonTable from "@/components/catalog/MacroComparisonTable";
import { resolveMacroCatalogDef } from "@/lib/resolveMacroCatalogDef";

/**
 * Fast country/topic macro comparison page.
 */
export default function CatalogTopicBrowseSection({ catalogs, onPreviewSeries }) {
  const [view, setView] = useState("macro");
  const catalogById = useMemo(() => {
    const m = new Map();
    for (const c of catalogs || []) m.set(c.id, c);
    return m;
  }, [catalogs]);

  const handleSeriesClick = useCallback(
    (row) => {
      if (!onPreviewSeries || !row) return;
      const def = resolveMacroCatalogDef(catalogById, row);
      if (!def) {
        const catalogId = String(row.catalog_id || row.source || row.source_type || "").trim();
        toast.error(
          catalogId
            ? `Katalog "${catalogId}" není v aplikaci dostupný pro náhled grafu.`
            : "U řady chybí identifikace katalogu - graf nelze otevřít."
        );
        return;
      }
      onPreviewSeries(def, {
        ...row,
        set_id: row.set_id,
        name: row.name || row.title,
        kind: "set",
        item_kind: "selection",
        source_type: row.source_type || row.source || def.sourceType,
      });
    },
    [catalogById, onPreviewSeries]
  );

  return (
    <section
      className="rounded-2xl border border-border/80 bg-gradient-to-br from-violet-50/35 via-card to-sky-50/30 canvas-dark:from-violet-950/20 canvas-dark:via-card canvas-dark:to-sky-950/15 shadow-sm p-4 sm:p-5 space-y-3"
      aria-labelledby="catalog-topic-browse-title"
    >
      <div>
        <h2
          id="catalog-topic-browse-title"
          className="text-base font-semibold text-foreground tracking-tight flex items-center gap-2"
        >
          <LayoutGrid className="h-4 w-4 text-violet-600" />
          Procházet podle témat
        </h2>
        <p className="text-[12px] text-muted-foreground mt-1 max-w-2xl leading-snug">
          Rychlé makro srovnání zemí, základní komodity a detail České republiky ze snapshotů.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-1.5" role="tablist" aria-label="Typ tabulky">
        <button
          type="button"
          role="tab"
          aria-selected={view === "macro"}
          onClick={() => setView("macro")}
          className={`h-9 rounded-xl border px-3 text-[12px] font-semibold transition-colors inline-flex items-center gap-1.5 ${
            view === "macro"
              ? "border-violet-400 bg-violet-100 text-violet-950 shadow-sm"
              : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
          }`}
        >
          <Globe2 className="h-3.5 w-3.5" />
          EU / svět
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={view === "commodities"}
          onClick={() => setView("commodities")}
          className={`h-9 rounded-xl border px-3 text-[12px] font-semibold transition-colors inline-flex items-center gap-1.5 ${
            view === "commodities"
              ? "border-violet-400 bg-violet-100 text-violet-950 shadow-sm"
              : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
          }`}
        >
          <Package className="h-3.5 w-3.5" />
          Komodity/indexy
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={view === "czech"}
          onClick={() => setView("czech")}
          className={`h-9 rounded-xl border px-3 text-[12px] font-semibold transition-colors inline-flex items-center gap-1.5 ${
            view === "czech"
              ? "border-violet-400 bg-violet-100 text-violet-950 shadow-sm"
              : "border-border/80 bg-card text-muted-foreground hover:bg-muted/50"
          }`}
        >
          <Table2 className="h-3.5 w-3.5" />
          Česká republika
        </button>
      </div>

      {view === "macro" ? (
        <MacroComparisonTable onPreviewSeries={handleSeriesClick} embedded />
      ) : (
        <MacroExtraTables tableId={view} onPreviewSeries={handleSeriesClick} />
      )}
    </section>
  );
}
