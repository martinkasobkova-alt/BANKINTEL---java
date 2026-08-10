import React from "react";
import {
  ARCHIVE_SEARCH_SCOPE_ISSUE,
  ARCHIVE_SEARCH_SCOPE_MAGAZINE,
  archiveHitIsOtherIssue,
  formatArchiveHitHeadline,
} from "@/lib/archiveReader";

export { ARCHIVE_SEARCH_SCOPE_ISSUE, ARCHIVE_SEARCH_SCOPE_MAGAZINE };

export function ArchiveSearchScopeToggle({ value, onChange, compact = false }) {
  const base = compact ? "h-7 px-2 text-[10px]" : "h-8 px-2.5 text-xs";
  const active = "bg-[hsl(var(--primary))] text-white shadow-sm";
  const idle = "text-slate-600 hover:bg-slate-100";
  return (
    <div className="inline-flex rounded-md border border-border/70 bg-white p-0.5" role="group" aria-label="Rozsah hledání">
      <button
        type="button"
        onClick={() => onChange(ARCHIVE_SEARCH_SCOPE_ISSUE)}
        className={`${base} rounded-md font-medium transition ${value === ARCHIVE_SEARCH_SCOPE_ISSUE ? active : idle}`}
      >
        V tomto čísle
      </button>
      <button
        type="button"
        onClick={() => onChange(ARCHIVE_SEARCH_SCOPE_MAGAZINE)}
        className={`${base} rounded-md font-medium transition ${value === ARCHIVE_SEARCH_SCOPE_MAGAZINE ? active : idle}`}
      >
        Napříč čísly
      </button>
    </div>
  );
}

export function ArchiveHitResultButton({ hit, currentIssueId, onJump, onAfterJump, variant = "inline" }) {
  const headline = formatArchiveHitHeadline(hit, currentIssueId);
  const otherIssue = archiveHitIsOtherIssue(hit, currentIssueId);
  const shell =
    variant === "card"
      ? "w-full text-left rounded-md border border-border/70 bg-white px-3 py-2 text-xs hover:bg-slate-50"
      : "w-full text-left rounded-md px-2 py-1.5 text-xs hover:bg-slate-50";
  return (
    <button
      type="button"
      onClick={() => {
        onJump(hit);
        onAfterJump?.();
      }}
      className={shell}
    >
      {variant === "card" ? (
        <>
          <div className={`font-semibold ${otherIssue ? "text-[hsl(var(--primary-deep))]" : "text-slate-900"}`}>{headline}</div>
          <div className="text-slate-600 mt-1 line-clamp-2">{hit.snippet}</div>
        </>
      ) : (
        <>
          <span className={`font-semibold ${otherIssue ? "text-[hsl(var(--primary-deep))]" : "text-slate-900"}`}>{headline}</span>
          <span className="text-slate-600"> · {hit.snippet}</span>
        </>
      )}
    </button>
  );
}

export function ArchiveCitationItem({ c, currentIssueId, onJumpHit }) {
  const hit = {
    page: c.page,
    snippet: c.snippet,
    issue: {
      id: c.issue_id,
      issue_label: c.issue_label,
      magazine_title: c.magazine_title,
    },
  };
  return <ArchiveHitResultButton hit={hit} currentIssueId={currentIssueId} onJump={onJumpHit} variant="card" />;
}
