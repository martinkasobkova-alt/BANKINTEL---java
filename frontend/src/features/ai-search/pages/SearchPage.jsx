import React, { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ArrowRight, Database, Download, FileBarChart2, Search } from "lucide-react";
import AppShell from "@/components/layout/AppShell";
import api from "@/lib/api";
import { LoadingInline } from "@/components/ui/loading";

function typeLabel(type, view, t) {
  const raw = String(type || "");
  if (String(view || "").toLowerCase() === "table") return t("pages.search.typeTable");
  if (raw.includes("view") || raw.includes("chart") || raw.includes("computed")) return t("pages.search.typeChart");
  return t("pages.search.typeWidget");
}

export default function SearchPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const query = params.get("q") || "";
  const [localQuery, setLocalQuery] = useState(query);
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState([]);

  useEffect(() => setLocalQuery(query), [query]);

  useEffect(() => {
    if (query.trim().length < 2) {
      setResults([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    api
      .get("/homepage/search", { params: { q: query } })
      .then(({ data }) => {
        if (!cancelled) setResults(Array.isArray(data?.results) ? data.results : []);
      })
      .catch(() => {
        if (!cancelled) setResults([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [query]);

  const catalogLink = useMemo(() => {
    const q = query.trim();
    if (q.length >= 2) return `/search/catalog?q=${encodeURIComponent(q)}`;
    return "/search/catalog";
  }, [query]);

  const submit = (e) => {
    e.preventDefault();
    const q = localQuery.trim();
    if (q.length >= 2) setParams({ q });
  };

  return (
    <AppShell title={t("pages.search.title")} subtitle={t("pages.search.subtitle")}>
      <div className="max-w-5xl space-y-5">
        <form onSubmit={submit} className="soft-card p-4 flex items-center gap-3">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <input
              type="search"
              value={localQuery}
              onChange={(e) => setLocalQuery(e.target.value)}
              className="w-full h-11 pl-9 pr-3 border border-border/70 rounded-xl text-sm bg-card"
              placeholder={t("pages.search.placeholder")}
              autoFocus
            />
          </div>
          <button className="btn-mint h-11 px-4 text-sm" type="submit">
            {t("pages.search.submit")}
          </button>
        </form>

        <section className="soft-card overflow-hidden">
          <div className="px-5 py-4 border-b border-border/60 flex items-center justify-between gap-3">
            <div>
              <div className="kpi-label">{t("pages.search.inApp")}</div>
              <div className="text-sm text-muted-foreground mt-1">{t("pages.search.inAppHint")}</div>
            </div>
            {loading && (
              <LoadingInline label={t("pages.search.loadingResults")} size="sm" muted className="shrink-0" />
            )}
          </div>
          {query.trim().length < 2 ? (
            <div className="p-8 text-sm text-muted-foreground font-mono">{t("pages.search.minChars")}</div>
          ) : results.length === 0 && !loading ? (
            <div className="p-8 text-sm text-muted-foreground">{t("pages.search.noResults")}</div>
          ) : (
            <div className="divide-y divide-border/60">
              {results.map((r) => {
                const wid = String(r.id || "").trim();
                const to = wid ? `${r.path || "/"}#widget-${encodeURIComponent(wid)}` : r.path || "/";
                const sub = r.subpage_title ? ` · ${r.subpage_title}` : "";
                return (
                  <Link
                    key={`${r.path}-${r.id}`}
                    to={to}
                    className="flex items-start gap-3 px-5 py-4 hover:bg-[hsl(var(--primary-soft)/0.55)] transition-colors"
                  >
                    <FileBarChart2 className="h-4 w-4 text-muted-foreground shrink-0 mt-0.5" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-[10px] uppercase tracking-wider px-2 py-0.5 rounded-full chip-mint font-semibold">
                          {typeLabel(r.type, r.view, t)}
                        </span>
                        <span className="text-sm font-semibold text-foreground line-clamp-1">{r.title}</span>
                      </div>
                      <div className="text-[11px] text-muted-foreground font-mono mt-1">
                        {r.section || r.page_title}
                        {sub} · {r.path}
                      </div>
                    </div>
                    <ArrowRight className="h-4 w-4 text-muted-foreground shrink-0 mt-1" />
                  </Link>
                );
              })}
            </div>
          )}
        </section>

        <section className="soft-card p-5 flex flex-col md:flex-row md:items-center gap-4 justify-between">
          <div className="flex items-start gap-3">
            <Database className="h-5 w-5 text-[hsl(var(--primary))] shrink-0 mt-0.5" />
            <div>
              <div className="kpi-label">{t("pages.search.allDatabases")}</div>
              <div className="text-sm text-muted-foreground mt-1 max-w-2xl">{t("pages.search.allDatabasesHint")}</div>
            </div>
          </div>
          <Link to={catalogLink} className="btn-mint h-10 px-4 text-sm inline-flex items-center gap-2 shrink-0">
            <Download className="h-4 w-4" /> {t("pages.search.searchInCatalogs")}
          </Link>
        </section>
      </div>
    </AppShell>
  );
}
