import React, { useEffect, useMemo, useState } from "react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { toast } from "sonner";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";

const OPS = [
  { value: "multi", label: "Složený graf (A + B, bez výpočtu)" },
  { value: "ratio", label: "Poměr A ÷ B" },
  { value: "diff", label: "Rozdíl A − B" },
  { value: "index_vs_b_pct", label: "Index A vůči B (A ÷ B × 100)" },
  { value: "yoy_pct_auto", label: "YoY % (řada A)" },
  { value: "corr_pearson", label: "Pearsonova korelace A a B" },
];

function emptyRef() {
  return { source_id: "", indicator_id: "", saved_series_id: "", x_field: "", y_field: "", name: "" };
}

function refFromSaved(id, name) {
  return { source_id: "", indicator_id: "", saved_series_id: id, x_field: "", y_field: "", name: name || "" };
}

export default function CompareWithSavedModal({ compareLeft, onClose, onCreated }) {
  const { allowed: canComposite, ready: compositeReady } = useFeatureAccess("composite_charts");
  const [seriesList, setSeriesList] = useState([]);
  const [bId, setBId] = useState("");
  const [operation, setOperation] = useState("ratio");
  const [name, setName] = useState("Porovnání");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let c = false;
    (async () => {
      try {
        const { data } = await api.get("/my-series");
        if (!c) setSeriesList(Array.isArray(data) ? data : []);
      } catch {
        if (!c) setSeriesList([]);
      }
    })();
    return () => {
      c = true;
    };
  }, []);

  const leftRef = useMemo(() => {
    if (!compareLeft) return emptyRef();
    if (compareLeft.mode === "saved") {
      return refFromSaved(compareLeft.saved_series_id, compareLeft.label);
    }
    const r = compareLeft.ref || {};
    return {
      source_id: r.source_id || "",
      indicator_id: r.indicator_id || "",
      saved_series_id: r.saved_series_id || "",
      x_field: r.x_field || "",
      y_field: r.y_field || "",
      name: r.name || compareLeft.label || "",
    };
  }, [compareLeft]);

  const filteredBOptions = useMemo(() => {
    const sid = compareLeft?.mode === "saved" ? compareLeft.saved_series_id : null;
    return seriesList.filter((s) => !sid || s.id !== sid);
  }, [seriesList, compareLeft]);

  const submit = async () => {
    if (operation !== "yoy_pct_auto" && !bId) {
      toast.error("Vyberte řadu B z vaší knihovny.");
      return;
    }
    if (operation === "multi" && compositeReady && !canComposite) {
      toast.error("Složený graf není ve vašem plánu k dispozici.");
      return;
    }
    const baseLeft = { ...leftRef };
    let payload;
    const right =
      operation === "yoy_pct_auto"
        ? emptyRef()
        : refFromSaved(bId, filteredBOptions.find((x) => x.id === bId)?.title || "");
    if (operation === "multi") {
      payload = {
        name: name.trim() || "Složený graf",
        operation: "multi",
        left: baseLeft,
        right,
        series: [
          { ...baseLeft, chart_type: "line" },
          { ...right, chart_type: "line" },
        ],
        description: "",
        unit: "",
        options: {},
      };
    } else if (operation === "yoy_pct_auto") {
      payload = {
        name: name.trim() || "YoY",
        operation: "yoy_pct_auto",
        left: baseLeft,
        right: emptyRef(),
        series: [],
        description: "",
        unit: "",
        options: {},
      };
    } else {
      payload = {
        name: name.trim() || "Výpočet",
        operation,
        left: baseLeft,
        right,
        series: [],
        description: "",
        unit: "",
        options: {},
      };
    }
    setBusy(true);
    try {
      await api.post("/me/computed", payload);
      toast.success("Výpočet byl uložen. Přidejte ho jako widget na Můj dashboard.");
      onCreated?.();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Uložení výpočtu selhalo.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[90] flex items-center justify-center p-4 bg-black/50" role="dialog" aria-modal="true">
      <div className="bg-card rounded-xl shadow-xl max-w-md w-full p-4 border border-border/80 space-y-3">
        <h4 className="text-sm font-semibold">Porovnat s uloženou řadou</h4>
        <p className="text-[11px] text-muted-foreground leading-relaxed">
          A: <strong>{compareLeft?.label || "aktuální řada"}</strong>. Vyberte B z knihovny a typ výstupu.
        </p>
        <label className="block text-[11px] text-muted-foreground">
          Název uloženého výpočtu
          <input
            className="mt-0.5 w-full border rounded-md px-2 py-1.5 text-xs"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        {operation !== "yoy_pct_auto" ? (
          <label className="block text-[11px] text-muted-foreground">
            Řada B (z Moje datové řady)
            <select className="mt-0.5 w-full border rounded-md px-2 py-1.5 text-xs" value={bId} onChange={(e) => setBId(e.target.value)}>
              <option value="">— vyberte —</option>
              {filteredBOptions.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.title} ({s.source || s.source_type})
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <label className="block text-[11px] text-muted-foreground">
          Režim
          <select className="mt-0.5 w-full border rounded-md px-2 py-1.5 text-xs" value={operation} onChange={(e) => setOperation(e.target.value)}>
            {OPS.map((o) => (
              <option key={o.value} value={o.value} disabled={o.value === "multi" && compositeReady && !canComposite}>
                {o.label}
                {o.value === "multi" && compositeReady && !canComposite ? " (uzamčeno)" : ""}
              </option>
            ))}
          </select>
        </label>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="text-xs px-3 py-1.5 rounded-md border border-border/80" onClick={onClose} disabled={busy}>
            Zrušit
          </button>
          <button
            type="button"
            className="text-xs px-3 py-1.5 rounded-md text-white bg-[hsl(var(--primary))] disabled:opacity-50"
            onClick={submit}
            disabled={busy}
          >
            {busy ? "Ukládám…" : "Vytvořit výpočet"}
          </button>
        </div>
      </div>
    </div>
  );
}
