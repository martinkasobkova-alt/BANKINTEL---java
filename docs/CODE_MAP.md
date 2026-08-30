# Mapa kódu BankIntel-v2

Rychlá orientace v repozitáři. Originál (read-only): `Bankoapp-main/Bankoapp-main/`.

## Stav portu z původní Python aplikace

> **Ověřeno proti kódu 2026-08-21.** Dřívější verze této tabulky pocházela z auditu ze začátku
> července a mezitím zestárla — moduly v ní vedené jako chybějící už existují. Když se údaj níže
> rozchází s kódem, platí kód; ohlaste to a tabulka se opraví.

| Modul | Stav | Kde v kódu |
|-------|------|-----------|
| UI port všech modulů | ✅ | `frontend/src/features/` |
| Auth, admin CRUD | ✅ | `controller/admin/`, `security/` |
| AI classic + deep search | ✅ | `search/CatalogDeepSearchService` |
| Live preview konektorů | ✅ 12 typů | `connector/ConnectorFactory` |
| Katalogové browsery | ✅ 8+ a ECB | `controller/sources/` |
| Sync ARAD/FRED/Eurostat/ČSÚ | ✅ | `service/sync/` |
| PDF archiv (`/api/magazines`) | ✅ 18 endpointů | `controller/magazine/MagazinesController` |
| Chat | ✅ 13 endpointů | `controller/chat/ChatController` |
| TradingEconomics, komodity | ✅ | `TradingEconomicsConnector`, `WorldbankPinkSheetConnector` |
| Deep-search SSE | ✅ | `search/CatalogSearchStreamService` |
| Background scheduler | ✅ 6 jobů | `config/BankIntelScheduler` |
| Homepage datové widgety | ✅ 8 resolverů | `service/homepage/resolver/` |
| Export PDF/Excel | ✅ | `service/export/` |
| Dashboard share | ✅ | `controller/dashboard/DashboardShareController` |
| Formula/computed engine | ✅ | `service/calculations/` |
| Explore backend | ✅ | `explore/`, `controller/explore/` |
| Mobilní aplikace (Expo) | ❌ neportováno | — |

Jediné, co z původní aplikace vědomě nemá protějšek, je mobilní Expo klient.

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
| `features/archive-reader/` | PDF archiv (`controller/magazine/`) |
| `features/dashboard/` | Homepage, my-dashboard, widgety |
| `features/admin/` | Sources, users, formulas |
| `src/lib/previewNormalizer.js` | Normalizace `/api/catalog/preview` pro graf |

Detail: `frontend/docs/FEATURE_MODULES.md`

## Backend Java (`backend-java/src/main/java/cz/bankintel/`)

| Balíček | Popis | Vstupní bod |
|---------|--------|-------------|
| `controller/` | REST API | `ControllerPackage.java` |
| `controller/sources/` | Katalogové browsery | `*CatalogController.java` |
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
