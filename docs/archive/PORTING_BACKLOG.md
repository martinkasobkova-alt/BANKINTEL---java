# Port backendu z originálu — backlog

**Start:** 2026-07-04  
**Cíl:** plná parity s `Bankoapp-main/backend/` (ne stub vrstva)  
**Originál:** read-only reference

## Stav portu

| Modul | Python | Java v2 | Stav |
|-------|--------|---------|------|
| ARAD catalog | `arad_catalog_routes.py` | `AradCatalogService` | ✅ hotovo |
| FRED catalog | `fred_catalog_routes.py` | `FredCatalogService` | ✅ hotovo |
| Eurostat catalog | `eurostat_catalog_routes.py` | `EurostatCatalogService` | ✅ hotovo |
| CSU catalog | `csu_catalog_routes.py` | `CsuCatalogService` | ✅ hotovo |
| IMF catalog | `imf_catalog_routes.py` | `ImfCatalogService` | ✅ hotovo |
| BIS catalog | `bis_catalog_routes.py` | `BisCatalogService` | ✅ hotovo |
| OECD catalog | `oecd_catalog_routes.py` | `OecdCatalogService` | ✅ hotovo |
| Data360 catalog | `data360_catalog_routes.py` | `Data360CatalogService` | ✅ hotovo |
| Catalog preview | `catalog_preview_routes.py` (~4k LOC) | live pro 8 zdrojů | 🔄 ~70 % |
| Sync + konektory | `connectors/*`, `sync_service.py` | `SyncService` arad/fred/eurostat/csu | 🔄 ~40 % |
| Explore plný | `explore_routes.py` (~8k LOC) | partial | ⏳ |
| Homepage plný | `homepage_routes.py` | partial | ⏳ |
| Magazines/chat | stub | stub | ⏳ |

## Hotovo v této iteraci (2026-07-04)

- **Connector framework** — `cz.bankintel.connector.*` (BaseConnector, Factory, HTTP)
- **Live preview** — ARAD, FRED, Eurostat, CSU, BIS, IMF, OECD, Data360 (`POST /api/catalog/preview`)
- **Sync engine** — `SyncService` + `POST /api/sources/{id}/sync` (arad/fred/eurostat/csu)

## Pořadí dalších kroků

1. ECB/ECB2 browsery + `EcbConnector`, Trading Economics, commodities, stocks, Yahoo
2. Preview parity — needs_filters (Eurostat planner), CSU enrichment, compare-geo paths
3. Sync pro BIS/IMF/OECD/Data360 + admin sync jobs
4. Deep search SSE extensions, explore parity (~8k LOC)
5. Homepage render, me dashboard uploads, magazines, chat, export PDF/Excel
