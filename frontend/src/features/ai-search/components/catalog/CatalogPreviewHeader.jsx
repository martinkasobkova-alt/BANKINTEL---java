import React from "react";
import { fmtPeriodLabel } from "@/lib/format";

/**
 * Horní metadata panel katalogového preview.
 */
export default function CatalogPreviewHeader({ previewState, preview }) {
  const meta = previewState?.metadata || {};
  const title = meta.title || preview?.source?.name || preview?.title || "Náhled";
  const source = previewState?.source || preview?.source?.source_type || "";
  const unit = meta.unit || "";
  const freq = meta.frequency || "";
  const last = meta.last_period || "";

  return (
    <div
      className="rounded-lg border border-border/50 bg-muted/20 px-3 py-2 text-xs text-slate-700 space-y-0.5"
      data-testid="catalog-preview-header"
    >
      <div className="font-medium text-slate-900 truncate">{title}</div>
      <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-slate-600">
        {source ? <span>Zdroj: {source}</span> : null}
        {freq ? <span>Frekvence: {freq}</span> : null}
        {unit ? <span>Jednotka: {unit}</span> : null}
        {last ? <span>Poslední období: {fmtPeriodLabel(last) || last}</span> : null}
        {previewState?.needs_selection ? (
          <span className="text-amber-700">Vyžaduje výběr dimenzí</span>
        ) : null}
      </div>
    </div>
  );
}
