import React, { useEffect } from "react";
import { createPortal } from "react-dom";
import {
  ArrowLeft,
  ChevronRight,
  Download,
  Maximize2,
  Minimize2,
  Plus,
  X,
} from "lucide-react";
import { formatPreviewMessage } from "@/lib/previewNormalizer";

/**
 * Fullscreen detail řady — podobný režimu grafu na dashboardu.
 */
export default function CatalogSeriesFullscreenDetail({
  open,
  onBack,
  onClose,
  breadcrumbItems = [],
  title,
  code,
  catalogLabel,
  frequencyEditor = null,
  previewContent,
  relatedItems = [],
  previewLoading = false,
  previewError = "",
  onDownload,
  onSave,
  onRetryPreview,
  presentation = "fullscreen",
  chartExpanded = false,
  onChartExpandToggle,
}) {
  const [mounted, setMounted] = React.useState(false);
  const previewErrorText = formatPreviewMessage(previewError);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      if (event.key === "Escape") {
        if (onBack) {
          onBack();
        } else {
          onClose?.();
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onBack, onClose]);

  useEffect(() => {
    if (!open || presentation !== "fullscreen") return undefined;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open, presentation]);

  if (!open || !mounted) return null;

  const isOverlay = presentation === "overlay";
  const shellClass = isOverlay
    ? "catalog-series-detail-overlay fixed inset-x-4 sm:inset-x-8 lg:inset-x-12 top-[5.5rem] bottom-4 z-[86] rounded-2xl border border-border shadow-2xl"
    : "catalog-series-detail-fullscreen fixed inset-0 z-[86]";

  const content = (
    <>
      {!isOverlay ? (
        <div className="catalog-series-detail-backdrop fixed inset-0 z-[85] bg-background/95 backdrop-blur-[2px]" />
      ) : (
        <button
          type="button"
          className="fixed inset-0 z-[85] bg-black/35"
          aria-label="Zavřít detail"
          onClick={onBack || onClose}
        />
      )}

      <section
        className={`${shellClass} flex flex-col overflow-hidden bg-card`}
        data-testid="catalog-series-fullscreen-detail"
        aria-label="Detail datové řady"
      >
        <div className="shrink-0 border-b border-border/60 bg-muted/15">
          <div className="flex items-center gap-2 px-3 sm:px-4 py-2">
            <button
              type="button"
              onClick={onBack}
              className="inline-flex items-center gap-1 h-8 px-2.5 text-xs rounded-lg border border-border/80 bg-card hover:bg-muted/50 shrink-0"
              data-testid="catalog-detail-back"
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">Zpět</span>
            </button>

            {breadcrumbItems.length > 0 ? (
              <nav
                className="flex-1 min-w-0 flex items-center gap-1 text-[11px] text-muted-foreground overflow-hidden"
                aria-label="Cesta v katalogu"
              >
                {breadcrumbItems.map((item, index) => (
                  <React.Fragment key={`${item.label}-${index}`}>
                    {index > 0 ? <ChevronRight className="h-3 w-3 shrink-0 opacity-50" /> : null}
                    <span
                      className={`truncate ${
                        item.kind === "series" ? "text-foreground font-medium" : ""
                      }`}
                      title={item.label}
                    >
                      {item.label}
                    </span>
                  </React.Fragment>
                ))}
              </nav>
            ) : (
              <div className="flex-1 min-w-0" />
            )}

            <button
              type="button"
              onClick={onClose || onBack}
              className="h-8 w-8 shrink-0 inline-flex items-center justify-center rounded-lg border border-border/70 hover:bg-muted/50"
              aria-label="Zavřít detail"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>

          <div className="px-3 sm:px-4 pb-2 flex items-baseline gap-2 min-w-0">
            {catalogLabel ? (
              <span className="shrink-0 text-[9px] uppercase tracking-wider font-semibold text-muted-foreground">
                {catalogLabel}
              </span>
            ) : null}
            <h2 className="min-w-0 flex-1 text-sm font-semibold text-foreground leading-snug truncate" title={title}>
              {title}
            </h2>
            {code ? (
              <span
                className="shrink-0 max-w-[40%] text-[10px] font-mono text-muted-foreground truncate hidden sm:inline"
                title={code}
              >
                {code}
              </span>
            ) : null}
          </div>

          {frequencyEditor?.kind === "imf" ? (
            <div
              className="px-3 sm:px-4 pb-2 flex flex-wrap items-center gap-1.5"
              data-testid="catalog-detail-imf-freq"
              onClick={(event) => event.stopPropagation()}
            >
              <span className="text-[10px] text-muted-foreground shrink-0">Frekvence:</span>
              {(frequencyEditor.options || []).map((opt) => (
                <button
                  key={opt.frekvence}
                  type="button"
                  disabled={frequencyEditor.loading}
                  onClick={() => frequencyEditor.onChange?.(opt.frekvence)}
                  className={`px-2 h-6 text-[10px] rounded-md border transition-colors ${
                    frequencyEditor.active === opt.frekvence
                      ? "border-teal-500 bg-teal-50 text-teal-950 font-medium"
                      : "border-border bg-card hover:bg-muted/60 text-foreground"
                  }`}
                  data-testid={`catalog-detail-imf-freq-${opt.frekvence}`}
                >
                  {opt.frekvence_label}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        <div
          className="flex-1 min-h-0 overflow-y-auto px-3 sm:px-4 py-2 md:py-3"
        >
          {previewLoading ? (
            <p className="text-sm text-muted-foreground mb-3" role="status">
              Načítám náhled dat…
            </p>
          ) : null}
          {previewErrorText ? (
            <div
              className="text-sm rounded-xl border border-rose-200 bg-rose-50/95 text-rose-950 px-3 py-2 mb-3 whitespace-pre-wrap"
              role="alert"
            >
              {previewErrorText}
              {onRetryPreview ? (
                <button
                  type="button"
                  className="block mt-2 text-sm underline"
                  onClick={onRetryPreview}
                >
                  Zkusit znovu
                </button>
              ) : null}
            </div>
          ) : null}
          {previewContent ? (
            <div className="catalog-detail-preview-root min-w-0">{previewContent}</div>
          ) : null}
        </div>

        {relatedItems.length > 0 ? (
          <div className="shrink-0 border-t border-border/60 px-4 sm:px-6 py-3 bg-muted/10">
            <div className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground mb-2">
              Související série
            </div>
            <ul className="space-y-1 text-[12px] text-foreground/90">
              {relatedItems.slice(0, 5).map((item) => (
                <li key={item.id || item.title} className="truncate">
                  {item.title}
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        <div className="shrink-0 flex flex-wrap gap-2 px-4 sm:px-6 py-3 border-t border-border/60 bg-card">
          {onChartExpandToggle ? (
            <button
              type="button"
              onClick={onChartExpandToggle}
              className="inline-flex items-center gap-1.5 h-9 px-3 text-sm rounded-lg border border-border/80 bg-card hover:bg-muted/50"
            >
              {chartExpanded ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
              {chartExpanded ? "Zmenšit plochu" : "Větší plocha"}
            </button>
          ) : null}
          {onDownload ? (
            <button
              type="button"
              onClick={onDownload}
              className="inline-flex items-center gap-1.5 h-9 px-3 text-sm rounded-lg border border-border/80 bg-card hover:bg-muted/50"
            >
              <Download className="h-4 w-4" />
              Stáhnout
            </button>
          ) : null}
          {onSave ? (
            <button
              type="button"
              onClick={onSave}
              className="inline-flex items-center gap-1.5 h-9 px-3 text-sm rounded-lg border border-border/80 bg-card hover:bg-muted/50"
            >
              <Plus className="h-4 w-4" />
              Uložit
            </button>
          ) : null}
        </div>
      </section>
    </>
  );

  return createPortal(content, document.body);
}
