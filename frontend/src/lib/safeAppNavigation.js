/**
 * Accepts only meaningful local application links. A bare root URL is not a
 * valid deep link for a chart and previously hid broken links on the homepage.
 */
export function resolveSafeAppPath(value) {
  const raw = String(value || "").trim();
  if (!raw || raw === "/" || !raw.startsWith("/") || raw.startsWith("//")) return "";
  try {
    const parsed = new URL(raw, "http://bankintel.local");
    if (parsed.origin !== "http://bankintel.local" || parsed.pathname === "/") return "";
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return "";
  }
}
