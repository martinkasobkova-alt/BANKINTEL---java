import React from "react";
import CatalogColumnExplorer, { CatalogExplorerSeriesButton } from "@/components/catalog/search/CatalogColumnExplorer";
import { DataLoadInline } from "@/components/ui/DataLoadIndicator";
import { LoadingBlock } from "@/components/ui/loading";
import {
  ALPHAVANTAGE_CATALOG_NOTE_CZ,
  YAHOO_FINANCE_CATALOG_NOTE_CZ,
} from "@/lib/catalogDefinitions";

export default function CatalogExplorerPanel({
  t,
  browseCatalogId,
  browseDef,
  browseOptions,
  selected,
  trees,
  loadingCats,
  errors,
  browseIndexPending,
  browseEmptyFilteredOut,
  browseRescueNotice,
  emptyBrowseMessage,
  explorerColumns,
  openPaths,
  explorerLoadingRowKey = null,
  onSelectRow,
  onClearBrowseFilter,
  loadingPrimaryLabel,
  errorFallbackLinks,
  catalogProfileTagline,
  explorerLayoutMode = "default",
  onClose,
}) {
  const notes = (
    <>
      {trees[browseCatalogId]?.fred_api_configured === false && browseCatalogId === "fred" ? (
        <div className="rounded-lg border border-rose-200 bg-rose-50/95 px-3 py-2 text-[11px] text-rose-950 leading-snug mb-3">
          <strong>FRED API key není nastavený na backendu.</strong>
        </div>
      ) : null}
      {browseCatalogId === "alphavantage" ? (
        <div className="rounded-lg border border-sky-200/90 bg-sky-50/90 px-3 py-2 text-[11px] mb-3">{ALPHAVANTAGE_CATALOG_NOTE_CZ}</div>
      ) : null}
      {browseCatalogId === "yahoo_finance" ? (
        <div className="rounded-lg border border-sky-200/90 bg-sky-50/90 px-3 py-2 text-[11px] mb-3">{YAHOO_FINANCE_CATALOG_NOTE_CZ}</div>
      ) : null}
      {browseRescueNotice ? (
        <div className="rounded-lg border border-amber-300/70 bg-amber-50 px-3 py-2 text-[11px] mb-3">{browseRescueNotice}</div>
      ) : null}
    </>
  );

  const loadingState =
    loadingCats[browseCatalogId] && !trees[browseCatalogId] ? (
      <LoadingBlock label={loadingPrimaryLabel} minHeightClass="min-h-[12rem]" showSkeletonLines />
    ) : browseIndexPending ? (
      <div className="text-sm border border-border rounded-xl p-6 bg-card/80">
        <DataLoadInline label={`Zpracovávám strom katalogu ${browseDef?.label || ""}…`} />
      </div>
    ) : null;

  const emptyState =
    browseOptions.length === 0 ? (
      <div className="text-sm text-muted-foreground border border-dashed rounded-xl p-6 text-center">
        Zaškrtněte ve filtrech alespoň jeden zdroj.
      </div>
    ) : !browseCatalogId ? (
      <div className="text-sm text-muted-foreground border border-dashed rounded-xl p-6 text-center leading-snug">
        Pro procházení složek nejdřív vyberte konkrétní katalog v rozbalovacím menu{" "}
        <span className="font-medium text-foreground">Katalog</span> v horním panelu.
      </div>
    ) : !selected.has(browseCatalogId) ? (
      <div className="text-sm text-muted-foreground border border-dashed rounded-xl p-6 text-center">
        Vyberte tento zdroj ve filtrech.
      </div>
    ) : errors[browseCatalogId] ? (
      <div className="text-sm rounded-xl p-6 bg-card/80 border border-rose-200 space-y-2">
        <p className="font-semibold">{browseDef?.label}</p>
        <p className="text-[13px] whitespace-pre-wrap font-mono">{errors[browseCatalogId]}</p>
        {errorFallbackLinks}
      </div>
    ) : explorerColumns.every((c) => !c.items.length) ? (
      <div className="border border-dashed rounded-2xl p-8 text-center text-sm">
        {browseEmptyFilteredOut ? (
          <button type="button" className="text-sm px-3 py-1.5 rounded-lg border" onClick={onClearBrowseFilter}>
            Zrušit filtr
          </button>
        ) : (
          emptyBrowseMessage
        )}
      </div>
    ) : null;

  return (
    <div className="space-y-3 min-h-0 flex flex-col h-full">
      {notes}
      <CatalogColumnExplorer
        className="flex-1 min-h-0"
        layoutMode={explorerLayoutMode}
        title={t("pages.catalogHub.browseFolders")}
        subtitle="Kliknutím otevřete další sloupec doprava. Při více sloupcích posuvník dole (← →), při dlouhém seznamu ukazatelů svislý posuvník ve sloupci."
        columns={explorerColumns}
        openPaths={openPaths}
        loadingRowKey={explorerLoadingRowKey}
        onSelectRow={onSelectRow}
        loadingState={loadingState}
        emptyState={emptyState}
        renderSeriesRow={(row, { isSelected, onSelect }) => (
          <CatalogExplorerSeriesButton row={row} isSelected={isSelected} onSelect={onSelect} />
        )}
        footer={
          <span>
            Aktuální katalog: <strong>{browseDef?.label || "—"}</strong>
            {catalogProfileTagline ? ` · ${catalogProfileTagline}` : ""}
          </span>
        }
        onClose={onClose}
      />
    </div>
  );
}
