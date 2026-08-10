import React from "react";
import { Link } from "react-router-dom";
import { ExternalLink } from "lucide-react";
import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import WidgetRenderer from "@/components/widgets/WidgetRenderer";
import { eurostatAiRowNeedsOpenInCatalog } from "@/lib/eurostatQueryableSlice";
import { eurostatDeepAiMessageLooksInternalDebug, catalogRowFromDeepCandidate } from "@/lib/catalogGlobalSearchHelpers";
import { GLOBAL_BROWSE_FALLBACK_ROUTE } from "@/lib/catalogBrowseStatusRegistry";
import { fetchSeriesConceptExplanation, seriesConceptExplainSourceNote } from "@/lib/catalogSeriesConceptExplain";
import {
  EUROSTAT_DEEP_AI_HINT_HAS_REF_CZ,
  EUROSTAT_DEEP_AI_OPEN_CATALOG_DIMS_CZ,
  EUROSTAT_DEEP_AI_PREVIEW_UNAVAILABLE_CZ,
  FALLBACK_LINK_LABELS_CZ,
  resolveDeepSearchCatalogDef,
} from "./globalCatalogSearchConstants";

export function InlineLoadingDots({ text = "Načítám" }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-[10px] uppercase tracking-wide text-sky-700 ml-2">
      <span className="h-1.5 w-1.5 rounded-full bg-sky-600 animate-bounce [animation-delay:-0.3s]" />
      <span className="h-1.5 w-1.5 rounded-full bg-sky-600 animate-bounce [animation-delay:-0.15s]" />
      <span className="h-1.5 w-1.5 rounded-full bg-sky-600 animate-bounce" />
      <span>{text}</span>
    </span>
  );
}

export function EurostatDeepAiTechnicalDetails({ raw }) {
  const t = String(raw ?? "").trim();
  if (!t) return null;
  return (
    <details className="mt-1.5 text-amber-950 canvas-dark:text-amber-50/90">
      <summary className="cursor-pointer text-[10px] font-semibold text-amber-900/90 select-none">
        Technické detaily
      </summary>
      <pre className="mt-1.5 whitespace-pre-wrap text-[10px] font-mono leading-snug text-amber-950 canvas-dark:text-amber-100/95 border border-amber-300/70 canvas-dark:border-amber-600/50 rounded-lg bg-card/90 p-2 max-h-48 overflow-y-auto">
        {t}
      </pre>
    </details>
  );
}

export function CatalogBrowseFallbackLinks({ def }) {
  if (!def?.id) return null;
  const to = GLOBAL_BROWSE_FALLBACK_ROUTE[def.id];
  if (!to) return null;
  const label = FALLBACK_LINK_LABELS_CZ[def.id] || `Otevřít ${def.label}`;
  return (
    <div className="mt-4 flex flex-wrap gap-2 items-center font-sans">
      <Link
        to={to}
        className="inline-flex items-center gap-1.5 text-sm px-3 py-2 rounded-xl border border-[hsl(var(--border)/0.75)] bg-card shadow-sm font-medium text-foreground hover:bg-muted/50 transition-colors"
      >
        {label}
        <ExternalLink className="h-3.5 w-3.5 opacity-70" aria-hidden />
      </Link>
    </div>
  );
}

export function SavedVisualAssetPreview({ previewPayload, compact = true }) {
  const pp = previewPayload && typeof previewPayload === "object" ? previewPayload : {};
  const wr = pp.widget_render && typeof pp.widget_render === "object" ? pp.widget_render : null;
  const h = compact ? 240 : 300;

  if (wr && wr.id && typeof wr.config === "object" && wr.data && typeof wr.data === "object" && !wr.data.error) {
    const w = { ...wr, _loading: false };
    const defaultChartType = String(wr.config?.chart_type || wr.data?.chart_type || "line").toLowerCase();
    return (
      <div className="mt-2 rounded-lg border border-border/70 bg-muted/15 px-2 py-2 min-w-0 max-w-full">
        <div className="w-full min-w-0 max-w-full overflow-auto" style={{ height: h }} data-testid="saved-visual-dashboard-preview">
          <WidgetRenderer w={w} defaultChartType={defaultChartType} aradMultiSeriesHelpContext="public_site" kpiSummaryMode="compact" />
        </div>
        {pp.source_note ? <p className="text-[10px] text-muted-foreground mt-1 line-clamp-2">{String(pp.source_note)}</p> : null}
      </div>
    );
  }

  const series = Array.isArray(pp.series) ? pp.series : [];
  const first = series[0] && typeof series[0] === "object" ? series[0] : null;
  const data = Array.isArray(first?.data) ? first.data : [];
  if (!data.length) return null;
  const hf = compact ? 140 : 180;
  return (
    <div className="mt-2 rounded-lg border border-border/70 bg-muted/15 px-2 py-2">
      <p className="text-[10px] text-muted-foreground mb-1">Zjednodušený náhled (bez plné konfigurace widgetu)</p>
      <div className="text-[10px] font-semibold text-foreground mb-1 truncate" title={String(pp.title || "")}>{String(pp.title || "Graf")}</div>
      <div style={{ height: hf }} className="w-full min-w-0">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 4, right: 6, left: 0, bottom: 0 }}>
            <XAxis dataKey="x" tick={{ fontSize: 9 }} height={28} interval="preserveStartEnd" />
            <YAxis width={36} tick={{ fontSize: 9 }} tickFormatter={(v) => String(v)} />
            <Tooltip formatter={(v) => [v, String(pp.y_axis || "hodnota")]} labelFormatter={(l) => String(l)} />
            <Line type="monotone" dataKey="y" stroke="hsl(160 55% 36%)" strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      {pp.source_note ? <p className="text-[10px] text-muted-foreground mt-1 line-clamp-2">{String(pp.source_note)}</p> : null}
    </div>
  );
}

export function DeepResultTierBadge({ tier }) {
  const t = String(tier || "candidate");
  const map = {
    verified: { cls: "border-emerald-300/80 bg-emerald-50 text-emerald-950", short: "Ověřeno", title: "Ověřeno — náhled vrátil data." },
    candidate: { cls: "border-amber-300/80 bg-amber-50 canvas-dark:bg-amber-950/35 text-amber-950 canvas-dark:text-amber-50", short: "Kandidát", title: "Nalezeno v katalogu — dokončete výběr dimenzí nebo ověření." },
    beta: { cls: "border-violet-300/70 bg-violet-50/95 text-violet-950", short: "Beta / slabší shoda", title: "Částečná shoda nebo méně spolehlivý kontext." },
    unavailable: { cls: "border-border/90 bg-muted text-foreground", short: "Nedostupné", title: "Ověření nebo zdroj nedostupný — zkuste upřesnit dotaz." },
    mismatch: { cls: "border-rose-300/80 bg-rose-50 text-rose-950", short: "Slabá shoda", title: "Náhled může fungovat, ale řada neodpovídá záměru dotazu." },
    catalog_supplement: { cls: "border-sky-300/70 bg-sky-50 canvas-dark:bg-sky-950/35 text-sky-950 canvas-dark:text-sky-50", short: "Katalogová shoda", title: "Nalezeno v katalogu AI hledáním, ale bez ověřeného datového náhledu." },
  };
  const m = map[t] || map.candidate;
  return (
    <span className={`inline-flex items-center font-normal text-[10px] uppercase tracking-wide border rounded-md px-1.5 py-0.5 shrink-0 ${m.cls}`} title={m.title}>
      {m.short}
    </span>
  );
}

export function SeriesLifecycleBadge({ row }) {
  const status = String(row?.lifecycle_status || "").trim().toLowerCase();
  const confidence = Number(row?.lifecycle_confidence ?? 0);
  if (status === "historical") {
    return (
      <span
        className="inline-flex items-center font-normal text-[10px] uppercase tracking-wide border rounded-md px-1.5 py-0.5 shrink-0 border-slate-300 bg-slate-100 text-slate-800"
        title={String(row?.lifecycle_reason || "Datov\u00fd zdroj ozna\u010duje \u0159adu jako historickou nebo ukon\u010denou.")}
      >
        {"Historick\u00e1"}
      </span>
    );
  }
  if (status === "current" && confidence >= 0.75) {
    return (
      <span
        className="inline-flex items-center font-normal text-[10px] uppercase tracking-wide border rounded-md px-1.5 py-0.5 shrink-0 border-sky-300 bg-sky-50 text-sky-900"
        title={String(row?.lifecycle_reason || "Metadata zdroje potvrzuj\u00ed aktu\u00e1ln\u00ed \u0159adu.")}
      >
        {"Aktu\u00e1ln\u00ed"}
      </span>
    );
  }
  return null;
}

export function deepCandidateAiSummaryLine(cand, preferredText) {
  const candidates = [preferredText, cand?.why_relevant, cand?.reason, cand?.what_to_verify, cand?.preview_error];
  for (const raw of candidates) {
    let s = String(raw || "").trim();
    if (!s) continue;
    const dashParts = s.split(/\s+[—–]\s+/);
    if (dashParts.length > 1) s = dashParts[0].trim();
    const paren = s.indexOf("(");
    if (paren > 20 && paren < 100) s = s.slice(0, paren).trim();
    if (s.length > 120) s = `${s.slice(0, 117).trim()}…`;
    if (s) return s;
  }
  return "";
}

export function deepCandidateHasExpandableAiNote(cand, detailLines, eurostatExtras) {
  if (eurostatExtras) return true;
  const summary = deepCandidateAiSummaryLine(cand);
  const texts = [];
  const seen = new Set();
  const push = (text) => {
    const t = String(text || "").trim();
    if (!t || seen.has(t)) return;
    seen.add(t);
    texts.push(t);
  };
  for (const line of detailLines || []) push(line);
  push(cand?.why_relevant);
  push(cand?.verify_note);
  push(cand?.what_to_verify);
  if (cand?.dimensions_hint) push(`Dimenzování: ${String(cand.dimensions_hint).trim()}`);
  if (texts.length > 1) return true;
  if (texts.some((t) => t !== summary && (t.length > summary.length + 8 || !summary))) return true;
  return Boolean(cand?.name || cand?.title || cand?.set_id || cand?.series_id);
}

function deepCandidateExplainMeta(cand) {
  const title = String(cand?.name || cand?.title || cand?.indicator_name || cand?.set_id || "Datová řada").trim();
  return {
    title,
    name: title,
    source_type: cand?.source_type || cand?.catalog_id || cand?.source || "",
    catalog_id: cand?.catalog_id || cand?.source_type || cand?.source || "",
    set_id: cand?.set_id || cand?.series_id || "",
    indicator_name: cand?.indicator_name || title,
    selected_indicator_name: cand?.selected_indicator_name || cand?.indicator_name || title,
    country_label: cand?.country_label || cand?.geo_label || cand?.territory || cand?.geo || "",
    geo_label: cand?.geo_label || cand?.country_label || cand?.territory || cand?.geo || "",
    frequency: cand?.frequency || cand?.freq || cand?.period || "",
    unit: cand?.unit || cand?.unit_label || "",
  };
}

export function DeepCandidateAiExplanation({ cand, summary, detailLines = [], eurostatExtras = null }) {
  const summaryText = String(summary || deepCandidateAiSummaryLine(cand) || "").trim();
  const expandable = deepCandidateHasExpandableAiNote(cand, detailLines, eurostatExtras);
  const [explanation, setExplanation] = React.useState(null);
  const [explanationStatus, setExplanationStatus] = React.useState("idle");
  if (!summaryText && !expandable) return null;
  if (!expandable) return <p className="text-foreground/90 leading-snug">{summaryText}</p>;

  const seen = new Set();
  const detailParagraphs = [];
  const pushDetail = (text) => {
    const t = String(text || "").trim();
    if (!t || seen.has(t)) return;
    seen.add(t);
    detailParagraphs.push(t);
  };
  for (const line of detailLines) pushDetail(line);
  pushDetail(cand?.why_relevant);
  pushDetail(cand?.verify_note);
  pushDetail(cand?.what_to_verify);
  if (cand?.dimensions_hint) pushDetail(`Dimenzování: ${String(cand.dimensions_hint).trim()}`);

  const loadExplanation = async (event) => {
    if (!event.currentTarget.open || explanationStatus !== "idle") return;
    setExplanationStatus("loading");
    try {
      const result = await fetchSeriesConceptExplanation(deepCandidateExplainMeta(cand));
      setExplanation(result);
      setExplanationStatus(result?.ok ? "ready" : "error");
    } catch {
      setExplanationStatus("error");
    }
  };

  const explanationText = String(explanation?.explanation_cz || explanation?.explanation_cs || explanation?.explanation || "").trim();
  const readHint = String(explanation?.read_hint_cz || "").trim();
  const sourceNote = seriesConceptExplainSourceNote(explanation);

  return (
    <details
      className="text-[11px] sm:text-xs group/deep-ai-note"
      onToggle={loadExplanation}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
    >
      <summary className="cursor-pointer list-none [&::-webkit-details-marker]:hidden flex flex-wrap items-baseline gap-x-2 gap-y-0.5 select-none">
        <span className="text-foreground/90 leading-snug line-clamp-2 flex-1 min-w-0">{summaryText || "Co tato řada měří"}</span>
        <span className="text-sky-800 canvas-dark:text-sky-300 text-[10px] font-medium shrink-0 group-open/deep-ai-note:hidden">Vysvětlení</span>
        <span className="text-muted-foreground text-[10px] shrink-0 hidden group-open/deep-ai-note:inline">Skrýt vysvětlení</span>
      </summary>
      <div className="mt-1.5 space-y-1 text-muted-foreground leading-snug border-l-2 border-border/50 pl-2.5">
        {explanationStatus === "loading" ? <p>Načítám vysvětlení ukazatele…</p> : null}
        {explanationText ? <p className="text-foreground/90">{explanationText}</p> : null}
        {readHint ? <p>{readHint}</p> : null}
        {explanationStatus === "error" ? <p>Vysvětlení se nepodařilo načíst.</p> : null}
        {detailParagraphs.map((t, i) => <p key={`deep-ai-detail-${i}`}>{t}</p>)}
        {eurostatExtras}
        {sourceNote ? <p className="text-[10px] text-muted-foreground/80">{sourceNote}</p> : null}
      </div>
    </details>
  );
}

export function AiResolverSelectionNote({ cand }) {
  if (!cand?.ai_data_resolver_selected) return null;
  const reason = String(cand.ai_data_resolver_reason_cz || "").trim();
  return (
    <p className="inline-flex w-fit rounded-md border border-sky-300/70 bg-sky-50 px-2 py-1 text-[11px] font-medium text-sky-950">
      AI vybrala tuto řadu jako přesný datový plán{reason ? `: ${reason}` : "."}
    </p>
  );
}

function deepCandidateSemanticBlockMessage(cand) {
  if (!cand || typeof cand !== "object") return "";
  const semanticLevel = String(cand.semantic_match_level || "").trim().toLowerCase();
  const demotion = String(cand.demotion_reason || cand.demotion_reasons || "").toLowerCase();
  if (cand.geo_match === false || demotion.includes("geo_mismatch")) {
    return "Náhled vrátil jinou geografii než požadoval dotaz; ponechávám pouze jako katalogového kandidáta.";
  }
  if (
    cand.negative_intent_match === true ||
    semanticLevel === "negative_mismatch" ||
    semanticLevel === "mismatch"
  ) {
    return "Náhled může existovat, ale řada neodpovídá významu dotazu; ponechávám ji jen jako nízkou katalogovou shodu.";
  }
  if (cand.metric_match === false) {
    return "Řada tematicky nesedí na požadovaný ukazatel, proto ji nepouštím jako datově ověřený výsledek.";
  }
  if (cand.domain_match === false || cand.topic_match === false) {
    return "Řada tematicky nesedí na oblast dotazu, proto ji nepouštím jako datově ověřený výsledek.";
  }
  return "";
}

function deepCandidateLooksSemanticallyRelevant(cand) {
  if (!cand || typeof cand !== "object") return false;
  const semanticLevel = String(cand.semantic_match_level || "").trim().toLowerCase();
  if (cand.negative_intent_match === true || semanticLevel === "negative_mismatch" || semanticLevel === "mismatch") {
    return false;
  }
  if (cand.topic_match === false || cand.metric_match === false || cand.domain_match === false) {
    return false;
  }
  return Boolean(
    cand.topic_match === true ||
    cand.metric_match === true ||
    cand.domain_match === true ||
    semanticLevel === "exact" ||
    semanticLevel === "partial" ||
    cand._ai_relevant === true,
  );
}

function deepCandidateNeutralCatalogMessage(cand) {
  if (!deepCandidateLooksSemanticallyRelevant(cand)) return "";
  const previewStatus = String(cand.preview_status || "").trim().toLowerCase();
  const discardReason = String(cand.discard_reason || "").trim().toLowerCase();
  if (
    previewStatus === "not_selected_for_preview" ||
    previewStatus === "unverified" ||
    discardReason === "preview_not_verified"
  ) {
    return "Kandidát tematicky odpovídá dotazu, ale není potvrzený živým náhledem. Otevřete ho v katalogu a vyberte konkrétní řadu nebo dimenze.";
  }
  return "";
}

export function DeepSearchCandidateResultCard({ cand, idx, variant = "auto", showRank = false, renderCatalogSetBlock, keyPrefix = "deep" }) {
  const def = resolveDeepSearchCatalogDef(cand);
  if (!def || typeof renderCatalogSetBlock !== "function") return null;
  const row = catalogRowFromDeepCandidate(cand);
  const isVerified = variant === "verified" || (variant !== "possible" && String(cand?.status || "").toLowerCase() === "verified");
  const rankLabel = cand.final_rank || idx + 1;
  const title = String(cand.name || cand.title || row.name || "").trim();

  let aiSlot;
  if (isVerified) {
    aiSlot = (
      <>
        <AiResolverSelectionNote cand={cand} />
        <DeepCandidateAiExplanation cand={cand} summary={deepCandidateAiSummaryLine(cand, cand.why_relevant)} detailLines={[cand.why_relevant, cand.verify_note].filter(Boolean)} />
      </>
    );
  } else {
    const rawCandMsg = String(cand.reason || cand.preview_error || "").trim();
    const euroOpen = def.sourceType === "eurostat" && eurostatAiRowNeedsOpenInCatalog(def, row);
    const euroTechnical = def.sourceType === "eurostat" && eurostatDeepAiMessageLooksInternalDebug(rawCandMsg);
    const semanticBlock = deepCandidateSemanticBlockMessage(cand);
    const neutralCatalogMessage = deepCandidateNeutralCatalogMessage(cand);
    let primaryFull = "";
    let eurostatExtras = null;
    if (semanticBlock) {
      primaryFull = semanticBlock;
    } else if (neutralCatalogMessage) {
      primaryFull = neutralCatalogMessage;
    } else if (def.sourceType === "eurostat") {
      if (euroTechnical) {
        primaryFull = EUROSTAT_DEEP_AI_PREVIEW_UNAVAILABLE_CZ;
        eurostatExtras = <EurostatDeepAiTechnicalDetails raw={rawCandMsg} />;
      } else if (euroOpen) {
        primaryFull = EUROSTAT_DEEP_AI_OPEN_CATALOG_DIMS_CZ;
      } else if (rawCandMsg) {
        primaryFull = rawCandMsg;
      } else {
        primaryFull = EUROSTAT_DEEP_AI_HINT_HAS_REF_CZ;
      }
    } else if (cand.reason || cand.preview_error) {
      primaryFull = String(cand.reason || cand.preview_error).trim();
    } else {
      primaryFull = "Nelze garantovat dostupný náhled jako u ověřených řad — ověřte zdroj v náhledu nebo zkuste užší řadu.";
    }
    aiSlot = (
      <>
        <AiResolverSelectionNote cand={cand} />
        <DeepCandidateAiExplanation cand={cand} summary={deepCandidateAiSummaryLine(cand, primaryFull)} detailLines={[primaryFull, cand.why_relevant].filter(Boolean)} eurostatExtras={eurostatExtras} />
      </>
    );
  }

  return renderCatalogSetBlock(def, row, "flat-compact", null, {
    showRank, rankLabel, isVerified, cand, title, aiSlot, keyPrefix,
  });
}
