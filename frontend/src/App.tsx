/**
 * BankIntel v2 — hlavní routing aplikace.
 *
 * Stránky jsou v `src/features/<modul>/pages/` — viz `src/features/README.md`.
 * Každý modul má vlastní README s popisem routes a backend API.
 */
import { lazy, Suspense } from "react";
import { BrowserRouter, Routes, Route, Navigate, useLocation, useParams } from "react-router-dom";
import "@/App.css";
import { AuthProvider } from "@/contexts/AuthContext";
import { FeatureAccessProvider } from "@/contexts/FeatureAccessContext";
import { AdminRoute, AuthRoute, EditorRoute, SubscriberRoute } from "@/components/ProtectedRoute";
import LoginModal from "@/components/LoginModal";
import CookieBanner from "@/components/CookieBanner";
import { Toaster } from "sonner";
import { BugReportProvider } from "@/contexts/BugReportContext";
import { PodcastPlayerProvider } from "@/contexts/PodcastPlayerContext";
import ExploreErrorBoundary from "@/features/manager-explorer/components/explore/ExploreErrorBoundary";

import DashboardPage from "@/features/dashboard/pages/DashboardPage";
import SectionPage from "@/features/homepage/pages/SectionPage";

const SourcesPage = lazy(() => import("@/features/admin/pages/SourcesPage"));
const SourceFormPage = lazy(() => import("@/features/admin/pages/SourceFormPage"));
const RecordsPage = lazy(() => import("@/features/admin/pages/RecordsPage"));
const FormulasPage = lazy(() => import("@/features/admin/pages/FormulasPage"));
const SyncLogsPage = lazy(() => import("@/features/admin/pages/SyncLogsPage"));
const ExportsPage = lazy(() => import("@/features/admin/pages/ExportsPage"));
const UsersPage = lazy(() => import("@/features/admin/pages/UsersPage"));
const UserDetailPage = lazy(() => import("@/features/admin/pages/UserDetailPage"));
const SettingsPage = lazy(() => import("@/features/auth/pages/SettingsPage"));
const HomepageEditorPage = lazy(() => import("@/features/dashboard/pages/HomepageEditorPage"));
const AdSlotsPage = lazy(() => import("@/features/admin/pages/AdSlotsPage"));
const FeatureAccessPage = lazy(() => import("@/features/admin/pages/FeatureAccessPage"));
const ComputedPage = lazy(() => import("@/features/admin/pages/ComputedPage"));
const AradCatalogPage = lazy(() => import("@/features/catalog-browsers/pages/AradCatalogPage"));
const EurostatCatalogPage = lazy(() => import("@/features/catalog-browsers/pages/EurostatCatalogPage"));
const CsuCatalogPage = lazy(() => import("@/features/catalog-browsers/pages/CsuCatalogPage"));
const FredCatalogPage = lazy(() => import("@/features/catalog-browsers/pages/FredCatalogPage"));
const Data360CatalogPage = lazy(() => import("@/features/catalog-browsers/pages/Data360CatalogPage"));
const BisCatalogPage = lazy(() => import("@/features/catalog-browsers/pages/BisCatalogPage"));
const ImfCatalogPage = lazy(() => import("@/features/catalog-browsers/pages/ImfCatalogPage"));
const OecdCatalogPage = lazy(() => import("@/features/catalog-browsers/pages/OecdCatalogPage"));
const TradingEconomicsBrowserPage = lazy(() => import("@/features/catalog-browsers/pages/TradingEconomicsBrowserPage"));
const GlobalCatalogSearchPage = lazy(() => import("@/features/ai-search/pages/GlobalCatalogSearchPage"));
const StockSearchPage = lazy(() => import("@/features/ai-search/pages/StockSearchPage"));
const CatalogTopicBrowsePage = lazy(() => import("@/features/ai-search/pages/CatalogTopicBrowsePage"));
const CommoditiesPage = lazy(() => import("@/features/catalog-browsers/pages/CommoditiesPage"));
const ExplorePage = lazy(() => import("@/features/manager-explorer/pages/ExplorePage"));
const SearchPage = lazy(() => import("@/features/ai-search/pages/SearchPage"));
const MyDashboardPage = lazy(() => import("@/features/dashboard/pages/MyDashboardPage"));
const SharedDashboardPage = lazy(() => import("@/features/dashboard/pages/SharedDashboardPage"));
const MyDataPage = lazy(() => import("@/features/dashboard/pages/MyDataPage"));
const MyRssFeedsPage = lazy(() => import("@/features/homepage/pages/MyRssFeedsPage"));
const CookiesPage = lazy(() => import("@/features/static/pages/CookiesPage"));
const PredplatnePage = lazy(() => import("@/features/static/pages/PredplatnePage"));
const OchranaOsobnichUdajuPage = lazy(() => import("@/features/static/pages/OchranaOsobnichUdajuPage"));
const ObchodniPodminkyPage = lazy(() => import("@/features/static/pages/ObchodniPodminkyPage"));
const VerifyEmailPage = lazy(() => import("@/features/auth/pages/VerifyEmailPage"));
const ResetPasswordPage = lazy(() => import("@/features/auth/pages/ResetPasswordPage"));
const ArchivePage = lazy(() => import("@/features/archive-reader/pages/ArchivePage"));
const ArchiveMagazinePage = lazy(() => import("@/features/archive-reader/pages/ArchiveMagazinePage"));
const ArchiveIssueReaderPage = lazy(() => import("@/features/archive-reader/pages/ArchiveIssueReaderPage"));
const MessagesPage = lazy(() => import("@/features/homepage/pages/MessagesPage"));
const AdminBugReportsPage = lazy(() => import("@/features/admin/pages/AdminBugReportsPage"));
const AdminArticlesPage = lazy(() => import("@/features/articles/pages/AdminArticlesPage"));
const ArticlesPage = lazy(() => import("@/features/articles/pages/ArticlesPage"));
const ArticleDetailPage = lazy(() => import("@/features/articles/pages/ArticleDetailPage"));
const AdminRssFeedsPage = lazy(() => import("@/features/admin/pages/AdminRssFeedsPage"));
const PodcastsPage = lazy(() => import("@/features/podcasts/pages/PodcastsPage"));
const PodcastShowPage = lazy(() => import("@/features/podcasts/pages/PodcastShowPage"));
const EmbedWidgetPage = lazy(() => import("@/features/dashboard/pages/EmbedWidgetPage"));
const MobileWidgetChartEmbedPage = lazy(() => import("@/features/dashboard/pages/MobileWidgetChartEmbedPage"));
const ChartSystemDemo = lazy(() => import("@/charts/ChartSystemDemo"));

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <BugReportProvider>
        <PodcastPlayerProvider>
        <FeatureAccessProvider>
        <Toaster position="top-right" richColors />
        <LoginModal />
        <CookieBanner />
        <Suspense fallback={null}>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/s/:slug" element={<SectionPage />} />
          <Route path="/s/:slug/:pageSlug" element={<SectionPage />} />
          <Route path="/m/widget-chart" element={<MobileWidgetChartEmbedPage />} />
          <Route path="/embed/:token/:widgetId" element={<EmbedWidgetPage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/search/catalog" element={<GlobalCatalogSearchPage />} />
          <Route path="/search/catalog/topics" element={<CatalogTopicBrowsePage />} />
          <Route path="/search/stocks" element={<StockSearchPage />} />
          <Route path="/search/akcie" element={<Navigate to="/search/stocks" replace />} />
          <Route
            path="/explore"
            element={
              <ExploreErrorBoundary>
                <ExplorePage />
              </ExploreErrorBoundary>
            }
          />
          <Route path="/dev/charts" element={<ChartSystemDemo />} />
          <Route path="/chart-system-demo" element={<ChartSystemDemo />} />
          <Route path="/commodities" element={<CommoditiesPage />} />
          <Route path="/bis/catalog" element={<BisCatalogPage />} />
          <Route path="/fred/catalog" element={<FredCatalogPage />} />
          <Route path="/imf/catalog" element={<ImfCatalogPage />} />
          <Route path="/oecd/catalog" element={<OecdCatalogPage />} />
          <Route path="/oecd3/catalog" element={<Navigate to="/search/catalog?catalog=oecd3" replace />} />
          <Route path="/trading-economics/catalog" element={<TradingEconomicsBrowserPage />} />
          <Route path="/data360/catalog" element={<Data360CatalogPage />} />
          <Route path="/arad/catalog" element={<LegacyCatalogRedirect catalog="arad" />} />
          <Route path="/csu/catalog" element={<LegacyCatalogRedirect catalog="csu" />} />
          <Route path="/eurostat/catalog" element={<LegacyCatalogRedirect catalog="eurostat" />} />
          <Route path="/worldbank/catalog" element={<LegacyCatalogRedirect catalog="data360" />} />
          <Route path="/ecb2/browse-tree" element={<LegacyCatalogRedirect catalog="ecb2" />} />
          <Route path="/imf/browse-tree" element={<LegacyCatalogRedirect catalog="imf" />} />
          <Route path="/imf2/browse-tree" element={<LegacyCatalogRedirect catalog="imf" />} />
          <Route path="/oecd2/browse-tree" element={<LegacyCatalogRedirect catalog="oecd4" />} />
          <Route path="/oecd4/browse-tree" element={<LegacyCatalogRedirect catalog="oecd4" />} />
          <Route path="/commodities/catalog" element={<LegacyCatalogRedirect catalog="commodities" />} />
          <Route path="/my-data" element={<SubscriberRoute><MyDataPage /></SubscriberRoute>} />
          <Route path="/my-dashboard" element={<SubscriberRoute><MyDashboardPage /></SubscriberRoute>} />
          <Route path="/shared/dashboard/:token" element={<SharedDashboardPage />} />
          <Route path="/shared/page/:pageId" element={<SubscriberRoute><SharedDashboardPage /></SubscriberRoute>} />
          <Route path="/my-rss" element={<SubscriberRoute><MyRssFeedsPage /></SubscriberRoute>} />
          <Route path="/cookies" element={<CookiesPage />} />
          <Route path="/predplatne" element={<PredplatnePage />} />
          <Route path="/ochrana-osobnich-udaju" element={<OchranaOsobnichUdajuPage />} />
          <Route path="/obchodni-podminky" element={<ObchodniPodminkyPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/zpravy" element={<ArticlesPage />} />
          <Route path="/zpravy/:articleId" element={<ArticleDetailPage />} />
          <Route path="/archive" element={<ArchivePage />} />
          <Route path="/archive/:magazineId" element={<ArchiveMagazinePage />} />
          <Route path="/archive/:magazineId/:issueId" element={<ArchiveIssueReaderPage />} />
          <Route path="/podcasty" element={<PodcastsPage />} />
          <Route path="/podcasty/:showId" element={<PodcastShowPage />} />
          <Route path="/admin/archive" element={<AdminRoute><ArchivePage manage /></AdminRoute>} />
          {/* ArchiveMagazinePage bere správu z useAuth().isAdmin, žádný `manage` prop nemá —
              předávat ho byl zbytek, který nic nedělal a rozbíjel `tsc` (a tím produkční build). */}
          <Route path="/admin/archive/:magazineId" element={<AdminRoute><ArchiveMagazinePage /></AdminRoute>} />
          <Route path="/messages" element={<AuthRoute><MessagesPage /></AuthRoute>} />
          <Route path="/records" element={<AdminRoute><RecordsPage /></AdminRoute>} />
          <Route path="/formulas" element={<AdminRoute><FormulasPage /></AdminRoute>} />
          <Route path="/exports" element={<AdminRoute><ExportsPage /></AdminRoute>} />
          <Route path="/computed" element={<AdminRoute><ComputedPage /></AdminRoute>} />
          <Route path="/sources" element={<AdminRoute><SourcesPage /></AdminRoute>} />
          <Route path="/sources/catalog-search" element={<Navigate to="/search/catalog" replace />} />
          <Route path="/sources/catalog" element={<AdminRoute fallbackTo="/search/catalog?catalog=arad" preserveSearch><AradCatalogPage /></AdminRoute>} />
          <Route path="/sources/eurostat" element={<AdminRoute fallbackTo="/search/catalog?catalog=eurostat" preserveSearch><EurostatCatalogPage /></AdminRoute>} />
          <Route path="/sources/csu" element={<AdminRoute fallbackTo="/search/catalog?catalog=csu" preserveSearch><CsuCatalogPage /></AdminRoute>} />
          <Route path="/sources/fred" element={<AdminRoute fallbackTo="/fred/catalog" preserveSearch><FredCatalogPage /></AdminRoute>} />
          <Route path="/sources/data360" element={<AdminRoute fallbackTo="/data360/catalog" preserveSearch><Data360CatalogPage /></AdminRoute>} />
          <Route path="/sources/bis" element={<AdminRoute fallbackTo="/bis/catalog" preserveSearch><BisCatalogPage /></AdminRoute>} />
          <Route path="/sources/imf" element={<AdminRoute fallbackTo="/imf/catalog" preserveSearch><ImfCatalogPage /></AdminRoute>} />
          <Route path="/sources/oecd" element={<AdminRoute fallbackTo="/oecd/catalog" preserveSearch><OecdCatalogPage /></AdminRoute>} />
          <Route path="/sources/oecd3" element={<AdminRoute><Navigate to="/search/catalog?catalog=oecd3" replace /></AdminRoute>} />
          <Route path="/sources/trading-economics" element={<AdminRoute><TradingEconomicsBrowserPage /></AdminRoute>} />
          <Route path="/sources/commodities" element={<AdminRoute><CommoditiesPage /></AdminRoute>} />
          <Route path="/sources/:id" element={<AdminRoute><SourceFormPage /></AdminRoute>} />
          <Route path="/users/:id" element={<AdminRoute><UserDetailPage /></AdminRoute>} />
          <Route path="/users" element={<AdminRoute><UsersPage /></AdminRoute>} />
          <Route path="/settings" element={<AuthRoute><SettingsPage /></AuthRoute>} />
          <Route path="/admin/homepage" element={<EditorRoute><HomepageEditorPage /></EditorRoute>} />
          <Route path="/admin/ads" element={<AdminRoute><AdSlotsPage /></AdminRoute>} />
          <Route path="/admin/feature-access" element={<AdminRoute><FeatureAccessPage /></AdminRoute>} />
          <Route path="/admin/rss-feeds" element={<AdminRoute><AdminRssFeedsPage /></AdminRoute>} />
          <Route path="/admin/bug-reports" element={<AdminRoute><AdminBugReportsPage /></AdminRoute>} />
          <Route path="/admin/articles" element={<EditorRoute><AdminArticlesPage /></EditorRoute>} />
          <Route path="/sync-logs" element={<AdminRoute><SyncLogsPage /></AdminRoute>} />
          <Route path="/admin/sections" element={<Navigate to="/admin/homepage" replace />} />
          <Route path="/admin/sections/:slug" element={<LegacySectionRedirect />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        </Suspense>
        </FeatureAccessProvider>
        </PodcastPlayerProvider>
        </BugReportProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}

function LegacySectionRedirect() {
  const { slug } = useParams();
  return <Navigate to={`/admin/homepage?target=${slug || ""}`} replace />;
}

function LegacyCatalogRedirect({ catalog }: { catalog: string }) {
  const { search } = useLocation();
  const params = new URLSearchParams(search);
  params.set("catalog", catalog);
  return <Navigate to={`/search/catalog?${params.toString()}`} replace />;
}
