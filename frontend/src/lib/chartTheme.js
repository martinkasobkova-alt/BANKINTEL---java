/**
 * Sdílený generátor barevné palety pro widgety podle uživatelského „barva grafu" (chart_color).
 *
 * Vrací vždy stejnou strukturu, kterou používá AradView (graf/tabulka),
 * RichText (textový widget) i případně další panely. Díky tomu se po
 * změně chart_color přebarví všechny prvky widgetu konzistentně —
 * border, akcentový titulek, jemný tint pozadí, headerBg, atd. — bez
 * toho, aby každá komponenta musela počítat svůj vlastní motiv.
 */

const HEX = /^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$/;

const DEFAULT_THEME = Object.freeze({
  accent: "hsl(202 90% 52%)",
  accentSoft: "hsl(202 90% 90%)",
  accentSofter: "hsl(205 75% 96%)",
  headerBg: "hsl(205 75% 96%)",
  tableHeaderBg: "hsl(205 78% 95%)",
  bodyBg: "transparent",
  captionBg: "hsl(205 75% 96%)",
  insightBg: "hsl(205 78% 94%)",
  border: "hsl(205 45% 84%)",
  grid: "#D4E6F7",
  axis: "#5878A0",
});

/** Převede `#aabbcc` (nebo `#abc`) na `{r,g,b}`. Vrací null pro nevalidní vstup. */
export function hexToRgb(hex) {
  const raw = String(hex || "").trim();
  const match = raw.match(HEX);
  if (!match) return null;
  const value = match[1].length === 3
    ? match[1].split("").map((c) => c + c).join("")
    : match[1];
  const n = parseInt(value, 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

/**
 * Vyrobí kompletní motiv pro widget z volitelné HEX barvy. Když není
 * předaná validní barva, vrací výchozí cyan/blue paletu aplikace.
 */
export function buildChartTheme(chartColor) {
  const rgb = hexToRgb(chartColor);
  if (!rgb) return DEFAULT_THEME;

  const rgbText = `${rgb.r} ${rgb.g} ${rgb.b}`;
  // Smícháme barevný odstín s bílou, abychom dostali plně neprůhlednou pastelku
  // pro sticky hlavičku tabulky (jinak text v řádcích pod ní prosvítá).
  const mix = (channel) => Math.round(channel * 0.18 + 255 * 0.82);
  const solidPastel = `rgb(${mix(rgb.r)}, ${mix(rgb.g)}, ${mix(rgb.b)})`;

  return {
    accent: `rgb(${rgb.r}, ${rgb.g}, ${rgb.b})`,
    accentSoft: `rgb(${rgb.r} ${rgb.g} ${rgb.b} / 0.16)`,
    accentSofter: `rgb(${rgb.r} ${rgb.g} ${rgb.b} / 0.07)`,
    headerBg: `linear-gradient(180deg, rgb(${rgbText} / 0.15), rgb(${rgbText} / 0.06))`,
    tableHeaderBg: solidPastel,
    bodyBg: `linear-gradient(180deg, rgb(${rgbText} / 0.035), rgb(${rgbText} / 0.015))`,
    captionBg: `rgb(${rgbText} / 0.08)`,
    insightBg: `rgb(${rgbText} / 0.12)`,
    border: `rgb(${rgbText} / 0.26)`,
    grid: `rgb(${rgbText} / 0.20)`,
    axis: `rgb(${rgb.r}, ${rgb.g}, ${rgb.b})`,
  };
}

export const CHART_THEME_DEFAULT = DEFAULT_THEME;
