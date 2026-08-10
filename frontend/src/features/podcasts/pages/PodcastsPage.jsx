import React, { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Headphones, Loader2, Mic, Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";
import AppShell from "@/components/layout/AppShell";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";

export default function PodcastsPage() {
  const { t } = useTranslation();
  const { ready, isAdmin } = useAuth();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleteBusyId, setDeleteBusyId] = useState(null);
  const [newTitle, setNewTitle] = useState("");
  const [newDesc, setNewDesc] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setErr("");
    try {
      const { data } = await api.get("/podcasts/shows");
      setRows(Array.isArray(data?.items) ? data.items : []);
    } catch (e) {
      setErr(formatApiErrorFromAxios(e));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const createShow = async (event) => {
    event.preventDefault();
    if (!newTitle.trim()) return;
    setSaving(true);
    try {
      await api.post("/podcasts/shows", {
        title: newTitle.trim(),
        description: newDesc.trim(),
      });
      toast.success(t("pages.podcasts.showCreated"));
      setNewTitle("");
      setNewDesc("");
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || t("pages.podcasts.showCreateFailed"));
    } finally {
      setSaving(false);
    }
  };

  const deleteShow = async (show) => {
    if (!window.confirm(t("pages.podcasts.showDeleteConfirm", { title: show.title }))) return;
    setDeleteBusyId(show.id);
    try {
      await api.delete(`/podcasts/shows/${show.id}`);
      toast.success(t("pages.podcasts.showDeleted"));
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || t("pages.podcasts.showDeleteFailed"));
    } finally {
      setDeleteBusyId(null);
    }
  };

  return (
    <AppShell title={t("pages.podcasts.title")} subtitle={t("pages.podcasts.subtitle")}>
      <div className="max-w-5xl space-y-5">
        <p className="text-sm text-muted-foreground">{t("pages.podcasts.showsHint")}</p>

        {err ? <div className="soft-card p-4 text-sm text-red-700">{err}</div> : null}

        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground py-8">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t("pages.podcasts.loadingShows")}
          </div>
        ) : rows.length === 0 ? (
          <div className="soft-card p-6 text-sm text-muted-foreground space-y-2">
            <div className="flex items-center gap-2 font-medium text-[hsl(var(--foreground))]">
              <Headphones className="h-4 w-4" />
              {t("pages.podcasts.emptyTitle")}
            </div>
            <p>{t("pages.podcasts.emptyShowsBody")}</p>
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {rows.map((show) => (
              <div key={show.id} className="soft-card p-3 hover:border-[hsl(var(--primary)/0.45)] transition sm:p-4">
                <Link to={`/podcasty/${show.id}`} className="block">
                  <div className="flex items-start gap-3">
                    <div className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]">
                      <Mic className="h-5 w-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="font-semibold text-[hsl(var(--foreground))] truncate">{show.title}</div>
                      <div className="text-xs text-muted-foreground mt-1 line-clamp-2">
                        {show.description || t("pages.podcasts.noDescription")}
                      </div>
                      <div className="text-xs text-muted-foreground mt-2">
                        {t("pages.podcasts.episodeCount", { count: show.episode_count || 0 })}
                      </div>
                    </div>
                  </div>
                </Link>
                {isAdmin ? (
                  <button
                    type="button"
                    disabled={deleteBusyId === show.id}
                    onClick={() => void deleteShow(show)}
                    className="mt-3 inline-flex items-center gap-1 rounded-md border border-red-200 px-2.5 py-1 text-xs font-medium text-red-700 hover:bg-red-50 disabled:opacity-60"
                  >
                    {deleteBusyId === show.id ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Trash2 className="h-3.5 w-3.5" />
                    )}
                    {t("pages.podcasts.deleteShow")}
                  </button>
                ) : null}
              </div>
            ))}
          </div>
        )}

        {ready && isAdmin ? (
          <details className="soft-card group">
            <summary className="cursor-pointer list-none px-4 py-3 text-sm font-semibold marker:content-none">
              {t("pages.podcasts.newShow")}
            </summary>
            <form onSubmit={createShow} className="space-y-3 border-t border-[hsl(var(--border)/0.45)] px-4 py-4">
              <div className="grid gap-3 md:grid-cols-2">
                <input
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder={t("pages.podcasts.showNamePlaceholder")}
                  className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
                  required
                />
                <input
                  value={newDesc}
                  onChange={(e) => setNewDesc(e.target.value)}
                  placeholder={t("pages.podcasts.showDescPlaceholder")}
                  className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
                />
              </div>
              <button
                type="submit"
                disabled={saving}
                className="inline-flex items-center gap-2 rounded-md bg-[hsl(var(--primary-deep))] px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
              >
                {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
                {t("pages.podcasts.createShow")}
              </button>
            </form>
          </details>
        ) : null}
      </div>
    </AppShell>
  );
}
