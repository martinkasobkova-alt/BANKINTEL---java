import React from "react";
import { Mic } from "lucide-react";
import { resolveEpisodeCoverUrl } from "@/lib/podcastCover";

export default function PodcastEpisodeArt({ item, className = "h-14 w-14" }) {
  const src = resolveEpisodeCoverUrl(item?.cover_image_url);
  if (src) {
    return (
      <img
        src={src}
        alt=""
        className={`${className} shrink-0 rounded-lg border border-[hsl(var(--border)/0.65)] object-cover bg-[hsl(var(--muted)/0.35)]`}
        loading="lazy"
      />
    );
  }
  return (
    <div
      className={`${className} shrink-0 inline-flex items-center justify-center rounded-lg border border-[hsl(var(--border)/0.65)] bg-[hsl(var(--primary-soft)/0.45)] text-[hsl(var(--primary-deep))]`}
      aria-hidden
    >
      <Mic className="h-5 w-5" />
    </div>
  );
}
