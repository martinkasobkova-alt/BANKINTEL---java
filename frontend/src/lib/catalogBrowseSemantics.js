/**
 * Sémantika řádků ve stromu katalogu (/search/catalog „Procházet podle složek“).
 * Po zploštění mají řádky `kind: "set"` a původní typ v `item_kind`.
 */

/** @typedef {"folder"|"category"|"letter_bucket"|"dataflow"|"indicator"|"series"|"ecb_dataflow"} CatalogBrowseSemantic */

/**
 * @param {{ sourceType: string, id?: string }} def
 * @param {Record<string, unknown>} row
 * @returns {{ semantic: CatalogBrowseSemantic | "other", badge: string }}
 */
export function getCatalogBrowseSemantics(def, row) {
  const st = def.sourceType;
  const catalogId = String(def.id || "").trim().toLowerCase();
  const ik = String(row.item_kind || row.kind || "").toLowerCase();
  const sid = String(row.set_id ?? "").trim();

  if (catalogId === "ecb2" && sid.includes("/")) {
    return { semantic: "series", badge: "Časová řada" };
  }
  if (st === "fred") {
    if (ik === "category" || sid.startsWith("CAT||"))
      return { semantic: "category", badge: "Kategorie" };
    if (ik === "selection" || row.fred_series_id)
      return { semantic: "series", badge: "Časová řada" };
  }
  if (st === "worldbank") {
    if (ik === "letter" || /^LETTER\|\|/i.test(sid))
      return { semantic: "letter_bucket", badge: "Složka indikátorů" };
    if (ik === "selection" || row.wb_indicator)
      return { semantic: "indicator", badge: "Indikátor" };
  }
  if (st === "bis") {
    if (ik === "dataflow" || /\|\|DATAFLOW\s*$/i.test(sid))
      return { semantic: "dataflow", badge: "Dataflow" };
  }
  if (st === "ecb") {
    if (
      ik === "selection"
      || row.ecb_indicator_id
      || /^ecb:[A-Z0-9]{2}:/i.test(sid)
    ) {
      return { semantic: "indicator", badge: "Ukazatel" };
    }
    if (ik === "dataflow" || /\|\|DATAFLOW/i.test(sid)) {
      return { semantic: "ecb_dataflow", badge: "Vyžaduje výběr dimenzí" };
    }
  }
  return { semantic: "other", badge: "Položka" };
}

/**
 * @param {{ sourceType: string }} def
 * @param {Record<string, unknown>} row
 * @param {CatalogBrowseSemantic | "other"} semantic
 */
export function getCatalogBrowseHintCz(def, row, semantic) {
  const st = def.sourceType;
  if (st === "fred" && semantic === "category") {
    return "Toto je kategorie FRED. Otevřete podkategorie níže, nebo použijte vyhledávání konkrétního series ID (nebo odkaz na web FRED).";
  }
  if (st === "worldbank" && semantic === "letter_bucket") {
    return "Toto je abecední skupina indikátorů (podle prvního znaku názvu). Načtěte konkrétní indikátory, pak vyberte zemi pro časovou řadu.";
  }
  if (st === "worldbank" && semantic === "indicator") {
    return "Vyberte zemi (výše v sekci „Kde hledat“) — bez země není časová řada jednoznačná. Klíč má tvar ZEMĚ.INDIKÁTOR (např. CZE před kódem).";
  }
  if (st === "bis" && semantic === "dataflow") {
    return "Toto je BIS dataflow / dataset. Pro náhled je potřeba sestavit série (klíč dimenzí podle DSD) — pokračujte na stránce BIS katalogu.";
  }
  if (semantic === "ecb_dataflow") {
    return "Toto je datová oblast ECB, ne hotová časová řada. Nejprve najděte konkrétní řadu.";
  }
  if (semantic === "series") {
    return "";
  }
  return "Toto není přímá náhledovatelná řada v tomto kroku — pokračujte podle typu položky.";
}

/**
 * @returns {string}
 */
export function getCatalogBrowseLimitedActionHint(def, row, semantic) {
  const st = def.sourceType;
  if (semantic === "series") return "";
  if (st === "fred" && semantic === "category") {
    return "Toto je složka/kategorie. Otevřete ji a vyberte konkrétní řadu (series), nebo použijte odkazy.";
  }
  if (st === "worldbank" && semantic === "letter_bucket") {
    return "Toto je abecední skupina. Načtěte indikátory pro písmeno a potom vyberte zemi.";
  }
  if (st === "worldbank" && semantic === "indicator") {
    return "Indikátor není sám o sobě řada — vyberte zemi a pak zobrazte náhled.";
  }
  if (st === "bis" && semantic === "dataflow") {
    return "Toto je BIS dataset/dataflow. Pro náhled je potřeba vybrat řadu nebo dimenze (pokud aplikace nabízí).";
  }
  return "Tato položka slouží k orientaci ve stromě — pro data vyberte konkrétní časovou řadu.";
}
