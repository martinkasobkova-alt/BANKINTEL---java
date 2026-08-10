import React, { useEffect, useState } from "react";
import { BarChart3, FileText, Globe, Video } from "lucide-react";
import api from "@/lib/api";
import { resolvePdfLinkTargetKind } from "@/lib/archiveChartLink";

function linkIcon(kind) {
  if (kind === "video") return Video;
  if (kind === "web") return Globe;
  if (kind === "document") return FileText;
  return BarChart3;
}

/** Kompaktní lišta propojení nad PDF pro aktuální stránku. */
export default function ArchivePdfPageLinksBar({ issueId, page, linksRevision = 0, onOpenChartLink }) {
  const [links, setLinks] = useState([]);

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

  if (!links.length) return null;

  return (
    <div className="flex flex-wrap items-center gap-1.5 rounded-md border border-[hsl(var(--primary)/0.25)] bg-[hsl(var(--primary-soft)/0.35)] px-2 py-1.5 mb-2">
      <span className="text-[10px] font-semibold uppercase tracking-wide text-[hsl(var(--primary-deep))] shrink-0">
        Odkazy:
      </span>
      {links.map((lnk) => {
        const kind = resolvePdfLinkTargetKind(lnk);
        const Icon = linkIcon(kind);
        return (
          <button
            key={lnk.id}
            type="button"
            onClick={() => onOpenChartLink?.(lnk)}
            className="inline-flex items-center gap-1 rounded-md border border-white/80 bg-white/90 px-2 py-0.5 text-[11px] font-medium text-[hsl(var(--primary-deep))] hover:bg-white"
            title={lnk.target_title || lnk.label}
          >
            <Icon className="h-3 w-3 shrink-0" />
            <span className="max-w-[10rem] truncate">{lnk.label}</span>
          </button>
        );
      })}
    </div>
  );
}
