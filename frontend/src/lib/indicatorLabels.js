/**
 * Popisky ukazatelů — jedna mechanika pro celou aplikaci.
 *
 * Proč to existuje: ve výběru řady se objevilo 22 položek se stejným popiskem „USD".
 * Hodnoty byly různé (SDR01M01USD, SDR01M02USD, …), selhal jen popisek. Ukázalo se,
 * že zdroj názvy prostě nemá — ARAD vrací pro sadu 1088 dvanáct ukazatelů, ale jen dva
 * různé názvy (šest z nich se jmenuje shodně „Běžné ceny").
 *
 * Aplikace na to do teď reagovala dvěma nedobrými způsoby:
 *   1. `ind.name || ind.id` — zobrazí se opakující se popisky a nejde vybrat, co člověk chce.
 *   2. zahodit duplicity — z dvanácti řad zbydou dvě a zbytek je nedostupný.
 *
 * Tady je třetí cesta: **všechny řady zůstanou, popisky se rozliší**. Kvalita zdrojových
 * dat tím přestává být podmínkou použitelnosti seznamu.
 */

/** Data ze zdrojů občas přijdou s rozbitým kódováním; takový text je horší než nic. */
export function hasReplacementChar(value) {
  return String(value ?? "").includes("�");
}

export function cleanIndicatorLabel(value) {
  const text = String(value ?? "").trim();
  return text && !hasReplacementChar(text) ? text : "";
}

/** Doplní popisek do mapy id → název, pokud je čitelný a není to jen opsané id. */
export function addCleanIndicatorLabel(out, id, label) {
  const key = String(id ?? "").trim();
  const clean = cleanIndicatorLabel(label);
  if (!key || !clean || clean === key) return;
  if (!out[key] || hasReplacementChar(out[key])) out[key] = clean;
}

/**
 * Název ukazatele: přednost má mapa načtená ze zdroje, pak vlastní `name`, nakonec id.
 * @param {{id?: string, name?: string}} indicator
 * @param {Record<string, string>} [labelLookup]
 */
export function resolveIndicatorLabel(indicator, labelLookup) {
  const id = String(indicator?.id ?? "").trim();
  const fromLookup = cleanIndicatorLabel(labelLookup?.[id]);
  if (fromLookup) return fromLookup;
  const fromName = cleanIndicatorLabel(indicator?.name);
  if (fromName && fromName !== id) return fromName;
  return id;
}

/** Společná předpona skupiny řetězců (na znaky). */
function commonPrefixLength(values) {
  if (values.length < 2) return 0;
  const first = values[0];
  let n = 0;
  while (n < first.length && values.every((v) => v[n] === first[n])) n += 1;
  return n;
}

function commonSuffixLength(values, skipFromStart) {
  if (values.length < 2) return 0;
  const room = Math.min(...values.map((v) => v.length - skipFromStart));
  let n = 0;
  while (n < room && values.every((v) => v[v.length - 1 - n] === values[0][values[0].length - 1 - n])) n += 1;
  return n;
}

/**
 * Rozlišovač z identifikátorů: ze skupiny se odřízne společná předpona i přípona,
 * takže zbyde jen ta část, kterou se položky od sebe liší.
 *
 * `SDR01M01USD` / `SDR01M02USD` / `SDR01M11USD` → `01` / `02` / `11`
 *
 * Když se ids neliší vůbec, vrací se pořadové číslo — seznam musí zůstat rozlišitelný
 * i v tom nejhorším případě.
 */
function buildDiscriminators(ids) {
  const trimmed = ids.map((id) => String(id ?? "").trim());
  const prefix = commonPrefixLength(trimmed);
  const suffix = commonSuffixLength(trimmed, prefix);
  return trimmed.map((id, i) => {
    const core = id.slice(prefix, id.length - suffix).trim();
    // Prázdné jádro znamená, že se ids v porovnávané části neliší — pak nerozliší
    // ani celé id a jediné, co zbývá, je pořadí.
    return core || String(i + 1);
  });
}

/**
 * Zaručí, že se v jednom seznamu neopakují popisky — žádná položka se neztratí.
 *
 * @param {Array<T>} items
 * @param {{ getId: (item: T) => string, getLabel: (item: T) => string }} accessors
 * @returns {Array<{ item: T, id: string, label: string }>}
 * @template T
 */
export function withDistinctLabels(items, { getId, getLabel }) {
  const list = Array.isArray(items) ? items : [];
  const entries = list.map((item) => ({
    item,
    id: String(getId(item) ?? "").trim(),
    label: String(getLabel(item) ?? "").trim(),
  }));

  const byLabel = new Map();
  for (const e of entries) {
    const key = e.label.toLowerCase();
    if (!byLabel.has(key)) byLabel.set(key, []);
    byLabel.get(key).push(e);
  }

  for (const group of byLabel.values()) {
    if (group.length < 2) continue;
    const marks = buildDiscriminators(group.map((e) => e.id));
    group.forEach((e, i) => {
      e.label = e.label ? `${e.label} · ${marks[i]}` : marks[i];
    });
  }

  return entries;
}

/**
 * Zkratka pro select: vrátí položky s rozlišenými popisky rovnou k vykreslení.
 * @param {Array<{id?: string, name?: string}>} indicators
 * @param {Record<string, string>} [labelLookup]
 */
export function indicatorSelectOptions(indicators, labelLookup) {
  return withDistinctLabels(indicators, {
    getId: (ind) => ind?.id,
    getLabel: (ind) => resolveIndicatorLabel(ind, labelLookup),
  });
}
