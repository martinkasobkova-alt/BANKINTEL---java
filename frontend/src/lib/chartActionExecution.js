function actionType(action) {
  return String(action?.type || "").trim().toLowerCase();
}

export function shouldAutoApplyChartActions(actions) {
  return Array.isArray(actions)
    && actions.length > 0
    && actions.every((action) => {
      const type = actionType(action);
      return type === "annotate_period" || type === "clear_period_annotations";
    });
}

export function appliedChartActionMessage(actions) {
  const list = Array.isArray(actions) ? actions : [];
  const annotations = list.filter((action) => actionType(action) === "annotate_period");
  const clears = list.filter((action) => actionType(action) === "clear_period_annotations");
  if (clears.length && !annotations.length) {
    return "Anotace byly odstraněny z grafu.";
  }
  if (annotations.length === 1) {
    const label = String(annotations[0]?.label || "událost").trim();
    return `Přidáno do grafu: ${label}.`;
  }
  if (annotations.length > 1) {
    const noun = annotations.length < 5 ? "ověřené anotace" : "ověřených anotací";
    return `Přidáno do grafu: ${annotations.length} ${noun}.`;
  }
  return "Změna byla použita v grafu.";
}

export function resultAfterAutomaticApply(result) {
  return {
    ...(result && typeof result === "object" ? result : {}),
    answer_cz: appliedChartActionMessage(result?.chart_actions),
    warnings: [],
    action_execution: "applied",
  };
}

export function uniqueResearchCitations(citations) {
  const seen = new Set();
  return (Array.isArray(citations) ? citations : []).filter((citation) => {
    const url = String(citation?.url || "").trim();
    if (!url || seen.has(url)) return false;
    seen.add(url);
    return true;
  });
}
