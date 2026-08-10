import React, { useMemo } from "react";
import { getAdImageFraming } from "@/lib/adConfig";

const FOCUS_PRESETS = [
  { x: 0, y: 0 },
  { x: 50, y: 0 },
  { x: 100, y: 0 },
  { x: 0, y: 50 },
  { x: 50, y: 50 },
  { x: 100, y: 50 },
  { x: 0, y: 100 },
  { x: 50, y: 100 },
  { x: 100, y: 100 },
];

/**
 * Ořez / fokus / přiblížení reklamního obrázku (sdílené AdminOverlay + AdConfigEditor).
 *
 * @param {{ cfg: object, onApply: (patch: object) => void, previewImageUrl?: string, introVariant?: "default" | "compact" }} props
 */
export function AdImageFramingBlock({ cfg, onApply, previewImageUrl, introVariant = "default" }) {
  const framing = useMemo(() => getAdImageFraming(cfg), [cfg]);
  const showPreview = Boolean(previewImageUrl?.trim());

  return (
    <div className="rounded-md border border-border/60 bg-white p-3 space-y-3">
      <div className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-semibold">
        Ořez a přiblížení
      </div>
      {introVariant === "compact" ? (
        <p className="text-[10px] text-slate-600 leading-snug">
          Kde držet střed obrázku v kartě a případné přiblížení. Ukládá se ihned po změně.
        </p>
      ) : (
        <p className="text-[10px] text-slate-600 leading-snug">
          Určuje, která část obrázku zůstane uprostřed rámečku a zda se má přiblížit (větší ořez).
          Platí pro jeden obrázek i carusel.
        </p>
      )}
      <div>
        <span className="text-[10px] font-medium text-slate-600 block mb-1.5">Výplň rámečku</span>
        <div className="inline-flex rounded-md border border-border overflow-hidden">
          <button
            type="button"
            onClick={() => onApply({ ad_image_object_fit: "cover" })}
            className={`h-8 px-3 text-xs ${
              (cfg?.ad_image_object_fit || "cover") !== "contain"
                ? "bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))] font-semibold"
                : "bg-amber-50 text-amber-900 hover:bg-amber-100 canvas-dark:bg-amber-900/45 canvas-dark:text-amber-100"
            }`}
          >
            Vyplnit (ořez)
          </button>
          <button
            type="button"
            onClick={() => onApply({ ad_image_object_fit: "contain" })}
            className={`h-8 px-3 text-xs border-l border-border ${
              (cfg?.ad_image_object_fit || "cover") === "contain"
                ? "bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))] font-semibold"
                : "bg-amber-50 text-amber-900 hover:bg-amber-100 canvas-dark:bg-amber-900/45 canvas-dark:text-amber-100"
            }`}
          >
            Celý obrázek
          </button>
        </div>
      </div>
      <div>
        <span className="text-[10px] font-medium text-slate-600 block mb-1.5">Fokus (kde držet střed)</span>
        <div className="grid grid-cols-3 gap-1 w-[132px]">
          {FOCUS_PRESETS.map((p, i) => {
            const cx = Number(cfg?.ad_image_pos_x);
            const cy = Number(cfg?.ad_image_pos_y);
            const ax = Number.isFinite(cx) ? cx : 50;
            const ay = Number.isFinite(cy) ? cy : 50;
            const active = ax === p.x && ay === p.y;
            return (
              <button
                key={i}
                type="button"
                title={`${p.x}% ${p.y}%`}
                onClick={() => onApply({ ad_image_pos_x: p.x, ad_image_pos_y: p.y })}
                className={`h-9 rounded border text-[10px] font-medium ${
                  active
                    ? "border-[hsl(var(--primary))] bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))]"
                    : "border-border bg-slate-50 hover:bg-slate-100 text-slate-600"
                }`}
              >
                ·
              </button>
            );
          })}
        </div>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <label className="text-[10px] text-slate-600 block">
          Vodorovně ({Number.isFinite(Number(cfg?.ad_image_pos_x)) ? Number(cfg.ad_image_pos_x) : 50} %)
          <input
            type="range"
            min={0}
            max={100}
            className="w-full mt-1"
            value={Number.isFinite(Number(cfg?.ad_image_pos_x)) ? Number(cfg.ad_image_pos_x) : 50}
            onChange={(e) => onApply({ ad_image_pos_x: Number(e.target.value) })}
          />
        </label>
        <label className="text-[10px] text-slate-600 block">
          Svisle ({Number.isFinite(Number(cfg?.ad_image_pos_y)) ? Number(cfg.ad_image_pos_y) : 50} %)
          <input
            type="range"
            min={0}
            max={100}
            className="w-full mt-1"
            value={Number.isFinite(Number(cfg?.ad_image_pos_y)) ? Number(cfg.ad_image_pos_y) : 50}
            onChange={(e) => onApply({ ad_image_pos_y: Number(e.target.value) })}
          />
        </label>
      </div>
      <label className="text-[10px] text-slate-600 block">
        Přiblížení ({Number.isFinite(Number(cfg?.ad_image_zoom_pct)) ? Number(cfg.ad_image_zoom_pct) : 100} %)
        <input
          type="range"
          min={100}
          max={200}
          step={5}
          className="w-full mt-1"
          value={Number.isFinite(Number(cfg?.ad_image_zoom_pct)) ? Number(cfg.ad_image_zoom_pct) : 100}
          onChange={(e) => onApply({ ad_image_zoom_pct: Number(e.target.value) })}
        />
      </label>

      {showPreview && (
        <div>
          <div className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-semibold mb-1.5">Náhled</div>
          <div className="w-full aspect-[16/10] max-h-[min(200px,40vh)] overflow-hidden rounded-md bg-slate-100/80 border border-border/50">
            <img
              src={previewImageUrl}
              alt=""
              className="h-full w-full min-h-0"
              style={{
                objectFit: framing.objectFit,
                objectPosition: framing.objectPosition,
                transform: framing.transform,
                transformOrigin: framing.transformOrigin,
              }}
            />
          </div>
          <p className="text-[10px] text-slate-500 mt-1 leading-snug">
            Poměr stran je zjednodušený — na stránce záleží na šířce a výšce widgetu.
          </p>
        </div>
      )}
    </div>
  );
}
