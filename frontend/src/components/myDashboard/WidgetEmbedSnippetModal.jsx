import React, { useMemo } from "react";
import { Copy, X } from "lucide-react";
import { toast } from "sonner";
import { buildWidgetEmbedIframeCode } from "@/lib/widgetEmbed";

/**
 * Modal s iframe kódem pro vložení grafu do článku.
 */
export default function WidgetEmbedSnippetModal({
  open = false,
  onClose,
  shareToken = "",
  widgetId = "",
  widgetTitle = "",
}) {
  const snippet = useMemo(
    () => buildWidgetEmbedIframeCode(shareToken, widgetId),
    [shareToken, widgetId]
  );

  if (!open) return null;

  const copySnippet = async () => {
    if (!snippet) return;
    try {
      await navigator.clipboard.writeText(snippet);
      toast.success("Embed kód zkopírován");
    } catch {
      toast.error("Kopírování se nepodařilo");
    }
  };

  return (
    <div
      className="fixed inset-0 z-[120] flex items-center justify-center p-4 bg-black/40"
      role="dialog"
      aria-modal="true"
      aria-labelledby="widget-embed-snippet-title"
    >
      <div className="bg-card rounded-2xl shadow-xl max-w-lg w-full p-5 space-y-4 border border-border">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div id="widget-embed-snippet-title" className="text-sm font-medium text-foreground">
              Vložit graf do článku
            </div>
            {widgetTitle ? (
              <p className="mt-1 text-xs text-muted-foreground line-clamp-2">{widgetTitle}</p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-border/70 hover:bg-muted/50"
            aria-label="Zavřít"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <p className="text-xs text-muted-foreground leading-snug">
          Vložte tento kód do HTML článku (WordPress, CMS…). Graf zůstane interaktivní; čtenář se nemusí
          přihlašovat.
        </p>
        <textarea
          readOnly
          value={snippet}
          rows={5}
          className="w-full rounded-lg border border-border/70 bg-muted/20 px-3 py-2 text-[11px] font-mono leading-relaxed"
          onFocus={(e) => e.target.select()}
        />
        <div className="flex justify-end gap-2">
          <button
            type="button"
            className="h-9 px-3 text-xs rounded-lg border border-border/80 bg-card hover:bg-muted/50"
            onClick={onClose}
          >
            Zavřít
          </button>
          <button
            type="button"
            className="btn-mint h-9 px-4 text-xs inline-flex items-center gap-1.5 disabled:opacity-50"
            onClick={() => void copySnippet()}
            disabled={!snippet}
          >
            <Copy className="h-3.5 w-3.5" />
            Kopírovat
          </button>
        </div>
      </div>
    </div>
  );
}
