import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import cs from "@/i18n/locales/cs.json";
import en from "@/i18n/locales/en.json";

export const LOCALE_STORAGE_KEY = "bankoapp:locale";
export const SUPPORTED_LOCALES = ["cs", "en"];
export const DEFAULT_LOCALE = "cs";

function readStoredLocale() {
  try {
    const raw = localStorage.getItem(LOCALE_STORAGE_KEY);
    if (raw && SUPPORTED_LOCALES.includes(raw)) return raw;
  } catch {
    /* ignore */
  }
  return DEFAULT_LOCALE;
}

function applyDocumentLang(locale) {
  if (typeof document !== "undefined") {
    document.documentElement.lang = locale;
  }
}

const initialLocale = readStoredLocale();
applyDocumentLang(initialLocale);

i18n.use(initReactI18next).init({
  resources: {
    cs: { translation: cs },
    en: { translation: en },
  },
  lng: initialLocale,
  fallbackLng: DEFAULT_LOCALE,
  interpolation: { escapeValue: false },
});

export function setAppLocale(locale) {
  const next = SUPPORTED_LOCALES.includes(locale) ? locale : DEFAULT_LOCALE;
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, next);
  } catch {
    /* ignore */
  }
  applyDocumentLang(next);
  void i18n.changeLanguage(next);
  return next;
}

export default i18n;
