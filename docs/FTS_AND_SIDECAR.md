# FTS index a sidecar (do hloubky)

Jak je řešený fulltextový katalogový index a jeho „sidecar". Tenhle dokument je psaný tak, aby
technik uměl najít, kde v indexu/rankingu vzniká chyba. Navazuje na [SEARCH_MAP.md](SEARCH_MAP.md).
Doplňkové (historické) audity: `docs/archive/search_v2_fts_index_audit.md`,
`docs/archive/search_v2_sidecar_enrichment_changes.md`, `backend-java/docs/DEPLOY_DATA.md`.

## 0. Důležité: index NENÍ v repozitáři

Katalogový index je **řádově gigabajty** (classic ~9,5 GB) a je záměrně mimo git (`data/` v
`.gitignore`). Bez indexu aplikace naběhne, ale katalogové vyhledávání jede v omezeném/JSONL režimu.
Jak ho získat:

1. **Snapshot při prvním startu** — `search/FtsIndexBootstrapRunner.java` (`@Order(40)`): když
   `ftsDbPath()` chybí a je nastaveno `FTS_INDEX_SNAPSHOT_URL` (`.gz`/`.zip`), stáhne a nainstaluje
   `classic_catalog_search.sqlite`. Bez URL jen zaloguje varování a nechá search na JSONL fallbacku.
2. **Vlastní build** — classic index **nestaví Java**; staví ho Python skript
   `scripts/build_classic_catalog_fts_index.py` (v referenčním repu, viz `backend-java/docs/DEPLOY_DATA.md`).
   Sidecar naopak **staví Java** (viz §3).

## 1. Dva indexy vedle sebe

| | Classic (engine V1) | Sidecar (engine V2) |
|--|--------------------|---------------------|
| Soubor | `classic_catalog_search.sqlite` | `search_v2_sidecar.sqlite` |
| Staví | Python skript (mimo Javu) | **Java** (`SearchCatalogSidecarBuilder`) |
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
</content>
