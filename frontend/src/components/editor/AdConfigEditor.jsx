import React, { useMemo, useRef, useState } from "react";
import { Image as ImageIcon, Link2, Code2, FileText, Upload, Plus, Trash2, Images } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiError } from "@/lib/api";
import { getAdImageSlides } from "@/lib/adConfig";
import { AdImageFramingBlock } from "@/components/editor/AdImageFramingBlock";

/**
 * Sdílený editor obsahu reklamy. Používá se ve dvou kontextech:
 *
 *   1) `WidgetListEditor` — config je `widget.config` a změny se posílají
 *      přes `onPatch({ ad_kind, image_url, ... })`
 *   2) `AdSlotsPage` — config je `slot` (sidebar/topbar), změny přes
 *      `onPatch({ kind, image_url, ... })`
 *
 * Pole jsou pojmenovaná identicky až na `ad_kind` vs. `kind` — komponenta
 * normalizuje obojí (čte `kind ?? ad_kind`, zapisuje do obou).
 */
export default function AdConfigEditor({ cfg, onPatch }) {
  const kind = (cfg?.kind ?? cfg?.ad_kind ?? "image").toLowerCase();
  const fileRef = useRef(null);
  const fileRefs = useRef({});
  const [uploading, setUploading] = useState(false);
  const [uploadingIndex, setUploadingIndex] = useState(null);
  const imageMode = (cfg?.image_mode || "single").toLowerCase() === "carousel" ? "carousel" : "single";

  const adPreviewImageUrl = useMemo(() => {
    const u = getAdImageSlides(cfg)[0]?.image_url;
    return u ? String(u).trim() : "";
  }, [cfg]);

  /** Min. 2 řádky pro editaci caruselu; sjednocuje legacy `image_url` + prázdný druhý slide. */
  const carouselEditRows = useMemo(() => {
    let s = Array.isArray(cfg?.slides) && cfg.slides.length ? cfg.slides.map((x) => ({ ...x })) : null;
    if (!s) {
      return [
        { image_url: cfg?.image_url || "", link_url: cfg?.link_url || "", alt: cfg?.alt || "" },
        { image_url: "", link_url: "", alt: "" },
      ];
    }
    if (s.length === 1) return [...s, { image_url: "", link_url: "", alt: "" }];
    return s;
  }, [cfg?.slides, cfg?.image_url, cfg?.link_url, cfg?.alt]);

  const set = (patch) => {
    // Zapisujeme oba klíče (kind + ad_kind) — slot config preferuje `kind`,
    // widget config historicky `ad_kind`. Když nastavujeme jeden, druhý
    // necháme neporušený, aby čtení zůstalo zpětně kompatibilní.
    if ("kind" in patch) {
      onPatch({ ...patch, ad_kind: patch.kind });
    } else if ("ad_kind" in patch) {
      onPatch({ ...patch, kind: patch.ad_kind });
    } else {
      onPatch(patch);
    }
  };

  const uploadImage = async (file) => {
    if (!file) return;
    const fd = new FormData();
    fd.append("file", file);
    setUploading(true);
    try {
      const { data } = await api.post("/media/upload", fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      if (!data?.url) throw new Error("Cloudinary nevrátil URL.");
      set({ image_url: data.url, kind: "image", image_mode: "single", slides: [] });
      toast.success("Obrázek nahrán.");
    } catch (e) {
      toast.error(formatApiError(e?.response?.data?.detail) || e.message);
    } finally {
      setUploading(false);
    }
  };

  const buildSlidesForPatch = (index) => {
    const slides = [...carouselEditRows];
    while (slides.length <= index) slides.push({ image_url: "", link_url: "", alt: "" });
    return slides;
  };

  const uploadImageToSlide = async (file, index) => {
    if (!file) return;
    const fd = new FormData();
    fd.append("file", file);
    setUploadingIndex(index);
    try {
      const { data } = await api.post("/media/upload", fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      if (!data?.url) throw new Error("Cloudinary nevrátil URL.");
      const slides = buildSlidesForPatch(index);
      slides[index] = { ...slides[index], image_url: data.url };
      set({ slides, kind: "image", image_mode: "carousel" });
      toast.success("Obrázek nahrán.");
    } catch (e) {
      toast.error(formatApiError(e?.response?.data?.detail) || e.message);
    } finally {
      setUploadingIndex(null);
    }
  };

  const patchSlide = (index, patch) => {
    const slides = buildSlidesForPatch(index);
    slides[index] = { ...slides[index], ...patch };
    set({ slides, image_mode: "carousel" });
  };

  const addSlide = () => {
    set({
      image_mode: "carousel",
      kind: "image",
      slides: [...carouselEditRows, { image_url: "", link_url: "", alt: "" }],
    });
  };

  const removeSlide = (index) => {
    if (carouselEditRows.length <= 1) return;
    const next = carouselEditRows.filter((_, i) => i !== index);
    set({ slides: next, image_mode: "carousel" });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500">
          Typ obsahu
        </span>
        <div className="inline-flex rounded-md border border-border overflow-hidden">
          {[
            { id: "image", label: "Obrázek", Icon: ImageIcon },
            { id: "richtext", label: "Text (markdown)", Icon: FileText },
            { id: "html", label: "HTML snippet", Icon: Code2 },
          ].map((opt, i) => {
            const active = kind === opt.id;
            return (
              <button
                key={opt.id}
                type="button"
                onClick={() => set({ kind: opt.id })}
                className={`inline-flex items-center gap-1.5 h-8 px-3 text-xs ${
                  i > 0 ? "border-l border-border" : ""
                } ${
                  active
                    ? "bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))] font-semibold"
                    : "bg-amber-50 text-amber-900 border-amber-300/70 hover:bg-amber-100 canvas-dark:bg-amber-900/45 canvas-dark:text-amber-100 canvas-dark:hover:bg-amber-900/65"
                }`}
              >
                <opt.Icon className="h-3.5 w-3.5" />
                {opt.label}
              </button>
            );
          })}
        </div>
      </div>

      {kind === "image" && (
        <div className="space-y-3">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-[10px] uppercase tracking-[0.14em] font-semibold text-slate-500">Zobrazení</span>
            <div className="inline-flex rounded-md border border-border overflow-hidden">
              <button
                type="button"
                onClick={() => {
                  const s = getAdImageSlides(cfg);
                  const first = s[0] || {};
                  set({
                    image_mode: "single",
                    kind: "image",
                    image_url: first.image_url || cfg?.image_url || "",
                    link_url: first.link_url || cfg?.link_url || "",
                    alt: first.alt || cfg?.alt || "",
                    slides: [],
                  });
                }}
                className={`inline-flex items-center gap-1.5 h-8 px-3 text-xs ${
                  imageMode === "single"
                    ? "bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))] font-semibold"
                    : "bg-amber-50 text-amber-900 hover:bg-amber-100 canvas-dark:bg-amber-900/45 canvas-dark:text-amber-100 canvas-dark:hover:bg-amber-900/65"
                }`}
              >
                <ImageIcon className="h-3.5 w-3.5" />
                Jeden obrázek
              </button>
              <button
                type="button"
                onClick={() => {
                  const base = {
                    image_url: cfg?.image_url || "",
                    link_url: cfg?.link_url || "",
                    alt: cfg?.alt || "",
                  };
                  const existing = Array.isArray(cfg?.slides) && cfg.slides.length ? cfg.slides : null;
                  set({
                    image_mode: "carousel",
                    kind: "image",
                    slides: existing && existing.length >= 2
                      ? existing
                      : [base, { image_url: "", link_url: "", alt: "" }],
                  });
                }}
                className={`inline-flex items-center gap-1.5 h-8 px-3 text-xs border-l border-border ${
                  imageMode === "carousel"
                    ? "bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))] font-semibold"
                    : "bg-amber-50 text-amber-900 hover:bg-amber-100 canvas-dark:bg-amber-900/45 canvas-dark:text-amber-100 canvas-dark:hover:bg-amber-900/65"
                }`}
              >
                <Images className="h-3.5 w-3.5" />
                Carusel
              </button>
            </div>
          </div>

          {imageMode === "single" && (
            <>
          <div>
            <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">URL obrázku</label>
            <div className="mt-1 flex items-stretch gap-2">
              <input
                type="text"
                className="input flex-1"
                placeholder="https://… nebo nahrát níže"
                value={cfg?.image_url || ""}
                onChange={(e) => set({ image_url: e.target.value, image_mode: "single", slides: [] })}
              />
              <button
                type="button"
                onClick={() => fileRef.current?.click()}
                disabled={uploading}
                className="inline-flex items-center gap-1.5 h-[38px] px-3 text-xs border border-amber-300/80 rounded-sm bg-amber-50 text-amber-950 hover:bg-amber-100 canvas-dark:bg-amber-900/45 canvas-dark:text-amber-100 canvas-dark:border-amber-500/45 canvas-dark:hover:bg-amber-900/65 disabled:opacity-60"
                title="Nahrát z PC (přes Cloudinary)"
              >
                <Upload className="h-3.5 w-3.5" />
                {uploading ? "Nahrávám…" : "Nahrát"}
              </button>
              <input
                ref={fileRef}
                type="file"
                accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
                className="hidden"
                onChange={(e) => {
                  uploadImage(e.target.files?.[0]);
                  e.target.value = "";
                }}
              />
            </div>
          </div>
          <div>
            <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium flex items-center gap-1">
              <Link2 className="h-3 w-3" /> Cílový odkaz (otevírá se v novém okně)
            </label>
            <input
              type="text"
              className="input mt-1"
              placeholder="https://… (volitelné)"
              value={cfg?.link_url || ""}
              onChange={(e) => set({ link_url: e.target.value, image_mode: "single" })}
            />
          </div>
          <div>
            <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">Popisek (alt text)</label>
            <input
              type="text"
              className="input mt-1"
              placeholder="Krátký popis obrázku pro přístupnost"
              value={cfg?.alt || ""}
              onChange={(e) => set({ alt: e.target.value, image_mode: "single" })}
            />
          </div>
            </>
          )}

          {imageMode === "carousel" && (
            <div className="space-y-3 rounded-md border border-border/60 bg-slate-50/50 p-3">
              <div className="flex flex-wrap items-end gap-3">
                <div>
                  <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium block">
                    Interval (sekundy)
                  </label>
                  <input
                    type="number"
                    min={2}
                    max={60}
                    className="input mt-1 w-24"
                    value={Number.isFinite(Number(cfg?.carousel_interval_sec)) ? Number(cfg.carousel_interval_sec) : 5}
                    onChange={(e) => {
                      const n = Math.min(60, Math.max(2, parseInt(e.target.value, 10) || 5));
                      set({ carousel_interval_sec: n, image_mode: "carousel" });
                    }}
                  />
                </div>
                <p className="text-[10px] text-slate-600 pb-0.5">Min. 2 s, max. 60 s. Potřebujete alespoň 2 snímky s URL obrázku.</p>
              </div>

              {carouselEditRows.map((sl, index) => (
                <div
                  key={index}
                  className="rounded-md border border-border/50 bg-white p-3 space-y-2"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                      Snímek {index + 1}
                    </span>
                    {carouselEditRows.length > 1 && (
                      <button
                        type="button"
                        onClick={() => removeSlide(index)}
                        className="inline-flex items-center gap-1 text-[10px] text-rose-600 hover:underline"
                      >
                        <Trash2 className="h-3 w-3" /> Odebrat
                      </button>
                    )}
                  </div>
                  <div>
                    <label className="text-[10px] text-slate-500">URL obrázku</label>
                    <div className="mt-0.5 flex items-stretch gap-2">
                      <input
                        type="text"
                        className="input flex-1 text-sm"
                        placeholder="https://…"
                        value={sl?.image_url || ""}
                        onChange={(e) => patchSlide(index, { image_url: e.target.value })}
                      />
                      <button
                        type="button"
                        onClick={() => fileRefs.current[index]?.click()}
                        disabled={uploadingIndex === index}
                        className="inline-flex items-center gap-1 h-[38px] px-2.5 text-xs border border-amber-300/80 rounded-sm bg-amber-50 text-amber-950 hover:bg-amber-100 canvas-dark:bg-amber-900/45 canvas-dark:text-amber-100 canvas-dark:border-amber-500/45 canvas-dark:hover:bg-amber-900/65"
                      >
                        <Upload className="h-3.5 w-3.5" />
                        {uploadingIndex === index ? "…" : "Nahrát"}
                      </button>
                      <input
                        ref={(el) => {
                          fileRefs.current[index] = el;
                        }}
                        type="file"
                        accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
                        className="hidden"
                        onChange={(e) => {
                          uploadImageToSlide(e.target.files?.[0], index);
                          e.target.value = "";
                        }}
                      />
                    </div>
                  </div>
                  <div>
                    <label className="text-[10px] text-slate-500">Odkaz (volitelné)</label>
                    <input
                      type="text"
                      className="input text-sm"
                      value={sl?.link_url || ""}
                      onChange={(e) => patchSlide(index, { link_url: e.target.value })}
                    />
                  </div>
                  <div>
                    <label className="text-[10px] text-slate-500">Alt</label>
                    <input
                      type="text"
                      className="input text-sm"
                      value={sl?.alt || ""}
                      onChange={(e) => patchSlide(index, { alt: e.target.value })}
                    />
                  </div>
                </div>
              ))}

              <button
                type="button"
                onClick={addSlide}
                className="inline-flex items-center gap-1.5 text-xs text-[hsl(var(--primary-deep))] font-medium hover:underline"
              >
                <Plus className="h-3.5 w-3.5" />
                Přidat snímek
              </button>
            </div>
          )}

          <AdImageFramingBlock
            cfg={cfg}
            onApply={set}
            previewImageUrl={adPreviewImageUrl || undefined}
          />
        </div>
      )}

      {kind === "richtext" && (
        <div>
          <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">
            Obsah (markdown — **tučně**, *kurzíva*, [text](url), ![alt](url))
          </label>
          <textarea
            className="input mt-1"
            style={{ height: "auto", minHeight: 120 }}
            value={cfg?.content || ""}
            onChange={(e) => set({ content: e.target.value })}
            placeholder="Krátký reklamní text, sponzorovaný odkaz, banner s textem…"
          />
        </div>
      )}

      {kind === "html" && (
        <div>
          <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">
            HTML snippet (např. AdSense, partnerský iframe)
          </label>
          <textarea
            className="input mt-1 font-mono"
            style={{ height: "auto", minHeight: 140 }}
            value={cfg?.html || ""}
            onChange={(e) => set({ html: e.target.value })}
            placeholder={'<script async src="..."></script>\n<ins class="adsbygoogle" ...></ins>'}
          />
          <div className="text-[10px] text-slate-500 mt-1 italic">
            HTML se vykresluje bez sanitizace — vkládejte jen kód, kterému důvěřujete.
          </div>
        </div>
      )}
    </div>
  );
}
