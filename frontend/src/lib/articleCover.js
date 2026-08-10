/** Náhledový obrázek zprávy pro seznam / karty. */
export function getArticleCoverImageUrl(article) {
  const direct = String(article?.cover_image_url || "").trim();
  if (direct) return direct;
  return "";
}

/** Náhledový obrázek čísla časopisu v archivu / čtečce. */
export function getIssueCoverImageUrl(issue) {
  return String(issue?.cover_image_url || "").trim();
}
