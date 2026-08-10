# Backend — mapa modulů podle funkcí

Balíček: `cz.bankintel`

```
cz.bankintel/
├── config/              Security, Flyway, embedded Postgres (profile local)
├── security/            JWT, CSRF, auth cookies
├── feature/
│   ├── auth/            controller + service (AuthController, AuthService)
│   ├── homepage/        HomepageController, SectionsController, HomepageService
│   ├── dashboard/       MeController, MeDashboardService, DashboardController
│   ├── catalog/         CatalogController + search/* + stub/catalog/*
│   ├── explore/         ExploreController + explore/*
│   ├── content/         Articles, RSS, Podcasts
│   ├── archive/         (stub — magazines)
│   ├── admin/           Users, Admin, formulas, computed
│   ├── sources/         SourcesController, connectors
│   └── data/            Datasets, records, sync
├── domain/              entity + dto
└── repository/          JPA repositories
```

> **Poznámka:** Fyzický přesun souborů do `feature/*` probíhá postupně; logické mapování níže odpovídá současné struktuře controller/service.

| Funkce | Controller | Service | Stav parity |
|--------|------------|---------|-------------|
| Auth | `controller/auth` | `service/auth` | ✅ plná |
| Homepage | `controller/homepage` | `service/homepage` | ⚠️ render stub pro datové widgety |
| Osobní dashboard | `controller/me` | `service/me` | ✅ CRUD pages/widgets |
| AI catalog search | `controller/catalog` | `search/*` | ⚠️ funguje s JSONL indexy |
| Manager Explorer | `controller/explore` | `explore/*` | ⚠️ základ + OpenAI |
| Zprávy / RSS / Podcast | `controller/content` | `service/content` | ✅ CRUD |
| Katalogové browsery | `controller/sources/AradCatalogController`, `sources/arad/*`, stub ostatní | — | ⚠️ ARAD ✅, FRED/BIS/… stub |
| Čtečka PDF | `controller/stub` | — | ❌ stub |
| Chat / AI konverzace | `controller/stub` | — | ❌ stub |
| Admin BI | `controller/admin`, sources, formula | services | ⚠️ částečně |
