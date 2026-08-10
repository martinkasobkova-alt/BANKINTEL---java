# BankIntel v2 — mapa funkcí a parity s originálem

**Datum auditu:** 2026-07-04  
**Nová aplikace:** `c:\Bankoapp-main\BankIntel-v2\`  
**Reference (read-only):** `c:\Bankoapp-main\Bankoapp-main\`  
**Původní mapa:** `Bankoapp-main/docs/DEVELOPER_APP_MAP.md`

---

## 1. Struktura frontendu (podle funkcí)

Stránky jsou v `frontend/src/features/<modul>/pages/`.  
Sdílené komponenty zatím v `src/components/` — viz `frontend/docs/FEATURE_MODULES.md`.

| Modul | Route příklady | Stránky | Stav UI |
|-------|----------------|---------|---------|
| **Homepage / sekce** | `/`, `/s/:slug` | DashboardPage, SectionPage | ✅ port |
| **Dashboard** | `/my-dashboard`, `/my-data` | MyDashboardPage, MyDataPage, … | ✅ port |
| **ARAD / grafy** | widgety v dashboardu | AradView v `components/widgets/` | ✅ port (monolit ~6k LOC) |
| **AI hledání** | `/search/catalog` | GlobalCatalogSearchPage, … | ✅ port + backend |
| **AI konverzace** | follow-up v search | CatalogAiSearchPanel | ⚠️ UI OK, backend stub follow-up |
| **Manager Explorer** | `/explore` | ExplorePage | ✅ port + backend |
| **Čtečka archivu** | `/archive/*` | Archive*Page | ⚠️ UI OK, backend stub (magazines) |
| **Zprávy** | `/zpravy` | ArticlesPage | ✅ CRUD |
| **Podcasty** | `/podcasty` | PodcastsPage | ✅ list |
| **Katalogové browsery** | `/fred/catalog`, `/sources/fred` | FredCatalogPage, … | ✅ UI + **live API** (8 zdrojů + ECB) |
| **Auth** | `/settings` | SettingsPage | ✅ |
| **Admin** | `/sources`, `/users` | SourcesPage, … | ✅ většina CRUD |
| **Statické** | `/cookies`, GDPR | CookiesPage, … | ✅ |

**Build:** `npm run build` ✅ (2026-07-04)

---

## 2. Struktura backendu (logické moduly)

Detail: `backend-java/docs/FEATURE_MODULES.md`

| Modul | Java balíček / controller | Parita |
|-------|---------------------------|--------|
| Auth | `controller/auth` | ✅ login, register, `/api/auth/me` |
| Homepage | `controller/homepage` | ⚠️ render datových widgetů stub |
| Dashboard | `controller/me`, `service/me` | ✅ CRUD stránek/widgetů |
| Catalog search | `controller/catalog`, `search/*` | ✅ classic + deep-search |
| Explore | `controller/explore`, `explore/*` | ✅ geo-options, sector, SSE |
| Content | `controller/content` | ✅ articles, RSS, podcasts |
| Sources / sync | `controller/sources`, `service/sync` | ✅ CRUD + sync (arad/fred/eurostat/csu) |
| Admin | `controller/admin`, formula, computed | ⚠️ CRUD ano, výpočty stub |
| Katalog browsery | `controller/sources/*CatalogController` | ✅ 8 zdrojů + ECB |
| Preview / konektory | `connector/*`, `search/CatalogPreviewOrchestrator` | ✅ 9 zdrojů live |
| Archiv / chat / AI agent | `controller/stub/*` | ❌ stub (magazines, chat, commodities, TE) |

**Startup bez Dockeru:** profil `local` (embedded PostgreSQL via Zonky)  
`.\gradlew.bat bootRun` — defaultně aktivní `local`

---

## 3. Automatické testy API (2026-07-04, backend :8081)

Soubor: `docs/parity-test-results.json`

| Endpoint | Metoda | Auth | Výsledek | Poznámka |
|----------|--------|------|----------|----------|
| `/health` | GET | — | ✅ 200 | |
| `/api/homepage/config` | GET | — | ✅ 200 | |
| `/api/homepage/render` | GET | — | ✅ 200 | |
| `/api/sections` | GET | — | ✅ 200 | prázdné `[]` |
| `/api/feature-access` | GET | — | ✅ 200 | |
| `/api/articles` | GET | — | ✅ 200 | |
| `/api/podcasts/shows` | GET | — | ✅ 200 | |
| `/api/catalog/status` | GET | — | ✅ 200 | indexy detekované |
| `/api/catalog/suggest?q=gdp` | GET | — | ✅ 200 | Eurostat suggestions |
| `/api/catalog/search` | POST | — | ✅ 200 | classic search |
| `/api/catalog/deep-search` | POST | — | ✅ 200 | ~18kB odpověď |
| `/api/ad-slots` | GET | — | ✅ 200 | |
| `/api/auth/login` | POST | — | ✅ 200 | dev admin |
| `/api/auth/me` | GET | cookie | ✅ 200 | |
| `/api/me/dashboard/pages` | GET | cookie | ✅ 200 | |
| `/api/sources` | GET | cookie | ✅ 200 | |
| `/api/explore/geo-options` | GET | cookie | ✅ 200 | ~30kB geo data |
| `/api/chat/unread-count` | GET | cookie | ✅ 200 | stub `0` |
| `/api/fred/catalog` | GET | cookie | ✅ 200 | live FRED API (`FRED_API_KEY`) |
| `/api/arad/catalog` | GET | cookie | ✅ 200 | live ČNB / bootstrap JSON |
| `/api/eurostat/catalog` | GET | cookie | ✅ 200 | live TOC XML / disk cache |
| `/api/csu/catalog` | GET | cookie | ✅ 200 | live ČSÚ DataStat API |
| `/api/imf/catalog` | GET | cookie | ✅ 200 | live IMF SDMX API |
| `/api/bis/catalog` | GET | cookie | ✅ 200 | live BIS SDMX API |
| `/api/oecd/catalog` | GET | cookie | ✅ 200 | live OECD SDMX API |
| `/api/data360/catalog` | GET | cookie | ✅ 200 | live World Bank Data360 API |
| `/api/magazines` | GET | cookie | ⚠️ 200 | **prázdný `[]` stub** |

---

## 4. Porovnání s originální aplikací (Python + MongoDB)

| Oblast | Originál | BankIntel v2 | Gap |
|--------|----------|--------------|-----|
| **Tech stack** | FastAPI + MongoDB | Spring Boot + PostgreSQL | záměr IT |
| **Auth / CSRF** | JWT cookies | ✅ stejný model | — |
| **Homepage widgety** | plný render + konektory | markdown/ad OK, data stub | střední |
| **AI catalog search** | Python pipeline | ✅ Java `search/*` + JSONL indexy | follow-up chat stub |
| **Manager Explorer** | OpenAI + SSE | ✅ port | OpenAI key nutný |
| **ARAD grafy** | live ARAD API | UI ✅, preview ✅ s `ARAD_API_KEY` | — |
| **Katalog FRED/BIS/…** | live proxy + cache | ✅ 8 browserů + ECB portováno | TE/commodities stub |
| **Sync zdrojů** | APScheduler | ✅ SyncService (4 typy), async | zbývá scheduler + víc konektorů |
| **Formula / computed** | engine | endpointy, stub výpočty | střední |
| **PDF archiv** | GridFS | stub list | **velký** |
| **Chat / zprávy** | plný chat | stub unread=0 | **velký** |
| **Export PDF/Excel** | openpyxl/reportlab | část 501 | střední |
| **Mobil Expo** | ano | odloženo | — |

---

## 5. Monolitické soubory — plán rozdělení

| Soubor | LOC (≈) | Cílový modul | Stav |
|--------|---------|--------------|------|
| `GlobalCatalogSearchPage.jsx` | ~7000 | `features/ai-search/` | stránka přesunuta, logika ne rozdělena |
| `ExplorePage.jsx` | ~6500 | `features/manager-explorer/` | stránka přesunuta |
| `AradView.jsx` | ~6000 | `features/arad-chart/` | zatím v `components/widgets/` |
| `MeDashboardService.java` | ~640 | `service/me/` | funkční, ne rozděleno |
| Stub controllery | ~380 routes | `controller/stub/` | záměrně centralizované |

Další krok: postupně extrahovat panely/hooky z monolitů do `features/<modul>/components/` a `hooks/`.

---

## 6. Spuštění a ověření

```powershell
# Backend (embedded Postgres — bez Dockeru)
cd c:\Bankoapp-main\BankIntel-v2\backend-java
.\gradlew.bat bootRun
# → http://localhost:8080/health  (nebo 8081 pokud 8080 obsazen)

# Frontend
cd c:\Bankoapp-main\BankIntel-v2\frontend
npm run dev
# → http://localhost:5173
```

**Dev login:** `admin@bankintel.local` / `admin123`  
**Indexy:** `CATALOG_SEARCH_INDEX_DIR` → `Bankoapp-main\backend\data\catalog_search_indexes`

### Dočasně plná funkcionalita (originál backend)

```powershell
cd C:\Bankoapp-main\Bankoapp-main\backend
.\.venv\Scripts\python -m uvicorn server:app --port 8000

cd C:\Bankoapp-main\BankIntel-v2\frontend
$env:REACT_APP_PROXY_TARGET='http://localhost:8000'; npm run dev
```

Originální aplikace v `Bankoapp-main/` **nebyla během auditu měněna** (kromě existujících lokálních změn v datech).

---

## 7. Shrnutí pro product ownera

| ✅ Hotovo | ⚠️ Částečně | ❌ Chybí |
|-----------|-------------|----------|
| UI port + feature složky + README | Homepage datové widgety | PDF archiv |
| Auth, dashboard CRUD | Formula/computed engine | Chat |
| AI search (classic + deep) | Export PDF/Excel | Trading Economics, commodities |
| Live preview 9 zdrojů | Explore backend (~60 %) | Deep-search SSE follow-up |
| Katalog browsery 8+ECB | Dashboard share | Mobil |
| Sync engine (4 typy) | Background scheduler | |

**Závěr:** v2 **není 100 %** — je to funkční přepis s plným UI a klíčovými datovými cestami (search, preview, katalogy, sync). Pro produkční 1:1 parity zbývá archiv, chat, commodities/TE, plný explore a formula engine.
