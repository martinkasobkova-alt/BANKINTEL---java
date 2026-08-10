import React, { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { RefreshCw, Plus, CheckCircle2, XCircle } from "lucide-react";

export default function PersonalRssPanel() {
  const { user } = useAuth();
  const isAdmin = user?.role === "admin";
  const [feeds, setFeeds] = useState([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [busyId, setBusyId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get("/rss/feeds");
      setFeeds(Array.isArray(data) ? data : []);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nelze načíst RSS zdroje.");
      setFeeds([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const addFeed = async () => {
    if (!name.trim() || !url.trim()) {
      toast.error("Vyplňte název a URL.");
      return;
    }
    try {
      await api.post("/rss/feeds", {
        scope: "user",
        name: name.trim(),
        url: url.trim(),
        category: "",
        enabled: true,
        refresh_interval_minutes: 60,
      });
      setName("");
      setUrl("");
      await load();
      toast.success("RSS zdroj přidán");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba přidání");
    }
  };

  const syncOne = async (id) => {
    setBusyId(id);
    try {
      await api.post(`/rss/feeds/${id}/sync`);
      await load();
      toast.success("Synchronizováno");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Sync selhalo");
    } finally {
      setBusyId(null);
    }
  };

  const validateOne = async (id) => {
    setBusyId(id);
    try {
      const { data } = await api.post(`/rss/feeds/${id}/validate`);
      if (data?.ok) {
        toast.success(`Feed OK — ${data.item_count ?? 0} položek v náhledu.`);
      } else {
        toast.error(data?.error || "Validace selhala");
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba validace");
    } finally {
      setBusyId(null);
    }
  };

  const toggleEnabled = async (f) => {
    try {
      await api.patch(`/rss/feeds/${f.id}`, { enabled: !f.enabled });
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba uložení");
    }
  };

  return (
    <div className="soft-card p-4 mb-6 text-sm space-y-3 copper-text-fix-scope">
      <div className="font-medium text-foreground">Moje RSS / Atom zdroje</div>
      <p className="text-xs text-muted-foreground leading-relaxed">
        Globální zdroje spravuje administrátor. Zde přidáte vlastní URL (pouze http/https); před uložením se feed ověří.
      </p>
      <div className="flex flex-wrap gap-2 items-end">
        <label className="flex-1 min-w-[140px]">
          <span className="text-[11px] text-muted-foreground block mb-0.5">Název</span>
          <input className="input w-full text-sm" value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="flex-[2] min-w-[200px]">
          <span className="text-[11px] text-muted-foreground block mb-0.5">URL feedu</span>
          <input className="input w-full text-sm" value={url} onChange={(e) => setUrl(e.target.value)} />
        </label>
        <button type="button" className="btn-primary text-sm py-1.5 px-3 inline-flex items-center gap-1" onClick={addFeed}>
          <Plus className="h-4 w-4" /> Přidat
        </button>
      </div>
      {loading ? (
        <div className="text-muted-foreground text-xs">Načítání…</div>
      ) : (
        <ul className="divide-y divide-border/60 border border-border/50 rounded-xl overflow-hidden">
          {feeds.map((f) => (
            <li key={f.id} className="px-3 py-2 flex flex-wrap items-center gap-2 bg-card/55">
              <span className="text-[10px] uppercase text-muted-foreground w-14 shrink-0">{f.scope === "global" ? "Globál" : "Vlastní"}</span>
              <span className="font-medium text-foreground flex-1 min-w-[120px]">{f.name}</span>
              <span className="text-[11px] text-muted-foreground truncate max-w-[200px]" title={f.url}>
                {f.url}
              </span>
              {f.last_sync_status === "ok" ? (
                <CheckCircle2 className="h-4 w-4 text-emerald-600 shrink-0" aria-label="OK" />
              ) : f.last_sync_status === "error" ? (
                <XCircle className="h-4 w-4 text-red-600 shrink-0" title={f.last_sync_message || ""} aria-label="Chyba" />
              ) : null}
              {f.scope === "user" ? (
                <label className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                  <input type="checkbox" checked={!!f.enabled} onChange={() => toggleEnabled(f)} />
                  Zapnuto
                </label>
              ) : (
                <span className="text-xs text-muted-foreground">{f.enabled ? "zapnuto" : "vypnuto"}</span>
              )}
              <button
                type="button"
                className="btn-ghost text-xs px-2 py-1 rounded-lg border border-border/70"
                disabled={busyId === f.id}
                onClick={() => validateOne(f.id)}
              >
                Ověřit
              </button>
              {(f.scope === "user" || (f.scope === "global" && isAdmin)) ? (
                <button
                  type="button"
                  className="btn-ghost text-xs px-2 py-1 rounded-lg border border-border/70 inline-flex items-center gap-1"
                  disabled={busyId === f.id}
                  onClick={() => syncOne(f.id)}
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${busyId === f.id ? "animate-spin" : ""}`} />
                  Sync
                </button>
              ) : null}
            </li>
          ))}
          {feeds.length === 0 ? <li className="px-3 py-4 text-muted-foreground text-xs">Žádné zdroje.</li> : null}
        </ul>
      )}
    </div>
  );
}
