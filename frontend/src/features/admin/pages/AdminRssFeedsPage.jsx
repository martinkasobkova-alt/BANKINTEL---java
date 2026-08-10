import React, { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import { RefreshCw, Plus, Trash2 } from "lucide-react";

export default function AdminRssFeedsPage() {
  const { t } = useTranslation();
  const [feeds, setFeeds] = useState([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [category, setCategory] = useState("");
  const [autoTranslate, setAutoTranslate] = useState(false);
  const [publishToArticles, setPublishToArticles] = useState(false);
  const [sourceType, setSourceType] = useState("rss");
  const [busyId, setBusyId] = useState(null);
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get("/rss/feeds");
      setFeeds(Array.isArray(data) ? data : []);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nelze načíst feedy.");
      setFeeds([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const addGlobal = async () => {
    if (adding) return;
    if (!name.trim() || !url.trim()) {
      toast.error("Vyplňte název a URL.");
      return;
    }
    setAdding(true);
    try {
      await api.post("/rss/feeds", {
        scope: "global",
        name: name.trim(),
        url: url.trim(),
        category: category.trim(),
        enabled: true,
        refresh_interval_minutes: 120,
        auto_translate: autoTranslate,
        publish_to_articles: publishToArticles,
        source_type: sourceType,
      });
      setName("");
      setUrl("");
      setCategory("");
      setAutoTranslate(false);
      setPublishToArticles(false);
      setSourceType("rss");
      await load();
      toast.success("Globální RSS přidán");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    } finally {
      setAdding(false);
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
        toast.success(`OK — „${data.feed_title || ""}“, ${data.item_count ?? 0} položek.`);
      } else {
        toast.error(data?.error || "Validace selhala");
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    } finally {
      setBusyId(null);
    }
  };

  const patchEnabled = async (f, enabled) => {
    try {
      await api.patch(`/rss/feeds/${f.id}`, { enabled });
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const patchAutoTranslate = async (f, auto_translate) => {
    try {
      await api.patch(`/rss/feeds/${f.id}`, { auto_translate });
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const patchPublishToArticles = async (f, publish_to_articles) => {
    try {
      await api.patch(`/rss/feeds/${f.id}`, { publish_to_articles });
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const patchSourceType = async (f, source_type) => {
    try {
      await api.patch(`/rss/feeds/${f.id}`, { source_type });
      await load();
      toast.success("Typ zdroje změněn — doporučujeme dát „Ověřit“.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const remove = async (id) => {
    if (!window.confirm("Smazat feed a všechny uložené položky?")) return;
    try {
      await api.delete(`/rss/feeds/${id}`);
      await load();
      toast.success("Smazáno");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba mazání");
    }
  };

  return (
    <AppShell title={t("pages.admin.rssAdminTitle")} subtitle={t("pages.admin.rssAdminSubtitle")}>
      <div className="max-w-4xl space-y-6 text-sm copper-text-fix-scope">
        <div className="soft-card p-4 space-y-3">
          <div className="font-medium text-foreground">Přidat globální RSS / Atom</div>
          <div className="grid sm:grid-cols-2 gap-2">
            <label>
              <span className="text-xs text-muted-foreground">Název</span>
              <input className="input mt-0.5 w-full" value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <label>
              <span className="text-xs text-muted-foreground">Kategorie (volitelné)</span>
              <input className="input mt-0.5 w-full" value={category} onChange={(e) => setCategory(e.target.value)} />
            </label>
            <label className="sm:col-span-2">
              <span className="text-xs text-muted-foreground">URL</span>
              <input className="input mt-0.5 w-full" value={url} onChange={(e) => setUrl(e.target.value)} />
            </label>
            <label className="sm:col-span-2">
              <span className="text-xs text-muted-foreground">Typ zdroje</span>
              <select
                className="input mt-0.5 w-full"
                value={sourceType}
                onChange={(e) => setSourceType(e.target.value)}
              >
                <option value="rss">RSS / Atom feed</option>
                <option value="html_scrape">Běžná webová stránka (bez RSS) — vytáhne se přes AI</option>
              </select>
            </label>
          </div>
          <div className="flex flex-col gap-1">
            <label className="inline-flex items-center gap-1.5 text-xs text-foreground">
              <input type="checkbox" checked={autoTranslate} onChange={(e) => setAutoTranslate(e.target.checked)} />
              Automaticky překládat do češtiny (widget)
            </label>
            <label className="inline-flex items-center gap-1.5 text-xs text-foreground">
              <input
                type="checkbox"
                checked={publishToArticles}
                onChange={(e) => setPublishToArticles(e.target.checked)}
              />
              Zakládat nové položky jako koncept ve Zprávách
            </label>
          </div>
          <button
            type="button"
            className="btn-primary text-sm py-1.5 px-3 inline-flex items-center gap-1 disabled:opacity-60"
            disabled={adding}
            onClick={addGlobal}
          >
            {adding ? (
              <RefreshCw className="h-4 w-4 animate-spin" />
            ) : (
              <Plus className="h-4 w-4" />
            )}
            {adding ? "Ověřuji a ukládám… (u „Web (AI)“ může trvat i déle)" : "Uložit a ověřit při uložení"}
          </button>
        </div>

        <div className="soft-card p-4">
          <div className="font-medium text-foreground mb-2">Seznam feedů</div>
          {loading ? (
            <p className="text-muted-foreground text-xs">Načítání…</p>
          ) : (
            <div className="overflow-x-auto border border-border/50 rounded-xl">
              <table className="w-full text-left text-xs">
                <thead className="bg-muted/45 border-b border-border/60">
                  <tr>
                    <th className="p-2">Rozsah</th>
                    <th className="p-2">Název</th>
                    <th className="p-2">URL</th>
                    <th className="p-2">Typ</th>
                    <th className="p-2">Stav</th>
                    <th className="p-2">Překlad</th>
                    <th className="p-2">Zprávy</th>
                    <th className="p-2">Akce</th>
                  </tr>
                </thead>
                <tbody>
                  {feeds.map((f) => (
                    <tr key={f.id} className="border-b border-border/40 last:border-0">
                      <td className="p-2 whitespace-nowrap">{f.scope}</td>
                      <td className="p-2">{f.name}</td>
                      <td className="p-2 max-w-[240px] truncate" title={f.url}>
                        {f.url}
                      </td>
                      <td className="p-2 whitespace-nowrap">
                        <select
                          className="input py-0.5 px-1 text-[11px]"
                          value={f.source_type || "rss"}
                          onChange={(e) => patchSourceType(f, e.target.value)}
                        >
                          <option value="rss">RSS</option>
                          <option value="html_scrape">Web (AI)</option>
                        </select>
                      </td>
                      <td className="p-2 whitespace-nowrap">
                        <label className="inline-flex items-center gap-1">
                          <input type="checkbox" checked={!!f.enabled} onChange={(e) => patchEnabled(f, e.target.checked)} />
                          zapnuto
                        </label>
                        <div className="text-[10px] text-muted-foreground mt-0.5">{f.last_sync_status || "—"}</div>
                      </td>
                      <td className="p-2 whitespace-nowrap">
                        <label className="inline-flex items-center gap-1">
                          <input
                            type="checkbox"
                            checked={!!f.auto_translate}
                            onChange={(e) => patchAutoTranslate(f, e.target.checked)}
                          />
                          do CS
                        </label>
                      </td>
                      <td className="p-2 whitespace-nowrap">
                        <label className="inline-flex items-center gap-1">
                          <input
                            type="checkbox"
                            checked={!!f.publish_to_articles}
                            onChange={(e) => patchPublishToArticles(f, e.target.checked)}
                          />
                          do Zpráv
                        </label>
                      </td>
                      <td className="p-2 whitespace-nowrap space-x-1">
                        <button type="button" className="btn-ghost px-2 py-1 text-[11px]" disabled={busyId === f.id} onClick={() => validateOne(f.id)}>
                          Ověřit
                        </button>
                        <button type="button" className="btn-ghost px-2 py-1 text-[11px] inline-flex items-center gap-0.5" disabled={busyId === f.id} onClick={() => syncOne(f.id)}>
                          <RefreshCw className={`h-3 w-3 ${busyId === f.id ? "animate-spin" : ""}`} />
                          Sync
                        </button>
                        <button type="button" className="inline-flex items-center gap-0.5 px-2 py-1 text-[11px] rounded-lg border border-red-500/55 text-red-700 hover:bg-red-500/10 canvas-dark:text-red-300 canvas-dark:border-red-400/45 canvas-dark:hover:bg-red-500/20" onClick={() => remove(f.id)}>
                          <Trash2 className="h-3 w-3" />
                          Smazat
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {feeds.length === 0 ? <p className="p-4 text-muted-foreground text-xs">Žádné záznamy.</p> : null}
            </div>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          Sloupec „Typ“: „RSS“ čte standardní RSS/Atom feed. „Web (AI)“ je pro běžné stránky bez RSS (např. seznam
          aktualit na webu instituce) — stáhne se HTML a AI z něj vytáhne jednotlivé zprávy (titulek, odkaz, datum);
          hodí se hlavně ve spojení s „do Zpráv“ níže.
        </p>
        <p className="text-xs text-muted-foreground">
          Zapnuté feedy se automaticky synchronizují na pozadí (podle intervalu feedu), ruční „Sync“ tlačítko slouží
          jen k okamžitému natažení. Sloupec „do CS“ zapíná automatický překlad titulku a shrnutí do češtiny přes
          OpenAI (projeví se ve widgetu) — přeloží se jen nové/změněné položky, nepřekládá se opakovaně. Sloupec „do
          Zpráv“ je samostatný přepínač: nové položky se založí jako nepublikovaný koncept v{" "}
          <a href="/admin/articles" className="underline">
            Zprávách
          </a>{" "}
          — funguje i bez „do CS“ (např. u zdroje, který je už v češtině, jako ČNB). Robot nikdy nic nezveřejňuje
          sám, admin si koncept projde, případně upraví a ručně publikuje.
        </p>
        <p className="text-xs text-muted-foreground">
          Přístup k RSS widgetu řídí funkce <code className="text-[11px]">rss_monitoring</code> v{" "}
          <a href="/admin/feature-access" className="underline">
            Zamykání funkcí
          </a>
          .
        </p>
      </div>
    </AppShell>
  );
}
