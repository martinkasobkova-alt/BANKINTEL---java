# FTS index a sidecar (do hloubky)

Jak je řešený fulltextový katalogový index a jeho „sidecar". Tenhle dokument je psaný tak, aby
technik uměl najít, kde v indexu/rankingu vzniká chyba. Navazuje na [SEARCH_MAP.md](SEARCH_MAP.md).
Doplňkové (historické) audity: `docs/archive/search_v2_fts_index_audit.md`,
`docs/archive/search_v2_sidecar_enrichment_changes.md`, `backend-java/docs/DEPLOY_DATA.md`.

## 0. Důležité: index NENÍ v repozitáři

Katalogový index je **řádově gigabajty** (classic ~9 GB, celé `data/` 24,5 GB) a je záměrně mimo
git (`data/` v `.gitignore`). Bez indexu aplikace naběhne, ale katalogové vyhledávání jede
v omezeném/JSONL režimu. Kolik místa je potřeba na serveru → **§7**. Jak index získat:

1. **Snapshot při prvním startu** — `search/FtsIndexBootstrapRunner.java` (`@Order(40)`): když
   `ftsDbPath()` chybí a je nastaveno `FTS_INDEX_SNAPSHOT_URL` (`.gz`/`.zip`), stáhne a nainstaluje
   `classic_catalog_search.sqlite`. Bez URL jen zaloguje varování a nechá search na JSONL fallbacku.
2. **Vlastní build** — oba indexy staví **Java**: classic
   `search/ClassicCatalogFtsIndexBuilder.java` (port `scripts/build_classic_catalog_fts_index.py`),
   sidecar `SearchCatalogSidecarBuilder` (viz §3). Na produkčním hostu proto kvůli indexu
   nemusí být Python. **Než rebuild pustíte, přečtěte si §8** — rebuild z JSONL vrací
   prořezané řady.

## 1. Dva indexy vedle sebe

| | Classic (engine V1) | Sidecar (engine V2) |
|--|--------------------|---------------------|
| Soubor | `classic_catalog_search.sqlite` | `search_v2_sidecar.sqlite` |
| Staví | **Java** (`ClassicCatalogFtsIndexBuilder`) | **Java** (`SearchCatalogSidecarBuilder`) |
| FTS tabulka | `catalog_fts` (+ `catalog_rows_lookup`) | `sidecar_fts` (kanonické sloupce) |
| Ranking | `bm25(catalog_fts)` — **bez vah** | `bm25(sidecar_fts, …)` — **explicitní váhy** |
| Otevírá | `CatalogSqliteReadPool` (read-only) | `SearchCatalogSidecarIndex` (WAL, `busy_timeout`) |
| Engine | `CatalogIndexStore` | `SearchCatalogSidecarIndex` |

Cesty řeší `search/CatalogSearchProperties.java`:
`ftsDbPath()` ← `CLASSIC_CATALOG_FTS_DB` / `CATALOG_SEARCH_INDEX_DIR`;
`sidecarDir()` ← `SEARCH_CATALOG_SIDECAR_DIR` (default `<indexDir>/../search_v2_sidecar`);
`sidecarFtsDbPath()` ← `SEARCH_CATALOG_SIDECAR_FTS_DB`.

## 2. Sidecar — schéma a ranking (`SearchCatalogSidecarIndex.java`)

- **Schéma** (`initializeSchema`): tabulky `sidecar_doc(source, series_id, dataset, doc_json,
  metadata_quality, content_hash, fts_rowid)`, `sidecar_source_state`, a FTS5 `sidecar_fts` s
  **oddělenými kanonickými sloupci**: `canonical_title, primary_concept, aliases, original_title,
  description, category, geo, source_label`. Tokenizer `unicode61 remove_diacritics 2`.
- **Ranking** (`executeFtsQuery`): `bm25(sidecar_fts, 0.1,0.1,0.5,8.0,7.0,5.0,3.0,1.5,1.0,1.0,0.5)`
  (title/koncept mají vysokou váhu), pak re-scoring `scoreDoc()` / `fieldScore()` (canonical_title 20,
  primary_concept 18, aliases 12, …) + `metadataQualityScore` + lifecycle boost.
  **Kdo řeší „V2 vrátil špatné pořadí", řeší tyto váhy.**
- **Retrieval lanes** (`searchHybridSqlite`): STRICT (konjunktivní `buildMatch`) + omezený RELAXED
  (`buildRelaxedMatch`, field-scoped OR) s fallbackem na strict při timeoutu; `searchBalancedSqlite`
  drží kvótu per zdroj. Timeout dotazu `SQLITE_SEARCH_TIMEOUT_SECONDS=1`. České skloňování přes
  `CzTextStemmer.ftsPrefixStem` (`exactOrStemWidenedClause`).

## 3. Build/populate sidecaru

- `rebuild()` / `ensureSourceIndexed()` / `buildSource()` čtou per-source `<source>.jsonl` (raw,
  `CATALOG_SEARCH_INDEX_DIR`) překryté metadaty `<source>.jsonl` (`CATALOG_SEARCH_METADATA_DIR`)
  přes `forEachSourceDocument` → `SearchCatalogSidecarBuilder`.
- **Inkrementální**: klíč `content_hash` + `input_signature` (velikost/mtime souboru +
  `builder.enrichmentVersion()`). `contentRevision` se zvedne **jen při změně** → stará data se
  nezacyklí (guard proti stale cache).
- Enrichment (`SearchCatalogSidecarBuilder`): kanonické tituly/aliasy/koncepty + `industry_sector`,
  `nominal_real`, `dataset_family`, `catalog_family` + lifecycle (`SearchSeriesLifecycleClassifier`:
  current vs historical).
- Endpointy: `POST /api/catalog/search-v2/sidecar/rebuild|optimize`, `GET /api/catalog/search-v2/sidecar/coverage`.

## 4. Metadatový sidecar pro classic index

Classic index ukládá serializovaný `row_json`, ale některým zdrojům chybí geo/jednotka/frekvence.
`CatalogIndexStore.mergeSidecarRows` / `sidecarRescueRows` (a `CatalogSearchMetadataSidecar`) chybějící
metadata doplní z metadatového sidecaru. „Chybí jednotka/území u výsledku" bydlí tady.

## 5. Invalidace a konzistence cache

- Classic: `CatalogIndexStore.catalogVersion()` = `sqlite:<velikost>:<mtime>`.
- Sidecar: `SearchCatalogSidecarIndex.contentRevision()`.
- `SearchV2CacheService` skládá finální klíč z **obou** → přestavěný index/sidecar automaticky
  zneplatní staré výsledky. Pokud se ale index přepíše „na stejnou velikost i mtime", verze se
  nezmění a cache může vracet stará data — na to pozor při ručních zásazích do souboru.

## 6. Časté třídy chyb (checklist pro revizi)

1. **Prázdné výsledky** → chybí `.sqlite` (JSONL fallback mimo `prod`; pod `prod` výjimka). Ověřit
   `GET /api/catalog/status` a existenci indexu na disku.
2. **Špatné pořadí (V1)** → `bm25` bez vah v `CatalogIndexStore` + `CatalogScoringPipeline`.
3. **Špatné pořadí (V2)** → váhy `bm25(sidecar_fts, …)` + `scoreDoc`/`fieldScore` v `SearchCatalogSidecarIndex`.
4. **České skloňování** → `CzTextStemmer` (prefix-stem ≥5 znaků), tokenizer nestemuje.
5. **Stará data po reindexu** → `contentRevision` / `catalogVersion` v klíči cache.
6. **Velké zdroje (ecb2/fred) pomalé/ořezané** → `resolveFtsQueryPlan` prahy v `CatalogIndexStore`.
7. **Chybí geo/unit/freq** → metadatový sidecar rescue (§4).
8. **Instance padá v noci, ne přes den** → došlo místo na disku při noční přestavbě indexu (§7).
</content>

## 7. Kolik to zabere místa na serveru

Naměřeno `du` nad `data/` 2026-09-01:

| Položka | Velikost | Potřeba |
|--|--|--|
| `search_v2_sidecar/search_v2_sidecar.sqlite` | 12,4 GB | ano (engine V2) |
| `catalog_search_indexes/classic_catalog_search.sqlite` | 8,9 GB | ano (engine V1) |
| per-source `*.jsonl` (`ecb2` 1,7 GB, `fred` 1,2 GB, …) + `catalog_search_metadata` | ~3,2 GB | ano (zdroj pro rebuild + metadata rescue §4) |
| `search_v2_sidecar/vector-embedding-cache.sqlite` + `vector-lucene` | 4,8 GB | jen když `SEARCH_VECTOR_RETRIEVAL_ENABLED=true` (viz níže) |
| **klidový stav bez vector částí** | **24,5 GB** | |

> **Pozor na dva různé flagy.** Vector části gatuje `SEARCH_VECTOR_RETRIEVAL_ENABLED`
> (`SearchVectorProperties#enabled`, default `"false"`), **ne** `SEARCH_SEMANTIC_RETRIEVAL_ENABLED`.
> V dev `.env` je vector `true`, v `render.yaml` se nenastavuje → prod jede na default `false`.
> Prod tedy hledá jinak než dev; kdo chce stejné chování, zapne flag a připočte 4,8 GB
> (klid 29,3 GB, špička 38,2 GB).

### Špička při noční přestavbě

`ClassicCatalogFtsIndexBuilder` (spouští ho `BankIntelMaintenanceService` v 02:30 UTC, jen když
je `BANKINTEL_MAINTENANCE_ENABLED`) **nestaví index na místě** — stejně jako Python předloha:
nový index vzniká v `classic_catalog_search.tmp.sqlite` vedle starého a teprve hotový ho nahradí.
Kdyby se psalo do ostrého souboru a build spadl, zůstane rozbitý index a hledání je mrtvé. Po dobu přestavby leží na disku
obě kopie:

```
24,5 GB (klid) + 8,9 GB (tmp kopie classic indexu) = 33,4 GB špička
```

To je důvod, proč se instanci vyčerpá disk **v noci a ne přes den**, a proč `render.yaml` měl
původně `sizeGB: 20` špatně — nestačilo to ani na klidový stav, natož na špičku. Teď je tam 60 GB.

Kdo mění velikost disku, počítá **špičku, ne klid**. A pokud přibude další zdroj do
`FTS_PILOT_SOURCES`, roste obojí — klidový stav i tmp kopie.

### RAM

Rychlost vyhledávání stojí a padá na tom, kolik indexu se vejde do page cache. Naměřený rozdíl
mezi studeným a teplým dotazem je řádový (jednotky sekund vs stovky ms), protože jinak se
čte z disku. Pro plnou rychlost chce stroj RAM ≥ velikost aktivní části indexu.

## 8. Rebuild vrací prořezané řady (číst před zapnutím noční údržby)

Ostrý index **není** jen výstup buildu. Po něm ještě jedou nástroje, které řežou a obohacují
řádky **přímo v indexu**, ne v JSONL:

- `prune_fred_local_series.py`, `prune_ecb_stale_series.py`, `prune_data360_stale_series.py`,
  `prune_stale_catalog_fts.py` — vyhazují mrtvé a lokální řady
- `enrich_fts_dimensions_inplace.py` — dopočítá členy dimenzí a označí řádek `__dimx__`

Build čte JSONL, takže **všechno prořezané vrátí zpátky**. Naměřeno 2026-09-01:

| zdroj | řádků v JSONL | v indexu | prořezáno |
|--|--|--|--|
| fred | 844 759 | 261 602 | −583 157 (69 %) |
| ecb2 | 544 001 | 424 536 | −119 465 |
| imf | 27 647 | 19 464 | −8 183 |
| eurostat | 8 446 | 5 854 | −2 592 |
| data360 | 10 320 | 7 785 | −2 535 |
| arad, bis, csu, oecd4 | — | — | 0 |

Rebuild by tedy do hledání vrátil ~716 tisíc řad, které z něj někdo vědomě vyhodil.

**Python skript to udělá mlčky.** `ClassicCatalogFtsIndexBuilder` se místo toho zastaví: když by
build některý zdroj nafoukl o víc než 5 % (a zároveň o víc než 100 řádků), index **nevymění**,
nechá hotový build jako `.tmp.sqlite` a vyhodí výjimku. Vědomé přepsání se povoluje
`CATALOG_FTS_ALLOW_CURATION_RESET=1`.

Praktický důsledek pro nasazení: `BANKINTEL_MAINTENANCE_ENABLED` **nezapínejte**, dokud není
vyřešené, jak se po rebuildu zopakuje prune a enrich. Bez toho noční údržba zhorší hledání.

### Parita s Python buildem

`ClassicCatalogFtsIndexBuilderPythonParityTest` staví index v Javě z ostrých JSONL a porovnává
ho proti ostrému indexu (arad 9 375, bis 850, csu 1 625 řádků) — `title`, `full_path`,
`search_blob`, `territory` musí sedět znak po znaku. Dvě vědomé odchylky:

- `row_json` — Jackson serializuje bez mezer, Python s nimi. Sloupec je `UNINDEXED`, nehledá se;
  test porovnává rozparsovaný JSON.
- `__dimx__` — značka idempotence z `enrich_fts_dimensions_inplace.py`. Build ji nepřidává,
  protože členy dimenzí zapéká rovnou; test ji před porovnáním odstraní.

Neportované: `CATALOG_FTS_PRUNE_STALE_BEFORE` (opt-in, nepoužívá se). Když je nastavené, build
odmítne běžet, místo aby postavil jiný index než Python.

