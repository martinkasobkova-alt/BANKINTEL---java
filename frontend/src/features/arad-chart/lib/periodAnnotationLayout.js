const MAX_ANNOTATIONS = 24;
const MAX_LABEL_LENGTH = 28;

export function compactPeriodAnnotationLabel(value, maxLength = MAX_LABEL_LENGTH) {
  const label = String(value || "Událost").replace(/\s+/g, " ").trim() || "Událost";
  if (label.length <= maxLength) return label;
  return `${label.slice(0, Math.max(1, maxLength - 1)).trimEnd()}…`;
}

export function buildPeriodAnnotationLayout(annotations) {
  const source = Array.isArray(annotations) ? annotations.slice(0, MAX_ANNOTATIONS) : [];
  const entries = source.map((annotation, originalIndex) => {
    const from = String(annotation?.from || "").trim();
    const to = String(annotation?.to || from).trim();
    const label = String(annotation?.label || "Událost").replace(/\s+/g, " ").trim() || "Událost";
    return {
      annotation,
      originalIndex,
      from,
      to,
      label,
      displayLabel: compactPeriodAnnotationLabel(label),
    };
  }).filter((entry) => entry.from && entry.to);
  return { entries };
}
