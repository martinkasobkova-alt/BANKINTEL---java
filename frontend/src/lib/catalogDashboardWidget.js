/**
 * Vytvoří external_catalog_chart widget a ihned uloží data_snapshot přes render-widget.
 */
export async function createExternalCatalogWidgetWithSnapshot(api, pageId, built) {
  const { data: widget } = await api.post(`/me/dashboard/pages/${pageId}/widgets`, {
    type: "external_catalog_chart",
    title: built.title,
    description: "",
    config: built.config,
  });
  const wid = widget?.id;
  if (wid) {
    try {
      await api.post("/me/dashboard/render-widget", { id: wid });
    } catch {
      /* snapshot je best-effort — widget existuje */
    }
  }
  return widget;
}
