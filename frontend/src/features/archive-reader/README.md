# Čtečka archivu — PDF časopisy

**Routes:** `/archive`, `/archive/:magazineId`, `/archive/:magazineId/:issueId`

## Hlavní soubory

| Soubor | Popis |
|--------|--------|
| `pages/ArchivePage.jsx` | Seznam časopisů |
| `pages/ArchiveMagazinePage.jsx` | Vydání jednoho časopisu |
| `pages/ArchiveIssueReaderPage.jsx` | PDF reader + inline grafy |
| `components/archive/ArchivePdfJsViewer.jsx` | PDF.js viewer |
| `components/archive/ArchiveInlineChartPanel.jsx` | Graf v kontextu článku |

## Backend API

- `GET /api/magazines` — seznam ❌ **stub prázdné `[]`**
- PDF soubory / GridFS — ❌ neportováno

## Parita

UI ✅ · Funkce ❌ (potřeba port magazines + úložiště PDF)
