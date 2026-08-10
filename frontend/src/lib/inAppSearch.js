function searchText(value) {
  if (value == null) return "";
  if (["string", "number", "boolean"].includes(typeof value)) return String(value);
  if (Array.isArray(value)) return value.map(searchText).join(" ");
  if (typeof value === "object") return Object.values(value).map(searchText).join(" ");
  return "";
}

function foldSearch(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/\s+/g, " ");
}

function queryTokens(query) {
  return foldSearch(query).split(/\s+/).filter((token) => token.length >= 2);
}

function matchesQuery(haystack, query) {
  const tokens = queryTokens(query);
  if (!tokens.length) return false;
  const hay = foldSearch(haystack);
  return tokens.every((token) => hay.includes(token));
}

function normalizeHomepageResult(row, idx) {
  const section = String(row?.section || row?.page_title || "Aplikace").trim();
  const path = String(row?.path || "/").trim() || "/";
  return {
    id: `public:${row?.id || idx}`,
    title: String(row?.title || "Bez názvu").trim(),
    type: String(row?.type || "").trim(),
    view: String(row?.view || "").trim(),
    section,
    pageTitle: String(row?.subpage_title || row?.page_title || section).trim(),
    path,
    surface: path === "/" ? "Přehled" : "Sekce",
    source: "public",
  };
}

function dashboardWidgetResult(widget, page, idx) {
  const wid = String(widget?.id || idx).trim();
  const pageId = String(page?.id || widget?.page_id || "").trim();
  const pageTitle = String(page?.title || "Můj dashboard").trim();
  return {
    id: `dashboard:${pageId}:${wid}`,
    title: String(widget?.title || widget?.config?.title || "Bez názvu").trim(),
    type: String(widget?.type || "").trim(),
    view: String(widget?.config?.view || widget?.config?.chart_type || "").trim(),
    section: "Můj dashboard",
    pageTitle,
    path: pageId ? `/my-dashboard?page=${encodeURIComponent(pageId)}#widget-${encodeURIComponent(wid)}` : "/my-dashboard",
    surface: "Můj dashboard",
    source: "dashboard",
  };
}

async function searchPublicApp(apiClient, query) {
  const { data } = await apiClient.get("/homepage/search", {
    params: { q: query },
    timeout: 12_000,
  });
  return (Array.isArray(data?.results) ? data.results : []).map(normalizeHomepageResult);
}

async function searchPersonalDashboard(apiClient, query) {
  let pages = [];
  try {
    const { data } = await apiClient.get("/me/dashboard/pages", { timeout: 12_000 });
    pages = Array.isArray(data) ? data : Array.isArray(data?.pages) ? data.pages : [];
  } catch {
    return [];
  }

  const settled = await Promise.allSettled(
    pages.map(async (page) => {
      const pageId = String(page?.id || "").trim();
      if (!pageId) return [];
      const { data } = await apiClient.get(`/me/dashboard/pages/${encodeURIComponent(pageId)}/widgets`, {
        timeout: 12_000,
      });
      const widgets = Array.isArray(data) ? data : Array.isArray(data?.widgets) ? data.widgets : [];
      return widgets
        .filter((widget) =>
          matchesQuery(
            [
              widget?.title,
              widget?.type,
              widget?.config,
              page?.title,
              "můj dashboard",
              "osobní dashboard",
            ].map(searchText).join(" "),
            query,
          ),
        )
        .map((widget, idx) => dashboardWidgetResult(widget, page, idx));
    }),
  );

  return settled.flatMap((result) => (result.status === "fulfilled" ? result.value : []));
}

export async function runInAppSearch(apiClient, { query, limit = 40 } = {}) {
  const q = String(query || "").trim();
  if (q.length < 2) return { query: q, results: [], errors: [] };

  const settled = await Promise.allSettled([
    searchPublicApp(apiClient, q),
    searchPersonalDashboard(apiClient, q),
  ]);

  const errors = [];
  const results = [];
  for (const result of settled) {
    if (result.status === "fulfilled") {
      results.push(...result.value);
    } else {
      errors.push(String(result.reason?.message || result.reason || "Vyhledávání v aplikaci selhalo."));
    }
  }

  const seen = new Set();
  const unique = [];
  for (const item of results) {
    const key = `${item.source}:${item.path}:${foldSearch(item.title)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(item);
  }

  return { query: q, results: unique.slice(0, limit), errors };
}
