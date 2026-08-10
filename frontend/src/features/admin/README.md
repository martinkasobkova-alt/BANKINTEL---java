# Admin — správa zdrojů, uživatelů, formulí

**Routes:** `/sources`, `/users`, `/formulas`, `/sync-logs`, `/exports`, …

## Hlavní stránky

| Stránka | API |
|---------|-----|
| `SourcesPage`, `SourceFormPage` | `/api/sources` CRUD ✅ |
| `SyncLogsPage` | `/api/sync-logs` ✅ |
| `RecordsPage` | `/api/records` ✅ |
| `FormulasPage`, `ComputedPage` | `/api/formulas`, `/computed` ⚠️ stub výpočty |
| `UsersPage` | `/api/admin/users` ✅ |

Sync tlačítko volá `POST /api/sources/{id}/sync` — ✅ pro arad/fred/eurostat/csu.

## Parita

CRUD ✅ · Formula engine ⚠️ · Admin sync jobs ⚠️
