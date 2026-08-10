# Mapa aplikace (APP_MAP)

Orientační mapa celé aplikace pro technickou revizi. Cesty jsou relativní k repozitáři, názvy tříd
odpovídají aktuálnímu kódu. Navazující detailní mapy:
[SEARCH_MAP.md](SEARCH_MAP.md), [MANAGER_EXPLORER_MAP.md](MANAGER_EXPLORER_MAP.md),
[FTS_AND_SIDECAR.md](FTS_AND_SIDECAR.md).

## 1. Co aplikace dělá

Sjednocuje veřejné ekonomické datové zdroje do jednoho místa a nad nimi staví:
- **Katalogové vyhledávání** řad (fulltext + AI deep-search + search-v2).
- **Živý náhled dat** z 9+ konektorů a jejich vykreslení do grafu.
- **Dashboardy a widgety** (osobní i homepage), sdílení přehledů.
- **Manager Explorer** — průzkum sektoru/tématu → objevení řad → interaktivní graf.
- **AI vrstvu** nad grafem, nad výsledky hledání a nad dashboardem.
- **Čtečku PDF časopisu**, zprávy/RSS, podcasty a admin/CRUD.

## 2. Vrstvy a technologie

| Vrstva | Technologie | Kde |
|--------|-------------|-----|
| Frontend | React 19 + Vite, Recharts, pdf.js | `frontend/` |
| Backend | Java 21, Spring Boot 3.4 | `backend-java/` |
| Relační DB | PostgreSQL (schéma přes Flyway) | `backend-java/.../db/migration/` |
| Katalogový index | SQLite FTS5 + Lucene | `data/` (mimo repo) |
| Cache Exploreru | MongoDB (`manager_series_cache`) | `explore/manager/` |
| AI | OpenAI Chat/Responses API | `search/openai/OpenAiClient` |
| Embeddings | ONNX Runtime + DJL (lokální) | `search/v2/vector/` |

## 3. Backend — balíčky (`backend-java/src/main/java/cz/bankintel`)

Vstupní bod: **`BankIntelApplication.java`** (`@SpringBootApplication @EnableAsync @EnableScheduling`).

| Balíček | Účel | Klíčové třídy |
|---------|------|---------------|
| `controller/` | REST `/api/**` | `catalog/`, `chartagent/`, `explore/`, `sources/`, `dashboard/`, `homepage/`, `auth/`, `magazine/`, `me/`, `admin/` |
| `service/` | byznys logika po doménách | `chartagent/`, `homepage/`, `magazine/`, `sync/`, `research/`, `me/`, … |
| `connector/` | živá data z externích API | `BaseConnector`, `ConnectorFactory`, `*Connector` (viz [SEARCH_MAP §4](SEARCH_MAP.md)) |
| `search/` | katalogové hledání + náhledy | `CatalogIndexStore`, `CatalogDeepSearchService`, `v2/`, `openai/` |
| `sources/` | per-provider katalog/kurace | `csu/`, `eurostat/`, `ecb/`, `fred/`, `stocks/`, … |
| `explore/` | Manager Explorer + `manager_series_cache` | `ExploreSectorService`, `explore/manager/*` |
| `security/` | autentizace | `JwtService`, `JwtAuthFilter`, `CsrfFilter`, `AuthCookieService`, `AuthRateLimitFilter` |
| `config/` | profily, DB, scheduler, `.env` | `SecurityConfig`, `LocalEmbeddedPostgresConfig`, `BankIntelScheduler`, `BankIntelEnvEnvironmentPostProcessor` |
| `domain/` | JPA `@Entity` + DTO | `entity/` (~37), `dto/` |
| `repository/` | Spring Data JPA | jedna per entita |
| `storage/` | binární úložiště | `MagazineStorageService` (PDF) |

## 4. Frontend — struktura (`frontend/src`)

Vstupní bod: `main.tsx` → `App.tsx` (routing). Feature-based moduly:

| Modul (`features/`) | Route | Popis |
|---------------------|-------|-------|
| `ai-search/` | `/search/catalog` | katalogové vyhledávání + náhled grafu (monolit `GlobalCatalogSearchPage.jsx`) |
| `manager-explorer/` | `/explore` | Manager Explorer (`ExplorePage.jsx`) — viz [MANAGER_EXPLORER_MAP](MANAGER_EXPLORER_MAP.md) |
| `dashboard/` | `/my-dashboard`, `/` | dashboardy, homepage, widgety |
| `arad-chart/` | widgety | `AradView` — jádro vykreslení grafu |
| `catalog-browsers/` | `/fred/catalog`, … | stromy zdrojů (FRED, Eurostat, ARAD, …) |
| `archive-reader/` | `/archive/*` | čtečka PDF časopisu (pdf.js) |
| `articles/`, `podcasts/` | `/zpravy`, `/podcasty` | obsah |
| `admin/` | `/sources`, `/users` | administrace |
| `auth/`, `static/` | `/settings`, GDPR | účet, statické |

Sdílené: `src/components/` (mj. `widgets/ChartAnalystTrigger.jsx` = „AI nad grafem", `sources/SourcePreview.jsx`),
`src/lib/` (integrace API, normalizace preview), `src/charts/` (renderer, kontrakt grafu).

## 5. Datový tok (typický požadavek)

```
Uživatel → React (features/*) → /api/... → Controller → Service
   → Connector (živá data z API zdroje)   ── nebo ──   Search (index/FTS)
   → normalizace → JSON → Frontend → ChartRenderer/AradView
```

Náhled grafu: `POST /api/catalog/preview` → `CatalogPreviewOrchestrator` → `ConnectorFactory` → konektor.
Vyhledávání: `POST /api/catalog/deep-search` / `/search-v2` → viz [SEARCH_MAP](SEARCH_MAP.md).

## 6. Autentizace (cookie JWT + CSRF)

- Login (`controller/auth/AuthController`) nastaví HttpOnly cookies `access_token` + `refresh_token`
  a ne-HttpOnly `csrf_token` (`AuthCookieService`).
- `JwtAuthFilter` čte `access_token` a nastaví autentizaci; `CsrfFilter` u zápisových metod vyžaduje
  hlavičku `X-CSRF-Token` rovnou cookie `csrf_token` (**double-submit**). Výjimky: login/register/refresh.
- Filtr chain (`SecurityConfig`): rate-limit → JWT → CSRF; stateless; role se řeší v controllerech.

## 7. Perzistence a migrace

- **PostgreSQL**, `ddl-auto: validate`, schéma spravuje **Flyway** — migrace `V1__core_schema.sql`
  … `V12__arad_indicators.sql` v `backend-java/src/main/resources/db/migration/`.
- Lokálně: **embedded PostgreSQL** (zonky) přes `LocalEmbeddedPostgresConfig` (`@Profile("local")`).
- **MongoDB** drží `manager_series_cache` (cache řad pro Explorer) — přímo přes driver.
- **SQLite + Lucene** drží katalogový fulltext (mimo repo) — viz [FTS_AND_SIDECAR](FTS_AND_SIDECAR.md).

## 8. Plánované úlohy (`config/BankIntelScheduler`)

| Job | Rozvrh | Co dělá |
|-----|--------|---------|
| `catalogWarmupNightly` | 03:00 UTC | zahřátí katalogových indexů |
| `managerEurostatCacheRefreshNightly` | 04:00 UTC | refresh `manager_series_cache` (Eurostat) |
| `managerCacheRefresh` | à 6 h | čištění mirror cache |
| `rssDueSync` | à 5 min | RSS (běží na každé instanci) |
| `nightlyExternalMaintenance` | 02:30 UTC | volitelný Python maintenance shell-out |

Leader gating: joby (kromě RSS) běží jen na instanci s `BANKINTEL_SCHEDULER_LEADER=1`.

## 9. Build, běh, nasazení

- **Dev:** `start-dev.ps1` nebo `./gradlew bootRun` (profil `local`) + `npm run dev`. Port backendu `PORT` (default 8080), frontend 5173, login `admin@bankintel.local` / `admin123`.
- **Testy:** `./gradlew test` (JUnit 5), `npm run test` (vitest).
- **Prod:** `backend-java/Dockerfile` (Java 21, non-root) + `render.yaml` (web + PostgreSQL + 20 GB disk `/data` pro FTS index), profil `prod` s fail-fast `StartupSecurityValidator`.

## 10. AI plochy (kde jsou)

| Plocha | Frontend | Backend endpoint |
|--------|----------|------------------|
| AI nad grafem | `components/widgets/ChartAnalystTrigger.jsx` | `/api/chart-agent/intent`, `/api/chart-agent/ask` |
| Vysvětlení řady („Co říká?") | `SeriesConceptExplainTrigger.jsx`, `lib/catalogSeriesConceptExplain.js` | `/api/catalog/explain-series(/ask)` |
| AI nad výsledky hledání | deep-search results panel | `/api/catalog/deep-search/results-chat` (`CatalogFollowupService`) |
| AI nad dashboardem | `components/myDashboard/DashboardAiChat.jsx` | `/api/chart-agent/ask` (stejná grounded cesta) |

Společné pravidlo: bez `OPENAI_API_KEY` (`OpenAiClient.isConfigured()==false`) AI degraduje na
deterministické heuristiky, aplikace ale běží dál.
</content>
