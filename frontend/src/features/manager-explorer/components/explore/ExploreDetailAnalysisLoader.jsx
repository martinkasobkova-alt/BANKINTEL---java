import { CheckCircle2, Circle, Loader2, AlertTriangle } from "lucide-react";

const DETAIL_STEPS = [
  { id: "fetch", label: "Data načtena" },
  { id: "relationships", label: "Počítám vztahy mezi ukazateli (korelace, trend, medián)" },
  { id: "ai_sections", label: "AI píše detailní interpretaci" },
  { id: "final", label: "Generuji detailní doporučení" },
];

export const DETAIL_JOB_FAILED_USER_MESSAGE =
  "Detailní analýzu se nepodařilo dokončit. Rychlý orientační náhled je dostupný, ale doporučujeme výsledek ověřit.";

const STEP_ORDER = DETAIL_STEPS.map((s) => s.id);

function stepState(stepId, progressStep, status) {
  if (status === "failed") {
    if (STEP_ORDER.indexOf(stepId) <= STEP_ORDER.indexOf(progressStep || "fetch")) return "done";
    return "pending";
  }
  if (status === "completed") return "done";
  const currentIdx = STEP_ORDER.indexOf(progressStep || "queued");
  const idx = STEP_ORDER.indexOf(stepId);
  if (idx < currentIdx) return "done";
  if (idx === currentIdx) return "active";
  return "pending";
}

export default function ExploreDetailAnalysisLoader({
  status = "running",
  progressStep = "queued",
  progressPercent = 5,
  detailMessage = "",
  failedMessage = "",
}) {
  const failed = status === "failed";
  return (
    <div
      className={`rounded-xl border px-4 py-4 space-y-3 ${
        failed ? "border-amber-300 bg-amber-50/80" : "border-slate-200 bg-slate-50/80"
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-slate-900">
            {failed ? "Detailní analýza" : "Detailní analýza se připravuje"}
          </div>
          <p className="text-xs text-slate-600 mt-0.5">
            {failed
              ? failedMessage || DETAIL_JOB_FAILED_USER_MESSAGE
              : "Rychlý orientační náhled je hotový. Finální analytický závěr se dopočítává na pozadí."}
          </p>
        </div>
        {!failed && status !== "completed" ? (
          <Loader2 className="h-5 w-5 text-indigo-600 animate-spin shrink-0" />
        ) : null}
        {failed ? <AlertTriangle className="h-5 w-5 text-amber-600 shrink-0" /> : null}
      </div>
      {failed ? (
        <div className="rounded-lg border border-amber-200 bg-white/80 px-3 py-2 text-[12px] text-amber-950">
          {failedMessage || DETAIL_JOB_FAILED_USER_MESSAGE}
        </div>
      ) : null}
      {!failed ? (
        <>
          <div className="h-2 rounded-full bg-slate-200 overflow-hidden">
            <div
              className="h-full bg-indigo-600 transition-all duration-500"
              style={{ width: `${Math.max(5, Math.min(100, Number(progressPercent) || 5))}%` }}
            />
          </div>
          <ul className="space-y-2">
            {DETAIL_STEPS.map((step) => {
              const state = stepState(step.id, progressStep, status);
              return (
                <li key={step.id} className="flex items-center gap-2 text-sm">
                  {state === "done" ? (
                    <CheckCircle2 className="h-4 w-4 text-emerald-600 shrink-0" />
                  ) : state === "active" ? (
                    <Loader2 className="h-4 w-4 text-indigo-600 animate-spin shrink-0" />
                  ) : (
                    <Circle className="h-4 w-4 text-slate-300 shrink-0" />
                  )}
                  <span className={state === "pending" ? "text-slate-400" : "text-slate-800"}>{step.label}</span>
                </li>
              );
            })}
          </ul>
          {detailMessage ? <div className="text-xs text-slate-500">{detailMessage}</div> : null}
        </>
      ) : null}
    </div>
  );
}
