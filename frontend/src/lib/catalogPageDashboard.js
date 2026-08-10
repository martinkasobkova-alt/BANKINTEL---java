/**
 * Sdílená logika „Přidat na dashboard“ pro katalogové stránky s CatalogChartPreview.
 */
import { toast } from "sonner";
import { formatApiErrorFromAxios } from "@/lib/api";
import { buildExternalCatalogChartConfig } from "@/lib/catalogPersonalDashboard";
import { createExternalCatalogWidgetWithSnapshot } from "@/lib/catalogDashboardWidget";
import {
  defaultPersonalDashboardPageId,
  ensurePersonalDashboardPages,
} from "@/lib/personalDashboardPages";

/**
 * @param {object} opts
 * @param {import('axios').AxiosInstance} opts.api
 * @param {Function} opts.nav — react-router navigate
 * @param {object} opts.def — catalog definition (id, sourceType)
 * @param {object} opts.previewData
 * @param {object} opts.row — catalog row (set_id, name, …)
 * @param {string|null} [opts.wbCountry]
 * @param {object} [opts.feature]
 * @param {(state: { pages: object[], built: object, selectedId: string }) => void} [opts.setPagePick]
 * @returns {Promise<boolean>}
 */
export async function addCatalogPreviewToPersonalDashboard({
  api,
  nav,
  def,
  previewData,
  row,
  wbCountry = null,
  feature = {},
  setPagePick,
  seriesConfig = null,
}) {
  const {
    isSubscriber = false,
    canPersonalDashboard = false,
    canSaveWidget = false,
    personalDashMsg = "",
    saveWidgetMsg = "",
  } = feature;

  if (!isSubscriber || !canPersonalDashboard) {
    toast.error(personalDashMsg || "Osobní dashboard není u vašeho účtu k dispozici.");
    return false;
  }
  if (!canSaveWidget) {
    toast.error(saveWidgetMsg || "Uložení widgetů není s vaším plánem k dispozici.");
    return false;
  }

  const built = buildExternalCatalogChartConfig(def, previewData, row, wbCountry, seriesConfig);
  if (!built) {
    toast.error("Vyčkejte na dokončení náhledu a případně vyberte ukazatel v přehledu dat.");
    return false;
  }

  try {
    const pages = await ensurePersonalDashboardPages(api);
    if (!pages.length) {
      toast.error("Nepodařilo se vytvořit výchozí stránku dashboardu.");
      return false;
    }
    if (pages.length > 1 && typeof setPagePick === "function") {
      setPagePick({
        pages,
        built,
        selectedId: defaultPersonalDashboardPageId(pages),
      });
      return false;
    }
    const pageId = defaultPersonalDashboardPageId(pages);
    await createExternalCatalogWidgetWithSnapshot(api, pageId, built);
    toast.success("Graf byl přidán do vašeho dashboardu.", {
      action: {
        label: "Otevřít můj dashboard",
        onClick: () => nav("/my-dashboard"),
      },
    });
    return true;
  } catch (e) {
    toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se přidat widget");
    return false;
  }
}

/** Vrátí props pro catalogChartActions z feature kontextu. */
export function buildCatalogChartActionsProps({
  feature,
  previewData,
  previewError,
  previewLoading,
  onAddToDashboard,
  addingToDashboard = false,
}) {
  const { isSubscriber, canPersonalDashboard, canSaveWidget } = feature;
  const hasPreviewData =
    Boolean(previewData) &&
    !previewData?.all_values_zero &&
    previewData?.preview_state !== "all_zero" &&
    !previewData?.metadata?.all_values_zero;
  return {
    show: Boolean(!previewError && (previewLoading || hasPreviewData)),
    canAddToDashboard: Boolean(isSubscriber && canPersonalDashboard && canSaveWidget),
    onAddToDashboard,
    addingToDash: addingToDashboard,
    loading: previewLoading,
    previewError: Boolean(previewError),
    hasPreviewData,
  };
}

export async function confirmPersonalDashboardPagePick({
  api,
  nav,
  pagePick,
  onDone,
}) {
  if (!pagePick?.built) return false;
  const pageId = String(pagePick.selectedId || "").trim();
  if (!pageId) {
    toast.error("Vyberte stránku.");
    return false;
  }
  try {
    await createExternalCatalogWidgetWithSnapshot(api, pageId, pagePick.built);
    toast.success("Graf byl přidán do vašeho dashboardu.", {
      action: {
        label: "Otevřít můj dashboard",
        onClick: () => nav("/my-dashboard"),
      },
    });
    onDone?.();
    return true;
  } catch (e) {
    toast.error(formatApiErrorFromAxios(e) || "Nepodařilo se přidat widget");
    return false;
  }
}
