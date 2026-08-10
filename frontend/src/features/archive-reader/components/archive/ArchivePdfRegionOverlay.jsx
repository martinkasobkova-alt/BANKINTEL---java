import React, { useCallback, useEffect, useRef, useState } from "react";
import { BarChart3, FileText, Globe, Link2, Mic, Video, X } from "lucide-react";
import { toast } from "sonner";
import api from "@/lib/api";
import { pdfLinkOpenLabel, resolvePdfLinkTargetKind } from "@/lib/archiveChartLink";

function bboxStyle(bbox) {
  if (!Array.isArray(bbox) || bbox.length !== 4) return null;
  const [x0, y0, x1, y1] = bbox;
  return {
    left: `${x0 * 100}%`,
    top: `${y0 * 100}%`,
    width: `${(x1 - x0) * 100}%`,
    height: `${(y1 - y0) * 100}%`,
  };
}

const MIN_BBOX_SIZE = 0.008;

function hotspotKindIcon(kind) {
  if (kind === "video") return Video;
  if (kind === "podcast") return Mic;
  if (kind === "web") return Globe;
  if (kind === "document") return FileText;
  if (kind === "chart") return BarChart3;
  return Link2;
}

/**
 * Překryv přímo nad PNG stránkou: admin tažením označí oblast grafu (bbox 0–1),
 * čtenáři vidí klikatelné hotspoty na propojené grafy.
 */
export default function ArchivePdfRegionOverlay({
  issueId,
  page,
  isAdmin,
  markMode = false,
  draftBbox = null,
  imageReady = false,
  anchorRef,
  onRegionDrawn,
  onCancelMark,
  linksRevision = 0,
  onOpenChartLink,
}) {
  const rootRef = useRef(null);
  const [links, setLinks] = useState([]);
  const [drag, setDrag] = useState(null);

  useEffect(() => {
    if (!issueId || !page) return;
    let cancelled = false;
    api
      .get(`/magazines/issues/${encodeURIComponent(issueId)}/links`, { params: { page } })
      .then(({ data }) => {
        if (!cancelled) setLinks(Array.isArray(data?.links) ? data.links : []);
      })
      .catch(() => {
        if (!cancelled) setLinks([]);
      });
    return () => {
      cancelled = true;
    };
  }, [issueId, page, linksRevision]);

  const hotspotLinks = links.filter((l) => Array.isArray(l.bbox) && l.bbox.length === 4);
  const pendingHotspots = links.filter((l) => !Array.isArray(l.bbox) || l.bbox.length !== 4);
  const hasDraft = Array.isArray(draftBbox) && draftBbox.length === 4;

  const measureTarget = useCallback(() => {
    return anchorRef?.current || rootRef.current;
  }, [anchorRef]);

  const pointerToNorm = useCallback(
    (clientX, clientY) => {
      const el = measureTarget();
      if (!el) return null;
      const rect = el.getBoundingClientRect();
      if (!rect.width || !rect.height) return null;
      const x = (clientX - rect.left) / rect.width;
      const y = (clientY - rect.top) / rect.height;
      return {
        x: Math.min(1, Math.max(0, x)),
        y: Math.min(1, Math.max(0, y)),
        px: clientX - rect.left,
        py: clientY - rect.top,
      };
    },
    [measureTarget]
  );

  const finishDrag = useCallback(
    (start, end) => {
      if (!start || !end) return;
      const x0 = Math.min(start.x, end.x);
      const y0 = Math.min(start.y, end.y);
      const x1 = Math.max(start.x, end.x);
      const y1 = Math.max(start.y, end.y);
      if (x1 - x0 < MIN_BBOX_SIZE || y1 - y0 < MIN_BBOX_SIZE) {
        toast.message("Označte větší oblast — tažením myši přes celý graf nebo text.");
        setDrag(null);
        return;
      }
      onRegionDrawn?.([x0, y0, x1, y1]);
      setDrag(null);
    },
    [onRegionDrawn]
  );

  useEffect(() => {
    if (!markMode) setDrag(null);
  }, [markMode]);

  if (!markMode && hotspotLinks.length === 0 && !hasDraft) return null;

  return (
    <div
      ref={rootRef}
      className={`absolute inset-0 z-[12] ${markMode ? "cursor-crosshair touch-none select-none" : "pointer-events-none"}`}
      style={markMode ? { touchAction: "none" } : undefined}
      onPointerDown={
        markMode
          ? (e) => {
              if (e.button !== 0) return;
              if (!imageReady) {
                toast.message("Počkejte na načtení náhledu stránky…");
                return;
              }
              e.preventDefault();
              e.stopPropagation();
              const pt = pointerToNorm(e.clientX, e.clientY);
              if (!pt) return;
              setDrag({ start: pt, current: pt, px0: pt.px, py0: pt.py, px1: pt.px, py1: pt.py });
              e.currentTarget.setPointerCapture(e.pointerId);
            }
          : undefined
      }
      onPointerMove={
        markMode
          ? (e) => {
              if (!drag) return;
              const pt = pointerToNorm(e.clientX, e.clientY);
              if (!pt) return;
              setDrag((d) =>
                d
                  ? {
                      ...d,
                      current: pt,
                      px1: pt.px,
                      py1: pt.py,
                    }
                  : null
              );
            }
          : undefined
      }
      onPointerUp={
        markMode
          ? (e) => {
              if (!drag) return;
              e.preventDefault();
              const pt = pointerToNorm(e.clientX, e.clientY);
              if (pt) finishDrag(drag.start, pt);
              try {
                e.currentTarget.releasePointerCapture(e.pointerId);
              } catch {
                /* ignore */
              }
            }
          : undefined
      }
      onPointerCancel={
        markMode
          ? () => {
              setDrag(null);
            }
          : undefined
      }
    >
      {markMode ? (
        <>
          <div className="pointer-events-none absolute inset-0 bg-[hsl(var(--primary-soft)/0.12)]" />
          <div className="pointer-events-none absolute left-2 top-2 z-10 flex max-w-[min(100%,18rem)] items-start gap-2 rounded-md border border-[hsl(var(--primary)/0.35)] bg-white/95 px-2.5 py-2 text-[11px] text-slate-700 shadow-sm">
            <span className="leading-snug">
              {imageReady
                ? "Tažením myši označte celý graf nebo text. Poté v panelu vpravo vyberte řadu z katalogu."
                : "Načítám náhled stránky…"}
            </span>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setDrag(null);
                onCancelMark?.();
              }}
              className="pointer-events-auto inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-border/70 text-slate-600 hover:bg-slate-50"
              title="Zrušit označování"
              aria-label="Zrušit označování"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </>
      ) : null}

      {markMode && drag ? (
        <div
          className="pointer-events-none absolute archive-pdf-region-draft"
          style={{
            left: Math.min(drag.px0, drag.px1),
            top: Math.min(drag.py0, drag.py1),
            width: Math.abs(drag.px1 - drag.px0),
            height: Math.abs(drag.py1 - drag.py0),
          }}
        />
      ) : null}

      {hasDraft ? (
        <div
          className="pointer-events-none absolute archive-pdf-region-pending"
          style={bboxStyle(draftBbox)}
          title="Nově označená oblast — uložte propojení v panelu vpravo"
        >
          <span
            className="pointer-events-none absolute left-1.5 top-1.5 z-10 rounded-md border border-emerald-600/30 bg-emerald-50/95 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-900 shadow-sm"
          >
            Nová oblast
          </span>
        </div>
      ) : null}

      {!markMode && pendingHotspots.length > 0 && isAdmin ? (
        <div className="pointer-events-none absolute left-2 bottom-2 z-20 max-w-[min(100%,20rem)] rounded-md border border-amber-300/80 bg-amber-50/95 px-2.5 py-2 text-[10px] text-amber-950 shadow-sm">
          {pendingHotspots.length === 1
            ? `„${pendingHotspots[0].label}“ zatím nemá klikací oblast — obnovte stránku, nebo použijte „Označit oblast grafu“ tažením přes slovo.`
            : `${pendingHotspots.length} propojení nemají klikací oblast — obnovte stránku, nebo označte slovo tažením myši.`}
        </div>
      ) : null}

      {!markMode
        ? hotspotLinks.map((lnk) => {
            const style = bboxStyle(lnk.bbox);
            if (!style) return null;
            const targetKind = resolvePdfLinkTargetKind(lnk);
            const isTextLink = String(lnk.link_kind || "text") === "text" || !lnk.link_kind;
            const KindIcon = hotspotKindIcon(targetKind);
            const openLabel = pdfLinkOpenLabel(lnk);
            return (
              <button
                key={lnk.id}
                type="button"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onOpenChartLink?.(lnk);
                }}
                className={`group absolute pointer-events-auto focus:outline-none focus-visible:ring-2 focus-visible:ring-[hsl(var(--primary)/0.45)] focus-visible:ring-offset-1 cursor-pointer ${
                  isTextLink ? "archive-pdf-text-hotspot" : "archive-pdf-chart-hotspot"
                }`}
                style={style}
                title={openLabel}
                aria-label={`${openLabel}: ${lnk.label}`}
              >
                {isTextLink ? (
                  <span className="archive-pdf-text-hotspot-tip pointer-events-none absolute left-1/2 bottom-full z-10 mb-1 flex max-w-[min(14rem,90vw)] items-center gap-1 whitespace-nowrap rounded-full border border-[hsl(var(--primary)/0.2)] bg-white/95 px-2 py-0.5 text-[10px] font-medium text-[hsl(var(--primary-deep))] shadow-sm">
                    <KindIcon className="h-3 w-3 shrink-0 opacity-80" aria-hidden />
                    <span className="truncate">{lnk.label}</span>
                  </span>
                ) : (
                  <span className="archive-pdf-region-hotspot-tip pointer-events-none absolute left-1.5 top-1.5 z-10 flex max-w-[calc(100%-0.75rem)] items-center gap-1 truncate rounded-md border border-[hsl(var(--primary)/0.25)] bg-white/95 px-1.5 py-0.5 text-[10px] font-semibold text-[hsl(var(--primary-deep))] shadow-sm">
                    <KindIcon className="h-3 w-3 shrink-0 opacity-80" aria-hidden />
                    <span className="truncate">{lnk.label}</span>
                  </span>
                )}
              </button>
            );
          })
        : null}
    </div>
  );
}
