/** URL obálky epizody — absolutní pro <img src>. */
export function resolveEpisodeCoverUrl(coverUrl) {
  const raw = String(coverUrl || "").trim();
  if (!raw) return null;
  if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;
  if (raw.startsWith("/api/") && typeof window !== "undefined") {
    return `${window.location.origin}${raw}`;
  }
  return null;
}
