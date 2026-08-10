const UNIT_MULT_LABEL_CS = {
  0: "Jednotky",
  3: "Tisíce",
  6: "Miliony",
  9: "Miliardy",
};

/** Odhad jednotky z řádků náhledu (UNIT_MULT, měna…). */
export function inferBisValueDescriptorFromRows(rows, fields = []) {
  if (!Array.isArray(rows) || rows.length === 0) return "";
  const sample = rows[0] && typeof rows[0] === "object" ? rows[0] : {};
  const fieldSet = new Set((fields || []).map((f) => String(f || "").trim().toUpperCase()));
  const parts = [];

  const readUnitMult = (key) => {
    const raw = sample[key];
    if (raw == null || raw === "") return "";
    const code = String(raw).trim();
    return UNIT_MULT_LABEL_CS[code] || UNIT_MULT_LABEL_CS[Number(code)] || "";
  };

  for (const key of ["UNIT_MULT", "unit_mult"]) {
    if (fieldSet.size && !fieldSet.has(key.toUpperCase()) && !fieldSet.has(key)) continue;
    const phrase = readUnitMult(key);
    if (phrase && !parts.includes(phrase)) parts.push(phrase);
  }

  for (const key of ["UNIT_MEASURE", "unit_measure", "CURRENCY", "currency"]) {
    if (fieldSet.size && !fieldSet.has(key.toUpperCase()) && !fieldSet.has(key)) continue;
    const raw = String(sample[key] ?? "").trim().toUpperCase();
    if (/^[A-Z]{3}$/.test(raw)) {
      const phrase = `měna ${raw}`;
      if (!parts.includes(phrase)) parts.push(phrase);
    }
  }

  return parts.join(" · ");
}

/** Sloučí popis z řádku katalogu, API náhledu a řádků dat. */
export function resolveCatalogValueDescriptor({
  fromRow = "",
  fromPreview = "",
  rows = [],
  fields = [],
} = {}) {
  for (const raw of [fromRow, fromPreview]) {
    const s = String(raw || "").trim();
    if (s) return s;
  }
  return inferBisValueDescriptorFromRows(rows, fields);
}
