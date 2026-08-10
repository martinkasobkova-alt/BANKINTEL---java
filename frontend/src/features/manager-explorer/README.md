# Manager Explorer — mapový průzkumník sektorů

**Route:** `/explore`

## Hlavní soubory

| Soubor | Popis |
|--------|--------|
| `pages/ExplorePage.jsx` | Monolit (~6500 LOC) — hlavní explore UI |
| `components/explore/ExploreSectionFollowup.jsx` | Follow-up otázky k sekci |
| `components/explore/ExploreAnalysisInsights.jsx` | AI insights panel |

## Backend API

- `GET /api/explore/geo-options` — seznam zemí ✅
- `POST /api/explore/sector` — analýza sektoru ⚠️ partial
- SSE streamy pro průběh analýzy ⚠️ partial

## Import alias

`@/components/explore` → `features/manager-explorer/components/explore/`

## Parita

UI ✅ · Backend ~60 % (plný Python modul ~8000 LOC)
