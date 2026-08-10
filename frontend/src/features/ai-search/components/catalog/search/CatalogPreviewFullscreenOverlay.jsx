import React, { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";

/**
 * Fullscreen náhled dat z výsledků hledání — stejný režim jako graf na dashboardu.
 */
export default function CatalogPreviewFullscreenOverlay({
  open,
  onClose,
  title,
  catalogLabel,
  code,
  previewLoading = false,
  children,
}) {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (event) => {
      if (event.key === "Escape") onClose?.();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = prev;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [open, onClose]);

  if (!open || !mounted || typeof document === "undefined") return null;

  return createPortal(
    <>
      <button
        type="button"
        className="fixed inset-0 z-[240] cursor-default border-0 bg-background p-0"
        aria-label="Zavřít náhled dat"
        onClick={onClose}
      />
      <div
        className="fixed inset-0 z-[250] flex items-stretch justify-stretch p-0 pointer-events-none bg-background"
        role="dialog"
        aria-modal="true"
        aria-label="Náhled dat řady"
        data-testid="catalog-preview-fullscreen"
      >
        <div className="pointer-events-auto flex h-[100dvh] max-h-[100dvh] min-h-0 w-[100dvw] max-w-[100dvw] min-w-0 flex-col overflow-hidden rounded-none border-0 bg-card shadow-none">
          <div className="shrink-0 flex items-start gap-2 max-md:gap-2 px-3 max-md:px-3 sm:px-5 py-2 max-md:py-2 sm:py-3 border-b border-border/60 bg-muted/15">
            <div className="min-w-0 flex-1">
              {catalogLabel ? (
                <span className="text-[9px] max-md:text-[9px] sm:text-[10px] uppercase tracking-wider font-semibold text-muted-foreground">
                  {catalogLabel}
                </span>
              ) : null}
              <h2
                className="text-xs max-md:text-xs sm:text-base font-semibold text-foreground leading-tight max-md:leading-tight mt-0.5 line-clamp-2"
                title={title || undefined}
              >
                {title || "Náhled dat"}
              </h2>
              {code ? (
                <p className="text-[10px] max-md:text-[10px] font-mono text-muted-foreground mt-0.5 truncate">kód: {code}</p>
              ) : null}
              {previewLoading ? (
                <p className="text-[11px] text-muted-foreground mt-1" role="status">
                  Načítám data ze zdroje…
                </p>
              ) : null}
            </div>
            <button
              type="button"
              onClick={onClose}
              className="h-8 w-8 shrink-0 inline-flex items-center justify-center rounded-xl border border-border/70 bg-card hover:bg-muted/50"
              title="Zavřít náhled (Esc)"
              aria-label="Zavřít náhled"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          <div className="flex flex-1 min-h-0 flex-col overflow-y-auto max-md:overflow-y-auto px-2 max-md:px-2 sm:px-5 py-2 max-md:py-2 sm:py-3 catalog-preview-fullscreen-body">
            {children}
          </div>
        </div>
      </div>
    </>,
    document.body,
  );
}
