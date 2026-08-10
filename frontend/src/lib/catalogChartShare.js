/** Sdílení grafu z katalogu — odkaz na náhled a předání do zpráv. */

export function resolveCatalogShareId(catalogId) {
  const id = String(catalogId || "").trim().toLowerCase();
  if (id === "ecb") return "ecb2";
  if (id === "oecd") return "oecd4";
  return id;
}

/** Přímý odkaz na otevřený náhled grafu v globálním katalogu. */
export function buildCatalogChartPageShareUrl({ catalogId, setId, title, indicatorId } = {}) {
  const cid = resolveCatalogShareId(catalogId);
  const sid = String(setId || "").trim();
  if (!cid || !sid) return "";
  const qp = new URLSearchParams();
  const q = String(title || sid).trim();
  if (q) qp.set("q", q);
  qp.set("catalog", cid);
  qp.set("set_id", sid);
  qp.set("preview", "1");
  const ind = String(indicatorId || "").trim();
  if (ind) qp.set("indicator_id", ind);
  return `/search/catalog?${qp.toString()}`;
}

/** Otevře zprávy s připraveným grafem ke sdílení (s volitelným cílem). */
export function buildCatalogChartMessagesShareUrl({
  title,
  sourceType,
  setId,
  pageUrl,
  userId,
  conversationId,
} = {}) {
  const qp = new URLSearchParams({ share: "chart" });
  const chartTitle = String(title || "").trim();
  const sid = String(setId || "").trim();
  const st = String(sourceType || "").trim();
  const uid = String(userId || "").trim();
  const cid = String(conversationId || "").trim();
  if (chartTitle) qp.set("chart_title", chartTitle);
  if (sid) qp.set("chart_set_id", sid);
  if (st) qp.set("chart_source_type", st);
  if (pageUrl) qp.set("chart_url", pageUrl);
  if (uid) qp.set("user", uid);
  if (cid) qp.set("conversation", cid);
  return `/messages?${qp.toString()}`;
}

export function buildCatalogShareContext({
  catalogId,
  sourceType,
  setId,
  title,
  indicatorId,
  pageUrl,
  origin,
} = {}) {
  const cid = resolveCatalogShareId(catalogId || sourceType);
  const sid = String(setId || "").trim();
  const chartTitle = String(title || sid || "Graf").trim();
  const pageLink = buildCatalogChartPageShareUrl({
    catalogId: cid,
    setId: sid,
    title: chartTitle,
    indicatorId,
  });
  const baseOrigin =
    String(origin || "").trim() ||
    (typeof window !== "undefined" ? window.location.origin : "");
  const absolutePageLink = pageLink && baseOrigin ? `${baseOrigin}${pageLink}` : pageLink;
  const linkForMessages = pageUrl || absolutePageLink || pageLink;
  return {
    catalogId: cid,
    sourceType: String(sourceType || cid).trim(),
    setId: sid,
    title: chartTitle,
    pageLink,
    absolutePageLink,
    messagesLink: buildCatalogChartMessagesShareUrl({
      title: chartTitle,
      sourceType: String(sourceType || cid).trim(),
      setId: sid,
      pageUrl: linkForMessages,
    }),
  };
}
