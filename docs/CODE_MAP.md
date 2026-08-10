# Mapa kódu BankIntel-v2

Rychlá orientace v repozitáři. Originál (read-only): `Bankoapp-main/Bankoapp-main/`.

## Je appka na 100 %?

**Ne.** Odhad funkční parity s originálem: **~70 % backend**, **~95 % UI (routy + vzhled)**.

| ✅ Hotovo | ⚠️ Částečně | ❌ Chybí |
|-----------|-------------|----------|
| UI port všech modulů | Explore backend | PDF archiv (`/api/magazines`) |
| Auth, admin CRUD | Homepage datové widgety | Chat |
| AI classic + deep search | Formula/computed engine | Trading Economics, commodities |
| Live preview 9 zdrojů | Export PDF/Excel | Deep-search SSE follow-up |
| 8+ katalog browserů + ECB | Dashboard share | Mobil Expo |
| Sync arad/fred/eurostat/csu | | Background scheduler |

---

## Frontend (`frontend/`)

**Vstupní bod:** `src/App.tsx` (routing)  
**Moduly:** `src/features/<modul>/README.md` — každá složka má popis routes a API

| Složka | Popis |
|--------|--------|
| `features/ai-search/` | Katalogové vyhledávání, náhled grafu (`/search/catalog`) |
| `features/catalog-browsers/` | Stromy FRED, Eurostat, ARAD, … |
| `features/arad-chart/` | ARAD widget (`AradView`) |
| `features/manager-explorer/` | Explore (`/explore`) |
| `features/archive-reader/` | PDF archiv — UI OK, backend stub |
| `features/dashboard/` | Homepage, my-dashboard, widgety |
| `features/admin/` | Sources, users, formulas |
| `src/lib/previewNormalizer.js` | Normalizace `/api/catalog/preview` pro graf |

Detail: `frontend/docs/FEATURE_MODULES.md`

## Backend Java (`backend-java/src/main/java/cz/bankintel/`)

| Balíček | Popis | Vstupní bod |
|---------|--------|-------------|
| `controller/` | REST API | `ControllerPackage.java` |
| `controller/sources/` | Katalogové browsery | `*CatalogController.java` |
| `controller/stub/` | Neportované moduly | `StubPackage.java` |
| `connector/` | Live data z API | `ConnectorPackage.java` |
| `search/` | Vyhledávání + preview | `SearchPackage.java` |
| `sources/*/` | Logika katalogů | `SourcesPackage.java` |
| `service/sync/` | Sync engine | `SyncPackage.java` |

## Klíčové API endpointy

| Endpoint | Java třída |
|----------|------------|
| `POST /api/catalog/preview` | `CatalogPreviewOrchestrator` |
| `POST /api/catalog/search` | `CatalogClassicSearchService` |
| `POST /api/sources/{id}/sync` | `SyncService` |
| `GET /api/ecb/browse-tree` | `EcbCatalogController` |

## Proměnné prostředí (`.env`)

| Proměnná | K čemu |
|----------|--------|
| `CATALOG_SEARCH_INDEX_DIR` | Indexy pro vyhledávání |
| `ARAD_API_KEY` | Náhled/sync ARAD |
| `FRED_API_KEY` | Náhled/sync FRED |
| `IMF_API_KEY` | Náhled/sync IMF |
| `OPENAI_API_KEY` | AI search / explore |

## Spuštění

```powershell
cd BankIntel-v2
.\start-dev.ps1
```

Profil `local` = embedded PostgreSQL, bez Dockeru.
