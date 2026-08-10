import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { BarChart3, ExternalLink, FileText, Globe, Loader2, Mic, Video, X } from "lucide-react";
import { Link } from "react-router-dom";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import SourcePreview from "@/components/sources/SourcePreview";
import AradView from "@/components/widgets/AradView";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import { LoadingSpinner } from "@/components/ui/loading";
import { podcastEmbedUrl, videoEmbedUrl } from "@/lib/articleBodyFormat";
import { resolveDirectAudioUrl } from "@/lib/podcastAudio";
import { usePodcastPlayerOptional } from "@/contexts/PodcastPlayerContext";
import { useTranslation } from "react-i18next";
import { pdfLinkToPreviewContext } from "@/lib/archiveChartLink";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import {
  extractCountryCodesFromFilters,
  fetchCatalogLivePreview,
  normalizeSelectedIndicators,
  resolveInitialGeoFromRow,
} from "@/lib/catalogLivePreview";
import { buildAradDataFromCatalogPreview } from "@/lib/mapCatalogPreviewToArad";
import {
  buildPreviewPayloadFromStructuredError,
  buildUnknownPreviewShapeMessage,
  previewShapeDebug,
} from "@/lib/previewNormalizer";
import { geoDisplayLabel } from "@/lib/macroGeoLabels";
import { catalogPickerLabel } from "@/lib/catalogChartPickerSearch";

function PreviewFiltersPanel({
  previewDef,
  previewRow,
  previewData,
  previewSourceType,
  previewCountryCode,
  previewLoading,
  fetchPreview,
}) {
  return (
    <SourcePreview
      liveCatalogPreview
      preview={{
        ...previewData,
        source: {
          name: previewRow?.name || previewRow?.title,
          source_type: previewDef?.sourceType,
        },
      }}
      catalogCountryCode={previewCountryCode}
      catalogCountryLabel={geoDisplayLabel(previewRow)}
      loading={previewLoading}
      compact={false}
      onIndicatorChange={(indicatorId) => {
        const geo =
          extractCountryCodesFromFilters(previewData?.metadata?.filters_applied).length > 0
            ? extractCountryCodesFromFilters(previewData?.metadata?.filters_applied)
            : extractCountryCodesFromFilters(previewData?.requested_filters);
        void fetchPreview(previewDef, previewRow, indicatorId, [indicatorId], geo);
      }}
      onGeoSelectionChange={
        previewSourceType === "imf"
          ? undefined
          : (geoIds) => {
              const many = normalizeSelectedIndicators(previewData?.selected_indicators);
              const one = String(many[0] || previewData?.selected_indicator || "").trim();
              void fetchPreview(previewDef, previewRow, one, many, geoIds);
            }
      }
      onDimensionFiltersApply={
        previewSourceType === "imf"
          ? undefined
          : (dimensionFilters) => {
              const many = normalizeSelectedIndicators(previewData?.selected_indicators);
              const one = String(many[0] || previewData?.selected_indicator || "").trim();
              const geo = extractCountryCodesFromFilters(dimensionFilters);
              void fetchPreview(previewDef, previewRow, one, many, geo, dimensionFilters);
            }
      }
    />
  );
}

/**
 * Modal s živým grafem v čtečce — stejné ovládání jako zvětšený graf na dashboardu, menší rozměr.
 */
export default function ArchiveInlineChartPanel({ link, onClose }) {
  const { t } = useTranslation();
  const podcastPlayer = usePodcastPlayerOptional();
  const ctx = useMemo(() => pdfLinkToPreviewContext(link), [link]);
  const previewSeqRef = useRef(0);
  const dashboardSeqRef = useRef(0);
  const previewDataRef = useRef(null);

  const [previewData, setPreviewData] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [dashboardWidget, setDashboardWidget] = useState(null);
  const [dashboardLoading, setDashboardLoading] = useState(false);
  const [dashboardError, setDashboardError] = useState("");

  const fetchPreview = useCallback(
    async (def, row, indicatorId, indicatorIds = [], geoValues = [], dimensionFilters = null) => {
      const seq = ++previewSeqRef.current;
      setPreviewLoading(true);
      setPreviewError("");
      try {
        const initialGeo =
          Array.isArray(geoValues) && geoValues.length ? geoValues : resolveInitialGeoFromRow(row);
        const normalized = await fetchCatalogLivePreview({
          def,
          row,
          previewData: previewDataRef.current,
          dimensionFilters,
          geoValues: initialGeo,
          indicatorId,
          indicatorIds,
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
    },
    []
  );

  useEffect(() => {
    previewSeqRef.current += 1;
    setPreviewData(null);
    previewDataRef.current = null;
    setPreviewError("");
    if (!ctx || ctx.kind !== "catalog") return undefined;
    if (!isCatalogRowPreviewEligible(ctx.def, ctx.row)) {
      setPreviewError("Tuto řadu nelze zobrazit jako živý graf v čtečce.");
      return undefined;
    }
    void fetchPreview(ctx.def, ctx.row, undefined, [], resolveInitialGeoFromRow(ctx.row));
    return () => {
      previewSeqRef.current += 1;
    };
  }, [ctx, fetchPreview]);

  useEffect(() => {
    dashboardSeqRef.current += 1;
    setDashboardWidget(null);
    setDashboardError("");
    if (!ctx || ctx.kind !== "dashboard") return undefined;
    const widgetId = String(ctx.widgetId || "").trim();
    if (!widgetId) {
      setDashboardError("Chybí ID widgetu z dashboardu.");
      return undefined;
    }
    const seq = ++dashboardSeqRef.current;
    setDashboardLoading(true);
    api
      .post("/me/dashboard/render-widget", { id: widgetId })
      .then(({ data: resolved }) => {
        if (seq !== dashboardSeqRef.current) return;
        const w = {
          id: widgetId,
          ...resolved,
          engine_type: resolved?.type,
          title: resolved?.title || ctx.title,
          width: resolved?.width || "full",
          _loading: false,
        };
        setDashboardWidget(w);
        if (resolved?.data?.error) {
          setDashboardError(String(resolved.data.error));
        }
      })
      .catch((e) => {
        if (seq !== dashboardSeqRef.current) return;
        const msg = formatApiErrorFromAxios(e) || "Widget se nepodařilo načíst.";
        setDashboardError(msg);
        setDashboardWidget({
          id: widgetId,
          title: ctx.title,
          type: "markdown",
          width: "full",
          config: {},
          data: { error: msg },
          _loading: false,
        });
      })
      .finally(() => {
        if (seq === dashboardSeqRef.current) setDashboardLoading(false);
      });
    return () => {
      dashboardSeqRef.current += 1;
    };
  }, [ctx]);

  useEffect(() => {
    if (typeof document === "undefined") return undefined;
    const onKey = (e) => {
      if (e.key === "Escape") onClose?.();
    };
    document.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [onClose]);

  previewDataRef.current = previewData;

  const previewDef = ctx?.kind === "catalog" ? ctx.def : null;
  const previewRow = ctx?.kind === "catalog" ? ctx.row : null;
  const previewSourceType = previewDef?.sourceType;
  const previewNeedsFilters = previewData?.status === "needs_filters";
  const previewRowCount = Array.isArray(previewData?.rows) ? previewData.rows.length : 0;
  const previewCountryCode = useMemo(() => {
    const fromRow = resolveInitialGeoFromRow(previewRow || {});
    return fromRow[0] || "";
  }, [previewRow]);

  const aradPreviewData = useMemo(
    () =>
      previewData && !previewNeedsFilters
        ? buildAradDataFromCatalogPreview(
            {
              ...previewData,
              source: { name: previewRow?.name || previewRow?.title },
            },
            previewRow?.name || previewRow?.title
          )
        : null,
    [previewData, previewNeedsFilters, previewRow]
  );

  const showAradChart = Boolean(!previewNeedsFilters && aradPreviewData?.rows?.length >= 2);
  const title =
    ctx?.title || String(link?.target_title || link?.label || "Obsah").trim() || "Obsah";
  const catalogLabel =
    ctx?.kind === "catalog"
      ? previewDef?.label || catalogPickerLabel(previewRow?.source_type)
      : ctx?.kind === "dashboard"
        ? "dashboard"
        : ctx?.kind === "video"
          ? "Video"
          : ctx?.kind === "podcast"
            ? "Podcast"
            : ctx?.kind === "web"
              ? "Webová stránka"
              : ctx?.kind === "document"
                ? "Dokument"
                : "";
  const HeaderIcon =
    ctx?.kind === "video"
      ? Video
      : ctx?.kind === "podcast"
        ? Mic
        : ctx?.kind === "web"
          ? Globe
          : ctx?.kind === "document"
            ? FileText
            : BarChart3;

  const fallbackUrl = String(link?.link_url || "").trim();
  const fallbackInternal = fallbackUrl.startsWith("/");
  const linkId = String(link?.id || link?.set_id || "chart").trim();

  const previewFiltersPanel =
    previewNeedsFilters && previewData ? (
      <PreviewFiltersPanel
        previewDef={previewDef}
        previewRow={previewRow}
        previewData={previewData}
        previewSourceType={previewSourceType}
        previewCountryCode={previewCountryCode}
        previewLoading={previewLoading}
        fetchPreview={fetchPreview}
      />
    ) : null;

  const chartBody = (() => {
    if (ctx?.kind === "video") {
      const embed = videoEmbedUrl(ctx.url);
      return (
        <div className="flex h-full min-h-0 flex-col items-center justify-center bg-slate-950 p-3 sm:p-4">
          {embed && /\.(mp4|webm|ogg)(\?|$)/i.test(embed) ? (
            <video controls className="max-h-full w-full rounded-lg bg-black" src={embed} />
          ) : embed ? (
            <iframe
              title={ctx.title}
              src={embed}
              className="h-full w-full min-h-[min(52vh,480px)] rounded-lg border border-white/10 bg-black"
              allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
              allowFullScreen
            />
          ) : (
            <p className="text-sm text-slate-300 px-4 text-center">
              Video nelze vložit — použijte odkaz na YouTube, Vimeo nebo MP4 soubor.
            </p>
          )}
        </div>
      );
    }
    if (ctx?.kind === "podcast") {
      const embed = podcastEmbedUrl(ctx.url);
      const directAudio = resolveDirectAudioUrl(ctx.url);
      return (
        <div className="flex h-full min-h-0 flex-col items-center justify-center bg-slate-50 p-4 sm:p-6">
          {embed ? (
            <iframe
              title={ctx.title}
              src={embed}
              className="w-full max-w-xl rounded-xl border border-border/60 bg-white shadow-sm"
              style={{ minHeight: "min(52vh, 352px)" }}
              allow="autoplay *; encrypted-media *; fullscreen *; clipboard-write"
              sandbox="allow-forms allow-popups allow-same-origin allow-scripts allow-storage-access-by-user-activation"
            />
          ) : (
            <p className="text-sm text-slate-600 px-4 text-center">
              Podcast nelze vložit — použijte odkaz na Spotify (epizodu nebo show) nebo Apple Podcasts.
            </p>
          )}
          <div className="mt-4 flex shrink-0 flex-wrap items-center justify-center gap-3">
            {directAudio && podcastPlayer ? (
              <button
                type="button"
                onClick={() =>
                  void podcastPlayer.playEpisode({
                    id: ctx.url,
                    title: ctx.title,
                    audioUrl: directAudio,
                    pageUrl: ctx.url,
                  })
                }
                className="inline-flex items-center gap-1.5 rounded-full border border-[hsl(var(--primary)/0.35)] bg-[hsl(var(--primary-soft)/0.35)] px-3 py-1.5 text-xs font-semibold text-[hsl(var(--primary-deep))] hover:bg-[hsl(var(--primary-soft))]"
              >
                <Mic className="h-3.5 w-3.5" />
                {t("podcast.playInBackground")}
              </button>
            ) : null}
            <a
              href={ctx.url}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-xs font-medium text-[hsl(var(--primary-deep))] hover:underline"
            >
              <ExternalLink className="h-3.5 w-3.5" />
              Otevřít v Spotify / Apple Podcasts
            </a>
          </div>
        </div>
      );
    }
    if (ctx?.kind === "web") {
      return (
        <div className="flex h-full min-h-0 flex-col">
          <iframe
            title={ctx.title}
            src={ctx.url}
            className="min-h-[min(52vh,520px)] w-full flex-1 border-0 bg-white"
            sandbox="allow-scripts allow-same-origin allow-popups allow-forms"
          />
          <div className="flex shrink-0 items-center justify-between gap-2 border-t border-border/60 bg-slate-50 px-3 py-2 text-[11px] text-slate-600">
            <span className="line-clamp-2">Některé stránky blokují vložení do čtečky.</span>
            <a
              href={ctx.url}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex shrink-0 items-center gap-1 font-medium text-[hsl(var(--primary-deep))] hover:underline"
            >
              <ExternalLink className="h-3.5 w-3.5" />
              Nové okno
            </a>
          </div>
        </div>
      );
    }
    if (ctx?.kind === "document") {
      return (
        <div className="flex h-full min-h-0 flex-col p-2 sm:p-3">
          <iframe
            title={ctx.title}
            src={ctx.url}
            className="min-h-[min(52vh,520px)] w-full flex-1 rounded-lg border border-border/60 bg-white"
          />
          <div className="shrink-0 pt-2 text-center">
            <a
              href={ctx.url}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-xs font-medium text-[hsl(var(--primary-deep))] hover:underline"
            >
              <ExternalLink className="h-3.5 w-3.5" />
              Otevřít dokument v novém okně
            </a>
          </div>
        </div>
      );
    }
    if (ctx?.kind === "dashboard") {
      if (dashboardLoading && !dashboardWidget) {
        return (
          <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-500">
            <LoadingSpinner className="h-5 w-5" />
            Načítám widget z dashboardu…
          </div>
        );
      }
      if (dashboardWidget) {
        return (
          <div className="h-full min-h-0 overflow-auto p-2 sm:p-3">
            <WidgetRenderer
              w={dashboardWidget}
              dashboardSharePageId={ctx.pageId || undefined}
              aradMultiSeriesHelpContext="public_site"
            />
          </div>
        );
      }
      return (
        <div className="py-12 text-center text-sm text-slate-600 space-y-2 px-4">
          <p>{dashboardError || "Widget se nepodařilo načíst."}</p>
        </div>
      );
    }
    if (ctx?.kind !== "catalog") {
      return (
        <div className="space-y-3 py-10 text-center text-sm text-slate-600 px-4">
          <p>Tento typ propojení zatím neumíme vložit přímo do čtečky.</p>
          {fallbackUrl ? (
            fallbackInternal ? (
              <Link
                to={fallbackUrl}
                className="inline-flex items-center gap-1 text-[hsl(var(--primary-deep))] font-medium hover:underline"
              >
                <ExternalLink className="h-3.5 w-3.5" />
                Otevřít v aplikaci
              </Link>
            ) : (
              <a
                href={fallbackUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-[hsl(var(--primary-deep))] font-medium hover:underline"
              >
                <ExternalLink className="h-3.5 w-3.5" />
                Otevřít odkaz
              </a>
            )
          ) : null}
        </div>
      );
    }
    if (previewLoading && previewRowCount === 0 && !previewNeedsFilters) {
      return (
        <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-500">
          <LoadingSpinner className="h-5 w-5" />
          Načítám data z API…
        </div>
      );
    }
    if (previewFiltersPanel) return previewFiltersPanel;
    if (showAradChart && aradPreviewData) {
      return (
        <AradView
          userTitle={previewRow?.name || previewRow?.title}
          data={aradPreviewData}
          widget={{
            id: `archive-chart-${linkId}`,
            type: "external_catalog_chart",
            width: "full",
            config: {},
          }}
          controlsInOptionsPanel={false}
          unlockChartPeriod
        />
      );
    }
    if (previewRowCount > 0 && previewData) {
      return (
        <PreviewFiltersPanel
          previewDef={previewDef}
          previewRow={previewRow}
          previewData={previewData}
          previewSourceType={previewSourceType}
          previewCountryCode={previewCountryCode}
          previewLoading={previewLoading}
          fetchPreview={fetchPreview}
        />
      );
    }
    return (
      <div className="py-12 text-center text-sm text-slate-600 space-y-2 px-4">
        {previewLoading ? <Loader2 className="h-5 w-5 animate-spin mx-auto text-slate-400" /> : null}
        <p>{String(previewData?.message || previewError || "Graf se nepodařilo načíst.").trim()}</p>
      </div>
    );
  })();

  if (typeof document === "undefined") return null;

  return createPortal(
    <>
      <button
        type="button"
        className="fixed inset-0 z-[380] cursor-default border-0 bg-slate-900/25 p-0 backdrop-blur-[2px]"
        aria-label="Zavřít náhled"
        onClick={onClose}
      />
      <div className="fixed inset-0 z-[390] pointer-events-none flex items-center justify-center p-2 sm:p-3 md:p-4">
        <div
          role="dialog"
          aria-modal="true"
          aria-label={title}
          className="pointer-events-auto flex min-h-[280px] w-[min(1180px,calc(100vw-1rem))] max-w-full flex-col overflow-hidden rounded-2xl border border-border/70 bg-white shadow-2xl h-[min(68dvh,720px)] max-h-[min(calc(100dvh-5rem),780px)]"
        >
          <div className="flex shrink-0 items-start justify-between gap-3 border-b border-border/60 bg-[hsl(var(--primary-soft)/0.28)] px-3 py-2 sm:px-4">
            <div className="min-w-0">
              <div className="text-sm font-semibold text-slate-900 inline-flex items-center gap-1.5">
                <HeaderIcon className="h-4 w-4 shrink-0 text-[hsl(var(--primary-deep))]" />
                <span className="line-clamp-2">{title}</span>
              </div>
              {catalogLabel ? (
                <p className="text-[11px] text-slate-600 mt-0.5 truncate">
                  {catalogLabel}
                  {ctx?.kind === "catalog" ? " · živá data" : ""}
                </p>
              ) : null}
            </div>
            <button
              type="button"
              onClick={onClose}
              className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border/70 bg-white text-slate-600 hover:bg-slate-50"
              title="Zavřít"
              aria-label="Zavřít náhled"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-hidden">{chartBody}</div>
        </div>
      </div>
    </>,
    document.body
  );
}
