import React from "react";

const QUERY_VARIANT_ROLE_LABELS = {
  original_exact: "puvodni dotaz",
  canonical_name: "kanonicky nazev",
  exact_alias: "alias",
  symbol: "symbol",
  translated_exact: "preklad",
  professional_synonym: "odborny synonymum",
  broader_concept: "sirsi pojem",
  related_entity: "souvisejici entita",
  comparison_entity: "srovnavaci entita",
  variant: "varianta",
};

export default function CatalogDeepSearchPlanPanel({ plan, sourceStatusText }) {
  if (!plan) return null;

  const queryVariants = Array.isArray(plan.queryVariants) ? plan.queryVariants : [];
  const metricTerms = Array.isArray(plan.metricTerms) ? plan.metricTerms : [];
  const domainTerms = Array.isArray(plan.domainTerms) ? plan.domainTerms : [];
  const signalTerms = [...metricTerms, ...domainTerms].filter(Boolean);
  const confidence = Number.isFinite(Number(plan.confidence)) ? Number(plan.confidence) : null;
  const statusLabel =
    plan.openaiStatus && typeof sourceStatusText === "function"
      ? sourceStatusText(plan.openaiStatus)
      : "";
  const statusMessage = plan.openaiStatus?.message_cs
    ? String(plan.openaiStatus.message_cs).slice(0, 200)
    : "";

  return (
    <div className="text-[11px] rounded-xl px-3 py-2.5 border border-sky-200 canvas-dark:border-sky-800/45 bg-sky-50/85 canvas-dark:bg-sky-950/35 space-y-1.5 leading-snug">
      <p className="font-semibold text-sky-950 canvas-dark:text-sky-100 uppercase tracking-wide text-[10px]">
        AI faze 1 - prelouskany dotaz
      </p>
      {plan.openaiStatus ? (
        <p className="text-foreground/90">
          Plan dotazu: <span className="font-medium">{statusLabel}</span>
          {statusMessage ? ` - ${statusMessage}` : ""}
        </p>
      ) : null}
      {plan.normalizedCz ? (
        <p>
          <span className="text-muted-foreground">CS:</span> {plan.normalizedCz}
        </p>
      ) : null}
      {plan.englishQuery ? (
        <p>
          <span className="text-muted-foreground">EN:</span> {plan.englishQuery}
        </p>
      ) : null}
      {plan.topic ? (
        <p>
          <span className="text-muted-foreground">Tema:</span> {plan.topic}
        </p>
      ) : null}
      {plan.queryShape ? (
        <p>
          <span className="text-muted-foreground">Profil:</span> {plan.queryShape}
          {confidence != null ? ` / jistota ${Math.round(confidence * 100)} %` : ""}
        </p>
      ) : null}
      {signalTerms.length > 0 ? (
        <p className="text-muted-foreground">Signaly: {signalTerms.join(" / ")}</p>
      ) : null}
      {queryVariants.length > 0 ? (
        <div className="text-muted-foreground">
          <span>Varianty hledani:</span>
          <div className="mt-1 flex flex-wrap gap-1.5">
            {queryVariants.map((variant, index) => {
              const text = typeof variant === "string" ? variant : String(variant?.text || "").trim();
              if (!text) return null;
              const role = typeof variant === "string" ? "variant" : String(variant?.role || "variant").trim();
              const label = QUERY_VARIANT_ROLE_LABELS[role] || role.replaceAll("_", " ");
              return (
                <span
                  key={`${role}:${text}:${index}`}
                  className="inline-flex items-center gap-1 rounded-md border border-sky-200 bg-white/75 px-1.5 py-0.5 text-[10px] text-sky-950 canvas-dark:border-sky-800/60 canvas-dark:bg-sky-950/40 canvas-dark:text-sky-100"
                >
                  <span>{text}</span>
                  <span className="text-sky-700/75 canvas-dark:text-sky-300/75">({label})</span>
                </span>
              );
            })}
          </div>
        </div>
      ) : null}
    </div>
  );
}
