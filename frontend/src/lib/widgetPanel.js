/**
 * Vzhled „karty“ widgetu na přehledu / v sekcích — admin vybírá v editoru (Widgety).
 * Umožňuje koláž různých podbarvení místo jednotné bílé.
 */

const HEX = /^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$/;

/** Fyzicky světlé panely (ne token `card`) — při tmavém globálním motivu je nutné znovu nastavit barvy textu/tabulky. */
const LIGHT_INFOGRAPHIC_PANELS = new Set(["white", "muted", "slate", "mint", "cream"]);

/**
 * @param {Record<string, unknown> | null | undefined} config
 * @returns {{ className: string, style: Record<string, string> }}
 */
export function resolveWidgetPanel(config) {
  const raw = config?.panel_style ?? config?.card_style ?? "default";
  const preset = typeof raw === "string" ? raw.trim().toLowerCase() : "default";

  if (preset === "custom") {
    const c = String(config?.panel_color ?? "").trim();
    if (HEX.test(c)) {
      return {
        className:
          "rounded-2xl border border-border/50 shadow-[0_4px_18px_hsl(218_55%_30%/0.06)] overflow-hidden",
        style: { backgroundColor: c },
      };
    }
    return { className: "soft-card", style: {} };
  }

  const map = {
    default: "soft-card",
    white: "widget-panel-white",
    muted: "widget-panel-muted",
    slate: "widget-panel-slate",
    mint: "widget-panel-mint",
    cream: "widget-panel-cream",
    none: "widget-panel-none",
  };

  const base = map[preset] || map.default;
  const lightShell = LIGHT_INFOGRAPHIC_PANELS.has(preset);
  const className = lightShell ? `${base} widget-infographic-light`.trim() : base;
  return { className, style: {} };
}
