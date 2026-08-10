import React, { useCallback, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import api, { formatApiErrorFromAxios } from "@/lib/api";

import AppShell from "@/components/layout/AppShell";

import CatalogHubNav from "@/components/catalog/CatalogHubNav";

import CatalogTopicBrowseSection from "@/components/catalog/CatalogTopicBrowseSection";

import CatalogChartPreview from "@/components/catalog/CatalogChartPreview";
import CatalogPreviewFullscreenOverlay from "@/components/catalog/search/CatalogPreviewFullscreenOverlay";

import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";

import { CATALOGS } from "@/lib/catalogDefinitions";

import { buildCatalogPreviewBody, resolveCatalogRowDef } from "@/lib/catalogPreviewBody";
import { geoDisplayLabel } from "@/lib/macroGeoLabels";
import {
  extractCountryCodesFromFilters,
  normalizeSelectedIndicators,
  resolveInitialGeoFromRow,
} from "@/lib/catalogLivePreview";
import { fetchMacroTopicSeriesPreview } from "@/lib/macroTopicSnapshot";
import {
  addCatalogPreviewToPersonalDashboard,
  buildCatalogChartActionsProps,
} from "@/lib/catalogPageDashboard";
import {
  buildPreviewPayloadFromStructuredError,
  buildUnknownPreviewShapeMessage,
  normalizePreviewPayload,
  previewShapeDebug,
} from "@/lib/previewNormalizer";

import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import { isMacroComparisonPreviewRow } from "@/lib/resolveMacroCatalogDef";

const LIVE_PREVIEW_EMPTY_MESSAGE =
  "Data pro tuto řadu nejsou ve snapshotu k dispozici. Snapshot se obnovuje každou noc.";

export default function CatalogTopicBrowsePage() {
  const nav = useNavigate();
  const [previewRow, setPreviewRow] = useState(null);
  const [previewDef, setPreviewDef] = useState(null);
  const [previewData, setPreviewData] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [previewCompareList, setPreviewCompareList] = useState([]);
  const [addingToDash, setAddingToDash] = useState(false);

  const { isSubscriber } = useAuth();
  const { allowed: canPersonalDashboard, message: personalDashMsg } = useFeatureAccess("personal_dashboard");
  const { allowed: canSaveWidget, message: saveWidgetMsg } = useFeatureAccess("save_widget");
  const dashboardFeature = useMemo(
    () => ({
      isSubscriber,
      canPersonalDashboard,
      canSaveWidget,
      personalDashMsg,
      saveWidgetMsg,
    }),
    [isSubscriber, canPersonalDashboard, canSaveWidget, personalDashMsg, saveWidgetMsg]
  );

  const previewDataRef = useRef(null);
  const previewSeqRef = useRef(0);

  const fetchPreview = useCallback(
    async (def, row, indicatorId, indicatorIds = [], geoValues = [], dimensionFilters = null) => {
      const seq = ++previewSeqRef.current;
      setPreviewLoading(true);
      setPreviewError("");

      try {
        let normalized;
        if (isMacroComparisonPreviewRow(row)) {
          normalized = await fetchMacroTopicSeriesPreview(row);
        } else {
          const body = buildCatalogPreviewBody(def, row);
          if (row?.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)) {
            body.query_params = { ...(body.query_params || {}), ...row.query_params };
          }

          const rowSelected = String(
            row?.selected_indicator || row?.indicator_id || row?.query_params?.selected_indicator || ""
          ).trim();
          const selectedOne = String(indicatorId || rowSelected || "").trim();
          const requestedMany = normalizeSelectedIndicators(indicatorIds);
          const rowMany = normalizeSelectedIndicators(row?.selected_indicators);
          const selectedMany = requestedMany.length ? requestedMany : rowMany;

          if (selectedOne) body.selected_indicator = selectedOne;
          if (selectedMany.length) {
            body.selected_indicators = [...new Set(selectedMany)];
            if (!body.selected_indicator) body.selected_indicator = body.selected_indicators[0];
          }
          if (dimensionFilters && typeof dimensionFilters === "object" && !Array.isArray(dimensionFilters)) {
            body.dimension_filters = dimensionFilters;
          }
          const geo = (Array.isArray(geoValues) ? geoValues : [])
            .map((x) => String(x || "").trim())
            .filter(Boolean);
          if (geo.length) {
            body.geo = geo[0];
            body.geo_values = geo;
          }

          const { data } = await api.post("/catalog/preview", body, { timeout: 90_000 });
          normalized = normalizePreviewPayload(data, def?.sourceType);
        }

        if (seq !== previewSeqRef.current) return;

        const shape = previewShapeDebug(normalized);
        const rowCount = Array.isArray(normalized?.rows) ? normalized.rows.length : 0;
        const hasKnownDataArrays = shape.hasRows || shape.hasData || shape.hasObservations;
        const structuredErr = String(normalized?.error || "").trim();

        if (structuredErr) {
          setPreviewData(normalized);
          setPreviewError(structuredErr);
          return;
        }

        if (
          rowCount === 0 &&
          normalized?.status !== "needs_filters" &&
          !String(normalized?.message || "").trim() &&
          !hasKnownDataArrays
        ) {
          const errMsg = buildUnknownPreviewShapeMessage(normalized);
          setPreviewData(normalized);
          setPreviewError(errMsg);
          return;
        }

        setPreviewData(normalized);
        setPreviewError("");
      } catch (e) {
        if (seq !== previewSeqRef.current) return;
        const errPayload = e?.response?.data;
        if (errPayload && typeof errPayload === "object") {
          const structured = buildPreviewPayloadFromStructuredError(errPayload, {
            source_type: def?.sourceType,
            set_id: row?.set_id,
            name: row?.name || row?.title,
          });
          setPreviewData(structured);
        }
        setPreviewError(formatApiErrorFromAxios(e));
      } finally {
        if (seq === previewSeqRef.current) {
          setPreviewLoading(false);
        }
      }
    },
    []
  );

  const handlePreviewSeries = useCallback(
    async (def, row) => {
      const effectiveDef = resolveCatalogRowDef(def, row);
      const macroComparisonRow = isMacroComparisonPreviewRow(row);
      if (!macroComparisonRow && !isCatalogRowPreviewEligible(effectiveDef, row)) {
        toast.info("Tato řada nemá platný identifikátor pro náhled u tohoto zdroje.");
        return;
      }

      previewSeqRef.current += 1;
      setPreviewDef(effectiveDef);
      setPreviewRow(row);
      setPreviewData(null);
      previewDataRef.current = null;
      setPreviewError("");
      setPreviewCompareList([]);

      const initialGeo = resolveInitialGeoFromRow(row);
      await fetchPreview(effectiveDef, row, undefined, [], initialGeo);
    },
    [fetchPreview]
  );

  const closePreview = () => {
    previewSeqRef.current += 1;
    setPreviewRow(null);
    setPreviewDef(null);
    setPreviewData(null);
    previewDataRef.current = null;
    setPreviewError("");
    setPreviewCompareList([]);
  };

  previewDataRef.current = previewData;

  const previewSourceType = previewDef?.sourceType;
  const previewCountryCode = useMemo(() => {
    const fromRow = resolveInitialGeoFromRow(previewRow || {});
    return fromRow[0] || "";
  }, [previewRow]);

  const readGeoForRefetch = useCallback(() => {
    return extractCountryCodesFromFilters(previewData?.metadata?.filters_applied).length > 0
      ? extractCountryCodesFromFilters(previewData?.metadata?.filters_applied)
      : extractCountryCodesFromFilters(previewData?.requested_filters);
  }, [previewData]);

  const handleAddToDashboard = useCallback(async (seriesConfig = null) => {
    if (!previewDef || !previewRow || !previewData || previewError) return;
    setAddingToDash(true);
    try {
      await addCatalogPreviewToPersonalDashboard({
        api,
        nav,
        def: previewDef,
        previewData,
        row: {
          ...previewRow,
          set_id: String(previewRow.set_id ?? "").trim(),
          name: previewRow.name || previewRow.title,
        },
        feature: dashboardFeature,
        seriesConfig,
      });
    } finally {
      setAddingToDash(false);
    }
  }, [previewDef, previewRow, previewData, previewError, nav, dashboardFeature]);

  const handleCompareSave = useCallback(
    async (payload) => {
      if (!previewDef || !previewRow) return;
      const compareIds = (payload?.chart_compare_with || [])
        .map((c) => String(c?.selected_indicator || "").trim())
        .filter(Boolean);
      setPreviewCompareList(Array.isArray(payload?.chart_compare_with) ? payload.chart_compare_with : []);
      const main = String(previewData?.selected_indicator || "").trim();
      const all = [...new Set([main, ...compareIds].filter(Boolean))];
      const geo = readGeoForRefetch();
      const dimFilters = previewData?.dimension_filters || previewData?.metadata?.filters_applied || null;
      await fetchPreview(previewDef, previewRow, main || all[0], all, geo, dimFilters);
    },
    [previewDef, previewRow, previewData, fetchPreview, readGeoForRefetch]
  );

  const sourcePreviewProps = useMemo(() => {
    if (!previewRow || !previewDef) return {};
    return {
      compact: false,
      catalogCountryCode: previewCountryCode,
      catalogCountryLabel: geoDisplayLabel(previewRow),
      onIndicatorChange: (indicatorId) => {
        void fetchPreview(previewDef, previewRow, indicatorId, [indicatorId], readGeoForRefetch());
      },
      onGeoSelectionChange:
        previewSourceType === "imf"
          ? undefined
          : (geoIds) => {
              const many = normalizeSelectedIndicators(previewData?.selected_indicators);
              const one = String(many[0] || previewData?.selected_indicator || "").trim();
              void fetchPreview(previewDef, previewRow, one, many, geoIds);
            },
      onDimensionFiltersApply:
        previewSourceType === "imf"
          ? undefined
          : (dimensionFilters) => {
              const many = normalizeSelectedIndicators(previewData?.selected_indicators);
              const one = String(many[0] || previewData?.selected_indicator || "").trim();
              const geo = extractCountryCodesFromFilters(dimensionFilters);
              void fetchPreview(previewDef, previewRow, one, many, geo, dimensionFilters);
            },
      catalogChartActions: buildCatalogChartActionsProps({
        feature: dashboardFeature,
        previewData,
        previewError,
        previewLoading,
        onAddToDashboard: handleAddToDashboard,
        addingToDashboard: addingToDash,
      }),
    };
  }, [
    previewRow,
    previewDef,
    previewCountryCode,
    previewSourceType,
    previewData,
    previewError,
    previewLoading,
    fetchPreview,
    readGeoForRefetch,
    dashboardFeature,
    handleAddToDashboard,
    addingToDash,
  ]);

  const chartPreview = useMemo(() => {
    if (!previewRow || !previewDef) return null;
    const title = previewRow.name || previewRow.title || "Náhled";
    const source = {
      name: title,
      source_type: previewDef.sourceType,
    };
    const emptyMessage = String(previewData?.message || previewError || "").trim() || LIVE_PREVIEW_EMPTY_MESSAGE;
    const preview =
      previewError && !previewData?.rows?.length
        ? { error: previewError || emptyMessage, source }
        : previewData
          ? { ...previewData, source }
          : { source, message: emptyMessage };

    return (
      <CatalogChartPreview
        widgetId={`catalog-topic-preview-${String(previewRow.set_id || "row").slice(0, 40)}`}
        title={title}
        sourceType={previewDef.sourceType}
        catalogDef={previewDef}
        catalogRow={{
          ...previewRow,
          set_id: String(previewRow.set_id ?? "").trim(),
        }}
        preferAradView
        catalogChartSize="fullscreen"
        preview={preview}
        previewError={previewError}
        previewLoading={previewLoading}
        controlsInOptionsPanel
        sourcePreviewProps={sourcePreviewProps}
        actions={{
          compareList: previewCompareList,
          onCompareSave: handleCompareSave,
        }}
      />
    );
  }, [
    previewRow,
    previewDef,
    previewData,
    previewError,
    previewLoading,
    sourcePreviewProps,
    previewCompareList,
    handleCompareSave,
  ]);

  return (
    <AppShell
      title="Procházet podle zemí a témat"
      subtitle="Kurátorovaný výběr makro ukazatelů — vyberte téma nebo zemi, pak konkrétní datovou řadu."
    >
      <div className="catalog-dark-scope space-y-4 max-w-none">
        <CatalogHubNav />
        <CatalogTopicBrowseSection catalogs={CATALOGS} onPreviewSeries={handlePreviewSeries} />
      </div>

      {previewRow ? (
        <CatalogPreviewFullscreenOverlay
          open
          onClose={closePreview}
          title={previewRow.name || previewRow.title || "Náhled dat"}
          catalogLabel={previewDef?.label}
          code={String(previewRow.set_id || "").trim()}
          previewLoading={previewLoading}
        >
          <div className="flex flex-col flex-1 min-h-0 h-full" data-testid="catalog-topic-preview-panel">
            {chartPreview}
          </div>
        </CatalogPreviewFullscreenOverlay>
      ) : null}
    </AppShell>
  );
}
