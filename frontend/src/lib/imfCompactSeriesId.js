/**
 * SDMX 3 browse strom: IMF|agency|flow|version|sdmx_key (např. IMF|IMF.RES|WEO|9.0.0|CZE.TM_RPCH).
 */
export function isImfSdmx3SeriesPreviewable(setId) {
  const s = String(setId ?? "").trim();
  if (!s.startsWith("IMF|")) return false;
  const parts = s.split("|");
  if (parts.length < 5) return false;
  const flow = String(parts[2] || "").trim();
  const key = String(parts[4] || "").trim();
  return flow.length >= 2 && key.length >= 3 && key.includes(".");
}

/**
 * Platný IMF CompactData set_id jako konkrétní řada (databáze / dimenze s tečkami).
 * Nejde o samotný label „IFS“ ani čistě tematické jméno bez řadové struktury.
 */
export function isImfCompactSeriesPreviewable(setId) {
  const s = String(setId ?? "").trim();
  if (isImfSdmx3SeriesPreviewable(s)) return true;
  const idx = s.indexOf("/");
  if (idx <= 0) return false;
  const q = s.slice(idx + 1).trim();
  if (q.length < 4 || !q.includes(".")) return false;
  if (q.toUpperCase() === "IFS") return false;
  return true;
}

/** Náhled v globálním katalogu — CompactData nebo SDMX 3 pipe tvar. */
export function isImfSeriesPreviewable(setId) {
  return isImfCompactSeriesPreviewable(setId);
}
