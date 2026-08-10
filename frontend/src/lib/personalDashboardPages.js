/** Pomocné funkce pro výběr stránky osobního dashboardu. */

export function defaultPersonalDashboardPageId(pages = []) {
  const list = Array.isArray(pages) ? pages : [];
  return (list.find((p) => p.is_default) || list[0])?.id || null;
}

export async function loadPersonalDashboardPages(api) {
  const { data } = await api.get("/me/dashboard/pages");
  return Array.isArray(data) ? data : [];
}

export async function ensurePersonalDashboardPages(api, { createTitle = "Můj přehled" } = {}) {
  let pages = await loadPersonalDashboardPages(api);
  if (pages.length) return pages;
  const { data: created } = await api.post("/me/dashboard/pages", { title: createTitle });
  if (created?.id) return [created];
  return [];
}
