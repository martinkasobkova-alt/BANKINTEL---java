import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Filter } from "lucide-react";
import CatalogDatabaseInfo from "@/components/catalog/CatalogDatabaseInfo";
import {
  CATALOG_HEADER_FILTERS_EVENT,
  catalogHeaderFiltersActiveCount,
  HEADER_FILTER_CATALOG_OPTIONS,
  loadCatalogHeaderFilters,
  saveCatalogHeaderFilters,
} from "@/lib/catalogHeaderFilters";

export default function CatalogHeaderFiltersMenu({ className = "" }) {
  const { t } = useTranslation();
  const rootRef = useRef(null);
  const [open, setOpen] = useState(false);
  const [prefs, setPrefs] = useState(() => loadCatalogHeaderFilters());
  const activeCount = catalogHeaderFiltersActiveCount(prefs);
  const selected = useMemo(() => new Set(prefs.selectedIds || []), [prefs.selectedIds]);

  const toggle = useCallback((id) => {
    setPrefs((prev) => {
      const n = new Set(prev.selectedIds || []);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return saveCatalogHeaderFilters({ ...prev, selectedIds: [...n] });
    });
  }, []);

  const toggleAllCatalogs = useCallback(() => {
    setPrefs((prev) => {
      const current = new Set(prev.selectedIds || []);
      const everyOn = HEADER_FILTER_CATALOG_OPTIONS.every((c) => current.has(c.id));
      return saveCatalogHeaderFilters({
        ...prev,
        selectedIds: everyOn ? [] : HEADER_FILTER_CATALOG_OPTIONS.map((c) => c.id),
      });
    });
  }, []);

  const updatePref = useCallback((patch) => {
    setPrefs((prev) => saveCatalogHeaderFilters({ ...prev, ...patch }));
  }, []);

  const allCatalogsSelected =
    HEADER_FILTER_CATALOG_OPTIONS.length > 0 &&
    HEADER_FILTER_CATALOG_OPTIONS.every((c) => selected.has(c.id));

  useEffect(() => {
    const onHeaderFilters = (event) => {
      if (event?.detail) setPrefs(event.detail);
    };
    window.addEventListener(CATALOG_HEADER_FILTERS_EVENT, onHeaderFilters);
    return () => window.removeEventListener(CATALOG_HEADER_FILTERS_EVENT, onHeaderFilters);
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event) => {
      if (event.key === "Escape") setOpen(false);
    };
    const onPointerDown = (event) => {
      if (rootRef.current?.contains(event.target)) return;
      setOpen(false);
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  return (
    <div
      ref={rootRef}
      className={`relative shrink-0 ${className}`.trim()}
    >
      <button
        type="button"
        onClick={(event) => {
          event.preventDefault();
          event.stopPropagation();
          setOpen((v) => !v);
        }}
        className={`inline-flex h-9 shrink-0 items-center justify-center gap-1 rounded-full border px-2 text-sm font-medium shadow-sm transition max-md:px-2 sm:px-3 ${
          open || activeCount > 0
            ? "border-sky-300/80 bg-sky-100/80 text-sky-950"
            : "border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.88)] text-foreground hover:bg-muted/50"
        }`}
        aria-expanded={open}
        aria-haspopup="true"
        data-testid="app-shell-catalog-filters-toggle"
      >
        <Filter className="h-4 w-4 shrink-0" aria-hidden />
        <span className="hidden lg:inline leading-none">{t("shell.catalogFilters")}</span>
        {activeCount > 0 ? (
          <span className="inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-sky-600 px-1 text-[10px] font-bold leading-none text-white">
            {activeCount}
          </span>
        ) : null}
      </button>

      <div
        className={`max-md:fixed max-md:inset-x-2 max-md:top-[calc(var(--app-shell-header-height,3.75rem)+0.35rem)] max-md:z-[160] md:absolute md:right-0 md:left-auto md:top-[calc(100%+0.35rem)] z-[160] origin-top transition-[opacity,transform] duration-200 ease-out ${
          open
            ? "pointer-events-auto translate-y-0 opacity-100"
            : "pointer-events-none -translate-y-1 opacity-0"
        }`}
        aria-hidden={!open}
        data-testid="app-shell-catalog-filters-panel"
      >
        <div
          className="w-full max-w-full md:w-max md:max-w-[min(92vw,56rem)] rounded-2xl border border-[hsl(var(--border)/0.85)] bg-[hsl(var(--card))] px-3 py-2.5 shadow-xl"
          role="group"
          aria-label={t("pages.catalogHub.databases")}
        >
          <div className="mb-2 flex items-center justify-between gap-3">
            <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {t("pages.catalogHub.databases")}
            </span>
            <button
              type="button"
              data-testid="catalog-select-all"
              onClick={toggleAllCatalogs}
              className="shrink-0 rounded-lg border border-[hsl(var(--border)/0.85)] bg-card px-2 py-0.5 text-xs font-medium text-foreground shadow-sm hover:bg-muted/50"
              title={
                allCatalogsSelected
                  ? t("pages.catalogHub.deselectAllCatalogs")
                  : t("pages.catalogHub.selectAllCatalogs")
              }
            >
              {allCatalogsSelected ? t("common.clearAll") : t("common.all")}
            </button>
          </div>

          <div className="flex max-w-full flex-nowrap items-center gap-x-3 gap-y-1 overflow-x-auto pb-0.5">
            {HEADER_FILTER_CATALOG_OPTIONS.map((c) => (
              <label
                key={c.id}
                className="inline-flex shrink-0 cursor-pointer select-none items-center gap-1.5 whitespace-nowrap text-sm text-foreground"
              >
                <input
                  type="checkbox"
                  checked={selected.has(c.id)}
                  onChange={() => toggle(c.id)}
                  className="mt-0 shrink-0 rounded border-border"
                />
                <span>{c.label}</span>
                {c.appSearch ? null : (
                  <CatalogDatabaseInfo catalogId={c.id} label={c.label} size="sm" />
                )}
              </label>
            ))}
          </div>

          <div className="mt-2.5 border-t border-border/60 pt-2.5 space-y-1.5">
            <div className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              Pokročilé volby hledání
            </div>
            <label className="flex items-center gap-2 text-sm text-foreground cursor-pointer select-none">
              <input
                type="checkbox"
                className="rounded border-border shrink-0"
                checked={prefs.browseLocalBranchOnly}
                onChange={(e) => {
                  const on = e.target.checked;
                  updatePref({
                    browseLocalBranchOnly: on,
                    ...(on
                      ? {
                          browseSearchAcrossSelected: false,
                          browseSearchCategoriesOnly: false,
                        }
                      : {}),
                  });
                }}
                data-testid="catalog-browse-local-branch-only"
              />
              Hledat jen v otevřené větvi
            </label>
            {!prefs.browseLocalBranchOnly ? (
              <label className="flex items-center gap-2 text-sm text-foreground cursor-pointer select-none">
                <input
                  type="checkbox"
                  className="rounded border-border shrink-0"
                  checked={prefs.browseSearchAcrossSelected}
                  onChange={(e) => updatePref({ browseSearchAcrossSelected: e.target.checked })}
                  data-testid="catalog-browse-search-across-selected"
                />
                Hledat napříč všemi aktivními katalogy
              </label>
            ) : null}
            {!prefs.browseLocalBranchOnly ? (
              <label className="flex items-center gap-2 text-sm text-foreground cursor-pointer select-none">
                <input
                  type="checkbox"
                  className="rounded border-border shrink-0"
                  checked={prefs.browseSearchCategoriesOnly}
                  onChange={(e) => updatePref({ browseSearchCategoriesOnly: e.target.checked })}
                  data-testid="catalog-browse-search-categories-only"
                />
                Hledat jen kategorie
              </label>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}
