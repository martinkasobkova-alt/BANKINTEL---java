/** Komunikace mobilní aplikace (WebView) ↔ embed stránka grafu. */

export const MOBILE_EMBED_MSG = {
  READY: "banko-ready",
  INIT: "banko-init",
  ERROR: "banko-error",
};

export function parseMobileEmbedMessage(raw) {
  if (raw == null || raw === "") return null;
  try {
    const data = typeof raw === "string" ? JSON.parse(raw) : raw;
    if (!data || typeof data !== "object") return null;
    return data;
  } catch {
    return null;
  }
}

export function postToReactNative(payload) {
  try {
    if (typeof window !== "undefined" && window.ReactNativeWebView?.postMessage) {
      window.ReactNativeWebView.postMessage(JSON.stringify(payload));
    }
  } catch {
    // no-op
  }
}

export function isMobileEmbedPath() {
  if (typeof window === "undefined") return false;
  return String(window.location.pathname || "").startsWith("/m/widget-chart");
}
