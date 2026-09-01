# Deploy data & nightly jobs (BankIntel v2)

Java backend **reads** mirror CSV, FTS SQLite, metadata sidecars, and macro snapshots; **build/import** zůstává v Python referenci (`Bankoapp-main`) nebo se obnoví z předpřipravených snapshotů.

## Env proměnné

```env
BANKINTEL_DATA_DIR=C:/Bankoapp-main/Bankoapp-main/backend/data
CATALOG_SEARCH_INDEX_DIR=C:/Bankoapp-main/Bankoapp-main/backend/data/catalog_search_indexes
CATALOG_SEARCH_METADATA_DIR=C:/Bankoapp-main/Bankoapp-main/backend/config/catalog_search_metadata
CLASSIC_CATALOG_FTS_DB=C:/Bankoapp-main/Bankoapp-main/backend/data/catalog_search_indexes/classic_catalog_search.sqlite
MACRO_TOPICS_SNAPSHOT_PATH=C:/Bankoapp-main/Bankoapp-main/backend/data/macro_topics_snapshot.json

# Volitelné — první boot stáhne FTS SQLite, pokud CLASSIC_CATALOG_FTS_DB chybí (Render / čistý disk)
# FTS_INDEX_SNAPSHOT_URL=https://storage.example.com/classic_catalog_search.sqlite.gz

# Volitelné — noční shell-out na Python skripty (02:30 UTC); vyžaduje Python na hostu, ne v Docker image
BANKINTEL_MAINTENANCE_ENABLED=1
BANKINTEL_PYTHON_ROOT=C:/Bankoapp-main/Bankoapp-main

# Multi-instance prod — nastavte na přesně jedné replice
BANKINTEL_SCHEDULER_LEADER=1
```

## 1. FTS index (9,5 GB SQLite)

**Build je od 2026-09-01 v Javě** (`search/ClassicCatalogFtsIndexBuilder`) — Python na hostu
kvůli němu být nemusí. Pouští ho noční údržba (02:30 UTC pod `BANKINTEL_MAINTENANCE_ENABLED`).

> **Než ho pustíte nad ostrým indexem:** rebuild čte JSONL, takže vrátí řady, které z indexu
> vyřezaly prune skripty (~716 tis. řádků, hlavně FRED). Build se v takovém případě sám zastaví
> a index nevymění — čísla a postup v `docs/FTS_AND_SIDECAR.md` §8.

Původní Python skript v referenčním repu zůstává použitelný, ale žádnou takovou pojistku nemá.

**Deploy (čistý disk):** Nahrajte komprimovaný snapshot do object storage a nastavte `FTS_INDEX_SNAPSHOT_URL`. Při startu `FtsIndexBootstrapRunner` stáhne archiv (`.zip` / `.gz`), rozbalí `classic_catalog_search.sqlite` do `CATALOG_SEARCH_INDEX_DIR` a zapíše na `CLASSIC_CATALOG_FTS_DB`.

Bez `.sqlite` souboru Java hlásí health „not ready“ a hledání padá na pomalý/prázdný JSONL scan.

## 2. Bankovní / energetické mirror CSV

Mirror data **nejsou** součástí Docker image. Možnosti:

| Cesta | Popis |
|-------|--------|
| **Prebuilt snapshot** | Z CI nahrajte FTS + mirror CSV (+ macro snapshot) na perzistentní disk `/data` před prvním provozem, nebo obnovte z object storage při bootu (FTS má `FTS_INDEX_SNAPSHOT_URL`; mirror CSV zatím ručně / vlastní sync). |
| **Python worker** | Samostatný cron/worker na Renderu (ne web služba) spouští import skripty proti sdílenému `BANKINTEL_DATA_DIR`; web zůstává read-only. |
| **Lokální Python** | `BANKINTEL_MAINTENANCE_ENABLED=1` + `BANKINTEL_PYTHON_ROOT` — funguje jen tam, kde je Python dostupný (ne v čistém JRE Docker image). |

```powershell
cd C:\Bankoapp-main\Bankoapp-main\backend
python scripts/import_eba_public_data.py
python scripts/import_eiopa_insurance_statistics.py
python scripts/import_entsoe_energy_data.py   # vyžaduje ENTSO-E token
python scripts/import_gie_energy_data.py      # vyžaduje GIE_API_KEY
```

Výstup: `{BANKINTEL_DATA_DIR}/eba/eba_banking_long.csv` atd.

## 3. Health check

`GET /api/health/platform` — stav FTS, metadata sidecar (`metadata_sidecar_dir`, `sidecar_ready`), mirror CSV + hinty.

## 4. Noční joby v Javě

| Job | Schedule | Leader? | Co dělá |
|-----|----------|---------|---------|
| `nightlyExternalMaintenance` | 02:30 UTC | ano | Python FTS + EBA/EIOPA import (pokud `BANKINTEL_MAINTENANCE_ENABLED=1` a Python dostupný) |
| `catalogWarmupNightly` | 03:00 UTC | ano | FTS warmup cache |
| `rssDueSync` | každých 5 min | ne | RSS feed sync (všechny repliky) |
| `managerCacheRefresh` | 6 h | ano | clear mirror CSV cache |
| `macroSnapshotPlaceholder` | 24 h | ano | Python `build_macro_topics_snapshot.py` pokud `BANKINTEL_PYTHON_ROOT`; jinak placeholder log |

V multi-instance produkci nastavte `BANKINTEL_SCHEDULER_LEADER=1` na jedné replice; ostatní joby se přeskočí s logem.
