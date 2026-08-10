import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Loader2, SlidersHorizontal, X } from "lucide-react";
import CatalogLiveChartPreview from "@/components/catalog/search/CatalogLiveChartPreview";
import { formatApiErrorFromAxios } from "@/lib/api";
import { extractGeoValuesFromDimensionFilters } from "@/lib/bisPreviewFilters";
import {
  fetchCatalogLivePreview,
  normalizeSelectedIndicators,
} from "@/lib/catalogLivePreview";
import { buildExploreInteractiveCatalogContext } from "@/lib/exploreInteractiveCatalog";

export default function ExploreInteractiveSeriesDetail({ open, series, onClose }) {
  const context = useMemo(() => buildExploreInteractiveCatalogContext(series), [series]);
  const [preview, setPreview] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selectedIndicators, setSelectedIndicators] = useState([]);
  const previewRef = useRef(null);

  useEffect(() => {
    previewRef.current = preview;
  }, [preview]);

  const loadPreview = useCallback(async (overrides = {}) => {
    if (!context) return;
    setLoading(true);
    setError("");
    try {
      const next = await fetchCatalogLivePreview({
        def: context.def,
        row: context.row,
        previewData: previewRef.current,
        ...overrides,
      });
      setPreview(next);
      const nextIndicators = normalizeSelectedIndicators(next?.selected_indicators);
      const single = String(next?.selected_indicator || "").trim();
      setSelectedIndicators(nextIndicators.length ? nextIndicators : single ? [single] : []);
    } catch (err) {
      setError(formatApiErrorFromAxios(err) || "Interaktivní data řady se nepodařilo načíst.");
    } finally {
      setLoading(false);
    }
  }, [context]);

  useEffect(() => {
    if (!open) return;
    setPreview(null);
    previewRef.current = null;
    setSelectedIndicators([]);
    if (context) void loadPreview();
  }, [open, context, loadPreview]);

  useEffect(() => {
    if (!open || typeof document === "undefined") return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const handleKeyDown = (event) => {
      if (event.key === "Escape") onClose?.();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [open, onClose]);

  if (!open || typeof document === "undefined") return null;

  const title = String(series?.name || context?.row?.name || "Datová řada").trim();
  const activeIndicators = selectedIndicators.length
    ? selectedIndicators
    : normalizeSelectedIndicators(preview?.selected_indicators);
  const appliedGeo = (filters) => extractGeoValuesFromDimensionFilters(filters);

  return createPortal(
    <div
      className="fixed inset-0 z-[430] flex items-center justify-center bg-slate-950/60 p-2 sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label={`Interaktivní graf: ${title}`}
      onClick={onClose}
    >
      <section
        className="flex h-[min(96vh,980px)] w-[min(98vw,1680px)] flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="flex shrink-0 items-start justify-between gap-3 border-b border-border/70 bg-muted/20 px-4 py-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-wider text-sky-700">
              <SlidersHorizontal className="h-3.5 w-3.5" />
              Interaktivní graf a data
            </div>
            <h2 className="mt-1 truncate text-sm font-semibold text-foreground" title={title}>{title}</h2>
            {context ? (
              <p className="mt-0.5 truncate text-[10px] text-muted-foreground">
                {context.def.label} · {context.row.set_id}
              </p>
            ) : null}
          </div>
          <button
            type="button"
            className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-border bg-card hover:bg-muted/60"
            aria-label="Zavřít interaktivní graf"
            onClick={onClose}
          >
            <X className="h-4 w-4" />
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto p-3 sm:p-4">
          {!context ? (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950">
              Tato řada nemá katalogový identifikátor potřebný pro interaktivní detail.
            </div>
          ) : error && !preview ? (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-950">
              <p>{error}</p>
              <button type="button" className="mt-2 underline" onClick={() => void loadPreview()}>
                Zkusit znovu
              </button>
            </div>
          ) : loading && !preview ? (
            <div className="flex min-h-[18rem] items-center justify-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" /> Načítám interaktivní data…
            </div>
          ) : (
            <CatalogLiveChartPreview
              widgetId={`manager-interactive-${context.def.id}-${context.row.set_id}`}
              title={title}
              sourceType={context.def.sourceType}
              catalogDef={context.def}
              catalogRow={context.row}
              preferAradView
              preview={preview}
              previewError={error}
              previewLoading={loading}
              catalogChartSize="fullscreen"
              controlsInOptionsPanel
              actions={{ canExport: true }}
              sourcePreviewProps={{
                preview,
                loading,
                compact: true,
                liveCatalogPreview: true,
                catalogChartSize: "fullscreen",
                catalogRowUnit: context.row.unit || undefined,
                onIndicatorChange: (indicatorId) => {
                  const next = normalizeSelectedIndicators([indicatorId]);
                  setSelectedIndicators(next);
                  void loadPreview({
                    indicatorId: next[0],
                    indicatorIds: next,
                    geoValues: appliedGeo(preview?.metadata?.filters_applied || preview?.requested_filters),
                  });
                },
                onIndicatorSelectionChange: (indicatorIds) => {
                  const next = normalizeSelectedIndicators(indicatorIds);
                  if (!next.length) return;
                  setSelectedIndicators(next);
                  void loadPreview({
                    indicatorId: next[0],
                    indicatorIds: next,
                    geoValues: appliedGeo(preview?.metadata?.filters_applied || preview?.requested_filters),
                  });
                },
                onGeoSelectionChange: (geoValues) => {
                  const many = activeIndicators.length
                    ? activeIndicators
                    : normalizeSelectedIndicators(preview?.selected_indicators);
                  void loadPreview({
                    indicatorId: context.def.sourceType === "imf" ? undefined : many[0],
                    indicatorIds: context.def.sourceType === "imf" ? [] : many,
                    geoValues,
                  });
                },
                onDimensionFiltersApply: context.def.sourceType === "imf"
                  ? undefined
                  : (dimensionFilters) => {
                      const many = activeIndicators.length
                        ? activeIndicators
                        : normalizeSelectedIndicators(preview?.selected_indicators);
                      void loadPreview({
                        indicatorId: many[0],
                        indicatorIds: many,
                        geoValues: appliedGeo(dimensionFilters),
                        dimensionFilters,
                      });
                    },
              }}
            />
          )}
        </div>
      </section>
    </div>,
    document.body,
  );
}

