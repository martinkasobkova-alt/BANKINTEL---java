import React, { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { LoadingSpinner } from "@/components/ui/loading";
import AradCompareIndicatorPanel from "@/components/arad/AradCompareIndicatorPanel";

export const MAX_ARAD_CHART_COMPARE = 8;

const CHART_TYPES = [
  { id: "line", label: "Čára" },
  { id: "bar", label: "Sloupec" },
  { id: "area", label: "Plocha" },
  { id: "dot", label: "Body" },
];

function CompareRowEditor({
  row,
  sources,
  mergedIndicators,
  mergedLoading,
  onChange,
  onRemove,
  externalCatalogSetId = "",
  externalCatalogSetName = "",
}) {
  const sourceId = String(row.source_id || "").trim();
  const catalogSetId = String(externalCatalogSetId || "").trim();

  const ct = (row.chart_type || "line").toLowerCase();
  const yax = (row.y_axis || "left").toLowerCase() === "right" ? "right" : "left";

  return (
    <div className="rounded-lg border border-border/70 bg-white p-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] font-semibold text-slate-700">Další řada (ARAD)</span>
        <button type="button" onClick={onRemove} className="text-[11px] text-rose-600 hover:underline shrink-0">
          Odebrat
        </button>
      </div>
      {!catalogSetId ? (
        <label className="block text-[10px] text-slate-600 space-y-0.5">
          <span className="font-medium">Zdroj</span>
          <select
            className="input w-full text-xs"
            value={sourceId}
            onChange={(e) => {
              const newSid = String(e.target.value || "").trim();
              const oldSid = sourceId;
              const iid = String(row.indicator_id || "").trim();
              if (newSid === oldSid) return;
              if (!newSid) {
                onChange({ source_id: "" });
                return;
              }
              if (iid) {
                const stillOk = mergedIndicators.some(
                  (x) =>
                    String(x.source_id || "").trim() === newSid && String(x.indicator_id || "").trim() === iid
                );
                if (stillOk) {
                  onChange({ source_id: newSid, indicator_id: iid });
                  return;
                }
              }
              onChange({ source_id: newSid, indicator_id: "" });
            }}
          >
            <option value="">Všechny napojené ARAD zdroje (celá sada ukazatelů)</option>
            {sources.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <p className="text-[9px] text-slate-500 leading-snug">
            Každý ARAD zdroj v aplikaci odpovídá jedné sestavě (set_id) — v seznamu jsou sloučeny všechny takové sady.
            Zužte výběrem konkrétního zdroje.
          </p>
        </label>
      ) : null}
      <AradCompareIndicatorPanel
        heading="Indikátor"
        row={row}
        mergedIndicators={mergedIndicators}
        mergedLoading={mergedLoading}
        onChange={onChange}
        inputClassName="input w-full text-xs"
        listMaxHeight={320}
        treeMaxHeight={220}
        fixedSetId={catalogSetId}
        fixedSetName={externalCatalogSetName}
      />
      <div className="grid grid-cols-2 gap-2">
        <label className="text-[10px] text-slate-600 space-y-0.5">
          <span className="font-medium">Typ v grafu</span>
          <select
            className="input w-full text-xs"
            value={CHART_TYPES.some((c) => c.id === ct) ? ct : "line"}
            onChange={(e) => onChange({ chart_type: e.target.value })}
          >
            {CHART_TYPES.map((c) => (
              <option key={c.id} value={c.id}>
                {c.label}
              </option>
            ))}
          </select>
        </label>
        <label className="text-[10px] text-slate-600 space-y-0.5">
          <span className="font-medium">Osa Y</span>
          <select className="input w-full text-xs" value={yax} onChange={(e) => onChange({ y_axis: e.target.value })}>
            <option value="left">Vlevo</option>
            <option value="right">Vpravo</option>
          </select>
        </label>
      </div>
    </div>
  );
}

/**
 * Modal: úprava `chart_compare_with` přímo z widgetu (Můj dashboard / admin přehled).
 * Jen ARAD — stejný model jako „Porovnání v grafu“ v editoru stránky.
 */
export default function AradChartCompareModal({
  open,
  onClose,
  mainSourceId,
  mainIndicatorId,
  initialCompareList,
  initialPrimaryYAxis = "left",
  onSave,
  compositeAllowed,
  compositeMessage,
  externalCatalogSetId = "",
  externalCatalogSetName = "",
}) {
  const [sources, setSources] = useState([]);
  const [mergedAradIndicators, setMergedAradIndicators] = useState([]);
  const [mergedLoading, setMergedLoading] = useState(false);
  const [rows, setRows] = useState([]);
  const [primaryYAxis, setPrimaryYAxis] = useState("left");
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/sources");
        if (cancelled) return;
        const list = Array.isArray(data) ? data : [];
        setSources(list.filter((s) => String(s?.source_type || "").toLowerCase() === "arad"));
      } catch {
        if (!cancelled) setSources([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open]);

  useEffect(() => {
    if (!open || sources.length === 0) {
      setMergedAradIndicators([]);
      setMergedLoading(false);
      return;
    }
    let cancelled = false;
    (async () => {
      setMergedLoading(true);
      try {
        const packs = await Promise.all(
          sources.map((s) =>
            api
              .get(`/sources/${s.id}/arad/indicators`)
              .then((r) => ({
                id: s.id,
                name: (s.name || s.id || "").trim(),
                rows: Array.isArray(r.data) ? r.data : [],
              }))
              .catch(() => ({
                id: s.id,
                name: (s.name || s.id || "").trim(),
                rows: [],
              }))
          )
        );
        if (cancelled) return;
        const out = [];
        const seen = new Set();
        for (const { id: sid, name: sname, rows: packRows } of packs) {
          for (const row of packRows) {
            const iid = String(row.indicator_id || "").trim();
            if (!iid) continue;
            const k = `${sid}\0${iid}`;
            if (seen.has(k)) continue;
            seen.add(k);
            out.push({
              ...row,
              source_id: sid,
              indicator_id: iid,
              _aradSourceLabel: sname,
            });
          }
        }
        out.sort((a, b) => {
          const na = (a.name || "").toLocaleLowerCase("cs");
          const nb = (b.name || "").toLocaleLowerCase("cs");
          if (na !== nb) return na.localeCompare(nb, "cs");
          return String(a.indicator_id || "").localeCompare(String(b.indicator_id || ""), "cs");
        });
        setMergedAradIndicators(out);
      } finally {
        if (!cancelled) setMergedLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open, sources]);

  const catalogSetId = String(externalCatalogSetId || "").trim();

  useEffect(() => {
    if (!open) return;
    const raw = Array.isArray(initialCompareList) ? initialCompareList : [];
    setRows(
      raw.map((r) => ({
        source_id: String(r?.source_id || r?.set_id || "").trim(),
        indicator_id: String(r?.indicator_id || r?.selected_indicator || "").trim(),
        label: String(r?.label || r?.name || "").trim(),
        chart_type: String(r?.chart_type || "line").toLowerCase(),
        y_axis: String(r?.y_axis || "left").toLowerCase() === "right" ? "right" : "left",
      }))
    );
    setPrimaryYAxis(String(initialPrimaryYAxis || "left").toLowerCase() === "right" ? "right" : "left");
    setErr("");
  }, [open, initialCompareList, initialPrimaryYAxis]);

  const updateAt = useCallback((idx, patch) => {
    setRows((prev) => prev.map((row, j) => (j === idx ? { ...row, ...patch } : row)));
  }, []);

  const removeAt = useCallback((idx) => {
    setRows((prev) => prev.filter((_, j) => j !== idx));
  }, []);

  const addRow = useCallback(() => {
    setRows((prev) => {
      if (prev.length >= MAX_ARAD_CHART_COMPARE) return prev;
      return [
        ...prev,
        {
          source_id: "",
          indicator_id: "",
          chart_type: "line",
          y_axis: "left",
        },
      ];
    });
  }, []);

  const handleSave = async () => {
    if (!compositeAllowed) return;
    setErr("");
    const ms = String(mainSourceId || "").trim();
    const mi = String(mainIndicatorId || "").trim();

    if (catalogSetId) {
      const cleaned = rows
        .map((r) => {
          const iid = String(r.indicator_id || "").trim();
          if (!iid || iid === mi) return null;
          const out = {
            indicator_id: iid,
            chart_type: ["line", "bar", "area", "dot"].includes(String(r.chart_type || "").toLowerCase())
              ? String(r.chart_type).toLowerCase()
              : "line",
            y_axis: String(r.y_axis || "").toLowerCase() === "right" ? "right" : "left",
          };
          const lab = String(r.label || "").trim();
          if (lab) out.label = lab;
          return out;
        })
        .filter(Boolean)
        .slice(0, MAX_ARAD_CHART_COMPARE);
      setSaving(true);
      try {
        await onSave({
          chart_compare_with: cleaned,
          primary_y_axis: primaryYAxis === "right" ? "right" : "left",
        });
        onClose();
      } catch (e) {
        setErr(formatApiErrorFromAxios(e) || "Uložení se nepodařilo.");
      } finally {
        setSaving(false);
      }
      return;
    }

    const normalized = rows.map((r) => {
      let sid = String(r.source_id || "").trim();
      const iid = String(r.indicator_id || "").trim();
      if (iid && !sid) {
        const hit = mergedAradIndicators.find((x) => String(x.indicator_id || "").trim() === iid);
        if (hit?.source_id) sid = String(hit.source_id).trim();
      }
      return { ...r, source_id: sid, indicator_id: iid };
    });

    const cleaned = normalized
      .map((r) => ({
        source_id: String(r.source_id || "").trim(),
        indicator_id: String(r.indicator_id || "").trim(),
        chart_type: ["line", "bar", "area", "dot"].includes(String(r.chart_type || "").toLowerCase())
          ? String(r.chart_type).toLowerCase()
          : "line",
        y_axis: String(r.y_axis || "").toLowerCase() === "right" ? "right" : "left",
      }))
      .filter((r) => r.source_id && r.indicator_id)
      .filter((r) => !(r.source_id === ms && r.indicator_id === mi))
      .slice(0, MAX_ARAD_CHART_COMPARE);

    const wantedExtra = normalized.some((r) => String(r.indicator_id || "").trim());
    const stillMissingSource = normalized.some(
      (r) => String(r.indicator_id || "").trim() && !String(r.source_id || "").trim()
    );
    if (wantedExtra && cleaned.length === 0 && stillMissingSource) {
      setErr(
        "U vybraného ukazatele chybí UUID zdroje v aplikaci. Přidejte příslušnou ARAD sestavu mezi zdroje v katalogu ARAD, nebo vyberte ukazatel v „Rychlém seznamu“ po synchronizaci metadat zdroje."
      );
      return;
    }

    setSaving(true);
    try {
      await onSave({
        chart_compare_with: cleaned,
        primary_y_axis: primaryYAxis === "right" ? "right" : "left",
      });
      onClose();
    } catch (e) {
      setErr(formatApiErrorFromAxios(e) || "Uložení se nepodařilo.");
    } finally {
      setSaving(false);
    }
  };

  if (!open) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-[260] flex items-end justify-center sm:items-center p-3 sm:p-6 bg-slate-900/45 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="arad-compare-modal-title"
      onClick={(e) => {
        if (e.target === e.currentTarget && !saving) onClose();
      }}
    >
      <div
        className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-2xl border border-border/80 bg-white shadow-2xl p-4 sm:p-5 space-y-3"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-2">
          <div>
            <h2 id="arad-compare-modal-title" className="text-base font-semibold text-slate-900">
              Složený graf — další řady
            </h2>
            <p className="text-[11px] text-slate-600 mt-1 leading-snug">
              {catalogSetId
                ? `Přidejte až ${MAX_ARAD_CHART_COMPARE} dalších ukazatelů ze stejné ARAD sestavy (set_id ${catalogSetId}). Data se načtou přímo z katalogu ČNB.`
                : `Přidejte až ${MAX_ARAD_CHART_COMPARE} dalších ARAD indikátorů do stejného grafu (společná časová osa). Výběr zobrazuje ukazatele ze všech vašich ARAD zdrojů (každý zdroj = jedna sestava v ČNB). U každé řady lze zvolit typ (čára / sloupec / plocha) a osu Y vlevo nebo vpravo.`}
            </p>
          </div>
          <button
            type="button"
            onClick={() => !saving && onClose()}
            className="h-8 w-8 shrink-0 rounded-lg border border-border/70 bg-white text-slate-600 hover:bg-slate-50 grid place-items-center"
            aria-label="Zavřít"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {!compositeAllowed ? (
          <p className="text-[11px] text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
            {compositeMessage || "Kombinace více řad je dostupná s předplatným."}
          </p>
        ) : (
          <>
            <div className="space-y-2">
              {rows.map((row, idx) => (
                <CompareRowEditor
                  key={`cmp-modal-row-${idx}`}
                  row={row}
                  sources={sources}
                  mergedIndicators={mergedAradIndicators}
                  mergedLoading={mergedLoading}
                  onChange={(patch) => updateAt(idx, patch)}
                  onRemove={() => removeAt(idx)}
                  externalCatalogSetId={catalogSetId}
                  externalCatalogSetName={externalCatalogSetName}
                />
              ))}
            </div>
            {rows.length < MAX_ARAD_CHART_COMPARE ? (
              <button
                type="button"
                onClick={addRow}
                className="w-full h-9 text-xs font-medium rounded-lg border border-violet-200 bg-violet-50/50 text-violet-950 hover:bg-violet-50"
              >
                + Přidat řadu
              </button>
            ) : null}
            <label className="flex flex-col sm:flex-row sm:items-center gap-1.5 sm:gap-3 text-[11px] text-slate-700">
              <span className="font-medium shrink-0">Hlavní řada (widget) — osa Y</span>
              <select
                className="input text-xs flex-1 min-w-0 max-w-xs"
                value={primaryYAxis}
                onChange={(e) => setPrimaryYAxis(e.target.value)}
              >
                <option value="left">Vlevo</option>
                <option value="right">Vpravo</option>
              </select>
            </label>
            <p className="text-[10px] text-slate-500">
              Hlavní indikátor zůstává z nastavení widgetu; zde jen volíte osu Y. Doplňkové řady a jejich typ nastavte
              výše.
            </p>
            {err ? <p className="text-[11px] text-rose-700">{err}</p> : null}
            <div className="flex flex-wrap justify-end gap-2 pt-1 border-t border-border/60">
              <button type="button" className="btn-secondary text-xs px-3 h-9" disabled={saving} onClick={onClose}>
                Zrušit
              </button>
              <button type="button" className="btn-primary text-xs px-3 h-9 inline-flex items-center gap-1.5" disabled={saving} onClick={() => void handleSave()}>
                {saving ? <LoadingSpinner suppressAria size="xs" aria-label="" /> : null}
                Uložit a přenačíst graf
              </button>
            </div>
          </>
        )}
      </div>
    </div>,
    document.body
  );
}
