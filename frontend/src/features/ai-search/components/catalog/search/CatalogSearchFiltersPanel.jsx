import React from "react";
import CatalogDatabaseInfo from "@/components/catalog/CatalogDatabaseInfo";
import { WB_DEFAULT_COUNTRY } from "@/lib/catalogDefinitions";

export default function CatalogSearchFiltersPanel({
  t,
  CATALOGS,
  selected,
  toggle,
  toggleAllCatalogs,
  allCatalogsSelected,
  browseLocalBranchOnly,
  setBrowseLocalBranchOnly,
  browseSearchAcrossSelected,
  setBrowseSearchAcrossSelected,
  browseSearchCategoriesOnly,
  setBrowseSearchCategoriesOnly,
  setCatalogGlobResults,
  setCatalogGlobError,
  setCatalogGlobLoading,
  setCatalogMultiResults,
  setCatalogMultiError,
  setCatalogMultiLoading,
  setBrowseExcludedCatalogIds,
  wbCountry,
  setWbCountry,
  wbCountries,
}) {
  return (
    <div className="border-t border-border/60 pt-4 space-y-4" data-testid="catalog-search-filters-panel">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h3 id="catalog-db-selection-title" className="text-sm font-semibold text-foreground tracking-tight">
          {t("pages.catalogHub.databases")}
        </h3>
        <button
          type="button"
          data-testid="catalog-select-all"
          onClick={toggleAllCatalogs}
          className="shrink-0 min-h-8 px-2.5 py-1 text-xs sm:text-sm font-medium rounded-lg border border-[hsl(var(--border)/0.85)] bg-card text-foreground shadow-sm hover:bg-muted/50"
          title={allCatalogsSelected ? t("pages.catalogHub.deselectAllCatalogs") : t("pages.catalogHub.selectAllCatalogs")}
        >
          {allCatalogsSelected ? t("common.clearAll") : t("common.all")}
        </button>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-x-3 gap-y-2">
        {CATALOGS.map((c) => (
          <label
            key={c.id}
            className="inline-flex items-center gap-1.5 text-xs sm:text-sm cursor-pointer select-none text-foreground min-w-0"
          >
            <input
              type="checkbox"
              checked={selected.has(c.id)}
              onChange={() => toggle(c.id)}
              className="rounded border-border shrink-0 mt-0.5"
            />
            <span className="truncate">{c.label}</span>
            {c.appSearch ? null : <CatalogDatabaseInfo catalogId={c.id} label={c.label} />}
          </label>
        ))}
      </div>

      <div className="rounded-xl border border-border/70 bg-muted/20 px-3 py-3 space-y-2">
        <div className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
          Pokročilé volby hledání
        </div>
        <label className="flex items-center gap-2 text-sm text-foreground cursor-pointer select-none">
          <input
            type="checkbox"
            className="rounded border-border shrink-0"
            checked={browseLocalBranchOnly}
            onChange={(e) => {
              const on = e.target.checked;
              setBrowseLocalBranchOnly(on);
              if (on) {
                setCatalogGlobResults(null);
                setCatalogGlobError("");
                setCatalogGlobLoading(false);
              }
            }}
            data-testid="catalog-browse-local-branch-only"
          />
          Hledat jen v otevřené větvi
        </label>
        {!browseLocalBranchOnly ? (
          <label className="flex items-center gap-2 text-sm text-foreground cursor-pointer select-none">
            <input
              type="checkbox"
              className="rounded border-border shrink-0"
              checked={browseSearchAcrossSelected}
              onChange={(e) => {
                const on = e.target.checked;
                setBrowseSearchAcrossSelected(on);
                setCatalogGlobResults(null);
                setCatalogGlobError("");
                setCatalogGlobLoading(false);
                setCatalogMultiResults(null);
                setCatalogMultiError("");
                setCatalogMultiLoading(false);
                setBrowseExcludedCatalogIds(new Set());
              }}
              data-testid="catalog-browse-search-across-selected"
            />
            Hledat napříč všemi aktivními katalogy
          </label>
        ) : null}
        {!browseLocalBranchOnly ? (
          <label className="flex items-center gap-2 text-sm text-foreground cursor-pointer select-none">
            <input
              type="checkbox"
              className="rounded border-border shrink-0"
              checked={browseSearchCategoriesOnly}
              onChange={(e) => setBrowseSearchCategoriesOnly(e.target.checked)}
              data-testid="catalog-browse-search-categories-only"
            />
            Hledat jen kategorie
          </label>
        ) : null}
      </div>

      {selected.has("worldbank") ? (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="text-muted-foreground shrink-0">World Bank · země</span>
          <select
            className="h-9 px-3 border border-[hsl(var(--border)/0.75)] rounded-xl text-sm bg-card max-w-xs shadow-sm"
            value={wbCountry}
            onChange={(e) => setWbCountry(e.target.value)}
          >
            {wbCountries.length === 0 ? (
              <option value={WB_DEFAULT_COUNTRY}>{WB_DEFAULT_COUNTRY}</option>
            ) : (
              wbCountries.map((country) => (
                <option key={country.code} value={country.code}>
                  {country.label} ({country.code})
                </option>
              ))
            )}
          </select>
        </div>
      ) : null}
    </div>
  );
}
