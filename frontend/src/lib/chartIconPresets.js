/** Presety ikon pro icon_chart a pictogram. */
export const CHART_ICON_PRESETS = [
  { id: "person", label: "Člověk", glyph: "👤" },
  { id: "people", label: "Lidé", glyph: "👥" },
  { id: "house", label: "Dům", glyph: "🏠" },
  { id: "car", label: "Auto", glyph: "🚗" },
  { id: "money", label: "Peníze", glyph: "💰" },
  { id: "food", label: "Jídlo", glyph: "🍽️" },
  { id: "factory", label: "Průmysl", glyph: "🏭" },
  { id: "chart", label: "Graf", glyph: "📊" },
  { id: "heart", label: "Srdce", glyph: "❤️" },
  { id: "star", label: "Hvězda", glyph: "⭐" },
  { id: "tree", label: "Příroda", glyph: "🌳" },
  { id: "globe", label: "Svět", glyph: "🌍" },
];

const PRESET_BY_ID = Object.fromEntries(CHART_ICON_PRESETS.map((p) => [p.id, p]));

export function chartIconGlyph(id, fallback = "📊") {
  return PRESET_BY_ID[String(id || "").trim()]?.glyph || fallback;
}

export function chartIconLabel(id) {
  return PRESET_BY_ID[String(id || "").trim()]?.label || String(id || "");
}
