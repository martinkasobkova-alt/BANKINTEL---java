/**
 * Lidské názvy běžných dimenzí OECD SDMX (nad technické id z API).
 */

export const OECD_DIM_LABELS_CZ = {
  REF_AREA: "Země / oblast",
  LOCATION: "Země / oblast",
  FREQ: "Frekvence",
  MEASURE: "Ukazatel",
  UNIT_MEASURE: "Jednotka",
  ACTIVITY: "Aktivita / sektor",
  ADJUSTMENT: "Očištění",
  TRANSFORMATION: "Transformace",
  PRICE_BASE: "Cenová báze",
  PRICES: "Ceny",
};

export function friendlyOecdDimLabel(dimId, apiName) {
  const raw = String(dimId || "").trim().toUpperCase();
  const fromDict = OECD_DIM_LABELS_CZ[raw];
  const name = typeof apiName === "string" ? apiName.trim() : "";
  if (fromDict && !name) return fromDict;
  if (fromDict && name) return `${fromDict}`;
  return name || dimId || "—";
}
