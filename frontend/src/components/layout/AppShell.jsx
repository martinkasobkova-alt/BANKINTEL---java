import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import Sidebar from "@/components/layout/Sidebar";
import AppShellCatalogSearchBar from "@/components/layout/AppShellCatalogSearchBar";
import { ChevronRight, ChevronUp, Cookie, Menu, X } from "lucide-react";
import { useIsMobileDashboard } from "@/hooks/useMediaQuery";
import { openCookieSettings } from "@/components/CookieBanner";
import AdWidget from "@/components/widgets/AdWidget";
import api from "@/lib/api";
import { useFeatureAccessContextOptional } from "@/contexts/FeatureAccessContext";
import { AppearanceThemeMenuList } from "@/components/theme/AppearanceThemePicker";
import {
  APPEARANCE_STORAGE_KEY,
  PRIMARY_APPEARANCE_PRESETS,
  applyAppearancePresetToDocument,
  getAppearancePresetById,
  hasUserChosenAppearance,
  loadStoredAppearanceId,
} from "@/theme/appearancePresets";

export default function AppShell({ children, actions, hideAds = false }) {
  const { t } = useTranslation();
  const SIDEBAR_COLLAPSED_STORAGE_KEY = "bankoapp:sidebar-collapsed";
  const location = useLocation();
  /** Veřejná URL — plný výběr katalogů (ARAD…OECD) jako při správě zdrojů; přidávání zdrojů jen pro admina v UI. */
  const catalogSearchPath = "/search/catalog";
  /** Globální katalog (/search/catalog) — zjednodušená horní lišta (jen vyhledávání). */
  const isGlobalCatalogHub = location.pathname === catalogSearchPath;
  const hidePageWatermark = location.pathname.startsWith(catalogSearchPath);
  const [backgroundThemeId, setBackgroundThemeId] = useState(loadStoredAppearanceId);
  // Rozlišujeme explicitní volbu uživatele (má se uložit do localStorage)
  // od serveru nastaveného výchozího schématu (nemá se přepisovat localStorage).
  const userPickedRef = useRef(hasUserChosenAppearance());
  const backgroundTheme = useMemo(
    () => getAppearancePresetById(backgroundThemeId),
    [backgroundThemeId]
  );
  useLayoutEffect(() => {
    applyAppearancePresetToDocument(backgroundTheme);
  }, [backgroundTheme]);

  useEffect(() => {
    if (!userPickedRef.current) return;
    try {
      localStorage.setItem(APPEARANCE_STORAGE_KEY, backgroundTheme.id);
    } catch {
      /* ignore */
    }
  }, [backgroundTheme.id]);

  const handleUserPickTheme = useCallback((id) => {
    userPickedRef.current = true;
    try {
      localStorage.setItem(APPEARANCE_STORAGE_KEY, id);
    } catch {
      /* ignore */
    }
    setBackgroundThemeId(id);
  }, []);

  // Pokud uživatel ještě nikdy nic nezvolil, načti výchozí schéma nastavené adminem.
  useEffect(() => {
    if (hasUserChosenAppearance()) return;
    api
      .get("/app-settings")
      .then((res) => {
        const adminDefault = res.data?.default_appearance_id;
        if (adminDefault && !hasUserChosenAppearance()) {
          setBackgroundThemeId(adminDefault);
        }
      })
      .catch(() => {/* tichá chyba — použijeme výchozí schéma */});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const appShellHeaderRef = useRef(null);
  /** Skutečný scroll kontejner obsahu (vnitřek `#app-main-scroll`). */
  const mainScrollPaneRef = useRef(null);
  const [showScrollToTop, setShowScrollToTop] = useState(false);

  const SCROLL_TO_TOP_THRESHOLD_PX = 220;

  useEffect(() => {
    const el = mainScrollPaneRef.current;
    if (!el) return undefined;
    const sync = () => setShowScrollToTop(el.scrollTop > SCROLL_TO_TOP_THRESHOLD_PX);
    sync();
    el.addEventListener("scroll", sync, { passive: true });
    return () => el.removeEventListener("scroll", sync);
  }, [location.pathname]);

  const scrollMainToTop = useCallback(() => {
    const el = mainScrollPaneRef.current;
    if (!el) return;
    const reduced =
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    el.scrollTo({ top: 0, behavior: reduced ? "auto" : "smooth" });
  }, []);

  /** iOS Safari občas posune celý dokument nahoru — hlavička pak „zmizí“ pod stavovým řádkem. */
  const resetDocumentScroll = useCallback(() => {
    if (typeof window === "undefined") return;
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    document.documentElement.scrollLeft = 0;
    document.body.scrollTop = 0;
    document.body.scrollLeft = 0;
    const pane = mainScrollPaneRef.current;
    if (pane && pane.scrollLeft !== 0) pane.scrollLeft = 0;
  }, []);

  const [mobileThemeSheetOpen, setMobileThemeSheetOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [desktopSidebarCollapsed, setDesktopSidebarCollapsed] = useState(false);
  const isMobileHeader = useIsMobileDashboard();

  useEffect(() => {
    const el = mainScrollPaneRef.current;
    if (!el || !isMobileHeader) return undefined;
    const clampHorizontalScroll = () => {
      if (el.scrollLeft !== 0) el.scrollLeft = 0;
    };
    clampHorizontalScroll();
    el.addEventListener("scroll", clampHorizontalScroll, { passive: true });
    return () => el.removeEventListener("scroll", clampHorizontalScroll);
  }, [isMobileHeader, location.pathname]);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY);
      setDesktopSidebarCollapsed(raw === "1");
    } catch {
      setDesktopSidebarCollapsed(false);
    }
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, desktopSidebarCollapsed ? "1" : "0");
    } catch {
      /* ignore */
    }
  }, [desktopSidebarCollapsed]);

  useEffect(() => {
    if (typeof document === "undefined") return;
    if (!mobileNavOpen) return undefined;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [mobileNavOpen]);

  useEffect(() => {
    if (!isMobileHeader) return undefined;
    const clampDocumentHorizontalScroll = () => {
      if (document.documentElement.scrollLeft !== 0) document.documentElement.scrollLeft = 0;
      if (document.body.scrollLeft !== 0) document.body.scrollLeft = 0;
    };
    clampDocumentHorizontalScroll();
    window.addEventListener("scroll", clampDocumentHorizontalScroll, { passive: true });
    return () => window.removeEventListener("scroll", clampDocumentHorizontalScroll);
  }, [isMobileHeader, location.pathname]);

  useEffect(() => {
    if (!isMobileHeader) return;
    resetDocumentScroll();
    mainScrollPaneRef.current?.scrollTo(0, 0);
  }, [isMobileHeader, location.pathname, resetDocumentScroll]);

  useEffect(() => {
    if (!isMobileHeader) return undefined;
    const onVisible = () => {
      if (document.visibilityState === "visible") resetDocumentScroll();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [isMobileHeader, resetDocumentScroll]);

  useEffect(() => {
    if (!mobileNavOpen) return undefined;
    const onKey = (e) => {
      if (e.key === "Escape") setMobileNavOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [mobileNavOpen]);

  // Globální reklamní sloty (sidebar + topbar) — vykreslujeme jen když jsou
  // v adminu zapnuté a mají obsah. Načtení selhává tiše (např. když není
  // backend), aby výpadek reklamy nikdy neshodil layout aplikace.
  const [adSlots, setAdSlots] = useState({ sidebar: null, topbar: null });
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/ad-slots");
        if (!cancelled) setAdSlots(data || {});
      } catch {
        /* ignore — reklamy jsou volitelné */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);
  const sidebarAd = adSlots?.sidebar;
  const topbarAd = adSlots?.topbar;
  const sidebarAdActive =
    sidebarAd?.enabled &&
    (sidebarAd.image_url || sidebarAd.content?.trim() || sidebarAd.html?.trim());
  const topbarAdActive =
    topbarAd?.enabled &&
    (topbarAd.image_url || topbarAd.content?.trim() || topbarAd.html?.trim());
  const feCtx = useFeatureAccessContextOptional();
  const adFreeEntry = feCtx?.effective?.ad_free_dashboard;
  /** Dokud není mapa hotová nebo během refetch → chovat se jako dnes (reklamy viditelné). */
  const showPublicAds =
    !hideAds && (!feCtx?.accessMapReady || adFreeEntry?.allowed !== true);

  useEffect(() => {
    const headerEl = appShellHeaderRef.current;
    if (!headerEl || typeof ResizeObserver === "undefined") return undefined;
    const syncHeaderHeight = () => {
      const height = Math.ceil(headerEl.getBoundingClientRect().height);
      document.documentElement.style.setProperty("--app-shell-header-height", `${height}px`);
    };
    syncHeaderHeight();
    const observer = new ResizeObserver(syncHeaderHeight);
    observer.observe(headerEl);
    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty("--app-shell-header-height");
    };
  }, [location.pathname, topbarAdActive, showPublicAds, isMobileHeader, isGlobalCatalogHub]);

  useEffect(() => {
    if (!mobileThemeSheetOpen) return undefined;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [mobileThemeSheetOpen]);

  useEffect(() => {
    if (!mobileThemeSheetOpen) return undefined;
    const onKey = (e) => {
      if (e.key === "Escape") setMobileThemeSheetOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [mobileThemeSheetOpen]);

  const mobileThemeSheet =
    mobileThemeSheetOpen && typeof document !== "undefined"
      ? createPortal(
          <div className="md:hidden fixed inset-0 z-[200] flex flex-col justify-end p-2 overflow-x-hidden">
            <button
              type="button"
              aria-label={t("shell.closeThemePicker")}
              className="absolute inset-0 bg-[hsl(var(--overlay-backdrop)/0.14)] backdrop-blur-[2px]"
              onClick={() => setMobileThemeSheetOpen(false)}
            />
            <div
              className="relative z-[1] rounded-2xl border border-[hsl(var(--border)/0.85)] bg-[hsl(var(--card))] text-[hsl(var(--card-foreground))] shadow-2xl p-4 max-h-[72vh] overflow-y-auto w-full max-w-[min(100vw-16px,400px)] mx-auto"
              role="dialog"
              aria-label={t("shell.appThemeDialog")}
            >
              <div className="flex items-center justify-between mb-3">
                <span className="text-sm font-semibold text-[hsl(var(--foreground))]">
                  {t("shell.appTheme")}
                </span>
                <button
                  type="button"
                  className="h-9 w-9 inline-flex items-center justify-center rounded-xl border border-[hsl(var(--border)/0.6)] bg-[hsl(var(--muted)/0.35)] text-[hsl(var(--foreground))]"
                  aria-label={t("shell.close")}
                  onClick={() => setMobileThemeSheetOpen(false)}
                >
                  <X className="h-5 w-5" />
                </button>
              </div>
              <AppearanceThemeMenuList
                presets={PRIMARY_APPEARANCE_PRESETS}
                selectedId={backgroundThemeId}
                onSelect={(id) => {
                  handleUserPickTheme(id);
                  setMobileThemeSheetOpen(false);
                }}
              />
            </div>
          </div>,
          document.body
        )
      : null;

  return (
    <div
      data-app-shell-root
      className="relative min-h-screen w-full max-w-full overflow-x-hidden transition-colors duration-300"
      style={{ background: backgroundTheme.page }}
    >
      {!hidePageWatermark ? (
        <div
          className="bg-watermark"
          aria-hidden
          style={{ backgroundImage: "url(/bankovnictvi-logo.png)" }}
        />
      ) : null}
      {mobileNavOpen ? (
        <button
          type="button"
          aria-label={t("shell.closeNav")}
          className="fixed inset-0 z-[45] bg-[hsl(var(--overlay-backdrop)/0.12)] backdrop-blur-[2px] md:hidden cursor-default border-0 p-0 m-0"
          onClick={() => setMobileNavOpen(false)}
        />
      ) : null}
      <Sidebar
        sidebarAdSlot={!hideAds && sidebarAdActive && showPublicAds ? sidebarAd : null}
        mobileOpen={mobileNavOpen}
        onNavigate={() => setMobileNavOpen(false)}
        backgroundThemeId={backgroundThemeId}
        onPickBackgroundTheme={handleUserPickTheme}
        desktopCollapsed={desktopSidebarCollapsed}
        onToggleDesktopCollapsed={() => setDesktopSidebarCollapsed((v) => !v)}
      />
      <main
        className={`relative z-10 ml-0 flex h-screen min-w-0 flex-col overflow-x-hidden overflow-y-hidden ${
          desktopSidebarCollapsed ? "md:ml-0" : "md:ml-64"
        }`}
      >
        {desktopSidebarCollapsed ? (
          <button
            type="button"
            onClick={() => setDesktopSidebarCollapsed(false)}
            className="fixed left-2 top-4 z-[60] pointer-events-auto hidden h-9 w-9 items-center justify-center rounded-full border border-[hsl(var(--border)/0.78)] bg-[hsl(var(--card)/0.95)] text-[hsl(var(--foreground))] shadow-md backdrop-blur-sm transition hover:bg-[hsl(var(--primary-soft))] md:inline-flex"
            title={t("nav.showSidebar")}
            aria-label={t("nav.showSidebar")}
            data-testid="app-desktop-sidebar-toggle"
          >
            <ChevronRight className="h-4 w-4" strokeWidth={2} aria-hidden />
          </button>
        ) : null}
        <header
          ref={appShellHeaderRef}
          data-app-shell-header
          data-catalog-hub={isGlobalCatalogHub ? "1" : undefined}
          className="relative z-50 isolate shrink-0 px-3 sm:px-4 md:px-5 lg:px-8 max-md:pt-[max(0.5rem,env(safe-area-inset-top,0px))] max-md:pb-2 pt-5 pb-3"
        >
          <div
            className="rounded-2xl border border-[hsl(var(--border)/0.85)] shadow-[0_14px_36px_hsl(var(--foreground)/0.13)] flex flex-col overflow-visible max-md:max-w-full max-md:w-full max-md:overflow-x-hidden max-md:shadow-md"
            style={{ background: backgroundTheme.panel }}
          >
            {topbarAdActive && showPublicAds && (
              <div
                className="w-full shrink-0 rounded-t-2xl border-b border-[hsl(var(--border)/0.55)] max-h-[min(160px,30vh)] min-h-0 overflow-hidden bg-[hsl(var(--foreground)/0.05)]"
                data-testid="ad-slot-topbar"
              >
                <div className="h-[88px] sm:h-[104px] w-full min-h-0 overflow-hidden [&_a]:block [&_a]:h-full [&_a]:min-h-0">
                  <AdWidget data={topbarAd} slotMode layout="horizontal" />
                </div>
              </div>
            )}
            <div
              className={`px-3 sm:px-5 flex flex-col max-md:!px-2 md:gap-x-6 md:flex-row md:flex-wrap md:items-start md:justify-between py-3 max-md:!py-2 gap-3 md:gap-y-4 md:justify-between ${
                topbarAdActive && showPublicAds ? "rounded-b-2xl" : "rounded-2xl"
              }`}
            >
              {/* Mobil — menu + vyhledávání (stejná šablona jako desktop vpravo) */}
              {isMobileHeader ? (
              <>
              <div className="shrink-0 w-full flex flex-nowrap items-center gap-1.5 min-h-[44px] min-w-0">
                <button
                  type="button"
                  className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.92)] text-[hsl(var(--foreground))] shadow-sm transition hover:bg-[hsl(var(--primary-soft))]"
                  aria-label={t("shell.openNav")}
                  aria-expanded={mobileNavOpen}
                  aria-controls="app-sidebar-nav"
                  onClick={() => setMobileNavOpen((v) => !v)}
                >
                  <Menu className="h-5 w-5" strokeWidth={2} aria-hidden />
                </button>
                <AppShellCatalogSearchBar
                  className="flex-1 min-w-0"
                  inputClassName="text-[13px] leading-tight bg-[hsl(var(--card)/0.95)]"
                />
              </div>
              </>
              ) : null}

              {!isMobileHeader && isGlobalCatalogHub ? (
              <div
                className="page-header-inner flex w-full flex-nowrap items-center gap-2 sm:gap-3 min-w-0"
                data-page-header-layout="catalog-hub-compact"
              >
                <AppShellCatalogSearchBar className="flex-1 min-w-0" />
                <div className="flex shrink-0 items-center gap-2">
                  {actions}
                </div>
              </div>
              ) : null}

              {!isMobileHeader && !isGlobalCatalogHub ? (
              <div
                className="page-header-inner flex w-full min-w-0 flex-wrap items-center gap-2 sm:gap-3"
                data-page-header-layout="search-primary"
              >
                <AppShellCatalogSearchBar className="relative flex-1 basis-[42rem] min-w-0" />
                {actions ? (
                  <nav
                    className="page-header-actions flex shrink-0 flex-wrap items-center gap-2"
                    aria-label={t("shell.pageActions")}
                  >
                    {actions}
                  </nav>
                ) : null}
              </div>
              ) : null}

              {isMobileHeader && actions ? (
                <div className="w-full flex flex-wrap items-center gap-2 pt-0.5 border-t border-[hsl(var(--border)/0.35)]">
                  {actions}
                </div>
              ) : null}
            </div>
          </div>
        </header>
        <div id="app-main-scroll" className="flex min-h-0 flex-1 flex-col overflow-hidden">
          <div
            id="app-main-scroll-pane"
            ref={mainScrollPaneRef}
            className="app-main-scroll-pane relative z-0 min-h-0 w-full max-w-full flex-1 overflow-x-hidden overflow-y-auto overscroll-y-contain overscroll-x-none px-4 pt-5 pb-8 sm:px-6 md:px-8 xl:px-12 xl:pb-10"
          >
            <div className="app-content-viewport min-w-0 w-full max-w-full overflow-x-hidden">
              {children}
            </div>
          </div>
          <footer className="hidden shrink-0 border-t border-border/40 px-4 py-6 text-xs text-muted-foreground sm:px-6 md:flex md:flex-wrap md:items-center md:justify-between md:gap-3 md:px-8 xl:px-12">
            <div>© {new Date().getFullYear()} Bankovnictví · {t("shell.footerTagline")}</div>
            <div className="flex items-center gap-4 flex-wrap">
              <Link to="/predplatne" className="hover:text-[hsl(var(--primary))] underline-offset-4 hover:underline">
                {t("nav.subscription")}
              </Link>
              <Link to="/ochrana-osobnich-udaju" className="hover:text-[hsl(var(--primary))] underline-offset-4 hover:underline">
                {t("shell.privacy")}
              </Link>
              <Link to="/cookies" className="hover:text-[hsl(var(--primary))] underline-offset-4 hover:underline">
                {t("shell.cookiesPolicy")}
              </Link>
              <button
                type="button"
                onClick={openCookieSettings}
                data-testid="footer-cookie-settings"
                className="flex items-center gap-1.5 hover:text-[hsl(var(--primary))]"
              >
                <Cookie className="h-3.5 w-3.5" /> {t("shell.cookieSettings")}
              </button>
            </div>
          </footer>
        </div>
        {showScrollToTop ? (
          <button
            type="button"
            onClick={scrollMainToTop}
            className={`fixed left-5 z-[36] flex h-12 w-12 items-center justify-center rounded-full border border-[hsl(var(--border)/0.75)] bg-[hsl(var(--card)/0.95)] text-[hsl(var(--foreground))] shadow-lg backdrop-blur-sm transition-all duration-200 hover:-translate-y-0.5 hover:bg-[hsl(var(--primary-soft))] hover:text-[hsl(var(--foreground))] bottom-24 md:bottom-8 ${
              desktopSidebarCollapsed ? "md:left-5" : "md:left-[17.25rem]"
            }`}
            title={t("shell.scrollToTopTitle")}
            aria-label={t("shell.scrollToTop")}
            data-testid="app-scroll-to-top"
          >
            <ChevronUp className="h-6 w-6" strokeWidth={2} aria-hidden />
          </button>
        ) : null}
      </main>
      {mobileThemeSheet}
    </div>
  );
}
