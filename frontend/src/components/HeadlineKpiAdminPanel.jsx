/**
 * HeadlineKpiAdminPanel — slide-in panel for adding/removing headline KPI items.
 *
 * Props:
 *   mode         – "homepage" | "section"
 *   slug         – section slug (used only when mode === "section")
 *   kpis         – current kpi list (raw config objects from backend)
 *   onSaved      – called after successful save with the new kpis array
 *   onClose      – called when the panel should be dismissed
 */
import React, { useState } from "react";
import { X, Loader2, Plus, Pencil, Check, RefreshCw, Settings2 } from "lucide-react";

const COMPARISON_OPTIONS = [
  { value: "prev", label: "Předchozí datový bod" },
  { value: "mom",  label: "Před měsícem (MoM)" },
  { value: "qoq",  label: "Před čtvrtletím (QoQ)" },
  { value: "yoy",  label: "Před rokem (YoY)" },
];
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import HeadlineKpiPicker from "@/components/HeadlineKpiPicker";

export default function HeadlineKpiAdminPanel({ mode, slug, kpis = [], onSaved, onClose }) {
  const { user } = useAuth();
  const [saving,        setSaving]        = useState(false);
  const [addingNew,     setAddingNew]     = useState(false);
  const [pendingTitle,  setPendingTitle]  = useState("");
  const [editingId,     setEditingId]     = useState(null);   // inline title edit
  const [editTitle,     setEditTitle]     = useState("");
  const [replacingId,   setReplacingId]   = useState(null);   // full indicator replace via picker
  const [settingsId,    setSettingsId]    = useState(null);   // per-KPI settings open

  const saveKpis = async (newList) => {
    setSaving(true);
    try {
      const url = mode === "homepage" ? "/homepage/kpis" : `/sections/${slug}/kpis`;
      const { data } = await api.put(url, { kpis: newList });
      onSaved?.(data.kpis || newList);
      toast.success("KPI ukazatele uloženy.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se uložit KPI.");
    } finally {
      setSaving(false);
    }
  };

  const handleRemove = (id) => saveKpis(kpis.filter((k) => k.id !== id));

  const startEditTitle = (k) => {
    setEditingId(k.id);
    setEditTitle(k.title || "");
  };

  const commitEditTitle = () => {
    if (!editingId) return;
    const newList = kpis.map((k) =>
      k.id === editingId ? { ...k, title: editTitle.trim() || k.title } : k
    );
    setEditingId(null);
    saveKpis(newList);
  };

  const enrichConfig = (type, config) => {
    const c = { ...(config || {}) };
    if (type === "user_upload_chart" && user?.id) c._kpi_user_id = user.id;
    return c;
  };

  // Called when adding a brand-new KPI from the picker
  const handlePickerSelect = ({ title, type, config }) => {
    const newKpi = {
      id: `kpi-${Math.random().toString(36).slice(2, 9)}`,
      title: (pendingTitle.trim() || title || "").trim() || "KPI",
      type,
      config: enrichConfig(type, config),
    };
    saveKpis([...kpis, newKpi]);
    setAddingNew(false);
    setPendingTitle("");
  };

  /** Patch a config field (decimal_places / comparison_type) for an existing KPI */
  const handleSettingChange = (id, field, value) => {
    const newList = kpis.map((k) =>
      k.id === id ? { ...k, config: { ...(k.config || {}), [field]: value } } : k
    );
    saveKpis(newList);
  };

  // Called when replacing indicator on an existing KPI row
  const handleReplaceSelect = ({ title, type, config }) => {
    const newList = kpis.map((k) =>
      k.id === replacingId
        ? { ...k, type, config: enrichConfig(type, config), title: k.title || title }
        : k
    );
    setReplacingId(null);
    saveKpis(newList);
  };

  const typeLabel = (type) => {
    if (!type) return "";
    if (type === "arad_view")           return "ARAD";
    if (type === "user_upload_chart")   return "Moje data";
    if (type === "computed_view")       return "Výpočet";
    if (type.endsWith("_view"))         return type.replace("_view", "").toUpperCase();
    return type;
  };

  return (
    <div className="rounded-2xl border border-border/80 bg-card shadow-lg p-4 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-foreground tracking-wide">
          Headline KPI ukazatele
        </h3>
        <button
          type="button"
          onClick={onClose}
          className="h-7 w-7 flex items-center justify-center rounded-full hover:bg-muted/60 text-muted-foreground transition-colors"
          aria-label="Zavřít"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Current KPI list */}
      {kpis.length > 0 && (
        <div className="space-y-2">
          {kpis.map((k) => (
            <div key={k.id} className="rounded-lg border border-border/50 bg-muted/20 overflow-hidden">
              {/* Row header */}
              <div className="flex items-center justify-between gap-2 px-3 py-2">
                <div className="min-w-0 flex-1">
                  {editingId === k.id ? (
                    <div className="flex items-center gap-1">
                      <input
                        autoFocus
                        type="text"
                        value={editTitle}
                        onChange={(e) => setEditTitle(e.target.value)}
                        onKeyDown={(e) => { if (e.key === "Enter") commitEditTitle(); if (e.key === "Escape") setEditingId(null); }}
                        className="flex-1 h-6 border border-primary rounded px-1.5 text-xs bg-card text-foreground focus:outline-none"
                      />
                      <button type="button" onClick={commitEditTitle}
                        className="h-5 w-5 flex items-center justify-center rounded text-primary hover:bg-primary/10">
                        <Check className="h-3 w-3" />
                      </button>
                      <button type="button" onClick={() => setEditingId(null)}
                        className="h-5 w-5 flex items-center justify-center rounded text-muted-foreground hover:bg-muted/60">
                        <X className="h-3 w-3" />
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-1.5">
                      <p className="text-sm font-medium text-foreground truncate">{k.title || "—"}</p>
                      <button type="button" onClick={() => startEditTitle(k)}
                        className="h-4 w-4 flex items-center justify-center rounded text-muted-foreground hover:text-foreground opacity-50 hover:opacity-100 transition-opacity shrink-0"
                        title="Přejmenovat">
                        <Pencil className="h-2.5 w-2.5" />
                      </button>
                    </div>
                  )}
                  <p className="text-[11px] text-muted-foreground mt-0.5">{typeLabel(k.type)}</p>
                </div>

                <div className="flex items-center gap-1 shrink-0">
                  {/* Settings (decimal places + comparison) */}
                  <button
                    type="button"
                    disabled={saving}
                    onClick={() => { setSettingsId(settingsId === k.id ? null : k.id); setReplacingId(null); setAddingNew(false); }}
                    title="Nastavení zobrazení"
                    className={[
                      "h-6 w-6 flex items-center justify-center rounded-full transition-colors disabled:opacity-40",
                      settingsId === k.id
                        ? "bg-primary/15 text-primary"
                        : "hover:bg-muted/60 text-muted-foreground hover:text-foreground",
                    ].join(" ")}
                  >
                    <Settings2 className="h-3 w-3" />
                  </button>
                  {/* Toggle replace-picker for this row */}
                  <button
                    type="button"
                    disabled={saving}
                    onClick={() => { setReplacingId(replacingId === k.id ? null : k.id); setSettingsId(null); setAddingNew(false); }}
                    title="Změnit datový zdroj ukazatele"
                    className={[
                      "h-6 w-6 flex items-center justify-center rounded-full transition-colors disabled:opacity-40",
                      replacingId === k.id
                        ? "bg-primary/15 text-primary"
                        : "hover:bg-muted/60 text-muted-foreground hover:text-foreground",
                    ].join(" ")}
                  >
                    <RefreshCw className="h-3 w-3" />
                  </button>
                  <button
                    type="button"
                    disabled={saving}
                    onClick={() => handleRemove(k.id)}
                    title="Odebrat KPI"
                    className="h-6 w-6 flex items-center justify-center rounded-full hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors disabled:opacity-40"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>

              {/* Inline settings panel */}
              {settingsId === k.id && (
                <div className="border-t border-border/40 bg-card px-3 pt-2 pb-3 space-y-3">
                  <p className="text-[11px] font-semibold text-muted-foreground uppercase tracking-wide">
                    Nastavení zobrazení
                  </p>
                  <div className="flex gap-3 flex-wrap">
                    <div className="flex-1 min-w-[120px]">
                      <label className="text-[11px] text-muted-foreground mb-1 block">
                        Desetinná místa
                      </label>
                      <select
                        value={k.config?.decimal_places ?? ""}
                        onChange={(e) => handleSettingChange(k.id, "decimal_places", e.target.value === "" ? null : Number(e.target.value))}
                        disabled={saving}
                        className="w-full h-7 border border-border rounded-md px-2 text-xs bg-card text-foreground focus:outline-none"
                      >
                        <option value="">Auto</option>
                        <option value="0">0 (celá čísla)</option>
                        <option value="1">1</option>
                        <option value="2">2</option>
                        <option value="3">3</option>
                        <option value="4">4</option>
                      </select>
                    </div>
                    <div className="flex-1 min-w-[160px]">
                      <label className="text-[11px] text-muted-foreground mb-1 block">
                        Porovnat s (změna)
                      </label>
                      <select
                        value={k.config?.comparison_type || "prev"}
                        onChange={(e) => handleSettingChange(k.id, "comparison_type", e.target.value)}
                        disabled={saving}
                        className="w-full h-7 border border-border rounded-md px-2 text-xs bg-card text-foreground focus:outline-none"
                      >
                        {COMPARISON_OPTIONS.map((o) => (
                          <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                      </select>
                    </div>
                  </div>
                </div>
              )}

              {/* Inline picker for replacing indicator */}
              {replacingId === k.id && (
                <div className="border-t border-border/40 bg-card px-3 pt-2 pb-3 space-y-2">
                  <p className="text-[11px] text-muted-foreground">
                    Vyberte nový ukazatel — nahradí stávající zdroj dat:
                  </p>
                  <HeadlineKpiPicker onSelect={handleReplaceSelect} disabled={saving} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {kpis.length === 0 && !addingNew && (
        <p className="text-xs text-muted-foreground text-center py-2">
          Zatím nejsou přidány žádné KPI ukazatele.
        </p>
      )}

      {/* Add new */}
      {addingNew ? (
        <div className="space-y-3 border border-dashed border-primary/40 rounded-xl p-3 bg-primary/5">
          <div className="flex items-center gap-2">
            <div className="flex-1">
              <label className="text-[11px] text-muted-foreground mb-1 block">
                Zobrazovaný název (nepovinný — doplní se automaticky)
              </label>
              <input
                type="text"
                value={pendingTitle}
                onChange={(e) => setPendingTitle(e.target.value)}
                placeholder="Např. HDP YoY, Inflace, …"
                className="w-full h-8 border border-border rounded-lg px-2.5 text-sm bg-card text-foreground focus:outline-none focus:ring-1 focus:ring-primary/50"
              />
            </div>
            <button
              type="button"
              onClick={() => { setAddingNew(false); setPendingTitle(""); }}
              className="h-7 w-7 flex items-center justify-center rounded-full hover:bg-muted/60 text-muted-foreground mt-5 shrink-0"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>

          <p className="text-[11px] text-muted-foreground">
            Vyberte datovou řadu — KPI zobrazí poslední dostupnou hodnotu:
          </p>

          <HeadlineKpiPicker onSelect={handlePickerSelect} disabled={saving} />
        </div>
      ) : (
        <button
          type="button"
          onClick={() => { setAddingNew(true); setReplacingId(null); }}
          disabled={saving}
          className="w-full flex items-center justify-center gap-1.5 h-9 rounded-xl border border-dashed border-primary/50 text-primary text-sm hover:bg-primary/5 transition-colors disabled:opacity-40"
        >
          {saving ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Plus className="h-4 w-4" />
          )}
          {saving ? "Ukládám…" : "Přidat ukazatel"}
        </button>
      )}
    </div>
  );
}
