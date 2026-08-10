/**
 * Jednotná konfigurace Recharts `<Tooltip>`: nepouštět box mimo výkresní viewBox.
 * Jinak při hoveri u pravého okraje Recharts přesune tooltip doprava, obsah přetéká
 * a rodič s `overflow-x: auto` vytvoří horizontální posuvník (často necitelný nad grafem).
 *
 * Pokud jsou allowEscapeViewBox.x/y false, používá se logika přepnutí (negative vs positive)
 * v `getTooltipTranslateXY` (Recharts) – tooltip zůstane uvnitř plot area.
 *
 * Výchozí `content` (`PlotClampedTooltipBody`) skryje tooltip, když kurzor není nad výkresní plochou.
 */

import { PlotClampedTooltipBody } from "./rechartsPlotTooltip";

export const CHART_TOOLTIP_ALLOW_ESCAPE_VIEW_BOX = Object.freeze({ x: false, y: false });

const BASE_CONTENT = {
  border: "1px solid hsl(205 45% 84%)",
  borderRadius: 8,
  fontFamily: "JetBrains Mono",
  background: "white",
  color: "hsl(218 30% 18%)",
  boxShadow: "0 4px 12px hsl(218 55% 30% / 0.12)",
  maxWidth: 280,
  wordWrap: "break-word",
  overflowWrap: "break-word",
  whiteSpace: "normal",
};

const BASE_WRAPPER = {
  outline: "none",
  zIndex: 120,
  pointerEvents: "none",
};

export function getRechartsTooltipContentStyle(overrides = {}) {
  return { ...BASE_CONTENT, ...overrides };
}

export function getRechartsTooltipWrapperStyle(overrides = {}) {
  return { ...BASE_WRAPPER, ...overrides };
}

/**
 * Sloučí vlastní `contentStyle` / `wrapperStyle` s bezpečným chováním tooltipu.
 * Předávejte i `formatter`, `labelFormatter`, `cursor` atd.
 */
export function mergeRechartsTooltipProps(props = {}) {
  const { contentStyle, wrapperStyle, allowEscapeViewBox, content, cursor, ...rest } = props;
  return {
    allowEscapeViewBox: allowEscapeViewBox ?? CHART_TOOLTIP_ALLOW_ESCAPE_VIEW_BOX,
    wrapperStyle: getRechartsTooltipWrapperStyle(wrapperStyle),
    contentStyle: getRechartsTooltipContentStyle(contentStyle),
    content: content ?? PlotClampedTooltipBody,
    // Bez vlastního `cursor` vypnout výchozí „celoplošný“ band u sloupců (hover v marginu os).
    cursor: cursor !== undefined ? cursor : false,
    ...rest,
  };
}
