import api from "@/lib/api";
import { CATALOGS, STOCK_MARKET_CATALOGS } from "@/lib/catalogDefinitions";
import { buildCatalogPreviewBody } from "@/lib/catalogPreviewBody";
import { buildExternalCatalogChartConfig } from "@/lib/catalogPersonalDashboard";
import { createExternalCatalogWidgetWithSnapshot } from "@/lib/catalogDashboardWidget";

// Akcie (yahoo_finance/alphavantage) jsou schvalne mimo CATALOGS - stejny duvod jako
// ChartAnalystTrigger.jsx (catalogDefForSuggestion).
const ALL_CATALOGS = [...CATALOGS, ...STOCK_MARKET_CATALOGS];

function catalogDefById(catalogId) {
  const id = String(catalogId || "").trim().toLowerCase();
  if (!id) return null;
  return (
    ALL_CATALOGS.find(
      (def) => String(def.id || "").toLowerCase() === id || String(def.sourceType || "").toLowerCase() === id,
    ) || null
  );
}

/**
 * "AI nad grafem" umí navrhnout přidání řady (`add_catalog_series` chart_actions) - AradView pak
 * tuhle akci spojí do `chart_compare_with` existujícího grafu. "AI nad dashboardem" ale nemusí mít
 * žádný "aktuální graf" (rozsah "Celý dashboard") a i u rozsahu "Aktuální graf" dává větší smysl
 * přidat NOVÝ nezávislý widget, ne řadu do existujícího srovnání. Tahle funkce proto ze stejné
 * akce rovnou vytvoří nový widget na dané stránce dashboardu - stejným mechanismem jako ruční
 * "Přidat na dashboard" v katalogovém vyhledávání (verifikace přes /catalog/preview,
 * buildExternalCatalogChartConfig, POST /me/dashboard/pages/{id}/widgets).
 */
export async function addDashboardWidgetFromChatAction(action, pageId) {
  const catalog = String(action?.catalog || action?.source || "").trim().toLowerCase();
  const setId = String(action?.set_id || "").trim();
  const selectedIndicator = String(action?.selected_indicator || action?.indicator_id || "").trim();
  const name = String(action?.name || action?.title || setId || selectedIndicator || "").trim();
  if (!catalog || (!setId && !selectedIndicator) || !pageId) {
    return { ok: false, reason: "missing_identity" };
  }

  const def = catalogDefById(catalog);
  if (!def) return { ok: false, reason: "unknown_catalog" };

  const row = {
    set_id: setId,
    name,
    selected_indicator: selectedIndicator,
    indicator_id: selectedIndicator,
  };

  try {
    const body = buildCatalogPreviewBody(def, row);
    const { data: previewData } = await api.post("/catalog/preview", body, { timeout: 20_000 });
    const rows =
      (Array.isArray(previewData?.rows) && previewData.rows) ||
      (Array.isArray(previewData?.data) && previewData.data) ||
      [];
    if (!rows.length) return { ok: false, reason: "no_data" };

    const built = buildExternalCatalogChartConfig(def, previewData, row, null);
    if (!built) return { ok: false, reason: "config_build_failed" };

    const widget = await createExternalCatalogWidgetWithSnapshot(api, pageId, built);
    return { ok: true, widget, title: built.title };
  } catch {
    return { ok: false, reason: "request_failed" };
  }
}

/**
 * Zpracuje chart_actions navržené AI chatem a vytvoří odpovídající widgety na dané stránce
 * dashboardu. Vrací true, pokud se přidal alespoň jeden widget (ChartAnalystPanel to použije pro
 * hlášku "řadu se nepodařilo přidat" při false).
 */
export async function addDashboardWidgetsFromChatActions(actions, pageId) {
  const list = Array.isArray(actions) ? actions : [];
  const addActions = list.filter((a) => String(a?.type || "").trim() === "add_catalog_series");
  if (!addActions.length || !pageId) return false;
  let anyAdded = false;
  for (const action of addActions) {
    // eslint-disable-next-line no-await-in-loop
    const result = await addDashboardWidgetFromChatAction(action, pageId);
    if (result.ok) anyAdded = true;
  }
  return anyAdded;
}
