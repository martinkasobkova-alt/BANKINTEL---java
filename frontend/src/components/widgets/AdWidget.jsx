import React, { useEffect, useMemo, useState } from "react";
import { adCarouselIntervalSec, getAdImageFraming, getAdImageSlides, isAdCarouselMode } from "@/lib/adConfig";
import { resolveWidgetPanel } from "@/lib/widgetPanel";

/**
 * Reklamní widget — bez titulku, prostor je celý k dispozici pro obsah.
 *
 * Podporuje 3 režimy (`config.ad_kind`):
 *   - `image`    : jeden obrázek nebo carusel (`image_mode: carousel` + `slides[]`)
 *                 (volitelně klikatelný odkaz na slide / link_url)
 *   - `richtext` : markdown text (jednoduchý — bold/italic + obrázky, jako RichText)
 *   - `html`     : raw HTML snippet (typicky AdSense kód)
 *
 * Stejná komponenta se používá:
 *   - jako widget na homepage / sekci (přes WidgetRenderer.case "ad")
 *   - jako globální slot v menu (sidebar / topbar) přes AppShell
 *
 * Když je vše prázdné, vykreslí decentní placeholder ať admin pozná,
 * že slot je aktivovaný ale bez obsahu.
 */
export default function AdWidget({ data, config, slotMode = false, layout = "panel" }) {
  const cfg = { ...(config || {}), ...(data || {}) };
  const kind = (cfg.kind || cfg.ad_kind || "image").toLowerCase();
  const content = cfg.content || "";
  const html = cfg.html || "";

  const panel = slotMode ? { className: "", style: {} } : resolveWidgetPanel(config || {});
  const wrapperBase = slotMode
    ? "h-full w-full overflow-hidden flex items-stretch justify-stretch"
    : `${panel.className} h-full w-full overflow-hidden flex items-stretch justify-stretch min-w-0`;

  const imageSlides = useMemo(() => (kind === "image" ? getAdImageSlides(cfg) : []), [kind, cfg]);
  const slideKey = useMemo(
    () => imageSlides.map((s) => s.image_url).join("|"),
    [imageSlides]
  );
  const intervalSec = adCarouselIntervalSec(cfg);
  const imageFraming = useMemo(() => (kind === "image" ? getAdImageFraming(cfg) : null), [kind, cfg]);
  const useCarousel =
    kind === "image" && isAdCarouselMode(cfg) && imageSlides.length > 1;
  const [active, setActive] = useState(0);
  useEffect(() => {
    setActive(0);
  }, [slideKey]);
  useEffect(() => {
    if (!useCarousel) return undefined;
    const t = setInterval(
      () => setActive((i) => (i + 1) % imageSlides.length),
      Math.max(2000, intervalSec * 1000)
    );
    return () => clearInterval(t);
  }, [useCarousel, imageSlides.length, intervalSec]);

  const isEmpty =
    (kind === "image" && imageSlides.length === 0) ||
    (kind === "richtext" && !content.trim()) ||
    (kind === "html" && !html.trim());

  // Subtle "INZERCE" label v rohu — je to dobrá UX praxe a zákony
  // (Zákon o regulaci reklamy) explicitní označení vyžadují.
  const adLabel = !slotMode && (
    <span
      className="absolute top-1.5 right-2 text-[8px] uppercase tracking-[0.16em] font-semibold text-slate-400/80 bg-white/60 backdrop-blur px-1.5 py-0.5 rounded-sm pointer-events-none select-none"
      aria-hidden
    >
      Inzerce
    </span>
  );

  if (isEmpty) {
    return (
      <div className={wrapperBase} style={panel.style}>
        <div className="m-auto p-6 text-center text-xs text-slate-400 font-mono italic">
          {slotMode ? "Reklamní slot — zatím prázdný." : "Reklamní widget — zatím bez obsahu."}
        </div>
      </div>
    );
  }

  let body;
  if (kind === "image") {
    const imgClass =
      layout === "vertical"
        ? "h-full w-auto max-w-full mx-auto"
        : layout === "sidebar"
        ? "h-full w-full min-h-0 object-cover object-center"
        : slotMode
        ? "h-full w-full min-h-0 object-cover object-center"
        : "w-full h-full object-cover";

    const imgStyle = imageFraming
      ? {
          objectFit: imageFraming.objectFit,
          objectPosition: imageFraming.objectPosition,
          transform: imageFraming.transform,
          transformOrigin: imageFraming.transformOrigin,
        }
      : undefined;

    const renderOneSlide = (slide, { fadeKey }) => {
      const altT = slide.alt || "Reklama";
      const img = (
        <img
          src={slide.image_url}
          alt={altT}
          className={imgClass}
          style={imgStyle}
          loading="lazy"
        />
      );
      if (slide.link_url) {
        return (
          <a
            key={fadeKey}
            href={slide.link_url}
            target="_blank"
            rel="noopener noreferrer sponsored"
            className="block h-full w-full min-h-0 overflow-hidden"
            title={altT}
          >
            {img}
          </a>
        );
      }
      return (
        <div key={fadeKey} className="h-full w-full min-h-0 overflow-hidden">
          {img}
        </div>
      );
    };

    if (!useCarousel) {
      const one = imageSlides[0] || { image_url: "", link_url: "", alt: "Reklama" };
      body = one.image_url ? renderOneSlide(one, { fadeKey: "one" }) : null;
    } else {
      body = (
        <div
          className="relative h-full w-full min-h-0 group"
          role="img"
          aria-label={`Reklamní carusel, snímek ${active + 1} z ${imageSlides.length}`}
        >
          {imageSlides.map((slide, i) => (
            <div
              key={slide.image_url + String(i)}
              className="absolute inset-0 transition-opacity duration-500 ease-in-out"
              style={{ opacity: i === active ? 1 : 0, pointerEvents: i === active ? "auto" : "none" }}
            >
              {renderOneSlide(slide, { fadeKey: i })}
            </div>
          ))}
          {imageSlides.length > 1 && (
            <div
              className="absolute bottom-1.5 right-1.5 flex gap-0.5 rounded-sm bg-black/40 px-1 py-0.5"
              aria-hidden
            >
              {imageSlides.map((_, i) => (
                <span
                  key={i}
                  className={`h-1 w-1 rounded-full ${i === active ? "bg-white" : "bg-white/40"}`}
                />
              ))}
            </div>
          )}
        </div>
      );
    }
  } else if (kind === "richtext") {
    body = (
      <div
        className="prose-compact h-full w-full p-4 text-[13px] text-slate-700 overflow-y-auto text-normal-wrap"
        dangerouslySetInnerHTML={{ __html: renderMarkdownInline(content) }}
      />
    );
  } else {
    // HTML snippet — admin si je vědom, že tady běží bez sanitizace.
    body = (
      <div
        className="h-full w-full overflow-hidden"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    );
  }

  return (
    <div className={`${wrapperBase} relative`} style={panel.style}>
      {adLabel}
      {body}
    </div>
  );
}

// Minimalistický „bezpečný markdown" — bez závislostí. Stejná logika jako
// v RichText.jsx, jen v zkrácené verzi (bez medailonku).
function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderMarkdownInline(content) {
  const blocks = String(content).split(/\n\s*\n/);
  return blocks
    .map((block) => {
      let t = escapeHtml(block);
      // ![alt](url)
      t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_m, a, u) =>
        `<img src="${u}" alt="${a}" class="my-2 max-w-full rounded-sm" />`
      );
      // [text](url)
      t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, txt, u) =>
        `<a href="${u}" target="_blank" rel="noopener noreferrer" class="underline text-[hsl(var(--primary))]">${txt}</a>`
      );
      // **bold**
      t = t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
      // *italic*
      t = t.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>");
      // newlines → <br/>
      t = t.replace(/\n/g, "<br/>");
      return `<p class="text-left">${t}</p>`;
    })
    .join("");
}
