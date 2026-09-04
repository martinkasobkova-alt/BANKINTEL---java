import {
  CATALOGS,
  CATALOGS_DEFAULT_SELECTED_IDS,
  catalogDefMatchesSourceId,
  normalizeCatalogSourceId,
} from "@/lib/catalogDefinitions";
import {
  GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS,
  CATALOG_SOURCE_STATUS_MAP,
} from "@/lib/catalogBrowseStatusRegistry";
export const IMF_BROWSE_BETA_UNAVAILABLE_CZ =
  "Katalog IMF je zatím beta. Upstream IMF může timeoutovat nebo strom nemusí být dostupný. Použijte AI asistenta / přímý výběr ověřené databáze, nebo zkuste později.";
export const IMF_PREVIEW_UNAVAILABLE_CZ =
  "Tuto IMF řadu se nepodařilo ověřit živým náhledem. Nejde o potvrzenou funkční řadu v aplikaci: může chybět IMF_API_KEY, IMF upstream může odmítnout SDMX dotaz, nebo položka z tematického katalogu nemá dostupná data pro aktuální filtr.";
export const OECD_BROWSE_BETA_UNAVAILABLE_CZ =
  "Katalog OECD je zatím beta. Některé dataflow se načítají dlouho nebo nejsou dostupné přes tento browse strom. Použijte ověřený seed OECD.SDD.STES / KEI nebo AI asistenta.";
export const CATALOG_EMPTY_BROWSE_CZ =
  "Katalog se načetl, ale neobsahuje žádné položky pro procházení.";

export const CATALOG_DEEP_SEARCH_CHUNK_TIMEOUT_MS =
  Number(process.env.REACT_APP_DEEP_SEARCH_CHUNK_TIMEOUT_MS) || 120000;

export const EUROSTAT_DEEP_AI_HINT_HAS_REF_CZ =
  "AI našla odpovídající položku v katalogu Eurostatu. Přidání použije stejný postup jako při ručním výběru v katalogu.";
export const EUROSTAT_DEEP_AI_OPEN_CATALOG_DIMS_CZ =
  "Tato položka Eurostatu vyžaduje výběr konkrétních dimenzí. Otevřete ji v katalogu a vyberte konkrétní řadu.";
export const EUROSTAT_DEEP_AI_PREVIEW_UNAVAILABLE_CZ =
  "Náhled dat není u této položky dostupný. Zdroj můžete přidat nebo otevřít v katalogu.";

export const INTERNAL_DEEP_SEARCH_DEF = Object.freeze({
  id: "internal",
  label: "Interní data",
  catalogPath: "",
  addPath: "",
  sourceType: "internal",
  tier: "production",
  usableForAiSearch: false,
});

export const IN_APP_SEARCH_ID = "app";
export const IN_APP_SEARCH_DEF = Object.freeze({
  id: IN_APP_SEARCH_ID,
  label: "V aplikaci",
  catalogPath: "",
  addPath: "",
  sourceType: "app",
  tier: "production",
  usableForAiSearch: false,
  appSearch: true,
});
export const CATALOG_SEARCH_FILTER_OPTIONS = Object.freeze([IN_APP_SEARCH_DEF, ...CATALOGS]);

export function resolveDeepSearchCatalogDef(cand) {
  const sourceIds = [
    cand?.catalog_id,
    cand?.source_type,
    cand?.source,
  ].map(normalizeCatalogSourceId).filter(Boolean);
  if (sourceIds.includes("internal")) return INTERNAL_DEEP_SEARCH_DEF;
  return CATALOGS.find((d) => sourceIds.some((sid) => catalogDefMatchesSourceId(d, sid)));
}

export const CATALOG_BROWSE_FETCH_CONCURRENCY = 4;

export function isCategoryLikeSearchHit(hit) {
  const h = hit && typeof hit === "object" ? hit : {};
  const row = h.row && typeof h.row === "object" ? h.row : {};
  const badge = String(h.result_badge || "").trim().toLowerCase();
  const kind = String(row.kind || "").trim().toLowerCase();
  const itemKind = String(row.item_kind || "").trim().toLowerCase();
  if (badge === "category") return true;
  if (kind === "cat" || kind === "category") return true;
  if (itemKind === "cat" || itemKind === "category") return true;
  if (itemKind.includes("category")) return true;
  return false;
}

export const DEEP_SOURCE_ORDER = [
  "arad", "csu", "eurostat", "openai_plan", "ai_data_resolver",
  "fred_remote", "fred", "ecb2", "ecb", "data360", "bis", "imf", "oecd4", "oecd",
];

export const GENERIC_AI_INDEX_NO_ROWS_CZ =
  "V připravených AI indexech nebyly pro tento dotaz nalezeny žádné vhodné řady.";
export const EUROSTAT_QUERY_REFINEMENT_HINT_CZ =
  "U Eurostatu pomáhá dotaz zpřesnit kombinací ukazatele, země, frekvence, jednotky nebo kódu datasetu.";

export function buildBrowseErrorTechnicalLines(def, axiosTimeoutMs, err) {
  const uiMs = err?.browseUiTimeoutMs ?? GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS;
  const lines = [];
  lines.push(`endpoint GET: ${String(def.catalogPath || "")}`);
  lines.push(`axios timeout (GET): ${axiosTimeoutMs} ms`);
  lines.push(`celkový limit UI (HTTP + zploštění): ${uiMs} ms`);
  if (err?.browseTotalUiTimeout) lines.push(`vypršení rozhraní: ${uiMs} ms (celý blok načtení)`);
  if (err?.browseFlattenGuard) lines.push("zploštění v prohlížeči: překročen vnitřní bezpečnostní limit");
  const code = err?.code;
  if (code != null && String(code).length) lines.push(`axios/síť kód: ${String(code)}`);
  if (typeof err?.message === "string" && err.message && err.message !== "browse total ui timeout") {
    lines.push(`zpráva: ${err.message}`);
  }
  return `Technické detaily:\n${lines.map((l) => `· ${l}`).join("\n")}`;
}

export function browseLoadingPrimaryLabel(def) {
  const st = CATALOG_SOURCE_STATUS_MAP[def?.id];
  const uiCap = def?.browseUiTimeoutMs ?? GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS;
  const extendedUi =
    Number.isFinite(def?.browseUiTimeoutMs) &&
    def.browseUiTimeoutMs > GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS;
  const slow =
    st &&
    (extendedUi ||
      st.browse_supported === "partial" ||
      st.browse_supported === "partial_seed" ||
      st.browse_supported === "tree_beta" ||
      st.browse_supported === "sdmx_dimensions" ||
      st.browse_supported === "limited_tree_plus_proxy_search");
  if (slow) return `Ověřuji strom ${def.label}… (max ${Math.round(uiCap / 1000)} s)`;
  return `Načítám katalog ${def.label}…`;
}

export function browseLoadingSubtextCz(def) {
  const st = CATALOG_SOURCE_STATUS_MAP[def?.id];
  if (st?.browse_supported === "none") {
    return "Globální strom podle jednotného modelu tu pro tento zdroj použitelný není — podrobnosti v kartě níže.";
  }
  if (st?.browse_supported === "partial" || st?.browse_supported === "partial_seed" || st?.browse_supported === "tree_beta") {
    return "Tento zdroj zatím nemá spolehlivý globální browse strom. Po limitu času uvidíte chybu nebo prázdný stav — použijte dedikovaný katalog níže nebo AI vyhledávání.";
  }
  if (st?.browse_supported === "limited" || st?.browse_supported === "sdmx_dimensions" || st?.browse_supported === "limited_tree_plus_proxy_search") {
    return "Struktura odpovídá vlastnímu API modelu (ne jednotný strom řad); u složitých toků použijte dedikovanou stránku.";
  }
  return null;
}

export const FALLBACK_LINK_LABELS_CZ = {
  fred: "Otevřít FRED katalog",
  worldbank: "Otevřít World Bank katalog",
  data360: "Otevřít World Bank katalog",
  bis: "Otevřít BIS katalog",
  imf: "Otevřít IMF katalog",
  imf2: "Otevřít IMF 2 katalog",
  oecd: "Otevřít OECD katalog",
  oecd2: "Otevřít OECD 2 katalog",
  oecd4: "Otevřít OECD katalog",
  commodities: "Otevřít katalog komodit",
  ecb: "Otevřít ECB katalog",
  ecb2: "Otevřít ECB katalog",
};

export const DEFAULT_CLASSIC_SEARCH_CATALOG_ID =
  CATALOGS.find((c) => CATALOGS_DEFAULT_SELECTED_IDS.includes(c.id))?.id ?? CATALOGS[0]?.id ?? "";

export const CATALOG_REFINE_SEARCH_TERMS = [
  "najdi", "hledej", "vyhledej", "find ", "search ", "alternativ", "zpřesni", "zpresni",
  "jiné řady", "jine rady", "další řad", "dalsi rad", "jen eurostat", "only eurostat",
  " v bis", " v eurostat", " v ecb", " v imf", " v oecd", " v čnb", " v cnb", " v arad",
];

export function looksLikeCatalogRefineSearch(text) {
  const lower = String(text || "").trim().toLowerCase();
  if (!lower) return false;
  return CATALOG_REFINE_SEARCH_TERMS.some((t) => lower.includes(t));
}

export const CHAT_FOLLOWUP_PREFIXES = [
  "jak ", "proč ", "proc ", "kolik ", "kdy ", "kde ", "co ", "kdo ", "jaký ", "jaká ", "jaké ",
  "jaky ", "jaka ", "jake ", "je ", "jsou ", "byl ", "bylo ", "byly ", "má ", "mají ",
  "maji ", "přidej ", "pridej ", "porovnej ", "srovnej ", "spočítej ", "spocitej ",
  "vysvětli ", "vysvetli ", "ukáž ", "ukaz ", "zobraz ", "vypiš ", "vypis ", "řekni ",
  "rekni ", "dej mi ", "ukaž ", "ukaz ", "co je ", "what ", "how ", "why ", "when ",
  "přidej", "pridej", "spočítej", "spocitej", "vysvětli", "vysvetli",
];

export function looksLikeTopicSearch(text) {
  const trimmed = String(text || "").trim();
  const lower = trimmed.toLowerCase();
  if (!lower || lower.length < 2) return false;
  if (CHAT_FOLLOWUP_PREFIXES.some((p) => lower.startsWith(p))) return false;
  if (trimmed.includes("?")) return false;
  return trimmed.split(/\s+/).length <= 5;
}

export function resolveFollowupActionHint(text, selectedCount, suggestedActions = []) {
  const trimmed = String(text || "").trim();
  const fromSuggestion = (suggestedActions || []).find(
    (x) => String(x?.prompt_cz || "").trim() === trimmed,
  );
  if (fromSuggestion?.kind) return String(fromSuggestion.kind).trim().toLowerCase();
  const lower = trimmed.toLowerCase();
  if (looksLikeCatalogRefineSearch(trimmed)) return "refine_search";
  if (Number(selectedCount) <= 0) return "";
  if (!trimmed) return "compare_selected";
  const compareTerms = ["srovnej", "porovnej", "compare", "analyzuj", "analyze", "vybrané řady", "vybrane rady", "selected series", "tyto řady", "tyto rady"];
  if (compareTerms.some((t) => lower.includes(t))) return "compare_selected";
  return "";
}
