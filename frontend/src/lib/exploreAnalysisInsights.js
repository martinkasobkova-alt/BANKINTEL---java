/** Barva a popisek pro skóre 1–10 (1 = špatné, 10 = výborné). */
export function scoreVisual(score) {
  const n = Number(score);
  if (!Number.isFinite(n)) return { color: "text-slate-500", ring: "stroke-slate-300", label: "—", bg: "bg-slate-100" };
  if (n >= 8) return { color: "text-emerald-700", ring: "stroke-emerald-500", label: "Příznivé", bg: "bg-emerald-50" };
  if (n >= 6) return { color: "text-teal-700", ring: "stroke-teal-500", label: "Spíše OK", bg: "bg-teal-50" };
  if (n >= 4) return { color: "text-amber-700", ring: "stroke-amber-500", label: "Smíšené", bg: "bg-amber-50" };
  return { color: "text-rose-700", ring: "stroke-rose-500", label: "Náročné", bg: "bg-rose-50" };
}

const GENERIC_HIGHLIGHT_LABELS = new Set([
  "klíčové číslo",
  "klicove cislo",
  "ukazatel",
  "položka",
  "polozka",
  "údaj",
  "udaj",
  "hodnota",
  "hodnota z analyzy",
  "value",
  "key number",
]);

function foldText(text) {
  return String(text || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

/** Bez názvu ukazatele není highlight relevantní — nesmí se zobrazovat. */
export function isGenericHighlightLabel(label) {
  const folded = foldText(label);
  if (!folded || GENERIC_HIGHLIGHT_LABELS.has(folded)) return true;
  if (folded.startsWith("hodnota z analy") || folded.startsWith("hodnota (")) return true;
  if (/^(polozka|udaj)\s+\d+$/.test(folded)) return true;
  return false;
}

function isValidHighlight(row) {
  if (!row || typeof row !== "object") return false;
  const label = String(row.label || "").trim();
  const value = String(row.value ?? "").trim();
  return Boolean(label && value && !isGenericHighlightLabel(label));
}

export const SECTION_DISPLAY_LIMITS = {
  default: { highlights: 3, drivers: 3 },
  sector: { highlights: 9, drivers: 9 },
  related_sectors: { highlights: 6, drivers: 6 },
};

export function sectionDisplayLimits(sectionId) {
  const sid = String(sectionId || "").trim().toLowerCase() || "default";
  return SECTION_DISPLAY_LIMITS[sid] || SECTION_DISPLAY_LIMITS.default;
}

/**
 * Highlight karty jen z backendu — vždy z načtených časových řad (label + value).
 * Neparsujeme čísla z textu analýzy (chyběl by název ukazatele).
 */
export function resolveSectionHighlights(section) {
  const fromApi = Array.isArray(section?.highlights) ? section.highlights : [];
  const limits = sectionDisplayLimits(section?.id);
  const seen = new Set();
  const out = [];
  for (const row of fromApi) {
    if (!isValidHighlight(row)) continue;
    const key = foldText(row.label);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(row);
    if (out.length >= limits.highlights) break;
  }
  return out;
}

export function formatScore(score) {
  const n = Number(score);
  if (!Number.isFinite(n)) return "—";
  return n.toFixed(1).replace(/\.0$/, "");
}

/** 8 povinných oblastí manažerského reportu. */
export const EXPLORE_HERO_SCORE_AREAS = [
  { id: "sector", label: "Hlavní segment" },
  { id: "related_sectors", label: "Přidružené segmenty" },
  { id: "macro", label: "Lokální ekonomika" },
  { id: "regional_economy", label: "Oblast a země" },
  { id: "global", label: "Světová ekonomika" },
  { id: "financial_markets", label: "Trhy" },
  { id: "commodities", label: "Komodity a energie" },
  { id: "demographics", label: "Demografie" },
];

function sectionDecisionScore(analysisScore, sectionId) {
  const details =
    analysisScore?.section_scores_detail && typeof analysisScore.section_scores_detail === "object"
      ? analysisScore.section_scores_detail
      : {};
  const simple =
    analysisScore?.section_scores && typeof analysisScore.section_scores === "object"
      ? analysisScore.section_scores
      : analysisScore?.sections && typeof analysisScore.sections === "object"
        ? analysisScore.sections
        : {};

  const fromMaps = (id) => {
    const detail = details[id];
    if (detail && typeof detail === "object") {
      const fromDetail = detail.decision_score ?? detail.score;
      if (Number.isFinite(Number(fromDetail))) return Number(fromDetail);
    }
    const fromSimple = simple[id];
    if (Number.isFinite(Number(fromSimple))) return Number(fromSimple);
    return null;
  };

  if (sectionId === "regional_economy") {
    const direct = fromMaps("regional_economy");
    if (direct != null) return direct;
    const legacy = ["neighbors", "partners", "eu"].map(fromMaps).filter((v) => v != null);
    if (!legacy.length) return null;
    const avg = legacy.reduce((sum, val) => sum + val, 0) / legacy.length;
    return Math.round(avg * 10) / 10;
  }

  return fromMaps(sectionId);
}

/** Skóre 8 oblastí pro hero kartu Decision score. */
export function resolveHeroSectionScores(analysisScore) {
  return EXPLORE_HERO_SCORE_AREAS.map((area) => ({
    id: area.id,
    label: area.label,
    score: sectionDecisionScore(analysisScore, area.id),
  }));
}

const DECISION_IMPACT_LABELS = {
  positive_for_question: "příznivé pro otázku",
  negative_for_question: "nepříznivé pro otázku",
  mixed_for_question: "smíšené pro otázku",
  neutral_for_question: "neutrální pro otázku",
};

const DIRECTION_LABELS = {
  positive: "pozitivní",
  negative: "negativní",
  neutral: "neutrální",
  mixed: "smíšené",
  rising: "roste",
  falling: "klesá",
  flat: "stabilní",
  unknown: "neznámé",
  risk_on: "risk-on",
  risk_off: "risk-off",
};

const DATA_QUALITY_LABELS = {
  high: "vysoká",
  medium: "střední",
  low: "nízká",
  insufficient: "nedostatečná",
  unknown: "neznámá",
};

const DRIVER_EXPLANATION_REPLACEMENTS = [
  [" strengthens the near-term case for investment or expansion.", " posiluje krátkodobý argument pro investici nebo expanzi."],
  [" weakens the near-term case for investment or expansion.", " oslabuje krátkodobý argument pro investici nebo expanzi."],
  [" has a mixed effect on the investment case and needs context.", " má na investiční argument smíšený dopad a vyžaduje širší kontext."],
  [" can support considering an exit sooner if conditions keep deteriorating.", " může podporovat dřívější exit, pokud se podmínky dál zhoršují."],
  [" argues against an immediate exit because waiting may still improve the outcome.", " mluví proti okamžitému exitu, protože vyčkání ještě může zlepšit výsledek."],
  [" supports exit timing only partially because worsening conditions can also hurt valuation.", " podporuje načasování exitu jen částečně, protože zhoršující se podmínky mohou zároveň snižovat valuaci."],
  [" may improve negotiating conditions, but the acquisition case stays mixed.", " může zlepšit vyjednávací podmínky, ale akviziční případ zůstává smíšený."],
  [" makes the acquisition case harder because weaker conditions can persist after the deal.", " ztěžuje akviziční případ, protože slabší podmínky mohou přetrvat i po transakci."],
  [" improves entry pricing on one side, but weakens the forward operating outlook on the other.", " zlepšuje vstupní ocenění, ale současně zhoršuje budoucí provozní výhled."],
  [" supports waiting because acting now could lock in poor timing.", " podporuje vyčkání, protože akce teď může znamenat špatné načasování."],
  [" weakens the case for waiting because conditions are already supportive.", " oslabuje argument pro vyčkání, protože podmínky už jsou podpůrné."],
  [" only partly supports waiting and should be read with timing context.", " podporuje vyčkání jen částečně a je potřeba ho číst v časovém kontextu."],
  [" supports defensive cost actions to protect margins and cash flow.", " podporuje obranná nákladová opatření k ochraně marží a cash flow."],
  [" reduces the urgency of defensive cost actions.", " snižuje naléhavost obranných nákladových opatření."],
  [" offers only partial support for cost-cutting right now.", " dává pro okamžité snižování nákladů jen částečnou oporu."],
  [" supports pricing action for this question.", " podporuje cenový krok pro tuto otázku."],
  [" weakens the case for pricing action for this question.", " oslabuje argument pro cenový krok u této otázky."],
  [" has a mixed impact on pricing because cost pressure and demand can move in opposite directions.", " má na pricing smíšený dopad, protože nákladový tlak a poptávka se mohou vyvíjet opačně."],
  [" supports the financing case for this question.", " podporuje finanční argument pro tuto otázku."],
  [" weakens the financing case for this question.", " oslabuje finanční argument pro tuto otázku."],
  [" gives a mixed financing signal and should be interpreted cautiously.", " dává smíšený finanční signál a je potřeba ho číst opatrně."],
  [" supports the case for entering the market now.", " podporuje vstup na trh právě teď."],
  [" weakens the case for entering the market now.", " oslabuje argument pro vstup na trh právě teď."],
  [" gives a mixed signal for market entry timing.", " dává smíšený signál pro načasování vstupu na trh."],
  [" improves the risk picture for this question.", " zlepšuje rizikový obrázek pro tuto otázku."],
  [" worsens the risk picture for this question.", " zhoršuje rizikový obrázek pro tuto otázku."],
  [" leaves the risk picture mixed.", " nechává rizikový obrázek smíšený."],
  [" is supportive in the general sector outlook.", " působí podpůrně v obecném sektorovém výhledu."],
  [" is adverse in the general sector outlook.", " působí nepříznivě v obecném sektorovém výhledu."],
  [" leaves the general outlook mixed.", " nechává celkový výhled smíšený."],
  [" looks supportive, but the question intent is not fully clear.", " působí podpůrně, ale záměr otázky není úplně jasný."],
  [" looks adverse, but the question intent is not fully clear.", " působí nepříznivě, ale záměr otázky není úplně jasný."],
  [" is context-dependent because the question intent is unclear.", " závisí na kontextu, protože záměr otázky není jasný."],
  [" The same weak trend is negative in general, but it can support acting before conditions worsen further.", " Stejný slabý trend je obecně negativní, ale může podporovat akci dřív, než se podmínky ještě zhorší."],
  [" Falling activity or demand usually weakens the case for near-term expansion.", " Klesající aktivita nebo poptávka obvykle oslabuje argument pro expanzi v nejbližší době."],
  [" Better entry pricing does not remove the risk of weaker post-deal performance.", " Lepší vstupní ocenění neodstraňuje riziko slabší výkonnosti po transakci."],
];

export function localizeDecisionImpact(value) {
  const key = String(value || "").trim().toLowerCase();
  return DECISION_IMPACT_LABELS[key] || String(value || "").trim() || "neznámé";
}

export function localizeDirectionLabel(value) {
  const key = String(value || "").trim().toLowerCase();
  return DIRECTION_LABELS[key] || String(value || "").trim() || "neznámé";
}

export function localizeDataQuality(value) {
  const key = String(value || "").trim().toLowerCase();
  return DATA_QUALITY_LABELS[key] || String(value || "").trim() || "neznámá";
}

export function localizeDriverExplanation(text) {
  let out = String(text || "").trim();
  if (!out) return "";
  for (const [from, to] of DRIVER_EXPLANATION_REPLACEMENTS) {
    out = out.replace(from, to);
  }
  return out;
}
