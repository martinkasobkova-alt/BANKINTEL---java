import React from "react";
import { formatScore } from "@/lib/exploreAnalysisInsights";

function formatReportDate(value) {
  try {
    return new Date(value || Date.now()).toLocaleString("cs-CZ", {
      dateStyle: "long",
      timeStyle: "short",
    });
  } catch {
    return "";
  }
}

function formatDecisionAnswer(answer) {
  const key = String(answer || "").trim().toLowerCase();
  const map = {
    yes: "spíše ano",
    no: "spíše ne",
    rather_no: "spíše ne",
    rather_wait: "spíše počkat",
    consider_exit: "spíše zvažovat exit",
    mixed_positive: "smíšeně příznivé",
    general_outlook: "obecný výhled",
    positive: "spíše příznivé",
    negative: "spíše nepříznivé",
    mixed: "smíšené",
    insufficient_data: "nedostatek dat",
  };
  return map[key] || (key ? String(answer) : "");
}

export default function ExploreManagerReportCover({ exploreMeta, result, generatedAt }) {
  const meta = exploreMeta && typeof exploreMeta === "object" ? exploreMeta : {};
  const analysisScore = result?.analysis_score && typeof result.analysis_score === "object" ? result.analysis_score : {};
  const decisionScore = analysisScore.decision_score ?? analysisScore.composite;
  const environmentScore = analysisScore.environment_score;
  const decisionAnswer = formatDecisionAnswer(analysisScore.decision_answer);
  const intentLabel =
    analysisScore?.question_understanding?.recognized_intent_label_cs ||
    analysisScore?.recognized_intent_label_cs ||
    "";

  const sector = String(meta.sector || "").trim();
  const countries = String(meta.countries || "").trim();
  const question = String(meta.question || "").trim();
  const related = String(meta.relatedSegments || "").trim();
  const headline = [sector, countries].filter(Boolean).join(" · ") || "Manažerská analýza";

  return (
    <header className="explore-manager-report-cover widget-panel-white widget-infographic-light overflow-hidden">
      <div className="explore-manager-report-cover-accent px-5 sm:px-7 pt-6 pb-5 sm:pt-7 sm:pb-6">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-white/75 mb-2">
          Bankoapp · Manager Explorer
        </div>
        <h2 className="text-xl sm:text-2xl font-extrabold text-white leading-tight tracking-tight">{headline}</h2>
        {intentLabel ? (
          <p className="text-[11px] sm:text-xs text-white/85 mt-2 font-medium">Záměr dotazu: {intentLabel}</p>
        ) : null}
        {question ? (
          <blockquote className="mt-4 border-l-[3px] border-white/50 pl-4 text-sm sm:text-[15px] text-white/95 leading-relaxed font-medium italic">
            „{question}“
          </blockquote>
        ) : null}
      </div>

      <div className="px-5 sm:px-7 py-4 sm:py-5 flex flex-wrap items-start justify-between gap-4 border-t border-border/50">
        <div className="space-y-1.5 text-[11px] sm:text-xs text-slate-600 min-w-0 flex-1">
          {related ? (
            <p>
              <span className="font-semibold text-slate-800">Související témata:</span> {related}
            </p>
          ) : null}
          <p>
            <span className="font-semibold text-slate-800">Vygenerováno:</span>{" "}
            {formatReportDate(generatedAt || Date.now())}
          </p>
          <p className="text-slate-500 leading-relaxed">
            Interní ekonomický report pro manažerské rozhodování — vhodný k archivaci, sdílení nebo vložení do
            prezentace (PDF).
          </p>
        </div>

        {decisionScore != null && Number.isFinite(Number(decisionScore)) ? (
          <div className="shrink-0 rounded-2xl border border-[hsl(205_45%_84%)] bg-gradient-to-br from-[hsl(205_75%_96%)] to-white px-4 py-3 text-center min-w-[7.5rem] shadow-sm">
            <div className="text-[9px] font-bold uppercase tracking-wider text-[hsl(218_65%_28%)]">
              Decision score
            </div>
            <div className="text-3xl font-extrabold tabular-nums text-[hsl(202_90%_42%)] leading-none mt-1">
              {formatScore(decisionScore)}
            </div>
            <div className="text-[10px] text-slate-500 font-medium mt-0.5">/ 10</div>
            {decisionAnswer ? (
              <div className="text-[10px] font-semibold text-slate-700 mt-2 leading-snug">{decisionAnswer}</div>
            ) : null}
            {environmentScore != null && Number.isFinite(Number(environmentScore)) ? (
              <div className="text-[9px] text-slate-500 mt-1.5">
                Prostředí {formatScore(environmentScore)}/10
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </header>
  );
}
