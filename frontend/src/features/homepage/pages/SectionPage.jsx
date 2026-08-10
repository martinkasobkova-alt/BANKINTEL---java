import React, { useEffect, useState, useCallback, useMemo, useRef } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { MoreHorizontal, Plus } from "lucide-react";
import { toast } from "sonner";
import api from "@/lib/api";
import {
  loadWidgetsProgressively,
  refetchOneWidget,
  enrichWidgetsWithAiAsync,
} from "@/lib/progressiveWidgetResolve";
import { mergeResolvedWidget, widgetInitialFromListRow, widgetPatchAffectsDataCache, mergeWidgetLayoutPatch, hasWidgetRenderableData } from "@/lib/widgetSnapshot";
import AppShell from "@/components/layout/AppShell";
import AdminQuickAddWidget from "@/components/admin/AdminQuickAddWidget";
import AdminWidgetCanvas from "@/components/admin/AdminWidgetCanvas";
import HeadlineKpiStrip from "@/components/HeadlineKpiStrip";
import { useAuth } from "@/contexts/AuthContext";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useScrollToWidgetFromHash } from "@/hooks/useScrollToWidgetFromHash";
import { useLocalizedContent } from "@/hooks/useLocalizedContent";
import { localizedSubpageTitle } from "@/lib/localizedContent";

const ROOT_SECTION_PAGE_ID = "__section_root__";

function normalizeSectionPages(raw, t) {
  const pages = Array.isArray(raw) ? raw : [];
  return pages
    .map((p, idx) => ({
      id: String(p?.id || "").trim(),
      title: String(p?.title || "").trim() || t("pages.section.subpageDefault", { n: idx + 1 }),
      title_en: String(p?.title_en || "").trim(),
      slug: String(p?.slug || "").trim().toLowerCase(),
      order: Number.isFinite(Number(p?.order)) ? Number(p.order) : (idx + 1) * 10,
      is_visible: p?.is_visible !== false,
    }))
    .filter((p) => p.id && p.slug)
    .sort((a, b) => a.order - b.order);
}

function widgetBelongsToPage(widget, pageId) {
  const widPage = String(widget?.section_page_id || "").trim();
  if (!pageId || pageId === ROOT_SECTION_PAGE_ID) return !widPage;
  return widPage === pageId;
}

function slugifySectionSubpage(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

export default function SectionPage() {
  const { t, i18n } = useTranslation();
  const loc = useLocalizedContent();
  const { slug, pageSlug } = useParams();
  const navigate = useNavigate();
  const { isAdmin } = useAuth();
  const [page, setPage] = useState(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [creatingSubpage, setCreatingSubpage] = useState(false);
  const [deletingSubpageId, setDeletingSubpageId] = useState("");
  const [renamingSubpageId, setRenamingSubpageId] = useState("");
  const widgetFetchGenRef = useRef(0);
  const pageRef = useRef(null);
  useEffect(() => {
    pageRef.current = page;
  }, [page]);

  const load = useCallback(async () => {
    setErr("");
    widgetFetchGenRef.current += 1;
    const gen = widgetFetchGenRef.current;
    const isStale = () => gen !== widgetFetchGenRef.current;

    const { data: sec } = await api.get(`/sections/${slug}`);
    if (isStale()) return;

    const widgets = Array.isArray(sec.widgets) ? sec.widgets : [];
    const sectionPages = normalizeSectionPages(sec.section_pages, t);
    setPage({
      id: sec.id,
      slug: sec.slug,
      name: sec.name,
      name_en: sec.name_en,
      subtitle: sec.subtitle,
      subtitle_en: sec.subtitle_en,
      icon: sec.icon,
      updated_at: sec.updated_at,
      default_chart_type: sec.default_chart_type || "line",
      default_chart_frequency: sec.default_chart_frequency || undefined,
      section_pages: sectionPages,
      widgets: widgets.map((w) => ({
        ...widgetInitialFromListRow(w),
        section_page_id: String(w?.section_page_id || "").trim() || null,
      })),
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
  }, [slug, t]);
  useEffect(() => {
    setLoading(true);
    load()
      .catch((e) => {
        setPage(null);
        setErr(e?.response?.data?.detail || t("pages.section.loadFailed"));
      })
      .finally(() => setLoading(false));
  }, [slug, load, t]);

  const navPages = useMemo(() => {
    const custom = Array.isArray(page?.section_pages) ? page.section_pages : [];
    const visibleCustom = isAdmin ? custom : custom.filter((p) => p.is_visible !== false);
    return [
      { id: ROOT_SECTION_PAGE_ID, slug: "", title: t("pages.section.overviewTab"), is_visible: true, is_root: true },
      ...visibleCustom.map((p) => ({
        ...p,
        displayTitle: localizedSubpageTitle(p, i18n.language),
      })),
    ];
  }, [page?.section_pages, isAdmin, t, i18n.language]);

  const activePage = useMemo(() => {
    const requested = String(pageSlug || "").trim().toLowerCase();
    if (!requested) return navPages[0] || null;
    return navPages.find((p) => p.slug === requested) || null;
  }, [pageSlug, navPages]);

  useEffect(() => {
    if (!page || loading) return;
    const requested = String(pageSlug || "").trim().toLowerCase();
    if (!requested) return;
    if (!activePage) navigate(`/s/${slug}`, { replace: true });
  }, [activePage, loading, navigate, page, pageSlug, slug]);

  const visibleWidgets = useMemo(() => {
    const all = Array.isArray(page?.widgets) ? page.widgets : [];
    const pid = activePage?.id || ROOT_SECTION_PAGE_ID;
    return all.filter((w) => widgetBelongsToPage(w, pid));
  }, [page?.widgets, activePage?.id]);

  const widgetScrollSig = useMemo(
    () =>
      (visibleWidgets || [])
        .map((w) => String(w?.id || "").trim())
        .filter(Boolean)
        .sort()
        .join(","),
    [visibleWidgets]
  );
  useScrollToWidgetFromHash({ loading, widgetSignature: widgetScrollSig });

  const reorder = useCallback(
    async (ids, widgetLayout) => {
      const current = pageRef.current;
      const allWidgets = Array.isArray(current?.widgets) ? current.widgets : [];
      const activeId = activePage?.id || ROOT_SECTION_PAGE_ID;
      const pageWidgetIds = allWidgets
        .filter((w) => widgetBelongsToPage(w, activeId))
        .map((w) => w.id)
        .filter(Boolean);
      const pageSet = new Set(pageWidgetIds);
      const nextSubset = ids.filter((id) => pageSet.has(id));
      const queue = [...nextSubset];
      const mergedIds = allWidgets.map((w) => (pageSet.has(w.id) ? (queue.shift() || w.id) : w.id));
      const body = { widget_ids: ids };
      body.widget_ids = mergedIds;
      if (widgetLayout && Object.keys(widgetLayout).length > 0) {
        body.widget_layout = widgetLayout;
      }
      await api.post(`/sections/${slug}/reorder`, body);
      await load();
    },
    [slug, load, activePage?.id]
  );

  const deleteWidget = useCallback(
    async (widgetId) => {
      await api.delete(`/sections/${slug}/widget/${widgetId}`);
      await load();
    },
    [slug, load]
  );

  const patchWidget = useCallback(
    async (widgetId, patch) => {
      let saved;
      try {
        ({ data: saved } = await api.patch(`/sections/${slug}/widget/${widgetId}`, patch));
      } catch {
        return false;
      }
      const prev = pageRef.current;
      if (!prev) return true;
      const wOld = (prev.widgets || []).find((x) => x.id === widgetId);
      if (!wOld) return true;

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
        widgets: (prev.widgets || []).map((w) =>
          w.id === widgetId ? { ...merged, _loading: !hasWidgetRenderableData(wOld) } : w
        ),
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
    },
    [slug]
  );

  const addSubpageInline = useCallback(async () => {
    if (!isAdmin || !page?.id) return;
    const titleRaw = window.prompt(t("pages.section.promptNewSubpage"));
    const title = String(titleRaw || "").trim();
    if (!title) return;
    const currentPages = normalizeSectionPages(page?.section_pages, t);
    const used = new Set(currentPages.map((p) => String(p.slug || "").trim().toLowerCase()).filter(Boolean));
    let baseSlug = slugifySectionSubpage(title) || "podstranka";
    let nextSlug = baseSlug;
    let i = 2;
    while (used.has(nextSlug)) {
      nextSlug = `${baseSlug}-${i}`;
      i += 1;
    }
    const nextPages = [
      ...currentPages,
      {
        title,
        slug: nextSlug,
        is_visible: true,
      },
    ];
    setCreatingSubpage(true);
    try {
      await api.patch(`/sections/${page.id}`, { section_pages: nextPages });
      await load();
      navigate(`/s/${slug}/${nextSlug}`);
      toast.success(t("pages.section.subpageAdded"));
    } catch (e) {
      toast.error(e?.response?.data?.detail || t("pages.section.subpageAddFailed"));
    } finally {
      setCreatingSubpage(false);
    }
  }, [isAdmin, load, navigate, page?.id, page?.section_pages, slug, t]);

  const removeSubpageInline = useCallback(
    async (targetPage) => {
      if (!isAdmin || !page?.id || !targetPage?.id || targetPage?.is_root) return;
      const title = String(targetPage?.title || "").trim() || t("pages.section.thisSubpage");
      const ok = window.confirm(t("pages.section.confirmDeleteSubpage", { title }));
      if (!ok) return;
      const currentPages = normalizeSectionPages(page?.section_pages, t);
      const nextPages = currentPages.filter((p) => p.id !== targetPage.id);
      setDeletingSubpageId(targetPage.id);
      try {
        await api.patch(`/sections/${page.id}`, { section_pages: nextPages });
        await load();
        if ((activePage?.id || "") === targetPage.id) {
          navigate(`/s/${slug}`);
        }
        toast.success(t("pages.section.subpageDeleted"));
      } catch (e) {
        toast.error(e?.response?.data?.detail || t("pages.section.subpageDeleteFailed"));
      } finally {
        setDeletingSubpageId("");
      }
    },
    [activePage?.id, isAdmin, load, navigate, page?.id, page?.section_pages, slug, t]
  );

  const renameSubpageInline = useCallback(
    async (targetPage) => {
      if (!isAdmin || !page?.id || !targetPage?.id || targetPage?.is_root) return;
      const oldTitle = String(targetPage?.title || "").trim() || t("pages.section.subpageDefaultName");
      const titleRaw = window.prompt(t("pages.section.promptRenameSubpage"), oldTitle);
      const nextTitle = String(titleRaw || "").trim();
      if (!nextTitle || nextTitle === oldTitle) return;
      const currentPages = normalizeSectionPages(page?.section_pages, t);
      const nextPages = currentPages.map((p) =>
        p.id === targetPage.id
          ? {
              ...p,
              title: nextTitle,
            }
          : p
      );
      setRenamingSubpageId(targetPage.id);
      try {
        await api.patch(`/sections/${page.id}`, { section_pages: nextPages });
        await load();
        toast.success(t("pages.section.subpageRenamed"));
      } catch (e) {
        toast.error(e?.response?.data?.detail || t("pages.section.subpageRenameFailed"));
      } finally {
        setRenamingSubpageId("");
      }
    },
    [isAdmin, load, page?.id, page?.section_pages, t]
  );

  return (
    <AppShell
      title={loc.sectionName(page) || t("pages.section.titleFallback")}
      subtitle={
        activePage?.is_root
          ? (loc.sectionSubtitle(page) || "")
          : `${loc.sectionSubtitle(page) || ""}${loc.sectionSubtitle(page) ? " · " : ""}${activePage?.displayTitle || activePage?.title || ""}`
      }
    >
      {loading ? (
        <div className="text-sm text-slate-500 font-mono">{t("common.loading")}</div>
      ) : err ? (
        <div className="soft-card p-6 text-sm" style={{ borderColor: "hsl(354 60% 90%)", color: "hsl(354 50% 40%)" }}>{err}</div>
      ) : (
        <>
          {(navPages.length > 1 || isAdmin) && (
            <div className="mb-4 flex items-center gap-2 overflow-x-auto pb-1">
              {navPages.map((p) => {
                const active = (activePage?.id || ROOT_SECTION_PAGE_ID) === p.id;
                const to = p.slug ? `/s/${slug}/${p.slug}` : `/s/${slug}`;
                const pillShell = `shrink-0 inline-flex h-8 items-stretch overflow-hidden rounded-full border text-xs font-medium ${
                  active
                    ? "chip-mint border-transparent"
                    : "border-border/60 bg-white text-slate-700"
                }`;
                const navBtn =
                  "inline-flex max-w-[min(14rem,calc(100vw-8rem))] items-center truncate px-3 hover:bg-black/[0.04] active:bg-black/[0.06]";
                const menuDivider = active ? "border-l border-white/30" : "border-l border-border/50";

                return (
                  <div key={p.id} className={pillShell}>
                    <button
                      type="button"
                      onClick={() => navigate(to)}
                      className={navBtn}
                      title={p.slug ? `/s/${slug}/${p.slug}` : `/s/${slug}`}
                    >
                      {p.displayTitle || p.title}
                      {isAdmin && p.is_visible === false ? t("pages.section.hiddenSuffix") : ""}
                    </button>
                    {isAdmin && !p.is_root && (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <button
                            type="button"
                            disabled={deletingSubpageId === p.id || renamingSubpageId === p.id || creatingSubpage}
                            className={`inline-flex w-8 shrink-0 items-center justify-center ${menuDivider} hover:bg-black/[0.04] active:bg-black/[0.06] disabled:opacity-50`}
                            title={t("pages.section.subpageActions", { title: p.title })}
                            aria-label={t("pages.section.subpageActions", { title: p.title })}
                          >
                            <MoreHorizontal className="h-4 w-4 opacity-80" />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem
                            onClick={() => renameSubpageInline(p)}
                            disabled={deletingSubpageId === p.id || renamingSubpageId === p.id || creatingSubpage}
                          >
                            {t("pages.section.rename")}
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={() => removeSubpageInline(p)}
                            disabled={deletingSubpageId === p.id || renamingSubpageId === p.id || creatingSubpage}
                            className="text-rose-700 focus:text-rose-700"
                          >
                            {t("pages.section.remove")}
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    )}
                  </div>
                );
              })}
              {isAdmin && (
                <button
                  type="button"
                  onClick={addSubpageInline}
                  disabled={creatingSubpage}
                  className="shrink-0 h-8 px-3 rounded-full border border-border/70 bg-white text-slate-700 text-xs font-medium hover:bg-muted/60 disabled:opacity-50 inline-flex items-center gap-1"
                  title={t("pages.section.addSubpage")}
                >
                  <Plus className="h-3.5 w-3.5" />
                  {creatingSubpage ? t("pages.section.addingSubpage") : t("pages.section.addSubpage")}
                </button>
              )}
            </div>
          )}
          <HeadlineKpiStrip mode="section" slug={slug} isAdmin={isAdmin} />
          {page?.id && (
            <AdminQuickAddWidget
              mode="section"
              sectionSlug={slug}
              sectionId={page.id}
              sectionPageId={activePage?.id && activePage.id !== ROOT_SECTION_PAGE_ID ? activePage.id : null}
              onAdded={load}
              inlinePanel
              autoExpandInlinePanel={
                !loading &&
                isAdmin &&
                !activePage?.is_root &&
                (!visibleWidgets || visibleWidgets.length === 0)
              }
            />
          )}
          {!visibleWidgets || visibleWidgets.length === 0 ? (
            <div className="border border-dashed border-border/70 rounded-xl p-12 text-center text-sm text-slate-500" style={{ background: "hsl(205 75% 96%)" }}>
              {(activePage?.is_root
                ? t("pages.section.emptyRoot")
                : t("pages.section.emptySubpage", {
                    title: activePage?.title || t("pages.section.unnamedSubpage"),
                  }))}{" "}
              {isAdmin ? t("pages.section.adminEmptyHint") : t("pages.section.guestEmptyHint")}
            </div>
          ) : (
            <AdminWidgetCanvas
              widgets={visibleWidgets}
              defaultChartType={page.default_chart_type || "line"}
              defaultChartFrequency={page.default_chart_frequency}
              isAdmin={isAdmin}
              onReorder={reorder}
              onPatch={patchWidget}
              onDelete={deleteWidget}
            />
          )}
        </>
      )}
    </AppShell>
  );
}
