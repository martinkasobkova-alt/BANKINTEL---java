# Deploy data & nightly jobs (BankIntel v2)

Java backend **čte** mirror CSV, FTS SQLite, metadata sidecary a macro snapshoty. Build FTS indexu
je od 2026-09-01 v Javě; ostatní importy (EBA, EIOPA, ENTSOE, GIE, macro topics) zatím staví Python
skripty v referenčním repu — **ale offline, mimo server**: vyrobí statické soubory, které se na
produkci jen nakopírují. Produkční host proto Python nepotřebuje.

## Env proměnné

```env
BANKINTEL_DATA_DIR=C:/Bankoapp-main/BankIntel-v2/data
CATALOG_SEARCH_INDEX_DIR=C:/Bankoapp-main/BankIntel-v2/data/catalog_search_indexes
CATALOG_SEARCH_METADATA_DIR=C:/Bankoapp-main/BankIntel-v2/data/catalog_search_metadata
CLASSIC_CATALOG_FTS_DB=C:/Bankoapp-main/BankIntel-v2/data/catalog_search_indexes/classic_catalog_search.sqlite

# Volitelné — první boot stáhne FTS SQLite, pokud CLASSIC_CATALOG_FTS_DB chybí (Render / čistý disk)
# FTS_INDEX_SNAPSHOT_URL=https://storage.example.com/classic_catalog_search.sqlite.gz

# Volitelné — noční Python importy (02:30 UTC). Build FTS indexu už mezi ně NEPATŘÍ (dělá ho Java).
# Než zapnete, přečtěte si docs/FTS_AND_SIDECAR.md §8 — rebuild vrací prořezané řady.
# BANKINTEL_MAINTENANCE_ENABLED=1
# BANKINTEL_PYTHON_ROOT=C:/Bankoapp-main/Bankoapp-main

# Multi-instance prod — nastavte na přesně jedné replice
BANKINTEL_SCHEDULER_LEADER=1
```

> **Historická past:** do 2026-09-01 mířil `BANKINTEL_DATA_DIR` do starého Python repa
> (`Bankoapp-main/backend/data`), zatímco index se bral z BankIntel-v2. Lokálně to fungovalo,
> ale v produkci (`BANKINTEL_DATA_DIR=/data`) tam mirror CSV ani `oecd4` nebyly — EBA, EIOPA,
> ENTSOE a GIE byly v Manager Exploreru mrtvé. Data se přesunula do `BankIntel-v2/data/`.

## 0. Co musí být na disku (29,9 GB)

Nic z toho není v gitu ani v Docker image. Naměřeno 2026-09-01:

| cesta pod `BANKINTEL_DATA_DIR` | velikost | k čemu |
|--|--|--|
| `catalog_search_indexes/` | 11,8 GB | classic FTS sqlite (8,9) + per-source JSONL |
| `search_v2_sidecar/` | 12,7 GB | sidecar index pro engine V2 |
| `oecd4/` | 4,1 GB | offline OECD mirror (123 datasetů) + `segment_mirror_index.json` |
| `macro_topics_snapshot.json` + `macro_topics_snapshot_parts/` | 1,1 GB | stránka Podle země a témat |
| `segment_series_assignments.json` | 68 MB | Manager Explorer segmenty |
| `final_segment_series_selection_manager_tiers.json` | 23 MB | výběr řad pro Manager Explorer |
| `final_macro_series_selection_sector_relevance.json` | 22 MB | sektorová relevance |
| `imf_availability.json` | 19 MB | dostupnost IMF řad |
| `catalog_search_metadata/` | 51 MB | metadatový sidecar (§4 FTS_AND_SIDECAR) |
| `macro_context_series.json` | 5,4 MB | makro kontext |
| `manager_segment_country_relationships.json` | 2,1 MB | vazby segment↔země |
| `eba/`, `eiopa/`, `entsoe/`, `gie/`, `acea/`, `financial_markets/` | 81 MB | mirror CSV (`*_long.csv`) |
| `eurostat_catalog_disk_cache.json.gz`, `macro_extra_tables_snapshot.json`, `arad_v13_sets_bootstrap.json` | <1 MB | drobné cache |

`vector-embedding-cache.sqlite` a `vector-lucene` (4,8 GB v `search_v2_sidecar/`) jsou potřeba jen
při `SEARCH_VECTOR_RETRIEVAL_ENABLED=true`.

Po přesunu z 2026-09-01 obsahuje `BankIntel-v2/data/` **přesně tuhle runtime sadu** a nic navíc
(žádné `raw/` zdrojové xlsx, `.bak_*` ani logy — ty zůstaly v referenčním repu). Nasazení dat je
proto jeden rsync celého adresáře:

```bash
rsync -avz --progress data/ user@server:/data/
```

**Ověření po nasazení:** `GET /api/health/platform` musí vrátit `overall_ready: true`,
`mirrors_available == mirrors_total` a `fts_db_available: true`.


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
