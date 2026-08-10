import React from "react";
import { ICON_ORIENTATIONS, MAP_REGION_OPTIONS } from "@/lib/chartKindCatalog";
import { CHART_ICON_PRESETS, chartIconGlyph } from "@/lib/chartIconPresets";

function IconOrientationControls({ orientation, onOrientationChange, activeControlStyle }) {
  return (
    <>
      <span className="text-[9px] text-slate-500 font-mono ml-1">Směr:</span>
      {ICON_ORIENTATIONS.map((opt) => (
        <button
          key={opt.id}
          type="button"
          onClick={() => onOrientationChange?.(opt.id)}
          className={`h-5 px-2 text-[9px] rounded border font-mono ${
            orientation === opt.id ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"
          }`}
          style={orientation === opt.id ? activeControlStyle : undefined}
          title={opt.id === "vertical" ? "Ikony pod sebou" : "Ikony vedle sebe"}
        >
          {opt.label}
        </button>
      ))}
    </>
  );
}

/** Doplňkové ovládání pro mapu, pictogram a ikonový graf. */
export default function ChartVisualConfigControls({
  chartKind,
  mapRegion = "europe",
  onMapRegionChange,
  pictogramIcon = "person",
  onPictogramIconChange,
  pictogramUnit = 1000,
  onPictogramUnitChange,
  iconOrientation = "horizontal",
  onIconOrientationChange,
  defaultIcon = "chart",
  onDefaultIconChange,
  activeControlStyle,
}) {
  if (chartKind === "geo_map") {
    return (
      <div className="flex flex-wrap items-center gap-2 mt-2">
        <span className="text-[9px] text-slate-500 font-mono">Region mapy:</span>
        {MAP_REGION_OPTIONS.map((opt) => (
          <button
            key={opt.id}
            type="button"
            onClick={() => onMapRegionChange?.(opt.id)}
            className={`h-5 px-2 text-[9px] rounded border font-mono ${
              mapRegion === opt.id ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"
            }`}
            style={mapRegion === opt.id ? activeControlStyle : undefined}
          >
            {opt.label}
          </button>
        ))}
      </div>
    );
  }

  if (chartKind === "pictogram") {
    return (
      <div className="flex flex-wrap items-center gap-2 mt-2">
        <span className="text-[9px] text-slate-500 font-mono">Ikona:</span>
        {CHART_ICON_PRESETS.slice(0, 8).map((opt) => (
          <button
            key={opt.id}
            type="button"
            onClick={() => onPictogramIconChange?.(opt.id)}
            title={opt.label}
            className={`h-6 w-6 text-sm rounded border ${
              pictogramIcon === opt.id ? "chip-mint border-transparent" : "border-border/60"
            }`}
            style={pictogramIcon === opt.id ? activeControlStyle : undefined}
          >
            {opt.glyph}
          </button>
        ))}
        <label className="inline-flex items-center gap-1 text-[9px] text-slate-600 ml-1">
          1 ikona =
          <input
            type="number"
            min={1}
            className="w-16 h-5 px-1 text-[9px] border rounded font-mono"
            value={pictogramUnit}
            onChange={(e) => onPictogramUnitChange?.(Number(e.target.value) || 1)}
          />
        </label>
        <IconOrientationControls
          orientation={iconOrientation}
          onOrientationChange={onIconOrientationChange}
          activeControlStyle={activeControlStyle}
        />
      </div>
    );
  }

  if (chartKind === "icon_chart") {
    return (
      <div className="flex flex-wrap items-center gap-2 mt-2">
        <span className="text-[9px] text-slate-500 font-mono">Výchozí ikona:</span>
        {CHART_ICON_PRESETS.map((opt) => (
          <button
            key={opt.id}
            type="button"
            onClick={() => onDefaultIconChange?.(opt.id)}
            title={opt.label}
            className={`h-6 w-6 text-sm rounded border ${
              defaultIcon === opt.id ? "chip-mint border-transparent" : "border-border/60"
            }`}
            style={defaultIcon === opt.id ? activeControlStyle : undefined}
          >
            {opt.glyph}
          </button>
        ))}
        <span className="text-[9px] text-slate-400">Náhled: {chartIconGlyph(defaultIcon)}</span>
        <IconOrientationControls
          orientation={iconOrientation}
          onOrientationChange={onIconOrientationChange}
          activeControlStyle={activeControlStyle}
        />
      </div>
    );
  }

  return null;
}
