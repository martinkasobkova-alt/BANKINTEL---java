import React from "react";
import { DefaultTooltipContent } from "recharts";

/** Kurzor uvnitř obdélníku výkresní plochy (ne margin os). */
export function isPointerInsidePlotViewBox(coordinate, viewBox) {
  if (!coordinate || !viewBox) return true;
  if (typeof coordinate.x !== "number" || typeof coordinate.y !== "number") return true;
  if (!Number.isFinite(coordinate.x) || !Number.isFinite(coordinate.y)) return true;
  const { x, y, width, height } = viewBox;
  if (![x, y, width, height].every((v) => Number.isFinite(v))) return true;
  if (width <= 0 || height <= 0) return true;
  return coordinate.x >= x && coordinate.x <= x + width && coordinate.y >= y && coordinate.y <= y + height;
}

/**
 * Tooltip jen nad výkresní plochou — Recharts jinak aktivuje payload i v okolí os (margin).
 * Recharts 3 předává `viewBox` přímo v props tooltipu (viz TooltipContentProps).
 */
export function PlotClampedTooltipBody(props) {
  if (!props.active) return null;
  if (!isPointerInsidePlotViewBox(props.coordinate, props.viewBox)) return null;
  return <DefaultTooltipContent {...props} />;
}
