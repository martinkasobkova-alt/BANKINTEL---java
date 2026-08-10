# Katalogové browsery — prohlížení stromů datasetů

**Routes:** `/fred/catalog`, `/sources/fred`, `/arad/catalog`, `/eurostat/catalog`, …

## Stránky

| Stránka | API prefix | Backend Java |
|---------|------------|--------------|
| `AradCatalogPage` | `/api/arad/catalog` | `AradCatalogController` ✅ |
| `FredCatalogPage` | `/api/fred/catalog` | `FredCatalogController` ✅ |
| `EurostatCatalogPage` | `/api/eurostat/catalog` | `EurostatCatalogController` ✅ |
| `CsuCatalogPage` | `/api/csu/catalog` | `CsuCatalogController` ✅ |
| `BisCatalogPage` | `/api/bis/catalog` | `BisCatalogController` ✅ |
| `ImfCatalogPage` | `/api/imf/catalog` | `ImfCatalogController` ✅ |
| `OecdCatalogPage` | `/api/oecd/catalog` | `OecdCatalogController` ✅ |
| `Data360CatalogPage` | `/api/data360/catalog` | `Data360CatalogController` ✅ |
| `TradingEconomicsBrowserPage` | `/api/tradingeconomics/*` | ❌ stub |
| `CommoditiesPage` | `/api/commodities/*` | ❌ stub |

ECB browsery jsou v originálu pod `/api/ecb` — v UI mohou být propojeny přes search, ne samostatná stránka.

## Parita

UI ✅ · Live katalogy 8/10 ✅ · TE + commodities ❌
