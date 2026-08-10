import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import {
  loadWidgetsProgressively,
  refetchOneWidget,
  enrichWidgetsWithAiAsync,
  refreshHomepageWidget,
} from "@/lib/progressiveWidgetResolve";
import { mergeResolvedWidget, widgetInitialFromListRow, widgetPatchAffectsDataCache, mergeWidgetLayoutPatch, hasWidgetRenderableData } from "@/lib/widgetSnapshot";
import AppShell from "@/components/layout/AppShell";
import { LoadingInline } from "@/components/ui/loading";
import AdminQuickAddWidget from "@/components/admin/AdminQuickAddWidget";
import AdminWidgetCanvas from "@/components/admin/AdminWidgetCanvas";
import HeadlineKpiStrip from "@/components/HeadlineKpiStrip";
import { useAuth } from "@/contexts/AuthContext";
import { useScrollToWidgetFromHash } from "@/hooks/useScrollToWidgetFromHash";
import { useLocalizedContent } from "@/hooks/useLocalizedContent";

/** Otevření veřejného přehledu na `/` s přesměrováním předplatitele na `/my-dashboard` dle user.open_personal_dashboard_on_login (DB pole, legacy název). */
export default function DashboardPage() {
  const { t } = useTranslation();
  const loc = useLocalizedContent();
  const { isAdmin, user, ready, isSubscriber } = useAuth();
  const navigate = useNavigate();
  const [page, setPage] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  /** Zvýší se při každém novém load / patch — ignorují se pozdě doběhlé staré POST /render-widget. */
  const widgetFetchGenRef = useRef(0);
  /** Zabrání souběžným běhům load(): starší .finally() nesmí shodit loading, když už běží novější fetch. */
  const homepageFetchIdRef = useRef(0);
  const pageRef = useRef(null);
  useEffect(() => {
    pageRef.current = page;
  }, [page]);

  // Zahřej katalogové indexy při příchodu na dashboard.
  useEffect(() => {
    api.get("/catalog/warmup", { timeout: 5000 }).catch(() => {});
  }, []);

  const widgetScrollSig = useMemo(
    () => (Array.isArray(page?.widgets) ? page.widgets.map((w) => w.id).filter(Boolean).join(",") : ""),
    [page?.widgets]
  );
  useScrollToWidgetFromHash({ loading, widgetSignature: widgetScrollSig });

  const load = useCallback(async () => {
    setLoadError(null);
    widgetFetchGenRef.current += 1;
    const gen = widgetFetchGenRef.current;
    const isStale = () => gen !== widgetFetchGenRef.current;

    const { data: cfg } = await api.get("/homepage/config");
    if (isStale()) return;

    const widgets = Array.isArray(cfg.widgets) ? cfg.widgets : [];
    setPage({
      title: cfg.title,
      title_en: cfg.title_en,
      subtitle: cfg.subtitle,
      subtitle_en: cfg.subtitle_en,
      updated_at: cfg.updated_at,
      default_chart_type: cfg.default_chart_type || "line",
      default_chart_frequency: cfg.default_chart_frequency || undefined,
      widgets: widgets.map((w) => widgetInitialFromListRow(w)),
    });
    loadWidgetsProgressively(
      widgets,
      (id, next) => {
        if (isStale()) return;
        setPage((prev) => {
          if (!prev) return prev;
          return {
            ...prev,
            widgets: prev.widgets.map((pw) => (pw.id === id ? next : pw)),
          };
        });
      },
      {
        isStale,
        afterInitialBatch: (taskList) => {
          enrichWidgetsWithAiAsync(taskList, { isStale, setPage });
        },
      }
    );
  }, []);
  useEffect(() => {
    if (!ready) return;
    const openPersonalOnAppStart =
      user &&
      user !== false &&
      !isAdmin &&
      isSubscriber &&
      user.open_personal_dashboard_on_login === true &&
      !!user.default_dashboard_page_id;
    if (openPersonalOnAppStart) {
      setLoading(false);
      navigate("/my-dashboard", { replace: true });
      return;
    }
    const fetchId = ++homepageFetchIdRef.current;
    setLoading(true);
    setLoadError(null);
    load()
      .catch((e) => {
        if (fetchId !== homepageFetchIdRef.current) return;
        setPage(null);
        setLoadError(
          formatApiErrorFromAxios(e) ||
            e?.response?.data?.detail ||
            e?.message ||
            "Nepodařilo se načíst přehled. Zkontrolujte, zda běží backend."
        );
      })
      .finally(() => {
        if (fetchId !== homepageFetchIdRef.current) return;
        setLoading(false);
      });
  }, [load, ready, user, isSubscriber, isAdmin, navigate]);

  const reorder = useCallback(
    async (ids, widgetLayout) => {
      const body = { widget_ids: ids };
      if (widgetLayout && Object.keys(widgetLayout).length > 0) {
        body.widget_layout = widgetLayout;
      }
      await api.post("/homepage/reorder", body);
      await load();
    },
    [load]
  );

  const deleteWidget = useCallback(async (widgetId) => {
    await api.delete(`/homepage/widget/${widgetId}`);
    await load();
  }, [load]);

  const patchWidget = useCallback(async (widgetId, patch) => {
    let saved;
    try {
      ({ data: saved } = await api.patch(`/homepage/widget/${widgetId}`, patch));
    } catch {
      return false;
    }
    const prev = pageRef.current;
    if (!prev) return true;
    const wOld = (prev.widgets || []).find((x) => x.id === widgetId);
    if (!wOld) return;

    if (!widgetPatchAffectsDataCache(patch)) {
      setPage({
        ...prev,
        widgets: (prev.widgets || []).map((w) =>
          w.id === widgetId ? mergeWidgetLayoutPatch(w, saved, patch) : w
        ),
      });
      return true;
    }

    widgetFetchGenRef.current += 1;
    const gen = widgetFetchGenRef.current;
    const merged = mergeWidgetLayoutPatch(wOld, saved, patch);
    setPage({
      ...prev,
      widgets: (prev.widgets || []).map((w) => (w.id === widgetId ? { ...merged, _loading: !hasWidgetRenderableData(wOld) } : w)),
    });

    try {
      const { data: resolved } = await refetchOneWidget(merged, true);
      if (gen !== widgetFetchGenRef.current) return true;
      setPage((p) =>
        p
          ? {
              ...p,
              widgets: p.widgets.map((pw) =>
                pw.id === widgetId ? mergeResolvedWidget(pw, resolved) : pw
              ),
            }
          : p
      );
    } catch {
      if (gen !== widgetFetchGenRef.current) return true;
      setPage((p) =>
        p
          ? {
              ...p,
              widgets: p.widgets.map((pw) =>
                pw.id === widgetId
                  ? { ...merged, data: wOld.data ?? pw.data, _loading: false }
                  : pw
              ),
            }
          : p
      );
    }
    return true;
  }, []);

  const refreshWidget = useCallback(async (widgetId) => {
    const prev = pageRef.current;
    const current = (prev?.widgets || []).find((w) => w.id === widgetId);
    if (!current) return;
    setPage((p) =>
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
      const { data: resolved } = await refreshHomepageWidget(current);
      setPage((p) =>
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
      setPage((p) =>
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

  return (
    <AppShell
      title={loc.homepageTitle(page) || t("pages.dashboard.titleFallback")}
      subtitle={loc.homepageSubtitle(page) || t("pages.dashboard.subtitleFallback")}
    >
      {loadError ? (
        <div
          className="soft-card p-6 text-sm"
          style={{ borderColor: "hsl(354 60% 90%)", color: "hsl(354 50% 40%)" }}
        >
          {loadError}
        </div>
      ) : !ready ? (
        <div className="rounded-xl border border-border/60 bg-white/80 px-6 py-8">
          <LoadingInline label={t("pages.dashboard.loadingAccount")} size="md" />
        </div>
      ) : loading && !page ? (
        <div className="grid grid-cols-1 md:grid-cols-4 gap-5">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="kpi-card animate-pulse">
              <div className="h-3 w-24 rounded" style={{ background: "hsl(205 60% 90%)" }} />
              <div className="h-9 w-32 rounded mt-3" style={{ background: "hsl(205 60% 90%)" }} />
            </div>
          ))}
        </div>
      ) : !page ? (
        <div className="soft-card p-6 text-sm text-slate-700 space-y-2">
          <p>{t("pages.dashboard.loadFailed")}</p>
          <button
            type="button"
            className="text-[hsl(var(--primary))] underline font-medium"
            onClick={() => window.location.reload()}
          >
            {t("pages.dashboard.reload")}
          </button>
        </div>
      ) : (
        <>
          <HeadlineKpiStrip mode="homepage" isAdmin={isAdmin} />
          {!loading && !loadError && (
            <AdminQuickAddWidget mode="homepage" onAdded={load} inlinePanel />
          )}
          {page.widgets?.length === 0 ? (
            <EmptyState />
          ) : (
            <AdminWidgetCanvas
              widgets={page.widgets}
              defaultChartType={page.default_chart_type || "line"}
              defaultChartFrequency={page.default_chart_frequency}
              isAdmin={isAdmin}
              onReorder={reorder}
              onPatch={patchWidget}
              onDelete={deleteWidget}
              onWidgetRefresh={refreshWidget}
            />
          )}
        </>
      )}
    </AppShell>
  );
}

function EmptyState() {
  const { t } = useTranslation();
  return (
    <div className="border border-dashed border-border/70 rounded-xl p-12 text-center text-sm text-slate-500" style={{ background: "hsl(205 75% 96%)" }}>
      {t("pages.dashboard.emptyWidgets")}
    </div>
  );
}

export { StatusBadge } from "@/components/widgets/WidgetRenderer";
