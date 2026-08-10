import { scoreVisual } from "./exploreAnalysisInsights";

const PRIORITY = { score: 5, rating: 4, eval: 3, metric: 2 };

/** Pravidla pro zvýraznění — pořadí v poli = priorita při stejném startu. */
const HIGHLIGHT_RULES = [
  {
    kind: "score",
    re: /\d+(?:[,.]\d+)?\/10/gi,
  },
  {
    kind: "rating",
    re: /\((?:smíšený|smíšené|příznivý|příznivé|nepříznivý|nepříznivé|neutrální|neutrální|neurčitý|slabší|slabý|silný|silné)\)/gi,
  },
  {
    kind: "rating",
    re: /(?:spíše ano|spíše ne|spíše počkat|spíše zvažovat exit|smíšené až slabší|smíšené až|smíšený obraz s výraznými protichůdy|spíše příznivý ekonomický kontext|spíše náročný ekonomický kontext|neutrální až smíšený kontext|slabý růst|silný růst|rostoucí trend|klesající trend)/gi,
  },
  {
    kind: "eval",
    re: /(?:Sektorová koherence:\s*[^.\n]+|sektorové signály jsou[^.,]+|Makro(?: prostředí)?:\s*[^.,]+)/gi,
  },
  {
    kind: "metric",
    re: /[-+−]?\d+(?:[,.]\d+)?\s*(?:%|procent)/gi,
  },
  {
    kind: "metric",
    re: /\d+(?:[,.]\d+)?\s*Mt\s*CO2(?:\s*eq\.?)?/gi,
  },
  {
    kind: "metric",
    re: /\b\d{4}-\d{2}-\d{2}\b/g,
  },
  {
    kind: "metric",
    re: /\b[-+]?\d+[,.]\d{3,}\b/g,
  },
  {
    kind: "metric",
    re: /\b\d+(?:[,.]\d+)?\s*(?:mld\.|mil\.|tis\.)\b/gi,
  },
];

function classifyHighlight(value) {
  const raw = String(value || "").trim();
  if (!raw) return "generic";
  if (/^\d+(?:[,.]\d+)?\/10$/i.test(raw)) return "score";
  if (/^\(.+\)$/.test(raw)) return "rating";
  if (/^Sektorová koherence:/i.test(raw)) return "coherence";
  if (/^Makro/i.test(raw)) return "macro_eval";
  if (/sektorové signály/i.test(raw)) return "coherence";
  if (/^[-+−]?\d/.test(raw)) return "metric";
  return "eval";
}

export function scoreHighlightClass(value) {
  const num = Number.parseFloat(String(value || "").replace("/10", "").replace(",", "."));
  if (!Number.isFinite(num)) return "font-bold text-[hsl(218_65%_32%)] tabular-nums";
  const { color, bg } = scoreVisual(num);
  return `${color} font-bold tabular-nums ${bg} px-1.5 py-0.5 rounded-md`;
}

export function ratingHighlightClass(value) {
  const fold = String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
  if (/prizniv|spise ok|konzistent|silne|spise ano/.test(fold)) {
    return "font-bold text-emerald-800 bg-emerald-50 px-1.5 py-0.5 rounded-md";
  }
  if (/neprizniv|naroc|slab|negativ|brzd|spise ne/.test(fold)) {
    return "font-bold text-rose-800 bg-rose-50 px-1.5 py-0.5 rounded-md";
  }
  if (/smisen|neutral|neurcit|rozptyl|smisene az/.test(fold)) {
    return "font-bold text-amber-900 bg-amber-50 px-1.5 py-0.5 rounded-md";
  }
  return "font-bold text-[hsl(218_65%_28%)] bg-[hsl(205_75%_94%)] px-1.5 py-0.5 rounded-md";
}

export function metricHighlightClass(value) {
  const raw = String(value || "").trim();
  if (/^[-−]/.test(raw)) {
    return "font-semibold text-rose-800 bg-rose-50/90 px-1 py-0.5 rounded tabular-nums";
  }
  if (/^\+/.test(raw)) {
    return "font-semibold text-emerald-800 bg-emerald-50/90 px-1 py-0.5 rounded tabular-nums";
  }
  return "font-semibold text-[hsl(202_90%_38%)] bg-[hsl(202_90%_52%/_0.12)] px-1 py-0.5 rounded tabular-nums";
}

export function evalHighlightClass(value) {
  return ratingHighlightClass(value);
}

export function highlightClassForToken(token) {
  const kind = token?.kind || classifyHighlight(token?.value);
  if (kind === "score") return scoreHighlightClass(token.value);
  if (kind === "metric") return metricHighlightClass(token.value);
  if (kind === "rating") return ratingHighlightClass(token.value);
  return evalHighlightClass(token.value);
}

function collectHighlightRanges(text) {
  const ranges = [];
  for (const rule of HIGHLIGHT_RULES) {
    const re = new RegExp(rule.re.source, rule.re.flags);
    let match = re.exec(text);
    while (match) {
      ranges.push({
        start: match.index,
        end: match.index + match[0].length,
        kind: rule.kind,
        value: match[0],
      });
      match = re.exec(text);
    }
  }
  ranges.sort((a, b) => {
    if (a.start !== b.start) return a.start - b.start;
    const pa = PRIORITY[a.kind] ?? 1;
    const pb = PRIORITY[b.kind] ?? 1;
    if (pa !== pb) return pb - pa;
    return b.end - b.start - (a.end - a.start);
  });
  const merged = [];
  let cursor = 0;
  for (const range of ranges) {
    if (range.start < cursor) continue;
    merged.push(range);
    cursor = range.end;
  }
  return merged;
}

/** Rozdělí text na běžný text a zvýrazněné úseky (skóre, čísla, hodnocení). */
export function tokenizeExploreCommentText(text) {
  const raw = String(text ?? "");
  if (!raw) return [{ kind: "text", value: "" }];

  const ranges = collectHighlightRanges(raw);
  if (!ranges.length) return [{ kind: "text", value: raw }];

  const tokens = [];
  let lastIndex = 0;
  for (const range of ranges) {
    if (range.start > lastIndex) {
      tokens.push({ kind: "text", value: raw.slice(lastIndex, range.start) });
    }
    tokens.push({ kind: classifyHighlight(range.value), value: range.value });
    lastIndex = range.end;
  }
  if (lastIndex < raw.length) {
    tokens.push({ kind: "text", value: raw.slice(lastIndex) });
  }
  return tokens.length ? tokens : [{ kind: "text", value: raw }];
}
