import React from "react";
import { Info, Link2, Shield } from "lucide-react";
import {
  formatRelationshipValue,
  formatScore,
  hasValidManagerFinalScore,
  resolveAnalysisModeLabel,
  resolveDataUsageSummary,
  resolveExecutiveScore,
  resolveManagerInterpretationContext,
  resolveRelationshipBadge,
  resolveRelationshipSubtitle,
  resolveRelationshipTitle,
  resolveScoreBreakdownRows,
  resolveTopRelationships,
  scoreVisual,
} from "@/lib/exploreManagerInterpretation";
import ExploreCommentText from "@/components/explore/ExploreCommentText";

function ScoreRingMini({ score }) {
  const visual = scoreVisual(score);
  return (
    <div className={`inline-flex items-baseline gap-1 tabular-nums ${visual.color}`}>
      <span className="text-3xl font-bold leading-none">{formatScore(score)}</span>
      <span className="text-sm font-semibold opacity-80">/10</span>
    </div>
  );
}

function ScoreBreakdownGrid({ rows }) {
  if (!rows.length) return null;
  return (
    <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
      {rows.map((row) => {
        const visual = scoreVisual(row.score);
        return (
          <div
            key={row.id}
            className={`rounded-xl border px-3 py-2.5 shadow-sm ${
              row.highlight ? "border-[hsl(205_65%_72%)] bg-[hsl(205_75%_96%)]" : "border-white/80 bg-white/75"
            }`}
          >
            <div className="text-[10px] font-semibold text-slate-600 leading-snug">{row.label}</div>
            <div className={`mt-1 text-lg font-bold tabular-nums ${visual.color}`}>
              {formatScore(row.score)}
              <span className="text-[10px] font-semibold text-slate-500"> /10</span>
            </div>
            {row.sublabel ? (
              <div className={`text-[9px] font-medium mt-0.5 ${visual.color}`}>{row.sublabel}</div>
            ) : null}
            {row.inverted ? (
              <div className="text-[9px] text-slate-500 mt-0.5">Vyšší = horší nákladový tlak</div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

function RelationshipCard({ rel }) {
  const impactText = String(rel.impact_label_cs || rel.impact || "").trim();
  return (
    <div className="rounded-xl border border-white/80 bg-white/75 px-3 py-3 shadow-sm space-y-2">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-[hsl(218_65%_28%)] leading-snug">
            {resolveRelationshipTitle(rel)}
          </div>
          <div className="text-[10px] text-slate-500 mt-0.5">{resolveRelationshipSubtitle(rel)}</div>
        </div>
        <div className="text-right shrink-0 max-w-[9rem]">
          <div className="text-sm font-bold tabular-nums text-slate-900">{formatRelationshipValue(rel)}</div>
          <div className="text-[9px] text-slate-500 leading-snug mt-0.5">{resolveRelationshipBadge(rel)}</div>
        </div>
      </div>
      {rel.value_explanation_cs ? (
        <p className="text-[10px] text-slate-500 leading-relaxed">{rel.value_explanation_cs}</p>
      ) : null}
      {rel.interpretation ? (
        <p className="text-[11px] text-slate-700 leading-relaxed">
          <ExploreCommentText text={String(rel.interpretation)} />
        </p>
      ) : null}
      {impactText ? (
        <div className="text-[10px] text-slate-600">
          <span className="font-semibold">Dopad:</span> {impactText.replace(/_/g, " ")}
        </div>
      ) : null}
      {rel.isCorrelation ? (
        <div className="flex items-start gap-1.5 text-[10px] text-amber-900 bg-amber-50/90 rounded-lg px-2 py-1.5 border border-amber-200/80">
          <Info className="h-3.5 w-3.5 shrink-0 mt-0.5" />
          <span>Korelace neprokazuje kauzalitu — jde o orientační souvislost.</span>
        </div>
      ) : null}
      {Array.isArray(rel.limitations) && rel.limitations.length > 0 ? (
        <ul className="text-[10px] text-slate-600 list-disc pl-4 space-y-0.5">
          {rel.limitations.slice(0, 3).map((lim, idx) => (
            <li key={`${rel.relationship_id}-lim-${idx}`}>{lim}</li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

export default function ExploreManagerInterpretationPanel({ result, scoringDisplay = null, hideExecutiveVerdict = false }) {
  const ctx = resolveManagerInterpretationContext(result);
  if (!ctx) return null;

  const display = scoringDisplay || { showManagerPanel: true };
  if (!display.showManagerPanel) return null;

  const executive = hasValidManagerFinalScore(result) ? resolveExecutiveScore(ctx) : null;
  const breakdownRows = resolveScoreBreakdownRows(ctx);
  const relationships = resolveTopRelationships(ctx, 6);
  const keyFindings = Array.isArray(ctx.key_findings) ? ctx.key_findings.slice(0, 5) : [];
  const dataUsage = resolveDataUsageSummary(ctx);

  if (!executive && !breakdownRows.length && !relationships.length && !dataUsage) return null;

  return (
    <section className="explore-manager-interpretation space-y-4">
      {executive && !hideExecutiveVerdict ? (
        <div className="explore-report-panel explore-report-panel-featured widget-panel-white widget-infographic-light px-5 py-5 sm:px-6 sm:py-6 space-y-4">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="space-y-2 min-w-0 flex-1">
              <h3 className="explore-report-section-title mb-0">Manažerský verdikt</h3>
              <p className="text-sm text-slate-600 leading-relaxed">
                {resolveAnalysisModeLabel(executive.analysisMode)}
                {executive.weightNote ? ` · ${executive.weightNote}` : ""}
              </p>
              {executive.label ? (
                <p className="text-base font-bold text-[hsl(218_65%_28%)]">{executive.label}</p>
              ) : null}
            </div>
            <div className="shrink-0 text-right space-y-1">
              <ScoreRingMini score={executive.score} />
              {executive.confidence != null ? (
                <div className="text-[10px] text-slate-500">
                  Důvěra v data: <span className="font-semibold tabular-nums">{formatScore(executive.confidence)}/10</span>
                </div>
              ) : null}
            </div>
          </div>
          {keyFindings.length > 0 ? (
            <div className="rounded-xl border border-[hsl(205_45%_84%)] bg-gradient-to-br from-[hsl(205_75%_96%)] to-white px-4 py-3 space-y-1.5">
              <div className="text-[10px] font-bold uppercase tracking-wider text-[hsl(218_65%_28%)]">
                Hlavní důvody
              </div>
              <ul className="space-y-1 text-[12px] text-slate-800 leading-relaxed list-disc pl-4">
                {keyFindings.map((item, idx) => (
                  <li key={`kf-${idx}`}>
                    <ExploreCommentText text={String(item)} />
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      ) : null}

      {breakdownRows.length > 0 ? (
        <div className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-5 space-y-3">
          <h3 className="explore-report-section-title mb-1">Rozklad skóre</h3>
          <p className="text-[12px] text-slate-600 leading-relaxed">
            Škála 1–10: 1 = silně proti rozhodnutí, 5 = neutrální/nejasné, 10 = silně pro.
            U nákladového tlaku znamená vyšší číslo menší tlak na náklady.
          </p>
          <ScoreBreakdownGrid rows={breakdownRows} />
        </div>
      ) : null}

      {relationships.length > 0 ? (
        <div className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-5 space-y-3">
          <div className="flex items-center gap-2">
            <Link2 className="h-4 w-4 text-[hsl(218_65%_38%)]" />
            <h3 className="explore-report-section-title mb-0">Vztahy v datech</h3>
          </div>
          <p className="text-[12px] text-slate-600 leading-relaxed">
            Srovnání dvou signálů ze stejné geografie. Hodnota v procentních bodech (p.b.) ukazuje rozdíl mezi
            meziročními tempy růstu — ne absolutní procenta.
          </p>
          <div className="grid gap-3 md:grid-cols-2">
            {relationships.map((rel) => (
              <RelationshipCard key={rel.relationship_id || rel.type} rel={rel} />
            ))}
          </div>
        </div>
      ) : null}

      {dataUsage ? (
        <div className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-4 space-y-1.5">
          <div className="flex items-center gap-2">
            <Shield className="h-4 w-4 text-slate-600" />
            <h3 className="explore-report-section-title mb-0">Použitá data</h3>
          </div>
          <p className="text-[12px] text-slate-700 leading-relaxed">{dataUsage.text}</p>
        </div>
      ) : null}
    </section>
  );
}

export function ExploreManagerRecommendedChartsHeader() {
  return (
    <div className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-4">
      <h3 className="explore-report-section-title mb-1">Klíčové grafy</h3>
      <p className="text-[12px] text-slate-600 leading-relaxed">
        Vývoj ukazatelů, které mají největší váhu pro odpověď na vaši otázku.
      </p>
    </div>
  );
}
