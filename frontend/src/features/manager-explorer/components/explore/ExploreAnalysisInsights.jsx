import React from "react";
import { ArrowDownRight, ArrowUpRight, Info, Minus } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  formatScore,
  localizeDataQuality,
  localizeDecisionImpact,
  localizeDirectionLabel,
  localizeDriverExplanation,
  resolveHeroSectionScores,
  resolveSectionHighlights,
  scoreVisual,
  sectionDisplayLimits,
} from "@/lib/exploreAnalysisInsights";

const SECTION_SCORE_LABELS = {
  company: "Firma",
  sector: "Odvětví",
  related_sectors: "Související odvětví",
  commodities: "Komodity",
  financial_markets: "Finanční trhy",
  macro: "Makro",
  regional_economy: "Oblast a přidružené země",
  demographics: "Demografie",
  fx: "Kurzy",
  neighbors: "Sousedé",
  partners: "Partneři",
  eu: "Region",
  global: "Globální",
};

const SECTION_SCORE_DESCRIPTIONS = {
  company: "Firemní interní data nebo uploadovaná data firmy.",
  sector: "Hlavní výkon a poptávka v cílovém odvětví.",
  related_sectors: "Dodavatelé, poptávkové a nákladové drivery vázané na hlavní sektor.",
  commodities: "Vstupy, energie a komoditní náklady.",
  financial_markets: "Sazby, výnosy, spready, sentiment a finanční podmínky.",
  macro: "Domácí makroekonomické prostředí hlavní země.",
  demographics: "Populace a strukturální poptávkové faktory.",
  fx: "Kurzový vývoj a dopad na dovoz, vývoz a ceny.",
  neighbors: "Tlak a srovnání se sousedními zeměmi.",
  partners: "Obchodní partneři a dodavatelský řetězec.",
  eu: "Širší regionální nebo evropský kontext.",
  global: "Světové trendy a globální kotvy.",
};

function formatDecisionAnswer(answer) {
  const key = String(answer || "").trim().toLowerCase();
  if (!key) return "—";
  if (key === "yes") return "spíše ano";
  if (key === "no") return "spíše ne";
  if (key === "rather_no") return "spíše ne";
  if (key === "rather_wait") return "spíše počkat";
  if (key === "consider_exit") return "spíše zvažovat exit";
  if (key === "mixed_positive") return "smíšeně příznivé";
  if (key === "general_outlook") return "obecný výhled";
  if (key === "positive") return "spíše příznivé";
  if (key === "negative") return "spíše nepříznivé";
  if (key === "mixed") return "smíšené";
  if (key === "insufficient_data") return "nedostatek dat";
  return String(answer);
}

function formatIntentMethod(method) {
  const key = String(method || "").trim().toLowerCase();
  if (!key) return "—";
  if (key === "rules") return "pravidla";
  if (key === "llm") return "AI klasifikátor";
  if (key === "fallback") return "bezpečný fallback";
  return key;
}

function ScoreRing({ score, size = 52 }) {
  const visual = scoreVisual(score);
  const n = Number(score);
  const pct = Number.isFinite(n) ? Math.max(0, Math.min(100, (n / 10) * 100)) : 0;
  const r = (size - 8) / 2;
  const c = 2 * Math.PI * r;
  const dash = (pct / 100) * c;

  return (
    <div className="relative shrink-0" style={{ width: size, height: size }} title={`Skóre ${formatScore(score)}/10`}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth="4" className="stroke-slate-200/90" />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          strokeWidth="4"
          strokeLinecap="round"
          className={visual.ring}
          strokeDasharray={`${dash} ${c}`}
        />
      </svg>
      <div className={`absolute inset-0 flex flex-col items-center justify-center text-center ${visual.color}`}>
        <span className="text-sm font-bold leading-none">{formatScore(score)}</span>
        <span className="text-[8px] uppercase tracking-wide opacity-80">/10</span>
      </div>
    </div>
  );
}

function TrendIcon({ dir }) {
  if (dir === "up") return <ArrowUpRight className="h-3.5 w-3.5 text-emerald-600" />;
  if (dir === "down") return <ArrowDownRight className="h-3.5 w-3.5 text-rose-600" />;
  return <Minus className="h-3.5 w-3.5 text-slate-400" />;
}

export function ExploreSectionHighlights({ highlights, sectionId }) {
  const rows = Array.isArray(highlights) ? highlights : [];
  if (!rows.length) return null;
  const dense = sectionId === "sector" || sectionId === "related_sectors";
  return (
    <div
      className={`grid gap-2 mb-3 ${
        dense ? "sm:grid-cols-2 lg:grid-cols-3" : "sm:grid-cols-2 lg:grid-cols-3"
      }`}
    >
      {rows.map((h, idx) => (
        <div
          key={`${h.label}-${h.value}-${idx}`}
          className="rounded-lg border border-white/60 bg-white/70 backdrop-blur-sm px-3 py-2.5 shadow-sm"
        >
          <div className="text-[11px] font-semibold text-slate-800 leading-snug line-clamp-2" title={h.label}>
            {h.label}
          </div>
          {h.description ? (
            <div className="text-[10px] text-muted-foreground mt-1 leading-snug line-clamp-3" title={h.description}>
              {h.description}
            </div>
          ) : null}
          <div className="flex items-center gap-1.5 mt-1.5">
            <span className="text-base font-semibold text-slate-900 tabular-nums">{h.value}</span>
            <TrendIcon dir={h.trend_dir} />
          </div>
          {h.period ? <div className="text-[10px] text-muted-foreground mt-0.5">{h.period}</div> : null}
        </div>
      ))}
    </div>
  );
}

function ExploreHeroSectionScoreGrid({ analysisScore }) {
  const rows = resolveHeroSectionScores(analysisScore);
  const hasAny = rows.some((row) => row.score != null);
  if (!hasAny) return null;

  return (
    <div className="mt-4 pt-4 border-t border-[hsl(205_45%_84%)]/80">
      <div className="text-[10px] font-bold uppercase tracking-[0.12em] text-[hsl(218_65%_28%)] mb-2.5">
        Skóre 8 oblastí
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
        {rows.map((row) => {
          const visual = row.score != null ? scoreVisual(row.score) : null;
          return (
            <div
              key={row.id}
              className="rounded-xl border border-white/80 bg-white/75 px-2.5 py-2 shadow-sm min-w-0"
              title={row.label}
            >
              <div className="text-[10px] font-semibold text-slate-600 leading-snug line-clamp-2 min-h-[2rem]">
                {row.label}
              </div>
              <div
                className={`mt-1 text-base font-bold tabular-nums leading-none ${
                  visual ? visual.color : "text-slate-400"
                }`}
              >
                {row.score != null ? formatScore(row.score) : "—"}
                <span className="text-[10px] font-semibold text-slate-500"> /10</span>
              </div>
              {visual && row.score != null ? (
                <div className={`text-[9px] font-semibold mt-1 ${visual.color}`}>{visual.label}</div>
              ) : (
                <div className="text-[9px] font-medium text-slate-400 mt-1">bez dat</div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export function ExploreCompositeScoreHero({ analysisScore, sectorLabel }) {
  const decisionScore = analysisScore?.decision_score ?? analysisScore?.composite;
  const environmentScore = analysisScore?.environment_score;
  if (decisionScore == null || !Number.isFinite(Number(decisionScore))) return null;
  const visual = scoreVisual(decisionScore);
  const sectionScores =
    analysisScore?.section_scores && typeof analysisScore.section_scores === "object"
      ? analysisScore.section_scores
      : analysisScore?.sections && typeof analysisScore.sections === "object"
        ? analysisScore.sections
        : {};
  const compositeConfidence = Number(analysisScore?.composite_confidence);
  const hasSectionScores = Object.entries(sectionScores).filter(([, val]) => Number.isFinite(Number(val))).length > 0;
  const questionUnderstanding =
    analysisScore?.question_understanding && typeof analysisScore.question_understanding === "object"
      ? analysisScore.question_understanding
      : {};
  const recognizedIntentLabel =
    questionUnderstanding?.recognized_intent_label_cs || analysisScore?.recognized_intent_label_cs || "—";
  const intentReason = questionUnderstanding?.intent_reason || analysisScore?.intent_reason || "";
  const intentMethod = questionUnderstanding?.intent_method || analysisScore?.intent_method || "";
  const intentConfidence = Number(questionUnderstanding?.intent_confidence ?? analysisScore?.intent_confidence);
  const showIntentWarning =
    (!recognizedIntentLabel || recognizedIntentLabel === "neurčený záměr") ||
    (Number.isFinite(intentConfidence) && intentConfidence > 0 && intentConfidence < 0.55);

  return (
    <div className="rounded-2xl border border-[hsl(205_45%_84%)] bg-gradient-to-br from-[hsl(205_75%_96%)] via-white to-[hsl(205_75%_98%)] px-5 py-4 shadow-sm">
      <div className="flex flex-wrap items-start gap-4">
        <ScoreRing score={decisionScore} size={72} />
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <div className="text-[11px] font-bold uppercase tracking-[0.1em] text-[hsl(218_65%_28%)]">
              Decision score
            </div>
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-teal-300/60 bg-white/90 text-teal-800 shadow-sm hover:bg-teal-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40"
                  aria-label="Více informací o Decision score"
                >
                  <Info className="h-4 w-4" />
                </button>
              </PopoverTrigger>
              <PopoverContent
                align="end"
                className="w-[min(22rem,calc(100vw-2rem))] max-h-[min(70vh,28rem)] overflow-y-auto p-4 text-[12px] text-slate-700 leading-relaxed"
              >
                <div className="font-semibold text-slate-900 text-sm mb-2">Jak číst Decision score</div>
                <div className="space-y-2">
                  <p>
                    <span className="font-medium">Decision score</span> je hlavní odpověď na váš dotaz: ukazuje, jak příznivé nebo nepříznivé jsou
                    podmínky pro konkrétní manažerské rozhodnutí.
                  </p>
                  <p>
                    <span className="font-medium">Environment score</span> popisuje obecný stav prostředí v datech, ještě bez přímé vazby na vaši otázku.
                  </p>
                  <p>
                    {analysisScore?.scale_label_cs ||
                      "1 = silně proti otázce · 5 = neutrální/nejasné · 10 = silně podporuje odpověď na otázku"}
                  </p>
                  {Number.isFinite(compositeConfidence) && compositeConfidence > 0 ? (
                    <p>
                      Důvěryhodnost skóre: <span className="font-semibold">{Math.round(compositeConfidence * 100)} %</span>
                    </p>
                  ) : null}
                  <p className="text-[11px] text-slate-600 leading-relaxed pt-1 border-t border-slate-200/80 mt-2">
                    <span className="font-medium text-slate-800">Jak se počítá:</span>{" "}
                    Vážený průměr oblastí (odvětví, makro, komodity…) podle relevance k otázce. Střed{" "}
                    <span className="font-medium">5 = skutečně smíšené</span> signály pro váš dotaz, ne „vždy stejné
                    skóre“. Při jasné převaze pro/proti se skóre posune k 2–3 nebo 7–8.
                  </p>
                  {analysisScore?.composite_explanation?.score_reading_note ? (
                    <p className="text-[11px] text-amber-900/90 leading-relaxed mt-2">
                      {analysisScore.composite_explanation.score_reading_note}
                    </p>
                  ) : null}
                </div>
                <div className="mt-3 rounded-lg border border-slate-200/80 bg-slate-50/80 px-3 py-2.5">
                  <p>
                    Rozpoznaný záměr: <span className="font-semibold text-slate-900">{recognizedIntentLabel}</span>
                    {intentMethod ? (
                      <span className="text-slate-500"> · metoda: {formatIntentMethod(intentMethod)}</span>
                    ) : null}
                  </p>
                  {intentReason ? (
                    <p className="mt-1">
                      AI pochopila otázku jako: <span className="font-medium">{intentReason}</span>
                    </p>
                  ) : null}
                  {Number.isFinite(intentConfidence) ? (
                    <p className="mt-1">
                      Jistota rozpoznání: <span className="font-medium">{Math.round(intentConfidence * 100)} %</span>
                    </p>
                  ) : null}
                  {showIntentWarning ? (
                    <p className="mt-1.5 text-amber-700">
                      Záměr otázky není jednoznačný, výsledek berte jako orientační.
                    </p>
                  ) : null}
                </div>
                {hasSectionScores ? (
                  <div className="flex flex-wrap gap-1.5 mt-3 pt-3 border-t border-slate-200">
                    {Object.entries(sectionScores)
                      .filter(([, val]) => Number.isFinite(Number(val)))
                      .map(([id, val]) => {
                        const v = scoreVisual(val);
                        return (
                          <span
                            key={id}
                            className={`inline-flex items-center gap-1.5 rounded-full border border-slate-200 px-2 py-0.5 text-[11px] font-medium ${v.bg} ${v.color}`}
                            title={
                              SECTION_SCORE_DESCRIPTIONS[id] ||
                              "Dílčí skóre oblasti, která vstupuje do celkového výsledku."
                            }
                          >
                            <span className="opacity-80">{SECTION_SCORE_LABELS[id] || id}</span>
                            <span className="font-bold tabular-nums">{formatScore(val)}</span>
                          </span>
                        );
                      })}
                  </div>
                ) : null}
              </PopoverContent>
            </Popover>
          </div>
          <div className={`text-2xl font-bold ${visual.color}`}>
            {formatScore(decisionScore)}
            <span className="text-base font-semibold text-slate-500"> / 10</span>
          </div>
          <p className="text-sm text-slate-700 mt-1">
            {visual.label}
            {sectorLabel ? ` · ${sectorLabel}` : ""}
          </p>
          {environmentScore != null && Number.isFinite(Number(environmentScore)) ? (
            <p className="text-[12px] text-slate-600 mt-1">
              Environment score: <span className="font-semibold">{formatScore(environmentScore)} / 10</span>
              {analysisScore?.environment_direction ? ` · ${analysisScore.environment_direction}` : ""}
            </p>
          ) : null}
          {analysisScore?.decision_answer ? (
            <p className="text-[12px] text-slate-600 mt-1">
              Odpověď na otázku:{" "}
              <span className="font-semibold">{formatDecisionAnswer(analysisScore.decision_answer)}</span>
            </p>
          ) : null}
          <ExploreHeroSectionScoreGrid analysisScore={analysisScore} />
        </div>
      </div>
    </div>
  );
}

export function ExploreAnalysisSectionHeader({ section, suppressPrimaryScore = false }) {
  const score = section?.score;
  const highlights = resolveSectionHighlights(section);
  const showScore =
    !suppressPrimaryScore
    && score != null
    && Number.isFinite(Number(score))
    && section?.id !== "conclusion";
  const confidence = Number(section?.confidence);
  const riskSentiment = String(section?.risk_sentiment || "").trim();
  const dataQuality = String(section?.data_quality || "").trim();
  const statusLabel = String(section?.decision_label || section?.label || "").trim();
  const environmentLabel = String(section?.environment_label || "").trim();
  const rawArrow = String(section?.raw_arrow || "").trim();
  const decisionArrow = String(section?.decision_arrow || "").trim();
  const environmentScore = section?.environment_score;
  const decisionScore = section?.decision_score ?? section?.score;
  const showSectionNumericScores =
    !suppressPrimaryScore
    && (environmentScore != null || decisionScore != null)
    && section?.id !== "conclusion";

  return (
    <>
      <div className="flex items-start justify-between gap-3 mb-2">
        <div className="min-w-0">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-600">{section.title}</h3>
          <div className="flex flex-wrap items-center gap-1.5 mt-1">
            {statusLabel ? (
              <span className="inline-flex items-center rounded-full border border-white/80 bg-white/70 px-2 py-0.5 text-[10px] font-medium text-slate-700">
                {statusLabel}
              </span>
            ) : null}
            {dataQuality ? (
              <span className="inline-flex items-center rounded-full border border-white/80 bg-white/70 px-2 py-0.5 text-[10px] font-medium text-slate-700">
                Kvalita: {localizeDataQuality(dataQuality)}
              </span>
            ) : null}
            {Number.isFinite(confidence) ? (
              <span className="inline-flex items-center rounded-full border border-white/80 bg-white/70 px-2 py-0.5 text-[10px] font-medium text-slate-700">
                Důvěryhodnost: {Math.round(confidence * 100)} %
              </span>
            ) : null}
            {riskSentiment && riskSentiment !== "unknown" ? (
              <span className="inline-flex items-center rounded-full border border-white/80 bg-white/70 px-2 py-0.5 text-[10px] font-medium text-slate-700">
                Sentiment: {localizeDirectionLabel(riskSentiment)}
              </span>
            ) : null}
            {environmentLabel ? (
              <span className="inline-flex items-center gap-1 rounded-full border border-white/80 bg-white/70 px-2 py-0.5 text-[10px] font-medium text-slate-700">
                Data <TrendIcon dir={rawArrow} />
                {environmentLabel}
              </span>
            ) : null}
          </div>
          {showSectionNumericScores ? (
            <div className="flex flex-wrap items-center gap-3 mt-2 text-[11px] text-slate-600">
              {environmentScore != null && Number.isFinite(Number(environmentScore)) ? (
                <span className="inline-flex items-center gap-1.5" title="Obecný stav prostředí">
                  <span className="font-medium">Prostředí</span>
                  <TrendIcon dir={rawArrow} />
                  <span>{formatScore(environmentScore)}/10</span>
                </span>
              ) : null}
              {decisionScore != null && Number.isFinite(Number(decisionScore)) ? (
                <span className="inline-flex items-center gap-1.5" title="Dopad na konkrétní otázku manažera">
                  <span className="font-medium">Dopad na otázku</span>
                  <TrendIcon dir={decisionArrow} />
                  <span>{formatScore(decisionScore)}/10</span>
                </span>
              ) : null}
            </div>
          ) : null}
        </div>
        {showScore ? (
          <div className="flex items-center gap-2">
            <span className={`text-[10px] font-medium ${scoreVisual(score).color}`}>{scoreVisual(score).label}</span>
            <ScoreRing score={score} size={44} />
          </div>
        ) : null}
      </div>
      <ExploreSectionHighlights highlights={highlights} sectionId={section?.id} />
    </>
  );
}

export function ExploreQuestionDrivers({ drivers, sectionId }) {
  const rows = Array.isArray(drivers) ? drivers.filter((row) => row && typeof row === "object") : [];
  if (!rows.length) return null;
  const maxRows = sectionDisplayLimits(sectionId).drivers;
  return (
    <div className="mt-3 space-y-2">
      <div className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        Co nejvíc táhne odpověď
      </div>
      {rows.slice(0, maxRows).map((row, idx) => (
        <div
          key={`${row.driver || "driver"}-${idx}`}
          className="rounded-lg border border-border/60 bg-white/65 px-3 py-2"
          title={localizeDriverExplanation(row.explanation)}
        >
          <div className="flex flex-wrap items-center gap-2 text-[11px]">
            <span className="font-semibold text-slate-800">{row.driver}</span>
            <span className="inline-flex items-center gap-1 text-slate-500">
              Data <TrendIcon dir={row.raw_arrow} />
              {localizeDirectionLabel(row.raw_direction)}
            </span>
            <span className="inline-flex items-center gap-1 text-slate-700">
              Dopad <TrendIcon dir={row.decision_arrow} />
              {localizeDecisionImpact(row.decision_impact)}
            </span>
            {Number.isFinite(Number(row.score_contribution)) ? (
              <span className="font-medium text-slate-700">
                {Number(row.score_contribution) > 0 ? "+" : ""}
                {row.score_contribution}
              </span>
            ) : null}
          </div>
          {row.decision_label ? (
            <div className="mt-1 text-[11px] text-slate-700">{localizeDriverExplanation(row.decision_label)}</div>
          ) : null}
        </div>
      ))}
    </div>
  );
}
