import React, { useCallback, useEffect, useState } from "react";
import api, { formatApiErrorFromAxios } from "@/lib/api";

/**
 * Konfigurace widgetu rss_monitoring ve Správě dat / sekcích (shodné klíče jako osobní dashboard).
 */
export default function RssMonitoringConfigEditor({ cfg, setConfig }) {
  const [feeds, setFeeds] = useState([]);
  const [loadErr, setLoadErr] = useState("");

  const selected = Array.isArray(cfg.selected_feed_ids) ? cfg.selected_feed_ids : [];
  const itemLimit = Number(cfg.item_limit) > 0 ? Number(cfg.item_limit) : 15;
  const days = cfg.days != null && cfg.days !== "" ? String(cfg.days) : "";
  const q = cfg.q != null ? String(cfg.q) : "";
  const categoriesRaw = Array.isArray(cfg.categories) ? cfg.categories.join(", ") : "";

  const loadFeeds = useCallback(async () => {
    setLoadErr("");
    try {
      const { data } = await api.get("/rss/feeds");
      setFeeds(Array.isArray(data) ? data : []);
    } catch (e) {
      setFeeds([]);
      setLoadErr(
        formatApiErrorFromAxios(e) ||
          "Nelze načíst seznam RSS zdrojů (je potřeba být přihlášen a mít oprávnění rss_monitoring)."
      );
    }
  }, []);

  useEffect(() => {
    loadFeeds();
  }, [loadFeeds]);

  const toggleId = (id, on) => {
    const next = on ? [...selected, id] : selected.filter((x) => x !== id);
    setConfig({ selected_feed_ids: next });
  };

  return (
    <div className="space-y-3 rounded-xl border border-sky-100/90 bg-sky-50/50 px-3 py-3 text-sm">
      <p className="text-xs text-slate-700 leading-relaxed">
        Zobrazí novinky z vybraných feedů (prázdný výběr = všechny dostupné). Na veřejné stránce uvidí data jen uživatelé s funkcí{" "}
        <strong>rss_monitoring</strong>; ostatním se zobrazí upozornění.
      </p>
      {loadErr ? <div className="text-xs text-rose-700">{loadErr}</div> : null}
      <div>
        <div className="text-[11px] font-medium text-slate-600 mb-1">Zdroje</div>
        <div className="max-h-48 overflow-y-auto rounded-lg border border-slate-200/80 bg-white p-2 space-y-1.5">
          {feeds.length === 0 && !loadErr ? (
            <span className="text-xs text-slate-500">Žádné feedy — přidejte je v RSS monitoring (admin) nebo na Mém dashboardu.</span>
          ) : (
            feeds.map((f) => (
              <label key={f.id} className="flex items-center gap-2 text-xs cursor-pointer">
                <input
                  type="checkbox"
                  checked={selected.includes(f.id)}
                  onChange={(e) => toggleId(f.id, e.target.checked)}
                />
                <span className="truncate" title={f.url || ""}>
                  <span className="text-slate-400">{f.scope === "global" ? "[G] " : "[V] "}</span>
                  {f.name}
                </span>
              </label>
            ))
          )}
        </div>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
        <label className="block text-[11px] text-slate-600">
          Max. položek (1–50)
          <input
            type="number"
            min={1}
            max={50}
            className="input mt-0.5 w-full"
            value={itemLimit}
            onChange={(e) => setConfig({ item_limit: Math.min(50, Math.max(1, Number(e.target.value) || 15)) })}
          />
        </label>
        <label className="block text-[11px] text-slate-600">
          Posledních X dní (volitelné)
          <input
            type="number"
            min={1}
            className="input mt-0.5 w-full"
            placeholder="např. 30"
            value={days}
            onChange={(e) => {
              const v = e.target.value.trim();
              setConfig({ days: v === "" ? null : Math.max(1, Number(v) || 1) });
            }}
          />
        </label>
        <label className="block text-[11px] text-slate-600 sm:col-span-1">
          Klíčová slova (nadpis / souhrn)
          <input className="input mt-0.5 w-full" value={q} onChange={(e) => setConfig({ q: e.target.value })} placeholder="např. inflace" />
        </label>
      </div>
      <label className="block text-[11px] text-slate-600">
        Kategorie (čárkou, volitelné)
        <input
          className="input mt-0.5 w-full"
          value={categoriesRaw}
          onChange={(e) => {
            const parts = e.target.value
              .split(",")
              .map((s) => s.trim())
              .filter(Boolean);
            setConfig({ categories: parts });
          }}
          placeholder="např. zprávy, makro"
        />
      </label>
      <button type="button" className="text-xs text-sky-800 underline" onClick={() => loadFeeds()}>
        Znovu načíst seznam feedů
      </button>
    </div>
  );
}
