import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  ChevronRight,
  ChevronDown,
  Search,
  RefreshCw,
  Plus,
  Check,
  ArrowLeft,
  Folder,
  FileBarChart2,
  ExternalLink,
  Database,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import CatalogBackToHubButton from "@/components/catalog/CatalogBackToHubButton";
import CatalogChartPreview from "@/components/catalog/CatalogChartPreview";
import { LoadingBlock } from "@/components/ui/loading";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import {
  addCatalogPreviewToPersonalDashboard,
  buildCatalogChartActionsProps,
} from "@/lib/catalogPageDashboard";
import { buildCatalogPreviewBody } from "@/lib/catalogPreviewBody";
import { CATALOGS } from "@/lib/catalogDefinitions";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import { normalizePreviewPayload } from "@/lib/previewNormalizer";
import {
  flattenCatalogCategories,
  allCategoryPathsFromTree,
  buildPathIndex,
  buildFilteredPaths,
  parseSearchKeywords,
  MAX_CATALOG_FILTER_ROWS,
  browseCategoryCountNode,
} from "@/lib/catalogTree";

function fredErrorUserMessage(payload, status) {
  if (!payload || typeof payload !== "object") {
    return "Katalog se nepodařilo načíst.";
  }
  if (payload.error === "FRED_API_KEY is missing on backend") {
    return "FRED API key není nastavený na backendu.";
  }
  if (
    payload.classified === "timeout" ||
    status === 504 ||
    (typeof payload.detail === "string" && payload.detail.toLowerCase().includes("timeout"))
  ) {
    return "Časový limit při kontaktu s FRED API vypršel.";
  }
  if (payload.classified === "network_error") {
    return "FRED API momentálně neodpovědělo (síť).";
  }
  if (payload.source === "FRED" || payload.detail) {
    return "FRED API momentálně neodpovědělo.";
  }
  return "Katalog se nepodařilo načíst.";
}

const FRED_CATALOG_DEF = CATALOGS.find((c) => c.id === "fred") || {
  id: "fred",
  sourceType: "fred",
  label: "FRED",
};

export default function FredCatalogPage() {
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const [tree, setTree] = useState(null);
  const [expandBundle, setExpandBundle] = useState(null);
  const [expandLoading, setExpandLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [search, setSearch] = useState("");
  const [existing, setExisting] = useState(new Set());
  const [adding, setAdding] = useState({});
  const [openPaths, setOpenPaths] = useState(new Set());
  const [catalogError, setCatalogError] = useState(null);
  const [expandError, setExpandError] = useState(null);
  const [fallbackQ, setFallbackQ] = useState("");
  const [fallbackBusy, setFallbackBusy] = useState(false);
  const [fallbackSeriesId, setFallbackSeriesId] = useState("");
  const [fallbackHits, setFallbackHits] = useState(null);
  const [expandCategoryTitle, setExpandCategoryTitle] = useState("");
  const [previewKey, setPreviewKey] = useState(null);
  const [previewData, setPreviewData] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [addingToDash, setAddingToDash] = useState(false);
  const { isSubscriber } = useAuth();
  const { allowed: canPersonalDashboard, message: personalDashMsg } = useFeatureAccess("personal_dashboard");
  const { allowed: canSaveWidget, message: saveWidgetMsg } = useFeatureAccess("save_widget");
  const dashboardFeature = useMemo(
    () => ({
      isSubscriber,
      canPersonalDashboard,
      canSaveWidget,
      personalDashMsg,
      saveWidgetMsg,
    }),
    [isSubscriber, canPersonalDashboard, canSaveWidget, personalDashMsg, saveWidgetMsg]
  );

  const clearExpand = useCallback(() => {
    setExpandBundle(null);
    setExpandCategoryTitle("");
    setPreviewKey(null);
    setPreviewData(null);
    setPreviewError("");
    if (tree) setOpenPaths(new Set(allCategoryPathsFromTree(tree.categories || [])));
  }, [tree]);

  const load = async (force = false) => {
    if (force) setRefreshing(true);
    else setLoading(true);
    setCatalogError(null);
    try {
      const url = force ? "/fred/catalog/refresh" : "/fred/catalog";
      const resp = force ? await api.post(url) : await api.get(url);
      setTree(resp.data);
      setExpandBundle(null);
      setOpenPaths(new Set(allCategoryPathsFromTree(resp.data.categories || [])));
      const { data: srcs } = await api.get("/sources/catalog-stubs");
      setExisting(
        new Set(
          (srcs || [])
            .filter((s) => s.source_type === "fred")
            .map((s) => s.fred_series_id || "")
            .filter(Boolean)
        )
      );
    } catch (e) {
      const st = e.response?.status;
      const d = e.response?.data;
      setCatalogError({
        status: st,
        payload: typeof d === "object" && d !== null ? d : { detail: formatApiErrorFromAxios(e) },
      });
      toast.error(formatApiErrorFromAxios(e));
    }
    setLoading(false);
    setRefreshing(false);
  };

  const loadExpand = async (categoryId) => {
    const id = String(categoryId || "").trim();
    if (!id) return;
    setExpandLoading(true);
    setExpandError(null);
    try {
      const { data } = await api.get("/fred/catalog/expand", { params: { category_id: id } });
      setExpandBundle({ categoryId: id, tree: data });
      const rootCat = Array.isArray(data?.categories) ? data.categories[0] : null;
      setExpandCategoryTitle(String(rootCat?.name || `Kategorie ${id}`).trim());
      setPreviewKey(null);
      setPreviewData(null);
      setPreviewError("");
      setOpenPaths(new Set(allCategoryPathsFromTree(data.categories || [])));
      const { data: srcs } = await api.get("/sources/catalog-stubs");
      setExisting(
        new Set(
          (srcs || [])
            .filter((s) => s.source_type === "fred")
            .map((s) => s.fred_series_id || "")
            .filter(Boolean)
        )
      );
    } catch (e) {
      const st = e.response?.status;
      const d = e.response?.data;
      setExpandError({
        status: st,
        payload: typeof d === "object" && d !== null ? d : { detail: formatApiErrorFromAxios(e) },
      });
      toast.error(formatApiErrorFromAxios(e));
    }
    setExpandLoading(false);
  };

  const runFallbackSearch = async () => {
    const q = fallbackQ.trim();
    if (!q) {
      toast.error("Zadejte hledaný text.");
      return;
    }
    setFallbackBusy(true);
    setFallbackHits(null);
    try {
      const { data } = await api.get("/fred/search", { params: { q, limit: 25 } });
      setFallbackHits(data);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setFallbackBusy(false);
  };

  useEffect(() => {
    load(false);
  }, []);

  useEffect(() => {
    const ex = (params.get("expand") || "").trim();
    if (!ex || !tree) return;
    const next = new URLSearchParams(params);
    next.delete("expand");
    if (expandBundle?.categoryId === ex) {
      setParams(next, { replace: true });
      return;
    }
    loadExpand(ex);
    setParams(next, { replace: true });
  }, [tree, params, expandBundle?.categoryId, setParams]);

  const activeTree = expandBundle?.tree || tree;
  const allRows = useMemo(
    () => (activeTree ? flattenCatalogCategories(activeTree.categories || []) : []),
    [activeTree]
  );
  const rowIndex = useMemo(() => buildPathIndex(allRows), [allRows]);
  const keywords = useMemo(() => parseSearchKeywords(search), [search]);
  const filteredPaths = useMemo(
    () => buildFilteredPaths(allRows, rowIndex, keywords),
    [allRows, rowIndex, keywords]
  );

  const visibleRows = useMemo(() => {
    if (!allRows.length) return [];
    if (filteredPaths) {
      return allRows.filter((r) => filteredPaths.has(r.path)).slice(0, MAX_CATALOG_FILTER_ROWS);
    }
    const result = [];
    for (const r of allRows) {
      if (r.depth === 0) {
        result.push(r);
        continue;
      }
      let parentSegments =
        r.kind === "set" ? r.parentPath.split(" > ") : r.path.split(" > ").slice(0, -1);
      let allOpen = true;
      const acc = [];
      for (const seg of parentSegments) {
        acc.push(seg);
        if (!openPaths.has(acc.join(" > "))) {
          allOpen = false;
          break;
        }
      }
      if (allOpen) result.push(r);
    }
    return result;
  }, [allRows, openPaths, filteredPaths]);

  const toggle = (path) => {
    setOpenPaths((s) => {
      const n = new Set(s);
      if (n.has(path)) n.delete(path);
      else n.add(path);
      return n;
    });
  };

  const fetchSeriesPreview = async (row) => {
    const previewRow = {
      ...row,
      set_id: String(row.set_id || row.fred_series_id || "").trim(),
      item_kind: row.item_kind || "selection",
      kind: "set",
    };
    setPreviewLoading(true);
    setPreviewData(null);
    setPreviewError("");
    try {
      const body = buildCatalogPreviewBody(FRED_CATALOG_DEF, previewRow);
      const { data } = await api.post("/catalog/preview", body);
      setPreviewData(normalizePreviewPayload(data, "fred"));
    } catch (e) {
      setPreviewError(formatApiErrorFromAxios(e));
    } finally {
      setPreviewLoading(false);
    }
  };

  const toggleSeriesPreview = async (row) => {
    const sid = String(row.set_id || row.fred_series_id || "").trim();
    if (!sid || !isCatalogRowPreviewEligible(FRED_CATALOG_DEF, { ...row, set_id: sid, item_kind: "selection" })) {
      return;
    }
    if (previewKey === sid) {
      setPreviewKey(null);
      setPreviewData(null);
      setPreviewError("");
      return;
    }
    setPreviewKey(sid);
    await fetchSeriesPreview(row);
  };

  const handleAddPreviewToDashboard = async (row, { setPagePick } = {}) => {
    if (!previewData || previewError) return;
    setAddingToDash(true);
    try {
      const previewRow = {
        ...row,
        set_id: String(row.set_id || row.fred_series_id || "").trim(),
        name: row.name || row.title,
      };
      await addCatalogPreviewToPersonalDashboard({
        api,
        nav,
        def: FRED_CATALOG_DEF,
        previewData,
        row: previewRow,
        feature: dashboardFeature,
        setPagePick,
      });
    } finally {
      setAddingToDash(false);
    }
  };

  const addSource = async (set_id) => {
    setAdding((a) => ({ ...a, [set_id]: true }));
    try {
      const { data } = await api.post("/fred/catalog/add-source", { set_id });
      toast.success(`Přidáno: ${data.name}`);
      setExisting((s) => new Set([...s, String(set_id)]));
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding((a) => ({ ...a, [set_id]: false }));
  };

  const subtitle = expandBundle
    ? `Kategorie ${expandBundle.categoryId} — ${(
        expandBundle.tree?.total_sets ?? 0
      ).toLocaleString("cs-CZ")} položek (podkategorie + řady) · api.stlouisfed.org`
    : `${tree?.total_sets?.toLocaleString("cs-CZ") || "—"} hlavních větví FRED (kořen API) · vyžaduje FRED_API_KEY na serveru`;

  return (
    <AppShell
      title="Katalog FRED"
      subtitle={subtitle}
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <a
            href="https://fred.stlouisfed.org/docs/api/api_key.html"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
            title="API klíč (zdarma)"
          >
            <ExternalLink className="h-4 w-4" /> FRED API klíč
          </a>
          <button
            onClick={() => nav("/sources")}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
          >
            <ArrowLeft className="h-4 w-4" /> Zpět na zdroje
          </button>
          <CatalogBackToHubButton catalogId="fred" />
          {expandBundle ? (
            <button
              type="button"
              onClick={clearExpand}
              className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))]"
              data-testid="fred-catalog-back-root"
            >
              <ArrowLeft className="h-4 w-4" /> Zpět na kategorie
            </button>
          ) : null}
          <button
            onClick={() => load(true)}
            disabled={refreshing}
            className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))] disabled:opacity-50"
          >
            <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
            {refreshing ? "Stahuji…" : "Obnovit"}
          </button>
        </div>
      }
    >
      <div className="mb-4 max-w-xl relative">
        <Search className="h-4 w-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
        <input
          type="text"
          className="w-full h-10 pl-9 pr-3 border border-[hsl(var(--border)/0.75)] rounded-xl text-sm bg-card shadow-sm"
          placeholder="Hledat (název, kód řady…) — u velkých kategorií použij filtr"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          data-testid="fred-catalog-search"
        />
      </div>
      {expandBundle ? (
        <div
          className="mb-4 flex flex-wrap items-center gap-2 rounded-xl border border-[hsl(var(--border)/0.75)] bg-card/90 px-3 py-2.5 shadow-sm"
          data-testid="fred-catalog-breadcrumb"
        >
          <button
            type="button"
            onClick={clearExpand}
            className="inline-flex items-center gap-1.5 rounded-lg border border-[hsl(var(--border)/0.75)] bg-white px-3 h-8 text-xs font-semibold text-slate-800 hover:bg-[hsl(var(--primary-soft))]"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            Zpět na hlavní kategorie
          </button>
          <span className="text-xs text-muted-foreground">
            <span className="font-medium text-foreground">{expandCategoryTitle || `Kategorie ${expandBundle.categoryId}`}</span>
            {search.trim() ? (
              <>
                {" "}
                · filtr: <span className="font-mono text-foreground">„{search.trim()}“</span>
              </>
            ) : null}
          </span>
        </div>
      ) : null}

      <p className="mb-4 text-xs text-muted-foreground max-w-3xl leading-relaxed">
        <strong>FRED tady neukazuje „celou databázi“ najednou</strong> — endpoint pro kořen vrací jen několik hlavních větví
        (např. Money, Banking…, National Accounts…). Statisíce řad jsou uvnitř po kliknutí na{" "}
        <em>Otevřít</em> a procházení podkategorií. To odpovídá oficiálnímu stromu na fred.stlouisfed.org.
        {expandBundle ? (
          <>
            {" "}
            U datové řady stačí <strong>kliknout na řádek</strong> — zobrazí se náhled časové řady.
          </>
        ) : null}
      </p>

      {catalogError ? (
        <div
          className="mb-4 rounded-2xl border border-amber-200 canvas-dark:border-amber-600/45 bg-amber-50 canvas-dark:bg-amber-950/35 px-4 py-3 text-sm text-amber-950 canvas-dark:text-amber-50 shadow-sm"
          data-testid="fred-catalog-error"
        >
          <div className="font-medium mb-1">Katalog FRED se nepodařilo načíst</div>
          <p className="text-amber-900/90 mb-2">{fredErrorUserMessage(catalogError.payload, catalogError.status)}</p>
          {catalogError.payload?.detail ? (
            <pre className="text-[11px] whitespace-pre-wrap font-mono text-amber-900/80 canvas-dark:text-amber-100/90 bg-card/60 rounded-lg p-2 max-h-32 overflow-auto border border-amber-300/70 canvas-dark:border-amber-600/50">
              {String(catalogError.payload.detail).slice(0, 800)}
            </pre>
          ) : null}
          <p className="mt-3 text-xs text-amber-900/80 canvas-dark:text-amber-100/90">
            Zkuste vyhledat konkrétní FRED series ID nebo dotaz — endpoint <code className="px-1 bg-card/70 rounded">GET /api/fred/search</code>.
          </p>
          <div className="mt-2 flex flex-wrap gap-2 items-center">
            <input
              type="text"
              className="min-w-[180px] flex-1 h-9 px-3 border border-amber-200 canvas-dark:border-amber-600/45 rounded-lg text-sm bg-card"
              placeholder="Hledat řady (např. GDP)…"
              value={fallbackQ}
              onChange={(e) => setFallbackQ(e.target.value)}
              data-testid="fred-fallback-search"
            />
            <button
              type="button"
              onClick={runFallbackSearch}
              disabled={fallbackBusy}
              className="h-9 px-3 rounded-lg text-sm border border-amber-300 bg-card hover:bg-amber-100/80 disabled:opacity-50"
            >
              {fallbackBusy ? "Hledám…" : "Hledat v FRED"}
            </button>
          </div>
          <div className="mt-2 flex flex-wrap gap-2 items-center">
            <input
              type="text"
              className="min-w-[140px] h-9 px-3 border border-amber-200 canvas-dark:border-amber-600/45 rounded-lg text-sm font-mono bg-card"
              placeholder="Series ID (např. DGS10)"
              value={fallbackSeriesId}
              onChange={(e) => setFallbackSeriesId(e.target.value)}
            />
            <button
              type="button"
              onClick={() => {
                const id = fallbackSeriesId.trim();
                if (!id) {
                  toast.error("Zadejte kód řady.");
                  return;
                }
                addSource(id);
              }}
              className="h-9 px-3 rounded-lg text-sm border border-amber-300 bg-card hover:bg-amber-100/80"
            >
              Přidat zdroj podle ID
            </button>
          </div>
          {fallbackHits?.seriess?.length ? (
            <ul className="mt-3 max-h-40 overflow-auto text-xs border border-amber-300/70 canvas-dark:border-amber-600/50 rounded-lg bg-card/70 divide-y divide-amber-100">
              {fallbackHits.seriess.slice(0, 15).map((s) => (
                <li key={s.id} className="px-2 py-1.5 flex justify-between gap-2">
                  <span className="truncate font-mono">{s.id}</span>
                  <button
                    type="button"
                    className="shrink-0 text-amber-800 underline"
                    onClick={() => addSource(s.id)}
                  >
                    Přidat
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {expandError ? (
        <div
          className="mb-4 rounded-2xl border border-rose-200 bg-rose-50/90 px-4 py-3 text-sm text-rose-950 canvas-dark:text-rose-100 shadow-sm"
          data-testid="fred-expand-error"
        >
          <div className="font-medium mb-1">Kategorii se nepodařilo načíst</div>
          <p>{fredErrorUserMessage(expandError.payload, expandError.status)}</p>
          {expandError.payload?.detail ? (
            <pre className="mt-2 text-[11px] whitespace-pre-wrap font-mono text-rose-900/80 bg-card/60 rounded-lg p-2 max-h-28 overflow-auto">
              {String(expandError.payload.detail).slice(0, 800)}
            </pre>
          ) : null}
        </div>
      ) : null}

      {expandLoading ? (
        <LoadingBlock
          label="Načítám kategorii z FRED…"
          minHeightClass="min-h-[100px]"
          showSkeletonLines
        />
      ) : loading ? (
        <LoadingBlock
          label="Načítám katalog FRED…"
          minHeightClass="min-h-[140px]"
          showSkeletonLines
        />
      ) : catalogError ? null : visibleRows.length === 0 ? (
        <div className="border border-dashed border-border bg-muted/25 rounded-2xl p-12 text-center text-sm text-muted-foreground font-mono">
          {search ? "Žádný výsledek pro zadaný filtr." : "Katalog je prázdný (zkontrolujte FRED_API_KEY)."}
        </div>
      ) : (
        <div className="bg-card border border-border rounded-2xl overflow-hidden shadow-sm">
          {visibleRows.map((row) => {
            if (row.kind === "cat") {
              const isOpen = openPaths.has(row.path) || Boolean(filteredPaths);
              const browseCountLabel = browseCategoryCountNode(row, isOpen);
              return (
                <button
                  key={row.path}
                  onClick={() => toggle(row.path)}
                  className={`w-full flex items-center gap-2 text-left px-4 py-2.5 hover:bg-muted/50 border-t border-border/60 ${
                    row.depth === 0 ? "bg-muted/30 font-medium" : ""
                  }`}
                  style={{ paddingLeft: `${16 + row.depth * 20}px` }}
                >
                  {isOpen ? (
                    <ChevronDown className="h-4 w-4 text-muted-foreground shrink-0" />
                  ) : (
                    <ChevronRight className="h-4 w-4 text-muted-foreground shrink-0" />
                  )}
                  <Folder className="h-4 w-4 text-muted-foreground shrink-0" />
                  <span className="text-sm text-foreground truncate">{row.name}</span>
                  {browseCountLabel != null ? (
                    <span className="text-[10px] uppercase tracking-wider text-muted-foreground ml-auto pr-2">
                      {browseCountLabel}
                    </span>
                  ) : null}
                </button>
              );
            }
            const isCategory = row.item_kind === "category";
            const sid = String(row.fred_series_id || row.set_id || "").trim();
            const isAdded = existing.has(sid);
            const isAdding = Boolean(adding[sid]);
            const previewRow = { ...row, set_id: sid, item_kind: row.item_kind || "selection" };
            const previewable = !isCategory && isCatalogRowPreviewEligible(FRED_CATALOG_DEF, previewRow);
            const previewOpen = previewable && previewKey === sid;
            const meta = [];
            if (row.period) meta.push(`období: ${row.period}`);
            if (row.territory) meta.push(`oblast: ${row.territory}`);
            return (
              <div key={row.path} data-testid={previewable ? `fred-series-row-${sid}` : undefined}>
                <div
                  role={previewable ? "button" : undefined}
                  tabIndex={previewable ? 0 : undefined}
                  onClick={() => {
                    if (previewable) void toggleSeriesPreview(previewRow);
                  }}
                  onKeyDown={(e) => {
                    if (!previewable || (e.key !== "Enter" && e.key !== " ")) return;
                    e.preventDefault();
                    void toggleSeriesPreview(previewRow);
                  }}
                  className={`flex items-center gap-3 py-2 pr-3 border-t border-border/60 hover:bg-muted/50 ${
                    previewable ? "cursor-pointer" : ""
                  } ${previewOpen ? "bg-muted/30" : ""}`}
                  style={{ paddingLeft: `${36 + row.depth * 20}px` }}
                >
                  <FileBarChart2 className="h-4 w-4 text-muted-foreground shrink-0" />
                  <div className="min-w-0 flex-1">
                    <div className="text-sm text-foreground truncate" title={row.name}>
                      {row.name}
                    </div>
                    <div className="text-[11px] text-muted-foreground font-mono">
                      {isCategory ? `kategorie: ${row.fred_category_id || ""}` : `kód: ${row.set_id}`}
                      {meta.length ? `  ·  ${meta.join("  ·  ")}` : ""}
                    </div>
                    {previewable ? (
                      <div className="text-[10px] text-muted-foreground mt-0.5">Klikněte pro náhled dat</div>
                    ) : null}
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0" onClick={(e) => e.stopPropagation()}>
                    {isCategory ? (
                  <button
                    type="button"
                    onClick={() => loadExpand(row.fred_category_id)}
                    disabled={expandLoading}
                    className="btn-mint flex items-center gap-1.5 px-3 h-7 text-xs disabled:opacity-50"
                  >
                    {expandLoading ? (
                      <RefreshCw className="h-3 w-3 animate-spin" />
                    ) : (
                      <Database className="h-3 w-3" />
                    )}
                    Otevřít
                  </button>
                ) : isAdded ? (
                  <span
                    data-testid={`fred-added-${sid}`}
                    className="flex items-center gap-1.5 px-2.5 h-7 text-[11px] uppercase tracking-wider rounded-lg chip-mint border border-[hsl(215_45%_82%)]"
                  >
                    <Check className="h-3 w-3" /> přidáno
                  </span>
                ) : (
                      <button
                        type="button"
                        onClick={() => addSource(sid)}
                        disabled={isAdding}
                        data-testid={`fred-add-${sid}`}
                        className="btn-mint flex items-center gap-1.5 px-3 h-7 text-xs disabled:opacity-50"
                      >
                        {isAdding ? (
                          <RefreshCw className="h-3 w-3 animate-spin" />
                        ) : (
                          <Plus className="h-3 w-3" />
                        )}
                        Přidat
                      </button>
                    )}
                  </div>
                </div>
                {previewOpen ? (
                  <div
                    className="border-t border-border/60 bg-muted/15 px-4 py-3"
                    style={{ paddingLeft: `${36 + row.depth * 20}px` }}
                    data-testid={`fred-preview-${sid}`}
                    onClick={(e) => e.stopPropagation()}
                  >
                    <CatalogChartPreview
                      widgetId={`fred-preview-${sid}`}
                      title={row.name || row.title || sid}
                      sourceType="fred"
                      catalogDef={FRED_CATALOG_DEF}
                      catalogRow={{
                        ...row,
                        set_id: sid,
                        item_kind: row.item_kind || "selection",
                      }}
                      preview={
                        previewError
                          ? { error: previewError, source: { name: row.name, source_type: "fred" } }
                          : previewData
                            ? { ...previewData, source: { name: row.name, source_type: "fred" } }
                            : { source: { name: row.name, source_type: "fred" } }
                      }
                      previewError={previewError}
                      previewLoading={previewLoading}
                      sourcePreviewProps={{
                        catalogChartActions: buildCatalogChartActionsProps({
                          feature: dashboardFeature,
                          previewData,
                          previewError,
                          previewLoading,
                          onAddToDashboard: (ctx) => void handleAddPreviewToDashboard(row, ctx),
                          addingToDashboard: addingToDash,
                        }),
                      }}
                    />
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      )}
    </AppShell>
  );
}
