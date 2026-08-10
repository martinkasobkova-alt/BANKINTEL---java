import React from "react";
import { Link2, Loader2, MessageSquareText, Sparkles } from "lucide-react";
import { ArchiveCitationItem, ArchiveHitResultButton, ArchiveSearchScopeToggle } from "@/components/archive/ArchiveReaderSearchWidgets";
import ArchivePdfLinksPanel from "@/components/archive/ArchivePdfLinksPanel";
import VoiceInputButton from "@/components/common/VoiceInputButton";
import { archiveHitIssueId } from "@/lib/archiveReader";

export default function ArchiveReaderAside({
  asideTab,
  setAsideTab,
  isMobile,
  mobileReaderMode,
  onCloseMobile,
  searchScope,
  setSearchScope,
  isMagazineScope,
  aiQuery,
  setAiQuery,
  aiLoading,
  runAiAction,
  aiSearchHits,
  aiTurns,
  issueId,
  page,
  isAdmin,
  linkDraft,
  onLinkDraftFromHit,
  onLinkDraftFromText,
  onLinkDraftConsumed,
  draftBbox,
  regionMarkActive,
  previewReady = false,
  previewLoading = false,
  previewFailed = false,
  previewError = "",
  onStartRegionMark,
  onClearRegionBbox,
  onLinksChanged,
  onOpenChartLink,
  jumpToHit,
}) {
  const linksPanelTitle = isAdmin ? "Propojení s grafy" : "Grafy v článku";
  const linksTabLabel = isAdmin ? "Propojení (admin)" : "Propojení";

  return (
    <>
      {isMobile && mobileReaderMode ? (
        <div className="sticky top-0 z-10 -mx-3 -mt-3 mb-2 flex items-center justify-between border-b border-border/60 bg-white px-3 py-2">
          <div className="text-sm font-semibold text-slate-900 inline-flex items-center gap-1.5">
            {asideTab === "links" ? <Link2 className="h-4 w-4" /> : <Sparkles className="h-4 w-4" />}
            {asideTab === "links" ? linksPanelTitle : "AI nad archivem"}
          </div>
          <button
            type="button"
            onClick={onCloseMobile}
            className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border/70 bg-white px-2.5 text-xs font-medium text-slate-700"
          >
            Zavřít
          </button>
        </div>
      ) : null}
      {!isMobile || !mobileReaderMode ? (
        <div className="text-sm font-semibold text-slate-900 inline-flex items-center gap-1.5">
          {asideTab === "links" ? <Link2 className="h-4 w-4" /> : <Sparkles className="h-4 w-4" />}
          {asideTab === "links" ? linksPanelTitle : "AI nad archivem"}
        </div>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => setAsideTab("search")}
          className={`h-8 px-3 text-xs rounded-md border ${asideTab === "search" ? "chip-mint border-transparent" : "btn-ghost"}`}
        >
          AI vyhledávání
        </button>
        <button
          type="button"
          onClick={() => setAsideTab("chat")}
          className={`h-8 px-3 text-xs rounded-md border ${asideTab === "chat" ? "chip-mint border-transparent" : "btn-ghost"}`}
        >
          AI chat
        </button>
        <button
          type="button"
          onClick={() => setAsideTab("links")}
          className={`h-8 px-3 text-xs rounded-md border inline-flex items-center gap-1 ${asideTab === "links" ? "chip-mint border-transparent" : "btn-ghost"}`}
        >
          <Link2 className="h-3.5 w-3.5" />
          {linksTabLabel}
        </button>
      </div>

      {asideTab === "links" ? (
        <ArchivePdfLinksPanel
          issueId={issueId}
          page={page}
          isAdmin={isAdmin}
          draftLabel={linkDraft?.label || ""}
          draftAnchorText={linkDraft?.anchorText || ""}
          draftSyncKey={linkDraft?.ts || 0}
          draftBbox={draftBbox}
          regionMarkActive={regionMarkActive}
          previewReady={previewReady}
          previewLoading={previewLoading}
          previewFailed={previewFailed}
          previewError={previewError}
          onStartRegionMark={onStartRegionMark}
          onClearRegionBbox={onClearRegionBbox}
          onDraftConsumed={onLinkDraftConsumed}
          onLinksChanged={onLinksChanged}
          onOpenChartLink={onOpenChartLink}
          onTextDraft={onLinkDraftFromText}
        />
      ) : (
        <>
          {asideTab === "search" && aiSearchHits.length > 0 ? (
            <div className="space-y-2">
              {aiSearchHits.map((h, i) => (
                <div key={`${h.chunk_id || i}-${archiveHitIssueId(h) || "issue"}-${h.page}`} className="space-y-1">
                  <ArchiveHitResultButton hit={h} currentIssueId={issueId} onJump={jumpToHit} variant="card" />
                  {isAdmin && archiveHitIssueId(h) === String(issueId || "") ? (
                    <button
                      type="button"
                      onClick={() => onLinkDraftFromHit?.(h)}
                      className="text-[11px] font-medium text-[hsl(var(--primary-deep))] hover:underline px-1"
                    >
                      Propojit s grafem…
                    </button>
                  ) : null}
                </div>
              ))}
            </div>
          ) : null}

          {asideTab === "chat" && aiTurns.length > 0 ? (
            <div className="max-h-[52vh] space-y-4 overflow-y-auto pr-1">
              {aiTurns.map((turn) => (
                <div key={turn.id} className="space-y-2">
                  <div className="ml-auto max-w-[88%] rounded-md bg-[hsl(var(--primary))] px-3 py-1.5 text-xs font-medium text-white">
                    {turn.question}
                  </div>
                  {turn.error ? (
                    <p className="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800">{turn.error}</p>
                  ) : turn.answer ? (
                    <div className="space-y-2">
                      <div className="rounded-md border border-border/70 bg-white p-3 text-sm text-slate-800 whitespace-pre-wrap">
                        <div className="font-semibold mb-1 inline-flex items-center gap-1.5">
                          <MessageSquareText className="h-4 w-4" />
                          Odpověď AI
                        </div>
                        {turn.answer}
                      </div>
                      {turn.citations.length > 0 ? (
                        <div className="space-y-2">
                          {turn.citations.map((c) => (
                            <ArchiveCitationItem key={`${c.id}-${c.page}`} c={c} currentIssueId={issueId} onJumpHit={jumpToHit} />
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ) : (
                    <div className="flex items-center gap-2 rounded-md border border-border/70 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                      Zpracovávám…
                    </div>
                  )}
                </div>
              ))}
            </div>
          ) : null}

          <form onSubmit={runAiAction} className="space-y-2">
            <ArchiveSearchScopeToggle value={searchScope} onChange={setSearchScope} compact={isMobile} />
            <div className="relative">
            <textarea
              className="input w-full min-h-[112px] rounded-xl border-2 border-sky-200 bg-sky-50/45 px-3 py-2 pr-12 text-sm shadow-inner placeholder:text-slate-600 focus:border-[hsl(var(--primary))] focus:bg-white focus:ring-4 focus:ring-[hsl(var(--primary)/0.12)]"
              placeholder={
                asideTab === "chat"
                  ? isMagazineScope
                    ? "Zeptejte se napříč čísly tohoto časopisu…"
                    : "Na co se chcete zeptat nad číslem?"
                  : isMagazineScope
                    ? "Co hledáte napříč čísly časopisu?"
                    : "Co chcete v tomto čísle najít?"
              }
              value={aiQuery}
              onChange={(e) => setAiQuery(e.target.value)}
            />
              <VoiceInputButton value={aiQuery} onChange={setAiQuery} disabled={aiLoading} className="absolute right-2 top-2 h-8 w-8" />
            </div>
            <button type="submit" className="btn-primary h-9 px-3 text-xs inline-flex items-center gap-1.5" disabled={aiLoading}>
              {aiLoading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              {aiLoading ? "Zpracovávám…" : asideTab === "chat" ? "Zeptat se AI" : "Vyhledat AI"}
            </button>
          </form>
        </>
      )}
    </>
  );
}
