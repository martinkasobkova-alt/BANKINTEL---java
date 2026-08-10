import React from "react";
import { X, Eye, Download, Plus } from "lucide-react";
import { formatPreviewMessage } from "@/lib/previewNormalizer";

/**
 * Detail panel vybrané řady (spec §5) — obal kolem existujícího náhledu.
 */
export default function SeriesDetailPreview({
  open,
  onClose,
  title,
  code,
  catalogLabel,
  previewContent,
  relatedItems = [],
  onPreview,
  onDownload,
  onSave,
  placement = "bottom",
  autoPreview = false,
  previewLoading = false,
  previewError = "",
}) {
  if (!open) return null;
  const previewErrorText = formatPreviewMessage(previewError);

  const panelClass =
    placement === "drawer"
      ? "fixed inset-y-0 right-0 z-[85] w-full max-w-lg shadow-2xl border-l border-border animate-in slide-in-from-right"
      : "rounded-2xl border border-border bg-card shadow-lg w-full";

  return (
    <>
      {placement === "drawer" ? (
        <button
          type="button"
          className="fixed inset-0 z-[84] bg-black/30"
          aria-label="Zavřít detail"
          onClick={onClose}
        />
      ) : null}
      <section
        className={`catalog-series-detail ${panelClass} flex flex-col max-h-[min(70vh,36rem)] overflow-hidden`}
        data-testid="catalog-series-detail"
        aria-label="Detail datové řady"
      >
        <div className="shrink-0 flex items-center gap-2 px-3 sm:px-4 py-2 border-b border-border/60 bg-muted/15">
          <div className="min-w-0 flex-1 flex items-baseline gap-2">
            {catalogLabel ? (
              <span className="shrink-0 text-[9px] uppercase tracking-wider font-semibold text-muted-foreground">
                {catalogLabel}
              </span>
            ) : null}
            <h3 className="min-w-0 flex-1 text-sm font-semibold text-foreground leading-snug truncate" title={title}>
              {title}
            </h3>
            {code ? (
              <span
                className="shrink-0 max-w-[40%] text-[10px] font-mono text-muted-foreground truncate hidden sm:inline"
                title={code}
              >
                {code}
              </span>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="h-8 w-8 shrink-0 inline-flex items-center justify-center rounded-lg border border-border/70 hover:bg-muted/50"
            aria-label="Zavřít"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>

        <div className="flex-1 min-h-0 overflow-y-auto px-4 sm:px-5 py-4">
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
            </div>
          ) : null}
          {previewContent ? previewContent : null}
          {!autoPreview && !previewContent && !previewLoading && !previewErrorText ? (
            <p className="text-sm text-muted-foreground">Klikněte na „Náhled řady“ pro zobrazení dat.</p>
          ) : null}
        </div>

        {relatedItems.length > 0 ? (
          <div className="shrink-0 border-t border-border/60 px-4 py-3 bg-muted/10">
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

        <div className="shrink-0 flex flex-wrap gap-2 px-4 sm:px-5 py-3 border-t border-border/60 bg-card">
          {onPreview && (!autoPreview || previewErrorText) ? (
            <button
              type="button"
              onClick={onPreview}
              className="inline-flex items-center gap-1.5 h-9 px-3 text-sm rounded-lg bg-sky-600 hover:bg-sky-700 text-white"
            >
              <Eye className="h-4 w-4" />
              {autoPreview && previewErrorText ? "Zkusit znovu" : "Náhled řady"}
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
}
