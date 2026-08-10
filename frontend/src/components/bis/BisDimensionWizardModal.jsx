import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Undo2, Eye, Layers, X } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import SourcePreview from "@/components/sources/SourcePreview";
import { LoadingBlock, LoadingSpinner } from "@/components/ui/loading";

const BIS_UI_TIMEOUT_MS = 20_000;

function filterDimensionOptions(query, opts) {
  const q = (query || "").trim().toLowerCase();
  if (!q) return opts;
  return opts.filter(
    (o) =>
      String(o.id).toLowerCase().includes(q) || String(o.name || "").trim().toLowerCase().includes(q),
  );
}

/**
 * Průvodce dimenzemi BIS Stats API (SDMX). Prázdná dimenze → segment klíče ``_``.
 */
function BisDimensionWizardModalContent({ onClose, flowRef, flowTitle }) {
  const [structure, setStructure] = useState(null);
  const [structErr, setStructErr] = useState("");
  const [structDetail, setStructDetail] = useState("");
  const [structLoading, setStructLoading] = useState(false);
  const [sel, setSel] = useState({});
  const [optFilter, setOptFilter] = useState({});
  const [preview, setPreview] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewEmptyNotice, setPreviewEmptyNotice] = useState("");
  const [startPeriod, setStartPeriod] = useState("2019-01");
  const [endPeriod, setEndPeriod] = useState("");

  const fetchGen = useRef(0);

  const loadStructure = useCallback(async () => {
    const fid = String(flowRef || "").trim();
    if (!fid) return;
    const myGen = ++fetchGen.current;
    setStructLoading(true);
    setStructErr("");
    setStructDetail("");
    try {
      const { data } = await api.get(
        `/bis/catalog/dataflow/${encodeURIComponent(fid)}/dimensions`,
        { timeout: BIS_UI_TIMEOUT_MS },
      );
      if (fetchGen.current !== myGen) return;
      setStructure(data || null);
      const next = {};
      for (const d of data?.dimensions || []) {
        const id = String(d.id || "");
        if (!id) continue;
        if (d.allow_multi_select) next[id] = [];
        else next[id] = "";
      }
      setSel(next);
      setPreview(null);
      setPreviewEmptyNotice("");
      setOptFilter({});
    } catch (e) {
      if (fetchGen.current !== myGen) return;
      setStructure(null);
      const msg = formatApiErrorFromAxios(e);
      const body = e?.response?.data;
      const tech =
        typeof body?.detail === "string"
          ? body.detail
          : typeof body === "string"
            ? body
            : "";
      setStructErr(
        msg.includes("502") || /strukturu|structure|parsovat/i.test(msg || "")
          ? "BIS struktura pro tento dataflow zatím není dostupná."
          : "Nepodařilo se načíst dimenze (BIS DSD)."
      );
      setStructDetail(tech ? String(tech) : msg || "");
    }
    setStructLoading(false);
  }, [flowRef]);

  useEffect(() => {
    if (!flowRef) return undefined;
    loadStructure();
    return () => {
      fetchGen.current += 1;
    };
  }, [flowRef, loadStructure]);

  const dimensionsSortedByDsd = useMemo(() => {
    const dims = structure?.dimensions;
    if (!Array.isArray(dims) || dims.length === 0) return [];
    return [...dims].sort(
      (a, b) =>
        (Number(a?.position ?? a?.POSITION) || 10 ** 9) -
        (Number(b?.position ?? b?.POSITION) || 10 ** 9),
    );
  }, [structure]);

  const bisSeriesKeyFromSelection = useMemo(() => {
    const fid = String(flowRef || "").trim();
    if (!dimensionsSortedByDsd.length || !fid) return "";
    return dimensionsSortedByDsd
      .map((dm) => {
        const id = String(dm.id || "");
        const v = sel[id];
        if (dm.allow_multi_select) {
          const arr = Array.isArray(v) ? [...v].filter(Boolean) : [];
          if (!arr.length) return "_";
          return [...new Set(arr.map(String))].sort().join("+");
        }
        const s = v == null ? "" : String(v).trim();
        return s === "" ? "_" : s;
      })
      .join(".");
  }, [dimensionsSortedByDsd, sel, flowRef]);

  const bisSetIdPreview = useMemo(() => {
    const key = bisSeriesKeyFromSelection;
    const fid = String(flowRef || "").trim();
    if (!key || !fid) return "";
    return `BIS|${fid}|${key}`;
  }, [bisSeriesKeyFromSelection, flowRef]);

  const previewGateHints = useMemo(() => {
    const hints = [];
    if (!dimensionsSortedByDsd.length) return hints;
    let wild = 0;
    let filled = 0;
    for (const dm of dimensionsSortedByDsd) {
      const id = String(dm?.id || "");
      if (!id) continue;
      const v = sel[id];
      if (dm.allow_multi_select) {
        const arr = Array.isArray(v) ? v.filter(Boolean) : [];
        if (arr.length === 0) wild += 1;
        else filled += 1;
      } else if (v === "" || v == null || (typeof v === "string" && !String(v).trim())) {
        wild += 1;
      } else {
        filled += 1;
      }
    }
    const n = dimensionsSortedByDsd.length;
    if (wild >= 6 && filled <= 3) {
      hints.push(
        "Mnoho výchozích „wildcardů“ (prázdné dimenze = „_„ v klíči) — dotaz na BIS může vrátit velký objem nebo prázdno. Zužte výběr.",
      );
    }
    if (wild >= 8 || (n >= 10 && wild / n >= 0.85)) {
      hints.push(
        `Vybrali jste ${wild} wildcard dimenzí z ${n}. Náhled může být pomalý nebo prázdný — použijte období a limit.`,
      );
    }
    return hints;
  }, [dimensionsSortedByDsd, sel]);

  const relaxLastConcreteDimension = useCallback(() => {
    setPreview(null);
    setPreviewEmptyNotice("");
    setSel((prev) => {
      const next = { ...prev };
      for (let i = dimensionsSortedByDsd.length - 1; i >= 0; i--) {
        const dm = dimensionsSortedByDsd[i];
        const id = String(dm?.id || "");
        if (!id) continue;
        const cur = prev[id];
        const concrete = dm.allow_multi_select
          ? Array.isArray(cur) && cur.filter(Boolean).length > 0
          : cur !== undefined && cur !== null && !(typeof cur === "string" && !String(cur).trim());
        if (!concrete) continue;
        if (dm.allow_multi_select) next[id] = [];
        else next[id] = "";
        return next;
      }
      return prev;
    });
  }, [dimensionsSortedByDsd]);

  const runPreview = async () => {
    const fid = String(flowRef || "").trim();
    const key = bisSeriesKeyFromSelection;
    if (!fid || !key) {
      toast.error("Nelze sestavit klíč řady.");
      return;
    }
    setPreviewLoading(true);
    setPreview(null);
    setPreviewEmptyNotice("");
    try {
      const qp = { detail: "dataonly", lastNObservations: "36" };
      const sp = startPeriod.trim();
      const ep = endPeriod.trim();
      if (sp) qp.startPeriod = sp;
      if (ep) qp.endPeriod = ep;

      const { data } = await api.post(
        "/catalog/preview",
        {
          source_type: "bis",
          set_id: `BIS|${fid}|${key}`,
          query_params: qp,
        },
        { timeout: BIS_UI_TIMEOUT_MS },
      );
      const rows = Array.isArray(data?.rows) ? data.rows : [];
      if (!rows.length) {
        setPreview(null);
        setPreviewEmptyNotice(
          data?.bis_preview_notice_cs ||
            "BIS pro tuto kombinaci dimenzí nevrátil data. Zužte výběr nebo zkuste jiné období."
        );
      } else {
        setPreview(data);
      }
      if ((data?.warnings || []).length) {
        for (const w of data.warnings) toast.warning(String(w || ""));
      }
      if ((data?.bis_query_warnings_cs || []).length) {
        for (const w of data.bis_query_warnings_cs) toast.warning(String(w || ""));
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
      setPreview(null);
    }
    setPreviewLoading(false);
  };

  const fid = String(flowRef || "").trim();

  return (
    <div
      className="fixed inset-0 z-[180] flex items-start justify-center p-4 sm:p-8 bg-black/45 backdrop-blur-[2px]"
      role="presentation"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      data-testid="bis-wizard-overlay"
    >
      <div
        role="dialog"
        aria-labelledby="bis-wizard-title"
        aria-modal="true"
        className="w-full max-w-3xl max-h-[calc(100vh-2rem)] overflow-y-auto bg-white rounded-2xl border border-border shadow-xl"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 px-5 py-4 border-b border-border bg-slate-50/85">
          <div className="flex items-start gap-2 min-w-0">
            <Layers className="h-5 w-5 text-teal-700 shrink-0 mt-0.5" />
            <div className="min-w-0">
              <div id="bis-wizard-title" className="text-base font-semibold text-slate-900 truncate">
                BIS — vyberte dimenze / řady
              </div>
              <div className="text-sm text-slate-600 truncate" title={flowTitle}>
                {(flowTitle || "").trim()}{" "}
                <span className="font-mono text-[11px] text-slate-500">({fid})</span>
              </div>
            </div>
          </div>
          <button
            type="button"
            aria-label="Zavřít"
            className="h-9 w-9 shrink-0 grid place-items-center rounded-xl border border-border bg-white hover:bg-slate-50"
            onClick={onClose}
          >
            <X className="h-4 w-4 text-slate-600" />
          </button>
        </div>

        <div className="px-5 py-4 space-y-5">
          <div className="space-y-2">
            <p className="text-[12px] text-slate-600 leading-snug">
              BIS používá <span className="font-mono">/data/[flow]/[key]/all</span>. Klíč (series key)
              se skládá v pořadí podle DSD. <strong className="font-medium text-slate-800">Prázdná dimenze se do klíče
              promítne jako znak „_“</strong> — BIS nedovolí dvě po sobě jdoucí tečky („..„) jako wildcard.
              Více hodnot v jedné dimenzi: <span className="font-mono">+</span>.
            </p>
          </div>

          {structLoading ? (
            <LoadingBlock
              label="Načítám strukturu dataflow z BIS…"
              minHeightClass="min-h-[220px]"
              showSkeletonLines
              skeletonRows={4}
              className="bg-white/85"
            />
          ) : structErr ? (
            <div className="space-y-2">
              <div className="rounded-xl border border-amber-200 bg-amber-50/85 px-3 py-2 text-sm text-amber-950">
                {structErr}
              </div>
              {structDetail ? (
                <details className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                  <summary className="cursor-pointer text-xs font-medium text-slate-700">Technický detail</summary>
                  <pre className="mt-2 text-[11px] text-slate-600 whitespace-pre-wrap break-all font-mono">
                    {structDetail}
                  </pre>
                </details>
              ) : null}
            </div>
          ) : structure?.dimensions?.length ? (
            <div className="space-y-4">
              {structure.dimensions.map((d) => {
                const id = String(d.id || "");
                const filter = optFilter[id] || "";
                const rawVals = Array.isArray(d.values) ? d.values : [];
                const opts = filterDimensionOptions(filter, rawVals);
                const truncated = Boolean(d.values_truncated);
                const hasVals = opts.length > 0;
                const pickMulti = Boolean(d.allow_multi_select);
                const arrSel = Array.isArray(sel[id]) ? sel[id] : [];
                const strSel = typeof sel[id] === "string" ? sel[id] : "";

                return (
                  <div
                    key={id}
                    className="rounded-xl border border-slate-200/90 bg-slate-50/40 p-3 space-y-2 shadow-sm"
                  >
                    <div>
                      <div className="text-xs font-semibold text-slate-900 leading-tight">
                        {d.name || id} <span className="font-mono font-normal text-[10px] text-slate-500">({id})</span>
                      </div>
                    </div>

                    <input
                      type="search"
                      className="w-full h-8 px-2 text-xs border border-border rounded-lg bg-white"
                      placeholder="Filtrovat podle kódu nebo názvu…"
                      disabled={rawVals.length === 0 && !truncated}
                      value={filter}
                      onChange={(e) => setOptFilter((prev) => ({ ...prev, [id]: e.target.value }))}
                    />

                    {!hasVals ? (
                      <div className="rounded-lg border border-amber-200 bg-amber-50/95 px-2.5 py-2 text-[11px] text-amber-950 leading-snug">
                        Hodnoty dimenzí se nepodařilo načíst z číselníku API. Vyberte ručně odkud jindy (kód ze
                        dokumentace BIS), nebo ponechte prázdně — použije se zástupný symbol{' '}
                        <span className="font-mono">_</span> pro tuto pozici klíče.
                      </div>
                    ) : pickMulti ? (
                      <div className="space-y-2">
                        <div className="max-h-56 overflow-y-auto rounded-lg border border-slate-200 bg-white divide-y divide-slate-100">
                          {opts.map((o) => {
                            const oid = String(o.id);
                            const selSet = new Set(arrSel.filter(Boolean));
                            const on = selSet.has(oid);
                            const labelText = String(o.name || "").trim();
                            return (
                              <button
                                key={oid}
                                type="button"
                                className={`w-full flex flex-col sm:flex-row sm:items-baseline gap-0.5 sm:gap-2 text-left px-2 py-2 text-xs transition-colors ${
                                  on ? "bg-teal-50/95 border-l-4 border-teal-600" : "hover:bg-slate-50 border-l-4 border-transparent"
                                }`}
                                onClick={() => {
                                  setSel((prev) => {
                                    const prevArr = Array.isArray(prev[id]) ? [...prev[id]] : [];
                                    const ix = prevArr.indexOf(oid);
                                    if (ix >= 0) prevArr.splice(ix, 1);
                                    else prevArr.push(oid);
                                    return { ...prev, [id]: prevArr };
                                  });
                                }}
                              >
                                <span className="font-mono font-semibold shrink-0 text-slate-900">{oid}</span>
                                <span className="text-slate-600 leading-snug">{labelText ? `— ${labelText}` : ""}</span>
                              </button>
                            );
                          })}
                        </div>
                        <button
                          type="button"
                          className="text-[10px] text-slate-600 underline decoration-dotted underline-offset-2"
                          onClick={() => setSel((prev) => ({ ...prev, [id]: [] }))}
                        >
                          Zrušit výběr (wildcard → _ )
                        </button>
                      </div>
                    ) : (
                      <div className="flex flex-wrap gap-1.5">
                        <button
                          type="button"
                          className={`rounded-lg border px-2 py-1.5 text-[11px] ${
                            strSel === "" ? "border-teal-500 bg-teal-50 text-teal-950" : "border-slate-200 bg-white hover:bg-slate-50"
                          }`}
                          onClick={() => setSel((prev) => ({ ...prev, [id]: "" }))}
                        >
                          Wildcard ( _ )
                        </button>
                        {opts.map((o) => (
                          <button
                            key={String(o.id)}
                            type="button"
                            className={`rounded-lg border px-2 py-1.5 text-left text-[11px] max-w-full ${
                              strSel === String(o.id)
                                ? "border-teal-600 bg-teal-50"
                                : "border-slate-200 bg-white hover:bg-slate-50"
                            }`}
                            onClick={() => setSel((prev) => ({ ...prev, [id]: String(o.id) }))}
                          >
                            <span className="font-mono font-semibold">{o.id}</span>
                            <span className="text-slate-600">{o.name ? ` — ${o.name}` : ""}</span>
                          </button>
                        ))}
                      </div>
                    )}
                    {truncated ? (
                      <span className="text-[10px] text-amber-800 bg-amber-50 border border-amber-200 px-2 py-0.5 rounded-md inline-block">
                        Číselník zobrazen částečně — zpřesněte filtr.
                      </span>
                    ) : null}
                  </div>
                );
              })}

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
                <label className="text-xs space-y-1">
                  <span className="text-slate-600">startPeriod (volitelně)</span>
                  <input
                    className="w-full h-9 px-2 text-xs font-mono border border-border rounded-lg"
                    value={startPeriod}
                    onChange={(e) => setStartPeriod(e.target.value)}
                  />
                </label>
                <label className="text-xs space-y-1">
                  <span className="text-slate-600">endPeriod (volitelně)</span>
                  <input
                    className="w-full h-9 px-2 text-xs font-mono border border-border rounded-lg"
                    value={endPeriod}
                    onChange={(e) => setEndPeriod(e.target.value)}
                  />
                </label>
              </div>

              <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2 min-w-0 max-w-full">
                <div className="text-[10px] uppercase tracking-wide text-slate-500">Sestavený series key · set_id</div>
                <div className="font-mono text-sm text-teal-900 mt-1 text-technical-wrap" data-testid="bis-wizard-set-id">
                  {bisSetIdPreview}
                </div>
              </div>
              {previewGateHints.length ? (
                <div className="rounded-xl border border-amber-200/90 bg-amber-50/90 px-3 py-2.5 text-[11px] text-amber-950 space-y-1">
                  {previewGateHints.map((h, i) => (
                    <p key={i}>
                      {h}
                    </p>
                  ))}
                </div>
              ) : null}
            </div>
          ) : (
            <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600">
              Žádné dimenze v odpovědi — použijte jiný dataflow nebo zkuste později.
            </div>
          )}

          <div className="flex flex-wrap gap-2 pt-1">
            <button
              type="button"
              className="inline-flex items-center gap-1.5 px-3 h-9 text-xs border border-slate-200 rounded-xl bg-white hover:bg-slate-50 disabled:opacity-50"
              disabled={structLoading || !dimensionsSortedByDsd.length}
              onClick={relaxLastConcreteDimension}
            >
              <Undo2 className="h-3.5 w-3.5" aria-hidden /> Zkusit obecnější kombinaci
            </button>
            <button
              type="button"
              data-testid="bis-wizard-preview"
              className="btn-mint inline-flex items-center gap-1.5 px-3 h-9 text-xs"
              disabled={structLoading || !structure?.dimensions?.length || previewLoading}
              aria-busy={previewLoading ? "true" : undefined}
              onClick={runPreview}
            >
              {previewLoading ? <LoadingSpinner suppressAria size="xs" aria-label="" /> : <Eye className="h-3.5 w-3.5" />}
              Zobrazit náhled
            </button>
          </div>

          {(previewLoading && !preview) || preview?.rows?.length ? (
            <div className="rounded-xl border border-border overflow-hidden">
              <SourcePreview
                compact
                preview={preview}
                loading={previewLoading && !preview?.rows?.length && !previewEmptyNotice}
                onClose={() => {}}
              />
            </div>
          ) : null}
          {!previewLoading && previewEmptyNotice ? (
            <div className="rounded-xl border border-dashed border-amber-200 bg-amber-50/95 px-3 py-3 text-sm text-amber-950">
              {previewEmptyNotice}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/** Vnější shell bez hooků — při zavření se vnitřek odmontuje (stabilní Fast Refresh). */
export default function BisDimensionWizardModal({ open, onClose, flowRef, flowTitle }) {
  if (!open) return null;
  return (
    <BisDimensionWizardModalContent
      onClose={onClose}
      flowRef={flowRef}
      flowTitle={flowTitle}
    />
  );
}
