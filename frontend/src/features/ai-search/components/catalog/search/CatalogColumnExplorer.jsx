import React, { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Folder, FileBarChart2, X } from "lucide-react";
import { explorerRowCountLabel, explorerRowIsCategory } from "@/lib/catalogColumnExplorer";
import { LoadingInline, LoadingSpinner } from "@/components/ui/loading";

function explorerColumnWidthClass(layoutMode) {
  if (layoutMode === "browse-full") {
    return "shrink-0 w-[min(88vw,22rem)] sm:w-72 md:w-80 lg:w-[22rem]";
  }
  return "shrink-0 w-[min(82vw,18rem)] sm:w-64 md:w-72";
}

function CatalogExplorerColumnEmpty({ isLoading, label = "Načítám položky…" }) {
  if (isLoading) {
    return (
      <div
        className="px-3 py-10 flex flex-col items-center justify-center gap-2 min-h-[8rem]"
        role="status"
        aria-live="polite"
        aria-busy="true"
        data-testid="catalog-explorer-column-loading"
      >
        <LoadingInline label={label} size="sm" muted className="justify-center flex-wrap" />
      </div>
    );
  }
  return <p className="px-3 py-6 text-[12px] text-muted-foreground text-center">Prázdné</p>;
}

export function CatalogExplorerColumn({
  columnIndex,
  items,
  selectedPath,
  parentPath = "",
  openPaths,
  onSelectRow,
  loadingRowKey,
  renderSeriesRow,
  layoutMode = "default",
}) {
  const loadingChildren = Boolean(
    loadingRowKey && parentPath && String(loadingRowKey) === String(parentPath),
  );

  return (
    <div
      className={`catalog-explorer-column ${explorerColumnWidthClass(layoutMode)} border-r border-border/60 last:border-r-0 flex flex-col min-h-0 h-full max-h-full overflow-hidden bg-card`}
      data-testid={`catalog-explorer-column-${columnIndex}`}
    >
      <div className="catalog-explorer-column-scroll flex-1 min-h-0 overflow-y-auto overscroll-contain">
        <div className="catalog-explorer-column-inner">
        {items.length === 0 ? (
          <CatalogExplorerColumnEmpty isLoading={loadingChildren} />
        ) : (
          <ul className="catalog-explorer-item-list space-y-1">
            {items.map((row) => {
              const isCategory = explorerRowIsCategory(row);
              const isSelected = selectedPath === row.path;
              const isOpen = openPaths?.has(row.path);
              const countLabel = isCategory ? explorerRowCountLabel(row, isOpen) : null;
              const loading = loadingRowKey === row.path;

              if (!isCategory && typeof renderSeriesRow === "function") {
                return (
                  <li key={row.path} className="catalog-explorer-item-wrap">
                    {renderSeriesRow(row, { isSelected, onSelect: () => onSelectRow(row, columnIndex) })}
                  </li>
                );
              }

              return (
                <li key={row.path} className="catalog-explorer-item-wrap">
                  <button
                    type="button"
                    onClick={() => onSelectRow(row, columnIndex)}
                    className={`catalog-explorer-item w-full flex items-start gap-2 text-left px-3 py-2.5 rounded-xl text-[14px] leading-snug transition-colors cursor-pointer box-border ${
                      isSelected
                        ? "catalog-explorer-item--selected bg-sky-50/95 text-sky-950 font-medium"
                        : "catalog-explorer-item--idle text-foreground hover:bg-muted/40"
                    }`}
                    title={row.name}
                    aria-label={row.name}
                    aria-selected={isSelected}
                    aria-expanded={isCategory ? isSelected || isOpen : undefined}
                  >
                    <Folder className="h-4 w-4 shrink-0 text-muted-foreground" />
                    <span className="flex-1 min-w-0 text-[14px] leading-snug break-words line-clamp-2">
                      {row.name}
                    </span>
                    {countLabel != null ? (
                      <span className="text-[11px] tabular-nums text-muted-foreground shrink-0">{countLabel}</span>
                    ) : null}
                    {isCategory ? (
                      <ChevronRight className={`h-4 w-4 shrink-0 ${isSelected ? "text-sky-700" : "text-muted-foreground/70"}`} />
                    ) : null}
                    {loading ? (
                      <LoadingSpinner
                        size="xs"
                        suppressAria
                        className="!border-sky-200 !border-t-sky-600"
                      />
                    ) : null}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        </div>
      </div>
    </div>
  );
}

function CatalogExplorerHorizontalScrollBar({ scrollState, onScrollBy, onScrollTo, onTrackJump }) {
  const { overflow, left, max, thumbRatio, thumbOffsetRatio } = scrollState;
  const trackRef = useRef(null);
  /** { pointerId, startX, startLeft, dragSpan } | null */
  const dragRef = useRef(null);
  const didDragRef = useRef(false);

  const onThumbPointerDown = useCallback(
    (event) => {
      const track = trackRef.current;
      if (!track || event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      const trackWidth = track.getBoundingClientRect().width;
      const thumbWidthRatio = Math.max(thumbRatio, 0.08);
      dragRef.current = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startLeft: left,
        // Dráha palce v px — o ni se mapuje pohyb myši na scrollLeft.
        dragSpan: trackWidth * (1 - thumbWidthRatio),
      };
      didDragRef.current = false;
      event.currentTarget.setPointerCapture(event.pointerId);
    },
    [left, thumbRatio],
  );

  const onThumbPointerMove = useCallback(
    (event) => {
      const drag = dragRef.current;
      if (!drag || drag.pointerId !== event.pointerId || drag.dragSpan <= 0) return;
      didDragRef.current = true;
      const deltaRatio = (event.clientX - drag.startX) / drag.dragSpan;
      onScrollTo(drag.startLeft + deltaRatio * max, false);
    },
    [max, onScrollTo],
  );

  const onThumbPointerEnd = useCallback((event) => {
    if (dragRef.current?.pointerId !== event.pointerId) return;
    dragRef.current = null;
    try {
      event.currentTarget.releasePointerCapture(event.pointerId);
    } catch {
      /* ignore */
    }
  }, []);

  if (!overflow) return null;

  const atStart = left <= 4;
  const atEnd = max <= 0 || left >= max - 4;

  return (
    <div
      className="catalog-column-explorer-hscroll shrink-0 border-t border-border/70 bg-muted/35 px-2 sm:px-3 py-2 flex items-center gap-2"
      data-testid="catalog-explorer-horizontal-scroll"
    >
      <button
        type="button"
        disabled={atStart}
        onClick={() => onScrollBy(-280)}
        className="catalog-explorer-hscroll-btn h-8 w-8 inline-flex items-center justify-center rounded-lg border border-border/70 bg-card text-foreground hover:bg-muted/60 disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
        aria-label="Posunout sloupce doleva"
        title="Posunout doleva"
      >
        <ChevronLeft className="h-4 w-4" aria-hidden />
      </button>

      <div
        ref={trackRef}
        className="catalog-explorer-hscroll-track flex-1 h-3.5 rounded-full bg-muted/90 border border-border/50 relative cursor-pointer touch-none"
        role="scrollbar"
        aria-orientation="horizontal"
        aria-valuemin={0}
        aria-valuemax={max}
        aria-valuenow={left}
        aria-label="Vodorovné posunutí sloupců katalogu"
        onClick={(event) => {
          // Klik, který vznikl tažením palce, nemá skákat na pozici kurzoru.
          if (didDragRef.current) {
            didDragRef.current = false;
            return;
          }
          onTrackJump(event);
        }}
      >
        <div
          className="catalog-explorer-hscroll-thumb absolute top-0 h-full rounded-full bg-sky-600/80 hover:bg-sky-600 shadow-sm min-w-[2.5rem] cursor-grab active:cursor-grabbing"
          style={{
            width: `${Math.max(thumbRatio * 100, 8)}%`,
            left: `${thumbOffsetRatio * (100 - Math.max(thumbRatio * 100, 8))}%`,
          }}
          onPointerDown={onThumbPointerDown}
          onPointerMove={onThumbPointerMove}
          onPointerUp={onThumbPointerEnd}
          onPointerCancel={onThumbPointerEnd}
          onClick={(event) => event.stopPropagation()}
        />
      </div>

      <button
        type="button"
        disabled={atEnd}
        onClick={() => onScrollBy(280)}
        className="catalog-explorer-hscroll-btn h-8 w-8 inline-flex items-center justify-center rounded-lg border border-border/70 bg-card text-foreground hover:bg-muted/60 disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
        aria-label="Posunout sloupce doprava"
        title="Posunout doprava"
      >
        <ChevronRight className="h-4 w-4" aria-hidden />
      </button>

      <span className="hidden md:inline text-[10px] text-muted-foreground shrink-0 max-w-[9rem] leading-snug">
        Posuvník dole — další sloupce vpravo
      </span>
    </div>
  );
}

export default function CatalogColumnExplorer({
  columns = [],
  openPaths,
  loadingRowKey = null,
  onSelectRow,
  title,
  subtitle,
  footer,
  emptyState,
  loadingState,
  renderSeriesRow,
  className = "",
  layoutMode = "default",
  onClose,
}) {
  const scrollRef = useRef(null);
  const [scrollState, setScrollState] = useState({
    overflow: false,
    left: 0,
    max: 0,
    thumbRatio: 1,
    thumbOffsetRatio: 0,
  });

  const syncHorizontalScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const max = Math.max(0, el.scrollWidth - el.clientWidth);
    const overflow = max > 2;
    const left = el.scrollLeft;
    const thumbRatio = el.scrollWidth > 0 ? el.clientWidth / el.scrollWidth : 1;
    const thumbOffsetRatio = max > 0 ? left / max : 0;
    setScrollState({ overflow, left, max, thumbRatio, thumbOffsetRatio });
  }, []);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return undefined;
    syncHorizontalScroll();
    const ro = new ResizeObserver(() => syncHorizontalScroll());
    ro.observe(el);
    if (el.firstElementChild) ro.observe(el.firstElementChild);
    el.addEventListener("scroll", syncHorizontalScroll, { passive: true });
    return () => {
      ro.disconnect();
      el.removeEventListener("scroll", syncHorizontalScroll);
    };
  }, [columns, syncHorizontalScroll]);

  const lastSelectedPath = columns[columns.length - 1]?.selectedPath;

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    requestAnimationFrame(() => {
      el.scrollLeft = el.scrollWidth;
      syncHorizontalScroll();
    });
  }, [columns.length, lastSelectedPath, syncHorizontalScroll]);

  const scrollBy = useCallback(
    (delta) => {
      const el = scrollRef.current;
      if (!el) return;
      el.scrollBy({ left: delta, behavior: "smooth" });
    },
    [],
  );

  const scrollTo = useCallback((leftPx, smooth = true) => {
    const el = scrollRef.current;
    if (!el) return;
    if (smooth) {
      el.scrollTo({ left: leftPx, behavior: "smooth" });
    } else {
      // Při tažení palce posouváme přímo — smooth animace by se kupila a drhla.
      el.scrollLeft = leftPx;
    }
  }, []);

  const jumpOnTrack = useCallback(
    (event) => {
      const el = scrollRef.current;
      const track = event.currentTarget;
      if (!el || !track) return;
      const rect = track.getBoundingClientRect();
      const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
      const max = Math.max(0, el.scrollWidth - el.clientWidth);
      el.scrollTo({ left: ratio * max, behavior: "smooth" });
    },
    [],
  );

  if (loadingState) return loadingState;
  if (emptyState && (!columns.length || columns.every((c) => !c.items.length))) {
    return emptyState;
  }

  // Výška je fixní — sloupce scrollují svisle uvnitř, ne roztahují celý panel.
  const shellHeightClass =
    layoutMode === "browse-full"
      ? "h-[min(85vh,calc(100dvh-10rem))] min-h-[min(60vh,28rem)]"
      : "h-[min(85vh,calc(100dvh-12rem))] min-h-[14rem]";

  return (
    <div
      className={`catalog-column-explorer catalog-column-explorer--${layoutMode} flex flex-col min-h-0 w-full overflow-hidden rounded-2xl border border-border/80 bg-card shadow-sm ${shellHeightClass} ${className}`}
      data-testid="catalog-column-explorer"
      data-layout-mode={layoutMode}
    >
      {(title || subtitle || onClose) && (
        <div className="shrink-0 px-4 py-3 border-b border-border/60 bg-muted/20 rounded-t-2xl">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0 flex-1">
              {title ? <div className="text-sm font-semibold text-foreground">{title}</div> : null}
              {subtitle ? (
                <p className="text-[12px] text-muted-foreground mt-0.5 leading-snug">{subtitle}</p>
              ) : null}
            </div>
            {onClose ? (
              <button
                type="button"
                className="h-7 w-7 inline-flex shrink-0 items-center justify-center rounded-md border border-border/70 bg-card text-muted-foreground hover:bg-muted/50 hover:text-foreground"
                onClick={onClose}
                aria-label="Zavřít procházení katalogu"
              >
                <X className="h-3.5 w-3.5" aria-hidden />
              </button>
            ) : null}
          </div>
        </div>
      )}

      <div className="catalog-column-explorer-body flex flex-col flex-1 min-h-0 min-w-0">
        <div
          ref={scrollRef}
          className={`catalog-column-explorer-scroll relative flex-1 min-h-0 min-w-0 h-0 overflow-x-auto overflow-y-hidden overscroll-x-contain ${
            scrollState.overflow ? "catalog-column-explorer-scroll--with-hbar" : ""
          }`}
          aria-label="Sloupce katalogu — posuňte vodorovně pro zobrazení dalších složek"
        >
          {scrollState.overflow && scrollState.left > 8 ? (
            <div
              className="catalog-column-explorer-fade-left pointer-events-none absolute inset-y-0 left-0 z-[2] w-6 bg-gradient-to-r from-card to-transparent"
              aria-hidden
            />
          ) : null}
          {scrollState.overflow && scrollState.max > 0 && scrollState.left < scrollState.max - 8 ? (
            <div
              className="catalog-column-explorer-fade-right pointer-events-none absolute inset-y-0 right-0 z-[2] w-6 bg-gradient-to-l from-card to-transparent"
              aria-hidden
            />
          ) : null}
          <div className="catalog-column-explorer-scroll-inner flex h-full min-h-0 min-w-max shrink-0 items-stretch">
            {columns.map((col) => (
              <CatalogExplorerColumn
                key={`col-${col.index}-${col.parentPath || "root"}`}
                columnIndex={col.index}
                items={col.items}
                selectedPath={col.selectedPath}
                parentPath={col.parentPath || ""}
                openPaths={openPaths}
                onSelectRow={onSelectRow}
                loadingRowKey={loadingRowKey}
                renderSeriesRow={renderSeriesRow}
                layoutMode={layoutMode}
              />
            ))}
          </div>
        </div>

        <CatalogExplorerHorizontalScrollBar
          scrollState={scrollState}
          onScrollBy={scrollBy}
          onScrollTo={scrollTo}
          onTrackJump={jumpOnTrack}
        />
      </div>

      {footer ? (
        <div className="shrink-0 border-t border-border/60 px-4 py-3 bg-muted/15 text-[12px] text-muted-foreground rounded-b-2xl">
          {footer}
        </div>
      ) : null}
    </div>
  );
}

export function CatalogExplorerSeriesButton({ row, isSelected, onSelect }) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`catalog-explorer-item catalog-explorer-item--series w-full flex items-start gap-2 text-left px-3 py-2.5 rounded-xl transition-colors cursor-pointer box-border ${
        isSelected
          ? "catalog-explorer-item--selected bg-emerald-50/95 text-emerald-950 font-medium"
          : "catalog-explorer-item--idle text-foreground hover:bg-muted/40"
      }`}
    >
      <FileBarChart2 className="h-4 w-4 shrink-0 mt-0.5 text-muted-foreground" />
      <span className="min-w-0 flex-1">
        <span className="block text-[13px] font-medium line-clamp-2">{row.name}</span>
        {row.set_id ? (
          <span className="block text-[10px] font-mono text-muted-foreground truncate mt-0.5">{row.set_id}</span>
        ) : null}
      </span>
    </button>
  );
}
