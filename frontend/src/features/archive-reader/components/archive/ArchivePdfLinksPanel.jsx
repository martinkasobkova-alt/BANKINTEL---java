import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  BarChart3,
  ClipboardPaste,
  Crosshair,
  FileText,
  Globe,
  Link2,
  Loader2,
  Mic,
  Plus,
  Trash2,
  Video,
} from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { pdfLinkOpenLabel, resolvePdfLinkTargetKind } from "@/lib/archiveChartLink";
import ArchiveChartLinkPicker from "@/components/archive/ArchiveChartLinkPicker";

const TARGET_OPTIONS = [
  { id: "chart", label: "Graf", Icon: BarChart3 },
  { id: "video", label: "Video", Icon: Video },
  { id: "podcast", label: "Podcast", Icon: Mic },
  { id: "web", label: "Stránka", Icon: Globe },
  { id: "document", label: "Dokument", Icon: FileText },
];

function targetKindIcon(kind) {
  const opt = TARGET_OPTIONS.find((o) => o.id === kind);
  return opt?.Icon || BarChart3;
}

/**
 * Propojení míst v PDF s grafy a externím obsahem — seznam pro aktuální stránku.
 */
export default function ArchivePdfLinksPanel({
  issueId,
  page,
  isAdmin,
  draftLabel = "",
  draftAnchorText = "",
  draftSyncKey = 0,
  draftBbox = null,
  regionMarkActive = false,
  previewReady = false,
  previewLoading = false,
  previewFailed = false,
  onStartRegionMark,
  onDraftConsumed,
  onLinksChanged,
  onOpenChartLink,
  onTextDraft,
}) {
  const [links, setLinks] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [label, setLabel] = useState("");
  const [targetKind, setTargetKind] = useState("chart");
  const [externalUrl, setExternalUrl] = useState("");
  const [pendingTarget, setPendingTarget] = useState(null);
  const lastSyncKeyRef = useRef(0);

  const loadLinks = useCallback(async () => {
    if (!issueId) return;
    setLoading(true);
    try {
      const { data } = await api.get(`/magazines/issues/${encodeURIComponent(issueId)}/links`, {
        params: { page },
      });
      setLinks(Array.isArray(data?.links) ? data.links : []);
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
      setLinks([]);
    } finally {
      setLoading(false);
    }
  }, [issueId, page]);

  useEffect(() => {
    loadLinks();
  }, [loadLinks]);

  useEffect(() => {
    if (!draftSyncKey || draftSyncKey === lastSyncKeyRef.current) return;
    lastSyncKeyRef.current = draftSyncKey;
    const fromDraft = String(draftAnchorText || draftLabel || "").trim();
    if (fromDraft) setLabel(fromDraft.slice(0, 500));
  }, [draftSyncKey, draftAnchorText, draftLabel]);

  useEffect(() => {
    if (Array.isArray(draftBbox) && draftBbox.length === 4 && !label.trim() && !draftLabel) {
      setLabel(`Odkaz na str. ${page}`);
    }
  }, [draftBbox, draftLabel, label, page]);

  const applyText = useCallback(
    (raw, { notifyParent = true } = {}) => {
      const t = String(raw || "")
        .replace(/\s+/g, " ")
        .trim();
      if (t.length < 1) return false;
      setLabel(t.slice(0, 500));
      if (notifyParent && t.length >= 2) onTextDraft?.(t);
      return true;
    },
    [onTextDraft]
  );

  const pasteFromClipboard = async () => {
    try {
      const t = await navigator.clipboard.readText();
      if (!applyText(t)) {
        toast.message("Schránka je prázdná — označte text v PDF a stiskněte Ctrl+C.");
      }
    } catch {
      toast.error("Vložení ze schránky nejde — napište text ručně do pole níže.");
    }
  };

  const hasRegion = Array.isArray(draftBbox) && draftBbox.length === 4;
  const canMarkRegion = !previewFailed && !previewLoading && previewReady;

  const resolveLabel = (chart) => {
    const trimmed = label.trim();
    if (trimmed) return trimmed;
    if (draftAnchorText) return draftAnchorText.slice(0, 80);
    if (chart?.title) return String(chart.title).slice(0, 240);
    if (hasRegion) return `Odkaz na str. ${page}`;
    return "";
  };

  const resolveAnchorText = () => {
    const fromSearch = String(draftAnchorText || "").trim();
    if (fromSearch) return fromSearch;
    if (!hasRegion) {
      const fromLabel = label.trim();
      if (fromLabel) return fromLabel;
    }
    return "";
  };

  const saveLinkBody = async (body) => {
    setSaving(true);
    try {
      const { data: created } = await api.post(
        `/magazines/issues/${encodeURIComponent(issueId)}/links`,
        body
      );
      const placedOnPage = Array.isArray(created?.bbox) && created.bbox.length === 4;
      toast.success(
        placedOnPage
          ? "Propojení uloženo — text v článku je teď klikací."
          : "Propojení uloženo. Slovo se nepodařilo na stránce najít — zkuste přesnější text, nebo označte oblast myší."
      );
      setLabel("");
      setExternalUrl("");
      lastSyncKeyRef.current = 0;
      setPendingTarget(null);
      onDraftConsumed?.();
      onLinksChanged?.();
      await loadLinks();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    } finally {
      setSaving(false);
    }
  };

  const onChartPicked = async (chart) => {
    setPendingTarget(chart);
    if (!issueId || !chart) return;
    const finalLabel = resolveLabel(chart);
    if (!finalLabel) {
      toast.error("Napište text z PDF do pole níže, nebo vyberte graf s názvem.");
      return;
    }
    const anchorText = resolveAnchorText();
    await saveLinkBody({
      page,
      label: finalLabel,
      anchor_text: anchorText || undefined,
      link_kind: hasRegion ? "chart" : "text",
      bbox: hasRegion ? draftBbox : undefined,
      target_kind: "chart",
      target_title: chart.title,
      source_type: chart.source_type,
      set_id: chart.set_id,
      link_url: chart.link_url,
    });
  };

  const saveExternalLink = async () => {
    if (!issueId) return;
    const finalLabel = resolveLabel();
    if (!finalLabel) {
      toast.error("Napište text z PDF do pole níže.");
      return;
    }
    const url = externalUrl.trim();
    if (!url) {
      toast.error("Zadejte URL cíle propojení.");
      return;
    }
    if (!/^https?:\/\//i.test(url)) {
      toast.error("URL musí začínat http:// nebo https://");
      return;
    }
    const anchorText = resolveAnchorText();
    await saveLinkBody({
      page,
      label: finalLabel,
      anchor_text: anchorText || undefined,
      link_kind: hasRegion ? "chart" : "text",
      bbox: hasRegion ? draftBbox : undefined,
      target_kind: targetKind,
      link_url: url,
      target_title: finalLabel,
    });
  };

  const removeLink = async (linkId) => {
    if (!issueId || !linkId) return;
    try {
      await api.delete(`/magazines/issues/${encodeURIComponent(issueId)}/links/${encodeURIComponent(linkId)}`);
      toast.success("Propojení odstraněno.");
      onLinksChanged?.();
      await loadLinks();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e));
    }
  };

  return (
    <div className="space-y-3">
      <div className="text-sm font-semibold text-slate-900 inline-flex items-center gap-1.5">
        <Link2 className="h-4 w-4" />
        {isAdmin ? "Propojení s obsahem" : "Odkazy v článku"}
      </div>
      <p className="text-xs text-slate-600 leading-relaxed">
        {isAdmin
          ? "Text z PDF můžete opsat, vložit ze schránky, nebo převzít tlačítkem u stránky. Po uložení bude slovo klikací — otevře graf, video, podcast, stránku nebo dokument ve vyskakovacím náhledu."
          : "Klikněte na podtržená slova v textu článku — otevře se graf, video, podcast nebo externí obsah. Propojení upravuje jen administrátor."}
      </p>

      {loading ? (
        <div className="flex items-center gap-2 text-xs text-slate-500 py-2">
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
          Načítám propojení…
        </div>
      ) : links.length === 0 ? (
        <p className="text-xs text-slate-500">Na této stránce zatím nejsou žádná propojení.</p>
      ) : (
        <ul className="space-y-2">
          {links.map((lnk) => {
            const kind = resolvePdfLinkTargetKind(lnk);
            const KindIcon = targetKindIcon(kind);
            return (
              <li key={lnk.id} className="rounded-md border border-border/70 bg-white px-3 py-2 text-xs">
                <div className="font-semibold text-slate-900">{lnk.label}</div>
                {lnk.anchor_text ? <div className="text-slate-500 mt-0.5 line-clamp-2">{lnk.anchor_text}</div> : null}
                <div className="mt-1.5 flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={() => onOpenChartLink?.(lnk)}
                    className="inline-flex items-center gap-1 text-[hsl(var(--primary-deep))] font-medium hover:underline"
                  >
                    <KindIcon className="h-3.5 w-3.5" />
                    {pdfLinkOpenLabel(lnk)}
                  </button>
                  {isAdmin ? (
                    <button
                      type="button"
                      onClick={() => removeLink(lnk.id)}
                      className="inline-flex items-center gap-1 text-red-600 hover:underline ml-auto"
                    >
                      <Trash2 className="h-3 w-3" />
                      Smazat
                    </button>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {isAdmin ? (
        <div className="bankoapp-white-panels-scope rounded-md border border-dashed border-border/80 bg-slate-50/80 p-3 space-y-2 relative z-20 isolate">
          <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">Nové propojení (admin)</div>

          <div className="flex flex-wrap gap-1.5">
            {TARGET_OPTIONS.map((opt) => {
              const Icon = opt.Icon;
              const active = targetKind === opt.id;
              return (
                <button
                  key={opt.id}
                  type="button"
                  onClick={() => setTargetKind(opt.id)}
                  className={`inline-flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-[11px] font-medium ${
                    active
                      ? "border-[hsl(var(--primary)/0.4)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                      : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                  }`}
                >
                  <Icon className="h-3.5 w-3.5" />
                  {opt.label}
                </button>
              );
            })}
          </div>

          <label className="block text-[11px] font-semibold text-slate-900" htmlFor="archive-link-label">
            1. Text / popisek z PDF
          </label>
          <textarea
            id="archive-link-label"
            className="input w-full min-h-[88px] text-sm bg-white text-slate-900 caret-slate-900 resize-y relative z-20 pointer-events-auto"
            placeholder="Sem napište nebo vložte text (Ctrl+V) — funguje i bez označování v PDF"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            rows={4}
            autoComplete="off"
            spellCheck={false}
          />
          <p className="text-[10px] text-slate-500">
            Ruční psaní i vložení ze schránky vždy funguje. Text z PDF lze převzít tlačítkem u stránky.
          </p>

          <button
            type="button"
            onClick={pasteFromClipboard}
            className="h-8 px-3 text-[11px] inline-flex items-center gap-1.5 w-full justify-center rounded-md border border-border/70 bg-white text-slate-700 hover:bg-slate-50"
          >
            <ClipboardPaste className="h-3.5 w-3.5" />
            Vložit ze schránky
          </button>

          {targetKind === "chart" ? (
            <button
              type="button"
              onClick={() => setPickerOpen(true)}
              disabled={saving}
              className="btn-primary h-9 px-3 text-xs inline-flex items-center gap-1.5 w-full justify-center"
            >
              {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Plus className="h-3.5 w-3.5" />}
              2. Vybrat graf z katalogu a uložit
            </button>
          ) : (
            <div className="space-y-2">
              <label className="block text-[11px] font-semibold text-slate-900" htmlFor="archive-link-external-url">
                2. URL{" "}
                {targetKind === "video"
                  ? "videa"
                  : targetKind === "podcast"
                    ? "podcastu"
                    : targetKind === "web"
                      ? "stránky"
                      : "dokumentu"}
              </label>
              <input
                id="archive-link-external-url"
                className="input w-full text-sm bg-white"
                placeholder={
                  targetKind === "video"
                    ? "https://www.youtube.com/watch?v=… nebo MP4 odkaz"
                    : targetKind === "podcast"
                      ? "https://open.spotify.com/episode/… nebo podcasts.apple.com/…"
                      : targetKind === "document"
                        ? "https://…/dokument.pdf"
                        : "https://example.com/clanek"
                }
                value={externalUrl}
                onChange={(e) => setExternalUrl(e.target.value)}
              />
              <button
                type="button"
                onClick={() => void saveExternalLink()}
                disabled={saving}
                className="btn-primary h-9 px-3 text-xs inline-flex items-center gap-1.5 w-full justify-center"
              >
                {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Plus className="h-3.5 w-3.5" />}
                Uložit propojení
              </button>
            </div>
          )}

          {canMarkRegion && targetKind === "chart" ? (
            <div className="pt-1 space-y-2 border-t border-border/60">
              <p className="text-[11px] font-semibold text-slate-500 uppercase tracking-wide">Volitelně: oblast grafu</p>
              <button
                type="button"
                onClick={() => onStartRegionMark?.()}
                disabled={regionMarkActive}
                className={`h-9 px-3 text-xs inline-flex items-center gap-1.5 w-full justify-center rounded-md border ${
                  regionMarkActive
                    ? "border-[hsl(var(--primary)/0.45)] bg-[hsl(var(--primary-soft)/0.55)] text-[hsl(var(--primary-deep))]"
                    : "border-border/70 bg-white text-slate-700 hover:bg-slate-50"
                }`}
              >
                <Crosshair className="h-3.5 w-3.5" />
                {regionMarkActive ? "Tažením označte oblast vlevo" : "Označit oblast grafu"}
              </button>
            </div>
          ) : null}

          {pendingTarget ? <p className="text-[11px] text-slate-500">Cíl: {pendingTarget.title}</p> : null}
        </div>
      ) : null}

      <ArchiveChartLinkPicker open={pickerOpen} onClose={() => setPickerOpen(false)} onPick={onChartPicked} />
    </div>
  );
}
