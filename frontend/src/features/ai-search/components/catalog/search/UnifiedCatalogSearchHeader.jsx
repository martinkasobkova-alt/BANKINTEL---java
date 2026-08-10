import React from "react";
import {
  Search,
  Sparkles,
  Filter,
  PanelLeftClose,
  PanelLeftOpen,
  Folder,
  ArrowLeft,
  ChevronDown,
  Database,
} from "lucide-react";
import { LoadingSpinner } from "@/components/ui/loading";
import CatalogDatabaseInfo from "@/components/catalog/CatalogDatabaseInfo";
import VoiceInputButton from "@/components/common/VoiceInputButton";

/**
 * Sticky search-first header (spec §2).
 */
export default function UnifiedCatalogSearchHeader({
  unifiedQuery,
  onQueryChange,
  onSubmit,
  useAiAssistant,
  onAiToggle,
  browseCatalogId,
  browseOptions = [],
  onBrowseCatalogChange,
  classicSearchScope,
  onClassicSearchScopeChange,
  getCatalogBrowseDropdownLabel,
  catalogLabel,
  filtersOpen,
  onFiltersToggle,
  browseSidebarOpen,
  mobileBrowseOpen = false,
  onCatalogToggle,
  aiSuggestGateEnabled,
  deepLoading,
  onCancelDeepSearch,
  catalogSearchEcbHint,
  catalogDeepSearchReady,
  canCatalogDeepSearch,
  catalogDeepSearchLockMsg,
  filtersPanel,
  showBrowseToggle = true,
  catalogViewMode = "browse",
  onBackToCatalog,
}) {
  const activeCatalogId = String(classicSearchScope ?? browseCatalogId ?? "").trim();
  const activeCatalogDef =
    browseOptions.find((c) => c.id === activeCatalogId) ||
    (activeCatalogId ? { id: activeCatalogId, label: activeCatalogId } : null);
  const activeCatalogLabel = activeCatalogDef
    ? getCatalogBrowseDropdownLabel(activeCatalogDef)
    : "";

  return (
    <section
      className="catalog-unified-header rounded-2xl border border-sky-100/90 p-4 sm:p-5 space-y-3 min-w-0 w-full"
      aria-labelledby="catalog-unified-search-title"
    >
      <h2 id="catalog-unified-search-title" className="sr-only">
        Vyhledávání v katalogu
      </h2>

      <form className="space-y-3" onSubmit={onSubmit}>
        <div className="catalog-unified-header-toolbar flex flex-col gap-3 xl:flex-row xl:items-center xl:flex-wrap p-0.5">
          {catalogViewMode === "detail" ? (
            <div className="flex shrink-0">
              <button
                type="button"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onBackToCatalog?.();
                }}
                className="inline-flex items-center justify-center gap-1.5 h-11 px-3 rounded-xl border border-[hsl(var(--border)/0.75)] bg-card hover:bg-muted/50 text-sm font-medium shadow-sm"
                data-testid="catalog-header-back"
              >
                <ArrowLeft className="h-4 w-4 shrink-0" aria-hidden />
                <span className="leading-none">Zpět na katalog</span>
              </button>
            </div>
          ) : showBrowseToggle || !browseSidebarOpen ? (
            <div className="hidden xl:flex shrink-0">
              <button
                type="button"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onCatalogToggle?.();
                }}
                className="catalog-browse-toggle h-11 w-11 inline-flex items-center justify-center rounded-xl border border-[hsl(var(--border)/0.75)] bg-card hover:bg-muted/50 text-foreground shadow-sm relative z-10 pointer-events-auto"
                aria-label={browseSidebarOpen ? "Sbalit katalog" : "Otevřít katalog"}
                aria-expanded={browseSidebarOpen}
                title={browseSidebarOpen ? "Sbalit katalog" : "Otevřít katalog"}
                data-testid={browseSidebarOpen ? "catalog-browse-sidebar-close" : "catalog-browse-sidebar-open"}
              >
                {browseSidebarOpen ? (
                  <PanelLeftClose className="h-4 w-4" />
                ) : (
                  <PanelLeftOpen className="h-4 w-4" />
                )}
              </button>
            </div>
          ) : null}

          <div className="relative flex-1 min-w-0 w-full xl:min-w-[16rem] xl:max-w-none">
            <Search className="h-5 w-5 absolute left-3.5 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
            <input
              type="search"
              className="catalog-unified-header-input w-full h-11 sm:h-12 pl-11 pr-12 rounded-xl text-[15px] sm:text-base bg-card"
              placeholder="Hledat datovou řadu, ukazatel nebo ekonomický dotaz…"
              value={unifiedQuery}
              onChange={(e) => onQueryChange(e.target.value)}
              autoComplete="off"
              data-testid={useAiAssistant ? "global-catalog-ai-query" : "global-catalog-cross-search"}
              aria-label="Hledat v katalogu datových řad"
              enterKeyHint="search"
            />
            <VoiceInputButton
              value={unifiedQuery}
              onChange={onQueryChange}
              disabled={deepLoading}
              className="absolute right-2 top-1/2 h-8 w-8 -translate-y-1/2"
              title="Diktovat dotaz"
            />
          </div>

          <div className="flex flex-wrap items-center gap-2 shrink-0">
            <label className="inline-flex items-center gap-2 h-11 px-3 rounded-xl border border-sky-200/90 bg-sky-50/80 text-sm font-medium text-foreground cursor-pointer select-none shadow-sm">
              <input
                type="checkbox"
                className="rounded border-border h-4 w-4 shrink-0 accent-sky-600"
                checked={useAiAssistant}
                onChange={(e) => onAiToggle(e.target.checked)}
                data-testid="catalog-use-ai-assistant"
              />
              <Sparkles className="h-4 w-4 text-sky-600 shrink-0" aria-hidden />
              <span className="hidden sm:inline leading-none">Použít AI hledání</span>
              <span className="sm:hidden leading-none">AI</span>
            </label>

            <div className="hidden md:inline-flex items-center gap-2 h-11 px-2.5 rounded-xl border border-[hsl(var(--border)/0.75)] bg-card shadow-sm min-w-[13.5rem] max-w-[18rem]">
              <span className="inline-flex items-center gap-1 text-[10px] uppercase tracking-wider font-semibold text-muted-foreground shrink-0 leading-none">
                <Database className="h-3.5 w-3.5 shrink-0" aria-hidden />
                {catalogLabel}
              </span>
              <div className="relative flex-1 min-w-[7.5rem]">
                <select
                  className="appearance-none w-full h-9 pl-2 pr-7 border border-[hsl(var(--border)/0.75)] rounded-lg text-sm bg-card truncate"
                  value={classicSearchScope ?? browseCatalogId}
                  onChange={(e) => {
                    const v = e.target.value;
                    onClassicSearchScopeChange?.(v);
                    onBrowseCatalogChange?.(v);
                  }}
                  disabled={false}
                  data-testid="classic-search-catalog-scope"
                  aria-label={catalogLabel}
                >
                  {browseOptions.map((c) => (
                    <option key={c.id} value={c.id}>
                      {getCatalogBrowseDropdownLabel(c)}
                    </option>
                  ))}
                </select>
                <ChevronDown
                  className="absolute right-2 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none shrink-0"
                  aria-hidden
                />
              </div>
              {activeCatalogId && !activeCatalogDef?.appSearch ? (
                <CatalogDatabaseInfo
                  catalogId={activeCatalogId}
                  label={activeCatalogLabel}
                  size="sm"
                />
              ) : null}
            </div>

            <button
              type="button"
              onClick={onFiltersToggle}
              className={`h-11 px-3 rounded-xl text-sm font-medium inline-flex items-center justify-center gap-2 border shadow-sm shrink-0 ${
                filtersOpen
                  ? "border-sky-300/80 bg-sky-100/70 text-sky-950"
                  : "border-[hsl(var(--border)/0.75)] bg-card hover:bg-muted/50 text-foreground"
              }`}
              aria-expanded={filtersOpen}
              data-testid="catalog-search-filters-toggle"
            >
              <Filter className="h-4 w-4 shrink-0" aria-hidden />
              <span className="hidden sm:inline leading-none">Filtry</span>
            </button>

            <button
              type="submit"
              disabled={useAiAssistant ? !aiSuggestGateEnabled || deepLoading : false}
              aria-busy={useAiAssistant && deepLoading ? "true" : undefined}
              className="catalog-unified-header-submit h-11 px-5 rounded-xl text-sm font-semibold inline-flex flex-nowrap items-center justify-center gap-2 bg-sky-600 hover:bg-sky-700 text-white shadow-sm disabled:opacity-60 min-w-[5.5rem] shrink-0"
            >
              {useAiAssistant && deepLoading ? (
                <LoadingSpinner
                  suppressAria
                  size="sm"
                  className="shrink-0 !border-white/35 !border-t-white motion-reduce:!border-white/60"
                />
              ) : (
                <Search className="h-4 w-4 shrink-0" aria-hidden />
              )}
              <span className="leading-none">{useAiAssistant && deepLoading ? "Hledám…" : "Hledat"}</span>
            </button>

            <button
              type="button"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onCatalogToggle?.();
              }}
              className={`xl:hidden h-11 px-3 rounded-xl text-sm font-medium inline-flex items-center justify-center gap-2 border border-[hsl(var(--border)/0.75)] bg-card hover:bg-muted/50 text-foreground shadow-sm relative z-10 pointer-events-auto shrink-0 ${
                catalogViewMode === "detail" || (!showBrowseToggle && browseSidebarOpen) ? "hidden" : ""
              }`}
              aria-label={mobileBrowseOpen ? "Zavřít katalog" : "Otevřít katalog"}
              aria-expanded={mobileBrowseOpen}
              title={mobileBrowseOpen ? "Zavřít katalog" : "Otevřít katalog"}
              data-testid="catalog-mobile-browse-open"
            >
              <Folder className="h-4 w-4 shrink-0" aria-hidden />
              <span className="leading-none">Katalog</span>
            </button>
          </div>
        </div>

        <p className="text-[12px] sm:text-[13px] text-muted-foreground leading-snug" role="status">
          {useAiAssistant
            ? "AI rozpozná význam dotazu, najde související ukazatele a seřadí je podle relevance."
            : "Hybridní hledání podle významu dotazu (intent, synonyma, geo) v názvech a metadatech katalogu."}
        </p>

        {catalogSearchEcbHint ? (
          <div className="catalog-ai-hint rounded-xl border border-sky-200/90 bg-sky-50/75 px-3 py-2.5 text-[12px] text-sky-950 leading-snug">
            <div className="font-semibold">{catalogSearchEcbHint.title}</div>
            <p>{catalogSearchEcbHint.body}</p>
          </div>
        ) : null}

        {useAiAssistant && catalogDeepSearchReady && !canCatalogDeepSearch ? (
          <p className="text-[12px] text-rose-950 bg-rose-50 border border-rose-200 rounded-lg px-2.5 py-2">
            {catalogDeepSearchLockMsg || "AI hloubkové vyhledávání není pro váš účet dostupné."}
          </p>
        ) : null}

        {useAiAssistant && deepLoading ? (
          <button
            type="button"
            className="text-sm px-3 py-1.5 rounded-lg border border-border bg-card hover:bg-muted/50"
            onClick={onCancelDeepSearch}
          >
            Zrušit hledání
          </button>
        ) : null}
      </form>

      {filtersOpen ? filtersPanel : null}

      {activeCatalogId ? (
        <div className="md:hidden flex items-center gap-2 text-[11px] text-muted-foreground pt-1 border-t border-border/50">
          <span className="inline-flex items-center gap-1 font-semibold uppercase tracking-wide shrink-0">
            <Database className="h-3.5 w-3.5 shrink-0" aria-hidden />
            {catalogLabel}
          </span>
          <div className="relative flex-1 min-w-0">
            <select
              className="appearance-none w-full h-8 pl-2 pr-7 border border-[hsl(var(--border)/0.75)] rounded-lg text-sm bg-card truncate"
              value={classicSearchScope ?? browseCatalogId}
              onChange={(e) => {
                const v = e.target.value;
                onClassicSearchScopeChange?.(v);
                onBrowseCatalogChange?.(v);
              }}
              data-testid="classic-search-catalog-scope-mobile"
            >
              {browseOptions.map((c) => (
                <option key={c.id} value={c.id}>
                  {getCatalogBrowseDropdownLabel(c)}
                </option>
              ))}
            </select>
            <ChevronDown
              className="absolute right-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground pointer-events-none shrink-0"
              aria-hidden
            />
          </div>
          {activeCatalogDef?.appSearch ? null : (
            <CatalogDatabaseInfo catalogId={activeCatalogId} label={activeCatalogLabel} size="sm" />
          )}
        </div>
      ) : null}
    </section>
  );
}
