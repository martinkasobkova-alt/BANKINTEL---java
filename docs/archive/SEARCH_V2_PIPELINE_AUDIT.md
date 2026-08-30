# Technický audit — Search Pipeline BankIntel V2 (Java / Spring Boot)

> Datum: 2026-07-23. Dokumentace popisuje **současný stav** V2 vyhledávání. Žádné návrhy změn.
> Základna: `C:\Bankoapp-main\BankIntel-v2\backend-java\`, balíček `cz.bankintel.search.v2.*`
> (+ sdílené classic pomůcky v `cz.bankintel.search.*`).

**Zásadní kontext:** existují dva enginy — **classic (v1)** a **v2**. Globální default je
`SEARCH_ENGINE_VERSION=v1`, ale **`.env` i `render.yaml` nastavují `v2`**, takže reálně běží V2.
Endpoint `/api/catalog/search-v2` volá V2 vždy napřímo. Tento dokument popisuje výhradně **V2**.

Efektivní konfigurace prostředí (render.yaml / .env):
- `SEARCH_ENGINE_VERSION=v2` (V2 aktivní)
- `SEARCH_CATALOG_INDEX=sidecar` (sidecar FTS index aktivní)
- `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false` (reportovací flag)
- `SEARCH_VECTOR_RETRIEVAL_ENABLED` — v `.env` `true`, v `render.yaml` NENASTAVENO → prod default `false`
- Sidecar SQLite: `/data/search_v2_sidecar/search_v2_sidecar.sqlite`
- Classic FTS SQLite: `/data/catalog_search_indexes/classic_catalog_search.sqlite`

---

## 1. Celkový tok (krok za krokem)

```
User query (POST /api/catalog/search-v2  nebo  /deep-search s engine=v2)
   |
[0] SearchV2Service.search()  - kontrola FINAL cache (10 min, in-memory)
   |  (cache miss)
[1] PLANOVANI  -> SearchV2QueryPlanner.plan()
       |- ExactEntityResolver.resolve()      (deterministicky, 22 entit)
       |- SearchV2ConceptRegistry.resolve()  (deterministicky, 18 konceptu)
       |- localPlan() = deterministicky fallback (spocita se VZDY dopredu)
       |- pokud high-confidence exact entita -> exactPlan() a KONEC planovani (bez LLM)
       |- jinak LLM PLANNER (gpt-5.4-nano) -> validatePlan() (deterministika prebiji LLM)
   |  SearchQueryPlan (koncepty, geo, zdroje, query varianty, entity/source routing)
[2] EXPANZE DOTAZU -> SearchV2QueryExpander.expand()  -> max 8 variant
   |
[3] RETRIEVAL -> SearchV2FtsRetriever.retrieve()   (RETRIEVAL cache 30 min)
       |- FTS lane (paralelne, virtualni vlakna):
       |     - sidecar mod: 1 globalni dotaz/variantu pres SearchCatalogSidecarIndex (SQLite FTS5)
       |     - legacy mod:  #zdroju x #variant dotazu pres CatalogIndexStore (SQLite FTS5)
       |     - commodities + stocks vzdy zvlast
       |- VECTOR lane (async, ONNX e5-small + Lucene KNN) - DEFAULTNE VYPNUTA
       |- merge + source-balance (SearchV2CandidateMerger, <=240)
       |- RRF fuze FTS+vector (SearchCandidateFusion, <=240)
   |  RetrievalResult (<=240 kandidatu)
[4] VYBER RERANK POOLU -> selectRerankPool()  -> <=60 kandidatu
   |
[5] SEMANTICKY RERANK -> SearchV2BatchReranker -> SearchV2SemanticValidator
       |- davky po 5, paralelne (virtualni vlakna)
       |- LLM RERANKER (gpt-5.4-nano), json_object, bez retry
       |- deterministicky fallback per-davka pri selhani
       |- enforceProvenStructuredConflicts() - deterministika muze prebit LLM verdikt na "drop"
   |  List<SemanticDecision>  (keep / supporting / drop + role + skore)
[6] FINALNI RAZENI -> SearchV2FinalReranker.finalRank()  (deterministicky, okno ~72)
   |
[7] COVERAGE CHECK -> SearchV2CoverageChecker.check()  -> complete / partial / insufficient
   |
[8] (volitelne) RETRY -> SearchV2RetryPlanner.retryTerms() -> novy retrieval+rerank, adopce jen kdyz zlepsi
   |
[9] PREVIEW VERIFIKACE -> SearchV2PreviewVerifier.verifyTopOnly()  <- ZIVA HTTP volani konektoru
       |- CatalogPreviewService -> CatalogPreviewOrchestrator -> ConnectorFactory.fetch()
   |  accepted / statuses (verified vs candidate)
[10] VYBER FINALNICH VYSLEDKU -> selectFinalResults()  -> <=12 (max 30)
   |
[11] ODPOVED/STORY -> CatalogSearchAnswerService.composeStory()
       |- LLM CHAT (gpt-5.4-mini) -> {headline_cz, story_cz, drivers[]}  nebo deterministicky text
   |
[12] Sestaveni JSON odpovedi (results/verified/possible/answer/coverage/trace/timings) + ulozeni do FINAL cache
   |
Response -> (u /deep-search jeste CatalogFollowupService.bootstrapConversation())
```

Kroky [3]-[9] jsou jadro, kde je vetsina latence.

---

## 2. Hlavní entry point

| Vrstva | Soubor | Třída | Metoda |
|---|---|---|---|
| **Controller** | `controller/catalog/CatalogController.java` | `CatalogController` | `searchV2(Map body)` — ř. 165; `deepSearch(Map body)` — ř. 148 |
| **Endpoint** | — | — | `POST /api/catalog/search-v2` (vždy V2); `POST /api/catalog/deep-search` (V2 za flagem) |
| **Feature gate** | `search/v2/orchestration/SearchV2FeatureFlags.java` | `SearchV2FeatureFlags` | `useV2(Map)` — ř. 19 |
| **Service (orchestrátor)** | `search/v2/orchestration/SearchV2Service.java` | `SearchV2Service` | `search(Map)` — ř. 91 → `runSearch(...)` — ř. 125 |

`/deep-search` (ř. 148-162): `useV2 ? searchV2Service.search() : catalogDeepSearchService.deepSearch()`;
při `shadowMode()` navíc pustí V2 „na stín". `/search-v2` (ř. 165-167) volá `searchV2Service.search()`
bezpodmínečně. Vstupní dotaz se čte z klíčů `q`/`query`.

**Handlery jednotlivých fází** (metody `SearchV2Service` volané z `runSearch`): `plan()` ř. 580,
`retrieve()` ř. 387, `rerank()` ř. 455, `finalReranker.finalRank()` ř. 197, `coverageChecker.check()` ř. 198,
`verifyPreview()` ř. 370, `selectFinalResults()` ř. 790, `searchAnswerService.composeStory()` ř. 304.

---

## 3. Search pipeline — všechny kroky

| # | Krok | Kde (soubor / třída / metoda) | Vstup | Výstup |
|---|---|---|---|---|
| a | Normalizace/scope requestu | `SearchV2Service.search` | `Map` payload | query string, cache klíče, allowed sources |
| b | Exact entity resolver | `search/v2/entity/ExactEntityResolver.resolve` | query | `ExactEntityResolution` + routing + varianty |
| c | Concept resolver (ontologie) | `search/v2/ontology/SearchV2ConceptRegistry.resolve` | query | `ConceptResolution` (concept ids, confidence) |
| d | LLM planner + validace | `search/v2/planner/SearchV2QueryPlanner.plan` | query + pre-resolvery + registry kontext | `SearchQueryPlan` |
| e | Query expansion | `search/v2/retrieval/SearchV2QueryExpander.expand` | `SearchQueryPlan` | `List<String>` <=8 variant |
| f | FTS/BM25 retrieval | `search/v2/retrieval/SearchV2FtsRetriever` -> `SearchCatalogSidecarIndex` / `CatalogIndexStore` | varianty, zdroje | raw kandidáti (per-variant <=25) |
| g | Vector retrieval | `search/v2/vector/SearchVectorRetriever` (**default off**) | vector query text | <=60 vektorových hitů |
| h | Normalizace kandidátů | `search/v2/normalization/SearchV2CandidateNormalizer.normalize` | raw SQLite řádek | `SearchCandidate` |
| i | Dedup + source-balance | `SearchV2Deduplicator.dedupe` -> `SearchV2CandidateMerger.merge` | kandidáti | <=240 |
| j | RRF fúze (hybrid) | `search/v2/retrieval/SearchCandidateFusion.fuse` | FTS + vector | <=240 (sjednoceno RRF skóre) |
| k | Výběr rerank poolu | `SearchV2Service.selectRerankPool` | <=240 | <=60 |
| l | LLM semantic rerank/validace | `search/v2/reranking/SearchV2BatchReranker` -> `SearchV2SemanticValidator.validate` | plán + <=60 kandidátů | `List<SemanticDecision>` |
| m | Deterministické finální řazení | `search/v2/reranking/SearchV2FinalReranker.finalRank` | kandidáti + decisions | `List<SearchResult>` (okno ~72) |
| n | Coverage check | `search/v2/coverage/SearchV2CoverageChecker.check` | plán + ranked + status | `CoverageResult` |
| o | Retry planner (podmíněně) | `search/v2/coverage/SearchV2RetryPlanner.retryTerms` | plán + coverage | <=6 retry termů |
| p | Preview verifikace (živá data) | `search/v2/orchestration/SearchV2PreviewVerifier.verifyTopOnly` | ranked <=8 | `VerificationResult` (accepted, statuses) |
| q | Výběr finálních výsledků | `SearchV2Service.selectFinalResults` | ranked + accepted | <=12 (max 30) |
| r | Answer/story (LLM) | `search/CatalogSearchAnswerService.composeStory` | query + verified/possible | `{headline_cz, story_cz, drivers}` |

**Reranker** je zároveň **validator** (třída `SearchV2SemanticValidator`) — LLM je „finální sémantická
autorita" pro keep/supporting/drop. Samostatný LLM planner (d) + LLM validator (l) + LLM answer (r) = tři
LLM stupně. Klasický cross-encoder/embedding reranker zde **není** (vektor slouží jen k retrievalu, a i ten
je defaultně vypnutý). Pruning je čistě deterministické (limity poolů + geo hardConflict evidence + `keepLike()`).

---

## 4. Kde se volá LLM

Definitivní konfigurace v `search/openai/OpenAiClient.java`. Modely: `PLANNER`/`RERANKER` = **gpt-5.4-nano**,
`CHAT` = **gpt-5.4-mini** (env override `OPENAI_MODEL_PLANNER/_RERANKER/_CHAT`; deprecated `OPENAI_MODEL`
přebíjí vše). Endpoint: `POST /v1/chat/completions`. `temperature=0.2`. Všechna volání jsou **synchronní
blokující** `HttpClient.send` (HTTP/2 klient sdílený, multiplexuje souběžné dávky).

**V jádru V2 pipeline jsou 3 LLM volání:**

| Volání | Soubor / metoda | Model | Účel | Timeout | Sync/async | Blokuje? | Lze přeskočit? |
|---|---|---|---|---|---|---|---|
| **Planner** | `SearchV2QueryPlanner.plan` -> `openAiClient.plannerCompletionJson` (:87) | gpt-5.4-nano | rozbor dotazu -> strukturovaný plán | connect 3 s / request **12 s**; **retry x2** (jen planner) | synchronní | Ano (plán je vstup retrievalu) | Ano: `use_ai=false`, nekonfig., nebo high-conf exact entita |
| **Reranker/Validator** | `SearchV2SemanticValidator.validate` -> `chatCompletionJson(..., RERANKER)` (:97) | gpt-5.4-nano | sémantické posouzení kandidátů | connect 3 s / request **12 s**; vnější `orTimeout` **20 s**/dávka; **bez retry** | dávky po 5 paralelně, `future.join()` blokuje | Ano (join na kritické cestě) | Ano: `use_ai=false`/nekonfig. -> deterministický fallback |
| **Answer/Story** | `CatalogSearchAnswerService.composeStory` -> `chatJsonObject(..., CHAT)` (:92) | gpt-5.4-mini | textové shrnutí (headline+story+drivers, CZ) | connect 15 s / request **120 s**; **bez retry** | synchronní | Ano | Ano: `use_ai_story=false` -> deterministický text |

- **Structured output:** planner = strict `json_schema` (`SearchV2PlannerStructuredOutput`,
  `max_completion_tokens=900`, `reasoning_effort=none`); reranker = volnější `json_object`
  (`max_completion_tokens=3000`); answer = `json_object`.
- **Retry:** pouze planner (2 pokusy, backoff 200 ms, jen timeout/rate-limit/server-error). Reranker a answer
  se **neopakují**.
- **Mimo jádro** (post-response follow-up přes `/deep-search`): `CatalogFollowupService`,
  `CatalogSeriesExplainService`, `CatalogRelatedSeriesService` také volají LLM (CHAT), ale až po vrácení
  výsledku vyhledávání — nejsou na kritické cestě.

---

## 5. Candidate reduction

Limity z `SearchV2Service.candidateLimits()` (ř. 1166) + retrieveru. Vstupní katalog má statisíce řad
(multi-GB SQLite FTS).

```
Katalog (stovky tisic rad ve FTS indexu)
        |  FTS MATCH per varianta (<=8 variant), limit 25/dotaz
        v
<= ~2 400 (legacy: 12 zdroju x 8 variant x 25)   |   <= ~800 (sidecar: ~3 lanes x 8 x 25-100)
        |  SearchV2Deduplicator.dedupe  (cap 960, first-wins podle source:seriesId)
        v
<= 960
        |  SearchV2CandidateMerger.merge  - source-balance round-robin
        v
<= 240   (MAX_POOL_SIZE; "retrieval_pool_after_merge")
        |  RRF fuze FTS+vector -> opet merge <=240
        v
<= 240
        |  selectRerankPool()  (concept-match 1/3 + strong-title 1/3 + doplneni)
        v
<= 60    (MAX_RERANK_CANDIDATES)
        |  LLM reranker: davky po 5 -> <=12 soubeznych volani
        v
<= 60 decisions  -> filtr keepLike() (zahodi "drop")
        |  finalRank nad VSEMI retrieval kandidaty, okno rankWindow = max(limit*6, 24) = 72 default
        v
<= 72 serazenych
        |  preview verifikace: verifyLimit = min(limit, 8); default preview_top_n = 5
        v
<= 8 overovano zive  (default 5)
        |  selectFinalResults() -> clampLimit
        v
<= 12  (DEFAULT_LIMIT; MAX_LIMIT = 30)
```

**Rozdíly podle režimu:**
- **Legacy vs sidecar** ovlivňuje šířku raw poolu a cestu FTS (per-source vs globální dotaz). Prod = **sidecar**.
- **Vektor zapnutý** přidá <=60 vektorových hitů do RRF fúze; defaultně **vypnutý** -> fúze jen z FTS.
- **`preview_mode`:** `full` (default) ověřuje `min(limit,8)`; `top_preview` ověřuje `min(8, preview_top_n)`;
  `metadata_only` ověří 0 (přeskočí živá data, finální = ranked bez preview brány).
- **Retry** (podmíněné): sloučí původní + retry kandidáty (`merge` cap 240), znovu vybere pool 60 a rerankuje.

---

## 6. Retrieval — sidecar / BM25 / vector / hybrid / ontology / metadata

Orchestruje `search/v2/retrieval/SearchV2FtsRetriever.java`. Executor = `newVirtualThreadPerTaskExecutor()`.
Časové stropy: FTS legacy 20 s (SQL statement 15 s), sidecar 2,5 s (SQL statement 1 s), vector 5 s.
`completeOnTimeout` **nepřeruší** běžící úlohu — jen zahodí výsledek.

**1) BM25 / FTS** — dvě samostatné SQLite databáze:
- **Sidecar** (`SearchCatalogSidecarIndex`): FTS5 tabulka `sidecar_fts`, tokenizer
  `unicode61 remove_diacritics 2`, vážené `bm25(sidecar_fts, ..., canonical_title 8.0, primary_concept 7.0,
  aliases 5.0, original_title 3.0, description 1.5, ...)`. Nad tím ještě **Java-side rerank `scoreDoc`**
  (druhá sada vah: canonical_title 20, primary_concept 18, aliases 12, structured_metadata 10 ...
  + `metadataQualityScore` + lifecycle bonus +-0,75). Dvě dráhy: STRICT (AND) a RELAXED (OR + prefix `*`),
  mix `STRICT_RECALL_SHARE=0.75`. Nová (read-write) `DriverManager` connection per volání (bez poolu).
- **Legacy** (`CatalogIndexStore`): FTS5 `catalog_fts`, `ORDER BY bm25(catalog_fts)`. V2 volá
  **`searchSourceFtsRaw`** — cestu **bez cache, bez scoring pipeline, bez sidecar rescue, bez big-source
  query-plan logiky** (`resolveFtsQueryPlan`/nasdaq rescue/anchoring žijí jen v classic `ftsSearchSqlite`,
  kterou V2 nevolá). Read-only pool 32 connection (`mode=ro`).

**2) Vector / embedding** (`SearchVectorRetriever` + `SearchVectorIndex`): lokální ONNX
**`intfloat/multilingual-e5-small`, 384 dim, CPU**; Lucene `KnnFloatVectorQuery` (HNSW, cosine), topK 60,
overfetch až 1200. **Defaultně vypnuto** (`SEARCH_VECTOR_RETRIEVAL_ENABLED=false`; prod flag nenastavuje -> OFF)
a index se nikde nebuduje automaticky (`rebuild()` jen on-demand). Query embedding se **necachuje**.

**3) Hybrid / kombinace** (`SearchCandidateFusion`): **Reciprocal Rank Fusion** `rrfScore += 1/(rrfK + rank)`,
`rrfK=60` pro obě dráhy stejné (žádné asymetrické váhy). Identita kandidáta `source:seriesId`. Při shodě
přebírá reprezentanta **FTS objekt**. RRF skóre přepíše `SearchCandidate.ftsScore`. Pořadí: FTS lane ->
vector lane -> seřadit dle RRF -> source-balance merge.

**4) Ontology lookup + metadata filtering** — ontologie se používá **před** retrievalem (v expanzi dotazu a
plánu, viz §8), ne jako filtr nad kandidáty. „Metadata filtering" v retrievalu je: source-balance
(round-robin, kvóta), lifecycle boost (aktuální vs historické řady), a **geo hardConflict jako advisory
evidence** pro LLM (ne tvrdý filtr — `assessDeterministicConstraints` explicitně `removed_before_llm: 0`).

**Priority:** (1) explicitní zdroje z requestu -> (2) `plan.explicitSources` -> (3)
`sourceRouting.preferredSources` -> (4) default 12 zdrojů. FTS je hlavní recall; vektor (když zapnutý) přidává
sémantický recall přes RRF; deterministické skórování + LLM validace řeší precision až dál.

---

## 7. Exact entity resolver

`search/v2/entity/ExactEntityResolver.java` + `SearchV2ExactEntityScorer.java`. Data:
`/search_v2/exact_entity_registry.json` (**22 entit** — burzovní indexy NASDAQ-100, S&P 500, DAX, VIX...
+ generický FX parser).

- **Kdy se používá:** **vždy** na začátku `plan()` (:68), **před LLM**. Když vrátí `highConfidenceExact`
  -> `plan()` vrátí `exactPlan` a **přeskočí LLM planner úplně**.
- **Kdy se neprosadí:** dotaz bez rozpoznatelné entity -> `openTopic` a jede standardní plán. Nízké skóre
  (< 0,88) -> `probable_entity` s `allowBroadExpansion=true`, což highConfidence nesplní.
- **Jak rozhoduje:** skóruje dotaz proti canonical+symbols+aliases+exact_terms každé entity, ve dvou formách
  (plná + „stripped" o kontextová slova jako cena/kurz/vývoj). Přesná shoda 1.0, stripped 0.96, obsažená
  sekvence 0.90, stripped obsažená 0.88. FX pár (6 písmen, obě půlky měnové kódy) -> `probable_entity`
  conf 0.82; kód-podobný token -> conf 0.78.
- **Confidence / práh:** `resolutionType="exact_entity"` při skóre >= **0.88**. `highConfidenceExact()` =
  `exact_entity` AND confidence >= **0.85** AND `!allowBroadExpansion`. Samostatný signál v orchestrátoru:
  `exactRetrievalSucceeded` = některý z **top 3** finálních výsledků má `exactScore >= 0.82`
  (`SearchV2Service:669`) — řídí trace pole `exact_retrieval_succeeded`/`broad_expansion_used`,
  **není to filtr**.
- Exact skóre dále vstupuje do rerankeru jako `deterministic_evidence.exact_entity.match_score` a do
  `finalRank` nepřímo přes `sourcePreferenceRank` (jen u exact-entity dotazů).

---

## 8. Ontologie / aliasy / geo / synonyma

Vše **data-driven** (JSON v `resources/search_v2/` a `resources/catalog/`), třídy jsou tenké loadery + algoritmus.

- **`SearchV2ConceptOntology`** (`concept_ontology.json`): plochý lexikon — stop-terms (20), currency codes
  (15), required-signal aliasy (cpi/gdp/hdp/hicp/ppi/roa/roe -> EN fráze), 9 `query_expansion_rules`,
  context-only termy. Používá `SearchV2QueryExpander` (expanze) a `SearchV2SemanticValidator` (signály/context).
- **`SearchV2ConceptRegistry`** (`concept_registry.json`): **18 sémantických konceptů** (interest_rate,
  policy_rate, inflation_rate, unemployment_rate, exchange_rate, gdp, bank_profitability, market_index...),
  každý s aliasy CZ/EN, retrieval_terms, catalog_families, entity_types, preferred_sources,
  compatible_concepts. `resolve(query)` matchuje aliasy na hranicích slov (víceslovný alias conf 0,96,
  jednoslovný 0,88; generická 1-token slova blokována). Řídí: `primaryConcepts` v plánu, `routeConcepts`
  (zdroje), filtrování konfliktních variant/termů, a výběr rerank poolu (`candidateMatchesRequiredConcepts`).
- **`SearchV2InstitutionalSectorRegistry`**: 8 institucionálních sektorů (central_bank, banks, households,
  government, insurance, pension_funds...) s CZ/EN aliasy. Explicitní sektor z formulace uživatele je
  v rerankeru **neměnný** (konflikt sektorů -> penalizace kandidáta).
- **Geo resolver — `SearchV2GeoCompatibility`** (statická utilita): normalizace kódů (ISO-3->ISO-2,
  EU/EU27->EU, EA/U2->U2), členské množiny (EU 27, euro-area 20, OECD 38), burzovní přípony->geo.
  `assessCandidateGeo` -> status `compatible` / `explicit_conflict` (**hardConflict**) /
  `source_scope_conflict` (**hardConflict**) / `unknown` / `not_requested`. **hardConflict jen při
  explicitní/fixní geo, nikdy kvůli chybějící evidenci.** `dimension-selectable` zdroje (eurostat, ecb2, bis,
  imf, oecd4, data360, worldbank) mohou geo splnit výběrem dimenze. Produkuje `geo_trace` do odpovědi.
- **Země — `CatalogCountryAliasRegistry`** (196 ISO-2, aliasy CZ+EN + wildcard-stemy `franci*`) a
  **`CatalogCountryIso3Registry`** (197 párů ISO-2<->ISO-3). Volané přímo z `SearchV2GeoCompatibility`
  a `SearchV2ConceptRegistry`.
- **CZ stemmer / synonyma:** `CzTextStemmer` (české pádové koncovky), `CatalogSearchSynonyms` (26 CZ synonym
  + 6 bankovních skupin), `CatalogSearchLexicon` (generic tokens, commodity lexikon). **Klíčový nález:**
  tento velký CZ<->EN synonymní/lexikonový aparát je v Javě **napojen jen na classic pipeline**. V2 ho
  používá jen tranzitivně přes `CatalogGeoIntent` (geo detekce/fold) a `CatalogCommoditySearch` (commodity
  termy). Vlastní expanzi V2 dělá přes `SearchV2QueryExpander` + ontologii + LLM planner.

---

## 9. Performance (odhad z implementace + timing klíče)

Timing klíče v trace (`response.timings`): `planner_ms`, `retrieval_ms`/`fts_ms`, `embedding_ms`,
`vector_search_ms`, `reranker_ms`, `retry_ms`, `preview_verification_ms`, `answer_ms`, `total_pipeline_ms`.

```
Cold beh (bez cache), default full mode, vektor vypnuty:

Exact entity resolve ....   < 1 ms      (in-memory, 22 entit)
Concept resolve .........   < 2 ms      (in-memory, 18 konceptu)
localPlan/validatePlan ..   < 10 ms     (retezcove operace, alias registry)
LLM PLANNER ............. ~1 000-5 000 ms   <- gpt-5.4-nano, budget 12 s, blokuje retrieval
Query expansion .........   1-5 ms
FTS retrieval ...........  ~50-500 ms   (SQLite FTS5; velke zdroje ecb2/fred i sekundy)
  |- embedding (vektor) .  ~desitky-stovky ms (jen kdyz zapnuto; jinak 0)
  |- vector KNN .........  ~jednotky-desitky ms (jen kdyz zapnuto)
merge + RRF fuze ........   < 10 ms
selectRerankPool ........   < 5 ms
LLM RERANKER ............ ~2 000-6 000 ms   <- gpt-5.4-nano, davky po 5 paralelne, wall~nejpomalejsi davka
finalRank (deterministic)  < 1 ms
coverage check ..........   < 1 ms
retry (podminene) ....... +sekundy       (opakuje retrieval + rerank, jen kdyz retryRecommended)
PREVIEW VERIFIKACE ...... ~8 000-24 000 ms  <- ZIVA HTTP, <=8 kand., pool 3, 8 s/davka
  (osirele pomale konektory: eurostat 120 s, ARAD 10 min, BIS/OECD 90 s...)
LLM ANSWER/STORY ........ ~2 000-8 000 ms   <- gpt-5.4-mini, budget 120 s, bez retry, blokuje
---------------------------------------------
total_pipeline_ms .......  typicky ~15-40 s cold; cache-hit (FINAL 10 min) ~0
```

Poznámka: `HttpClient.send` je synchronní; `foldAscii`/`normalizeTokenBoundaries` jsou historicky horké CPU
operace (miliony unicode ops) — volané v expanderu, match-builderu, normalizeru. SQLite čtení serializuje
pool 32 connection (legacy) resp. per-volání connection (sidecar).

---

## 10. Bottlenecks (jen identifikace)

**Co blokuje / co čeká na co (kritická cesta, vše na request-vlákně):**
1. **LLM planner** blokuje start retrievalu (plán je jeho vstup). ~1-5 s, budget 12 s, retry x2.
2. **LLM reranker** — `future.join()` čeká na dokončení **všech** dávek; wall-time ~ nejpomalejší dávka.
   Bez retry; při timeoutu (12/20 s) padá dávka do deterministického fallbacku (status `partial`).
3. **Preview verifikace = hlavní bottleneck.** Jediný krok s N síťovými voláními na externí API. Blokující
   (`.join()` per dávka), pool jen **3 vlákna**, 8 s/dávka. `completeOnTimeout` **nepřeruší** běžící HTTP —
   osiřelý task drží vlákno poolu až do konektorového timeoutu (30 s-10 min) -> **hladovění poolu** =
   latenční riziko č. 1. Worst-case default ~3 x 8 s ~ 24 s, reálně i víc.
4. **LLM answer/story** — synchronní, budget **120 s** bez retry, na konci kritické cesty.

**Co běží paralelně:**
- **Retrieval:** FTS úlohy přes zdroje x varianty na **virtuálních vláknech** (legacy až ~96 souběžně;
  sidecar ~3x#variant); vektorová větev **async** vedle FTS. Ale všechny se sbíhají v `allOf().join()`.
- **Reranker:** dávky po 5 se submitnou najednou (<=12 souběžných LLM volání přes sdílený HTTP/2), pak `join()`.
- **Preview:** <=3 kandidáti souběžně (fixní pool 3).

**Co běží synchronně/sériově:** celý `runSearch` je sekvenční řetěz fází (plánování -> retrieval -> rerank ->
finalRank -> coverage -> retry -> preview -> answer); mezi fázemi jsou bariéry (`join`). SQLite čtení je
fakticky serializované. Tři LLM stupně jsou sériově za sebou (nelze překrýt, každý potřebuje výstup předchozího).

**Utracená práce:** `completeOnTimeout` u retrievalu i preview neruší běžící úlohy -> CPU/I/O práce doběhne
„naprázdno" i po vypršení timeoutu.

---

## 11. ASCII diagram celé pipeline

```
                          POST /api/catalog/search-v2
                                     |
                                     v
                          SearchV2Service.search()
                                     |
                         +-----------+-----------+
                         |  FINAL cache (10 min) |--hit--> Response (cache_hit=true)
                         +-----------+-----------+
                                     | miss
                                     v
        +---------------------- PLANOVANI -----------------------+
        |  ExactEntityResolver --highConf?--> exactPlan (BEZ LLM)|
        |  ConceptRegistry.resolve                               |
        |  localPlan() = deterministicky fallback (vzdy)         |
        |  |- use_ai=false / nekonfig. -----> fallback (BEZ LLM) |
        |  |- jinak: ## LLM PLANNER (gpt-5.4-nano, 12s) ##       |
        |             -> validatePlan (deterministika > LLM)     |
        +-----------------------+--------------------------------+
                                v   SearchQueryPlan
                       SearchV2QueryExpander.expand()  -> <=8 variant
                                |
                                v
        +--------------------- RETRIEVAL (virtualni vlakna) ----------------------+
        |   FTS lane (sidecar SQLite FTS5  |  legacy CatalogIndexStore)           |
        |        per varianta <=25   --+                                          |
        |   VECTOR lane (ONNX e5 +     |  async, 5s   [DEFAULTNE VYPNUTO]         |
        |        Lucene KNN)         --+                                          |
        |        |                                                               |
        |        v  merge+balance <=240  -->  RRF fuze (rrfK=60)  -->  <=240      |
        +-----------------------+------------------------------------------------+
                                v
                    selectRerankPool()  -> <=60 kandidatu
                                |
                                v
        +------------------ SEMANTICKY RERANK / VALIDACE --------------------+
        |   davky po 5, paralelne:  ## LLM RERANKER (gpt-5.4-nano, 12/20s) ##|
        |   |- use_ai=false/selhani -> deterministicky fallback skorer       |
        |   |- enforceProvenStructuredConflicts -> muze prebit na "drop"     |
        +-----------------------+-------------------------------------------+
                                v   List<SemanticDecision>
                 SearchV2FinalReranker.finalRank()  (deterministicky, okno 72)
                                |  filtr keepLike() (zahodi drop)
                                v
                    SearchV2CoverageChecker  -> complete/partial/insufficient
                                |
                     retryRecommended? --ano--> RetryPlanner + novy retrieval+rerank
                                |                (adopce jen kdyz zlepsi coverage)
                                v
        +---------------- PREVIEW VERIFIKACE (pool 3, 8s/davka) -------------+
        |   verifyTopOnly(<=8)  -->  CatalogPreviewService                   |
        |        |                     |- ConnectorFactory.fetch()          |
        |        v                        %% ZIVE HTTP na externi API %%     |
        |   accepted (verified)  vs  candidate           <- BOTTLENECK      |
        +-----------------------+-------------------------------------------+
                                v
                    selectFinalResults()  -> <=12 (max 30)
                                |
                                v
        +----------------------- ODPOVED / STORY ---------------------------+
        |   |- use_ai_story=false -> deterministicky text                   |
        |   |- jinak: ## LLM CHAT (gpt-5.4-mini, 120s, bez retry) ##        |
        |             -> {headline_cz, story_cz, drivers[]}                 |
        +-----------------------+-------------------------------------------+
                                v
              JSON: results / verified / possible / answer / coverage
                    / trace_id / timings   +  ulozeni do FINAL cache
                                |
                                v
                            Response
     (u /deep-search jeste CatalogFollowupService.bootstrapConversation)

   Legenda:  ## = LLM volani (synchronni)   %% = ziva externi HTTP volani
```

---

## 12. Shrnutí klíčových architektonických faktů

- **Tři LLM stupně** na kritické cestě: planner (gpt-5.4-nano) -> semantic validator/reranker (gpt-5.4-nano,
  dávkovaný) -> answer/story (gpt-5.4-mini). Vše synchronní `HttpClient.send`, sériově za sebou. Každý lze
  deterministicky přeskočit (`use_ai`/`use_ai_story=false`, exact entita).
- **Retrieval je primárně FTS/BM25** ze SQLite (sidecar v prod). Vektorová/hybridní větev existuje (ONNX
  e5-small + Lucene HNSW + RRF), ale je **defaultně vypnutá a index se nebuduje automaticky** -> v běžném
  provozu je „hybrid" fakticky jen FTS.
- **Deterministika a LLM se prolínají:** planner validuje LLM výstup deterministickými pravidly (entita/geo/
  zdroje přebíjejí LLM); reranker je LLM autorita, ale `enforceProvenStructuredConflicts` může jeho verdikt
  přepsat na „drop" při prokázaném strukturálním konfliktu z metadat.
- **Preview verifikace je hlavní bottleneck** — jediný krok se živými externími HTTP voláními, pool 3 vlákna,
  riziko hladovění kvůli pomalým konektorům (`completeOnTimeout` neruší běžící HTTP).
- **Cache je in-memory per-instance** (plan 1 h, retrieval 30 min, final 10 min, preview 10 min) — po deployi
  studená, mezi instancemi nesdílená. Vlastní implementace nad `ConcurrentHashMap` (ne Caffeine), single-flight,
  MAX_ENTRIES 2048.
- **Redukce kandidátů:** FTS MATCH -> <=240 (merge/RRF) -> <=60 (rerank pool) -> LLM po 5 -> okno 72
  (finalRank) -> <=8 preview -> <=12 finálních.
- Rozsáhlý CZ<->EN synonymní/lexikonový aparát (ROE/NPL/HDP expanze) je v Javě **napojen jen na classic
  pipeline**, ne na V2 retrieval.

---

## Přehled klíčových souborů

Orchestrace:
- `search/v2/orchestration/SearchV2Service.java` (páteř, 1363 ř., limity, práh 0.82 na ř. 669)
- `search/v2/orchestration/SearchV2FeatureFlags.java` (v1/v2 gate)
- `search/v2/orchestration/SearchV2CacheService.java` (in-memory cache)
- `search/v2/orchestration/SearchV2PreviewVerifier.java` (živá verifikace)
- `controller/catalog/CatalogController.java` (endpointy)

Plánování:
- `search/v2/planner/SearchV2QueryPlanner.java` (1040 ř.)
- `search/v2/planner/SearchV2PlannerStructuredOutput.java` (JSON schéma)
- `search/v2/entity/ExactEntityResolver.java`, `SearchV2ExactEntityScorer.java`, `SearchV2SourceCapabilityRegistry.java`
- `search/v2/schema/SearchQueryPlan.java`, `SearchQueryVariant.java`, `ExactEntityResolution.java`, `SourceRoutingDecision.java`

Retrieval:
- `search/v2/retrieval/SearchV2FtsRetriever.java`, `SearchV2QueryExpander.java`, `SearchCandidateFusion.java`, `SearchV2CandidateMerger.java`
- `search/v2/normalization/SearchV2CandidateNormalizer.java`, `SearchV2Deduplicator.java`
- `search/v2/schema/SearchCandidate.java`
- `search/CatalogIndexStore.java`, `CatalogSqliteReadPool.java`, `CatalogSearchResultCache.java`
- `search/v2/sidecar/SearchCatalogSidecarIndex.java`, `SearchSeriesLifecycleClassifier.java`

Vector:
- `search/v2/vector/EmbeddingProvider.java`, `LocalOnnxEmbeddingProvider.java`, `SearchVectorIndex.java`,
  `SearchVectorIndexBuilder.java`, `VectorDocumentBuilder.java`, `SearchEmbeddingCache.java`,
  `SearchVectorProperties.java`, `SearchVectorRetriever.java`

Reranking:
- `search/v2/reranking/SearchV2BatchReranker.java`, `SearchV2SemanticValidator.java`, `SearchV2FinalReranker.java`
- `search/v2/schema/SemanticDecision.java`, `SearchResult.java`

Ontologie / geo:
- `search/v2/ontology/SearchV2ConceptOntology.java`, `SearchV2ConceptRegistry.java`, `SearchV2InstitutionalSectorRegistry.java`
- `search/v2/geo/SearchV2GeoCompatibility.java`
- `search/CzTextStemmer.java`, `CatalogSearchSynonyms.java`, `CatalogSearchLexicon.java`,
  `CatalogCountryAliasRegistry.java`, `CatalogCountryIso3Registry.java`

Coverage / answer:
- `search/v2/coverage/SearchV2CoverageChecker.java`, `SearchV2RetryPlanner.java`
- `search/CatalogSearchAnswerService.java`, `CatalogPreviewService.java`, `CatalogPreviewOrchestrator.java`
- `search/v2/observability/SearchV2Trace.java`, `SearchV2TraceStore.java`, `SearchV2ShadowStore.java`

LLM:
- `search/openai/OpenAiClient.java`, `OpenAiModelTask.java`, `OpenAiJsonSupport.java`
- `resources/search_v2/planner_prompt.md`, `reranker_prompt.md` (system prompty)

Konfigurace:
- `resources/application.yml` (defaulty), `.env` (lokál), `render.yaml` (prod)
- `resources/search_v2/*.json` (concept_ontology, concept_registry, exact_entity_registry,
  source_capability_registry, institutional_sector_registry, entity_type_schema, gold_queries)
