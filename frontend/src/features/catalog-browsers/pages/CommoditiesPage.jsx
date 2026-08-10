import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import {
  ChevronRight,
  ChevronDown,
  Search,
  RefreshCw,
  Plus,
  Check,
  ExternalLink,
  FileBarChart2,
  Folder,
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
import { buildCatalogPreviewBody, resolveCatalogRowDef } from "@/lib/catalogPreviewBody";
import { COMMODITIES_CATALOG_DEF } from "@/lib/catalogDefinitions";
import { normalizePreviewPayload } from "@/lib/previewNormalizer";
import {
  flattenCatalogCategories,
  allCategoryPathsFromTree,
  buildPathIndex,
  buildFilteredPaths,
  parseSearchKeywords,
  MAX_CATALOG_FILTER_ROWS,
} from "@/lib/catalogTree";

const TABS = [
  { id: "pink_sheet", label: "Pink Sheet (ceny)" },
  { id: "forecasts", label: "CMO prognózy" },
  { id: "imf", label: "IMF indexy" },
  { id: "bis", label: "BIS ceny" },
  { id: "links", label: "World Bank odkazy" },
];

function tabTree(hub, tab) {
  if (!hub) return null;
  if (tab === "pink_sheet") return hub.pink_sheet;
  if (tab === "forecasts") return hub.forecasts;
  if (tab === "imf") {
    return {
      categories: [
        {
          name: "IMF commodity price indices",
          path: "IMF",
          children: [],
          sets: (hub.imf_commodity_indices || []).map((r) => ({ ...r, kind: "set" })),
        },
      ],
    };
  }
  if (tab === "bis") {
    return {
      categories: [
        {
          name: "BIS commodity prices",
          path: "BIS",
          children: [],
          sets: (hub.bis_commodity_series || []).map((r) => ({ ...r, kind: "set" })),
        },
      ],
    };
  }
  return null;
}

function previewDefForRow(row) {
  return resolveCatalogRowDef(COMMODITIES_CATALOG_DEF, row);
}

function StatCard({ label, value }) {
  return (
    <div className="rounded-xl border border-[hsl(var(--border)/0.75)] bg-card/90 px-4 py-3 shadow-sm">
      <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="text-2xl font-semibold tabular-nums">{value ?? "—"}</div>
    </div>
  );
}

function BisEmptyPanel({ note, hub }) {
  const text =
    note ||
    hub?.bis_commodity_note_cs ||
    "BIS v aplikaci nemá samostatný datový tok pro světové ceny komodit.";
  return (
    <div className="rounded-2xl border border-dashed border-[hsl(var(--border)/0.75)] bg-card/90 p-6 text-sm text-muted-foreground space-y-3">
      <p className="leading-relaxed">{text}</p>
      <p className="leading-relaxed">
        Pro ceny komodit použijte záložky <strong>Pink Sheet</strong> nebo <strong>IMF indexy</strong>.
        Obecné statistiky BIS (kurzy, úvěry, CPI…) najdete v dedikovaném katalogu.
      </p>
      <Link
        to="/bis/catalog"
        className="inline-flex items-center gap-1.5 text-sm font-medium text-[hsl(var(--primary))] underline underline-offset-2"
      >
        Otevřít katalog BIS
      </Link>
    </div>
  );
}

function LinksPanel({ links }) {
  const items = [
    {
      title: "Commodity Markets (Pink Sheet + XLS)",
      href: links.commodity_markets,
      desc: "Měsíční PDF, Monthly/Annual XLS, zprávy CMO.",
    },
    {
      title: "Price Forecasts (archiv prognóz)",
      href: links.price_forecasts,
      desc: "Excel/PDF prognóz CMO od roku 1994.",
    },
    {
      title: "Pink Sheet PDF (aktuální)",
      href: links.pink_sheet_pdf,
      desc: "Tisková verze cen komodit.",
    },
    {
      title: "Podmínky použití dat",
      href: links.terms_of_use,
      desc: "World Bank dataset terms.",
    },
  ];
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {items.map((it) => (
        <a
          key={it.href}
          href={it.href}
          target="_blank"
          rel="noreferrer"
          className="rounded-xl border border-[hsl(var(--border)/0.75)] bg-card/90 p-4 shadow-sm hover:bg-[hsl(var(--primary-soft))] transition-colors"
        >
          <div className="flex items-start gap-2 font-medium text-sm">
            <ExternalLink className="h-4 w-4 shrink-0 mt-0.5" />
            {it.title}
          </div>
          <p className="mt-2 text-xs text-muted-foreground leading-relaxed">{it.desc}</p>
        </a>
      ))}
      <div className="rounded-xl border border-dashed border-[hsl(var(--border)/0.75)] p-4 text-xs text-muted-foreground">
        Data Pink Sheet a prognóz v aplikaci se stahují z oficiálních XLS na webu World Bank. Pro WDI
        indikátory použijte{" "}
        <Link to="/data360/catalog" className="underline">
          World Bank
        </Link>
        .
      </div>
    </div>
  );
}

export default function CommoditiesPage() {
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const { user, isSubscriber } = useAuth();
  const isAdmin = user?.role === "admin";

  const tab = params.get("tab") || "pink_sheet";
  const setTab = (id) => {
    const sp = new URLSearchParams(params);
    sp.set("tab", id);
    setParams(sp, { replace: true });
  };

  const [hub, setHub] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [search, setSearch] = useState("");
  const [openPaths, setOpenPaths] = useState(new Set());
  const [existing, setExisting] = useState(new Set());
  const [adding, setAdding] = useState({});
  const [previewKey, setPreviewKey] = useState(null);
  const [previewRow, setPreviewRow] = useState(null);
  const [previewData, setPreviewData] = useState(null);
  const [previewError, setPreviewError] = useState("");
  const [previewLoading, setPreviewLoading] = useState(false);
  const [addingToDash, setAddingToDash] = useState(false);
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

  const loadHub = useCallback(async () => {
    setLoading(true);
    try {
      const { data: hubData } = await api.get("/commodities/hub");
      const { data: catalogData } = await api.get("/commodities/catalog");
      setHub({ ...hubData, ...catalogData });
      setOpenPaths(new Set(allCategoryPathsFromTree(catalogData?.categories || [])));
      if (user) {
        try {
          const { data: srcs } = await api.get("/sources/catalog-stubs");
          setExisting(
            new Set(
              (srcs || [])
                .filter((s) => s.source_type === "worldbank_pink_sheet" || s.pink_sheet_code)
                .map((s) => String(s.pink_sheet_code || s.set_id || ""))
                .filter(Boolean)
            )
          );
        } catch {
          /* ignore */
        }
      }
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setLoading(false);
  }, [user]);

  useEffect(() => {
    void loadHub();
  }, [loadHub]);

  const refreshData = async () => {
    if (!isAdmin) {
      toast.error("Obnovení dat může provést pouze administrátor.");
      return;
    }
    setRefreshing(true);
    try {
      await api.post("/commodities/refresh", {});
      toast.success("Pink Sheet a prognózy byly staženy z World Bank.");
      await loadHub();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setRefreshing(false);
  };

  const activeTree = useMemo(() => tabTree(hub, tab), [hub, tab]);
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
      const parentSegments =
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

  const fetchPreview = async (row) => {
    setPreviewLoading(true);
    setPreviewData(null);
    setPreviewError("");
    try {
      const def = previewDefForRow(row);
      const body = buildCatalogPreviewBody(def, { ...row, kind: "set" });
      const { data } = await api.post("/catalog/preview", body);
      setPreviewData(normalizePreviewPayload(data, def.sourceType));
    } catch (e) {
      setPreviewError(formatApiErrorFromAxios(e));
    } finally {
      setPreviewLoading(false);
    }
  };

  const togglePreview = async (row) => {
    const sid = String(row.set_id || "").trim();
    if (!sid || row.kind !== "set") return;
    if (previewKey === sid) {
      setPreviewKey(null);
      setPreviewRow(null);
      setPreviewData(null);
      setPreviewError("");
      return;
    }
    setPreviewKey(sid);
    setPreviewRow(row);
    await fetchPreview(row);
  };

  const handleAddPreviewToDashboard = async ({ setPagePick } = {}) => {
    if (!previewRow || !previewData || previewError) return;
    setAddingToDash(true);
    try {
      const def = previewDefForRow(previewRow);
      await addCatalogPreviewToPersonalDashboard({
        api,
        nav,
        def,
        previewData,
        row: { ...previewRow, kind: "set", set_id: String(previewRow.set_id || "").trim() },
        feature: dashboardFeature,
        setPagePick,
      });
    } finally {
      setAddingToDash(false);
    }
  };

  const addSource = async (row) => {
    const sid = String(row.set_id || "").trim();
    if (!sid) return;
    setAdding((a) => ({ ...a, [sid]: true }));
    try {
      const kind = sid.startsWith("FCST|") ? "forecast" : "actual";
      const { data } = await api.post("/commodities/add-source", {
        set_id: sid,
        kind,
        name: row.name,
      });
      toast.success(`Přidáno: ${data.name}`);
      setExisting((s) => new Set([...s, sid]));
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
    setAdding((a) => ({ ...a, [sid]: false }));
  };

  const stats = hub?.stats || {};
  const links = hub?.links || {};

  return (
    <AppShell
      title="Komodity"
      subtitle={
        hub?.description_cs ||
        "World Bank Pink Sheet, CMO prognózy, IMF a BIS — kurátorovaný přehled cen komodit."
      }
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <CatalogBackToHubButton catalogId="commodities" fallbackPath="/search/catalog" />
          {isAdmin ? (
            <button
              type="button"
              onClick={() => refreshData()}
              disabled={refreshing}
              className="flex items-center gap-2 px-3 h-9 text-sm border border-[hsl(var(--border)/0.75)] bg-card/82 shadow-sm rounded-xl hover:bg-[hsl(var(--primary-soft))] disabled:opacity-50"
            >
              <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
              {refreshing ? "Stahuji XLS…" : "Obnovit z World Bank"}
            </button>
          ) : null}
        </div>
      }
    >
      {loading ? (
        <LoadingBlock label="Načítám katalog komodit…" />
      ) : (
        <>
          <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="Pink Sheet řady" value={stats.pink_sheet_series} />
            <StatCard label="CMO prognózy" value={stats.cmo_forecast_items} />
            <StatCard label="IMF indexy" value={stats.imf_commodity_indices} />
            <StatCard label="BIS řady" value={stats.bis_commodity_series} />
          </div>

          <TabBar tab={tab} setTab={setTab} />

          {tab === "links" ? (
            <LinksPanel links={links} />
          ) : tab === "bis" && !(hub?.bis_commodity_series || []).length ? (
            <BisEmptyPanel hub={hub} />
          ) : (
            <>
              <div className="mb-4 max-w-xl relative">
                <Search className="h-4 w-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
                <input
                  type="text"
                  className="w-full h-10 pl-9 pr-3 border border-[hsl(var(--border)/0.75)] rounded-xl text-sm bg-card shadow-sm"
                  placeholder="Hledat komoditu (ropa, měď, káva…)"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </div>

              <p className="mb-4 text-xs text-muted-foreground max-w-3xl leading-relaxed">
                <strong>Pink Sheet</strong> — měsíční nominální ceny z{" "}
                <a
                  href={links.commodity_markets}
                  target="_blank"
                  rel="noreferrer"
                  className="underline"
                >
                  World Bank Commodity Markets
                </a>
                . Klikněte na řadu pro náhled; admin může přidat zdroj na dashboard.
              </p>

              <div className="rounded-2xl border border-[hsl(var(--border)/0.75)] bg-card/90 shadow-sm overflow-hidden">
                {visibleRows.length === 0 ? (
                  <div className="p-8 text-sm text-muted-foreground text-center">
                    Žádná shoda. Zkuste jiný filtr nebo obnovte data (admin).
                  </div>
                ) : (
                  <ul className="divide-y divide-[hsl(var(--border)/0.5)]">
                    {visibleRows.map((row) => {
                      const sid = String(row.set_id || "");
                      const isSet = row.kind === "set";
                      const isOpenFolder = row.kind === "folder" && openPaths.has(row.path);
                      const pad = { paddingLeft: `${12 + row.depth * 18}px` };
                      return (
                        <li key={row.path} className="hover:bg-[hsl(var(--primary-soft)/0.35)]">
                          <div className="flex items-center gap-2 min-h-[44px] pr-3" style={pad}>
                            {row.kind === "folder" ? (
                              <button
                                type="button"
                                onClick={() => toggle(row.path)}
                                className="flex items-center gap-2 flex-1 py-2 text-left text-sm font-medium"
                              >
                                {isOpenFolder ? (
                                  <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
                                ) : (
                                  <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                                )}
                                <Folder className="h-4 w-4 shrink-0 text-amber-600" />
                                {row.name}
                              </button>
                            ) : (
                              <>
                                <button
                                  type="button"
                                  onClick={() => togglePreview(row)}
                                  className={`flex items-center gap-2 flex-1 py-2 text-left text-sm ${
                                    previewKey === sid ? "font-semibold text-[hsl(var(--primary))]" : ""
                                  }`}
                                >
                                  <FileBarChart2 className="h-4 w-4 shrink-0 text-emerald-600" />
                                  <span>{row.name}</span>
                                  {row.unit ? (
                                    <span className="text-[11px] font-mono text-muted-foreground">
                                      {row.unit}
                                    </span>
                                  ) : null}
                                </button>
                                {row.source_type === "imf" ? (
                                  <button
                                    type="button"
                                    className="text-xs px-2 h-7 rounded-lg border"
                                    onClick={() => nav("/imf/browse-tree")}
                                  >
                                    IMF
                                  </button>
                                ) : null}
                                {row.source_type === "bis" ? (
                                  <button
                                    type="button"
                                    className="text-xs px-2 h-7 rounded-lg border"
                                    onClick={() => nav("/bis/catalog")}
                                  >
                                    BIS
                                  </button>
                                ) : null}
                                {isAdmin &&
                                (row.source_type === "worldbank_pink_sheet" ||
                                  sid.startsWith("FCST|")) ? (
                                  <button
                                    type="button"
                                    disabled={adding[sid] || existing.has(sid)}
                                    onClick={() => addSource(row)}
                                    className="flex items-center gap-1 px-2 h-8 text-xs rounded-lg border disabled:opacity-50"
                                    title="Přidat jako zdroj"
                                  >
                                    {existing.has(sid) ? (
                                      <Check className="h-3.5 w-3.5" />
                                    ) : (
                                      <Plus className="h-3.5 w-3.5" />
                                    )}
                                  </button>
                                ) : null}
                              </>
                            )}
                          </div>
                          {isSet && previewKey === sid && (
                            <div className="px-4 pb-4 border-t border-[hsl(var(--border)/0.35)] bg-[hsl(var(--muted)/0.25)]">
                              <CatalogChartPreview
                                widgetId={`commodities-preview-${sid}`}
                                title={row.name || sid}
                                sourceType={previewDefForRow(row).sourceType}
                                catalogDef={previewDefForRow(row)}
                                catalogRow={{ ...row, kind: "set", set_id: sid }}
                                preferAradView
                                preview={
                                  previewError
                                    ? {
                                        error: previewError,
                                        source: {
                                          name: row.name,
                                          source_type: previewDefForRow(row).sourceType,
                                        },
                                      }
                                    : previewData
                                      ? {
                                          ...previewData,
                                          source: {
                                            name: row.name,
                                            source_type: previewDefForRow(row).sourceType,
                                          },
                                        }
                                      : {
                                          source: {
                                            name: row.name,
                                            source_type: previewDefForRow(row).sourceType,
                                          },
                                        }
                                }
                                previewError={previewError}
                                previewLoading={previewLoading}
                                sourcePreviewProps={{
                                  catalogValueDescriptor: previewData?.unit_label_cs
                                    ? `Jednotka: ${previewData.unit_label_cs}`
                                    : previewData?.unit
                                      ? `Jednotka: ${previewData.unit}`
                                      : null,
                                  catalogChartActions: buildCatalogChartActionsProps({
                                    feature: dashboardFeature,
                                    previewData,
                                    previewError,
                                    previewLoading,
                                    onAddToDashboard: handleAddPreviewToDashboard,
                                    addingToDashboard: addingToDash,
                                  }),
                                }}
                              />
                            </div>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            </>
          )}
        </>
      )}
    </AppShell>
  );
}

function TabBar({ tab, setTab }) {
  return (
    <div className="mb-4 flex flex-wrap gap-2">
      {TABS.map((t) => (
        <button
          key={t.id}
          type="button"
          onClick={() => setTab(t.id)}
          className={`px-3 h-9 rounded-xl text-sm border transition-colors ${
            tab === t.id
              ? "border-[hsl(var(--primary))] bg-[hsl(var(--primary-soft))] font-medium"
              : "border-[hsl(var(--border)/0.75)] bg-card/80 hover:bg-[hsl(var(--primary-soft)/0.5)]"
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
