# Frontend features

Stránky aplikace jsou rozděleny podle business modulů. Každá složka má vlastní **README.md** s routes, klíčovými soubory a stavem parity.

```
src/features/
├── ai-search/          /search, /search/catalog — vyhledávání + náhled grafu
├── arad-chart/         ARAD widget (AradView)
├── archive-reader/     /archive/* — PDF časopisy
├── articles/           /zpravy
├── auth/               /settings, verify, reset password
├── catalog-browsers/   /fred/catalog, /sources/* — stromy datasetů
├── dashboard/          /, /my-dashboard, /my-data
├── homepage/           /s/:slug, /messages, /my-rss
├── manager-explorer/   /explore
├── podcasts/           /podcasty
├── admin/              /sources, /users, /formulas, …
└── static/             cookies, GDPR, předplatné
```

## Dokumentace

| Soubor | Obsah |
|--------|--------|
| `features/<modul>/README.md` | Popis modulu, API, parity |
| [docs/FEATURE_MODULES.md](docs/FEATURE_MODULES.md) | Tabulka modulů + aliasy |
| [../../docs/CODE_MAP.md](../../docs/CODE_MAP.md) | Celá app (FE + BE) |
| [../../docs/FUNCTIONALITY_MAP.md](../../docs/FUNCTIONALITY_MAP.md) | Parita s originálem |

## Sdílená knihovna (`src/lib/`)

| Soubor | Popis |
|--------|--------|
| `previewNormalizer.js` | Normalizace `POST /api/catalog/preview` → tvar pro graf |
| `previewRequestParams.js` | Sestavení těla preview requestu ze řádku katalogu |

## Vite aliasy (zpětná kompatibilita importů)

Viz `vite.config.ts` — `@/components/catalog`, `@/components/explore`, `@/hooks/catalogSearch`, `@/components/widgets/AradView`.
