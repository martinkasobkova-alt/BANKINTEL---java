/**
 * UploadSeriesPanel — inline panel for editing which columns are shown
 * in a user_upload_chart widget (switch single ↔ multi-series after creation).
 *
 * Props:
 *   uploadId      – widget config.user_upload_id
 *   currentConfig – widget.config (to read current x_field, y_fields etc.)
 *   compositeAllowed – boolean from feCompositeCharts.allowed
 *   compositeMessage – message if not allowed
 *   onSave(patchConfig)  – called with the config diff to apply via onWidgetConfigPatch
 *   onClose       – called to dismiss the panel
 */
import React, { useEffect, useState } from "react";
import { X, Loader2, Check } from "lucide-react";
import { toast } from "sonner";
import api from "@/lib/api";

const COLORS = ["#3b82f6", "#f59e0b", "#10b981", "#ef4444", "#8b5cf6", "#ec4899", "#14b8a6", "#f97316"];

export default function UploadSeriesPanel({
  uploadId,
  currentConfig = {},
  compositeAllowed = false,
  compositeMessage = "",
  onSave,
  onClose,
}) {
  const [cols, setCols] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [xField, setXField] = useState(currentConfig.x_field || "");
  const [yFields, setYFields] = useState(() => {
    if (currentConfig.chart_mode === "multi" && Array.isArray(currentConfig.y_fields)) {
      return [...currentConfig.y_fields];
    }
    if (currentConfig.y_field) return [currentConfig.y_field];
    return [];
  });

  useEffect(() => {
    if (!uploadId) {
      setCols([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    api.get(`/me/uploads/${uploadId}/preview`)
      .then(({ data }) => {
        const c = Array.isArray(data?.columns) ? data.columns : [];
        setCols(c);
        if (!xField && c.length) setXField(c[0]);
      })
      .catch(() => setCols([]))
      .finally(() => setLoading(false));
  }, [uploadId]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggleY = (col) => {
    if (col === xField) return;
    setYFields((prev) =>
      prev.includes(col) ? prev.filter((c) => c !== col) : [...prev, col]
    );
  };

  const handleSave = async () => {
    if (yFields.length === 0) {
      toast.error("Vyberte alespoň jeden sloupec Y.");
      return;
    }
    if (yFields.length > 1 && !compositeAllowed) {
      toast.error(compositeMessage || "Složené grafy vyžadují předplatné.");
      return;
    }
    setSaving(true);
    try {
      const patch = {
        x_field: xField,
        chart_mode: yFields.length > 1 ? "multi" : "single",
        y_fields: yFields,
        y_field: yFields[0] || "",
        y_mode: "single",
      };
      await onSave(patch);
      toast.success("Konfigurace sloupců uložena.");
      onClose?.();
    } catch {
      toast.error("Nepodařilo se uložit konfiguraci.");
    } finally {
      setSaving(false);
    }
  };

  const nonXCols = cols.filter((c) => c !== xField);

  return (
    <div className="rounded-xl border border-border/80 bg-card shadow-md p-3 space-y-3 text-sm">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-foreground/80 uppercase tracking-wider">Sloupce grafu</span>
        <button
          type="button"
          onClick={onClose}
          className="h-6 w-6 flex items-center justify-center rounded-full hover:bg-muted/60 text-muted-foreground"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>

      {loading ? (
        <div className="flex items-center gap-2 text-muted-foreground text-xs py-2">
          <Loader2 className="h-4 w-4 animate-spin" /> Načítám sloupce…
        </div>
      ) : cols.length === 0 ? (
        <p className="text-xs text-destructive">Nepodařilo se načíst sloupce souboru.</p>
      ) : (
        <>
          {/* X axis selector */}
          <div>
            <label className="text-[11px] text-muted-foreground block mb-1">Osa X (čas / kategorie)</label>
            <select
              value={xField}
              onChange={(e) => {
                setXField(e.target.value);
                setYFields((prev) => prev.filter((c) => c !== e.target.value));
              }}
              className="w-full h-7 border border-border rounded-md px-2 text-xs bg-card text-foreground"
            >
              {cols.map((c) => (
                <option key={c} value={c}>{c}</option>
              ))}
            </select>
          </div>

          {/* Y axis column checkboxes */}
          <div>
            <div className="text-[11px] text-muted-foreground mb-1">
              Sloupce Y — zaškrtněte řady v grafu
              {!compositeAllowed && (
                <span className="ml-1 text-amber-700">(složené grafy vyžadují předplatné)</span>
              )}
            </div>
            <div className="max-h-44 overflow-y-auto rounded-lg border border-border/60 bg-muted/10 p-2 space-y-1">
              {nonXCols.map((c) => {
                const checked = yFields.includes(c);
                const isExtra = yFields.indexOf(c) > 0;
                return (
                  <label key={c} className="flex items-center gap-2 cursor-pointer group">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleY(c)}
                      disabled={checked && yFields.length === 1}
                    />
                    <span
                      className="w-2.5 h-2.5 rounded-full shrink-0"
                      style={{ background: COLORS[yFields.indexOf(c) % COLORS.length] || "#94a3b8", opacity: checked ? 1 : 0.25 }}
                    />
                    <span className={`text-xs font-mono ${checked ? "text-foreground" : "text-muted-foreground"}`}>
                      {c}
                    </span>
                    {isExtra && !compositeAllowed && (
                      <span className="text-[10px] text-amber-700 ml-auto">předplatné</span>
                    )}
                  </label>
                );
              })}
            </div>
            <p className="text-[10px] text-muted-foreground mt-1">
              {yFields.length === 1 ? "Jednoduchý graf (jedna řada)" : `Složený graf — ${yFields.length} řad`}
            </p>
          </div>

          <button
            type="button"
            onClick={handleSave}
            disabled={saving || yFields.length === 0}
            className="w-full flex items-center justify-center gap-1.5 h-8 rounded-lg bg-primary text-primary-foreground text-xs font-medium disabled:opacity-50 hover:opacity-90 transition-opacity"
          >
            {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
            {saving ? "Ukládám…" : "Uložit"}
          </button>
        </>
      )}
    </div>
  );
}
