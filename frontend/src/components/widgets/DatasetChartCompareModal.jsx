import React, { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { formatApiErrorFromAxios } from "@/lib/api";
import { LoadingSpinner } from "@/components/ui/loading";

/** Max. počet doplňkových řad (stejný limit jako u ARAD). */
export const MAX_DATASET_CHART_COMPARE = 8;

const CHART_TYPES = [
  { id: "line", label: "Čára" },
  { id: "bar", label: "Sloupec" },
  { id: "area", label: "Plocha" },
  { id: "dot", label: "Body" },
];

function DatasetCompareRowEditor({ row, defaultSeriesField, onChange, onRemove }) {
  const ct = (row.chart_type || "line").toLowerCase();
  const yax = (row.y_axis || "left").toLowerCase() === "right" ? "right" : "left";
  const sf = String(row.series_field || "").trim();

  return (
    <div className="rounded-lg border border-border/70 bg-white p-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] font-semibold text-slate-700">Další řada (stejný zdroj)</span>
        <button type="button" onClick={onRemove} className="text-[11px] text-rose-600 hover:underline shrink-0">
          Odebrat
        </button>
      </div>
      <label className="block text-[10px] text-slate-600 space-y-0.5">
        <span className="font-medium">Hodnota dimenze (series_value)</span>
        <input
          className="input w-full text-xs font-mono"
          value={row.series_value || ""}
          onChange={(e) => onChange({ series_value: e.target.value })}
          placeholder="např. CZ, TOTAL, …"
        />
      </label>
      <label className="block text-[10px] text-slate-600 space-y-0.5">
        <span className="font-medium">Legenda (volitelné)</span>
        <input
          className="input w-full text-xs"
          value={row.label || ""}
          onChange={(e) => onChange({ label: e.target.value })}
          placeholder="Krátký popisek v legendě"
        />
      </label>
      <label className="block text-[10px] text-slate-600 space-y-0.5">
        <span className="font-medium">Pole dimenze (volitelné, výchozí z widgetu)</span>
        <input
          className="input w-full text-xs font-mono"
          value={sf}
          onChange={(e) => onChange({ series_field: e.target.value })}
          placeholder={defaultSeriesField || "(stejné jako hlavní řada)"}
        />
      </label>
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
 * Modal: `chart_compare_with` pro dataset widgety (Eurostat, ČSÚ, …) — stejný `source_id`,
 * stejná dimenze (`series_field`), jiné `series_value`.
 */
export default function DatasetChartCompareModal({
  open,
  onClose,
  mainSourceId,
  mainSeriesField,
  mainSeriesValue,
  initialCompareList,
  initialPrimaryYAxis = "left",
  onSave,
  compositeAllowed,
  compositeMessage,
}) {
  const [rows, setRows] = useState([]);
  const [primaryYAxis, setPrimaryYAxis] = useState("left");
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  const msf = String(mainSeriesField || "").trim();
  const mainSidDisp = String(mainSourceId || "").trim();

  useEffect(() => {
    if (!open) return;
    const raw = Array.isArray(initialCompareList) ? initialCompareList : [];
    setRows(
      raw.map((r) => ({
        series_value: String(r?.series_value ?? "").trim(),
        label: String(r?.label ?? "").trim(),
        series_field: String(r?.series_field ?? "").trim(),
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
      if (prev.length >= MAX_DATASET_CHART_COMPARE) return prev;
      return [
        ...prev,
        {
          series_value: "",
          label: "",
          series_field: "",
          chart_type: "line",
          y_axis: "left",
        },
      ];
    });
  }, []);

  const handleSave = async () => {
    if (!compositeAllowed) return;
    setErr("");
    const mainSid = String(mainSourceId || "").trim();
    const mainSv = String(mainSeriesValue ?? "").trim();
    if (!mainSid || !msf || !mainSv) {
      setErr("Chybí source_id nebo výběr řady v hlavním widgetu.");
      return;
    }
    const cleaned = rows
      .map((r) => {
        const sv = String(r.series_value ?? "").trim();
        const sf = String(r.series_field ?? "").trim();
        const out = {
          source_id: mainSid,
          series_value: sv,
          chart_type: ["line", "bar", "area", "dot"].includes(String(r.chart_type || "").toLowerCase())
            ? String(r.chart_type).toLowerCase()
            : "line",
          y_axis: String(r.y_axis || "").toLowerCase() === "right" ? "right" : "left",
        };
        const lab = String(r.label ?? "").trim();
        if (lab) out.label = lab;
        if (sf) out.series_field = sf;
        return out;
      })
      .filter((r) => r.series_value && r.series_value !== mainSv)
      .slice(0, MAX_DATASET_CHART_COMPARE);
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
      aria-labelledby="dataset-compare-modal-title"
      onClick={(e) => {
        if (e.target === e.currentTarget && !saving) onClose();
      }}
    >
      <div
        className="w-full max-w-lg max-h-[90vh] overflow-y-auto rounded-2xl border border-border/80 bg-white shadow-2xl p-4 sm:p-5 space-y-3"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-2">
          <div>
            <h2 id="dataset-compare-modal-title" className="text-base font-semibold text-slate-900">
              Složený graf — další řady
            </h2>
            <p className="text-[11px] text-slate-600 mt-1 leading-snug">
              Přidejte až {MAX_DATASET_CHART_COMPARE} dalších řad ze <strong>stejného zdroje</strong> (Eurostat s
              Eurostatem, ČSÚ s ČSÚ, …). U každé řady zadejte jinou hodnotu dimenze (
              <span className="font-mono">series_value</span>) odpovídající poli{" "}
              <span className="font-mono">series_field</span> z widgetu.
            </p>
            {msf ? (
              <p className="text-[10px] text-slate-500 mt-1 font-mono">
                Zdroj: {mainSidDisp || "—"} · dimenze: {msf} · hlavní hodnota: {String(mainSeriesValue ?? "").trim() || "—"}
              </p>
            ) : null}
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
                <DatasetCompareRowEditor
                  key={`ds-cmp-${idx}`}
                  row={row}
                  defaultSeriesField={msf}
                  onChange={(patch) => updateAt(idx, patch)}
                  onRemove={() => removeAt(idx)}
                />
              ))}
            </div>
            {rows.length < MAX_DATASET_CHART_COMPARE ? (
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
            {err ? <p className="text-[11px] text-rose-700">{err}</p> : null}
            <div className="flex flex-wrap justify-end gap-2 pt-1 border-t border-border/60">
              <button type="button" className="btn-secondary text-xs px-3 h-9" disabled={saving} onClick={onClose}>
                Zrušit
              </button>
              <button
                type="button"
                className="btn-primary text-xs px-3 h-9 inline-flex items-center gap-1.5"
                disabled={saving}
                onClick={() => void handleSave()}
              >
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
