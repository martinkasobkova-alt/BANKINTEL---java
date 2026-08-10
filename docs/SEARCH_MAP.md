# Mapa vyhledávání (SEARCH_MAP)

Katalogové vyhledávání je nejsložitější část backendu. **Existují dva vyhledávací enginy** a o tom,
který požadavek obslouží, rozhoduje feature flag — na tom závisí i to, kde bydlí případná chyba.
Detail FTS indexu a sidecaru je v [FTS_AND_SIDECAR.md](FTS_AND_SIDECAR.md).

Kód: `backend-java/src/main/java/cz/bankintel/search/`.

## 0. Rozcestník enginů (kde se routuje)

- `controller/catalog/CatalogController.java` — `POST /api/catalog/deep-search`:
  `searchV2FeatureFlags.useV2(payload) ? searchV2Service.search(payload) : catalogDeepSearchService.deepSearch(payload)`.
  Při aktivní V1 může V2 běžet ve **shadow** módu (`SEARCH_V2_SHADOW_MODE`).
- `POST /api/catalog/search-v2` — vždy přímo `SearchV2Service`.
- Stejné `useV2()` rozhodnutí je i v `CatalogSearchStreamService` (SSE) a `CatalogFollowupService` (follow-up).
- `search/v2/orchestration/SearchV2FeatureFlags.java` — `useV2()` čte per-request override
  `search_engine_version`/`engine`, jinak env `SEARCH_ENGINE_VERSION` (`.env` = `v2`).
- Jednoduché „classic" hledání bez AI: `CatalogClassicSearchService` → `CatalogIndexStore.searchSource(...)`.

```
POST /api/catalog/deep-search
        │  SearchV2FeatureFlags.useV2()?
        ├── ANO → SearchV2Service.search()      (engine V2, sidecar index)   [§3]
        └── NE  → CatalogDeepSearchService       (engine V1, classic FTS)     [§2]
POST /api/catalog/search-v2  → SearchV2Service (vždy)
POST /api/catalog/search     → CatalogClassicSearchService (bez AI)
```

## 1. Classic FTS index (`classic_catalog_search.sqlite`) — engine V1

Fulltext v SQLite FTS5. **Index se do repa nedává** (řádově GB) — viz [FTS_AND_SIDECAR](FTS_AND_SIDECAR.md).

- **Otevření DB:** `CatalogSqliteReadPool.java` — jediný, kdo otevírá classic DB
  (`jdbc:sqlite:file:<cesta>?mode=ro`, read-only pool). Když `.sqlite` chybí → výjimka.
- **Cesta:** `CatalogSearchProperties.ftsDbPath()` = `bankintel.catalog.fts-db` nebo
  `CATALOG_SEARCH_INDEX_DIR/classic_catalog_search.sqlite`.
- **Engine:** `CatalogIndexStore.java` (~1400 řádků) — dotazuje FTS5 tabulku `catalog_fts`
  a row-store `catalog_rows_lookup`. Ranking `ORDER BY bm25(catalog_fts)` — **čisté bm25 bez
  váhování polí**. Pro velké zdroje (`ecb2`, `fred`) `resolveFtsQueryPlan()` přepíná mezi
  bm25-ordered a rychlou neuspořádanou cestou (prahy `FTS_BM25_ORDER_THRESHOLD=45000` atd.).
- **Match výraz + čeština:** `CatalogTextUtils.java` (stavba MATCH výrazu) + `CzTextStemmer.java`
  (prefix-stemming, `MIN_STEM_PREFIX_LEN=5`). Tokenizer `unicode61 remove_diacritics 2` skládá
  diakritiku, ale **nestemuje** — proto to prefix-widening. „Nenajde se skloňované české slovo" bydlí tady.
- **Skórování po retrievalu:** `search/scoring/CatalogScoringPipeline.scoreAndRankAsMaps(...)`.
- **Fallback bez indexu:** pod profilem `prod` chybějící DB vyhodí chybu; jinak varuje a čte JSONL
  (`jsonlScan`, cap 500k). „Tiché pomalé/prázdné hledání" = chybějící `.sqlite`.
- **Invalidace cache:** `catalogVersion()` = `sqlite:<velikost>:<mtime>` — přestavěný index sám
  zneplatní cache (`CatalogSearchResultCache`).

## 2. Deep-search (AI) — engine V1 (`CatalogDeepSearchService.java`)

Tok `deepSearchWithLanes()`:
1. **Plán** — `CatalogQueryPlanner.planTyped` → `SearchPlan` (searchTerms, geoIntent, planner=openai/local).
2. **Routing zdrojů** — `narrowSourcesForGeo(...)` (CZ-only `arad`,`csu`; cizí geo max 4 zdroje).
3. **Paralelní lanes** (virtuální vlákna) — jedna `searchLane()` per zdroj: sidecar seeds +
   per-term budgetovaný FTS (`searchFtsHitsWithinBudget`), skórováno `CatalogScoringPipeline`.
4. **Normalizace/dedup/geo** — `dedupeCandidates`, `rerankCandidatesForGeo`, `CatalogSearchVariantDedup`,
   `CatalogStructuredSemanticCompatibilityService`.
5. **LLM rerank nad daty** — `CatalogAiDataResolver.rankCandidates` (jen když `use_ai`).
6. **Verifikace živým náhledem** — `CatalogDeepSearchPreviewService.verifyCandidates` → bucketing
   verified / possible / discarded (`CatalogDeepSearchFinalRanker`, `CatalogDeepSearchPromotion`).
7. **Odpověď + fallback** — `CatalogSearchAnswerService.composeStory`; webový fallback
   `service/research/WebResearchService` jen když nic platného nevzniklo.

## 3. Search-v2 — engine V2 (`SearchV2Service.java`)

Nový engine (~2000 řádků), `runSearch()` po fázích (každá měřená do `SearchV2Trace`):

```
plan (SearchV2QueryPlanner) → allowedSources → retrieve (SearchV2FtsRetriever)
  → geo constraints (advisory) → selectRerankPool (max 240)
  → rerank (SearchV2BatchReranker → SearchV2SemanticValidator) → final rank (SearchV2FinalReranker)
  → coverage (SearchV2CoverageChecker) → volitelný retry (SearchV2RetryPlanner)
  → preview verify (SearchV2PreviewVerifier) → results/verified/possible → answer → web fallback
```

- **Sidecar index** (`search/v2/sidecar/SearchCatalogSidecarIndex.java`) je srdce V2 rankingu —
  FTS5 tabulka `sidecar_fts` s **oddělenými kanonickými sloupci** a **explicitně váženým**
  `bm25(sidecar_fts, …)`. Detail v [FTS_AND_SIDECAR §2](FTS_AND_SIDECAR.md). **V2 „špatný ranking"
  bydlí tady, ne v `CatalogIndexStore`.**
- **Cache:** `SearchV2CacheService` — klíč obsahuje `catalogVersion()` + `sidecarIndex.contentRevision()`,
  takže přestavěný index/sidecar invalidují. `no_cache`/`debug` obchází.
- **Feature flagy:** `semanticRetrievalEnabled()` = `SEARCH_SEMANTIC_RETRIEVAL_ENABLED` (default `false`,
  nezávislé na `SEARCH_CATALOG_INDEX=sidecar`); vektorová cesta `SEARCH_VECTOR_RETRIEVAL_ENABLED`.
  AI plánovač a AI reranker jsou nezávislé — **reranker je defaultně vypnutý**.
- **Odolnost náhledu:** `SearchV2PreviewBulkhead`, `SearchV2PreviewCircuitBreaker`, `SearchV2PreviewTimeoutPolicy`.

## 4. Konektory (`cz.bankintel.connector`)

- **Kontrakt:** `BaseConnector` — `sourceType()`, `fetch(source)`, `parse(raw, source)`.
- **Routing:** `ConnectorFactory` — mapa `sourceType → BaseConnector`, `normalizeSourceType()`
  řeší aliasy (`commodities→worldbank_pink_sheet`, `data360|world_bank_data360`, `ecb2→ecb`, …).
- **Registrované:** `AradConnector` (ČNB/ARAD), `CsuConnector` (ČSÚ), `EurostatConnector`,
  `EcbConnector`, `BisConnector`, `ImfConnector`, `OecdConnector`, `Data360Connector` (World Bank),
  `WorldbankPinkSheetConnector` (komodity), `TradingEconomicsConnector`, `FileUploadConnector`.
- **Akcie** nejsou `BaseConnector` — `sources/stocks/StockSearchService` je volaný přímo V2 retrieverem
  (`source_type=stocks`, náhled přes `yahoo_finance`).

## 5. LLM vrstva (`search/openai`)

- `OpenAiClient` — `POST /v1/chat/completions` (+ `/v1/responses` pro web search). Klíč
  `OPENAI_API_KEY`. **`isConfigured()`** = klíč není prázdný AND `OPENAI_COMMENTARY` není falsy;
  každá LLM cesta to kontroluje a jinak spadne na deterministický fallback.
- Modely per úloha (`OpenAiModelTask`): `OPENAI_MODEL_PLANNER` (`gpt-5.4-nano`),
  `OPENAI_MODEL_RERANKER` (`gpt-5.4-nano`), `OPENAI_MODEL_CHAT` (`gpt-5.4-mini`).
- **V2 plánovač:** `search/v2/planner/SearchV2QueryPlanner` (prompt `planner_prompt.md`, verzovaný;
  exact-entity `ExactEntityResolver`, ontologie `search/v2/ontology/*`; fallback `localPlan`).
- **V2 reranker:** `search/v2/reranking/SearchV2SemanticValidator` (batch 5, virtuální vlákna),
  finální deterministické pořadí `SearchV2FinalReranker`.

## 6. Rychlý index „kde by ta chyba byla"

| Symptom | Kde hledat |
|---------|------------|
| V1: nic nenajde / špatné bm25 / starý index | `CatalogIndexStore` (+ `CatalogTextUtils`, `CzTextStemmer`, `CatalogSqliteReadPool`, `CatalogScoringPipeline`); build indexu = Python `build_classic_catalog_fts_index.py` / `FtsIndexBootstrapRunner` |
| V2: nic nenajde / špatný ranking | `search/v2/sidecar/SearchCatalogSidecarIndex` (vážené bm25, strict/relaxed lanes) + `SearchCatalogSidecarBuilder` (enrichment) + `SearchV2FtsRetriever` + `SearchV2FinalReranker` |
| V2: stará data po přestavbě indexu | `contentRevision` v `SearchCatalogSidecarIndex` + klíč `SearchV2CacheService` (`catalogVersion` + `contentRevision`) |
| AI plán/rerank blbne | `SearchV2QueryPlanner` / `SearchV2SemanticValidator` / `OpenAiClient.isConfigured()` |
| Skloňované české slovo bez výsledku | `CzTextStemmer` + tokenizer `unicode61 remove_diacritics 2` (nestemuje) |
| „Tiché" prázdné/pomalé hledání | chybějící `.sqlite` index → JSONL fallback (mimo `prod`) |

## 7. Konfigurace vyhledávání (env)

`SEARCH_ENGINE_VERSION` (`v2`), `SEARCH_V2_SHADOW_MODE`, `SEARCH_CATALOG_INDEX` (`sidecar`/`legacy`),
`CATALOG_SEARCH_INDEX_DIR`, `CLASSIC_CATALOG_FTS_DB`, `SEARCH_CATALOG_SIDECAR_DIR`,
`SEARCH_CATALOG_SIDECAR_FTS_DB`, `SEARCH_SEMANTIC_RETRIEVAL_ENABLED` (`false`),
`SEARCH_VECTOR_RETRIEVAL_ENABLED`, `FTS_INDEX_SNAPSHOT_URL`, `OPENAI_API_KEY` (+ `OPENAI_MODEL_*`).
Rozlišení hodnot: `util/BankIntelEnvVars` (OS env → sys-props → `.env`), bind přes `application.yml` +
`config/BankIntelProperties`.
</content>
