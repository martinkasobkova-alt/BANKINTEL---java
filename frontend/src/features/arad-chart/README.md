# ARAD chart — widget a graf ČNB dat

Používá se v dashboardu, explore a náhledech katalogu.

## Hlavní soubory

| Soubor | Popis |
|--------|--------|
| `components/widgets/AradView.jsx` | Monolit (~6000 LOC) — hlavní graf ARAD |
| `components/widgets/arad/*` | Panely: tabulka, insights, advanced controls |

## Backend API

- Náhled: `POST /api/catalog/preview` (`source_type: arad`) — vyžaduje `ARAD_API_KEY`
- Katalog: `GET /api/arad/catalog`

## Import alias

`@/components/widgets/AradView` → `features/arad-chart/components/widgets/AradView`

## Parita

UI ✅ · Live data závisí na backend konektoru + API klíči ✅
