import React from "react";
import { useTranslation } from "react-i18next";
import { ChevronUp, ExternalLink, Minimize2, X } from "lucide-react";
import { usePodcastPlayerOptional } from "@/contexts/PodcastPlayerContext";

export default function PodcastEmbedBar() {
  const { t } = useTranslation();
  const player = usePodcastPlayerOptional();
  const episode = player?.episode;
  const minimized = Boolean(player?.embedMinimized);
  if (!episode || episode.playerMode !== "embed" || !episode.embedUrl) return null;

  return (
    <div
      className="fixed inset-x-0 bottom-0 z-[70] border-t border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card))] shadow-[0_-8px_32px_hsl(var(--foreground)/0.12)] md:left-64"
      data-testid="podcast-embed-bar"
      data-minimized={minimized ? "true" : "false"}
    >
      <div className="mx-auto flex max-w-3xl items-center gap-2 px-3 py-2">
        <div className="min-w-0 flex-1">
          <div className="text-[10px] font-semibold uppercase tracking-wide text-[hsl(var(--primary))]">
            {t("podcast.nowPlayingEmbed")}
          </div>
          <div className="line-clamp-1 text-sm font-semibold text-[hsl(var(--foreground))]">{episode.title}</div>
          {!minimized ? (
            <p className="mt-0.5 line-clamp-2 text-[11px] text-muted-foreground">{t("podcast.embedBarHint")}</p>
          ) : (
            <p className="mt-0.5 text-[11px] text-muted-foreground">{t("podcast.embedMinimizedHint")}</p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <button
            type="button"
            onClick={player.toggleEmbedMinimized}
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] text-muted-foreground hover:bg-[hsl(var(--muted)/0.45)]"
            aria-label={minimized ? t("podcast.expandEmbed") : t("podcast.minimizeEmbed")}
            title={minimized ? t("podcast.expandEmbed") : t("podcast.minimizeEmbed")}
          >
            {minimized ? <ChevronUp className="h-4 w-4" /> : <Minimize2 className="h-4 w-4" />}
          </button>
          {episode.pageUrl ? (
            <a
              href={episode.pageUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] text-muted-foreground hover:bg-[hsl(var(--muted)/0.45)]"
              aria-label={t("podcast.openSource")}
              title={t("podcast.openSource")}
            >
              <ExternalLink className="h-4 w-4" />
            </a>
          ) : null}
          <button
            type="button"
            onClick={player.stop}
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] text-muted-foreground hover:bg-[hsl(var(--muted)/0.45)]"
            aria-label={t("podcast.stop")}
            title={t("podcast.stop")}
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      </div>
      <div
        className={
          minimized
            ? "pointer-events-none fixed -left-[9999px] top-auto h-[152px] w-full max-w-3xl opacity-0"
            : "block"
        }
        aria-hidden={minimized}
      >
        <iframe
          title={episode.title}
          src={episode.embedUrl}
          className="h-[152px] w-full border-0"
          allow="autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture"
          loading="lazy"
        />
      </div>
    </div>
  );
}
