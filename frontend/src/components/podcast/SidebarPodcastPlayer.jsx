import React from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Loader2, Pause, Play, RotateCcw, SkipBack, SkipForward, Square, X } from "lucide-react";
import { usePodcastPlayerOptional, SKIP_SECONDS } from "@/contexts/PodcastPlayerContext";
import { formatPodcastTime } from "@/lib/podcastAudio";

export default function SidebarPodcastPlayer({ onNavigate }) {
  const { t } = useTranslation();
  const player = usePodcastPlayerOptional();
  if (!player?.episode) return null;

  const { episode, isPlaying, loading, currentTime, duration, togglePlayPause, seek, skip, stop } = player;

  if (episode.playerMode === "embed") {
    return (
      <div
        className="mx-3 mb-2 shrink-0 rounded-2xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.96)] p-3 shadow-[0_8px_24px_hsl(var(--foreground)/0.08)]"
        data-testid="sidebar-podcast-player"
      >
        <div className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <div className="text-[10px] font-semibold uppercase tracking-wide text-[hsl(var(--primary))]">
              {t("podcast.nowPlayingEmbed")}
            </div>
            <div className="mt-0.5 line-clamp-2 text-[13px] font-semibold leading-snug text-[hsl(var(--foreground))]">
              {episode.title}
            </div>
            {episode.subtitle ? (
              <div className="mt-0.5 line-clamp-1 text-[11px] text-muted-foreground">{episode.subtitle}</div>
            ) : null}
            <p className="mt-2 text-[11px] leading-snug text-muted-foreground">{t("podcast.embedSidebarHint")}</p>
          </div>
          <button
            type="button"
            onClick={stop}
            className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] text-muted-foreground hover:bg-[hsl(var(--muted)/0.45)]"
            aria-label={t("podcast.stop")}
            title={t("podcast.stop")}
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
        <div className="mt-2 flex items-center justify-between gap-2">
          <Link
            to="/podcasty"
            onClick={() => onNavigate?.()}
            className="text-[11px] font-medium text-[hsl(var(--primary))] hover:underline"
          >
            {t("podcast.openCatalog")}
          </Link>
          {episode.pageUrl ? (
            <a
              href={episode.pageUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-[hsl(var(--foreground))]"
            >
              <RotateCcw className="h-3 w-3" />
              {t("podcast.openSource")}
            </a>
          ) : null}
        </div>
      </div>
    );
  }

  return (
    <div
      className="mx-3 mb-2 shrink-0 rounded-2xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.96)] p-3 shadow-[0_8px_24px_hsl(var(--foreground)/0.08)]"
      data-testid="sidebar-podcast-player"
    >
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <div className="text-[10px] font-semibold uppercase tracking-wide text-[hsl(var(--primary))]">
            {t("podcast.nowPlaying")}
          </div>
          <div className="mt-0.5 line-clamp-2 text-[13px] font-semibold leading-snug text-[hsl(var(--foreground))]">
            {episode.title}
          </div>
          {episode.subtitle ? (
            <div className="mt-0.5 line-clamp-1 text-[11px] text-muted-foreground">{episode.subtitle}</div>
          ) : null}
        </div>
        <button
          type="button"
          onClick={stop}
          className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] text-muted-foreground hover:bg-[hsl(var(--muted)/0.45)]"
          aria-label={t("podcast.stop")}
          title={t("podcast.stop")}
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>

      <div className="mt-2.5">
        <input
          type="range"
          min={0}
          max={duration > 0 ? duration : 100}
          step={0.1}
          value={duration > 0 ? currentTime : 0}
          onChange={(e) => seek(Number(e.target.value))}
          className="h-1.5 w-full accent-[hsl(var(--primary))]"
          aria-label={t("podcast.seek")}
        />
        <div className="mt-1 flex items-center justify-between text-[10px] font-mono text-muted-foreground">
          <span>{formatPodcastTime(currentTime)}</span>
          <span>{formatPodcastTime(duration)}</span>
        </div>
      </div>

      <div className="mt-2 flex items-center justify-center gap-1.5">
        <button
          type="button"
          onClick={() => skip(-SKIP_SECONDS)}
          className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] hover:bg-[hsl(var(--muted)/0.45)]"
          aria-label={t("podcast.skipBack", { seconds: SKIP_SECONDS })}
        >
          <SkipBack className="h-4 w-4" />
        </button>
        <button
          type="button"
          onClick={() => void togglePlayPause()}
          className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-[hsl(var(--primary))] text-[hsl(var(--primary-foreground))] shadow-sm hover:opacity-90"
          aria-label={isPlaying ? t("podcast.pause") : t("podcast.play")}
        >
          {loading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : isPlaying ? (
            <Pause className="h-4 w-4" />
          ) : (
            <Play className="h-4 w-4 ml-0.5" />
          )}
        </button>
        <button
          type="button"
          onClick={() => skip(SKIP_SECONDS)}
          className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] hover:bg-[hsl(var(--muted)/0.45)]"
          aria-label={t("podcast.skipForward", { seconds: SKIP_SECONDS })}
        >
          <SkipForward className="h-4 w-4" />
        </button>
      </div>

      <div className="mt-2 flex items-center justify-between gap-2">
        <Link
          to="/podcasty"
          onClick={() => onNavigate?.()}
          className="text-[11px] font-medium text-[hsl(var(--primary))] hover:underline"
        >
          {t("podcast.openCatalog")}
        </Link>
        {episode.pageUrl ? (
          <a
            href={episode.pageUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-[hsl(var(--foreground))]"
          >
            <RotateCcw className="h-3 w-3" />
            {t("podcast.openSource")}
          </a>
        ) : (
          <button
            type="button"
            onClick={stop}
            className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-[hsl(var(--foreground))]"
          >
            <Square className="h-3 w-3" />
            {t("podcast.stop")}
          </button>
        )}
      </div>
    </div>
  );
}
