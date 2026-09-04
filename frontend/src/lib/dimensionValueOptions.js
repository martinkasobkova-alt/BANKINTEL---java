import { withDistinctLabels } from "@/lib/indicatorLabels";

/**
 * Dimenze náhledu převedené na něco, z čeho jde postavit výběr — s popisky, ne s kódy.
 *
 * Proč to existuje: `extra_dimensions` nese jen ploché seznamy kódů
 * (`{field, label, values: ["AT","BE",…]}`), takže formulář nabízel „AT" a uživatel nevěděl,
 * co vybírá. Čitelné názvy leží vedle, v `metadata.dimensions[pole].sample_options`
 * (dvojice `{code,label}`) a `value_labels` — jen je nikdo nespojil dohromady.
 *
 * Popisky prochází `withDistinctLabels`, takže se v jednom seznamu nikdy neopakují:
 * když zdroj pošle dvakrát „Celkem", dostanou rozlišovač odvozený z kódu. Je to tatáž
 * mechanika, která řeší opakující se názvy ukazatelů.
 */

function readDimensionMeta(previewData) {
  const meta = previewData?.metadata?.dimensions;
  return meta && typeof meta === "object" && !Array.isArray(meta) ? meta : {};
}

/** Mapa kód → čitelný název, poskládaná ze všech míst, kam ji backend ukládá. */
function labelLookupFor(dimMeta) {
  const out = {};
  const options = Array.isArray(dimMeta?.sample_options) ? dimMeta.sample_options : [];
  for (const opt of options) {
    const code = String(opt?.code ?? "").trim();
    const label = String(opt?.label ?? "").trim();
    if (code && label) out[code] = label;
  }
  const valueLabels = dimMeta?.value_labels;
  if (valueLabels && typeof valueLabels === "object" && !Array.isArray(valueLabels)) {
    for (const [code, label] of Object.entries(valueLabels)) {
      const key = String(code ?? "").trim();
      const text = String(label ?? "").trim();
      if (key && text && !out[key]) out[key] = text;
    }
  }
  return out;
}

/**
 * @param {object} previewData odpověď náhledu (`/sources/{id}/preview` nebo `/catalog/preview`)
 * @param {{minValues?: number}} [opts] kolik hodnot musí dimenze mít, aby mělo smysl ji nabízet
 * @returns {Array<{field: string, label: string, values: Array<{code: string, label: string}>}>}
 */
export function buildDimensionValueOptions(previewData, { minValues = 2 } = {}) {
  const dims = Array.isArray(previewData?.extra_dimensions) ? previewData.extra_dimensions : [];
  const meta = readDimensionMeta(previewData);

  return dims
    .map((dim) => {
      const field = String(dim?.field ?? "").trim();
      if (!field) return null;
      const dimMeta = meta[field] || {};
      const lookup = labelLookupFor(dimMeta);
      const codes = [
        ...new Set(
          (Array.isArray(dim?.values) && dim.values.length > 0
            ? dim.values
            : Array.isArray(dimMeta?.values)
              ? dimMeta.values
              : []
          )
            .map((v) => String(v ?? "").trim())
            .filter(Boolean)
        ),
      ];
      if (codes.length < minValues) return null;

      const values = withDistinctLabels(codes, {
        getId: (code) => code,
        getLabel: (code) => lookup[code] || code,
      }).map((entry) => ({ code: entry.id, label: entry.label }));

      return {
        field,
        label: String(dimMeta?.label || dim?.label || field).trim() || field,
        values,
      };
    })
    .filter(Boolean);
}

/** Popisek hodnoty dimenze; když se nenajde, zůstane kód — prázdné políčko nikomu nepomůže. */
export function dimensionValueLabel(dimension, code) {
  const key = String(code ?? "").trim();
  const found = (dimension?.values || []).find((v) => v.code === key);
  return found?.label || key;
}
