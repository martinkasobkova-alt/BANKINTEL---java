# Mapa Manager Exploreru (MANAGER_EXPLORER_MAP)

Manager Explorer je „průzkumník sektorů" — z volného dotazu (nebo sektor + země) objeví relevantní
datové zdroje/řady napříč institucemi a vykreslí je jako report s interaktivními grafy. Navazuje na
[APP_MAP.md](APP_MAP.md) a [SEARCH_MAP.md](SEARCH_MAP.md).

Route: `/explore`. Vstup: `frontend/src/features/manager-explorer/index.js` → `pages/ExplorePage.jsx`.

## 1. Co dělá pro uživatele

1. Uživatel napíše dotaz (nebo zvolí sektor/segment + zemi).
2. Backend odvodí segment + geografii a spustí **multi-source deep-search** (objeví „managery"/zdroje
   — EUROSTAT, ECB, IMF, OECD, ČSÚ/ARAD, BIS, World Bank, FRED — a jejich řady).
3. Výsledky se vykreslí jako report karty rozdělené na `sector_indicators` vs `macro_indicators`.
4. Uživatel může: rozkliknout řadu do interaktivního grafu, měnit období, přidat porovnání (jiné
   země, FX páry), zeptat se AI „co ukazatel znamená", načíst příbuzné řady, ptát se na sekci.

## 2. Frontend

- **Stránka:** `pages/ExplorePage.jsx` (monolit ~6500 řádků) — orchestruje: query-understanding →
  preset-preview → SSE stream → summarize; drží stav porovnání, karty grafů, progress „scanner",
  návrhy příbuzných.
- **Komponenty** (`components/explore/`):
  - `ExploreInteractiveSeriesDetail.jsx` — fullscreen modal s interaktivním grafem (React portal).
  - `ExploreReportChart.jsx` / `ExploreManagerChartCard.jsx` — report karty nad `@/charts/ChartRenderer`.
  - `SeriesConceptExplainTrigger.jsx` — ✨ „AI nad řadou" (vysvětlení, příbuzné, follow-up); exportuje
    `buildExploreChartConceptExplainMeta` a `buildWidgetConceptExplainMeta`.
  - `ExploreSectionFollowup.jsx` (+ `ExploreInlineCatalogPicker.jsx`) — AI follow-up nad sekcí.
  - Další: `ExploreAnalysisInsights`, `ExploreManagerInterpretationPanel`, `ExploreCountrySelect`,
    `ManagerSectorHierarchyEditor`, `ExploreErrorBoundary`, …
- **Lib helpery** (`frontend/src/lib/`):
  - `exploreSectorStream.js` — řídí SSE analytický stream (`runExploreSectorStream`).
  - `exploreInteractiveCatalog.js` — `buildExploreInteractiveCatalogContext(series)` převede řadu
    z Exploreru zpět na kanonický katalogový řádek (přes `compare_ref`), aby detail uměl načíst živý náhled.
  - `catalogLivePreview.js` — `fetchCatalogLivePreview` → `POST /catalog/preview`.
  - `catalogSeriesConceptExplain.js`, `catalogRelatedSeries.js`, `chartDataQuality.js`.
- **„Cold path"** = necachovaná živá deep-search cesta (bez čtení/zápisu discovery cache), zapíná se
  `force_live_deep_search:true` a jen pod profilem `cold-path-profile`. Benchmark:
  `tools/manager-explorer-cold-path-benchmark.mjs` (POST `/api/explore/sector`; ověřuje, že to není `cache_hit`).

## 3. Backend

Controller: `controller/explore/ExploreController.java`, base `@RequestMapping("/api/explore")`:

| Endpoint | Service |
|----------|---------|
| `GET /geo-options` | `ExploreGeoCatalog` |
| `POST /sector` | `ExploreSectorService.analyzeSector` (hlavní analýza; používá i cold-path benchmark) |
| `GET /sector/stream` (SSE) | `ExploreStreamService.streamSector` (cesta, kterou reálně používá UI) |
| `GET /sector/preset-preview` | `ExplorePresetPreviewService` |
| `POST /summarize` (+ status/detail) | `ExploreSummarizeService` (async job store) |
| `POST /related-suggestions`, `/country-suggestions`, `/query-understanding`, `/sector/refine` | `ExploreAuxiliaryService` |

**Discovery pipeline:** `ExploreSectorService` → `ExploreDiscoveryService` (`discover()` cachovaně přes
`ExploreDiscoveryCache`, `discoverWithLanes()` s SSE progressem, `discoverMultiSector()` fan-out na
virtuálních vláknech). Vše volá `search/CatalogDeepSearchService.deepSearch(...)` s příznaky
`manager_discovery:true`, `use_ai_story:false`. Bucketing sektor vs makro řeší
`ExploreManagerDiscoveryTerms` + `SearchResultCanonicalMetadataService` + `ExploreGeoResolver`.

**Načtení řad (`manager_series_cache`):** `ExploreSummarizeFetchService` tahá vybrané řady ve vlnách
(concurrency 6, cap 14 řad) s pořadím: (1) `explore/manager/ManagerSeriesCacheReader.readObservations`
— read-first v MongoDB kolekci `manager_series_cache`; (2) mirror fetchery `explore/manager/fetch/*`
(ACEA/EBA/EIOPA/ENTSOE/GIE/OECD4…); (3) živý konektor přes `CatalogPreviewOrchestrator.fetchRecords`.
Cache plní/refreshuje `explore/manager/refresh/*` (scheduler + admin `/api/admin/...`).

## 4. Interaktivní graf + AI nad grafem

- `ExploreInteractiveSeriesDetail.jsx` sestaví katalogový kontext (`buildExploreInteractiveCatalogContext`),
  načte živý náhled (`POST /catalog/preview`) a vykreslí `CatalogLiveChartPreview` (skládá dashboardový
  `AradView` + `SourcePreview`).
- Uvnitř `AradView` je i „AI nad grafem" (`components/widgets/ChartAnalystTrigger.jsx` →
  `/api/chart-agent/intent`, `/api/chart-agent/ask`).
- Lehčí, Explorerem vlastněná ✨ „AI nad řadou" je `SeriesConceptExplainTrigger.jsx` na každé kartě →
  `/catalog/explain-series`, `/catalog/related-series`, `chartDataQuality`.

## 5. Cache/výkon — na co u revize koukat

- **Dvě úrovně cache:** (a) discovery `ExploreDiscoveryCache` (klíč `question+sector+broaderSearch`,
  vč. termů z LLM plánovače — nedeterministický plánovač snižuje hit-rate); (b) `manager_series_cache`
  (Mongo) jako read-first zdroj pozorování v summarize.
- **Cold path** vědomě obchází (a): `ExploreSectorService.isIsolatedColdPath`.
- `finalizeAnalysis` píše pravdivé `cache_hit` / `serving_time_ms` / `performance_profile`; benchmark
  ověřuje `!cache_hit`.
- Gold set benchmarku: `evaluation/manager-explorer-cold-path-gold.json`; registry
  `backend-java/src/main/resources/search_v2/*.json`.

## 6. Pozor — audit v rootu je zastaralý

`MANAGER_EXPLORER_AUDIT_V2.md` (root, 2026-08-03) popisuje blokery, které aktuální kód už **řeší**
(v komentářích značené „ETAPA 7/8"): pole `series_coverage` je nyní pole (ne objekt),
`ExploreErrorBoundary` existuje, `series_used`/coverage se plní, `use_ai_story:false`, fetch 14 řad je
paralelní. Zbylá tvrzení auditu (nedeterminismus plánovače, kosmetický progress bar, debug telemetrie
v prod) je potřeba znovu ověřit proti živému buildu, ne slepě věřit dokumentu.

## 7. Klíčové soubory

- FE: `features/manager-explorer/pages/ExplorePage.jsx`, `components/explore/{ExploreInteractiveSeriesDetail,
  SeriesConceptExplainTrigger,ExploreReportChart,ExploreSectionFollowup}.jsx`;
  `lib/{exploreSectorStream,exploreInteractiveCatalog,catalogLivePreview,catalogSeriesConceptExplain}.js`;
  `components/widgets/ChartAnalystTrigger.jsx`.
- BE: `controller/explore/ExploreController.java`, `controller/chartagent/ChartAgentController.java`,
  `explore/{ExploreSectorService,ExploreDiscoveryService,ExploreStreamService,ExploreSummarizeService,
  ExploreSummarizeFetchService,ExploreDiscoveryCache}.java`, `explore/manager/ManagerSeriesCacheReader.java`
  (+ `manager/refresh/*`, `manager/fetch/ManagerFetchRegistry.java`).
- Nástroje: `tools/manager-explorer-cold-path-benchmark.mjs`.
</content>
