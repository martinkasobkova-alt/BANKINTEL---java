import React, { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ArrowLeft, Loader2, Mic, Upload } from "lucide-react";
import { toast } from "sonner";
import AppShell from "@/components/layout/AppShell";
import PodcastEpisodeList from "@/components/podcast/PodcastEpisodeList";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { usePodcastPlayer } from "@/contexts/PodcastPlayerContext";

export default function PodcastShowPage() {
  const { showId } = useParams();
  const { t } = useTranslation();
  const { user, ready, isAdmin, openLogin } = useAuth();
  const { playEpisode, playEmbedEpisode, episode: activeEpisode, isPlaying, togglePlayPause, stop } =
    usePodcastPlayer();

  const [show, setShow] = useState(null);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [uploadBusy, setUploadBusy] = useState(false);
  const [externalBusy, setExternalBusy] = useState(false);
  const [deleteBusyId, setDeleteBusyId] = useState(null);
  const fileRef = useRef(null);
  const coverRef = useRef(null);

  const [uploadTitle, setUploadTitle] = useState("");
  const [uploadSummary, setUploadSummary] = useState("");
  const [extTitle, setExtTitle] = useState("");
  const [extSummary, setExtSummary] = useState("");
  const [extUrl, setExtUrl] = useState("");
  const [extAudioUrl, setExtAudioUrl] = useState("");

  const load = useCallback(async () => {
    if (!showId) return;
    setLoading(true);
    setErr("");
    try {
      const [showRes, episodesRes] = await Promise.all([
        api.get(`/podcasts/shows/${showId}`),
        api.get("/podcasts/episodes", { params: { show_id: showId, limit: 120 } }),
      ]);
      setShow(showRes.data || null);
      setRows(Array.isArray(episodesRes.data?.items) ? episodesRes.data.items : []);
    } catch (e) {
      setErr(formatApiErrorFromAxios(e));
      setShow(null);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [showId]);

  useEffect(() => {
    void load();
  }, [load]);

  const handlePlay = async (item) => {
    const ok = await playEpisode({
      id: item.id,
      title: item.title,
      subtitle: show?.title || item.feed_title,
      audioUrl: item.audio_url,
      pageUrl: item.page_url || item.external_url,
    });
    if (!ok) setErr(t("podcast.playFailed"));
  };

  const handleEmbedPlay = (item) => {
    const ok = playEmbedEpisode({
      id: item.id,
      title: item.title,
      subtitle: show?.title || item.feed_title,
      external_url: item.external_url,
      page_url: item.page_url || item.external_url,
    });
    if (!ok) toast.error(t("podcast.embedFailed"));
    else toast.info(t("podcast.embedStarted"));
  };

  const handleUpload = async (event) => {
    event.preventDefault();
    if (!user) {
      openLogin?.();
      return;
    }
    const file = fileRef.current?.files?.[0];
    if (!file) {
      toast.error(t("pages.podcasts.uploadPickFile"));
      return;
    }
    const form = new FormData();
    form.append("file", file);
    form.append("title", uploadTitle.trim());
    form.append("summary", uploadSummary.trim());
    form.append("show_id", showId);
    const coverFile = coverRef.current?.files?.[0];
    if (coverFile) form.append("cover_image", coverFile);
    setUploadBusy(true);
    setErr("");
    try {
      await api.post("/podcasts/episodes/upload", form, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      toast.success(t("pages.podcasts.uploadSuccess"));
      setUploadTitle("");
      setUploadSummary("");
      if (fileRef.current) fileRef.current.value = "";
      if (coverRef.current) coverRef.current.value = "";
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || t("pages.podcasts.uploadFailed"));
    } finally {
      setUploadBusy(false);
    }
  };

  const handleExternal = async (event) => {
    event.preventDefault();
    if (!extTitle.trim() || !extUrl.trim()) {
      toast.error(t("pages.podcasts.externalRequired"));
      return;
    }
    setExternalBusy(true);
    setErr("");
    try {
      await api.post("/podcasts/episodes/external", {
        title: extTitle.trim(),
        summary: extSummary.trim(),
        show_id: showId,
        external_url: extUrl.trim(),
        audio_url: extAudioUrl.trim(),
      });
      toast.success(t("pages.podcasts.externalSuccess"));
      setExtTitle("");
      setExtSummary("");
      setExtUrl("");
      setExtAudioUrl("");
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || t("pages.podcasts.externalFailed"));
    } finally {
      setExternalBusy(false);
    }
  };

  const handleDelete = async (item) => {
    if (!window.confirm(t("pages.podcasts.deleteConfirm", { title: item.title }))) return;
    setDeleteBusyId(item.id);
    try {
      await api.delete(`/podcasts/episodes/${item.id}`);
      toast.success(t("pages.podcasts.deleteSuccess"));
      await load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || t("pages.podcasts.deleteFailed"));
    } finally {
      setDeleteBusyId(null);
    }
  };

  const openExternal = (item) => {
    const url = item.external_url || item.page_url;
    if (url) window.open(url, "_blank", "noopener,noreferrer");
  };

  const manageBlock =
    ready && (user || isAdmin) ? (
      <details className="soft-card group">
        <summary className="cursor-pointer list-none px-4 py-3 text-sm font-semibold text-[hsl(var(--foreground))] marker:content-none">
          {t("pages.podcasts.manageSection")}
        </summary>
        <div className="space-y-4 border-t border-[hsl(var(--border)/0.45)] px-4 py-4">
          {user ? (
            <form onSubmit={handleUpload} className="space-y-3">
              <div className="flex items-center gap-2 text-sm font-medium">
                <Upload className="h-4 w-4" />
                {t("pages.podcasts.uploadTitle")}
              </div>
              <p className="text-xs text-muted-foreground">{t("pages.podcasts.uploadHint")}</p>
              <input
                ref={fileRef}
                type="file"
                accept="audio/mpeg,audio/mp4,video/mp4,audio/aac,audio/ogg,audio/wav,.mp3,.m4a,.mp4,.aac,.ogg,.wav"
                className="block w-full text-sm"
              />
              <input
                ref={coverRef}
                type="file"
                accept="image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp"
                className="block w-full text-sm"
              />
              <p className="text-[11px] text-muted-foreground">{t("pages.podcasts.coverUploadHint")}</p>
              <input
                type="text"
                value={uploadTitle}
                onChange={(e) => setUploadTitle(e.target.value)}
                placeholder={t("pages.podcasts.fieldTitle")}
                className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
              />
              <textarea
                value={uploadSummary}
                onChange={(e) => setUploadSummary(e.target.value)}
                placeholder={t("pages.podcasts.fieldSummary")}
                rows={2}
                className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
              />
              <button
                type="submit"
                disabled={uploadBusy}
                className="inline-flex items-center gap-2 rounded-md bg-[hsl(var(--primary-deep))] px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
              >
                {uploadBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                {t("pages.podcasts.uploadSubmit")}
              </button>
            </form>
          ) : (
            <p className="text-sm text-muted-foreground">
              {t("pages.podcasts.loginToUpload")}{" "}
              <button type="button" onClick={() => openLogin?.()} className="text-[hsl(var(--primary-deep))] underline">
                {t("nav.login")}
              </button>
            </p>
          )}

          {isAdmin ? (
            <form onSubmit={handleExternal} className="space-y-3 border-t border-[hsl(var(--border)/0.35)] pt-4">
              <div className="text-sm font-medium">{t("pages.podcasts.externalTitle")}</div>
              <p className="text-xs text-muted-foreground">{t("pages.podcasts.externalHint")}</p>
              <input
                type="text"
                value={extTitle}
                onChange={(e) => setExtTitle(e.target.value)}
                placeholder={t("pages.podcasts.fieldTitle")}
                className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
                required
              />
              <input
                type="url"
                value={extUrl}
                onChange={(e) => setExtUrl(e.target.value)}
                placeholder={t("pages.podcasts.fieldExternalUrl")}
                className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
                required
              />
              <input
                type="url"
                value={extAudioUrl}
                onChange={(e) => setExtAudioUrl(e.target.value)}
                placeholder={t("pages.podcasts.fieldDirectAudioUrl")}
                className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
              />
              <textarea
                value={extSummary}
                onChange={(e) => setExtSummary(e.target.value)}
                placeholder={t("pages.podcasts.fieldSummary")}
                rows={2}
                className="w-full rounded-md border border-border bg-white px-3 py-2 text-sm"
              />
              <button
                type="submit"
                disabled={externalBusy}
                className="inline-flex items-center gap-2 rounded-md border border-[hsl(var(--primary-deep))] px-4 py-2 text-sm font-medium text-[hsl(var(--primary-deep))] disabled:opacity-60"
              >
                {externalBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                {t("pages.podcasts.externalSubmit")}
              </button>
            </form>
          ) : null}
        </div>
      </details>
    ) : null;

  return (
    <AppShell title={show?.title || t("pages.podcasts.showFallback")} subtitle={t("pages.podcasts.subtitle")}>
      <div className="max-w-3xl space-y-4">
        <Link
          to="/podcasty"
          className="inline-flex items-center gap-1 text-sm font-medium text-[hsl(var(--primary-deep))] hover:underline"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("pages.podcasts.backToShows")}
        </Link>

        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground py-8">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t("pages.podcasts.loading")}
          </div>
        ) : (
          <>
            {show ? (
              <div className="soft-card flex items-start gap-3 p-4">
                <div className="inline-flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]">
                  <Mic className="h-6 w-6" />
                </div>
                <div className="min-w-0 flex-1">
                  <h2 className="text-lg font-semibold text-[hsl(var(--foreground))]">{show.title}</h2>
                  {show.description ? (
                    <p className="mt-1 text-sm text-muted-foreground">{show.description}</p>
                  ) : null}
                  <p className="mt-2 text-xs text-muted-foreground">
                    {t("pages.podcasts.episodeCount", { count: rows.length || show.episode_count || 0 })}
                  </p>
                </div>
              </div>
            ) : null}

            {err ? <div className="soft-card p-4 text-sm text-red-700">{err}</div> : null}

            <section>
              <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {t("pages.podcasts.episodesSection")}
              </h3>
              <PodcastEpisodeList
                rows={rows}
                activeEpisode={activeEpisode}
                isPlaying={isPlaying}
                deleteBusyId={deleteBusyId}
                onPlay={handlePlay}
                onEmbedPlay={handleEmbedPlay}
                onTogglePause={() => void togglePlayPause()}
                onStop={stop}
                onDelete={(item) => void handleDelete(item)}
                onOpenExternal={openExternal}
              />
            </section>

            {manageBlock}
          </>
        )}
      </div>
    </AppShell>
  );
}
