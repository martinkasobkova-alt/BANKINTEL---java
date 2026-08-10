export function getDateLocale(language) {
  return language?.startsWith("en") ? "en-GB" : "cs-CZ";
}

export function formatLocaleDate(iso, language, options = { day: "numeric", month: "long", year: "numeric" }) {
  if (!iso) return "";
  try {
    return new Date(iso).toLocaleDateString(getDateLocale(language), options);
  } catch {
    return "";
  }
}
