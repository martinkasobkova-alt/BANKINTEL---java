# AI search — katalogové vyhledávání a náhledy

**Routes:** `/search`, `/search/catalog`, `/search/stocks`, `/search/topic/:topicId`

## Hlavní soubory

| Soubor | Popis |
|--------|--------|
| `pages/GlobalCatalogSearchPage.jsx` | Hlavní stránka — classic + deep search, inline preview grafu |
| `pages/globalCatalogSearchConstants.js` | Konstanty, labely, limity |
| `pages/globalCatalogSearchDeepResultUi.jsx` | UI panel deep-search výsledků |
| `components/catalog/CatalogChartPreview.jsx` | Vykreslení náhledu grafu z `/api/catalog/preview` |
| `components/catalog/search/CatalogSetPreviewPanel.jsx` | Panel náhledu jedné sady |
| `hooks/catalogSearch/useCatalogDeepSearchViewModel.js` | Stav a logika deep search |
| `hooks/catalogSearch/useDeepSearchRunner.js` | Spuštění deep-search requestu |

## Backend API

- `POST /api/catalog/search` — classic FTS
- `POST /api/catalog/deep-search` — AI pipeline
- `POST /api/catalog/preview` — **live data pro graf** (Java konektory)
- `GET /api/catalog/suggest` — autocomplete

## Parita

| Oblast | Stav |
|--------|------|
| UI + routy | ✅ |
| Classic / deep search | ✅ |
| Live preview grafu | ✅ (ARAD, FRED, Eurostat, CSU, BIS, IMF, OECD, Data360, ECB) |
| Follow-up chat / SSE streamy | ⚠️ stub backend |

## Import aliasy (vite.config)

`@/components/catalog` → tento modul `components/catalog/`
