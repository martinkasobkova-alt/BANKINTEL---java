import React from "react";
import { ClipboardPaste, Crosshair, FileText, MousePointer2, X } from "lucide-react";

/** Lišta režimů nad PDF — admin vidí nástroje přímo u stránky. */
export default function ArchivePdfAdminToolbar({
  viewMode = "iframe",
  regionMarkActive = false,
  regionMarkAvailable = false,
  previewLoading = false,
  onViewModeChange,
  onCancelRegionMark,
  onCaptureText,
  captureLoading = false,
}) {
  const btnBase =
    "inline-flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-[11px] font-medium transition";
  const activeCls = "border-[hsl(var(--primary)/0.45)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]";
  const idleCls = "border-border/70 bg-white text-slate-700 hover:bg-slate-50";

  return (
    <div className="w-full rounded-md border border-border/70 bg-slate-50/90 px-2 py-1.5 space-y-1.5">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500 mr-1">Nástroje:</span>
        <button
          type="button"
          onClick={() => onViewModeChange?.("iframe")}
          className={`${btnBase} ${viewMode === "iframe" && !regionMarkActive ? activeCls : idleCls}`}
          title="Stránka s klikacími odkazy na grafy"
        >
          <FileText className="h-3.5 w-3.5" />
          Článek s odkazy
        </button>
        <button
          type="button"
          onClick={() => onViewModeChange?.("text")}
          className={`${btnBase} ${viewMode === "text" && !regionMarkActive ? activeCls : idleCls}`}
          title="Propojení textu s grafem"
        >
          <MousePointer2 className="h-3.5 w-3.5" />
          Označit text
        </button>
        <button
          type="button"
          onClick={() => onViewModeChange?.("region")}
          disabled={!regionMarkAvailable || previewLoading}
          className={`${btnBase} ${
            regionMarkActive || viewMode === "region"
              ? activeCls
              : !regionMarkAvailable || previewLoading
                ? "border-border/50 bg-slate-100 text-slate-400 cursor-not-allowed"
                : idleCls
          }`}
          title={
            regionMarkAvailable
              ? "Tažením označit oblast grafu"
              : "Vyžaduje náhled stránky ze serveru (PyMuPDF)"
          }
        >
          <Crosshair className="h-3.5 w-3.5" />
          Označit oblast grafu
        </button>
        {viewMode === "text" && !regionMarkActive ? (
          <button
            type="button"
            onClick={() => onCaptureText?.()}
            disabled={captureLoading}
            className={`${btnBase} ${activeCls} ml-auto`}
            title="Po označení textu v PDF stiskněte Ctrl+C, pak toto tlačítko"
          >
            <ClipboardPaste className="h-3.5 w-3.5" />
            {captureLoading ? "Načítám…" : "Převzít označený text"}
          </button>
        ) : null}
        {regionMarkActive ? (
          <button
            type="button"
            onClick={() => onCancelRegionMark?.()}
            className={`${btnBase} ${idleCls} ml-auto`}
          >
            <X className="h-3.5 w-3.5" />
            Zrušit označování
          </button>
        ) : null}
      </div>
      <p className="text-[10px] text-slate-600 leading-snug">
        {regionMarkActive
          ? "Tažením myši označte graf na stránce níže. Poté vpravo vyberte řadu z katalogu."
          : viewMode === "text"
            ? "V PDF níže označte text myší → Ctrl+C (kopírovat) → text se sám doplní vpravo, nebo klikněte „Převzít označený text“."
            : viewMode === "region" && !regionMarkAvailable
              ? "Oblast grafu teď nejde — chybí náhled na serveru. Použijte „Označit text“."
              : "Klikněte na zvýrazněná slova v článku (nebo tlačítka Odkazy nahoře). Pro nové propojení zvolte „Označit text“."}
      </p>
    </div>
  );
}
