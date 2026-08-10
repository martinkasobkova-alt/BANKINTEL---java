# Frontend — mapa modulů podle funkcí

Stránky: `src/features/<modul>/pages/`  
Detail každého modulu: `src/features/<modul>/README.md`

Komponenty jsou v `features/<modul>/components/`. Importy `@/components/catalog`, `@/components/explore`, `@/hooks/catalogSearch`, `@/components/widgets/AradView` směřují přes Vite aliasy (`vite.config.ts`).

| Modul | Složka | Routes | Backend | Parita |
|-------|--------|--------|---------|--------|
| **AI hledání** | `ai-search/` | `/search/catalog` | `/api/catalog/*` | ✅ search + preview |
| **Katalog browsery** | `catalog-browsers/` | `/fred/catalog`, … | `/api/*/catalog` | ✅ 8 zdrojů, TE/commodities ❌ |
| **ARAD graf** | `arad-chart/` | (widget) | preview + `/api/arad/catalog` | ✅ UI, data dle API klíčů |
| **Explore** | `manager-explorer/` | `/explore` | `/api/explore/*` | ⚠️ ~60 % |
| **Dashboard** | `dashboard/` | `/`, `/my-dashboard` | `/api/me/dashboard/*` | ⚠️ widget render |
| **Archiv PDF** | `archive-reader/` | `/archive/*` | `/api/magazines` | ❌ stub |
| **Admin** | `admin/` | `/sources`, … | `/api/sources`, sync | ✅ CRUD + sync partial |
| **Homepage** | `homepage/` | `/s/:slug` | `/api/homepage/*` | ⚠️ |
| **Zprávy** | `articles/` | `/zpravy` | `/api/articles` | ✅ |
| **Podcasty** | `podcasts/` | `/podcasty` | `/api/podcasts` | ✅ |
| **Auth** | `auth/` | `/settings` | `/api/auth/*` | ✅ |
| **Statické** | `static/` | cookies, GDPR | — | ✅ |

## Monolity (plán rozdělení)

| Soubor | LOC | Stav |
|--------|-----|------|
| `GlobalCatalogSearchPage.jsx` | ~6700 | částečně extrahováno |
| `ExplorePage.jsx` | ~6500 | komponenty odděleny, stránka monolit |
| `AradView.jsx` | ~6000 | panely v `arad/` podsložce |

## Proxy na originální backend (dočasně)

```powershell
$env:REACT_APP_PROXY_TARGET='http://localhost:8000'; npm run dev
```
