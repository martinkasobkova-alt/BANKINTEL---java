import { CATALOGS } from "@/lib/catalogDefinitions";
import { resolveCatalogRowDef } from "@/lib/catalogPreviewBody";

const CATALOG_ID_ALIASES = {
  ecb: "ecb2",
  oecd: "oecd4",
  world_bank_data360: "data360",
};

export function parseCatalogIdFromLinkUrl(linkUrl) {
  try {
    const raw = String(linkUrl || "").trim();
    if (!raw.startsWith("/")) return "";
    const u = new URL(raw, "http://local");
    return String(u.searchParams.get("catalog") || "").trim().toLowerCase();
  } catch {
    return "";
  }
}

export function parseDashboardPageIdFromLinkUrl(linkUrl) {
  try {
    const raw = String(linkUrl || "").trim();
    if (!raw.startsWith("/")) return "";
    const u = new URL(raw, "http://local");
    if (!u.pathname.includes("my-dashboard")) return "";
    return String(u.searchParams.get("page") || "").trim();
  } catch {
    return "";
  }
}

export function resolveCatalogDefForSource(sourceType) {
  const st = String(sourceType || "").trim().toLowerCase();
  if (!st) return null;
  const alias = CATALOG_ID_ALIASES[st] || st;
  return (
    CATALOGS.find((c) => c.id === alias || c.id === st || c.sourceType === st) || null
  );
}

/** Typ cíle propojení v PDF (graf, web, video, dokument, podcast). */
export function resolvePdfLinkTargetKind(link) {
  if (!link || typeof link !== "object") return "chart";
  const k = String(link.target_kind || "").trim().toLowerCase();
  if (k === "web" || k === "video" || k === "document" || k === "chart" || k === "podcast") return k;
  const st = String(link.source_type || "").trim().toLowerCase();
  if (st === "external_web") return "web";
  if (st === "external_video") return "video";
  if (st === "external_document") return "document";
  if (st === "external_podcast") return "podcast";
  return "chart";
}

export function pdfLinkOpenLabel(link) {
  const kind = resolvePdfLinkTargetKind(link);
  if (kind === "video") return "Zobrazit video";
  if (kind === "podcast") return "Poslechnout podcast";
  if (kind === "web") return "Otevřít stránku";
  if (kind === "document") return "Otevřít dokument";
  return link?.target_title || "Zobrazit graf";
}

/** Převod uloženého propojení PDF → kontext pro overlay náhled. */
export function pdfLinkToPreviewContext(link) {
  if (!link || typeof link !== "object") return null;
  const targetKind = resolvePdfLinkTargetKind(link);
  const title = String(link.target_title || link.title || link.label || "").trim();
  const url = String(link.link_url || "").trim();

  if (targetKind === "video" && url) {
    return { kind: "video", url, title: title || "Video" };
  }
  if (targetKind === "podcast" && url) {
    return { kind: "podcast", url, title: title || "Podcast" };
  }
  if (targetKind === "web" && url) {
    return { kind: "web", url, title: title || "Webová stránka" };
  }
  if (targetKind === "document" && url) {
    return { kind: "document", url, title: title || "Dokument" };
  }

  const sourceType = String(link.source_type || "").trim().toLowerCase();
  const setId = String(link.set_id || "").trim();

  if (sourceType === "dashboard_widget" || setId.startsWith("dashboard_widget:")) {
    const linkUrl = String(link.link_url || "").trim();
    return {
      kind: "dashboard",
      widgetId: setId.replace(/^dashboard_widget:/, ""),
      pageId: parseDashboardPageIdFromLinkUrl(linkUrl),
      title,
      linkUrl,
    };
  }
  if (sourceType === "my_series" || setId.startsWith("my_series:")) {
    return {
      kind: "my_series",
      seriesId: setId.replace(/^my_series:/, ""),
      title,
      linkUrl: String(link.link_url || "").trim(),
    };
  }
  if (sourceType === "my_upload_chart" || setId.startsWith("my_upload_chart:")) {
    return {
      kind: "my_upload",
      chartId: setId.replace(/^my_upload_chart:/, ""),
      title,
      linkUrl: String(link.link_url || "").trim(),
    };
  }

  const catalogId = sourceType || parseCatalogIdFromLinkUrl(link.link_url);
  const def = resolveCatalogDefForSource(catalogId);
  if (!def || !setId) return null;

  const row = {
    set_id: setId,
    source_type: def.sourceType || catalogId,
    name: title || setId,
    title: title || setId,
  };
  return {
    kind: "catalog",
    def: resolveCatalogRowDef(def, row),
    row,
    title,
  };
}

export function isPdfLinkInlinePreviewable(link) {
  const ctx = pdfLinkToPreviewContext(link);
  if (!ctx) return false;
  return (
    ctx.kind === "catalog" ||
    ctx.kind === "dashboard" ||
    ctx.kind === "video" ||
    ctx.kind === "podcast" ||
    ctx.kind === "web" ||
    ctx.kind === "document"
  );
}
