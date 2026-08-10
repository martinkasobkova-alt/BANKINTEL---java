import React from "react";

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

export default function AradViewCaptionNotesPanel({
  caption = {},
  chartTheme,
  showCaption = true,
  showNotes = true,
  notes = {},
}) {
  const {
    captionDisplayText,
    captionAnchorRef,
    canExpandCaption,
    onExpandCaption,
    compact = false,
    hidden = false,
    onHide,
    onShow,
  } = caption;
  const {
    miniChartMode,
    isMobileEmbed,
    hasAiAnalysisDatasets,
    aiAnalysisDatasets = [],
    fallbackCaption,
  } = notes;

  return (
    <>
      {showCaption && captionDisplayText && hidden ? (
        <div
          ref={captionAnchorRef}
          className="relative z-[1] shrink-0 border-t px-3 py-1 text-left text-[10px] leading-snug text-slate-500"
          style={{ background: chartTheme.captionBg, borderColor: chartTheme.border }}
        >
          <span>Komentář ke grafu je skrytý.</span>
          {typeof onShow === "function" ? (
            <button
              type="button"
              onClick={onShow}
              className="ml-2 inline-flex items-center px-1 py-0.5 text-[10px] font-medium normal-case hover:underline"
              style={{ color: chartTheme.accent }}
            >
              Zobrazit
            </button>
          ) : null}
        </div>
      ) : showCaption && captionDisplayText ? (
        <div
          ref={captionAnchorRef}
          className={`relative z-[1] shrink-0 overflow-hidden border-t text-left text-slate-600 ${
            miniChartMode
              ? "px-2 py-1 text-[9px] leading-snug"
              : compact
                ? "px-3 py-1 text-[10px] leading-snug"
              : "px-4 py-2 text-[11px] leading-relaxed"
          }`}
          style={{ background: chartTheme.captionBg, borderColor: chartTheme.border, fontStyle: "italic" }}
        >
          <span
            className={`min-w-0 [overflow-wrap:anywhere] ${
              compact ? "line-clamp-1" : canExpandCaption ? "line-clamp-2" : "block"
            }`}
          >
            {captionDisplayText}
          </span>
          {canExpandCaption && (
            <button
              type="button"
              onClick={onExpandCaption}
              className={`ml-1 inline-flex items-center px-1 py-0.5 text-[11px] italic font-normal normal-case tracking-normal hover:underline opacity-90 hover:opacity-100 touch-manipulation ${
                isMobileEmbed ? "relative z-[2]" : ""
              }`}
              style={{ color: chartTheme.accent }}
            >
              … více
            </button>
          )}
          {compact && typeof onHide === "function" ? (
            <button
              type="button"
              onClick={onHide}
              className="ml-2 inline-flex items-center px-1 py-0.5 text-[10px] italic font-normal normal-case hover:underline opacity-90 hover:opacity-100"
              style={{ color: chartTheme.accent }}
            >
              Skrýt
            </button>
          ) : null}
        </div>
      ) : null}

      {showNotes && !miniChartMode && hasAiAnalysisDatasets ? (
        <div
          className="shrink-0 px-4 py-3 border-t text-[11px] leading-relaxed text-slate-700 text-left space-y-3"
          style={{ background: chartTheme.captionBg, borderColor: chartTheme.border }}
        >
          <div className="space-y-1">
            <div className="text-[12px] font-semibold text-slate-900">AI interpretace dat</div>
            <div className="text-[11px] leading-relaxed line-clamp-2">
              {fallbackCaption || "Interpretace není dostupná."}
            </div>
          </div>

          <div className="space-y-2">
            <div className="text-[12px] font-semibold text-slate-900">Použité datové řady</div>
            {aiAnalysisDatasets.length === 0 ? (
              <div className="text-[11px] text-slate-600">Seznam datových řad není k dispozici.</div>
            ) : (
              aiAnalysisDatasets.map((ds, idx) => {
                const row = asObject(ds);
                const values = asArray(row.values).filter((v) => v && typeof v === "object");
                return (
                  <div key={`${row.series_id || row.dataset_id || "series"}-${idx}`} className="rounded-md border border-border/60 bg-white/70 p-2 space-y-1">
                    <div><strong>Zdroj:</strong> {row.source_name || "—"}</div>
                    <div><strong>Dataset:</strong> {row.dataset_name || "—"}</div>
                    <div><strong>ID datasetu:</strong> {row.dataset_id || "—"}</div>
                    <div><strong>ID řady:</strong> {row.series_id || "—"}</div>
                    <div><strong>Ukazatel:</strong> {row.indicator || "—"}</div>
                    <div><strong>Země:</strong> {row.country || "—"}</div>
                    <div><strong>Jednotka:</strong> {row.unit || "—"}</div>
                    <div><strong>Frekvence:</strong> {row.frequency || "—"}</div>
                    <div><strong>Období:</strong> {row.time_range || "—"}</div>
                    <div>
                      <strong>Odkaz na zdroj:</strong>{" "}
                      {row.source_url ? (
                        <a href={row.source_url} target="_blank" rel="noreferrer" className="text-[hsl(var(--primary))] underline">
                          {row.source_url}
                        </a>
                      ) : "—"}
                    </div>

                    <details className="mt-1">
                      <summary className="cursor-pointer text-[11px] font-medium text-[hsl(var(--primary-deep))]">
                        Zobrazit hodnoty datové řady
                      </summary>
                      <div className="mt-2 overflow-x-auto">
                        <table className="data-table text-[10px]">
                          <thead>
                            <tr>
                              <th>date</th>
                              <th>value</th>
                            </tr>
                          </thead>
                          <tbody>
                            {values.length === 0 ? (
                              <tr>
                                <td colSpan={2} className="text-slate-500">Žádné hodnoty.</td>
                              </tr>
                            ) : (
                              values.map((v, vIdx) => (
                                <tr key={`${idx}-${vIdx}`}>
                                  <td>{String(v.date || "")}</td>
                                  <td>{v.value ?? ""}</td>
                                </tr>
                              ))
                            )}
                          </tbody>
                        </table>
                      </div>
                    </details>
                  </div>
                );
              })
            )}
          </div>
        </div>
      ) : null}
    </>
  );
}
