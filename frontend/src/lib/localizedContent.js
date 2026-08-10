/** Výběr lokalizovaného textu z CMS polí (CS primární, EN volitelný fallback). */

export function normalizeAppLocale(locale) {
  const raw = String(locale || "").trim().toLowerCase();
  return raw.startsWith("en") ? "en" : "cs";
}

export function isEnglishLocale(locale) {
  return normalizeAppLocale(locale) === "en";
}

/**
 * @param {object|null|undefined} obj
 * @param {string} fieldBase — např. "name", "title", "caption" (bez _en)
 * @param {string} [locale]
 */
export function pickLocalized(obj, fieldBase, locale) {
  if (!obj || typeof obj !== "object") return "";
  const base = String(fieldBase || "").trim();
  if (!base) return "";
  const cs = String(obj[base] ?? "").trim();
  if (!isEnglishLocale(locale)) return cs;
  const enKey = `${base}_en`;
  const en = String(obj[enKey] ?? "").trim();
  return en || cs;
}

export function localizedHomepageTitle(page, locale) {
  return pickLocalized(page, "title", locale);
}

export function localizedHomepageSubtitle(page, locale) {
  return pickLocalized(page, "subtitle", locale);
}

export function localizedSectionName(section, locale) {
  return pickLocalized(section, "name", locale);
}

export function localizedSectionSubtitle(section, locale) {
  return pickLocalized(section, "subtitle", locale);
}

export function localizedSubpageTitle(page, locale) {
  return pickLocalized(page, "title", locale);
}

export function localizedWidgetTitle(widget, locale) {
  return pickLocalized(widget, "title", locale);
}

export function localizedConfigText(config, fieldBase, locale) {
  if (!config || typeof config !== "object") return "";
  return pickLocalized(config, fieldBase, locale);
}

/** Ruční popisek widgetu; EN fallback na CS. */
export function localizedWidgetCaption(widget, locale) {
  if (!widget || typeof widget !== "object") return "";
  const cfg = widget.config && typeof widget.config === "object" ? widget.config : {};
  const manual = localizedConfigText(cfg, "caption", locale);
  if (manual) return manual;
  return pickLocalized(widget, "ai_commentary", locale);
}

export function localizedKpiTitle(kpi, locale) {
  return pickLocalized(kpi, "title", locale);
}

/** Pro markdown widget — heading preferuje config.heading, jinak widget.title */
export function localizedRichTextHeading(widget, locale) {
  const cfg = widget?.config || {};
  const fromConfig = localizedConfigText(cfg, "heading", locale);
  if (fromConfig) return fromConfig;
  return localizedWidgetTitle(widget, locale);
}

export function localizedRichTextSubheading(widget, locale) {
  return localizedConfigText(widget?.config, "subheading", locale);
}

export function localizedRichTextContent(widget, locale) {
  const cfg = widget?.config || {};
  const en = isEnglishLocale(locale) ? String(cfg.content_en ?? "").trim() : "";
  if (en) return en;
  return String(cfg.content ?? "").trim();
}

/** Uloží EN pole do objektu (prázdný string = smazat klíč volitelně ponechat). */
export function withLocalizedField(obj, fieldBase, csValue, enValue) {
  const next = { ...(obj || {}) };
  next[fieldBase] = String(csValue ?? "").trim();
  const en = String(enValue ?? "").trim();
  const enKey = `${fieldBase}_en`;
  if (en) next[enKey] = en;
  else delete next[enKey];
  return next;
}

export function withLocalizedConfigField(config, fieldBase, csValue, enValue) {
  const cfg = { ...(config || {}) };
  cfg[fieldBase] = String(csValue ?? "").trim();
  const enKey = `${fieldBase}_en`;
  const en = String(enValue ?? "").trim();
  if (en) cfg[enKey] = en;
  else delete cfg[enKey];
  return cfg;
}

/** Serializace widgetu pro uložení do API — zachová volitelná EN pole. */
export function serializeWidgetForSave(w) {
  const out = {
    id: String(w?.id || ""),
    type: w?.type,
    title: String(w?.title ?? "").trim(),
    width: w?.width || "full",
    config: w?.config && typeof w.config === "object" ? w.config : {},
  };
  const titleEn = String(w?.title_en ?? "").trim();
  if (titleEn) out.title_en = titleEn;
  if (w?.rowSpan != null) out.rowSpan = w.rowSpan;
  if (w?.section_page_id) out.section_page_id = String(w.section_page_id);
  return out;
}
