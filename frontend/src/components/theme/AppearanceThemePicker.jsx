import React, { useId, useState } from "react";
import { Check, ChevronDown, Palette } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { useIsMobileDashboard } from "@/hooks/useMediaQuery";
import { getAppearancePresetById, getPresetStripCss } from "@/theme/appearancePresets";

/** @typedef {import("@/theme/appearancePresets").AppearancePreset} AppearancePreset */

/**
 * @param {object} props
 * @param {AppearancePreset[]} props.presets
 * @param {string} props.selectedId
 * @param {(id: string) => void} props.onSelect
 * @param {boolean} [props.showIntro]
 * @param {string} [props.className]
 */
export function AppearanceThemeMenuList({ presets, selectedId, onSelect, showIntro = true, className }) {
  const listId = useId();
  const selected = getAppearancePresetById(selectedId);

  return (
    <div className={cn("flex flex-col gap-1.5 min-h-0", className)}>
      {showIntro ? (
        <div className="px-2 pt-1 pb-0.5 border-b border-[hsl(var(--border)/0.4)] shrink-0">
          <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">Výběr schématu</p>
          <p className="text-[11px] text-muted-foreground leading-snug mt-1 mb-2">
            Hotové kombinace pozadí a barev rozhraní. Aktivní položka je zvýrazněná.
          </p>
        </div>
      ) : null}
      <div
        id={listId}
        role="listbox"
        aria-label="Seznam barevných schémat"
        className="overflow-y-auto overflow-x-hidden min-h-0 flex-1 pr-0.5 -mr-0.5 space-y-1"
      >
        {presets.map((p) => {
          const active = p.id === selected.id;
          const colors = getPresetStripCss(p);
          return (
            <button
              key={p.id}
              type="button"
              role="option"
              aria-selected={active}
              onClick={() => onSelect(p.id)}
              className={cn(
                "flex w-full items-center gap-3 rounded-xl px-2 py-2 text-left transition outline-none",
                "hover:bg-[hsl(var(--muted)/0.55)]",
                "focus-visible:ring-2 focus-visible:ring-[hsl(var(--ring))] focus-visible:ring-offset-1",
                active &&
                  "bg-[hsl(var(--primary-soft)/0.55)] ring-2 ring-[hsl(var(--primary)/0.45)] ring-offset-0 shadow-sm",
              )}
            >
              <PresetStripStrip colors={colors} className="h-9 w-[4.75rem] shrink-0 rounded-lg border border-[hsl(var(--border)/0.5)]" />
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-[hsl(var(--foreground))] leading-tight">{p.label}</span>
                <span className="block text-[10px] text-muted-foreground mt-0.5 capitalize">
                  {p.type === "gradient" ? "Gradient" : p.type === "mixed" ? "Kombinace" : "Plocha"}
                </span>
              </span>
              {active ? (
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[hsl(var(--primary))] text-[hsl(var(--primary-foreground))]">
                  <Check className="h-4 w-4" strokeWidth={2.5} aria-hidden />
                </span>
              ) : (
                <span className="w-7 shrink-0" aria-hidden />
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * @param {object} props
 * @param {AppearancePreset[]} props.presets
 * @param {string} props.selectedId
 * @param {(id: string) => void} props.onSelect
 * @param {"sidebar"|"icon"} props.variant
 * @param {string} [props.className]
 */
export default function AppearanceThemePicker({
  presets,
  selectedId,
  onSelect,
  variant = "sidebar",
  className,
  contentProps,
}) {
  const {
    side = "bottom",
    align = "end",
    sideOffset = 8,
    className: contentClassName,
    ...restPopoverContentProps
  } = contentProps || {};

  const [open, setOpen] = useState(false);
  const [inlineOpen, setInlineOpen] = useState(false);
  const isMobile = useIsMobileDashboard();
  const selected = getAppearancePresetById(selectedId);

  const handleSelect = (id) => {
    onSelect(id);
    setOpen(false);
    setInlineOpen(false);
  };

  const sidebarTrigger = (
    <button
      type="button"
      className={cn(
        "group flex w-full items-center gap-2.5 rounded-xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.92)] px-2.5 py-2 text-left shadow-sm transition",
        "hover:bg-[hsl(var(--card))] hover:border-[hsl(var(--primary)/0.35)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[hsl(var(--ring))] focus-visible:ring-offset-2 focus-visible:ring-offset-[hsl(var(--card))]",
        "data-[state=open]:[&_svg:last-child]:rotate-180",
        (open || inlineOpen) && "[&_svg:last-child]:rotate-180",
        className,
      )}
      aria-label={`Barevné schéma: ${selected.label}. Otevřít nabídku`}
      aria-expanded={inlineOpen || open}
    >
      <PresetStripStrip colors={getPresetStripCss(selected)} className="h-8 w-[4.5rem] shrink-0 rounded-lg" />
      <span className="min-w-0 flex-1">
        <span className="block text-[10px] font-semibold uppercase tracking-[0.1em] text-muted-foreground">
          Barevné schéma
        </span>
        <span className="block truncate text-xs font-medium text-[hsl(var(--foreground))] mt-0.5">{selected.label}</span>
      </span>
      <ChevronDown
        className="h-4 w-4 shrink-0 text-muted-foreground opacity-80 transition-transform duration-200"
        strokeWidth={2}
        aria-hidden
      />
    </button>
  );

  if (isMobile && variant === "sidebar") {
    return (
      <div className={cn("min-w-0", className)} data-testid="appearance-theme-picker-mobile-inline">
        <button
          type="button"
          onClick={() => setInlineOpen((v) => !v)}
          className={cn(
            "group flex w-full items-center gap-2.5 rounded-xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.92)] px-2.5 py-2 text-left shadow-sm transition",
            "hover:bg-[hsl(var(--card))] hover:border-[hsl(var(--primary)/0.35)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[hsl(var(--ring))] focus-visible:ring-offset-2 focus-visible:ring-offset-[hsl(var(--card))]",
            inlineOpen && "[&_svg:last-child]:rotate-180",
          )}
          aria-label={`Barevné schéma: ${selected.label}. Otevřít nabídku`}
          aria-expanded={inlineOpen}
        >
          <PresetStripStrip colors={getPresetStripCss(selected)} className="h-8 w-[4.5rem] shrink-0 rounded-lg" />
          <span className="min-w-0 flex-1">
            <span className="block text-[10px] font-semibold uppercase tracking-[0.1em] text-muted-foreground">
              Barevné schéma
            </span>
            <span className="block truncate text-xs font-medium text-[hsl(var(--foreground))] mt-0.5">{selected.label}</span>
          </span>
          <ChevronDown
            className="h-4 w-4 shrink-0 text-muted-foreground opacity-80 transition-transform duration-200"
            strokeWidth={2}
            aria-hidden
          />
        </button>
        {inlineOpen ? (
          <div
            className="mt-2 max-h-[min(45vh,22rem)] overflow-y-auto overscroll-contain rounded-xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card))] p-2 shadow-sm"
            data-testid="appearance-theme-inline-panel"
          >
            <AppearanceThemeMenuList
              presets={presets}
              selectedId={selectedId}
              onSelect={handleSelect}
              showIntro={false}
            />
          </div>
        ) : null}
      </div>
    );
  }

  const resolvedSide = isMobile && side === "right" ? "top" : side;
  const resolvedAlign = isMobile ? "start" : align;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        {variant === "sidebar" ? (
          sidebarTrigger
        ) : (
          <button
            type="button"
            className={cn(
              "inline-flex h-9 items-center gap-2 rounded-full border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.88)] pl-1 pr-2.5 text-muted-foreground shadow-sm transition",
              "hover:bg-[hsl(var(--card))] hover:text-[hsl(var(--primary))] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[hsl(var(--ring))] focus-visible:ring-offset-2",
              className,
            )}
            title="Barevné schéma aplikace"
            aria-label={`Motiv vzhledu: ${selected.label}. Otevřít nabídku`}
          >
            <PresetStripStrip colors={getPresetStripCss(selected)} className="h-7 w-14 shrink-0 rounded-full" />
            <Palette className="h-4 w-4 shrink-0 hidden sm:block" strokeWidth={1.8} aria-hidden />
          </button>
        )}
      </PopoverTrigger>
      <PopoverContent
        side={resolvedSide}
        align={resolvedAlign}
        sideOffset={sideOffset}
        collisionPadding={16}
        className={cn(
          "w-[min(calc(100vw-24px),20rem)] p-2 max-h-[min(70vh,440px)] flex flex-col bg-[hsl(var(--popover))] border-[hsl(var(--border)/0.85)] shadow-xl",
          contentClassName,
        )}
        {...restPopoverContentProps}
      >
        <AppearanceThemeMenuList
          presets={presets}
          selectedId={selectedId}
          onSelect={handleSelect}
        />
      </PopoverContent>
    </Popover>
  );
}

function PresetStripStrip({ colors, className }) {
  return (
    <span className={cn("flex overflow-hidden shadow-inner", className)} aria-hidden>
      {colors.map((c, i) => (
        <span key={i} className="min-w-0 flex-1 h-full" style={{ background: c }} />
      ))}
    </span>
  );
}
