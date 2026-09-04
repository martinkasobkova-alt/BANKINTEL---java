import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ChevronDown, PanelLeftOpen, Search, Sparkles } from "lucide-react";
import { toast } from "sonner";
import api from "@/lib/api";
import CatalogHeaderFiltersMenu from "@/components/layout/CatalogHeaderFiltersMenu";
import {
  CATALOG_HEADER_FILTERS_EVENT,
  isStockSearchSelected,
  loadCatalogHeaderFilters,
} from "@/lib/catalogHeaderFilters";
import VoiceInputButton from "@/components/common/VoiceInputButton";
import { getCatalogBrowseDropdownLabel } from "@/lib/catalogBrowseStatusRegistry";
import { buildCatalogChartPageShareUrl } from "@/lib/catalogChartShare";
import {
  AI_SCOPE_ALL,
  AI_SCOPE_SELECTED,
  buildCatalogHubSearchUrl,
  buildCatalogHubScopeUrl,
  CATALOG_HUB_PATH,
  dispatchCatalogHeaderBrowseToggle,
  HEADER_CATALOG_OPTIONS,
  parseCatalogHeaderFromLocation,
  readHeaderAiScope,
  writeHeaderAiScope,
} from "@/lib/catalogHeaderSearch";

// Náš zdroj `oecd` mapuje na header scope `oecd4`; ostatní zdroje mají stejné id.
const SOURCE_TO_SCOPE = { oecd: "oecd4" };
const scopeForSource = (s) => SOURCE_TO_SCOPE[s] || s;

/**
 * Globální katalogové hledání v horní liště — navigace na /search/catalog.
 * Při psaní (debounced) ukazuje našeptávač přes všechny zdroje (GET /api/catalog/suggest).
 */
const STOCKS_SEARCH_PATH = "/search/stocks";

export default function AppShellCatalogSearchBar({ className = "", inputClassName = "" }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const onCatalogHub = location.pathname === CATALOG_HUB_PATH;
  /**
   * Stránka Akcie má vlastní backend (Yahoo/Alpha Vantage) i vlastní podobu výsledků.
   * Enter v horním poli odsud dřív odnavigoval do katalogu, takže na Akciích muselo být
   * druhé hledací pole jen proto, aby se dalo hledat a zůstat na stránce. Dvě pole vedle
   * sebe mátla; tady se místo toho horní pole přizpůsobí stránce, na které stojíš.
   */
  const onStocksPage = location.pathname.startsWith(STOCKS_SEARCH_PATH);
  /** Akcie ve filtru: když si je uživatel odškrtne, našeptávač je nemá proč tahat. */
  const [stocksSelected, setStocksSelected] = useState(() =>
    isStockSearchSelected(loadCatalogHeaderFilters().selectedIds),
  );
  useEffect(() => {
    const apply = (prefs) =>
      setStocksSelected(isStockSearchSelected((prefs || loadCatalogHeaderFilters()).selectedIds));
    const onHeaderFilters = (event) => apply(event?.detail);
    window.addEventListener(CATALOG_HEADER_FILTERS_EVENT, onHeaderFilters);
    return () => window.removeEventListener(CATALOG_HEADER_FILTERS_EVENT, onHeaderFilters);
  }, []);

  const parsedFromUrl = useMemo(
    () => (onCatalogHub ? parseCatalogHeaderFromLocation(location.search) : null),
    [onCatalogHub, location.search],
  );

  const [query, setQuery] = useState("");
  const [aiScope, setAiScope] = useState(() => readHeaderAiScope());
  const aiScopeRef = useRef(aiScope);

  // Našeptávač
  const [suggestions, setSuggestions] = useState([]);
  // Akcie mají vlastní backend (/stocks/search) - dotazujeme paralelně, ale nikdy neslučujeme
  // do jednoho žebříčku s katalogovými návrhy; zobrazují se jako samostatná skupina "Akcie".
  const [stockSuggestions, setStockSuggestions] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [focused, setFocused] = useState(false);
  const boxRef = useRef(null);
  const abortRef = useRef(null);
  const suppressFetchRef = useRef(false);
  // Uživatel dropdown explicitně zavřel (Escape / klik mimo) - pozdější, pomalejší odpověď
  // (typicky /stocks/search) ho pak nesmí sama znovu otevřít.
  const dismissedRef = useRef(false);
  // Po kliknutí na typeahead-návrh navigate() změní URL → parsedFromUrl se přepočítá →
  // useEffect by znovu sync-oval input ze staré URL query. Tento ref blokuje jeden sync.
  const suppressUrlSyncRef = useRef(false);

  useEffect(() => {
    aiScopeRef.current = aiScope;
  }, [aiScope]);

  // Na Akciích drží dotaz URL (?q=), takže pole musí ukazovat to, co je právě vyhledané —
  // jinak by po zrušení druhého pole zůstalo prázdné nad plnými výsledky.
  useEffect(() => {
    if (!onStocksPage) return;
    if (suppressUrlSyncRef.current) {
      suppressUrlSyncRef.current = false;
      return;
    }
    const q = new URLSearchParams(location.search).get("q") || "";
    if (!q) return;
    suppressFetchRef.current = true;
    setQuery((prev) => (q !== prev ? q : prev));
  }, [onStocksPage, location.search]);

  useEffect(() => {
    if (!onCatalogHub || !parsedFromUrl) return;
    if (suppressUrlSyncRef.current) {
      suppressUrlSyncRef.current = false;
      return;
    }
    suppressFetchRef.current = true; // sync z URL nesmí otevřít našeptávač
    setQuery((prev) => (parsedFromUrl.q && parsedFromUrl.q !== prev ? parsedFromUrl.q : prev));
    aiScopeRef.current = parsedFromUrl.aiScope;
    setAiScope(parsedFromUrl.aiScope);
  }, [onCatalogHub, parsedFromUrl]);

  // Debounced našeptávač (jen když je input aktivní a min. 2 znaky).
  useEffect(() => {
    const q = String(query || "").trim();
    if (suppressFetchRef.current) {
      suppressFetchRef.current = false;
      return;
    }
    if (!focused || q.length < 2) {
      setSuggestions([]);
      setStockSuggestions([]);
      setShowDropdown(false);
      return;
    }
    const timer = window.setTimeout(() => {
      if (abortRef.current) abortRef.current.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      dismissedRef.current = false;

      // Katalog (~1-70ms, lokální FTS) a akcie (~30-280ms, externí Yahoo Finance API) mají
      // oddělené backendy s velmi odlišnou latencí - dotazujeme je NEZÁVISLE, každý zobrazí
      // svou skupinu hned, jakmile je hotový, aby pomalejší akciové API nikdy nezpomalilo běžné
      // katalogové našeptávání. Nikdy se neslučují do jednoho žebříčku (viz vykreslení níže).
      api
        .get("/catalog/suggest", { params: { q, limit: 8 }, signal: controller.signal, timeout: 6000 })
        .then(({ data }) => {
          const items = Array.isArray(data?.suggestions) ? data.suggestions : [];
          setSuggestions(items);
          setActiveIndex(-1);
          if (!dismissedRef.current) setShowDropdown((prev) => prev || items.length > 0);
        })
        .catch((err) => {
          if (err?.name !== "CanceledError" && err?.code !== "ERR_CANCELED") setSuggestions([]);
        });

      if (!stocksSelected) {
        setStockSuggestions([]);
      } else {
        api
        .post("/stocks/search", { query: q, limit: 4, allow_llm: false }, { signal: controller.signal, timeout: 6000 })
        .then(({ data }) => {
          const stockItems = Array.isArray(data?.results) ? data.results : [];
          setStockSuggestions(stockItems);
          if (!dismissedRef.current) setShowDropdown((prev) => prev || stockItems.length > 0);
        })
        .catch((err) => {
          if (err?.name !== "CanceledError" && err?.code !== "ERR_CANCELED") setStockSuggestions([]);
        });
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query, focused, stocksSelected]);

  // Zavřít našeptávač při kliku mimo.
  useEffect(() => {
    if (!showDropdown) return undefined;
    const onDocMouseDown = (e) => {
      if (boxRef.current && !boxRef.current.contains(e.target)) {
        dismissedRef.current = true;
        setShowDropdown(false);
      }
    };
    document.addEventListener("mousedown", onDocMouseDown);
    return () => document.removeEventListener("mousedown", onDocMouseDown);
  }, [showDropdown]);

  const handleAiScopeChange = useCallback(
    (nextScope) => {
      const scope = String(nextScope || AI_SCOPE_ALL);
      aiScopeRef.current = scope;
      setAiScope(scope);
      writeHeaderAiScope(scope);
      if (!onCatalogHub) return;
      if (scope !== AI_SCOPE_ALL && scope !== AI_SCOPE_SELECTED) {
        // Přepnout na jeden zdroj — aktualizovat scope= v URL
        navigate(buildCatalogHubScopeUrl({ catalogId: scope, currentSearch: location.search }), {
          replace: true,
        });
      } else {
        // Přepnout na vše/výběr — vymazat scope= z URL
        const sp = new URLSearchParams(String(location.search || "").replace(/^\?/, ""));
        sp.delete("scope");
        sp.delete("aiScope");
        if (scope === AI_SCOPE_SELECTED) sp.set("aiScope", "selected");
        const qs = sp.toString();
        navigate(`${CATALOG_HUB_PATH}${qs ? `?${qs}` : ""}`, { replace: true });
      }
    },
    [location.search, navigate, onCatalogHub],
  );

  const openCatalogBrowse = useCallback(
    (event) => {
      event?.preventDefault?.();
      event?.stopPropagation?.();
      if (onCatalogHub) {
        dispatchCatalogHeaderBrowseToggle();
        return;
      }
      navigate(CATALOG_HUB_PATH, { state: { openBrowse: true } });
    },
    [navigate, onCatalogHub],
  );

  const navigateToSearch = useCallback(
    (q, scopeOverride) => {
      const resolvedScope = scopeOverride
        ? scopeForSource(String(scopeOverride).toLowerCase())
        : aiScopeRef.current || AI_SCOPE_ALL;
      writeHeaderAiScope(resolvedScope);
      const target = buildCatalogHubSearchUrl({
        q,
        aiScope: resolvedScope,
        currentSearch: onCatalogHub ? location.search : "",
      });
      navigate(target, onCatalogHub ? { replace: true } : undefined);
    },
    [location.search, navigate, onCatalogHub],
  );

  const submitSearch = useCallback(
    (e) => {
      e?.preventDefault?.();
      const q = String(query || "").trim();
      if (q.length < 2) {
        toast.error(t("shell.catalogSearchMinChars"));
        return;
      }
      dismissedRef.current = true;
      setShowDropdown(false);
      if (onStocksPage) {
        navigate(`${STOCKS_SEARCH_PATH}?q=${encodeURIComponent(q)}`);
        return;
      }
      navigateToSearch(q);
    },
    [navigate, navigateToSearch, onStocksPage, query, t],
  );

  const selectStockSuggestion = useCallback(
    (row) => {
      if (!row) return;
      const ticker = String(row.ticker || "").trim();
      const label = ticker || String(row.name || query || "").trim();
      dismissedRef.current = true;
      setShowDropdown(false);
      setSuggestions([]);
      setStockSuggestions([]);
      suppressFetchRef.current = true;
      if (label) setQuery(label);
      suppressUrlSyncRef.current = true;
      navigate(`/search/stocks?q=${encodeURIComponent(label)}`);
    },
    [navigate, query],
  );

  const selectSuggestion = useCallback(
    (s) => {
      if (!s) return;
      const title = String(s.title || "").trim();
      dismissedRef.current = true;
      setShowDropdown(false);
      setSuggestions([]);
      setStockSuggestions([]);
      suppressFetchRef.current = true;
      if (title) setQuery(title);
      // Klik = otevřít PŘÍMÝ náhled konkrétní řady (rychlé /catalog/preview), ne znovu
      // fuzzy-hledat název přes pomalý deep-search. Návrh nese set_id + source +
      // indicator_id → není co hledat, jdeme rovnou na tu řadu. Zároveň tím odpadá
      // dřívější chyba: klik volal navigateToSearch(..., scopeForSource(zdroj)), což
      // přes writeHeaderAiScope NATRVALO zamklo hledání na jeden zdroj a rozbilo další
      // hledání. Přímý náhled scope nemění.
      const setId = String(s.set_id || "").trim();
      const source = String(s.source || "").toLowerCase();
      if (setId && source) {
        const url = buildCatalogChartPageShareUrl({
          catalogId: source,
          setId,
          title,
          indicatorId: s.indicator_id,
        });
        if (url) {
          suppressUrlSyncRef.current = true;
          navigate(url);
          return;
        }
      }
      // Fallback (návrh bez set_id): hledat název v AKTUÁLNÍM scope — NE vnuceně
      // v jednom zdroji (žádná persistence scope).
      navigateToSearch(title || String(query || ""));
    },
    [navigate, navigateToSearch, query],
  );

  // Kombinovaný prostor indexů jen pro navigaci šipkami - katalog nejdřív, pak akcie -
  // vizuálně jsou to ale pořád dvě oddělené skupiny, ne jeden smíchaný žebříček.
  const combinedCount = suggestions.length + stockSuggestions.length;
  const selectByCombinedIndex = useCallback(
    (idx) => {
      if (idx < suggestions.length) {
        selectSuggestion(suggestions[idx]);
      } else {
        selectStockSuggestion(stockSuggestions[idx - suggestions.length]);
      }
    },
    [selectStockSuggestion, selectSuggestion, stockSuggestions, suggestions],
  );

  const onInputKeyDown = useCallback(
    (e) => {
      if (!showDropdown || combinedCount === 0) return;
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActiveIndex((i) => (i + 1) % combinedCount);
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        setActiveIndex((i) => (i <= 0 ? combinedCount - 1 : i - 1));
      } else if (e.key === "Enter" && activeIndex >= 0) {
        e.preventDefault();
        selectByCombinedIndex(activeIndex);
      } else if (e.key === "Escape") {
        dismissedRef.current = true;
        setShowDropdown(false);
      }
    },
    [activeIndex, combinedCount, selectByCombinedIndex, showDropdown],
  );

  // Výpočet zkráceného popisku aktuálního rozsahu pro mobilní zobrazení
  const scopeShortLabel = useMemo(() => {
    if (aiScope === AI_SCOPE_ALL) return t("shell.aiScopeAll", "Vše");
    if (aiScope === AI_SCOPE_SELECTED) return t("shell.aiScopeSelected", "Výběr");
    const def = HEADER_CATALOG_OPTIONS.find((c) => c.id === aiScope);
    return def ? getCatalogBrowseDropdownLabel(def) : aiScope;
  }, [aiScope, t]);

  return (
    <form
      onSubmit={submitSearch}
      className={`app-shell-catalog-search flex max-md:flex-nowrap md:flex-wrap min-[1180px]:flex-nowrap items-center gap-1 max-sm:gap-1.5 sm:gap-2 min-w-0 w-full max-w-full overflow-visible ${className}`.trim()}
      aria-label={t("shell.searchCatalog")}
      data-testid="app-shell-catalog-search"
    >
      <button
        type="button"
        onClick={openCatalogBrowse}
        className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.88)] text-foreground shadow-sm transition hover:bg-muted/50"
        aria-label={t("shell.openCatalogBrowse")}
        title={t("shell.openCatalogBrowse")}
        data-testid="app-shell-catalog-browse-toggle"
      >
        <PanelLeftOpen className="h-4 w-4 shrink-0" aria-hidden />
      </button>

      <div className="relative min-w-[12rem] flex-1 max-md:min-w-[8rem] md:min-w-[16rem]" ref={boxRef}>
        <Search
          className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"
          aria-hidden
        />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          onKeyDown={onInputKeyDown}
          role="combobox"
          aria-expanded={showDropdown}
          aria-autocomplete="list"
          aria-controls="app-shell-catalog-suggest-list"
          className={`h-9 w-full min-w-0 rounded-full border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.88)] pl-8 pr-11 text-sm shadow-sm outline-none transition focus:border-[hsl(var(--primary)/0.65)] focus:bg-[hsl(var(--card))] placeholder:text-muted-foreground/85 ${inputClassName}`.trim()}
          placeholder={onStocksPage ? "Hledat akcii, ETF nebo index · Apple, AAPL, ČEZ, SPY…" : t("shell.catalogSearchPlaceholder")}
          autoComplete="off"
          enterKeyHint="search"
          data-testid="app-shell-catalog-search-input"
          aria-label={t("shell.searchCatalog")}
        />
        <VoiceInputButton value={query} onChange={setQuery} className="absolute right-1 top-1/2 h-7 w-7 -translate-y-1/2 rounded-full" title="Diktovat hledání" />
        {showDropdown && combinedCount > 0 && (
          <ul
            id="app-shell-catalog-suggest-list"
            role="listbox"
            className="absolute left-0 right-0 top-full z-50 mt-1 max-h-80 w-auto max-w-full overflow-auto rounded-xl border border-[hsl(var(--border)/0.8)] bg-popover py-1 text-sm shadow-lg md:left-0 md:right-auto md:w-[min(92vw,480px)] md:max-w-[min(92vw,480px)]"
            data-testid="app-shell-catalog-suggest-list"
          >
            {suggestions.length > 0 && (
              <li role="presentation" className="px-3 pt-1 pb-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground/80">
                Katalog
              </li>
            )}
            {suggestions.map((s, idx) => (
              <li
                key={`cat|${s.source}|${s.set_id}|${idx}`}
                role="option"
                aria-selected={idx === activeIndex}
                onMouseDown={(e) => {
                  e.preventDefault(); // ať to nezavře blur dřív, než klik proběhne
                  selectSuggestion(s);
                }}
                onMouseEnter={() => setActiveIndex(idx)}
                className={`flex cursor-pointer items-center gap-2 px-3 py-1.5 ${
                  idx === activeIndex ? "bg-[hsl(var(--primary-soft))]" : "hover:bg-muted/60"
                }`}
              >
                <span className="w-[4.5rem] shrink-0 truncate rounded-full border border-[hsl(var(--border)/0.7)] bg-[hsl(var(--card)/0.7)] px-1.5 py-0.5 text-center text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                  {s.source_label || s.source}
                </span>
                <span className="min-w-0 flex-1 truncate text-foreground" title={s.full_path || s.title}>
                  {s.title}
                </span>
              </li>
            ))}
            {stockSuggestions.length > 0 && (
              <li
                role="presentation"
                className={`px-3 pb-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground/80 ${
                  suggestions.length > 0 ? "mt-1 border-t border-border/60 pt-1.5" : "pt-1"
                }`}
              >
                Akcie
              </li>
            )}
            {stockSuggestions.map((row, i) => {
              const globalIdx = suggestions.length + i;
              const ticker = String(row.ticker || "").trim();
              const price = Number(row?.market_snapshot?.last_price);
              const currency = String(row?.market_snapshot?.currency || "").trim();
              return (
                <li
                  key={`stock|${ticker}|${i}`}
                  role="option"
                  aria-selected={globalIdx === activeIndex}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    selectStockSuggestion(row);
                  }}
                  onMouseEnter={() => setActiveIndex(globalIdx)}
                  className={`flex cursor-pointer items-center gap-2 px-3 py-1.5 ${
                    globalIdx === activeIndex ? "bg-[hsl(var(--primary-soft))]" : "hover:bg-muted/60"
                  }`}
                >
                  <span className="w-[4.5rem] shrink-0 truncate rounded-full border border-[hsl(var(--border)/0.7)] bg-[hsl(var(--card)/0.7)] px-1.5 py-0.5 text-center text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                    Akcie
                  </span>
                  <span className="min-w-0 flex-1 truncate text-foreground" title={row.name || ticker}>
                    <span className="font-mono">{ticker}</span>
                    {row.name && row.name !== ticker ? <span className="text-muted-foreground"> · {row.name}</span> : null}
                  </span>
                  {Number.isFinite(price) ? (
                    <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground">
                      {price.toLocaleString("cs-CZ", { maximumFractionDigits: 2 })} {currency}
                    </span>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <CatalogHeaderFiltersMenu className="shrink-0" />

      {/* AI rozsah — mobile: jen ikona (nativní select); desktop (md+): pill tlačítka */}
      <div
        className="relative md:hidden h-9 w-9 shrink-0"
        title={`${t("shell.aiScopeLabel", "Rozsah AI hledání")}: ${scopeShortLabel}`}
      >
        <Sparkles className="pointer-events-none absolute left-1/2 top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 text-sky-600" aria-hidden />
        <select
          className="absolute inset-0 h-full w-full cursor-pointer appearance-none rounded-full border border-sky-300/80 bg-sky-50/90 text-[0px] text-transparent shadow-sm outline-none transition focus:border-sky-400"
          value={aiScope}
          onChange={(e) => handleAiScopeChange(e.target.value)}
          aria-label={`${t("shell.aiScopeLabel", "Rozsah AI hledání")}: ${scopeShortLabel}`}
          data-testid="app-shell-catalog-ai-scope-mobile"
        >
          <option value={AI_SCOPE_ALL}>{t("shell.aiScopeAll", "Vše")}</option>
          <option value={AI_SCOPE_SELECTED}>{t("shell.aiScopeSelected", "Výběr")}</option>
          <optgroup label={t("shell.aiScopeSingleGroup", "Jeden zdroj")}>
            {HEADER_CATALOG_OPTIONS.map((def) => (
              <option key={def.id} value={def.id}>{getCatalogBrowseDropdownLabel(def)}</option>
            ))}
          </optgroup>
        </select>
      </div>
      {/* Desktop: pill tlačítka */}
      <div className="hidden md:flex items-center gap-1 shrink-0" aria-label={t("shell.aiScopeLabel", "Rozsah AI hledání")} data-testid="app-shell-catalog-ai-scope">
        <button
          type="button"
          onClick={() => handleAiScopeChange(AI_SCOPE_ALL)}
          className={`h-9 px-3 rounded-full border text-xs font-medium transition whitespace-nowrap ${
            aiScope === AI_SCOPE_ALL
              ? "border-sky-500 bg-sky-600 text-white shadow-sm"
              : "border-sky-300/80 bg-sky-50/80 text-sky-800 hover:bg-sky-100"
          }`}
          title="AI vybere nejlepší zdroje automaticky"
        >
          {t("shell.aiScopeAll", "Vše")}
        </button>
        <button
          type="button"
          onClick={() => handleAiScopeChange(AI_SCOPE_SELECTED)}
          className={`h-9 px-3 rounded-full border text-xs font-medium transition whitespace-nowrap ${
            aiScope === AI_SCOPE_SELECTED
              ? "border-sky-500 bg-sky-600 text-white shadow-sm"
              : "border-sky-300/80 bg-sky-50/80 text-sky-800 hover:bg-sky-100"
          }`}
          title="Hledat jen ve zdrojích zaškrtnutých na stránce"
        >
          {t("shell.aiScopeSelected", "Výběr")}
        </button>
        <div className="relative">
          <select
            className={`h-9 appearance-none truncate rounded-full border pr-6 pl-3 text-xs font-medium shadow-sm outline-none transition focus:border-sky-400 ${
              aiScope !== AI_SCOPE_ALL && aiScope !== AI_SCOPE_SELECTED
                ? "border-sky-500 bg-sky-600 text-white"
                : "border-sky-300/80 bg-sky-50/80 text-sky-800 hover:bg-sky-100"
            }`}
            value={aiScope !== AI_SCOPE_ALL && aiScope !== AI_SCOPE_SELECTED ? aiScope : ""}
            onChange={(e) => { if (e.target.value) handleAiScopeChange(e.target.value); }}
            aria-label={t("shell.aiScopeSingleGroup", "Jeden zdroj")}
          >
            <option value="">{t("shell.aiScopeSingleGroup", "Jeden zdroj")}</option>
            {HEADER_CATALOG_OPTIONS.map((def) => (
              <option key={def.id} value={def.id}>{getCatalogBrowseDropdownLabel(def)}</option>
            ))}
          </select>
          <ChevronDown
            className={`pointer-events-none absolute right-2 top-1/2 h-3 w-3 -translate-y-1/2 ${
              aiScope !== AI_SCOPE_ALL && aiScope !== AI_SCOPE_SELECTED ? "text-white/80" : "text-sky-500"
            }`}
            aria-hidden
          />
        </div>
      </div>

      <button
        type="submit"
        className="hidden md:inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-sky-600 text-sm font-semibold text-white shadow-sm transition hover:bg-sky-700 2xl:w-auto 2xl:px-4 2xl:gap-1.5"
        data-testid="app-shell-catalog-search-submit"
        aria-label={t("shell.searchSubmit")}
        title={t("shell.searchSubmit")}
      >
        <Search className="h-4 w-4 shrink-0" aria-hidden />
        <span className="hidden 2xl:inline leading-none">{t("shell.searchSubmit")}</span>
      </button>
    </form>
  );
}
