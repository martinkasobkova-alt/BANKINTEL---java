/**
 * Sjednocení reklamního obrázku: legacy pole `image_url` + volitelné `slides`
 * (carusel). Používá AdWidget i AdConfigEditor.
 * V režimu „jeden obrázek“ se použije `image_url` (ne staré `slides` v DB).
 */
export function getAdImageSlides(cfg) {
  if (!cfg || typeof cfg !== "object") return [];
  if (!isAdCarouselMode(cfg)) {
    const u = String(cfg.image_url || "").trim();
    if (!u) return [];
    return [
      {
        image_url: u,
        link_url: String(cfg.link_url || "").trim(),
        alt: String(cfg.alt || "").trim(),
      },
    ];
  }
  const raw = cfg.slides;
  if (Array.isArray(raw) && raw.length) {
    const out = raw
      .map((s) => {
        if (!s || typeof s !== "object") return null;
        const image_url = String(s.image_url || "").trim();
        if (!image_url) return null;
        return {
          image_url,
          link_url: String(s.link_url || "").trim(),
          alt: String(s.alt || "").trim(),
        };
      })
      .filter(Boolean);
    if (out.length) return out;
  }
  const u = String(cfg.image_url || "").trim();
  if (!u) return [];
  return [
    {
      image_url: u,
      link_url: String(cfg.link_url || "").trim(),
      alt: String(cfg.alt || "").trim(),
    },
  ];
}

export function isAdCarouselMode(cfg) {
  return String(cfg?.image_mode || "single").toLowerCase() === "carousel";
}

export function adCarouselIntervalSec(cfg) {
  const n = Number(cfg?.carousel_interval_sec);
  if (Number.isFinite(n) && n >= 2 && n <= 60) return Math.floor(n);
  return 5;
}

/**
 * Ořez a „zoom“ reklamního obrázku (CSS object-fit / object-position + scale).
 * Uloží se do `widget.config` u typu `ad` (stejně jako u globálních slotů).
 */
export function getAdImageFraming(cfg) {
  if (!cfg || typeof cfg !== "object") {
    return {
      objectFit: "cover",
      objectPosition: "50% 50%",
      transform: undefined,
      transformOrigin: "50% 50%",
    };
  }
  const fitRaw = String(cfg.ad_image_object_fit || "cover").toLowerCase();
  const objectFit = fitRaw === "contain" ? "contain" : "cover";
  let x = Number(cfg.ad_image_pos_x);
  let y = Number(cfg.ad_image_pos_y);
  if (!Number.isFinite(x)) x = 50;
  if (!Number.isFinite(y)) y = 50;
  x = Math.min(100, Math.max(0, x));
  y = Math.min(100, Math.max(0, y));
  let zoom = Number(cfg.ad_image_zoom_pct);
  if (!Number.isFinite(zoom)) zoom = 100;
  zoom = Math.min(200, Math.max(100, zoom));
  const scale = zoom / 100;
  const objectPosition = `${x}% ${y}%`;
  const transformOrigin = `${x}% ${y}%`;
  return {
    objectFit,
    objectPosition,
    transformOrigin,
    transform: scale === 1 ? undefined : `scale(${scale})`,
  };
}
