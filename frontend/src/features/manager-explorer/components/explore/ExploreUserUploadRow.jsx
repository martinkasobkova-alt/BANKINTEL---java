import React from "react";
import { ChevronDown, Loader2 } from "lucide-react";

export function formatExploreUploadDate(raw) {
  if (!raw) return "bez data";
  try {
    const d = new Date(raw);
    if (Number.isNaN(d.getTime())) return String(raw);
    return d.toLocaleString("cs-CZ", { dateStyle: "medium", timeStyle: "short" });
  } catch {
    return String(raw);
  }
}

export default function ExploreUserUploadRow({
  upload,
  checked,
  expanded,
  preview,
  onToggleSelect,
  onToggleExpand,
  formatUploadSize,
}) {
  const uploadId = String(upload?.id || "").trim();
  const name = String(upload?.original_name || uploadId);
  const previewState = preview?.status || "idle";

  return (
    <div
      className={`rounded-xl border overflow-hidden ${
        checked ? "border-emerald-300/70 bg-emerald-50/50" : "border-border/70 bg-muted/10"
      }`}
    >
      <div className="flex items-start gap-2 px-3 py-2.5">
        <input
          type="checkbox"
          className="mt-1 shrink-0"
          checked={checked}
          onChange={() => onToggleSelect(uploadId)}
          onClick={(e) => e.stopPropagation()}
          aria-label={`Zahrnout ${name} do analýzy`}
        />
        <button
          type="button"
          className="min-w-0 flex-1 text-left rounded-lg hover:bg-muted/30 -mx-1 px-1 py-0.5"
          onClick={() => onToggleExpand(uploadId)}
          aria-expanded={expanded}
        >
          <div className="text-sm font-medium text-slate-900 truncate">{name}</div>
          <div className="text-[11px] text-muted-foreground mt-0.5">
            {formatUploadSize(upload?.size)} · {formatExploreUploadDate(upload?.created_at)}
          </div>
        </button>
        <button
          type="button"
          className="h-8 w-8 shrink-0 rounded-lg border border-border/60 bg-card hover:bg-muted/40 inline-flex items-center justify-center text-slate-600"
          onClick={() => onToggleExpand(uploadId)}
          aria-expanded={expanded}
          aria-label={expanded ? "Sbalit detail souboru" : "Rozbalit detail souboru"}
        >
          <ChevronDown
            className={`h-4 w-4 transition-transform ${expanded ? "rotate-180" : ""}`}
          />
        </button>
      </div>

      {expanded ? (
        <div className="border-t border-border/60 bg-card/80 px-3 py-3 text-xs space-y-2">
          {previewState === "loading" ? (
            <div className="inline-flex items-center gap-2 text-muted-foreground">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              Načítám náhled sloupců…
            </div>
          ) : null}
          {previewState === "error" ? (
            <p className="text-amber-900">{String(preview?.error || "Náhled se nepodařil načíst.")}</p>
          ) : null}
          {previewState === "ready" ? (
            <>
              {preview?.error ? (
                <p className="text-amber-900">{String(preview.error)}</p>
              ) : null}
              <div>
                <span className="font-medium text-slate-700">Sloupce: </span>
                <span className="text-muted-foreground">
                  {(preview?.columns || []).length
                    ? (preview.columns || []).join(", ")
                    : "—"}
                </span>
              </div>
              {(preview?.sample_rows || []).length > 0 ? (
                <div>
                  <div className="font-medium text-slate-700 mb-1">Ukázka dat (první řádek)</div>
                  <pre className="text-[10px] overflow-x-auto max-h-28 bg-muted/30 p-2 rounded-lg border border-border/50">
                    {JSON.stringify(preview.sample_rows[0], null, 2)}
                  </pre>
                </div>
              ) : (
                <p className="text-muted-foreground">Soubor neobsahuje žádné řádky k náhledu.</p>
              )}
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
