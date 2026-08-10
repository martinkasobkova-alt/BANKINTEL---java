const GENERATED_CAPTION_RE =
  /^widget\s+(ukazuje|vykazuje|zobrazuje|naznačuje)\b/i;

const AI_NOISE_SENTENCES = [
  /řesnou příčinu z těchto dat nelze určit;?\s*vývoj je vhodné porovnat s dalšími ukazateli\.?/gi,
  /přesnou příčinu z těchto dat nelze určit;?\s*vývoj je vhodné porovnat s dalšími ukazateli\.?/gi,
  /přesnou příčinu nelze z těchto dat určit\.?/gi,
  /vývoj je vhodné porovnat s dalšími ukazateli\.?/gi,
  /data sama o sobě neříkají konkrétní příčinu\.?/gi,
];

export function stripAiCaptionNoise(value) {
  let text = String(value || "").trim();
  for (const pattern of AI_NOISE_SENTENCES) {
    text = text.replace(pattern, "");
  }
  return text.replace(/\s{2,}/g, " ").trim().replace(/[;\s]+$/, "");
}

/**
 * Skryje staré automaticky generované popisky, které patří do AI shrnutí,
 * ne do ručního caption pole pod grafem/tabulkou.
 */
export function cleanWidgetCaption(value) {
  const text = stripAiCaptionNoise(value);
  if (!text) return "";
  if (GENERATED_CAPTION_RE.test(text)) return "";
  return text;
}
