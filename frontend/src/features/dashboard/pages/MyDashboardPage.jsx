import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { loadPersonalWidgetsProgressively, refreshPersonalWidget } from "@/lib/personalWidgetResolve";
import { mergeResolvedWidget, widgetInitialFromListRow, widgetPatchAffectsDataCache, mergeWidgetLayoutPatch, applyLocalWidgetReorder } from "@/lib/widgetSnapshot";
import { useScrollToWidgetFromHash } from "@/hooks/useScrollToWidgetFromHash";
import AppShell from "@/components/layout/AppShell";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccessContextOptional } from "@/contexts/FeatureAccessContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import AdminWidgetCanvas from "@/components/admin/AdminWidgetCanvas";
import HeadlineKpiStrip from "@/components/HeadlineKpiStrip";
import PersonalCatalogChartForm from "@/components/myDashboard/PersonalCatalogChartForm";
import PersonalComputedInlineForm from "@/components/myDashboard/PersonalComputedInlineForm";
import PersonalUploadChartForm from "@/components/myDashboard/PersonalUploadChartForm";
import DashboardPageAccessPanel from "@/components/myDashboard/DashboardPageAccessPanel";
import DashboardPresentationMode from "@/components/myDashboard/DashboardPresentationMode";
import DashboardAiChat from "@/components/myDashboard/DashboardAiChat";
import { getPresentableDashboardWidgets } from "@/lib/dashboardChartContracts";
import { Plus, Trash2, Pencil, Star, Sparkles, X, Presentation, Download, Loader2 } from "lucide-react";

const WIDGET_OPTION_KEYS = [
  { value: "text", labelKey: "pages.myDashboard.widgetText" },
  { value: "chart", labelKey: "pages.myDashboard.widgetChart" },
  { value: "computed_chart", labelKey: "pages.myDashboard.widgetComputed" },
  { value: "uploaded_data_chart", labelKey: "pages.myDashboard.widgetUpload" },
];

export default function MyDashboardPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const { user, ready, isSubscriber, openLogin, refreshUser } = useAuth();
  const feCtx = useFeatureAccessContextOptional();
  const effective = feCtx?.effective;
  const canPersonal = feCtx?.accessMapReady && effective?.personal_dashboard?.allowed === true;
  const { allowed: canRss } = useFeatureAccess("rss_monitoring");
  const widgetOptions = useMemo(() => {
    const o = WIDGET_OPTION_KEYS.map((item) => ({ value: item.value, label: t(item.labelKey) }));
    if (canRss) o.push({ value: "rss_monitoring", label: t("pages.myDashboard.widgetRss") });
    return o;
  }, [canRss, t]);
  const [pages, setPages] = useState([]);
  const [selPage, setSelPage] = useState(null);
  const [pageData, setPageData] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [newPageOpen, setNewPageOpen] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [addWidgetOpen, setAddWidgetOpen] = useState(false);
  const [wType, setWType] = useState("text");
  const [wTitle, setWTitle] = useState("");
  const [wDesc, setWDesc] = useState("");
  const [wTextBody, setWTextBody] = useState("");
  const [wConfig, setWConfig] = useState("{}");
  const [uploads, setUploads] = useState([]);
  const [computedList, setComputedList] = useState([]);
  const [computedId, setComputedId] = useState("");
  const [computedChartType, setComputedChartType] = useState("line");
  const [computedLimit, setComputedLimit] = useState(0);
  const [rssFeeds, setRssFeeds] = useState([]);
  const [rssSelected, setRssSelected] = useState([]);
  const [rssItemLimit, setRssItemLimit] = useState(15);
  const [rssDays, setRssDays] = useState("");
  const [rssQ, setRssQ] = useState("");
  const [prefs, setPrefs] = useState({ open_personal: false, default_id: null });
  const [presentationOpen, setPresentationOpen] = useState(false);
  const [dashboardAiOpen, setDashboardAiOpen] = useState(false);
  const [pptxExporting, setPptxExporting] = useState(false);
  /** Sekvence načtení widgetů stránky — odděleně od patch, aby React Strict Mode nezahodil odpovědi. */
  const pageWidgetsLoadSeqRef = useRef(0);
  const patchWidgetSeqRef = useRef(0);
  const pageDataRef = useRef(null);
  useEffect(() => {
    pageDataRef.current = pageData;
  }, [pageData]);
  const requestedPageId = String(searchParams.get("page") || "").trim();

  const loadPages = useCallback(async () => {
    const { data } = await api.get("/me/dashboard/pages");
    setPages(Array.isArray(data) ? data : []);
    return data;
  }, []);

  const loadPrefs = useCallback(async () => {
    const { data } = await api.get("/me/preferences");
    setPrefs({
      open_personal: !!data.open_personal_dashboard_on_login,
      default_id: data.default_dashboard_page_id || null,
    });
  }, []);

  const loadUploads = useCallback(async () => {
    try {
      const { data } = await api.get("/me/uploads");
      setUploads(Array.isArray(data) ? data : []);
    } catch {
      setUploads([]);
    }
  }, []);

  const loadWidgetsFor = useCallback(
    async (pageId) => {
      if (!pageId) {
        setPageData(null);
        return;
      }
      setLoadError(null);
      pageWidgetsLoadSeqRef.current += 1;
      const seq = pageWidgetsLoadSeqRef.current;
      try {
        const { data: raw } = await api.get(`/me/dashboard/pages/${pageId}/widgets`);
        const list = Array.isArray(raw) ? raw : [];
        setPageData({
          widgets: list.map((w) => widgetInitialFromListRow(w)),
          default_chart_type: "line",
          default_chart_frequency: undefined,
        });
        loadPersonalWidgetsProgressively(
          list,
          (id, next) => {
            if (seq !== pageWidgetsLoadSeqRef.current) return;
            setPageData((prev) => {
              if (!prev) return prev;
              return {
                ...prev,
                widgets: (prev.widgets || []).map((pw) => (pw.id === id ? next : pw)),
              };
            });
          },
          { isStale: () => seq !== pageWidgetsLoadSeqRef.current }
        );
      } catch (e) {
        if (seq !== pageWidgetsLoadSeqRef.current) return;
        setPageData({ widgets: [], default_chart_type: "line", default_chart_frequency: undefined });
        setLoadError(formatApiErrorFromAxios(e) || "Nepodařilo se načíst widgety.");
      }
    },
    [setPageData]
  );

  useEffect(() => {
    if (!ready) return;
    if (!user || user === false) return;
    if (!isSubscriber) return;
    // Čekat na GET /feature-access/effective — jinak můžeme paralelně volat /me/* a zaseknout UI v 401→refresh.
    if (feCtx != null && !feCtx.accessMapReady) return;
    if (feCtx?.accessMapReady && !canPersonal) {
      setLoading(false);
      return;
    }
    setLoading(true);
    (async () => {
      try {
        await loadPrefs();
        const pl = await loadPages();
        if (pl?.length) {
          const d = (await api.get("/me/preferences")).data;
          const def = d?.default_dashboard_page_id;
          const first =
            pl.find((p) => p.id === requestedPageId) ||
            pl.find((p) => p.id === def) ||
            pl[0];
          setSelPage(first.id);
        }
        setLoadError(null);
      } catch (e) {
        setLoadError(
          formatApiErrorFromAxios(e) || e?.response?.data?.detail || "Chyba načtení"
        );
      } finally {
        setLoading(false);
      }
    })();
  }, [ready, user, isSubscriber, canPersonal, feCtx?.accessMapReady, loadPages, loadPrefs, requestedPageId]);

  useEffect(() => {
    if (!selPage) {
      setPageData(null);
      return;
    }
    loadWidgetsFor(selPage);
  }, [selPage, loadWidgetsFor]);

  const widgetScrollSig = useMemo(
    () => (Array.isArray(pageData?.widgets) ? pageData.widgets.map((w) => w?.id).filter(Boolean).join(",") : ""),
    [pageData?.widgets]
  );
  useScrollToWidgetFromHash({ loading, widgetSignature: widgetScrollSig });

  useEffect(() => {
    if (!canPersonal || !isSubscriber) return;
    loadUploads();
  }, [canPersonal, isSubscriber, loadUploads]);

  const loadComputedList = useCallback(async () => {
    try {
      const { data } = await api.get("/computed");
      const list = Array.isArray(data) ? data : [];
      setComputedList(list);
      setComputedId((prev) => {
        // Neztratit už vybraný/vytvořený výpočet při asynchronním refreshi seznamu.
        if (prev) return prev;
        return list[0]?.id || "";
      });
    } catch (e) {
      setComputedList([]);
      // Zachovat aktuální volbu i při dočasném selhání načtení seznamu.
      toast.error(formatApiErrorFromAxios(e) || "Nelze načíst seznam vlastních výpočtů.");
    }
  }, []);

  useEffect(() => {
    if (!ready || !user || user === false) return;
    if (!canPersonal || !isSubscriber) return;
    loadComputedList();
  }, [ready, user, canPersonal, isSubscriber, loadComputedList]);

  /** Při otevření formuláře znovu načíst (stejná data jako u admina na /computed). */
  useEffect(() => {
    if (!addWidgetOpen || wType !== "computed_chart") return;
    loadComputedList();
  }, [addWidgetOpen, wType, loadComputedList]);

  useEffect(() => {
    if (!addWidgetOpen || wType !== "rss_monitoring" || !canRss) return;
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/rss/feeds");
        if (!cancelled) setRssFeeds(Array.isArray(data) ? data : []);
      } catch {
        if (!cancelled) setRssFeeds([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [addWidgetOpen, wType, canRss]);

  const createPage = async () => {
    if (!newTitle.trim()) {
      toast.error("Vyplňte název stránky");
      return;
    }
    try {
      const { data } = await api.post("/me/dashboard/pages", { title: newTitle.trim() });
      await loadPages();
      setSelPage(data.id);
      setNewPageOpen(false);
      setNewTitle("");
      toast.success("Stránka vytvořena");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se vytvořit stránku");
    }
  };

  const removePage = async (pid) => {
    if (!window.confirm("Opravdu smazat stránku a všechny widgety?")) return;
    try {
      await api.delete(`/me/dashboard/pages/${pid}`);
      await loadPages();
      if (selPage === pid) setSelPage(null);
      toast.success("Stránka smazána");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba mazání");
    }
  };

  const setDefaultPage = async (pid) => {
    try {
      await api.patch("/me/preferences", {
        default_dashboard_page_id: pid,
        open_personal_dashboard_on_login: prefs.open_personal,
      });
      await loadPrefs();
      await refreshUser();
      setPages((prev) => prev.map((p) => ({ ...p, is_default: p.id === pid })));
      toast.success("Výchozí stránka uložena");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  /** Uloží open_personal_dashboard_on_login (API pole; historicky „on login“, ve skutečnosti při otevření `/`). */
  const toggleOpenPersonalOnAppStart = async (v) => {
    try {
      const { data: me } = await api.patch("/me/preferences", {
        open_personal_dashboard_on_login: v,
        default_dashboard_page_id: prefs.default_id || selPage,
      });
      setPrefs((p) => ({ ...p, open_personal: v }));
      if (me?.id) {
        await refreshUser();
      }
      toast.success(
        v
          ? "Hotovo. Při příštím otevření aplikace se zobrazí váš dashboard."
          : "Hotovo. Aplikace se bude otevírat na veřejném přehledu."
      );
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba ukládání");
    }
  };

  const postNewWidget = async (body) => {
    if (!selPage) return;
    try {
      await api.post(`/me/dashboard/pages/${selPage}/widgets`, body);
      setAddWidgetOpen(false);
      setWTitle("");
      setWDesc("");
      setWTextBody("");
      setWConfig("{}");
      setComputedId("");
      await loadWidgetsFor(selPage);
      toast.success("Widget přidán");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nelze přidat widget");
    }
  };

  const addWidgetFromForm = async () => {
    if (wType === "chart" || wType === "uploaded_data_chart") {
      toast.error("Použijte průvodce výběrem dat u typu „Datový graf“ nebo „Graf z mých dat“.");
      return;
    }
    if (wType === "text") {
      await postNewWidget({
        type: "text",
        title: wTitle || "Poznámka",
        description: wDesc,
        config: { content: wTextBody || "" },
      });
      return;
    }
    if (wType === "computed_chart") {
      if (!computedId) {
        toast.error("Vyberte vlastní výpočet (nebo klikněte „Vytvořit výpočet a vybrat“).");
        return;
      }
      await postNewWidget({
        type: "computed_chart",
        title: wTitle || "Výpočtový graf",
        description: wDesc,
        config: {
          computed_id: computedId,
          view: "chart",
          chart_type: computedChartType,
          limit: computedLimit > 0 ? computedLimit : 0,
        },
      });
      return;
    }
    if (wType === "rss_monitoring") {
      const daysNum = rssDays === "" ? null : Number(rssDays);
      await postNewWidget({
        type: "rss_monitoring",
        title: wTitle || "RSS novinky",
        description: wDesc,
        config: {
          selected_feed_ids: rssSelected,
          categories: [],
          q: rssQ.trim(),
          item_limit: rssItemLimit > 0 ? rssItemLimit : 15,
          days: Number.isFinite(daysNum) && daysNum > 0 ? daysNum : null,
        },
      });
      setRssSelected([]);
      setRssQ("");
      setRssDays("");
      setRssItemLimit(15);
      return;
    }
    let config = {};
    try {
      config = wConfig.trim() ? JSON.parse(wConfig) : {};
    } catch {
      toast.error("Neplatný JSON v konfiguraci");
      return;
    }
    await postNewWidget({
      type: wType,
      title: wTitle || "Widget",
      description: wDesc,
      config,
    });
  };

  const onCatalogChartApply = async ({ title, description, config, widgetType = "chart" }) => {
    await postNewWidget({ type: widgetType, title, description, config });
  };

  const onUploadChartApply = async ({ title, description, config }) => {
    await postNewWidget({ type: "user_upload_chart", title, description, config });
  };

  const reorder = async (ids, widgetLayout) => {
    if (!selPage) return;
    try {
      const body = { page_id: selPage, widget_ids: ids };
      if (widgetLayout && Object.keys(widgetLayout).length > 0) {
        body.widget_layout = widgetLayout;
      }
      await api.post("/me/dashboard/widgets/reorder", body);
      setPageData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          widgets: applyLocalWidgetReorder(prev.widgets, ids, widgetLayout),
        };
      });
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba řazení");
    }
  };

  const refreshWidget = useCallback(async (widgetId) => {
    const prev = pageDataRef.current;
    const current = (prev?.widgets || []).find((w) => w.id === widgetId);
    if (!current) return;
    setPageData((p) =>
      p
        ? {
            ...p,
            widgets: p.widgets.map((w) =>
              w.id === widgetId ? { ...w, _refreshing: true, refresh_error: null } : w
            ),
          }
        : p
    );
    try {
      const { data: resolved } = await refreshPersonalWidget(widgetId);
      setPageData((p) =>
        p
          ? {
              ...p,
              widgets: p.widgets.map((w) =>
                w.id === widgetId ? mergeResolvedWidget(w, resolved) : w
              ),
            }
          : p
      );
    } catch (e) {
      const msg =
        formatApiErrorFromAxios(e) ||
        e?.response?.data?.detail ||
        "Aktualizace selhala, zobrazuji poslední uložená data.";
      setPageData((p) =>
        p
          ? {
              ...p,
              widgets: p.widgets.map((w) =>
                w.id === widgetId
                  ? {
                      ...w,
                      _refreshing: false,
                      refresh_error: typeof msg === "string" ? msg : "Aktualizace selhala.",
                    }
                  : w
              ),
            }
          : p
      );
    }
  }, []);

  const patchWidget = async (widgetId, patch) => {
    let saved;
    try {
      ({ data: saved } = await api.patch(`/me/dashboard/widgets/${widgetId}`, patch));
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se uložit widget.");
      return false;
    }
    const prev = pageDataRef.current;
    if (!prev) return;

    const movedPageId =
      patch?.page_id && String(patch.page_id).trim() && String(patch.page_id).trim() !== String(selPage || "");
    if (movedPageId) {
      const targetTitle =
        pages.find((p) => p.id === String(patch.page_id).trim())?.title || "jinou stránku";
      setPageData({
        ...prev,
        widgets: (prev.widgets || []).filter((w) => w.id !== widgetId),
      });
      toast.success(`Widget přesunut na „${targetTitle}".`);
      return true;
    }

    const merged = (prev.widgets || []).find((x) => x.id === widgetId);
    if (!merged) return true;

    if (!widgetPatchAffectsDataCache(patch)) {
      setPageData({
        ...prev,
        widgets: (prev.widgets || []).map((w) =>
          w.id === widgetId ? mergeWidgetLayoutPatch(w, saved, patch) : w
        ),
      });
      return true;
    }

    const rowSpanFromPatch = patch.rowSpan !== undefined ? patch.rowSpan : (saved?.rowSpan !== undefined ? saved.rowSpan : undefined);
    const hadData = merged.data != null && !merged.data?.error;
    const next = mergeWidgetLayoutPatch(merged, saved, patch);
    setPageData({
      ...prev,
      widgets: (prev.widgets || []).map((w) =>
        w.id === widgetId ? { ...next, _loading: !hadData } : w
      ),
    });
    patchWidgetSeqRef.current += 1;
    const g = patchWidgetSeqRef.current;
    try {
      const { data: resolved } = await api.post("/me/dashboard/render-widget", { id: widgetId });
      if (g !== patchWidgetSeqRef.current) return true;
      setPageData((p) =>
        p
          ? {
              ...p,
              widgets: p.widgets.map((pw) =>
                pw.id === widgetId
                  ? {
                      ...mergeResolvedWidget(pw, resolved),
                      rowSpan: rowSpanFromPatch !== undefined ? rowSpanFromPatch : pw.rowSpan,
                    }
                  : pw
              ),
            }
          : p
      );
    } catch {
      if (g !== patchWidgetSeqRef.current) return true;
      setPageData((p) =>
        p
          ? {
              ...p,
              widgets: p.widgets.map((pw) =>
                pw.id === widgetId
                  ? { ...next, data: merged.data ?? pw.data, _loading: false }
                  : pw
              ),
            }
          : p
      );
    }
    return true;
  };

  const removeWidget = useCallback(
    async (widgetId) => {
      await api.delete(`/me/dashboard/widgets/${widgetId}`);
      if (selPage) {
        await loadWidgetsFor(selPage);
      }
      toast.success("Widget odstraněn");
    },
    [selPage, loadWidgetsFor]
  );

  const currentPage = useMemo(() => pages.find((p) => p.id === selPage) || null, [pages, selPage]);
  const currentPageTitle = currentPage?.title || "Dashboard";
  const presentableDashboardWidgets = useMemo(
    () => getPresentableDashboardWidgets(pageData?.widgets || []),
    [pageData?.widgets],
  );
  const hasPresentableWidgets = presentableDashboardWidgets.length > 0;

  const handleExportPptx = useCallback(async () => {
    if (pptxExporting) return;
    if (!hasPresentableWidgets) {
      toast.info("Zatím není co exportovat — přidejte na stránku alespoň jeden graf. (Textové widgety se do prezentace nedávají.)");
      return;
    }
    setPptxExporting(true);
    try {
      const { exportDashboardPageToPptx } = await import("@/lib/dashboardPresentationExport");
      const result = await exportDashboardPageToPptx({
        widgets: presentableDashboardWidgets,
        pageTitle: currentPageTitle,
      });
      toast.success(`Prezentace exportována (${result.exported}/${result.total} grafů).`);
    } catch (err) {
      toast.error(err?.message || "Export PPTX se nepodařil.");
    } finally {
      setPptxExporting(false);
    }
  }, [currentPageTitle, hasPresentableWidgets, pptxExporting, presentableDashboardWidgets]);

  if (!ready) {
    return (
      <AppShell title={t("pages.myDashboard.title")} hideAds>
        <div className="text-sm text-slate-500">{t("common.loading")}</div>
      </AppShell>
    );
  }
  if (!user || user === false) {
    return (
      <AppShell title={t("pages.myDashboard.personalTitle")} hideAds>
        <div className="max-w-lg soft-card p-8 space-y-4 text-slate-700">
          <div className="flex items-center gap-2 text-slate-800 font-medium">
            <Sparkles className="h-5 w-5 text-[hsl(var(--primary))]" />
            {t("pages.myDashboard.loginRequired")}
          </div>
          <p className="text-sm leading-relaxed">{t("pages.myDashboard.loginRequiredHint")}</p>
          <div className="flex flex-wrap gap-3 pt-2">
            <button
              type="button"
              onClick={() => openLogin()}
              className="inline-flex items-center justify-center min-h-[44px] px-5 rounded-xl text-sm font-semibold text-white"
              style={{
                background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
                boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
              }}
              data-testid="mydash-cta-login"
            >
              {t("pages.myDashboard.loginCta")}
            </button>
            <Link
              to="/predplatne"
              className="inline-flex items-center justify-center min-h-[44px] px-5 rounded-xl text-sm font-semibold border border-[hsl(var(--primary)/0.4)] text-[hsl(var(--primary-deep))] bg-white/80 hover:bg-white"
              data-testid="mydash-cta-predplatne-anon"
            >
              {t("pages.subscription.cta")}
            </Link>
          </div>
        </div>
      </AppShell>
    );
  }
  if (!isSubscriber) {
    return (
      <AppShell title={t("pages.myDashboard.title")} hideAds>
        <div className="max-w-lg soft-card p-8 space-y-4 text-slate-700">
          <p className="text-sm font-medium text-slate-800">{t("pages.myDashboard.subscriberRequired")}</p>
          <p className="text-sm leading-relaxed text-slate-600">{t("pages.myDashboard.subscriberHint")}</p>
          <Link
            to="/predplatne"
            className="inline-flex items-center justify-center min-h-[44px] px-5 rounded-xl text-sm font-semibold text-white w-fit"
            style={{
              background: "linear-gradient(135deg, hsl(202 95% 58%), hsl(208 85% 45%))",
              boxShadow: "0 2px 8px hsl(202 90% 45% / 0.35)",
            }}
            data-testid="mydash-cta-predplatne-free"
          >
            {t("pages.subscription.cta")}
          </Link>
        </div>
      </AppShell>
    );
  }
  if (feCtx?.accessMapReady && !canPersonal) {
    return (
      <AppShell title={t("pages.myDashboard.title")} hideAds>
        <p className="text-sm text-slate-600">{t("pages.myDashboard.notAllowed")}</p>
        <Link to="/" className="text-sm text-primary underline mt-2 inline-block">{t("pages.myDashboard.backOverview")}</Link>
      </AppShell>
    );
  }
  if (loading) {
    return (
      <AppShell title={t("pages.myDashboard.title")} hideAds>
        <div className="text-sm text-slate-500">{t("common.loading")}</div>
      </AppShell>
    );
  }
  if (loadError) {
    return (
      <AppShell title={t("pages.myDashboard.title")} hideAds>
        <div className="text-sm text-red-700">{loadError}</div>
      </AppShell>
    );
  }

  return (
    <AppShell
      title={t("pages.myDashboard.title")}
      subtitle={t("pages.myDashboard.subtitle")}
      hideAds
    >
      <div className="copper-text-fix-scope my-dashboard-scope">
      <div className="flex flex-col gap-4 mb-6">
        <div className="flex flex-wrap items-center gap-2">
          {pages.map((p) => (
            <div key={p.id} className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setSelPage(p.id)}
                className={`px-3 py-1.5 rounded-xl text-sm border ${
                  selPage === p.id
                    ? "border-[hsl(var(--primary))] bg-white shadow-sm"
                    : "border-border/70 bg-white/50"
                }`}
              >
                {p.title}
                {p.is_default ? " ★" : ""}
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={() => setNewPageOpen(true)}
            className="inline-flex items-center gap-1 px-3 py-1.5 rounded-xl text-sm border border-dashed border-border/80 text-slate-600 hover:bg-white/60"
          >
            <Plus className="h-3.5 w-3.5" /> Nová stránka
          </button>
        </div>

        {selPage && (
          <div className="flex flex-wrap gap-2 items-center text-sm text-slate-600">
            <label
              className="inline-flex items-center gap-2 cursor-pointer"
              title="Když aplikaci příště otevřete, zobrazí se nejdříve tato osobní stránka místo veřejného přehledu."
            >
              <input
                type="checkbox"
                checked={!!prefs.open_personal}
                onChange={(e) => toggleOpenPersonalOnAppStart(e.target.checked)}
              />
              Otevírat aplikaci na mém dashboardu
            </label>
            <button
              type="button"
              onClick={() => setDefaultPage(selPage)}
              className="inline-flex items-center gap-1 ml-2 px-2 py-1 rounded-lg border border-border/60 text-xs"
              title="Nastaví, kterou stránku otevře osobní dashboard a která se použije pro volbu vlevo."
            >
              <Star className="h-3.5 w-3.5" /> Tuto stránku jako výchozí
            </button>
            <button
              type="button"
              onClick={() => {
                const name = window.prompt("Nový název stránky", pages.find((x) => x.id === selPage)?.title || "");
                if (name) {
                  api
                    .patch(`/me/dashboard/pages/${selPage}`, { title: name })
                    .then(() => loadPages())
                    .then(() => toast.success("Přejmenováno"));
                }
              }}
              className="inline-flex items-center gap-1 px-2 py-1 rounded-lg border text-xs"
            >
              <Pencil className="h-3.5 w-3.5" /> Přejmenovat
            </button>
            <button
              type="button"
              onClick={() => removePage(selPage)}
              className="inline-flex items-center gap-1 px-2 py-1 rounded-lg border border-red-200 text-red-800 text-xs"
            >
              <Trash2 className="h-3.5 w-3.5" /> Smazat stránku
            </button>
            {(currentPage?.access_mode === "public" || currentPage?.share_token) ? (
              <Link
                to={
                  currentPage?.share_token
                    ? `/shared/dashboard/${encodeURIComponent(currentPage.share_token)}`
                    : `/shared/page/${encodeURIComponent(selPage)}`
                }
                target="_blank"
                rel="noreferrer"
                className="ml-auto text-xs text-slate-500 hover:underline"
                title="Otevřít veřejný náhled této stránky v novém okně"
              >
                Veřejný přehled
              </Link>
            ) : (
              <span
                className="ml-auto text-xs text-slate-400"
                title="Nejdřív nastavte přístup stránky na Veřejný nebo zapněte sdílený odkaz"
              >
                Veřejný přehled
              </span>
            )}
          </div>
        )}

        {newPageOpen && (
          <div className="soft-card p-4 max-w-md">
            <div className="text-sm font-medium mb-2">Nová stránka</div>
            <input
              className="w-full border rounded-lg px-3 py-2 text-sm mb-2"
              placeholder="Název (např. Banky, Makro)"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
            />
            <div className="flex gap-2">
              <button type="button" className="btn-primary text-sm py-1.5 px-3" onClick={createPage}>
                Vytvořit
              </button>
              <button type="button" className="text-sm text-slate-500" onClick={() => setNewPageOpen(false)}>
                Zrušit
              </button>
            </div>
          </div>
        )}
      </div>

      {selPage && pageData && (
        <>
          <DashboardPageAccessPanel
            isOwner={Boolean(
              user?.id &&
                String(pages.find((p) => p.id === selPage)?.user_id || "") === String(user.id)
            )}
            pageId={selPage}
            page={pages.find((p) => p.id === selPage)}
            onUpdated={(data) => {
              setPages((prev) => prev.map((p) => (p.id === data.id ? { ...p, ...data } : p)));
            }}
            widgets={pageData.widgets || []}
            onPatchWidget={patchWidget}
          />

          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-lg font-serif text-slate-800">Widgety</h2>
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => setDashboardAiOpen((value) => !value)}
                className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-sky-200 bg-white px-3 text-xs font-semibold text-sky-800 shadow-sm hover:bg-sky-50 disabled:opacity-50"
                title="Ptát se AI nad grafy na této stránce, nebo požádat o přidání nového grafu"
              >
                <Sparkles className="h-4 w-4" />
                AI nad dashboardem
              </button>
              <button
                type="button"
                onClick={() => {
                  // Prezentace i PPTX zobrazují jen grafové widgety (ne text/poznámky). Když žádný
                  // takový na stránce není, tlačítko dřív jen „zšedlo" a klik nic neudělal — teď
                  // dá jasnou zpětnou vazbu místo zdánlivě mrtvého tlačítka.
                  if (!hasPresentableWidgets) {
                    toast.info("Zatím není co prezentovat — přidejte na stránku alespoň jeden graf.");
                    return;
                  }
                  setPresentationOpen(true);
                }}
                className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 text-xs font-semibold text-slate-700 shadow-sm hover:bg-slate-50 disabled:opacity-50"
                title="Spustit prezentační mód (fullscreen)"
              >
                <Presentation className="h-4 w-4" />
                Prezentovat
              </button>
              <button
                type="button"
                onClick={() => void handleExportPptx()}
                disabled={pptxExporting}
                className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 text-xs font-semibold text-slate-700 shadow-sm hover:bg-slate-50 disabled:opacity-50"
                title="Exportovat grafy dashboardu jako PPTX (stáhne se soubor)"
              >
                {pptxExporting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
                Export PPTX
              </button>
            </div>
          </div>

          {dashboardAiOpen ? (
            <div className="mb-4">
              <DashboardAiChat
                widgets={presentableDashboardWidgets}
                pageId={selPage}
                pageTitle={currentPageTitle}
                defaultScope="dashboard"
                onClose={() => setDashboardAiOpen(false)}
                onWidgetsChanged={() => loadWidgetsFor(selPage)}
              />
            </div>
          ) : null}

          {addWidgetOpen && (
            <div className="soft-card p-4 mb-4 text-sm max-w-2xl space-y-3 relative">
              <button
                type="button"
                onClick={() => setAddWidgetOpen(false)}
                className="absolute top-2 right-2 p-1 rounded-md text-slate-500 hover:bg-slate-100 hover:text-slate-800"
                aria-label="Zavřít přidávání widgetu"
                title="Zavřít"
              >
                <X className="h-5 w-5" />
              </button>
              <div className="pr-8">
                <label className="block text-xs text-slate-500 mb-1">Typ widgetu</label>
                <select
                  className="w-full border rounded-lg px-2 py-1.5"
                  value={wType}
                  onChange={(e) => setWType(e.target.value)}
                >
                  {widgetOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>

              {wType === "chart" && (
                <PersonalCatalogChartForm
                  onApply={(payload) => onCatalogChartApply(payload)}
                  disabled={!selPage}
                />
              )}

              {wType === "uploaded_data_chart" && (
                <PersonalUploadChartForm
                  uploads={uploads}
                  onUploadsRefresh={loadUploads}
                  onApply={(payload) => onUploadChartApply(payload)}
                  disabled={!selPage}
                />
              )}

              {wType !== "chart" && wType !== "uploaded_data_chart" && (
                <>
                  <div>
                    <label className="block text-xs text-slate-500 mb-1">Název</label>
                    <input
                      className="w-full border rounded-lg px-2 py-1.5"
                      value={wTitle}
                      onChange={(e) => setWTitle(e.target.value)}
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-slate-500 mb-1">Popisek (volitelné)</label>
                    <textarea
                      className="w-full border rounded-lg px-2 py-1.5 min-h-[64px]"
                      value={wDesc}
                      onChange={(e) => setWDesc(e.target.value)}
                    />
                  </div>
                </>
              )}

              {wType === "text" && (
                <div>
                  <label className="block text-xs text-slate-500 mb-1">Text / poznámka</label>
                  <textarea
                    className="w-full border rounded-lg px-2 py-1.5 min-h-[100px]"
                    placeholder="Nadpis stránky, komentář, poznámka…"
                    value={wTextBody}
                    onChange={(e) => setWTextBody(e.target.value)}
                  />
                </div>
              )}

              {wType === "rss_monitoring" && (
                <div className="space-y-2 border border-border/60 rounded-xl p-3 bg-slate-50/50">
                  <div className="text-xs font-medium text-slate-700">Zdroje (prázdné = všechny dostupné)</div>
                  <div className="max-h-40 overflow-y-auto border rounded-lg p-2 bg-white space-y-1">
                    {rssFeeds.length === 0 ? (
                      <span className="text-xs text-slate-500">Žádné feedy — přidejte je výše.</span>
                    ) : (
                      rssFeeds.map((f) => (
                        <label key={f.id} className="flex items-center gap-2 text-xs cursor-pointer">
                          <input
                            type="checkbox"
                            checked={rssSelected.includes(f.id)}
                            onChange={(e) => {
                              if (e.target.checked) setRssSelected((s) => [...s, f.id]);
                              else setRssSelected((s) => s.filter((x) => x !== f.id));
                            }}
                          />
                          <span className="truncate" title={f.url}>
                            {f.name} ({f.scope === "global" ? "globální" : "vlastní"})
                          </span>
                        </label>
                      ))
                    )}
                  </div>
                  <label className="block text-[11px] text-slate-600">
                    Max. položek (1–50)
                    <input
                      type="number"
                      min={1}
                      max={50}
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      value={rssItemLimit}
                      onChange={(e) => setRssItemLimit(Number(e.target.value) || 15)}
                    />
                  </label>
                  <label className="block text-[11px] text-slate-600">
                    Posledních X dní (volitelné)
                    <input
                      type="number"
                      min={1}
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      placeholder="např. 30"
                      value={rssDays}
                      onChange={(e) => setRssDays(e.target.value)}
                    />
                  </label>
                  <label className="block text-[11px] text-slate-600">
                    Klíčová slova (title / souhrn)
                    <input
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      value={rssQ}
                      onChange={(e) => setRssQ(e.target.value)}
                    />
                  </label>
                </div>
              )}

              {wType === "computed_chart" && (
                <div className="space-y-2 border border-border/60 rounded-xl p-3 bg-slate-50/50">
                  <div className="text-xs font-medium text-slate-700">Vlastní výpočet</div>
                  <PersonalComputedInlineForm
                    onCreated={(c) => {
                      if (c?.id) setComputedId(c.id);
                      loadComputedList();
                    }}
                    disabled={!selPage}
                  />
                  <div className="text-[11px] text-slate-500 pt-1 border-t border-border/40">
                    Nebo vyberte dříve uložený výpočet:
                  </div>
                  <label className="block text-[11px] text-slate-600">
                    Výběr výpočtu
                    <select
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      value={computedId}
                      onChange={(e) => setComputedId(e.target.value)}
                    >
                      <option value="">— vyberte —</option>
                      {computedList.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name || c.id}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="block text-[11px] text-slate-600">
                    Typ grafu
                    <select
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      value={computedChartType}
                      onChange={(e) => setComputedChartType(e.target.value)}
                    >
                      <option value="line">Čára</option>
                      <option value="bar">Sloupce</option>
                      <option value="area">Plocha</option>
                    </select>
                  </label>
                  <label className="block text-[11px] text-slate-600">
                    Limit bodů (0 = vše)
                    <input
                      type="number"
                      min={0}
                      className="mt-1 w-full border rounded-lg px-2 py-1.5 text-sm"
                      value={computedLimit}
                      onChange={(e) => setComputedLimit(Number(e.target.value) || 0)}
                    />
                  </label>
                </div>
              )}

              {wType !== "chart" && wType !== "uploaded_data_chart" && (
                <button
                  type="button"
                  className="btn-primary text-sm py-1.5 px-3 disabled:opacity-60"
                  onClick={addWidgetFromForm}
                  disabled={wType === "computed_chart" && !computedId}
                >
                  Přidat widget
                </button>
              )}
            </div>
          )}

          {/* KPI dlaždice stránky — stejná komponenta jako na veřejném přehledu, jen
              vázaná na osobní stránku. Editace je povolená, protože stránku vlastní
              přihlášený uživatel (server to znovu ověřuje přes requireOwnedPage). */}
          {selPage && <HeadlineKpiStrip mode="personal" pageId={selPage} isAdmin />}

          {pageData.widgets?.length ? (
            <AdminWidgetCanvas
              widgets={pageData.widgets}
              defaultChartType={pageData.default_chart_type || "line"}
              defaultChartFrequency={pageData.default_chart_frequency}
              isAdmin
              aradMultiSeriesHelpContext="personal_dashboard"
              dashboardSharePageId={selPage}
              personalDashboardPages={pages}
              currentDashboardPageId={selPage}
              pageAllowEmbed={Boolean(pages.find((p) => p.id === selPage)?.allow_embed)}
              pageShareToken={pages.find((p) => p.id === selPage)?.share_token || ""}
              onReorder={reorder}
              onPatch={patchWidget}
              onDelete={removeWidget}
              onWidgetRefresh={refreshWidget}
            />
          ) : (
            <div className="text-sm text-slate-500 border border-dashed rounded-xl p-8 text-center">
              Zatím žádné widgety — použijte tlačítko + vpravo dole.
            </div>
          )}

          <button
            type="button"
            onClick={() => setAddWidgetOpen(true)}
            className="fixed bottom-24 right-5 z-[35] flex h-16 w-16 items-center justify-center rounded-full border border-border bg-white text-slate-800 shadow-lg transition hover:bg-slate-50 hover:shadow-md md:bottom-8"
            title="Přidat widget"
            aria-label="Přidat widget"
            data-testid="mydash-quick-add-widget"
          >
            <Plus className="h-8 w-8" strokeWidth={2} />
          </button>

          <DashboardPresentationMode
            open={presentationOpen}
            widgets={presentableDashboardWidgets}
            pageId={selPage}
            pageTitle={currentPageTitle}
            defaultChartType={pageData.default_chart_type || "line"}
            defaultChartFrequency={pageData.default_chart_frequency}
            onClose={() => setPresentationOpen(false)}
          />
        </>
      )}

      {!selPage && pages.length === 0 && (
        <p className="text-sm text-slate-600">Vytvořte první stránku (např. Banky) tlačítkem „Nová stránka“.</p>
      )}
      </div>
    </AppShell>
  );
}
