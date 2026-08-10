import React from "react";

import { LoadingSpinner } from "@/components/ui/loading";
import { formatPreviewMessage } from "@/lib/previewNormalizer";

export function CatalogStubsLoadErrorBanner({ title, message, retryLabel, onRetry }) {
  const messageText = formatPreviewMessage(message, "Katalog se nepodařilo načíst.");
  if (!messageText) return null;

  return (
    <div
      className="rounded-2xl border border-rose-200 bg-rose-50/95 text-rose-950 canvas-dark:text-rose-100 p-6 space-y-3 shadow-sm"
      role="alert"
      data-testid="catalog-stubs-load-error"
    >
      <p className="font-semibold">{title}</p>
      <p className="text-sm whitespace-pre-wrap break-words">{messageText}</p>
      <button
        type="button"
        className="inline-flex items-center gap-2 px-4 py-2 rounded-xl border border-rose-300 canvas-dark:border-rose-600/45 bg-card hover:bg-rose-50 canvas-dark:hover:bg-rose-950/30 text-sm font-medium text-rose-950 canvas-dark:text-rose-100"
        onClick={onRetry}
      >
        {retryLabel}
      </button>
    </div>
  );
}

export function CatalogAiSearchFeedback({
  visible,
  showLock,
  lockMessage,
  loading,
  onCancel,
}) {
  if (!visible) return null;

  return (
    <div className="catalog-unified-header-sticky">
      <div
        className="catalog-unified-header rounded-2xl border border-sky-100/90 p-3 sm:p-4 space-y-2.5 min-w-0 w-full"
        data-testid="catalog-ai-search-feedback"
        role="status"
      >
        {showLock ? (
          <p className="text-[12px] text-rose-950 bg-rose-50 border border-rose-200 rounded-lg px-2.5 py-2">
            {lockMessage || "AI hloubkové vyhledávání není pro váš účet dostupné."}
          </p>
        ) : null}

        {loading ? (
          <div className="flex flex-wrap items-center gap-3">
            <span
              className="inline-flex items-center gap-2 text-sm text-muted-foreground"
              aria-busy="true"
            >
              <LoadingSpinner suppressAria size="sm" /> Hledám…
            </span>
            <button
              type="button"
              className="text-sm px-3 py-1.5 rounded-lg border border-border bg-card hover:bg-muted/50"
              onClick={onCancel}
            >
              Zrušit hledání
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}

export function CatalogClassicEmptyState() {
  return (
    <div className="border border-dashed border-border bg-muted/25 rounded-2xl p-10 text-center text-sm text-muted-foreground font-mono">
      Žádná řada nevyhovuje (kurátorované katalogy nemusí obsahovat přesně tento text). Zkuste jiné klíčové
      slovo (např. u FRED kódy HOUST, CSUSHPISA), méně slov najednou, nebo jiné zdroje.
    </div>
  );
}

export function CatalogSearchStatusBanners({
  useAiAssistant,
  inAppSearchError,
  crossSearchPartialIndexMissing,
  crossSearchUsedLocalFallback,
  awaitingCatalogs,
  hasBackendResults,
}) {
  return (
    <>
      {!useAiAssistant && inAppSearchError ? (
        <div className="mb-3 rounded-xl border border-amber-200/90 bg-amber-50/80 px-4 py-2 text-[12px] text-amber-950">
          V aplikaci: {inAppSearchError}
        </div>
      ) : null}
      {crossSearchPartialIndexMissing ? (
        <div className="mb-3 border border-slate-200/90 canvas-dark:border-slate-600/45 bg-slate-50/80 canvas-dark:bg-slate-950/30 rounded-xl px-4 py-2 text-[12px] text-slate-800 canvas-dark:text-slate-100/95">
          Některé katalogy zatím nemají lokální index, zobrazujeme dostupné výsledky.
        </div>
      ) : null}
      {crossSearchUsedLocalFallback ? (
        <div className="mb-3 border border-amber-200/90 canvas-dark:border-amber-600/45 bg-amber-50/80 canvas-dark:bg-amber-950/30 rounded-xl px-4 py-2 text-[12px] text-amber-950 canvas-dark:text-amber-50/95">
          Serverové hledání není dostupné — zobrazujeme lokální filtr načtených stromů (AND podle slov).
        </div>
      ) : null}
      {!useAiAssistant && awaitingCatalogs && hasBackendResults ? (
        <div className="mb-3 border border-[hsl(var(--border)/0.75)] bg-card/80 rounded-xl px-4 py-2 text-[12px] text-muted-foreground font-mono">
          Některé vybrané katalogy se ještě načítají; výsledky jsou ze serverového hledání napříč zdroji.
        </div>
      ) : null}
      {awaitingCatalogs && crossSearchUsedLocalFallback ? (
        <div className="mb-3 border border-[hsl(var(--border)/0.75)] bg-card/80 rounded-xl px-4 py-2 text-[12px] text-muted-foreground font-mono">
          Některé vybrané katalogy se ještě načítají; výsledky níže jsou z těch, které už doběhly.
        </div>
      ) : null}
    </>
  );
}
