# Migration map — Python → Java

Reference app: `../Bankoapp-main/Bankoapp-main/backend/`  
Target app: `BankIntel-v2/backend-java/`

Status legend: `[ ]` todo · `[~]` skeleton · `[x]` done

## Package layout

```
cz.bankintel
├── config/           Security, CORS, properties
├── security/         JWT cookies, CSRF (TODO), CurrentUser
├── controller/       Thin REST layer (mirrors routes/*.py)
├── service/          Business logic (mirrors services/*.py)
├── connector/        External data sources (mirrors connectors/*.py)
├── repository/       Mongo repositories
├── domain/
│   ├── document/     Mongo @Document entities
│   └── dto/          Request/response records
├── search/           AI catalog search pipeline (Java rewrite)
├── explore/          Manager Explorer pipeline (Java rewrite)
├── export/           Excel/PDF
└── scheduler/        Sync jobs (Quartz / @Scheduled)
```

## Route modules (66)

| Status | Python route | API prefix | Java controller |
|--------|--------------|------------|-----------------|
| [~] | `auth_routes.py` | `/api/auth` | `controller.auth.AuthController` |
| [ ] | `app_settings_routes.py` | `/api` | `controller.AppSettingsController` |
| [ ] | `sources_routes.py` | `/api/sources` | `controller.sources.SourcesController` |
| [ ] | `sync_routes.py` | `/api/sync` | `controller.sources.SyncController` |
| [ ] | `datasets_routes.py` | `/api` | `controller.bi.DatasetsController` |
| [ ] | `formulas_routes.py` | `/api/formulas` | `controller.bi.FormulasController` |
| [ ] | `dashboard_routes.py` | `/api/dashboard` | `controller.bi.DashboardController` |
| [ ] | `export_routes.py` | `/api/export` | `controller.export.ExportController` |
| [ ] | `users_routes.py` | `/api/users` | `controller.admin.UsersController` |
| [ ] | `user_data_routes.py` | `/api/user-data` | `controller.me.UserDataController` |
| [ ] | `admin_subscriber_routes.py` | `/api/admin` | `controller.admin.AdminSubscriberController` |
| [ ] | `admin_audit_routes.py` | `/api/admin` | `controller.admin.AdminAuditController` |
| [ ] | `admin_sync_jobs_routes.py` | `/api/admin` | `controller.admin.AdminSyncJobsController` |
| [ ] | `admin_catalog_index_routes.py` | `/api/admin` | `controller.admin.AdminCatalogIndexController` |
| [ ] | `feature_access_routes.py` | `/api/feature-access` | `controller.admin.FeatureAccessController` |
| [ ] | `bug_report_routes.py` | `/api/bug-reports` | `controller.support.BugReportController` |
| [ ] | `admin_bug_reports_routes.py` | `/api/admin/bug-reports` | `controller.admin.AdminBugReportsController` |
| [ ] | `me_routes.py` | `/api/me` | `controller.me.MeController` |
| [ ] | `shared_dashboard_routes.py` | `/api/dashboard-share` | `controller.me.SharedDashboardController` |
| [ ] | `my_series_routes.py` | `/api/my-series` | `controller.me.MySeriesController` |
| [ ] | `rss_routes.py` | `/api/rss` | `controller.content.RssController` |
| [ ] | `podcast_routes.py` | `/api/podcasts` | `controller.content.PodcastController` |
| [ ] | `homepage_routes.py` | `/api/homepage` | `controller.content.HomepageController` |
| [ ] | `media_routes.py` | `/api/media` | `controller.content.MediaController` |
| [ ] | `sections_routes.py` | `/api/sections` | `controller.content.SectionsController` |
| [ ] | `computed_routes.py` | `/api/computed` | `controller.bi.ComputedController` |
| [ ] | `calculation_routes.py` | `/api/calculations` | `controller.bi.CalculationController` |
| [ ] | `chart_agent_routes.py` | `/api/chart-agent` | `controller.ai.ChartAgentController` |
| [ ] | `arad_catalog_routes.py` | `/api/arad/catalog` | `controller.catalog.AradCatalogController` |
| [ ] | `eurostat_catalog_routes.py` | `/api/eurostat/catalog` | `controller.catalog.EurostatCatalogController` |
| [ ] | `eurostat_dimension_routes.py` | `/api/eurostat` | `controller.catalog.EurostatDimensionController` |
| [ ] | `csu_catalog_routes.py` | `/api/csu/catalog` | `controller.catalog.CsuCatalogController` |
| [ ] | `ecb_browser_routes.py` | `/api/ecb` | `controller.catalog.EcbBrowserController` |
| [ ] | `ecb2_browser_routes.py` | `/api/ecb2` | `controller.catalog.Ecb2BrowserController` |
| [ ] | `imf2_browser_routes.py` | `/api/imf2` | `controller.catalog.Imf2BrowserController` |
| [ ] | `oecd2_browser_routes.py` | `/api/oecd2` | `controller.catalog.Oecd2BrowserController` |
| [ ] | `oecd3_browser_routes.py` | `/api/oecd3` | `controller.catalog.Oecd3BrowserController` |
| [ ] | `oecd4_catalog_routes.py` | `/api/oecd4` | `controller.catalog.Oecd4CatalogController` |
| [ ] | `fred_catalog_routes.py` | `/api/fred/catalog` | `controller.catalog.FredCatalogController` |
| [ ] | `fred_proxy_routes.py` | `/api/fred` | `controller.catalog.FredProxyController` |
| [ ] | `data360_catalog_routes.py` | `/api/data360/catalog` | `controller.catalog.Data360CatalogController` |
| [ ] | `bis_catalog_routes.py` | `/api/bis/catalog` | `controller.catalog.BisCatalogController` |
| [ ] | `bis_proxy_routes.py` | `/api/bis` | `controller.catalog.BisProxyController` |
| [ ] | `imf_catalog_routes.py` | `/api/imf/catalog` | `controller.catalog.ImfCatalogController` |
| [ ] | `imf_browser_routes.py` | `/api/imf` | `controller.catalog.ImfBrowserController` |
| [ ] | `oecd_catalog_routes.py` | `/api/oecd/catalog` | `controller.catalog.OecdCatalogController` |
| [ ] | `alphavantage_catalog_routes.py` | `/api/alphavantage/catalog` | `controller.catalog.AlphaVantageCatalogController` |
| [ ] | `yahoo_finance_catalog_routes.py` | `/api/yahoo_finance` | `controller.catalog.YahooFinanceCatalogController` |
| [ ] | `tradingeconomics_catalog_routes.py` | `/api/tradingeconomics/catalog` | `controller.catalog.TradingEconomicsCatalogController` |
| [ ] | `trading_economics_routes.py` | `/api/trading-economics` | `controller.catalog.TradingEconomicsController` |
| [ ] | `te_country_router.py` | `/api/te/country` | `controller.catalog.TeCountryController` |
| [ ] | `te_indicator_router.py` | `/api/te/indikator` | `controller.catalog.TeIndicatorController` |
| [~] | `catalog_deep_search_routes.py` | `/api/catalog` | `controller.catalog.CatalogDeepSearchController` |
| [ ] | `catalog_preview_routes.py` | `/api/catalog` | `controller.catalog.CatalogPreviewController` |
| [ ] | `catalog_suggest_routes.py` | `/api/catalog` | `controller.catalog.CatalogSuggestController` |
| [ ] | `catalog_availability_routes.py` | `/api/catalog/availability` | `controller.catalog.CatalogAvailabilityController` |
| [ ] | `macro_topic_routes.py` | `/api/catalog/macro-topics` | `controller.catalog.MacroTopicController` |
| [ ] | `stock_search_routes.py` | `/api/stocks` | `controller.catalog.StockSearchController` |
| [ ] | `commodities_routes.py` | `/api/commodities` | `controller.catalog.CommoditiesController` |
| [~] | `explore_routes.py` | `/api/explore` | `controller.explore.ExploreController` |
| [ ] | `ad_slots_routes.py` | `/api/ad-slots` | `controller.content.AdSlotsController` |
| [ ] | `magazines_routes.py` | `/api/magazines` | `controller.content.MagazinesController` |
| [ ] | `articles_routes.py` | `/api/articles` | `controller.content.ArticlesController` |
| [ ] | `chat_routes.py` | `/api/chat` | `controller.ai.ChatController` |

## Large services to split during port

| Python file | Lines | Java target packages |
|-------------|------:|----------------------|
| `services/catalog_deep_search.py` | ~11k | `search/planning`, `search/lanes`, `search/scoring`, `search/verify`, `search/sse` |
| `routes/explore_routes.py` | ~7.8k | `controller.explore` + `explore/*` |
| `services/catalog_global_search.py` | ~5.1k | `search/classic` |
| `services/explore_manager.py` | ~5.1k | `explore/orchestrator` |
| `services/manager_segment_bundles.py` | ~4.8k | `explore/segments` (config-driven) |
| `routes/catalog_preview_routes.py` | ~4.3k | `controller.catalog` + `search/preview` |
| `services/explore_analysis_insights.py` | ~4.1k | `explore/insights` |

## Models (`models.py` → `domain/dto`)

Port Pydantic models as Java records in batches:

1. Auth/users — `AuthDtos`, `UserDtos` `[~]`
2. Sources/sync/datasets/records/formulas
3. Dashboards/homepage/widgets
4. Content (articles, magazines, RSS, podcasts)
5. Catalog/search DTOs
6. Explore/chart-agent DTOs

## Database

| Legacy (reference) | v2 target |
|--------------------|-----------|
| MongoDB collections | PostgreSQL tables + JSONB where needed |
| GridFS uploads | PostgreSQL bytea or S3 (TBD) |
| SQLite catalog FTS index | Same index files on disk or PG full-text (TBD) |

Flyway migrations: `backend-java/src/main/resources/db/migration/`

## Connectors (`connectors/` → `connector/`)

Each Python connector → Java `@Component` implementing a common interface:

```java
public interface DataConnector {
    String type();
    ConnectorTestResult test(SourceConfig config);
    SyncResult sync(SourceConfig config, Instant since);
}
```

Registry mirrors `connectors/registry.py`.

## Frontend route map

See `frontend/src/app/routes.tsx` — 1:1 paths from `frontend/src/App.js` in the original app.

Target structure:

```
frontend/src/
├── app/           providers, routes
├── pages/         thin page containers
├── features/      catalog, explore, dashboard, archive, admin…
├── components/ui/ shadcn primitives
├── hooks/
├── lib/api/       typed client
└── styles/        design tokens (from index.css :root)
```

## What we do NOT port

- `backend/scripts/` audit/dev one-offs
- `backend/reports/`, large JSON dumps in `backend/data/` (read same files at runtime)
- Experiment folders in workspace root
- Mobile app (phase 2)
