/**
 * Rozpoznání souhrnné hodnoty dimenze („Úhrn", „Celkem", „Total"…).
 *
 * Proč to existuje: výchozí hodnota dimenze se brala jako první položka seznamu.
 * ČSÚ vrací některé seznamy abecedně, takže se graf otevřel na „Alkoholické nápoje,
 * tabák a narkotika", zatímco ukazatel zůstal na „Úhrn" — průnik obou podmínek byl
 * skoro prázdný a v grafu zbyl jediný bod. Souhrn je navíc to, co člověk čeká vidět
 * jako první.
 *
 * Používá se na dvou místech, která staví dimenze nezávisle na sobě:
 * `catalogDimensions.js` a `sourcePreviewUserChoiceDimensions.js`.
 */

const AGGREGATE_PATTERNS = [
  /^úhrn$/i,
  /^celkem$/i,
  /^celkov[áý]\b/i,
  /^total$/i,
  /^všechny\b/i,
  /^vše$/i,
  /^all\b/i,
  /^t$/i,
  /^tot(al)?$/i,
];

/** @param {unknown} text */
export function isAggregateValue(text) {
  const s = String(text ?? "").trim();
  if (!s) return false;
  return AGGREGATE_PATTERNS.some((re) => re.test(s));
}

/**
 * Vybere výchozí položku: souhrn, pokud v seznamu je, jinak první.
 *
 * @param {Array<unknown>} items
 * @param {(item: unknown) => Array<unknown>} readTexts — hodnoty k porovnání (kód i popisek)
 * @returns {unknown | null}
 */
export function pickAggregateOrFirst(items, readTexts) {
  if (!Array.isArray(items) || items.length === 0) return null;
  const match = items.find((item) => (readTexts(item) || []).some(isAggregateValue));
  return match ?? items[0];
}
