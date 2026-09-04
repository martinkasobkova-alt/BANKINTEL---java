import React, { useMemo } from "react";
import { X } from "lucide-react";
import SearchProgressRadar from "./SearchProgressRadar.jsx";

const STAGE_LABELS = {
  finding: "Hledám data",
  scanning: "Prohledávám zdroje",
  comparing: "Porovnávám výsledky",
  selecting: "Vybírám nejlepší datové řady",
  preparing: "Připravuji výsledky",
};

const MAX_RADAR_SOURCES = 6;

function normalizeSources(sources) {
  return (Array.isArray(sources) ? sources : [])
    .map((s) => (typeof s === "string" ? { id: s, label: s } : s))
    .filter((s) => s && s.id != null && String(s.id).trim())
    .map((s) => ({ id: String(s.id), label: String(s.label ?? s.id) }));
}

function toIdSet(value) {
  const arr = Array.isArray(value) ? value : value ? [value] : [];
  return new Set(arr.map((v) => String(v)));
}

/**
 * Světlá, animovaná karta průběhu hledání - nahrazuje starý tmavý terminálový panel
 * (`CatalogDeepSearchLoader`/`ExploreSectorScanLoader`). Nikdy nevykresluje jednotlivý zdroj
 * jako "právě aktivní", pokud volající explicitně nepošle `activeSource` - pro katalogové AI
 * hledání to appka doopravdy neví (backend SSE posílá jen dokončení zdroje, ne jeho start),
 * takže tam volající posílá `activeSource={null}` a zdroje se ukazují jen jako čekající/hotové.
 */
export default function SearchProgressCard({
  query = "",
  sources = [],
  activeSource = null,
  completedSources = [],
  stage = "finding",
  resultCount = null,
  onCancel,
  mode = "catalog",
  className = "",
}) {
  const normSources = useMemo(() => normalizeSources(sources), [sources]);
  const activeSet = useMemo(() => toIdSet(activeSource), [activeSource]);
  const completedSet = useMemo(() => toIdSet(completedSources), [completedSources]);

  const radarSources = useMemo(() => {
    const withState = normSources.map((s) => ({
      ...s,
      state: completedSet.has(s.id) ? "done" : activeSet.has(s.id) ? "active" : "pending",
    }));
    // Stabilní řazení podle priority (aktivní/hotové před čekajícími) - jen tak se do
    // omezeného počtu bodů na radaru vejde i zdroj s reálným stavem, když je zdrojů hodně
    // (Manager Explorer "Vše"), místo aby ho zakrylo prvních N čekajících.
    const ranked = withState
      .map((s, index) => ({ s, index, rank: s.state === "pending" ? 1 : 0 }))
      .sort((a, b) => a.rank - b.rank || a.index - b.index)
      .map((entry) => entry.s);
    return ranked.slice(0, MAX_RADAR_SOURCES);
  }, [normSources, activeSet, completedSet]);

  const shownIds = useMemo(() => new Set(radarSources.map((s) => s.id)), [radarSources]);
  const extraCount = normSources.length - radarSources.length;

  const totalKnown = normSources.length > 0;
  const completedCount = normSources.filter((s) => completedSet.has(s.id)).length;
  const progressLabel = totalKnown ? `${completedCount} z ${normSources.length} zdrojů` : "";

  const stageLabel = STAGE_LABELS[stage] || STAGE_LABELS.finding;
  const trimmedQuery = String(query || "").trim();

  return (
    <div
      className={`rounded-2xl border border-[hsl(var(--primary)/0.16)] bg-gradient-to-br from-white to-[hsl(var(--primary-soft)/0.3)] shadow-sm overflow-hidden ${className}`}
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={`${stageLabel}${progressLabel ? ` — ${progressLabel}` : ""}`}
      data-testid="search-progress-card"
      data-mode={mode}
      data-stage={stage}
    >
      <div className="flex flex-col sm:flex-row items-center gap-3 sm:gap-5 px-4 sm:px-6 py-4 sm:py-5">
        <SearchProgressRadar sources={radarSources} size={104} />

        <div className="flex-1 min-w-0 w-full">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-[14px] sm:text-[15px] font-semibold text-[hsl(var(--primary-deep))] leading-snug">
                {stageLabel}
              </p>
              {trimmedQuery ? (
                <p className="text-[12px] text-slate-500 mt-0.5 truncate" title={trimmedQuery}>
                  „{trimmedQuery}“
                </p>
              ) : null}
              {resultCount != null ? (
                <p className="text-[11px] text-slate-500 mt-0.5">
                  Zatím nalezeno {resultCount} {resultCount === 1 ? "řada" : "datových řad"}
                </p>
              ) : null}
            </div>
            {onCancel ? (
              <button
                type="button"
                onClick={onCancel}
                className="shrink-0 h-7 px-2.5 inline-flex items-center gap-1 rounded-md border border-[hsl(var(--primary)/0.25)] bg-white/70 text-[11px] font-medium text-slate-600 hover:bg-[hsl(var(--primary-soft)/0.55)] hover:text-[hsl(var(--primary-deep))]"
                aria-label="Zastavit hledání a zachovat nalezené kandidáty"
                title="Zastaví hledání — nalezené kandidáty zůstanou"
              >
                <X className="h-3 w-3" />
                Zrušit
              </button>
            ) : null}
          </div>

          {normSources.length > 0 ? (
            <div className="mt-3 flex flex-wrap items-center gap-1.5 overflow-x-auto sm:overflow-visible">
              {normSources
                .filter((s) => shownIds.has(s.id))
                .map((s) => {
                  const state = completedSet.has(s.id) ? "done" : activeSet.has(s.id) ? "active" : "pending";
                  return (
                    <span
                      key={s.id}
                      className={`shrink-0 inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-medium ${
                        state === "done"
                          ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                          : state === "active"
                            ? "border-[hsl(var(--primary)/0.35)] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
                            : "border-slate-200 bg-slate-50 text-slate-500"
                      }`}
                    >
                      {s.label}
                    </span>
                  );
                })}
              {extraCount > 0 ? (
                <span className="shrink-0 inline-flex items-center rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-[10px] font-medium text-slate-500">
                  +{extraCount} dalších
                </span>
              ) : null}
            </div>
          ) : null}

          <div className="mt-3">
            {progressLabel ? (
              <p className="text-[11px] font-medium text-slate-500 tabular-nums">{progressLabel}</p>
            ) : (
              <div
                className="h-1 w-full max-w-[220px] rounded-full bg-[hsl(var(--primary)/0.12)] overflow-hidden"
                aria-hidden="true"
              >
                <div className="h-full w-1/3 rounded-full bg-[hsl(var(--primary)/0.55)] motion-safe:animate-[explore-scan_1.6s_ease-in-out_infinite]" />
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
