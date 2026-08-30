# Porting plan — full parity (no mobile)

Scope confirmed:

- **Full web parity** with `Bankoapp-main/Bankoapp-main`
- **PostgreSQL as primary DB** (Flyway migrations, JPA) — IT target architecture
- **AI search + Manager Explorer rewritten in Java**
- **Mobile later**

Original app is **read-only reference**. Legacy Mongo data can be migrated via one-off ETL script (future).

---

## Phase 0 — Foundation (week 1–2) `[mostly done]`

- [x] New folder `BankIntel-v2/`
- [x] Spring Boot + Gradle + Flyway + PostgreSQL schema
- [x] Auth: login, register, refresh, verify, forgot/reset, logout, JWT cookies, CSRF
- [x] `/api/me` read: dashboard pages/default, preferences, nav order
- [x] `/api/feature-access` list + effective
- [x] AI search skeleton (`search/*`, OpenAI client)
- [x] Frontend: Vite + TS + login modal + CSRF-aware API client
- [ ] OpenAPI contract tests vs Python reference

---

## Phase 1 — Platform core (week 3–6)

Port in order:

1. Auth completion — register, verify-email, forgot/reset, refresh, CSRF double-submit
2. Feature access + roles (`/api/feature-access`, `@PreAuthorize`)
3. `/api/me` — dashboards, widgets, profile, uploads (~33 endpoints)
4. Homepage + sections + media
5. Legacy BI core — sources, sync, datasets, records, formulas, dashboard KPIs, export

**Exit criteria:** Admin can manage sources; subscriber dashboards load from same Mongo docs.

---

## Phase 2 — AI catalog search (week 7–14) `[critical path]`

Rewrite in Java (`search/` package):

1. Classic FTS search (SQLite index — call same files under `backend/data/catalog_search_indexes/` or embed SQLite JDBC)
2. Deep search pipeline split from `catalog_deep_search.py`:
   - query planning (OpenAI)
   - source lanes (parallel executor)
   - scoring / rerank
   - live preview verification
   - SSE progress events
3. Suggest, preview, availability, macro-topics, stock search
4. Chart agent + chat endpoints

**Exit criteria:** `/search/catalog` page works end-to-end against Java API with same UX.

---

## Phase 3 — Manager Explorer (week 15–20)

Rewrite `explore_manager.py` + routes:

- sector discovery, geo refinement, report generation
- SSE streams, credit tracking
- segment bundles as YAML/JSON config (reduce Java LOC)

**Exit criteria:** `/explore` wizard produces same report structure as Python.

---

## Phase 4 — Per-source catalogs (week 21–28)

Generic catalog browser component on frontend + Java proxy/catalog controllers:

- ARAD, Eurostat, ČSÚ, FRED, IMF, OECD, BIS, Data360, Alpha Vantage, Yahoo, Trading Economics
- ECB/IMF/OECD browser proxies
- Commodities

Connectors in Java for sync jobs (can reuse HTTP logic from Python connectors).

**Exit criteria:** All catalog deep links and admin `/sources/*` pages work.

---

## Phase 5 — Content & publishing (week 29–34)

- Magazines / archive reader (GridFS)
- Articles CMS
- RSS monitoring
- Podcasts
- Ad slots, bug reports, admin tools

**Exit criteria:** `/archive`, `/zpravy`, `/podcasty`, admin content tools at parity.

---

## Phase 6 — Frontend lean rewrite (parallel from Phase 1)

Work **module-by-module** against Java API:

| Module | Original LOC hint | Target approach |
|--------|------------------:|-----------------|
| Catalog search | ~7k page | `features/catalog/` hooks + small components |
| Explore | ~6.5k | `features/explore/` wizard steps |
| AradView charts | ~6k | `features/charts/` shared chart shell |
| Catalog browsers | duplicated | single `CatalogBrowserPage` + config |
| Admin | many pages | shared tables/forms |

Copy design tokens from original `index.css` `:root` — not the full 2300-line file.

**Exit criteria:** Visual parity check on key pages; product LOC target −40% vs original frontend.

---

## Phase 7 — Hardening & cutover (week 35–38)

- Contract tests: every `/api/*` path vs OpenAPI
- Load test catalog search SSE
- Scheduler parity (sync jobs)
- Production config (Render/Vercel equivalents for Java + static)

**Exit criteria:** Staging runs fully on Java; Python backend kept as fallback only.

---

## Phase 8 — Mobile (later)

- Point Expo app to Java API (same contracts)
- Or rewrite mobile after web stable

---

## Team & estimate

| Role | FTE | Notes |
|------|-----|-------|
| Java backend | 1–2 | AI search is the bottleneck |
| React frontend | 1 | Parallel from Phase 1 |
| QA / parity | 0.5 | Page-by-page checklist |

**Calendar:** ~9 months with 2 devs; ~6 months with 3.

---

## Next immediate tasks

1. Finish auth parity (register, refresh, CSRF)
2. Port `/api/me` read paths
3. Start `search/query/` package with OpenAI client
4. Frontend: AuthContext + LoginModal against Java API
5. Copy Tailwind theme tokens into `frontend/src/styles/tokens.css`
