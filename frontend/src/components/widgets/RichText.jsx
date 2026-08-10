import React from "react";
import { resolveWidgetPanel } from "@/lib/widgetPanel";
import { buildChartTheme, hexToRgb } from "@/lib/chartTheme";
import { useLocalizedContent } from "@/hooks/useLocalizedContent";

/**
 * Lightweight rich-text widget used on the homepage / sections.
 * Supports:
 *   - optional heading / subheading (left aligned)
 *   - inline bold (**text**), italic (*text* or _text_)
 *   - images via ![alt](url)  OR a bare URL on its own line  OR <img src="…">
 *   - medallion (commentator head): ![medailon](url) — first block alone = large
 *     circle at top; same syntax inline = small round avatar in text
 *   - single newlines → <br/>, blank lines → paragraph breaks
 *
 * We deliberately avoid a heavyweight markdown/HTML dependency to keep
 * the bundle slim; everything is rendered safely by escaping first.
 */
const MEDALLION_ALT = /^(medailon|medailonek|medallion|avatar)$/i;
const HEX = /^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$/;

/**
 * Najde KAMKOLI v textu první ![medailon](url) markdown a vytáhne ho ven –
 * vždy ho zobrazíme nahoře vedle nadpisu, nezávisle na tom, kam ho admin
 * v editoru vložil. Z těla obsahu medailonek odstraníme, aby se nezobrazoval dvakrát.
 */
function extractMedallion(content) {
  const raw = String(content);
  if (!raw) return { url: null, rest: raw };
  const re = /!\[([^\]]+)\]\(([^)\s]+)\)/g;
  let match;
  while ((match = re.exec(raw)) !== null) {
    if (MEDALLION_ALT.test(match[1])) {
      const url = match[2];
      const before = raw.slice(0, match.index);
      const after = raw.slice(match.index + match[0].length);
      // Posklízíme zbylé prázdné řádky kolem vyňatého markdownu, aby v textu nezůstala mezera.
      const rest = `${before}${after}`.replace(/\n{3,}/g, "\n\n").replace(/^\s+|\s+$/g, "");
      return { url, rest };
    }
  }
  return { url: null, rest: raw };
}

export default function RichText({ title, data, config, widget }) {
  const loc = useLocalizedContent();
  const w = widget || { title, config };
  const content = loc.richTextContent(w) || data?.content || config?.content || "";
  const heading = loc.richTextHeading(w);
  const subheading = loc.richTextSubheading(w);
  const { url: topMedallionUrl, rest: bodyContent } = extractMedallion(content);
  const hasWrittenContent = String(content).trim().length > 0;
  const panel = resolveWidgetPanel(config || {});
  const hasAccent = HEX.test(String(config?.chart_color || ""));
  const accent = hasAccent ? config.chart_color : undefined;
  const rgb = hasAccent ? hexToRgb(accent) : null;
  // Sdílený motiv — stejná paleta jako AradView (border, headerBg, captionBg…),
  // aby se po změně „barva grafu" textový widget zbarvil úplně stejně jako
  // grafy/tabulky vedle sebe v jedné řadě.
  const theme = hasAccent ? buildChartTheme(accent) : null;
  const panelStyle = {
    ...panel.style,
    ...(theme ? { borderColor: theme.border } : {}),
    // Jemný gradient přes celý panel — stejně jako u grafů (theme.bodyBg).
    // Kombinujeme s `panel.style.backgroundColor` tak, aby se obojí
    // překrývalo (gradient je polo-průhledný, takže prosvítá zvolené pozadí karty).
    ...(theme && !panel.style.backgroundColor
      ? { backgroundImage: theme.bodyBg }
      : {}),
  };
  const headerStyle = theme
    ? {
        background: theme.headerBg,
        borderColor: theme.border,
      }
    : {};
  return (
    <div
      className={`${panel.className} h-full text-left flex flex-col overflow-hidden min-w-0`}
      style={panelStyle}
    >
      {(heading || subheading || topMedallionUrl) && (
        <div
          className={`flex items-start gap-3 px-6 ${theme ? "py-4 border-b" : "pt-6 pb-2"} min-w-0`}
          style={headerStyle}
        >
          {topMedallionUrl && (
            <img
              src={topMedallionUrl}
              alt=""
              className="h-14 w-14 sm:h-16 sm:w-16 rounded-2xl object-cover shadow-sm shrink-0 bg-slate-100"
              style={{
                borderWidth: 1,
                borderStyle: "solid",
                borderColor: theme?.border || "hsl(var(--border) / 0.8)",
              }}
            />
          )}
          <div className="min-w-0 flex-1">
            {heading && (
              <h3
                className="font-serif text-[22px] leading-tight mb-1 text-normal-wrap"
                style={accent ? { color: accent } : undefined}
              >
                {heading}
              </h3>
            )}
            {subheading && (
              <div
                className="text-sm text-slate-500 text-normal-wrap"
                style={accent ? { color: accent, opacity: 0.85 } : undefined}
              >
                {subheading}
              </div>
            )}
          </div>
        </div>
      )}
      <div
        className="prose-compact px-6 py-5 text-[13.5px] leading-relaxed text-slate-700 space-y-3 text-left flex-1 min-h-0 overflow-y-auto text-normal-wrap"
        style={
          theme && rgb
            ? {
                // Jemné podbarvení textové oblasti, ať „kabát" zvolené barvy
                // pokrývá i samotný text a karta nepůsobí rozpůleně.
                backgroundColor: `rgb(${rgb.r} ${rgb.g} ${rgb.b} / 0.025)`,
              }
            : undefined
        }
      >
        {hasWrittenContent ? (
          renderBlocks(bodyContent)
        ) : (
          <div className="text-sm text-slate-400 italic">
            Zatím prázdný text. Použijte editor pro přidání textu, formátování a obrázků.
          </div>
        )}
      </div>
    </div>
  );
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderInline(text) {
  let t = escapeHtml(text);
  // Medailonek (komentátor) — před běžným obrázkem; malý kulatý výřez v řádku
  t = t.replace(/!\[(medailon|medailonek|medallion|avatar)\]\(([^)\s]+)\)/gi, (_m, _alt, url) => {
    return `<img src="${url}" alt="" class="inline-block align-middle h-9 w-9 sm:h-10 sm:w-10 rounded-full object-cover border border-border/80 shadow-sm mx-0.5 bg-slate-100" />`;
  });
  // Images ![alt](url)
  t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_m, alt, url) => {
    return `<img src="${url}" alt="${alt}" class="my-2 max-w-full rounded-sm border border-border" />`;
  });
  // Bold: **text** or __text__
  t = t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  t = t.replace(/__([^_]+)__/g, "<strong>$1</strong>");
  // Italic: *text* or _text_
  t = t.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>");
  t = t.replace(/(^|[^_])_([^_\n]+)_(?!_)/g, "$1<em>$2</em>");
  return t;
}

function renderBlocks(content) {
  const trimmed = String(content).trim();
  if (!trimmed) {
    return null;
  }
  const blocks = String(content).split(/\n\s*\n/);
  return blocks.map((block, i) => {
    const trimmed = block.trim();
    // Bare image URL on its own block.
    if (/^https?:\/\/\S+\.(png|jpe?g|gif|webp|svg)(\?\S*)?$/i.test(trimmed)) {
      return (
        <img
          key={i}
          src={trimmed}
          alt=""
          className="max-w-full rounded-sm border border-border"
        />
      );
    }
    // Allow raw <img> tag (admin pasted HTML snippet).
    if (/^<img\s/i.test(trimmed)) {
      return <div key={i} dangerouslySetInnerHTML={{ __html: trimmed }} />;
    }
    const html = renderInline(block).replace(/\n/g, "<br/>");
    return (
      <p
        key={i}
        className="text-left"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    );
  });
}
