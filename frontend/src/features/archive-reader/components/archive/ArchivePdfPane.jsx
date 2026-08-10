import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import ArchivePdfRegionOverlay from "@/components/archive/ArchivePdfRegionOverlay";
import ArchivePdfJsViewer from "@/components/archive/ArchivePdfJsViewer";
import { buildIssuePdfUrl } from "@/lib/archiveReader";

const PREVIEW_WIDTH_STEP = 32;
const PREVIEW_RENDER_SCALE = 2;

function normalizePreviewWidth(width) {
  const n = Number(width);
  const bounded = Number.isFinite(n) ? Math.min(2400, Math.max(320, n)) : 960;
  return Math.ceil(bounded / PREVIEW_WIDTH_STEP) * PREVIEW_WIDTH_STEP;
}

/** Stránka časopisu — PDF.js pro čtení a PNG jen pro admin označení oblasti grafu. */
export default function ArchivePdfPane({
  issueId,
  page,
  zoom,
  effectivePdfWidth,
  isAdmin,
  regionMarkActive,
  draftBbox = null,
  onRegionDrawn,
  onCancelRegionMark,
  linksRevision = 0,
  onOpenChartLink,
  onPreviewStatusChange,
  onTextSelected,
  onPageTextReady,
  preferTextSelectMode = false,
  allowHorizontalScroll = true,
}) {
  const [previewBlobUrl, setPreviewBlobUrl] = useState("");
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewFailed, setPreviewFailed] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [imgReady, setImgReady] = useState(false);
  const [adminView, setAdminView] = useState("iframe");
  const wrapRef = useRef(null);
  const imgRef = useRef(null);
  const blobUrlRef = useRef("");

  const renderWidth = useMemo(() => {
    const base = Number(effectivePdfWidth) > 0 ? Math.floor(Number(effectivePdfWidth)) : 960;
    return normalizePreviewWidth(base * PREVIEW_RENDER_SCALE);
  }, [effectivePdfWidth]);

  const pdfDownloadUrl = useMemo(() => buildIssuePdfUrl(issueId, page, zoom), [issueId, page, zoom]);

  const textSelectWantsPdf = Boolean(isAdmin && preferTextSelectMode && !regionMarkActive);
  // Čtečka vždy vykresluje PDF — PNG náhled (i admin „Oblast") je vypnutý na žádost.
  // Tím odpadá rušivý PDF→PNG swap u stránek s uloženými grafy a previewReady už
  // nikdy není true, takže se v bočním panelu nezobrazí ani tlačítko „Označit oblast".
  const needsPreview = false;

  const revokeBlob = useCallback(() => {
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current);
      blobUrlRef.current = "";
    }
  }, []);

  useEffect(() => {
    if (!isAdmin) return;
    if (regionMarkActive) setAdminView("region");
    else if (adminView === "region") setAdminView(preferTextSelectMode ? "text" : "iframe");
  }, [regionMarkActive, isAdmin, preferTextSelectMode]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (isAdmin && preferTextSelectMode && !regionMarkActive) {
      setAdminView("text");
    }
  }, [isAdmin, preferTextSelectMode, regionMarkActive]);

  useEffect(() => {
    if (!needsPreview) {
      setPreviewLoading(false);
      setPreviewFailed(false);
      setPreviewError("");
      setImgReady(false);
      setPreviewBlobUrl("");
      revokeBlob();
      return undefined;
    }
    if (!issueId || !page) {
      setPreviewLoading(false);
      setPreviewFailed(true);
      setPreviewError("Chybí ID čísla nebo stránka.");
      return undefined;
    }

    let cancelled = false;
    setPreviewLoading(true);
    setPreviewFailed(false);
    setPreviewError("");
    setImgReady(false);
    setPreviewBlobUrl("");
    revokeBlob();

    api
      .get(`/magazines/issues/${encodeURIComponent(issueId)}/page-preview`, {
        params: { page, width: renderWidth },
        responseType: "blob",
      })
      .then(({ data }) => {
        if (cancelled) return;
        const blob = data instanceof Blob ? data : new Blob([data], { type: "image/png" });
        if (blob.size < 32) throw new Error("Server nevrátil obrázek stránky.");
        const url = URL.createObjectURL(blob);
        blobUrlRef.current = url;
        setPreviewBlobUrl(url);
      })
      .catch((e) => {
        if (cancelled) return;
        setPreviewFailed(true);
        const status = e?.response?.status;
        const detail = formatApiErrorFromAxios(e) || e?.message || "";
        setPreviewError(
          status === 404
            ? "Server ještě nemá náhled stránky (PyMuPDF) — oblast grafu myší nejde."
            : detail || "Náhled stránky se nepodařilo načíst."
        );
      })
      .finally(() => {
        if (!cancelled) setPreviewLoading(false);
      });

    return () => {
      cancelled = true;
      revokeBlob();
    };
  }, [issueId, needsPreview, page, renderWidth, revokeBlob]);

  useEffect(() => {
    onPreviewStatusChange?.({
      loading: previewLoading,
      ready: Boolean(previewBlobUrl && imgReady),
      failed: previewFailed,
      error: previewError,
    });
  }, [previewLoading, previewBlobUrl, imgReady, previewFailed, previewError, onPreviewStatusChange]);

  const syncImgReady = useCallback(() => {
    const img = imgRef.current;
    if (img && img.complete && img.naturalWidth > 0) {
      setImgReady(true);
      return true;
    }
    return false;
  }, []);

  useLayoutEffect(() => {
    if (!previewBlobUrl) return;
    syncImgReady();
  }, [previewBlobUrl, syncImgReady]);

  const markModeActive = Boolean(isAdmin && regionMarkActive && !previewFailed && previewBlobUrl);

  const showPng = Boolean(needsPreview && !previewFailed && previewBlobUrl);
  const showPdf = Boolean(
    issueId &&
      pdfDownloadUrl &&
      !showPng
  );

  return (
    <div className={`relative flex flex-col items-center flex-1 min-h-0 ${allowHorizontalScroll ? 'w-full' : 'w-max'} gap-1 py-0`}>
      {previewLoading && !showPng && !showPdf ? (
        <div className="flex min-h-[40vh] w-full flex-col items-center justify-center gap-2 text-sm text-slate-500">
          <Loader2 className="h-6 w-6 animate-spin text-[hsl(var(--primary))]" />
          Načítám stránku s klikacími odkazy…
        </div>
      ) : null}

      {textSelectWantsPdf && showPdf ? (
        <p className="mb-1 text-[11px] text-slate-600 text-center px-2">
          Označte text v PDF myší — převezme se do panelu propojení vpravo.
        </p>
      ) : null}

      {showPdf ? (
        <ArchivePdfJsViewer
          issueId={issueId}
          page={page}
          width={effectivePdfWidth}
          isAdmin={isAdmin}
          textSelectActive={textSelectWantsPdf}
          onTextSelected={onTextSelected}
          onPageTextReady={onPageTextReady}
          linksRevision={linksRevision}
          onOpenChartLink={onOpenChartLink}
          allowHorizontalScroll={allowHorizontalScroll}
        />
      ) : null}

      {showPng ? (
        <div
          ref={wrapRef}
          className={`relative inline-block max-w-full leading-none mx-auto ${
            markModeActive ? "ring-2 ring-[hsl(var(--primary))] ring-offset-2 rounded-sm" : ""
          }`}
          data-archive-page-preview="1"
          data-archive-mark-mode={markModeActive ? "1" : "0"}
        >
          <img
            ref={imgRef}
            src={previewBlobUrl}
            alt={`Strana ${page}`}
            draggable={false}
            onLoad={() => setImgReady(true)}
            onError={() => {
              setImgReady(false);
              setPreviewFailed(true);
              setPreviewError("Obrázek stránky se v prohlížeči nepodařilo zobrazit.");
            }}
            className="block max-w-full h-auto bg-white select-none pointer-events-none"
            style={{
              width: effectivePdfWidth ? `${effectivePdfWidth}px` : "100%",
              maxWidth: "100%",
            }}
          />
          <ArchivePdfRegionOverlay
            issueId={issueId}
            page={page}
            isAdmin={isAdmin}
            markMode={markModeActive}
            draftBbox={draftBbox}
            imageReady={imgReady}
            anchorRef={imgRef}
            onRegionDrawn={(bbox) => onRegionDrawn?.(bbox, page)}
            onCancelMark={onCancelRegionMark}
            linksRevision={linksRevision}
            onOpenChartLink={onOpenChartLink}
          />
        </div>
      ) : null}

    </div>
  );
}
