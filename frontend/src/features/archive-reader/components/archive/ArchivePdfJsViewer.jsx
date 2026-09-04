import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import * as pdfjsLib from "pdfjs-dist/build/pdf";
import pdfjsWorker from "pdfjs-dist/build/pdf.worker.min.js?url";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import ArchivePdfRegionOverlay from "@/components/archive/ArchivePdfRegionOverlay";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorker;

/**
 * Cache jednotlivých STRÁNEK, ne celého čísla.
 *
 * Dřív si čtečka stáhla celý PDF soubor jedním požadavkem — u osmdesátimegového čísla to
 * znamenalo čekat na celý soubor před vykreslením první stránky a hlavně to znamenalo, že
 * kompletní číslo skončilo v prohlížeči a dalo se uložit. Archiv je přitom předplatitelský.
 * Backend umí vrátit jednu stránku (`?reader_page=N`), takže se stahuje jen to, co je vidět.
 */
const pdfPageCache = new Map();
const SMALL_PAGE_RENDER_SCALE = 2;
const LARGE_PAGE_RENDER_SCALE = 1.5;

function getCachedPdfPage(issueId, page) {
  const id = String(issueId || "").trim();
  if (!id) return Promise.reject(new Error("Chybí ID čísla."));
  const pageNum = Math.max(1, Number(page) || 1);
  const key = `${id}#${pageNum}`;
  const cached = pdfPageCache.get(key);
  if (cached) return cached;
  const promise = api
    .get(`/magazines/issues/${encodeURIComponent(id)}/file`, {
      params: { reader_page: pageNum },
      responseType: "blob",
    })
    .then(async ({ data }) => {
      const blob = data instanceof Blob ? data : new Blob([data], { type: "application/pdf" });
      const buf = await blob.arrayBuffer();
      return pdfjsLib.getDocument({ data: buf }).promise;
    })
    .catch((e) => {
      pdfPageCache.delete(key);
      throw e;
    });
  pdfPageCache.set(key, promise);
  return promise;
}

export function preloadArchivePdfDocument(issueId, page = 1) {
  return getCachedPdfPage(issueId, page).catch(() => null);
}

function selectionInsideRoot(sel, root) {
  if (!sel || sel.isCollapsed || !root) return false;
  if (sel.rangeCount < 1) return false;
  const range = sel.getRangeAt(0);
  return root.contains(range.commonAncestorContainer);
}

function normalizePdfTextContent(textContent) {
  const items = Array.isArray(textContent?.items) ? textContent.items : [];
  return items
    .map((item) => {
      const text = String(item?.str || "").replace(/\s+/g, " ").trim();
      return item?.hasEOL && text ? `${text}\n` : text;
    })
    .filter(Boolean)
    .join(" ")
    .replace(/[ \t]*\n[ \t]*/g, "\n")
    .replace(/[ \t]{2,}/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export async function getArchivePdfPageText(issueId, page) {
  // Výřez má jedinou stránku, takže se v něm sahá vždy na první.
  const pdf = await getCachedPdfPage(issueId, page);
  const pdfPage = await pdf.getPage(1);
  const textContent = await pdfPage.getTextContent();
  return normalizePdfTextContent(textContent);
}

/**
 * PDF stránka s textovou vrstvou — admin označí text myší (propojení s grafem).
 */
export default function ArchivePdfJsViewer({
  issueId,
  page,
  width,
  isAdmin = false,
  textSelectActive = true,
  onTextSelected,
  onPageTextReady,
  linksRevision = 0,
  onOpenChartLink,
  allowHorizontalScroll = true,
}) {
  const canvasRef = useRef(null);
  const textLayerRef = useRef(null);
  const wrapRef = useRef(null);
  const containerRef = useRef(null);
  const pdfDocRef = useRef(null);
  const renderTaskRef = useRef(null);
  const renderGenRef = useRef(0);
  const lastSentTextRef = useRef("");
  const selectionTimerRef = useRef(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [pageSize, setPageSize] = useState({ w: 0, h: 0 });
  const [textSpanCount, setTextSpanCount] = useState(-1);
  const [hasRendered, setHasRendered] = useState(false);

  useEffect(() => {
    lastSentTextRef.current = "";
    setHasRendered(false);
  }, [issueId]);

  // Quantize the render width to 8px steps (rounded DOWN so the page never
  // overflows its layout slot). Sub-step measurement jitter on first paint
  // (estimate vs measured vs ResizeObserver, scrollbar appear/disappear) then
  // no longer re-renders the whole PDF — fewer renders while the size settles.
  const renderWidth = useMemo(() => {
    const fw = Number(width);
    if (!Number.isFinite(fw) || fw <= 0) return 0;
    const bounded = Math.max(220, Math.min(2400, Math.floor(fw)));
    return Math.floor(bounded / 8) * 8;
  }, [width]);

  const emitSelection = useCallback(() => {
    if (!isAdmin || !textSelectActive || !onTextSelected) return;
    const root = wrapRef.current;
    const sel = window.getSelection();
    if (!selectionInsideRoot(sel, root)) return;
    const raw = String(sel?.toString() || "")
      .replace(/\s+/g, " ")
      .trim();
    if (raw.length < 2) return;
    if (raw === lastSentTextRef.current) return;
    lastSentTextRef.current = raw;
    onTextSelected(raw);
  }, [isAdmin, textSelectActive, onTextSelected]);

  const handlePointerUp = useCallback(() => {
    window.setTimeout(emitSelection, 0);
  }, [emitSelection]);

  useEffect(() => {
    if (!isAdmin || !textSelectActive) return undefined;

    const onSelectionChange = () => {
      if (selectionTimerRef.current) window.clearTimeout(selectionTimerRef.current);
      selectionTimerRef.current = window.setTimeout(emitSelection, 80);
    };

    document.addEventListener("selectionchange", onSelectionChange);
    return () => {
      document.removeEventListener("selectionchange", onSelectionChange);
      if (selectionTimerRef.current) window.clearTimeout(selectionTimerRef.current);
    };
  }, [isAdmin, textSelectActive, emitSelection]);

  const renderPage = useCallback(async () => {
    const pdf = pdfDocRef.current;
    const canvas = canvasRef.current;
    const textLayer = textLayerRef.current;
    if (!pdf || !canvas || !textLayer) return;

    // Generation guard: width/page settle across several renders on first paint
    // (estimate → measured → ResizeObserver). Without this, an older invocation
    // can finish after a newer one and overwrite the visible canvas / page size
    // with stale dimensions — that is the "flicker until it settles". Only the
    // newest invocation is allowed to touch the canvas and state.
    const myGen = (renderGenRef.current += 1);
    const isStale = () => myGen !== renderGenRef.current;

    if (renderTaskRef.current) {
      try {
        renderTaskRef.current.cancel();
      } catch {
        /* ignore */
      }
      renderTaskRef.current = null;
    }

    const pageNum = Math.max(1, Number(page) || 1);
    const pdfPage = await pdf.getPage(1);
    if (isStale()) return;
    const baseViewport = pdfPage.getViewport({ scale: 1 });
    const targetWidth = Math.max(320, Math.min(2400, renderWidth || baseViewport.width));
    const scale = targetWidth / baseViewport.width;
    const viewport = pdfPage.getViewport({ scale });
    const deviceScale = typeof window !== "undefined" ? window.devicePixelRatio || 1 : 1;
    const qualityScale = targetWidth < 900 ? SMALL_PAGE_RENDER_SCALE : LARGE_PAGE_RENDER_SCALE;
    const maxSafeScale = Math.min(2.5, 4096 / Math.max(viewport.width, viewport.height));
    const outputScale = Math.min(maxSafeScale, Math.max(deviceScale, qualityScale));

    const cssW = Math.floor(viewport.width);
    const cssH = Math.floor(viewport.height);
    const bitmapW = Math.floor(viewport.width * outputScale);
    const bitmapH = Math.floor(viewport.height * outputScale);

    // Set the new CSS display size immediately — old canvas content stretches/shrinks to fit,
    // so there is no blank frame while the new content renders in the background.
    canvas.style.width = `${cssW}px`;
    canvas.style.height = `${cssH}px`;
    // Let React own the wrapRef dimensions via state to avoid DOM/React conflicts.
    setPageSize({ w: cssW, h: cssH });

    textLayer.style.width = `${cssW}px`;
    textLayer.style.height = `${cssH}px`;
    textLayer.style.setProperty("--scale-factor", String(scale));
    wrapRef.current?.style.setProperty("--scale-factor", String(scale));

    // Render into an off-screen canvas so the visible canvas bitmap is never blank.
    const offscreen = document.createElement("canvas");
    offscreen.width = bitmapW;
    offscreen.height = bitmapH;
    const offCtx = offscreen.getContext("2d");

    const renderTask = pdfPage.render({
      canvasContext: offCtx,
      viewport,
      transform: outputScale !== 1 ? [outputScale, 0, 0, outputScale, 0, 0] : undefined,
    });
    renderTaskRef.current = renderTask;
    await renderTask.promise;
    if (isStale()) return;

    // Swap: resize + fill the visible canvas in one synchronous block — browser paints no blank frame.
    canvas.width = bitmapW;
    canvas.height = bitmapH;
    canvas.getContext("2d").drawImage(offscreen, 0, 0);
    setHasRendered(true);

    textLayer.innerHTML = "";
    const textContent = await pdfPage.getTextContent();
    if (isStale()) return;
    onPageTextReady?.(pageNum, normalizePdfTextContent(textContent));
    const layerTask = pdfjsLib.renderTextLayer({
      textContent,
      container: textLayer,
      viewport,
      textDivs: [],
    });
    if (layerTask?.promise) await layerTask.promise;
    if (isStale()) return;
    textLayer.style.setProperty("--scale-factor", String(scale));
    wrapRef.current?.style.setProperty("--scale-factor", String(scale));
    setTextSpanCount(textLayer.querySelectorAll("span").length);
  }, [page, renderWidth, onPageTextReady]);

  useEffect(() => {
    if (!issueId) {
      setLoading(false);
      setError("Chybí ID čísla.");
      return undefined;
    }

    let cancelled = false;
    setLoading(true);
    setError("");

    pdfDocRef.current = null;

    getCachedPdfPage(issueId, page)
      .then((pdf) => {
        if (cancelled) return;
        pdfDocRef.current = pdf;
        setLoading(false);
      })
      .catch((e) => {
        if (!cancelled) {
          // Konkrétní příčina místo obecné hlášky: číslo existuje, ale nemá nahraný PDF soubor
          // (backend /issues/{id}/file vrací 404). Admin ho může doplnit přes „Nahradit PDF".
          setError(
            e?.response?.status === 404
              ? "K tomuto číslu zatím není nahraný PDF soubor. Admin ho může doplnit přes „Nahradit PDF“ na seznamu čísel."
              : formatApiErrorFromAxios(e) || e?.message || "PDF se nepodařilo načíst.",
          );
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
      if (renderTaskRef.current) {
        try {
          renderTaskRef.current.cancel();
        } catch {
          /* ignore */
        }
      }
      pdfDocRef.current = null;
    };
    // Stránka je součástí klíče, protože se načítá po jedné — při přelistování se musí
    // stáhnout nový výřez, ne sáhnout do už načteného celého dokumentu.
  }, [issueId, page]);

  useEffect(() => {
    if (!pdfDocRef.current || loading || error || renderWidth < 220) return;
    let cancelled = false;
    renderPage().catch((e) => {
      if (!cancelled) setError(e?.message || "Stránku PDF se nepodařilo vykreslit.");
    });
    return () => {
      cancelled = true;
    };
  }, [page, renderWidth, loading, error, renderPage]);

  if (error) {
    return (
      <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-900">{error}</div>
    );
  }

  // Pre-allocate placeholder at expected PDF dimensions so the spinner occupies
  // the same space the PDF will occupy — eliminates any layout shift on reveal.
  const w = Number(width);
  const placeholderW = Number.isFinite(w) && w > 0 ? Math.max(220, Math.floor(w)) : 300;
  const placeholderH = Math.round(placeholderW * 1.42);
  const showOverlay = loading || !hasRendered;

  return (
    <div
      ref={containerRef}
      className={`${allowHorizontalScroll ? 'w-full' : 'w-max'} flex flex-col items-center gap-2${textSelectActive ? ' archive-pdf-text-select-mode' : ''}`}
      data-archive-pdf-js="1"
      data-text-select={textSelectActive ? "1" : "0"}
    >
      {!showOverlay && textSpanCount === 0 ? (
        <div className="w-full rounded-md border border-amber-200 bg-amber-50 px-2.5 py-2 text-[11px] text-amber-950 leading-snug">
          Tato stránka nemá prohledávatelný text v PDF. Použijte <strong>hledání nahoře</strong> nebo vpravo tlačítko
          "Vložit ze schránky" (Ctrl+C v PDF čtečce).
        </div>
      ) : null}
      <div
        className={allowHorizontalScroll ? 'w-full flex justify-center overflow-x-auto' : 'flex justify-center'}
      >
        <div
          ref={wrapRef}
          className={`relative bg-white shadow-sm leading-none shrink-0${
            textSelectActive ? " ring-2 ring-[hsl(var(--primary)/0.45)] ring-offset-1 rounded-sm" : ""
          }`}
          style={pageSize.w ? { width: pageSize.w, height: pageSize.h } : { width: placeholderW, height: placeholderH }}
          onMouseUp={textSelectActive ? handlePointerUp : undefined}
        >
          <canvas ref={canvasRef} className="block" />
          {showOverlay ? (
            <div className="absolute inset-0 flex items-center justify-center bg-white">
              <Loader2 className="h-6 w-6 animate-spin text-[hsl(var(--primary))]" />
            </div>
          ) : null}
          <div
            ref={textLayerRef}
            className="textLayer absolute left-0 top-0"
            style={{ lineHeight: 1 }}
            onMouseUp={handlePointerUp}
          />
          {/* Klikací oblasti grafů přímo nad vykresleným PDF. Bbox jsou normalizované
              0–1, takže sedí na canvas stejně jako dřív na PNG. Overlay je read-only
              (žádné kreslení); root má pointer-events-none, klikatelná jsou jen tlačítka
              hotspotů, takže výběr textu i posun stránky procházejí skrz. */}
          {!showOverlay && pageSize.w ? (
            <ArchivePdfRegionOverlay
              issueId={issueId}
              page={page}
              isAdmin={isAdmin}
              linksRevision={linksRevision}
              onOpenChartLink={onOpenChartLink}
            />
          ) : null}
        </div>
      </div>
    </div>
  );
}
