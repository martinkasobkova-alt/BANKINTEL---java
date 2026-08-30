import axios from "axios";

/**
 * V development: výchozí je relativní /api (webpack devServer proxy → backend na 127.0.0.1:8000).
 * Vypnout: REACT_APP_DEV_PROXY=false (pak přímé volání, nutné CORS pro váš port).
 * Pro production: REACT_APP_BACKEND_URL na veřejnou URL API.
 */
const isDev = process.env.NODE_ENV === "development";
const devProxyOptOut = /^false|0$/i.test(String(process.env.REACT_APP_DEV_PROXY ?? "").trim());
const useDevProxy = isDev && !devProxyOptOut;
const prodSameOriginApiOptOut = /^false|0$/i.test(
  String(process.env.REACT_APP_PROD_SAME_ORIGIN_API ?? "").trim()
);
const useProdSameOriginApi = !isDev && !prodSameOriginApiOptOut;
const envBase =
  typeof process.env.REACT_APP_BACKEND_URL === "string"
    ? process.env.REACT_APP_BACKEND_URL.trim()
    : "";

/** V produkčním buildu CRA vloží REACT_APP_* jen při `npm run build` — musí být nastavené u Vercelu při tomto kroku. */
const reactAppBackendUrlSet = Boolean(envBase);

/**
 * Při přímém volání (bez proxy) – kvůli CORS a cookies konzistentně 127.0.0.1, ne produkce.
 *
 * Port 8000 tu zbyl po původním Python backendu; Java backend jede na 8080 (resp. na tom,
 * co říká REACT_APP_PROXY_TARGET). S REACT_APP_DEV_PROXY=false tak aplikace mířila na port,
 * kde nic neposlouchá, a dokumentovaná varianta „vypnout proxy" nefungovala.
 */
const DEFAULT_LOCAL_DEV_API =
  (typeof process.env.REACT_APP_PROXY_TARGET === "string"
    ? process.env.REACT_APP_PROXY_TARGET.trim()
    : "") || "http://127.0.0.1:8080";

let BASE;
let API_ROOT;

if (useDevProxy || useProdSameOriginApi) {
  API_ROOT = "/api";
  BASE = "";
} else {
  BASE = envBase || DEFAULT_LOCAL_DEV_API;
  API_ROOT = `${BASE.replace(/\/$/, "")}/api`;
}

/** Bez secrets — jen kde browser volá API (užitečné při řešení CORS vs chybějící env při buildu). */
export const API_DIAG = {
  nodeEnv: process.env.NODE_ENV,
  useDevProxy,
  useProdSameOriginApi,
  reactAppBackendUrlSet,
  BASE,
  apiRoot: API_ROOT,
  /** Axios default pro session cookies (Nutné na produkci pro cross-origin Render). */
  withCredentialsDefault: true,
};
export const API_ROOT_URL = API_ROOT;
if (typeof window !== "undefined") {
  // eslint-disable-next-line no-console
  console.info("[Bankoapp API]", API_DIAG);
}

/** Společný timeout pro api i pro raw /auth/refresh (bez něj může 401 interceptor viset donekonečna). */
export const API_TIMEOUT_MS = Number(process.env.REACT_APP_API_TIMEOUT_MS) || 360000;

/**
 * Auth a health volání musí selhat rychle. S obecným API_TIMEOUT_MS (6 minut) visel
 * `/auth/me` při zaseknuté dev proxy tak dlouho, že aplikace vypadala zamrzlá a uživatel
 * nedostal žádnou chybu. Tyhle endpointy jsou vždy rychlé, takže tvrdý strop nic nerozbije.
 */
export const AUTH_TIMEOUT_MS = Number(process.env.REACT_APP_AUTH_TIMEOUT_MS) || 20000;

/** Manager Explorer / deep search — bez pevného limitu (0 = axios neukončí požadavek). */
export const EXPLORE_LONG_REQUEST_TIMEOUT_MS =
  Number(process.env.REACT_APP_EXPLORE_TIMEOUT_MS) || 0;

export function isAxiosRequestAborted(err) {
  return err?.code === "ERR_CANCELED" || err?.name === "CanceledError";
}

export function isAxiosRequestTimeout(err) {
  if (isAxiosRequestAborted(err)) return false;
  const msg = String(err?.message || "");
  return err?.code === "ECONNABORTED" || /timeout/i.test(msg);
}

/** Max. doba pollování /explore/summarize/status (0 = bez limitu, dokud běží job). */
export const EXPLORE_SUMMARIZE_POLL_MAX_MS =
  Number(process.env.REACT_APP_EXPLORE_POLL_MAX_MS) || 0;

const api = axios.create({
  baseURL: API_ROOT,
  withCredentials: true,
  headers: { "Content-Type": "application/json" },
  // Bez timeoutu při neodpovídajícím backendu/proxy UI navždy „načítá“ (skeleton).
  timeout: API_TIMEOUT_MS,
});

/** CSRF (double submit): ne-HttpOnly cookie `csrf_token` + hlavička; jen mutace, ne localStorage. */
function readDocumentCookie(name) {
  if (typeof document === "undefined") return null;
  const m = document.cookie.match(new RegExp("(?:^|;\\s*)" + name + "=([^;]*)"));
  return m ? decodeURIComponent(m[1]) : null;
}

api.interceptors.request.use((config) => {
  // Krátký strop pro auth/health — viz AUTH_TIMEOUT_MS. Explicitní per-request timeout
  // (deep search, explore) má vždy přednost.
  if (config.timeout === undefined || config.timeout === API_TIMEOUT_MS) {
    const path = String(config.url || "").replace(API_ROOT, "");
    if (/^\/?(auth|health)(\/|$)/.test(path)) {
      config.timeout = AUTH_TIMEOUT_MS;
    }
  }
  const m = (config.method || "get").toString().toLowerCase();
  if (["post", "put", "patch", "delete"].includes(m)) {
    const t = readDocumentCookie("csrf_token");
    if (t) {
      config.headers = config.headers || {};
      config.headers["X-CSRF-Token"] = t;
    }
  }
  return config;
});

/**
 * POST s FormData: výchozí Content-Type z instance (application/json) by rozbil multipart;
 * natvrdo „multipart/form-data“ bez boundary také. Mažeme header → axios pak při
 * detekci FormData nechá prohlížeč/node doplnit `multipart/form-data; boundary=…`.
 *
 * V axios 1.x stačilo posílat hodnotu „false", ale chování není napříč verzemi
 * spolehlivé. Proto explicitně mažeme `Content-Type` (lower- i Pascal-case)
 * pomocí `transformRequest`, abychom přebili cokoli, co by ještě interceptor přidal.
 */
export function postFormData(url, formData, config) {
  return api.post(url, formData, {
    ...config,
    headers: { ...config?.headers, "Content-Type": undefined },
    transformRequest: [
      (data, headers) => {
        if (headers && typeof headers === "object") {
          if (typeof headers.delete === "function") {
            try {
              headers.delete("Content-Type");
            } catch  {
              /* AxiosHeaders ojediněle vyhazuje — bezpečně přepneme na manuální delete. */
            }
          }
          delete headers["Content-Type"];
          delete headers["content-type"];
        }
        return data;
      },
    ],
  });
}

// Handler registrovaný z AuthContext — zavolá se až když refresh selže
// (vypršený i refresh token / odhlášení).
let onUnauthorized = null;
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

let refreshInFlight = null;
function getRefreshToken() {
  if (!refreshInFlight) {
    const url = `${API_ROOT}/auth/refresh`;
    const t = readDocumentCookie("csrf_token");
    const headers = { "Content-Type": "application/json" };
    if (t) headers["X-CSRF-Token"] = t;
    refreshInFlight = axios
      .post(url, {}, { withCredentials: true, headers, timeout: AUTH_TIMEOUT_MS })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

api.interceptors.response.use(
  (r) => r,
  async (err) => {
    const status = err?.response?.status;
    const config = err?.config;
    if (status !== 401 || !config) {
      return Promise.reject(err);
    }

    const url = String(err.config?.url || "");
    // Neplatné heslo u loginu — není co obnovovat.
    if (/\/auth\/login/.test(url)) {
      return Promise.reject(err);
    }
    // Druhý pokus stále 401 (např. nemáte oprávnění) — pak teprve globální odhlášení.
    if (config._authRetry) {
      if (typeof onUnauthorized === "function") {
        onUnauthorized();
      }
      return Promise.reject(err);
    }

    try {
      await getRefreshToken();
    } catch (e) {
      if (e?.response?.status === 401 && typeof onUnauthorized === "function") {
        onUnauthorized();
      }
      // Ať catch v komponentách neukazuje technickou hlášku z /auth/refresh.
      if (e?.response?.data && typeof e.response.data === "object") {
        e.response.data.detail = "Vypršelo přihlášení — přihlas se prosím znovu.";
      }
      return Promise.reject(e);
    }

    config._authRetry = true;
    return api(config);
  }
);

/** Technické zprávy z /auth/refresh přemapujeme na srozumitelnou češtinu. */
function normalizeAuthDetail(detail) {
  if (detail == null) return detail;
  if (typeof detail !== "string") return detail;
  if (
    detail === "No refresh token" ||
    detail === "Refresh expired" ||
    detail === "Invalid refresh"
  ) {
    return "Vypršelo přihlášení — přihlas se prosím znovu.";
  }
  return detail;
}

const SAFE_API_DETAIL_FALLBACK = "Něco se pokazilo. Zkuste to prosím znovu.";

/**
 * Převod libovolného FastAPI/HTML/proxy `detail` na bezpečný řetězec (bez TypeError při podivné odpovědi).
 */
export function safeFormatApiDetail(detail, fallbackMessage) {
  const fb = fallbackMessage ?? SAFE_API_DETAIL_FALLBACK;
  try {
    if (detail == null) return fb;
    if (typeof detail === "function") {
      return "Neplatná struktura odpovědi ze serveru.";
    }
    if (typeof detail === "string") {
      const t = detail.trim();
      if (!t) return fb;
      // Proxy/gateway někdy vrátí HTML místo JSON — neparsuj jako text uživateli.
      if (/^[\s\r\n]*<!DOCTYPE/i.test(t) || /^[\s\r\n]*<html/i.test(t)) {
        return fb;
      }
      const auth = normalizeAuthDetail(t);
      if (typeof auth === "string" && auth.trim()) return auth.trim();
      return fb;
    }
    if (Array.isArray(detail)) {
      if (!detail.length) return fb;
      const parts = [];
      for (let i = 0; i < detail.length; i += 1) {
        const e = detail[i];
        try {
          if (e != null && typeof e === "object" && typeof e.msg === "string") {
            let locPref = "";
            if (Array.isArray(e.loc) && e.loc.length) {
              try {
                locPref = `${e.loc.map(String).join(".")}: `;
              } catch  {
                locPref = "";
              }
            }
            parts.push(`${locPref}${e.msg}`);
          } else if (typeof e === "string") {
            parts.push(e);
          } else {
            parts.push(JSON.stringify(e));
          }
        } catch  {
          parts.push("[Nepodařilo se načíst detail chyby.]");
        }
      }
      const out = parts.filter(Boolean).join(" ").trim();
      return out || fb;
    }
    if (typeof detail === "object") {
      const detailCs = typeof detail.detail_cs === "string" ? detail.detail_cs.trim() : "";
      if (detailCs) return detailCs;
      const messageCs = typeof detail.message_cs === "string" ? detail.message_cs.trim() : "";
      if (messageCs) return messageCs;
      if (detail.detail && typeof detail.detail === "object") {
        const nested = safeFormatApiDetail(detail.detail, "");
        if (nested) return nested;
      }
      const error = typeof detail.error === "string" ? detail.error.trim() : "";
      if (error) return error;
      const m =
        typeof detail.message === "string" ? detail.message.trim() : "";
      if (m) return /^[\s\r\n]*<!DOCTYPE/i.test(m) || /^[\s\r\n]*<html/i.test(m) ? fb : m;
      const mn = typeof detail.msg === "string" ? detail.msg.trim() : "";
      if (mn) return mn;
      try {
        const j = JSON.stringify(detail);
        return j && j !== "{}" ? j : fb;
      } catch  {
        return fb;
      }
    }
    return String(detail);
  } catch  {
    return fb;
  }
}

export function formatApiError(detail) {
  try {
    const normalized = normalizeAuthDetail(detail);
    return safeFormatApiDetail(normalized, SAFE_API_DETAIL_FALLBACK);
  } catch  {
    return SAFE_API_DETAIL_FALLBACK;
  }
}

/** Krátká zpráva pro UI — bez opakování technických řetězců o CORS_ORIGINS v konzoli. */
export const API_FAILURE_CORS_OR_NETWORK =
  "Server není dostupný nebo blokuje požadavek z této domény.";

function networkFailureDeveloperHint() {
  if (useDevProxy) {
    return (
      "Zkontrolujte backend a REACT_APP_PROXY_TARGET v frontend/.env " +
      "(výchozí http://127.0.0.1:8000)."
    );
  }
  const prod = process.env.NODE_ENV === "production";
  if (prod && !reactAppBackendUrlSet) {
    return `Chybí REACT_APP_BACKEND_URL při buildu na Vercelu; fallback ${DEFAULT_LOCAL_DEV_API}. [backendBase="${BASE}", apiRoot="${API_ROOT}"]`;
  }
  if (prod && reactAppBackendUrlSet) {
    return `Ověřte CORS_ORIGINS na API a dostupnost serveru. [backendBase="${BASE}", apiRoot="${API_ROOT}"]`;
  }
  return `Nastavte REACT_APP_BACKEND_URL nebo REACT_APP_DEV_PROXY=true. [backendBase="${BASE}", apiRoot="${API_ROOT}"]`;
}

export function formatApiErrorFromAxios(err) {
  try {
    return _formatApiErrorFromAxiosInner(err);
  } catch  {
    return "Nepodařilo se zpracovat chybu odpovědi. Zkuste to prosím znovu.";
  }
}

/**
 * Jednotný tvar chyby pro komponenty (bez vyhazování výjimky).
 * @returns {{ ok: false, status: number|null, message: string, details: string|null, isCanceled: boolean, isCorsOrNetwork: boolean }}
 */
export function normalizeApiFailure(err) {
  const status = typeof err?.response?.status === "number" ? err.response.status : null;
  const isCanceled =
    err?.code === "ERR_CANCELED" ||
    err?.name === "CanceledError" ||
    /canceled|cancell?ed/i.test(String(err?.message || ""));
  if (isCanceled) {
    return {
      ok: false,
      status,
      message: "",
      details: null,
      isCanceled: true,
      isCorsOrNetwork: false,
    };
  }
  const hasResponse = Boolean(err?.response);
  const msg = String(err?.message || "");
  const code = err?.code;
  const isCorsOrNetwork =
    !hasResponse &&
    (code === "ERR_NETWORK" ||
      code === "ECONNABORTED" ||
      code === "ECONNRESET" ||
      /timeout|network error|network|failed to fetch/i.test(msg));
  const message = formatApiErrorFromAxios(err);
  const details = isCorsOrNetwork ? networkFailureDeveloperHint() : null;
  return {
    ok: false,
    status,
    message,
    details,
    isCanceled: false,
    isCorsOrNetwork,
  };
}

function _formatApiErrorFromAxiosInner(err) {
  const status = err?.response?.status;
  const data = err?.response?.data;
  if (data && typeof data === "object") {
    if (typeof data.detail_cs === "string" && data.detail_cs.trim()) {
      return data.detail_cs.trim();
    }
    if (
      typeof status === "number" &&
      status === 503 &&
      data.detail &&
      typeof data.detail === "object" &&
      typeof data.detail.message === "string" &&
      String(data.detail.message).trim()
    ) {
      return String(data.detail.message).trim();
    }
    if (status === 422 && Array.isArray(data.detail) && data.detail.length) {
      return safeFormatApiDetail(data.detail, SAFE_API_DETAIL_FALLBACK);
    }
    if (data.detail && typeof data.detail === "object") {
      const nested = safeFormatApiDetail(data.detail, "");
      if (nested) return nested;
    }
    const de = typeof data.detail === "string" ? data.detail : "";
    if (typeof data.detail === "string" && de.includes("CSRF validation failed")) {
      return "Bezpečnostní ověření požadavku selhalo (CSRF). Zkuste obnovit stránku nebo kontaktovat správce.";
    }
    if (
      typeof de === "string" &&
      de.trim() &&
      typeof status === "number" &&
      status !== 401
    ) {
      return formatApiError(data.detail);
    }
  }
  if (data && typeof data === "object" && typeof data.error === "string") {
    const src = data.source ? `${data.source}: ` : "";
    return `${src}${data.error}`;
  }
  if (err?.response?.data?.detail != null) return formatApiError(err.response.data.detail);
  if (!err?.response) {
    if (isAxiosRequestAborted(err)) {
      return "";
    }

    const msg = String(err?.message || "");
    const code = err?.code;

    const isNetworkHint =
      code === "ERR_NETWORK" ||
      code === "ECONNABORTED" ||
      code === "ECONNRESET" ||
      /timeout/i.test(msg) ||
      /network error/i.test(msg) ||
      /network/i.test(msg) ||
      /failed to fetch/i.test(msg);

    if (isNetworkHint) {
      if (isAxiosRequestTimeout(err)) {
        return "Požadavek vypršel, spusťte analýzu znovu";
      }
      if (process.env.NODE_ENV === "development") {
        const dev = networkFailureDeveloperHint();
        return dev ? `${API_FAILURE_CORS_OR_NETWORK} (${dev})` : API_FAILURE_CORS_OR_NETWORK;
      }
      return API_FAILURE_CORS_OR_NETWORK;
    }

    return msg || "Něco se pokazilo. Zkuste to prosím znovu.";
  }
  return formatApiError(err.response?.data?.detail);
}

/**
 * Jednoznačný klíč pro deduplikaci položek z /catalog/deep-search napříč dávkovanými voláními.
 */
export function catalogDeepSearchRowDedupeKey(row) {
  const cid = String(row?.catalog_id ?? "").trim().toLowerCase();
  const sid = String(row?.set_id ?? row?.series_id ?? "").trim();
  return `${cid}|${sid}`;
}

const DEEP_MERGE_ORDER = [
  "arad",
  "csu",
  "eurostat",
  "openai_plan",
  "fred_remote",
  "ecb",
  "data360",
  "bis",
  "imf",
  "oecd",
];

/**
 * Sloučení více strukturovaných odpovědí hloubkového hledání (rozšířený režim — po jedné databázi).
 * @param {Array<Record<string, unknown>>} payloads
 */
export function mergeCatalogDeepSearchPartials(payloads) {
  const parts = Array.isArray(payloads) ? payloads.filter((p) => p && typeof p === "object") : [];
  if (!parts.length) return null;

  const seenVerified = new Set();
  const seenPossible = new Set();
  /** @type {unknown[]} */
  const verified = [];
  /** @type {unknown[]} */
  const possible = [];

  function appendUnique(dst, row, seen) {
    if (!row || typeof row !== "object") return;
    const k = catalogDeepSearchRowDedupeKey(row);
    if (!k || k === "|" || seen.has(k)) return;
    seen.add(k);
    dst.push(row);
  }

  /** @type {Record<string, unknown>} */
  const statusBySrc = {};
  /** @type {Record<string, unknown>} */
  const sourcesAgg = {};
  const cw = [];

  /** @type {unknown} */
  let plan = null;
  let aiPlanUsed = false;
  /** @type {string[]} */
  let suggested = [];

  for (const p of parts) {
    const ss = Array.isArray(p.source_statuses) ? p.source_statuses : [];
    for (const st of ss) {
      const sid = String(st.source || "").toLowerCase();
      if (sid) statusBySrc[sid] = st;
    }
    const smRaw = p.sources != null && typeof p.sources === "object" && !Array.isArray(p.sources) ? p.sources : {};
    for (const k of Object.keys(smRaw)) {
      if (Object.prototype.hasOwnProperty.call(smRaw, k)) sourcesAgg[k] = smRaw[k];
    }

    const vIn = Array.isArray(p.verified) ? p.verified : [];
    const pIn = Array.isArray(p.possible) ? p.possible : [];
    const resIn = Array.isArray(p.results) ? p.results : [];
    if ((!vIn.length && !pIn.length) && resIn.length) {
      for (const r of resIn) {
        if (!r || typeof r !== "object") continue;
        const st = typeof r.status === "string" ? r.status.toLowerCase() : "";
        const tier = typeof r.result_tier === "string" ? r.result_tier.toLowerCase() : "";
        const isPoss =
          st === "candidate" ||
          st === "unverified" ||
          tier === "candidate" ||
          tier === "catalog_match" ||
          tier === "possible" ||
          tier === "unverified";
        if (isPoss) {
          const dk = catalogDeepSearchRowDedupeKey(r);
          if (seenVerified.has(dk)) continue;
          appendUnique(possible, r, seenPossible);
        } else {
          appendUnique(verified, r, seenVerified);
        }
      }
    } else {
      for (const r of vIn) appendUnique(verified, r, seenVerified);
      for (const r of pIn) {
        const dk = catalogDeepSearchRowDedupeKey(r);
        if (seenVerified.has(dk)) continue;
        appendUnique(possible, r, seenPossible);
      }
    }

    cw.push(...(Array.isArray(p.catalog_index_warnings) ? p.catalog_index_warnings : []));
    if (Array.isArray(p.warnings)) {
      for (const w of p.warnings) {
        if (w != null && String(w).trim()) cw.push(String(w).trim());
      }
    }
    if (plan == null && (p.plan != null || p.search_plan != null)) {
      plan = p.search_plan ?? p.plan;
    }
    aiPlanUsed = aiPlanUsed || Boolean(p.ai_plan_used || p.ai_active);
    if (Array.isArray(p.suggested_queries) && p.suggested_queries.length && suggested.length === 0) {
      suggested = [...p.suggested_queries];
    }
  }

  const uniqCw = [...new Set(cw.map(String).filter(Boolean))];

  /** @type {unknown[]} */
  const orderedStatuses = [];
  const seenOrd = new Set();
  for (const id of DEEP_MERGE_ORDER) {
    const st = statusBySrc[id];
    if (st) {
      orderedStatuses.push(st);
      seenOrd.add(id);
    }
  }
  for (const id of Object.keys(statusBySrc)) {
    if (!seenOrd.has(id)) orderedStatuses.push(statusBySrc[id]);
  }

  const hadRows = verified.length > 0 || possible.length > 0;
  const anyPartialPayload = parts.some((p) => p.partial === true);
  const universalFail =
    parts.length > 0 &&
    parts.every((p) => p.ok === false) &&
    !hadRows &&
    !parts.some((p) => p.partial === true);
  const combinedOk = !universalFail;
  const combinedPartial =
    anyPartialPayload || (hadRows && parts.some((p) => p.ok === false));

  /** @type {Record<string, unknown>} */
  const base = parts.length === 1 ? { ...parts[0] } : { ...parts[0] };

  Object.assign(base, {
    ok: combinedOk,
    partial: combinedPartial,
    verified,
    possible,
    source_statuses: orderedStatuses,
    sources: sourcesAgg,
    catalog_index_warnings: uniqCw,
    search_plan: plan,
    plan,
    ai_plan_used: aiPlanUsed,
    suggested_queries: suggested,
  });

  if (uniqCw.length) base.warnings = uniqCw;

  /** Vrstva „odpověď asistenta“ — zvolíme část s nejvíc ověřenými řadami (ne slepě poslední chunk). */
  let aiResultLayer = null;
  let aiLayerScore = -1;
  for (const p of parts) {
    if (!p || typeof p !== "object") continue;
    if (p.ai_result_layer == null || typeof p.ai_result_layer !== "object") continue;
    const ans = String(p.ai_result_layer.answer_cz || "").trim();
    if (!ans) continue;
    const v = Array.isArray(p.verified) ? p.verified.length : 0;
    const po = Array.isArray(p.possible) ? p.possible.length : 0;
    const sc = v * 20 + po;
    if (sc > aiLayerScore) {
      aiLayerScore = sc;
      aiResultLayer = p.ai_result_layer;
    }
  }
  if (aiResultLayer != null) base.ai_result_layer = aiResultLayer;

  return base;
}

export { BASE, API_ROOT };
export default api;
