import React, { useEffect, useMemo, useState } from "react";
import { Link, NavLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  LayoutDashboard,
  Database,
  Megaphone,
  Users,
  Settings,
  LogIn,
  LogOut,
  ShieldCheck,
  PanelsTopLeft,
  Folder,
  Landmark,
  Shield,
  PiggyBank,
  Building2,
  CreditCard,
  Coins,
  TrendingUp,
  BarChart3,
  Briefcase,
  LineChart,
  Lock,
  Sparkles,
  Bug,
  ClipboardList,
  RefreshCw,
  Search,
  HardDrive,
  Wallet,
  PieChart,
  Activity,
  Layers,
  Home,
  Globe2,
  Scale,
  Banknote,
  Receipt,
  Cpu,
  Newspaper,
  Gauge,
  Smartphone,
  Zap,
  FileSpreadsheet,
  GripVertical,
  ChevronLeft,
  MessageSquare,
  Mic,
} from "lucide-react";
import SidebarPodcastPlayer from "@/components/podcast/SidebarPodcastPlayer";
import { useAuth } from "@/contexts/AuthContext";
import { useBugReport } from "@/contexts/BugReportContext";
import api from "@/lib/api";
import AdWidget from "@/components/widgets/AdWidget";
import AppearanceThemePicker from "@/components/theme/AppearanceThemePicker";
import LocaleSwitcher from "@/components/locale/LocaleSwitcher";
import { PRIMARY_APPEARANCE_PRESETS } from "@/theme/appearancePresets";
import { useLocalizedContent } from "@/hooks/useLocalizedContent";

function navLabel(item, t) {
  return item.labelKey ? t(item.labelKey) : item.label;
}

// Icon registry for admin-defined sections.
export const SECTION_ICONS = {
  Folder,
  Landmark,
  Shield,
  PiggyBank,
  Building2,
  CreditCard,
  Coins,
  TrendingUp,
  BarChart3,
  Briefcase,
  LineChart,
  Database,
  Wallet,
  PieChart,
  Activity,
  Layers,
  Home,
  Globe2,
  Scale,
  Banknote,
  Receipt,
  Cpu,
  Newspaper,
  Gauge,
  Smartphone,
  Zap,
  FileSpreadsheet,
};

const NAV_OVERVIEW = { to: "/", labelKey: "nav.overview", icon: LayoutDashboard, testid: "nav-overview" };
const NAV_SUBSCRIPTION = { to: "/predplatne", labelKey: "nav.subscription", icon: CreditCard, testid: "nav-predplatne" };

const NAV_USER = [
  {
    id: "nav-user-catalog-search",
    to: "/search/catalog",
    labelKey: "nav.catalogSearch",
    icon: Search,
    testid: "nav-catalog-search",
    visible: () => true,
    isActive: (pathname) => pathname.startsWith("/search/catalog"),
    kind: "link",
  },
  {
    id: "nav-user-stock-search",
    to: "/search/stocks",
    labelKey: "nav.stockSearch",
    icon: TrendingUp,
    testid: "nav-stock-search",
    visible: () => true,
    isActive: (pathname) =>
      pathname.startsWith("/search/stocks") || pathname.startsWith("/search/akcie"),
    kind: "link",
  },
  {
    id: "nav-user-manager-explorer",
    to: "/explore",
    labelKey: "nav.managerExplorer",
    icon: Briefcase,
    testid: "nav-manager-explorer",
    visible: () => true,
    isActive: (pathname) => pathname.startsWith("/explore"),
    kind: "link",
  },
  {
    id: "nav-user-pdf-archive",
    to: "/archive",
    labelKey: "nav.reader",
    icon: FileSpreadsheet,
    testid: "nav-pdf-archive",
    visible: () => true,
    isActive: (pathname) => pathname.startsWith("/archive"),
    kind: "link",
  },
  {
    id: "nav-user-podcasts",
    to: "/podcasty",
    labelKey: "nav.podcasts",
    icon: Mic,
    testid: "nav-podcasts",
    visible: () => true,
    isActive: (pathname) => pathname.startsWith("/podcasty"),
    kind: "link",
  },
  {
    id: "nav-user-articles",
    to: "/zpravy",
    labelKey: "nav.articles",
    icon: Newspaper,
    testid: "nav-articles-public",
    visible: () => true,
    isActive: (pathname) => pathname.startsWith("/zpravy"),
    kind: "link",
  },
  {
    id: "nav-user-messages",
    to: "/messages",
    labelKey: "nav.chat",
    icon: MessageSquare,
    testid: "nav-messages",
    visible: ({ user }) => Boolean(user),
    isActive: (pathname) => pathname.startsWith("/messages"),
    kind: "link",
  },
  {
    id: "nav-user-my-data",
    to: "/my-data",
    labelKey: "nav.dataSources",
    icon: HardDrive,
    testid: "nav-my-data",
    visible: ({ user, isSubscriber }) => Boolean(user) && isSubscriber,
    isActive: (pathname) => pathname.startsWith("/my-data"),
    kind: "link",
  },
  {
    id: "nav-user-subscription",
    to: NAV_SUBSCRIPTION.to,
    labelKey: NAV_SUBSCRIPTION.labelKey,
    icon: NAV_SUBSCRIPTION.icon,
    testid: NAV_SUBSCRIPTION.testid,
    visible: () => true,
    isActive: (pathname) => pathname === NAV_SUBSCRIPTION.to || pathname.startsWith(NAV_SUBSCRIPTION.to),
    kind: "link",
  },
  {
    id: "nav-user-my-dashboard",
    to: "/my-dashboard",
    labelKey: "nav.myDashboard",
    icon: Sparkles,
    testid: "nav-my-dashboard",
    visible: ({ user, isSubscriber }) => Boolean(user) && isSubscriber,
    isActive: (pathname) => pathname.startsWith("/my-dashboard"),
    kind: "link",
  },
  {
    id: "nav-user-rss-feeds",
    to: "/my-rss",
    labelKey: "nav.rssMonitoring",
    icon: Newspaper,
    testid: "nav-user-rss-feeds",
    visible: ({ user, isAdmin, isSubscriber }) => Boolean(user) && !isAdmin && isSubscriber,
    isActive: (pathname) => pathname.startsWith("/my-rss"),
    kind: "link",
  },
  {
    id: "nav-user-report-bug",
    labelKey: "nav.reportBug",
    icon: Bug,
    testid: "nav-report-bug",
    visible: () => true,
    isActive: () => false,
    kind: "button",
  },
  {
    id: "nav-user-settings",
    to: "/settings",
    labelKey: "nav.settings",
    icon: Settings,
    testid: "nav-settings",
    visible: ({ user }) => Boolean(user),
    isActive: (pathname) => pathname === "/settings",
    kind: "link",
  },
];

const NAV_ADMIN = [
  { id: "nav-users", to: "/users", labelKey: "nav.users", icon: Users, testid: "nav-users" },
  { id: "nav-feature-access", to: "/admin/feature-access", labelKey: "nav.featureLock", icon: Lock, testid: "nav-feature-access" },
  { id: "nav-rss-feeds", to: "/admin/rss-feeds", labelKey: "nav.rssMonitoring", icon: Newspaper, testid: "nav-rss-feeds" },
  { id: "nav-articles", to: "/admin/articles", labelKey: "nav.articles", icon: MessageSquare, testid: "nav-articles" },
  {
    id: "nav-pdf-archive-admin",
    to: "/admin/archive",
    labelKey: "nav.pdfArchiveAdmin",
    icon: FileSpreadsheet,
    testid: "nav-pdf-archive-admin",
    alsoMatches: ["/admin/archive"],
  },
  { id: "nav-bug-reports", to: "/admin/bug-reports", labelKey: "nav.bugReports", icon: ClipboardList, testid: "nav-bug-reports" },
  { id: "nav-data-admin", to: "/admin/homepage", labelKey: "nav.widgets", icon: PanelsTopLeft, testid: "nav-data-admin" },
  {
    id: "nav-sources",
    to: "/sources",
    labelKey: "nav.dataSources",
    icon: Database,
    testid: "nav-sources",
    alsoMatches: ["/computed", "/formulas", "/exports"],
  },
  { id: "nav-ads", to: "/admin/ads", labelKey: "nav.adSlots", icon: Megaphone, testid: "nav-ads" },
  { id: "nav-sync-logs", to: "/sync-logs", labelKey: "nav.syncLogs", icon: RefreshCw, testid: "nav-sync-logs" },
];

const NAV_EDITOR = [
  { id: "nav-articles", to: "/admin/articles", labelKey: "nav.manageArticles", icon: MessageSquare, testid: "nav-articles" },
  { id: "nav-data-admin", to: "/admin/homepage", labelKey: "nav.widgets", icon: PanelsTopLeft, testid: "nav-data-admin" },
];

const ADMIN_NAV_DEFAULT_IDS = NAV_ADMIN.map((n) => n.id);
const USER_NAV_DEFAULT_IDS = NAV_USER.map((n) => n.id);

function reorderIds(order, draggedId, targetId) {
  const from = order.indexOf(draggedId);
  const to = order.indexOf(targetId);
  if (from < 0 || to < 0 || from === to) return order;
  const next = [...order];
  next.splice(from, 1);
  next.splice(to, 0, draggedId);
  return next;
}

/**
 * @param {object | null} [sidebarAdSlot] — data z `GET /ad-slots` (klíč `sidebar`); reklama pod menu, ne vedle lišty.
 * @param {boolean} [mobileOpen] — pod 768px: rozbalení off-canvas menu (řídí transform).
 * @param {() => void} [onNavigate] — po kliknutí na odkaz/vstup (zavře mobilní drawer v AppShell).
 * @param {string} [backgroundThemeId]
 * @param {(id: string) => void} [onPickBackgroundTheme]
 * @param {boolean} [desktopCollapsed]
 * @param {() => void} [onToggleDesktopCollapsed]
 */
export default function Sidebar({
  sidebarAdSlot = null,
  mobileOpen = false,
  onNavigate,
  backgroundThemeId = "blue",
  onPickBackgroundTheme,
  desktopCollapsed = false,
  onToggleDesktopCollapsed,
}) {
  const { t } = useTranslation();
  const cmsLoc = useLocalizedContent();
  const { user, ready, isAdmin, isEditor, canEditContent, isSubscriber, logout, openLogin } = useAuth();
  /** Stav účtu pro štítek v patičce sidebaru — barva podle role (viditelnost u všech motivů). */
  const accountStatus = useMemo(() => {
    if (!ready) return null;
    if (!user || user === false) return { kind: "guest", labelKey: "account.guest" };
    if (isAdmin) return { kind: "admin", labelKey: "account.admin" };
    if (isEditor) return { kind: "editor", labelKey: "account.editor" };
    if (isSubscriber) return { kind: "subscriber", labelKey: "account.subscriber" };
    return { kind: "registered", labelKey: "account.registered" };
  }, [ready, user, isAdmin, isEditor, isSubscriber]);

  const accountBadgeToneClass = (kind) => {
    switch (kind) {
      case "admin":
        return "border-red-400/90 bg-red-50 text-red-800 ring-1 ring-red-500/15";
      case "editor":
        return "border-violet-400/90 bg-violet-50 text-violet-900 ring-1 ring-violet-600/12";
      case "subscriber":
        return "border-emerald-400/90 bg-emerald-50 text-emerald-900 ring-1 ring-emerald-600/12";
      case "registered":
        return "border-blue-400/90 bg-blue-50 text-blue-900 ring-1 ring-blue-600/12";
      case "guest":
      default:
        return "border-slate-500/50 bg-slate-100 text-slate-950 ring-1 ring-black/5";
    }
  };
  const { openBugReport } = useBugReport();
  const loc = useLocation();
  const [sections, setSections] = useState([]);
  const [chatUnreadCount, setChatUnreadCount] = useState(0);
  /** Pořadí položek v bloku Administrace (jen admin), stabilní ID jako `testid`. */
  const [adminNavIds, setAdminNavIds] = useState(() => [...ADMIN_NAV_DEFAULT_IDS]);
  /** Pořadí položek v bloku Uživatelské rozhraní (per user). */
  const [userNavIds, setUserNavIds] = useState(() => [...USER_NAV_DEFAULT_IDS]);
  const OverviewIcon = NAV_OVERVIEW.icon;
  const closeAfter = (fn) => {
    fn?.();
    onNavigate?.();
  };

  useEffect(() => {
    let cancelled = false;
    api
      .get("/sections")
      .then(({ data }) => {
        if (!cancelled) setSections(Array.isArray(data) ? data : []);
      })
      .catch(() => {
        if (!cancelled) setSections([]);
      });
    return () => {
      cancelled = true;
    };
    // Re-fetch when admin logs in/out (sections stay public, but this covers
    // cases where the admin just added / removed one in another tab).
  }, [isAdmin]);

  useEffect(() => {
    if (!user || user === false) {
      setChatUnreadCount(0);
      return undefined;
    }
    let cancelled = false;
    let timer = null;
    const loadUnread = async () => {
      try {
        const { data } = await api.get("/chat/unread-count");
        if (!cancelled) setChatUnreadCount(Math.max(0, Number(data?.unread_count || 0)));
      } catch {
        if (!cancelled) setChatUnreadCount(0);
      }
    };
    void loadUnread();
    timer = window.setInterval(loadUnread, 30000);
    return () => {
      cancelled = true;
      if (timer) window.clearInterval(timer);
    };
  }, [user]);

  useEffect(() => {
    if (!isAdmin || !user) return;
    let cancelled = false;
    api
      .get("/me/admin-nav-order")
      .then(({ data }) => {
        if (cancelled || !Array.isArray(data?.order) || !data.order.length) return;
        setAdminNavIds(data.order);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [isAdmin, user?.id]);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    api
      .get("/me/user-nav-order")
      .then(({ data }) => {
        if (cancelled || !Array.isArray(data?.order) || !data.order.length) return;
        setUserNavIds(data.order);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [user?.id]);

  const adminNavById = useMemo(() => Object.fromEntries(NAV_ADMIN.map((n) => [n.id, n])), []);
  const adminNavItems = useMemo(() => {
    const items = adminNavIds.map((id) => adminNavById[id]).filter(Boolean);
    if (items.length === NAV_ADMIN.length) return items;
    const seen = new Set(items.map((n) => n.id));
    const merged = [...items];
    for (const n of NAV_ADMIN) {
      if (!seen.has(n.id)) merged.push(n);
    }
    return merged;
  }, [adminNavIds, adminNavById]);

  const contentNavItems = isAdmin ? adminNavItems : isEditor ? NAV_EDITOR : [];

  const userNavById = useMemo(() => Object.fromEntries(NAV_USER.map((n) => [n.id, n])), []);
  const userNavItems = useMemo(() => {
    const items = userNavIds.map((id) => userNavById[id]).filter(Boolean);
    if (items.length === NAV_USER.length) return items;
    const seen = new Set(items.map((n) => n.id));
    const merged = [...items];
    for (const n of NAV_USER) {
      if (!seen.has(n.id)) merged.push(n);
    }
    return merged;
  }, [userNavIds, userNavById]);

  return (
    <aside
      id="app-sidebar-nav"
      data-testid="app-sidebar"
      className={[
        "fixed inset-y-0 left-0 p-3",
        /* Mobil: výsuvný drawer; desktop: pevně zobrazená lišta */
        "z-[50] md:z-20",
        "w-[min(88vw,360px)] max-w-[360px] md:w-64 md:max-w-none",
        "transition-transform duration-200 ease-out will-change-transform",
        /* Mobil: skrytý drawer nesmí zůstat v DOM s -translate-x (iOS Safari pak roztáhne horizontální scroll). */
        mobileOpen
          ? "max-md:translate-x-0 max-md:visible max-md:pointer-events-auto"
          : "max-md:hidden",
        desktopCollapsed ? "md:hidden" : "md:translate-x-0 md:visible",
      ].join(" ")}
      style={{ background: "transparent" }}
    >
      <div
        className="h-full rounded-3xl border border-[hsl(var(--border)/0.85)] bg-[hsl(var(--card))] text-[hsl(var(--card-foreground))] shadow-[0_14px_38px_hsl(var(--foreground)/0.07)] flex flex-col overflow-hidden md:backdrop-blur-sm"
      >
        <div className="relative flex flex-col items-start gap-2 max-md:gap-1.5 px-4 max-md:px-4 pt-5 max-md:pt-4 pb-6 max-md:pb-4 border-b border-[hsl(var(--border)/0.55)]">
          <Link
            to="/"
            onClick={() => onNavigate?.()}
            className="flex flex-col items-start gap-3 rounded-lg -m-1 p-1 min-w-0 focus:outline-none focus-visible:ring-2 focus-visible:ring-[hsl(var(--primary))] focus-visible:ring-offset-2"
            aria-label={t("nav.goHome")}
            data-testid="sidebar-brand-home"
          >
            <img
              src="/bankovnictvi-logo.png"
              alt=""
              className="w-full max-w-[min(180px,calc(88vw-96px))] md:max-w-[200px] h-auto select-none"
              draggable={false}
            />
            <div
              className="text-[10px] tracking-wide font-semibold"
              style={{ color: "hsl(var(--primary))" }}
            >
              Top Finance - Live
            </div>
          </Link>
        </div>

        <nav className="px-3 py-4 max-md:py-3 flex-1 min-h-0 flex flex-col gap-0.5 overflow-y-auto">
          {/* 1) Přehled vždy nahoře */}
          <NavLink
            to={NAV_OVERVIEW.to}
            data-testid={NAV_OVERVIEW.testid}
            onClick={() => onNavigate?.()}
            className={`nav-link ${
              loc.pathname === NAV_OVERVIEW.to ||
              (NAV_OVERVIEW.to !== "/" && loc.pathname.startsWith(NAV_OVERVIEW.to))
                ? "active"
                : ""
            }`}
          >
            <OverviewIcon className="h-4 w-4" strokeWidth={1.5} />
            <span>{t(NAV_OVERVIEW.labelKey)}</span>
          </NavLink>

          {/* 2) Sekce (veřejné stránky z administrace) */}
          {sections.length > 0 && (
            <>
              <div
                className="px-3 pt-6 pb-2 text-[10px] uppercase tracking-[0.08em] md:tracking-[0.16em] flex items-center gap-2 font-semibold"
                style={{ color: "hsl(var(--primary))" }}
              >
                <Layers className="h-3 w-3" strokeWidth={1.8} aria-hidden />
                {t("nav.sections")}
              </div>
              {sections.map((s) => {
                const Icon = SECTION_ICONS[s.icon] || Folder;
                const to = `/s/${s.slug}`;
                const active = loc.pathname === to;
                const sectionId = String(s.id ?? "");
                const sectionLabel = cmsLoc.sectionName(s);
                return (
                  <div
                    key={s.id}
                    className={`flex w-full min-w-0 items-stretch gap-0.5 rounded-xl -mx-0.5 px-0.5 hover:bg-[hsl(var(--muted)/0.25)] ${
                      isAdmin ? "cursor-grab active:cursor-grabbing" : ""
                    }`}
                    draggable={isAdmin}
                    onDragStart={(e) => {
                      if (!isAdmin || !sectionId) return;
                      e.dataTransfer.setData("text/plain", `section:${sectionId}`);
                      e.dataTransfer.effectAllowed = "move";
                    }}
                    onDragOver={(e) => {
                      if (!isAdmin) return;
                      e.preventDefault();
                    }}
                    onDrop={(e) => {
                      if (!isAdmin) return;
                      e.preventDefault();
                      const raw = e.dataTransfer.getData("text/plain");
                      const parts = String(raw || "").split(":");
                      const sourceId = String(parts[1] || "");
                      if (parts[0] !== "section" || !sourceId || sourceId === sectionId) return;
                      const from = sections.findIndex((x) => String(x.id ?? "") === sourceId);
                      const toIdx = sections.findIndex((x) => String(x.id ?? "") === sectionId);
                      if (from < 0 || toIdx < 0 || from === toIdx) return;
                      const next = [...sections];
                      const [moved] = next.splice(from, 1);
                      next.splice(toIdx, 0, moved);
                      setSections(next);
                      api
                        .post("/sections/reorder", { section_ids: next.map((x) => String(x.id ?? "")) })
                        .catch(() => {
                          setSections([...sections]);
                        });
                    }}
                  >
                    {isAdmin ? (
                      <button
                        type="button"
                        draggable
                        onDragStart={(e) => {
                          e.stopPropagation();
                          e.dataTransfer.setData("text/plain", `section:${sectionId}`);
                          e.dataTransfer.effectAllowed = "move";
                        }}
                        className="shrink-0 flex w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 hover:text-slate-700 hover:bg-[hsl(var(--muted)/0.45)] cursor-grab active:cursor-grabbing touch-none"
                        aria-label={t("nav.reorderSection", { name: sectionLabel })}
                        title={t("nav.reorderSectionHint")}
                      >
                        <GripVertical className="h-4 w-4" strokeWidth={1.5} aria-hidden />
                      </button>
                    ) : null}
                    <NavLink
                      to={to}
                      data-testid={`nav-section-${s.slug}`}
                      onClick={() => onNavigate?.()}
                      className={`nav-link min-w-0 flex-1 ${active ? "active" : ""}`}
                      title={sectionLabel}
                      draggable={false}
                    >
                      <Icon className="h-4 w-4" strokeWidth={1.5} />
                      <span className="truncate">{sectionLabel}</span>
                    </NavLink>
                  </div>
                );
              })}
            </>
          )}

          {/* 3) Uživatelské rozhraní: katalog, vlastní data, účet */}
          <div
            className="px-3 pt-6 pb-2 text-[10px] uppercase tracking-[0.08em] md:tracking-[0.16em] flex items-center gap-2 font-semibold"
            style={{ color: "hsl(var(--primary))" }}
          >
            <PanelsTopLeft className="h-3 w-3" strokeWidth={1.8} aria-hidden />
            {t("nav.userInterface")}
          </div>
          {userNavItems.map((n) => {
            const visible = n.visible({ user, isAdmin, isSubscriber });
            if (!visible) return null;
            const active = typeof n.isActive === "function" ? n.isActive(loc.pathname) : false;
            const dndEnabled = Boolean(user);
            const Icon = n.icon;
            const navId = String(n.id ?? "");
            return (
              <div
                key={n.id}
                className={`flex w-full min-w-0 items-stretch gap-0.5 rounded-xl -mx-0.5 px-0.5 hover:bg-[hsl(var(--muted)/0.25)] ${
                  dndEnabled ? "cursor-grab active:cursor-grabbing" : ""
                }`}
                draggable={dndEnabled}
                onDragStart={(e) => {
                  if (!dndEnabled || !navId) return;
                  e.dataTransfer.setData("text/plain", `user-nav:${navId}`);
                  e.dataTransfer.effectAllowed = "move";
                }}
                onDragOver={(e) => {
                  if (!dndEnabled) return;
                  e.preventDefault();
                }}
                onDrop={(e) => {
                  if (!dndEnabled) return;
                  e.preventDefault();
                  const raw = e.dataTransfer.getData("text/plain");
                  const parts = String(raw || "").split(":");
                  const draggedId = String(parts[1] || "");
                  if (parts[0] !== "user-nav" || !draggedId || draggedId === navId) return;
                  const next = reorderIds(userNavIds, draggedId, navId);
                  setUserNavIds(next);
                  api.put("/me/user-nav-order", { order: next }).catch(() => {});
                }}
              >
                {dndEnabled ? (
                  <button
                    type="button"
                    draggable
                    onDragStart={(e) => {
                      e.stopPropagation();
                      e.dataTransfer.setData("text/plain", `user-nav:${navId}`);
                      e.dataTransfer.effectAllowed = "move";
                    }}
                    className="shrink-0 flex w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 hover:text-slate-700 hover:bg-[hsl(var(--muted)/0.45)] cursor-grab active:cursor-grabbing touch-none"
                    aria-label={t("nav.reorderNav", { name: navLabel(n, t) })}
                    title={t("nav.reorderUserNavHint")}
                  >
                    <GripVertical className="h-4 w-4" strokeWidth={1.5} aria-hidden />
                  </button>
                ) : null}
                {n.kind === "button" ? (
                  <button
                    type="button"
                    data-testid={n.testid}
                    onClick={() => closeAfter(openBugReport)}
                    className={`nav-link min-w-0 flex-1 w-full text-left border-0 bg-transparent cursor-pointer font-[inherit] ${
                      active ? "active" : ""
                    }`}
                    draggable={false}
                  >
                    <Icon className="h-4 w-4" strokeWidth={1.5} />
                    <span className="inline-flex items-center gap-1.5">
                      <span>{navLabel(n, t)}</span>
                      {n.id === "nav-user-messages" && chatUnreadCount > 0 ? (
                        <span className="inline-flex h-5 min-w-[1.2rem] items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-semibold text-white">
                          {chatUnreadCount}
                        </span>
                      ) : null}
                    </span>
                  </button>
                ) : (
                  <NavLink
                    to={n.to}
                    data-testid={n.testid}
                    onClick={() => onNavigate?.()}
                    className={`nav-link min-w-0 flex-1 ${active ? "active" : ""}`}
                    draggable={false}
                  >
                    <Icon className="h-4 w-4" strokeWidth={1.5} />
                    <span className="inline-flex items-center gap-1.5">
                      <span>{navLabel(n, t)}</span>
                      {n.id === "nav-user-messages" && chatUnreadCount > 0 ? (
                        <span className="inline-flex h-5 min-w-[1.2rem] items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-semibold text-white">
                          {chatUnreadCount}
                        </span>
                      ) : null}
                    </span>
                  </NavLink>
                )}
              </div>
            );
          })}

          {canEditContent && contentNavItems.length > 0 && (
            <>
              <div className="px-3 pt-7 pb-2 text-[10px] uppercase tracking-[0.08em] md:tracking-[0.16em] flex items-center gap-2 font-semibold" style={{ color: "hsl(var(--primary))" }}>
                <ShieldCheck className="h-3 w-3" strokeWidth={1.8} /> {isAdmin ? t("nav.administration") : t("nav.editorial")}
              </div>
              {contentNavItems.map((n) => {
                const matches = [n.to, ...(n.alsoMatches || [])];
                const active = matches.some(
                  (p) => loc.pathname === p || loc.pathname.startsWith(p + "/")
                );
                return (
                  <div
                    key={n.id}
                    className="flex w-full min-w-0 items-stretch gap-0.5 rounded-xl -mx-0.5 px-0.5 hover:bg-[hsl(var(--muted)/0.25)]"
                    onDragOver={isAdmin ? (e) => e.preventDefault() : undefined}
                    onDrop={
                      isAdmin
                        ? (e) => {
                            e.preventDefault();
                            const dragged = e.dataTransfer.getData("text/plain");
                            if (!dragged || dragged === n.id) return;
                            const next = reorderIds(adminNavIds, dragged, n.id);
                            setAdminNavIds(next);
                            api.put("/me/admin-nav-order", { order: next }).catch(() => {});
                          }
                        : undefined
                    }
                  >
                    {isAdmin ? (
                      <button
                        type="button"
                        draggable
                        onDragStart={(e) => {
                          e.dataTransfer.setData("text/plain", n.id);
                          e.dataTransfer.effectAllowed = "move";
                        }}
                        className="shrink-0 flex w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 hover:text-slate-700 hover:bg-[hsl(var(--muted)/0.45)] cursor-grab active:cursor-grabbing touch-none"
                        aria-label={t("nav.reorderNav", { name: navLabel(n, t) })}
                        title={t("nav.reorderAdminNavHint")}
                      >
                        <GripVertical className="h-4 w-4" strokeWidth={1.5} aria-hidden />
                      </button>
                    ) : null}
                    <NavLink
                      to={n.to}
                      data-testid={n.testid}
                      onClick={() => onNavigate?.()}
                      className={`nav-link min-w-0 flex-1 ${active ? "active" : ""} ${isAdmin ? "" : "ml-0.5"}`}
                      draggable={false}
                    >
                      <n.icon className="h-4 w-4 shrink-0" strokeWidth={1.5} />
                      <span className="truncate">{navLabel(n, t)}</span>
                    </NavLink>
                  </div>
                );
              })}
            </>
          )}
        </nav>

        <SidebarPodcastPlayer onNavigate={onNavigate} />

        <div className="shrink-0 px-3 pt-3 pb-2 border-t border-[hsl(var(--border)/0.45)] overflow-x-hidden space-y-3">
          <div>
            <div className="px-1 pb-2 text-[10px] uppercase tracking-[0.08em] md:tracking-[0.14em] font-semibold text-muted-foreground">
              {t("locale.label")}
            </div>
            <LocaleSwitcher variant="sidebar" />
          </div>
          {onPickBackgroundTheme ? (
          <div>
            <div className="px-1 pb-2 text-[10px] uppercase tracking-[0.08em] md:tracking-[0.14em] font-semibold text-muted-foreground">
              {t("nav.appearance")}
            </div>
            <AppearanceThemePicker
              variant="sidebar"
              presets={PRIMARY_APPEARANCE_PRESETS}
              selectedId={backgroundThemeId}
              onSelect={(id) => {
                onPickBackgroundTheme(id);
                onNavigate?.();
              }}
              contentProps={{ side: "right", align: "start", sideOffset: 12, className: "z-[140]" }}
            />
          </div>
          ) : null}
        </div>

        {sidebarAdSlot && (
          <div
            className="shrink-0 px-3 pt-1.5 pb-2 border-t border-[hsl(var(--border)/0.4)]"
            data-testid="ad-slot-sidebar"
            aria-label={t("nav.adSpace")}
          >
            <div className="rounded-xl border border-[hsl(var(--border)/0.65)] shadow-[0_4px_14px_hsl(var(--foreground)/0.08)] overflow-hidden h-52 max-h-[46vh] bg-[hsl(var(--foreground)/0.04)]">
              <div className="h-full w-full min-h-0 [&_a]:block [&_a]:h-full [&_a]:min-h-0">
                <AdWidget data={sidebarAdSlot} slotMode layout="sidebar" />
              </div>
            </div>
          </div>
        )}

        <div className="mt-auto shrink-0 px-3 py-3 max-md:py-3 border-t border-[hsl(var(--border)/0.45)] bg-[hsl(var(--muted)/0.25)]">
          {user ? (
            <div className="flex items-center gap-2 min-w-0">
              {accountStatus ? (
                <span
                  data-testid="sidebar-account-status"
                  data-account-role={accountStatus.kind}
                  className={`inline-flex min-w-0 flex-1 items-center justify-center rounded-xl border px-2.5 py-2 text-xs sm:text-[13px] font-bold leading-tight tracking-tight truncate shadow-sm ${accountBadgeToneClass(accountStatus.kind)}`}
                  title={t(accountStatus.labelKey)}
                >
                  {t(accountStatus.labelKey)}
                </span>
              ) : null}
              <button
                data-testid="sidebar-logout-btn"
                onClick={() => {
                  logout();
                  onNavigate?.();
                }}
                className="shrink-0 flex h-9 w-9 items-center justify-center rounded-xl border border-[hsl(var(--border)/0.65)] bg-[hsl(var(--card))] shadow-sm transition-all text-[hsl(var(--muted-foreground))] hover:text-[hsl(var(--foreground))] hover:bg-[hsl(var(--primary-soft)/0.85)]"
                title={t("nav.logout")}
              >
                <LogOut className="h-4 w-4" strokeWidth={1.8} />
              </button>
            </div>
          ) : (
            <>
              {accountStatus ? (
                <div className="mb-2 px-0.5 min-w-0">
                  <span
                    data-testid="sidebar-account-status"
                    data-account-role={accountStatus.kind}
                    className={`inline-flex max-w-full items-center justify-center rounded-xl border px-2.5 py-2 text-xs sm:text-[13px] font-bold leading-tight tracking-tight truncate shadow-sm ${accountBadgeToneClass(accountStatus.kind)}`}
                    title={t(accountStatus.labelKey)}
                  >
                    {t(accountStatus.labelKey)}
                  </span>
                </div>
              ) : null}
              <button
                data-testid="sidebar-login-btn"
                type="button"
                onClick={() => closeAfter(openLogin)}
                className="w-full min-h-[44px] flex items-center justify-center gap-2.5 rounded-xl px-4 py-2.5 text-sm font-semibold transition-colors border border-[hsl(var(--primary)/0.35)] bg-[hsl(var(--primary-soft)/0.65)] text-[hsl(var(--primary-deep))] shadow-sm hover:bg-[hsl(var(--primary-soft))] hover:border-[hsl(var(--primary)/0.5)]"
                title={t("nav.loginTitle")}
                aria-label={t("nav.login")}
              >
                <LogIn className="h-5 w-5 shrink-0" strokeWidth={1.8} />
                <span className="truncate">{t("nav.login")}</span>
              </button>
            </>
          )}
        </div>
      </div>
      {onToggleDesktopCollapsed ? (
        <button
          type="button"
          onClick={onToggleDesktopCollapsed}
          className="absolute inset-y-3 right-3 hidden w-3 items-center justify-center rounded-r-full rounded-l-md border border-l-0 border-[hsl(var(--border)/0.82)] bg-[hsl(var(--card)/0.95)] text-[hsl(var(--muted-foreground))] shadow-sm transition hover:w-3.5 hover:bg-[hsl(var(--primary-soft))] hover:text-[hsl(var(--foreground))] md:flex"
          title={t("nav.hideSidebar")}
          aria-label={t("nav.hideSidebar")}
          data-testid="sidebar-collapse-toggle"
        >
          <ChevronLeft className="h-3 w-3" strokeWidth={2} aria-hidden />
        </button>
      ) : null}
    </aside>
  );
}
