/** Přímý audio soubor vhodný pro HTML5 přehrávač na pozadí. */
export function resolveDirectAudioUrl(url) {
  const raw = String(url || "").trim();
  if (!raw) return null;
  if (/\.(mp3|m4a|mp4|aac|ogg|wav|mpeg)(\?|$)/i.test(raw)) return raw;
  try {
    const u = new URL(raw);
    if (/\.(mp3|m4a|mp4|aac|ogg|wav|mpeg)$/i.test(u.pathname)) return raw;
  } catch {
    return null;
  }
  return null;
}

/** URL pro HTML5 audio — přímý soubor nebo stream z naší API. */
export function resolveEpisodePlayUrl(audioUrl) {
  const raw = String(audioUrl || "").trim();
  if (!raw) return null;
  const direct = resolveDirectAudioUrl(raw);
  if (direct) return direct;
  if (raw.startsWith("/api/") && typeof window !== "undefined") {
    return `${window.location.origin}${raw}`;
  }
  return null;
}

export function canPlayEpisodeInBackground(item) {
  if (!item) return false;
  if (item.play_mode === "background") return Boolean(item.audio_url);
  return Boolean(resolveEpisodePlayUrl(item.audio_url));
}

export function formatPodcastTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  return `${m}:${String(s).padStart(2, "0")}`;
}

