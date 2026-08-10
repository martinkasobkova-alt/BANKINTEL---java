# Dashboard — homepage, osobní stránky, widgety

**Routes:** `/`, `/my-dashboard`, `/my-data`, `/shared/:token`, `/homepage-editor`

## Hlavní soubory

| Soubor | Popis |
|--------|--------|
| `pages/DashboardPage.jsx` | Veřejná homepage |
| `pages/MyDashboardPage.jsx` | Editor osobního dashboardu |
| `pages/MyDataPage.jsx` | Nahraná data uživatele |
| `pages/SharedDashboardPage.jsx` | Sdílený dashboard (token) |
| `pages/HomepageEditorPage.jsx` | Admin editor homepage |

## Backend API

- `/api/homepage/config`, `/render` — ⚠️ markdown/ads OK, datové widgety partial
- `/api/me/dashboard/*` — CRUD stránek a widgetů ✅
- `/api/dashboard-share/*` — ⚠️ stub

## Parita

UI ✅ · Render widgetů s live daty ⚠️
