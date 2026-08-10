import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Maximize2 } from "lucide-react";
import AradView from "@/components/widgets/AradView";
import { LoadingSpinner } from "@/components/ui/loading";
import { pdfLinkToPreviewContext } from "@/lib/archiveChartLink";
import { catalogPickerLabel } from "@/lib/catalogChartPickerSearch";
import {
  fetchCatalogLivePreview,
  resolveInitialGeoFromRow,
} from "@/lib/catalogLivePreview";
import { buildAradDataFromCatalogPreview } from "@/lib/mapCatalogPreviewToArad";
import {
  buildPreviewPayloadFromStructuredError,
  buildUnknownPreviewShapeMessage,
  previewShapeDebug,
} from "@/lib/previewNormalizer";
import { formatApiErrorFromAxios } from "@/lib/api";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import {
  isSharedChartInlinePreviewable,
  sharedChartToPreviewLink,
} from "@/lib/sharedChartLink";

/**
 * Sdílený graf ve zprávě — živý mini náhled + tlačítko pro zvětšení.
 */
export default function SharedChartMessagePreview({ sharedChart, onExpand }) {
  const link = useMemo(() => sharedChartToPreviewLink(sharedChart), [sharedChart]);
  const ctx = useMemo(() => pdfLinkToPreviewContext(link), [link]);
  const previewSeqRef = useRef(0);
  const previewDataRef = useRef(null);

  const [previewData, setPreviewData] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");

  const previewable = isSharedChartInlinePreviewable(sharedChart);
  const title = String(sharedChart?.title || sharedChart?.set_id || "Graf").trim();
  const sourceLabel = sharedChart?.source_type
    ? catalogPickerLabel(sharedChart.source_type)
    : "";

  const fetchPreview = useCallback(async (def, row) => {
    const seq = ++previewSeqRef.current;
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const normalized = await fetchCatalogLivePreview({
        def,
        row,
        previewData: previewDataRef.current,
        geoValues: resolveInitialGeoFromRow(row),
      });
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
        setPreviewData(normalized);
        setPreviewError(buildUnknownPreviewShapeMessage(normalized));
        return;
      }
      setPreviewData(normalized);
      setPreviewError("");
    } catch (e) {
      if (seq !== previewSeqRef.current) return;
      const errPayload = e?.response?.data;
      if (errPayload && typeof errPayload === "object") {
        setPreviewData(
          buildPreviewPayloadFromStructuredError(errPayload, {
            source_type: def?.sourceType,
            set_id: row?.set_id,
            name: row?.name || row?.title,
          })
        );
      }
      setPreviewError(formatApiErrorFromAxios(e));
    } finally {
      if (seq === previewSeqRef.current) setPreviewLoading(false);
    }
  }, []);

  useEffect(() => {
    previewSeqRef.current += 1;
    setPreviewData(null);
    previewDataRef.current = null;
    setPreviewError("");
    if (!previewable || !ctx || ctx.kind !== "catalog") return undefined;
    if (!isCatalogRowPreviewEligible(ctx.def, ctx.row)) {
      setPreviewError("Tuto řadu nelze zobrazit jako živý graf.");
      return undefined;
    }
    void fetchPreview(ctx.def, ctx.row);
    return () => {
      previewSeqRef.current += 1;
    };
  }, [ctx, fetchPreview, previewable]);

  previewDataRef.current = previewData;

  const previewRow = ctx?.kind === "catalog" ? ctx.row : null;
  const previewNeedsFilters = previewData?.status === "needs_filters";
  const previewAllZero = Boolean(
    previewData?.all_values_zero
    || previewData?.metadata?.all_values_zero
    || previewData?.preview_state === "all_zero"
  );
  const aradPreviewData = useMemo(
    () =>
      previewData && !previewNeedsFilters && !previewAllZero
        ? buildAradDataFromCatalogPreview(
            {
              ...previewData,
              source: { name: previewRow?.name || previewRow?.title },
            },
            previewRow?.name || previewRow?.title
          )
        : null,
    [previewData, previewNeedsFilters, previewAllZero, previewRow]
  );
  const showAradChart = Boolean(!previewNeedsFilters && !previewAllZero && aradPreviewData?.rows?.length >= 2);
  const linkId = String(link?.id || link?.set_id || "chart").trim();

  const handleExpand = () => {
    if (typeof onExpand === "function" && link) onExpand(link);
  };

  return (
    <div className="mt-2 rounded-md border border-indigo-200 bg-indigo-50/70 p-2">
      <div className="text-[11px] font-semibold text-indigo-900">Sdílený graf</div>
      <div className="text-xs text-slate-800">{title}</div>
      {sourceLabel ? (
        <div className="text-[11px] text-slate-600">Zdroj: {sourceLabel}</div>
      ) : null}

      {previewable ? (
        <div className="mt-2 overflow-hidden rounded-md border border-indigo-200/80 bg-white">
          {previewLoading && !showAradChart ? (
            <div className="flex h-[168px] items-center justify-center gap-2 text-xs text-slate-500">
              <LoadingSpinner className="h-4 w-4" />
              Načítám náhled…
            </div>
          ) : showAradChart && aradPreviewData ? (
            <button
              type="button"
              onClick={handleExpand}
              className="group relative block w-full cursor-pointer text-left"
              title="Zvětšit graf"
            >
              <div className="pointer-events-none max-h-[220px] overflow-hidden">
                <AradView
                  userTitle={previewRow?.name || previewRow?.title || title}
                  data={aradPreviewData}
                  widget={{
                    id: `chat-chart-${linkId}`,
                    type: "external_catalog_chart",
                    width: "sixth",
                    config: { hide_chart_controls: true },
                  }}
                  controlsInOptionsPanel={false}
                />
              </div>
              <span className="absolute bottom-2 right-2 inline-flex h-7 items-center gap-1 rounded-md border border-indigo-300 bg-white/95 px-2 text-[11px] font-semibold text-indigo-700 shadow-sm opacity-100 transition group-hover:bg-indigo-50 sm:opacity-90">
                <Maximize2 className="h-3 w-3" />
                Zvětšit
              </span>
            </button>
          ) : (
            <div className="space-y-2 px-3 py-4 text-center">
              <p className="text-xs text-slate-600">
                {previewNeedsFilters
                  ? "Graf vyžaduje výběr filtrů."
                  : String(previewData?.message || previewError || "Náhled se nepodařilo načíst.").trim()}
              </p>
              <button
                type="button"
                onClick={handleExpand}
                className="inline-flex h-7 items-center gap-1 rounded-md border border-indigo-300 bg-white px-2 text-[11px] font-semibold text-indigo-700 hover:bg-indigo-50"
              >
                <Maximize2 className="h-3 w-3" />
                Otevřít graf
              </button>
            </div>
          )}
        </div>
      ) : sharedChart?.link_url ? (
        <div className="mt-1.5">
          <a
            href={sharedChart.link_url}
            target={sharedChart.link_url.startsWith("/") ? undefined : "_blank"}
            rel={sharedChart.link_url.startsWith("/") ? undefined : "noreferrer"}
            className="inline-flex h-7 items-center rounded-md border border-indigo-300 bg-white px-2 text-[11px] font-semibold text-indigo-700 hover:bg-indigo-50"
          >
            Otevřít graf
          </a>
        </div>
      ) : null}
    </div>
  );
}
