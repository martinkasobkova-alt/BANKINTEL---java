import React, { useMemo, useState } from "react";
import { Check, Link2, MessageCircle } from "lucide-react";
import { toast } from "sonner";
import ChartShareRecipientDialog from "@/components/catalog/ChartShareRecipientDialog";
import { buildCatalogShareContext } from "@/lib/catalogChartShare";

/**
 * Sdílení grafu — zkopírovat odkaz nebo poslat přes zprávy.
 * Buď katalogové pole, nebo hotový shareContext ({ copyLink, messagesLink }).
 */
export default function CatalogChartShareButtons({
  catalogId,
  sourceType,
  setId,
  title,
  indicatorId,
  shareContext = null,
  disabled = false,
  compact = false,
}) {
  const [copied, setCopied] = useState(false);
  const [recipientDialogOpen, setRecipientDialogOpen] = useState(false);
  const ctx = useMemo(() => {
    if (shareContext && (shareContext.copyLink || shareContext.messagesLink)) {
      return shareContext;
    }
    return buildCatalogShareContext({ catalogId, sourceType, setId, title, indicatorId });
  }, [shareContext, catalogId, sourceType, setId, title, indicatorId]);

  const copyUrl = ctx.copyLink || ctx.absolutePageLink || ctx.pageLink;
  const messagesLink = ctx.messagesLink;

  if (!copyUrl && !messagesLink) return null;

  const copyLink = async () => {
    const url = copyUrl;
    if (!url) {
      toast.error("Odkaz na graf nelze sestavit.");
      return;
    }
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      toast.success("Odkaz na graf zkopírován do schránky.");
      window.setTimeout(() => setCopied(false), 2200);
    } catch {
      toast.error("Nepodařilo se zkopírovat odkaz.");
    }
  };

  const shareInChat = () => {
    if (!messagesLink && !ctx.setId) {
      toast.error("Sdílení v chatu nelze sestavit.");
      return;
    }
    setRecipientDialogOpen(true);
  };

  const recipientDialog = (
    <ChartShareRecipientDialog
      open={recipientDialogOpen}
      onOpenChange={setRecipientDialogOpen}
      title={ctx.title}
      sourceType={ctx.sourceType}
      setId={ctx.setId}
      pageUrl={ctx.absolutePageLink || ctx.pageLink}
    />
  );

  if (compact) {
    return (
      <>
        {recipientDialog}
        <button
          type="button"
          onClick={() => void copyLink()}
          disabled={disabled || !copyUrl}
          className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-border/70 bg-white text-slate-600 shadow-sm hover:bg-white hover:text-slate-900 disabled:opacity-50"
          title="Sdílet odkaz"
          aria-label="Sdílet odkaz"
        >
          {copied ? <Check className="h-3.5 w-3.5 text-emerald-600" /> : <Link2 className="h-3.5 w-3.5" />}
        </button>
        <button
          type="button"
          onClick={shareInChat}
          disabled={disabled || !messagesLink}
          className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-indigo-300 bg-indigo-50 text-indigo-800 shadow-sm hover:bg-indigo-100 disabled:opacity-50"
          title="Sdílet v chatu"
          aria-label="Sdílet v chatu"
        >
          <MessageCircle className="h-3.5 w-3.5" />
        </button>
      </>
    );
  }

  return (
    <>
      {recipientDialog}
      <button
        type="button"
        onClick={() => void copyLink()}
        disabled={disabled}
        className="inline-flex items-center gap-1.5 h-7 px-2.5 text-[11px] rounded-lg border border-border/70 bg-card hover:bg-muted/50 disabled:opacity-50"
        title="Zkopírovat odkaz na tento graf"
      >
        {copied ? <Check className="h-3 w-3 text-emerald-600" /> : <Link2 className="h-3 w-3" />}
        Sdílet odkaz
      </button>
      <button
        type="button"
        onClick={shareInChat}
        disabled={disabled}
        className="inline-flex items-center gap-1.5 h-7 px-2.5 text-[11px] rounded-lg border border-indigo-300 bg-indigo-50 text-indigo-900 hover:bg-indigo-100 disabled:opacity-50"
        title="Vybrat konverzaci nebo uživatele pro sdílení grafu"
      >
        <MessageCircle className="h-3 w-3" />
        Sdílet v chatu
      </button>
    </>
  );
}
