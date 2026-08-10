import React, { useEffect } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import CatalogBrowseView from "@/components/catalog/search/CatalogBrowseView";

const DESKTOP_EXPLORER_MIN_WIDTH = 1280;

/**
 * Responzivní layout explorer + výsledky + detail (browse/detail two-state na <1536px).
 */
export default function ResponsiveCatalogLayout({
  layoutVariant = "A",
  viewportWidth = DESKTOP_EXPLORER_MIN_WIDTH,
  browseDetailWorkflow = false,
  viewMode = "browse",
  hasActiveSearch = false,
  explorerOpen,
  mobileExplorerOpen,
  onMobileExplorerClose,
  explorer,
  results,
  detail,
  explorerWidthClass = "",
  className = "",
}) {
  const isDesktopViewport = viewportWidth >= DESKTOP_EXPLORER_MIN_WIDTH;
  const isMobileLayout = layoutVariant === "mobile" || viewportWidth < 1024;

  // Browse bez aktivního hledání jde přes celou šířku i na širokém desktopu —
  // pravý sloupec (výsledky/detail) by jinak zůstal prázdný a katalog stísněný.
  const showFullWidthBrowse = viewMode === "browse" && !hasActiveSearch;

  const showDetailMode = viewMode === "detail" && Boolean(detail);

  const resolvedLayoutVariant =
    explorerOpen && isDesktopViewport && layoutVariant === "C" && !showFullWidthBrowse
      ? "B"
      : layoutVariant;

  const showDesktopSidebarExplorer =
    explorerOpen &&
    isDesktopViewport &&
    !isMobileLayout &&
    !showFullWidthBrowse &&
    !showDetailMode;

  const showMobileDrawer =
    mobileExplorerOpen &&
    !isDesktopViewport &&
    !showFullWidthBrowse &&
    !showDetailMode;

  const hideResultsPane = showFullWidthBrowse || (browseDetailWorkflow && showDetailMode && !hasActiveSearch);

  const gridClass = showFullWidthBrowse
    ? "catalog-layout-variant-browse-full"
    : showDetailMode && browseDetailWorkflow && !hasActiveSearch
      ? "catalog-layout-variant-detail-only"
      : resolvedLayoutVariant === "A"
        ? "catalog-layout-variant-a"
        : resolvedLayoutVariant === "B"
          ? "catalog-layout-variant-b"
          : resolvedLayoutVariant === "C"
            ? "catalog-layout-variant-c"
            : "catalog-layout-variant-mobile";

  useEffect(() => {
    if (!showMobileDrawer) return undefined;
    const onKeyDown = (event) => {
      if (event.key === "Escape") onMobileExplorerClose?.();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [showMobileDrawer, onMobileExplorerClose]);

  return (
    <div
      className={`catalog-responsive-layout ${gridClass} ${
        showDetailMode ? "catalog-layout-in-detail" : ""
      } ${className}`.trim()}
      data-view-mode={viewMode}
    >
      {showFullWidthBrowse ? (
        <div className="catalog-layout-browse-full min-w-0 col-span-full">
          <CatalogBrowseView>{explorer}</CatalogBrowseView>
        </div>
      ) : null}

      {showDesktopSidebarExplorer ? (
        <div
          className={`catalog-layout-explorer min-w-0 hidden xl:block ${explorerWidthClass}`.trim()}
        >
          {explorer}
        </div>
      ) : null}

      {showMobileDrawer && typeof document !== "undefined"
        ? createPortal(
            // Portál do <body>: drawer musí ležet nad fixní hlavičkou i sidebar
            // navigací — uvnitř stránky ho věznily stacking contexty (z-0/z-1).
            <div className="fixed inset-0 z-[150] xl:hidden" role="dialog" aria-modal="true">
              <button
                type="button"
                className="absolute inset-0 bg-black/40"
                aria-label="Zavřít katalog"
                onClick={onMobileExplorerClose}
              />
              <div className="catalog-mobile-explorer-drawer absolute inset-y-0 inset-x-0 w-full max-w-none bg-card shadow-xl flex flex-col overflow-hidden">
                <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border/60">
                  <span className="text-sm font-semibold">Procházet katalog</span>
                  <button
                    type="button"
                    onClick={onMobileExplorerClose}
                    className="h-8 w-8 inline-flex items-center justify-center rounded-lg border border-border/70"
                    aria-label="Zavřít katalog"
                  >
                    <X className="h-4 w-4" />
                  </button>
                </div>
                <div className="flex-1 min-h-0 overflow-y-auto p-3">{explorer}</div>
              </div>
            </div>,
            document.body
          )
        : null}

      {!hideResultsPane ? (
        <div className="catalog-layout-results min-w-0 flex flex-col gap-4">{results}</div>
      ) : null}

      {detail && !showFullWidthBrowse ? (
        <div
          className={`catalog-layout-detail min-w-0 ${
            browseDetailWorkflow ? "catalog-layout-detail-portal" : ""
          }`}
        >
          {detail}
        </div>
      ) : null}
    </div>
  );
}
