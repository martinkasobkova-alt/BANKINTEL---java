/** Sestaví HTML iframe pro vložení widgetu do externí stránky / článku. */
export function buildWidgetEmbedIframeCode(shareToken, widgetId, { height = 420, origin } = {}) {
  const tok = String(shareToken || "").trim();
  const wid = String(widgetId || "").trim();
  if (!tok || !wid) return "";
  const base =
    typeof origin === "string" && origin.trim()
      ? origin.trim().replace(/\/$/, "")
      : typeof window !== "undefined"
        ? window.location.origin
        : "";
  const src = `${base}/embed/${encodeURIComponent(tok)}/${encodeURIComponent(wid)}`;
  return `<iframe src="${src}" width="100%" height="${height}" style="border:0" loading="lazy"></iframe>`;
}

export function buildWidgetEmbedPageUrl(shareToken, widgetId, { origin } = {}) {
  const tok = String(shareToken || "").trim();
  const wid = String(widgetId || "").trim();
  if (!tok || !wid) return "";
  const base =
    typeof origin === "string" && origin.trim()
      ? origin.trim().replace(/\/$/, "")
      : typeof window !== "undefined"
        ? window.location.origin
        : "";
  return `${base}/embed/${encodeURIComponent(tok)}/${encodeURIComponent(wid)}`;
}
