import React from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink, Headphones, Loader2, Pause, Play, Trash2 } from "lucide-react";
import { formatLocaleDate } from "@/i18n/formatLocale";
import { canEmbedPodcastUrl } from "@/lib/articleBodyFormat";
import { canPlayEpisodeInBackground } from "@/lib/podcastAudio";
import PodcastEpisodeArt from "@/components/podcast/PodcastEpisodeArt";

export default function PodcastEpisodeList({
  rows,
  activeEpisode,
  isPlaying,
  deleteBusyId,
  onPlay,
  onEmbedPlay,
  onTogglePause,
  onStop,
  onDelete,
  onOpenExternal,
}) {
  const { t, i18n } = useTranslation();

  if (!rows.length) {
    return (
      <div className="soft-card p-4 text-sm text-muted-foreground space-y-1.5">
        <div className="flex items-center gap-2 font-medium text-[hsl(var(--foreground))]">
          <Headphones className="h-4 w-4" />
          {t("pages.podcasts.emptyEpisodesTitle")}
        </div>
        <p className="text-xs">{t("pages.podcasts.emptyEpisodesBody")}</p>
      </div>
    );
  }

  return (
    <ul className="space-y-2">
      {rows.map((item) => {
        const active = activeEpisode?.id === item.id;
        const playable = canPlayEpisodeInBackground(item);
        const sourceUrl = item.external_url || item.page_url || "";
        const embeddable = item.play_mode === "embed" && canEmbedPodcastUrl(sourceUrl);
        const showPlay = playable || embeddable;
        const activeAudio = active && activeEpisode?.playerMode === "audio";
        const activeEmbed = active && activeEpisode?.playerMode === "embed";
        const hasExternal = Boolean(item.external_url || (item.play_mode !== "background" && item.page_url));

        return (
          <li
            key={item.id}
            className="soft-card flex items-center gap-2.5 p-2.5 sm:gap-3 sm:p-3"
          >
            <PodcastEpisodeArt item={item} className="h-12 w-12 sm:h-14 sm:w-14" />
            {showPlay ? (
              <button
                type="button"
                onClick={() => {
                  if (playable) {
                    if (activeAudio && isPlaying) onTogglePause();
                    else onPlay(item);
                    return;
                  }
                  if (activeEmbed) onStop();
                  else onEmbedPlay(item);
                }}
                className={`inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border shadow-sm transition ${
                  active
                    ? "border-[hsl(var(--primary)/0.45)] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
                    : "border-border bg-white hover:bg-[hsl(var(--primary-soft)/0.35)]"
                }`}
                aria-label={
                  playable && activeAudio && isPlaying
                    ? t("podcast.pause")
                    : embeddable
                      ? t("podcast.playEmbed")
                      : t("podcast.play")
                }
              >
                {playable && activeAudio && isPlaying ? (
                  <Pause className="h-3.5 w-3.5" />
                ) : (
                  <Play className="h-3.5 w-3.5 ml-0.5" />
                )}
              </button>
            ) : null}
            <div className="min-w-0 flex-1">
              <div className="line-clamp-2 text-sm font-semibold leading-snug text-[hsl(var(--foreground))]">
                {item.title}
              </div>
              <div className="mt-0.5 text-[11px] text-muted-foreground">
                {[
                  item.source === "upload" ? t("pages.podcasts.sourceUpload") : null,
                  item.source === "external" ? t("pages.podcasts.sourceExternal") : null,
                  item.source === "rss" ? t("pages.podcasts.sourceRss") : null,
                  item.published_at ? formatLocaleDate(item.published_at, i18n.language) : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </div>
              {item.summary ? (
                <p className="mt-1 line-clamp-1 text-xs text-muted-foreground hidden sm:block">{item.summary}</p>
              ) : null}
            </div>
            <div className="flex shrink-0 flex-col items-end gap-1 sm:flex-row sm:items-center">
              {embeddable ? (
                <button
                  type="button"
                  onClick={() => (activeEmbed ? onStop() : onEmbedPlay(item))}
                  className="hidden sm:inline-flex items-center gap-1 rounded-md border border-[hsl(var(--primary-deep)/0.35)] bg-[hsl(var(--primary-soft)/0.45)] px-2 py-1 text-[11px] font-medium text-[hsl(var(--primary-deep))] hover:bg-[hsl(var(--primary-soft)/0.7)]"
                >
                  <Play className="h-3 w-3" />
                  {t("pages.podcasts.listenInApp")}
                </button>
              ) : null}
              {hasExternal ? (
                <button
                  type="button"
                  onClick={() => onOpenExternal(item)}
                  className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-[11px] font-medium hover:bg-[hsl(var(--primary-soft)/0.35)]"
                  title={t("pages.podcasts.openExternal")}
                >
                  <ExternalLink className="h-3 w-3" />
                  <span className="hidden md:inline">{t("pages.podcasts.openExternalShort")}</span>
                </button>
              ) : null}
              {item.can_manage ? (
                <button
                  type="button"
                  disabled={deleteBusyId === item.id}
                  onClick={() => onDelete(item)}
                  className="inline-flex items-center gap-1 rounded-md border border-red-200 px-2 py-1 text-[11px] font-medium text-red-700 hover:bg-red-50 disabled:opacity-60"
                  title={t("pages.podcasts.delete")}
                >
                  {deleteBusyId === item.id ? (
                    <Loader2 className="h-3 w-3 animate-spin" />
                  ) : (
                    <Trash2 className="h-3 w-3" />
                  )}
                </button>
              ) : null}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
