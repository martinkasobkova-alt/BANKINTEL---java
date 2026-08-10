import React, { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { formatApiErrorFromAxios } from "@/lib/api";
import { LoadingSpinner } from "@/components/ui/loading";

export const MAX_EXTERNAL_CATALOG_CHART_COMPARE = 8;

const CHART_TYPES = [
  { id: "line", label: "Čára" },
  { id: "bar", label: "Sloupec" },
  { id: "area", label: "Plocha" },
  { id: "dot", label: "Body" },
];

function ExternalCompareRowEditor({ row, defaultGroupField, onChange, onRemove }) {
  const ct = (row.chart_type || "line").toLowerCase();
  const yax = (row.y_axis || "left").toLowerCase() === "right" ? "right" : "left";

  return (
    <div className="rounded-lg border border-border/70 bg-white p-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] font-semibold text-slate-700">Další řada ze stejné sady</span>
        <button type="button" onClick={onRemove} className="text-[11px] text-rose-600 hover:underline shrink-0">
          Odebrat
        </button>
      </div>
      <label className="block text-[10px] text-slate-600 space-y-0.5">
        <span className="font-medium">ID ukazatele (selected_indicator)</span>
        <input
          className="input w-full text-xs font-mono"
          value={row.selected_indicator || ""}
          onChange={(e) => onChange({ selected_indicator: e.target.value })}
          placeholder={defaultGroupField ? "hodnota ve sloupci group_field" : "např. kód ukazatele"}
        />
      </label>
      <label className="block text-[10px] text-slate-600 space-y-0.5">
        <span className="font-medium">Legenda (volitelné)</span>
        <input
          className="input w-full text-xs"
          value={row.name || ""}
          onChange={(e) => onChange({ name: e.target.value })}
          placeholder="Krátký popisek v legendě"
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
 * Modal: `chart_compare_with` pro external_catalog_chart — `catalog`, `set_id`, `selected_indicator`.
 */
export default function ExternalCatalogChartCompareModal({
  open,
  onClose,
  catalog,
  setId,
  mainIndicator,
  mainLabel,
  groupField,
  selectedDimensions = null,
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

  const cat = String(catalog || "").trim().toLowerCase();
  const sid = String(setId || "").trim();
  const mainInd = String(mainIndicator || "").trim();

  useEffect(() => {
    if (!open) return;
    const raw = Array.isArray(initialCompareList) ? initialCompareList : [];
    setRows(
      raw.map((r) => ({
        selected_indicator: String(r?.selected_indicator ?? r?.series_value ?? "").trim(),
        name: String(r?.name ?? r?.label ?? "").trim(),
        set_id: String(r?.set_id ?? "").trim(),
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
      if (prev.length >= MAX_EXTERNAL_CATALOG_CHART_COMPARE) return prev;
      return [
        ...prev,
        {
          selected_indicator: "",
          name: "",
          set_id: "",
          chart_type: "line",
          y_axis: "left",
        },
      ];
    });
  }, []);

  const handleSave = async () => {
    if (!compositeAllowed) return;
    setErr("");
    if (!cat || !sid) {
      setErr("Chybí katalog nebo sada v konfiguraci widgetu.");
      return;
    }
    const cleaned = rows
      .map((r) => {
        const sel = String(r.selected_indicator ?? "").trim();
        if (!sel || sel === mainInd) return null;
        const out = {
          selected_indicator: sel,
          chart_type: ["line", "bar", "area", "dot"].includes(String(r.chart_type || "").toLowerCase())
            ? String(r.chart_type).toLowerCase()
            : "line",
          y_axis: String(r.y_axis || "").toLowerCase() === "right" ? "right" : "left",
        };
        const nm = String(r.name ?? "").trim();
        if (nm) out.name = nm;
        const altSet = String(r.set_id ?? "").trim();
        if (altSet && altSet !== sid) out.set_id = altSet;
        return out;
      })
      .filter(Boolean)
      .slice(0, MAX_EXTERNAL_CATALOG_CHART_COMPARE);
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
      aria-labelledby="ext-catalog-compare-modal-title"
      onClick={(e) => {
        if (e.target === e.currentTarget && !saving) onClose();
      }}
    >
      <div className="w-full max-w-lg rounded-2xl border border-border/80 bg-white shadow-2xl flex flex-col max-h-[min(90vh,720px)]">
        <div className="flex items-start justify-between gap-3 px-4 py-3 border-b border-border/60 shrink-0">
          <div>
            <h2 id="ext-catalog-compare-modal-title" className="text-sm font-semibold text-slate-900">
              Srovnání řad v grafu
            </h2>
            <p className="text-[11px] text-slate-500 mt-0.5 leading-snug">
              Katalog <span className="font-mono">{cat || "—"}</span>
              {sid ? (
                <>
                  {" "}
                  · sada <span className="font-mono">{sid.length > 48 ? `${sid.slice(0, 48)}…` : sid}</span>
                </>
              ) : null}
            </p>
            {mainInd ? (
              <p className="text-[10px] text-slate-500 mt-1">
                Hlavní řada: <span className="font-mono">{mainLabel || mainInd}</span>
                {groupField ? (
                  <>
                    {" "}
                    (<span className="font-mono">{groupField}</span>)
                  </>
                ) : null}
              </p>
            ) : null}
            {selectedDimensions && typeof selectedDimensions === "object" && Object.keys(selectedDimensions).length ? (
              <p className="text-[10px] text-slate-500 mt-1 leading-snug">
                Aktuální dimenze:{" "}
                {Object.entries(selectedDimensions)
                  .filter(([k]) => !["selected_indicator", "selected_indicators"].includes(k))
                  .slice(0, 8)
                  .map(([k, v]) => `${k}=${Array.isArray(v) ? v.join(",") : v}`)
                  .join(" · ")}
              </p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            className="h-8 w-8 inline-flex items-center justify-center rounded-lg border border-border/60 text-slate-500 hover:text-slate-700 shrink-0"
            aria-label="Zavřít"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="overflow-y-auto px-4 py-3 space-y-2 flex-1 min-h-0">
          {!compositeAllowed && compositeMessage ? (
            <p className="text-[11px] text-amber-800 bg-amber-50 border border-amber-200 rounded-lg p-2">
              {compositeMessage}
            </p>
          ) : null}
          {rows.length === 0 ? (
            <p className="text-[11px] text-slate-500 py-2">
              Přidejte další ukazatele ze stejné datové sady — každá řada se zobrazí jako samostatná čára.
            </p>
          ) : (
            rows.map((row, idx) => (
              <ExternalCompareRowEditor
                key={idx}
                row={row}
                defaultGroupField={groupField}
                onChange={(patch) => updateAt(idx, patch)}
                onRemove={() => removeAt(idx)}
              />
            ))
          )}
          {err ? <p className="text-[11px] text-red-700">{err}</p> : null}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 border-t border-border/60 shrink-0">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={addRow}
              disabled={!compositeAllowed || rows.length >= MAX_EXTERNAL_CATALOG_CHART_COMPARE || saving}
              className="text-[11px] text-[hsl(var(--primary))] hover:underline disabled:opacity-50"
            >
              + Přidat řadu
            </button>
            <label className="inline-flex items-center gap-1.5 text-[10px] text-slate-600">
              <span>Hlavní osa Y</span>
              <select
                className="input h-7 text-xs"
                value={primaryYAxis}
                onChange={(e) => setPrimaryYAxis(e.target.value)}
                disabled={saving}
              >
                <option value="left">Vlevo</option>
                <option value="right">Vpravo</option>
              </select>
            </label>
          </div>
          <div className="flex items-center gap-2">
            <button type="button" onClick={onClose} disabled={saving} className="btn-secondary text-xs h-8 px-3">
              Zrušit
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={!compositeAllowed || saving}
              className="btn-primary text-xs h-8 px-3 inline-flex items-center gap-1.5"
            >
              {saving ? <LoadingSpinner size="xs" /> : null}
              Uložit
            </button>
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
}
