# Search V2 — Preview Cancellation Investigation (PR-2)

Status: **investigativní, žádná produkční změna**. Cíl: potvrdit nebo vyvrátit hypotézu, že
`SearchV2PreviewVerifier`'s `completeOnTimeout(...)` neruší podkladovou práci, a navrhnout nejmenší
opravu pro budoucí PR-6. Nic v této dokumentaci není implementováno — jen ověřeno testem a čtením
skutečného kódu.

Datum: 2026-07-24. Reprodukční test: `src/test/java/cz/bankintel/search/v2/orchestration/SearchV2PreviewCancellationReproTest.java`.

---

## 1. Skutečný call chain (ověřeno čtením zdrojového kódu, ne z názvů metod)

```
SearchV2PreviewVerifier.verifyTopOnly(results, maxResults, geo)
  → verify(results, maxResults, geo, scanForReplacements=false)     [private]
      → CompletableFuture.supplyAsync(() -> verifyOne(candidate, geo), executor)
            .completeOnTimeout(timeoutStatus(...), previewTimeoutMs, MILLISECONDS)
        → verifyOne(candidate, geo)
            → cacheService.getOrCompute(key, CACHE_TTL=10min, () -> doVerify(candidate, geo))
                → doVerify(candidate, geo)
                    → previewService.preview(payload)                    [CatalogPreviewService]
                        → CatalogPreviewOrchestrator.preview(payload)
                            → connectorFactory.get(sourceType)             [ConnectorFactory]
                            → connector.fetch(source)                     [BaseConnector impl]
                                → ConnectorHttpSupport.get(...)/postJson(...)
                                    → httpClient.send(request, bodyHandler)   ← SKUTEČNÉ SÍŤOVÉ VOLÁNÍ
```

Soubory: `search/v2/orchestration/SearchV2PreviewVerifier.java`, `search/CatalogPreviewService.java`,
`search/CatalogPreviewOrchestrator.java`, `connector/ConnectorFactory.java`, `connector/BaseConnector.java`
(rozhraní), konkrétní `connector/*Connector.java`, `connector/ConnectorHttpSupport.java`.

---

## 2. Sync/async mapa

| Vrstva | Sync/Async | Executor/Pool | Poznámka |
|---|---|---|---|
| `SearchV2PreviewVerifier.verify(...)` | Submituje async (`supplyAsync`), ale **`futures.get(i).join()` blokuje volající vlákno** | `Executors.newFixedThreadPool(previewParallelism)` — vlastní pole na `@Service` singletonu | Jeden `executor` sdílený **všemi** souběžnými preview požadavky napříč celou aplikací |
| `CompletableFuture.completeOnTimeout(...)` | Interní JDK `Delayer` (samostatný `ScheduledThreadPoolExecutor(1)`, JVM-wide singleton) | Nezávislé na `SearchV2PreviewVerifier`'s poolu | Časovač vždy odpálí nezávisle na obsazenosti poolu |
| `CatalogPreviewService`/`CatalogPreviewOrchestrator` | **Zcela synchronní** | Běží na tom vlákně, které dostalo úlohu z `executor` výše | Žádný vlastní async/executor |
| `connector.fetch(...)` (všechny konektory) | **Synchronní** | — | Žádný per-connector executor |
| `ConnectorHttpSupport.get/postJson` | **Synchronní, blokující** (`httpClient.send(...)`, ne `sendAsync`) | Jeden sdílený `HttpClient` field (`@Component`, konstruován jednou) | **Toto je klíčové zjištění — viz bod 10** |

---

## 3. Současné timeouty (ověřeno přímo ve zdrojovém kódu)

| Konektor | Timeout | Soubor:řádek |
|---|---|---|
| ARAD | **10 minut** | `AradConnector.java:36` |
| Eurostat | 120 s | `EurostatConnector.java:36` |
| OECD | 90 s | `OecdConnector.java:50` |
| BIS | 90 s | `BisConnector.java:46` |
| IMF | 60 s | `ImfConnector.java:52` |
| Data360 | 60 s | `Data360Connector.java:86` |
| ECB | 35 s | `EcbConnector.java:76` |
| FRED | 30 s | `FredConnector.java:85` |
| ČSÚ | 30 s / 3 min / 5 min (různá volání) | `CsuConnector.java:66,100,139,157` |
| `ConnectorHttpSupport` connect timeout | 20 s (`HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))`) | `ConnectorHttpSupport.java:34` |
| `ConnectorHttpSupport` GET/POST default (když volající nezadá) | GET 60 s, POST 120 s | `ConnectorHttpSupport.java:41,53` |
| `SearchV2PreviewVerifier` (verifier-level, aplikuje se před tím, než konektor vůbec stihne doběhnout) | `SEARCH_PREVIEW_TIMEOUT_MS`, default **8000 ms**, clamp 500–30000 | `SearchV2PreviewVerifier.java` (`configuredPreviewTimeoutMs()`) |

**Nesoulad je enormní**: verifier čeká defaultně 8 s, zatímco ARAD smí legitimně potřebovat až 10 minut.

---

## 4. Executory a jejich velikosti

- `SearchV2PreviewVerifier.executor = Executors.newFixedThreadPool(previewParallelism)`, `previewParallelism` = `SEARCH_PREVIEW_CONCURRENCY`, default **3**, clamp 1–8. **Jediná instance na celou aplikaci** (Spring `@Service` singleton) — sdílená napříč všemi souběžnými uživatelskými dotazy.
- `ConnectorHttpSupport.httpClient` — jeden sdílený `java.net.http.HttpClient`, žádný vlastní thread pool nad rámec toho, co `HttpClient` interně používá pro HTTP/1.1 vs HTTP/2 (výchozí, nekonfigurováno explicitně na verzi protokolu).
- Žádný per-connector nebo per-source executor — všechny konektory sdílí stejnou synchronní cestu přes stejné volající vlákno (to, které dostane úlohu z `SearchV2PreviewVerifier`'s poolu).

---

## 5. Reprodukce problému — co test skutečně prokázal

Dva testy v `SearchV2PreviewCancellationReproTest`:

### Test 1 — `timeoutCompletesCallerFutureButUnderlyingWorkKeepsRunningUninterrupted`
Kandidát, jehož podkladová práce **už běží** (zablokovaná na řízeném `CountDownLatch`, simuluje pomalé síťové volání), když `previewTimeoutMs` (500 ms, technické minimum daného clampu) vyprší:
- Volající (`verifyTopOnly`) dostane **`preview_state=timeout`** odpověď po ~500 ms — **potvrzeno**.
- Podkladová práce (mock `previewService.preview(...)`) **pokračuje běžet** dál, dokud ji test explicitně neuvolní — **potvrzeno**. Vlákno v poolu zůstává obsazené po celou (v produkci potenciálně až 10minutovou) dobu.
- Podkladová práce **není nikdy interruptnuta** (`workWasInterrupted` zůstává `false`) — **potvrzeno**.

→ **Původní hypotéza (a)/(b) potvrzena přesně tak, jak byla formulována.**

### Test 2 — `singleWorkerPoolStarvesConcurrentFastRequestBehindSlowOne`
Pool o velikosti 1 (`SEARCH_PREVIEW_CONCURRENCY=1`), jeden pomalý a jeden rychlý kandidát souběžně:
- Oba volající dostanou `timeout` odpověď rychle (~500 ms) — **potvrzeno**, nezávisí na obsazenosti poolu (`completeOnTimeout` časovač běží na nezávislém JDK `Delayer`, ne na `SearchV2PreviewVerifier`'s poolu).
- V okamžiku, kdy pomalý kandidát drží jediné vlákno poolu, rychlý kandidát **nikdy nezačne** svou skutečnou práci (`fastStarted=false`) — **potvrzeno, to je čisté „pool starvation"**.
- **Zde byla původní hypotéza (c) upřesněna testem, ne jen potvrzena beze změny**: po uvolnění pomalého kandidáta (a uvolnění jediného vlákna poolu) se očekávalo, že rychlý kandidát **konečně poběží** (opožděně, ale poběží). **Test to vyvrátil**: rychlý kandidát **nikdy neproběhne, ani po uvolnění poolu** (`fastStarted` zůstává `false` i po dalších 1000 ms čekání s volným vláknem k dispozici).

**Přesná příčina (JDK-úrovňová, ne chyba tohoto kódu)**: `CompletableFuture.supplyAsync(supplier, executor)` odevzdá do executoru interní úlohu typu `AsyncSupply`, jejíž `run()` metoda začíná kontrolou `if (d.result == null)` — teprve pak zavolá `supplier.get()`. Jakmile `completeOnTimeout(...)` DŘÍVE (po `previewTimeoutMs`) závodem dokončí `CompletableFuture` časovací hodnotou — a to se stane **zatímco je úloha ještě jen ve frontě, nikdy nezačala běžet** — je `d.result` už nastaveno. Když se pak konečně na tuto úlohu dostane volné vlákno poolu, `run()` uvidí `d.result != null` a **`supplier.get()` vůbec nezavolá**. Skutečné síťové volání (v produkci: HTTP request na konektor) se **tiše, navždy přeskočí** — ne opozdí.

**Rozlišení dvou různých selhání podle přesného načasování:**
- Kandidát, jehož práce **už začala běžet** předtím, než timeout vypršel → práce pokračuje neomezeně dál, neinterruptnutá (test 1).
- Kandidát, jehož práce **ještě čekala ve frontě** ve chvíli, kdy timeout vypršel → práce se **nikdy nespustí**, ani když se pool později uvolní (test 2).

Které z těchto dvou chování kandidát potká, závisí čistě na náhodném načasování obsazenosti poolu — ne na ničem, co volající kontroluje.

---

## 6. Potvrzení/vyvrácení pool starvation

**Potvrzeno, a je to horší, než hypotéza předpokládala.** Není to jen „rychlý požadavek čeká déle" — je to „rychlý požadavek se za určitých (běžných, ne okrajových) podmínek vůbec neprovede".

---

## 7. Stačil by `future.cancel(true)`?

**Ne, nestačil by**, a to ze dvou nezávislých důvodů:

1. **`CompletableFuture.cancel(...)` nepropaguje interrupt na podkladové vlákno.** I kdyby `SearchV2PreviewVerifier` explicitně zavolal `.cancel(true)` na future vracenou z `supplyAsync(...)`, tato implementace `cancel()` jen dokončí *future* (volajícímu) výjimečně — nezpůsobí `Thread.interrupt()` na vlákně, které aktuálně běží podkladový `supplier.get()`. Toto je známé chování `CompletableFuture` (na rozdíl od `ExecutorService.submit(...)`-vráceného `Future`, kde `cancel(true)` interrupt skutečně posílá).
2. **Podkladové HTTP volání je synchronní, blokující `httpClient.send(...)`** (`ConnectorHttpSupport.java:43,58`), ne `sendAsync(...)`. I kdyby se interrupt nějak dostal na vlákno, `HttpClient.send(...)` na interrupt nutně nereaguje okamžitým přerušením síťového I/O stejným způsobem, jako by to udělala zrušitelná `sendAsync` budoucnost.

**Závěr**: skutečné zrušení vyžaduje strukturální změnu — přechod na `HttpClient.sendAsync(...)` v `ConnectorHttpSupport`, jejíž vrácenou `CompletableFuture` LZE smysluplně zrušit (JDK implementace `sendAsync` na `cancel()` skutečně ukončí podkladovou HTTP výměnu).

---

## 8. Návrh nejmenší opravy pro budoucí PR-6 (návrh, NEIMPLEMENTOVÁNO)

1. `ConnectorHttpSupport.get(...)`/`postJson(...)` — přidat async variantu (`getAsync`/`postJsonAsync`) volající `httpClient.sendAsync(request, bodyHandler)`, vracející `CompletableFuture<HttpResponse<String>>`.
2. `BaseConnector.fetch(...)` — buď přidat async variantu rozhraní (`CompletableFuture<ConnectorFetchResult> fetchAsync(...)`), nebo (menší zásah) nechat `fetch(...)` synchronní pro ostatní volající místa a zavést async cestu **jen** pro preview-verification tok.
3. `CatalogPreviewOrchestrator`/`CatalogPreviewService` — přidat async variantu `preview(...)`, která interně použije async fetch a vrátí `CompletableFuture<Map<String,Object>>` místo volání blokujícího `fetch(...)`.
4. `SearchV2PreviewVerifier.verifyOne(...)`/`doVerify(...)` — přestat obalovat blokující volání do `supplyAsync(blocking, executor)`; místo toho zřetězit přímo na `CompletableFuture` vrácenou z kroku 3, a na timeout zavolat `.cancel(true)` PŘÍMO na TUTO future (ne na wrapper) — teprve to skutečně zruší běžící HTTP výměnu.
5. Ponechat `completeOnTimeout(...)` jako fallback hodnotu pro volajícího, ale doplnit explicitní `.whenComplete((v, ex) -> { if (timedOut) realFuture.cancel(true); })` nebo ekvivalent, aby zrušení skutečně proběhlo.

---

## 9. Seznam konektorů, které by taková oprava ovlivnila

Všechny — protože všechny jdou přes stejnou sdílenou `ConnectorHttpSupport`: **ARAD, ČSÚ, Eurostat, ECB, FRED, IMF, OECD, BIS, Data360, WorldbankPinkSheet, TradingEconomics**. `FileUploadConnector` pravděpodobně ne (žádné síťové volání, je-li to lokální upload).

---

## 10. Rizika přechodu na `sendAsync` (dnes `send`)

- **Sdílený `HttpClient`**: `sendAsync` je i tak thread-safe (stejná instance se dnes už sdílí mezi všemi konektory pro `send`), riziko nízké.
- **Propagace chyb**: v async řetězci se výjimky projeví jako `CompletionException` obalující původní příčinu — volající kód (`catch IOException`/`InterruptedException` v konektorech) by musel být upraven na zpracování `CompletionException`/`.exceptionally(...)`, jinak stávající chybové větve (`ConnectorFetchResult` s `httpStatus`/`raw` chybovou mapou) přestanou fungovat stejně.
- **Testy mockující synchronní `fetch(...)`** (existují per-connector testy, např. `EcbConnectorTest`, `FredConnectorTest`, `CsuConnectorTest`) by potřebovaly úpravu, pokud by se měnila signatura `BaseConnector.fetch(...)` na async — proto doporučení výše zachovat `fetch(...)` synchronní a přidat **paralelní async cestu jen pro preview** (menší blast radius).
- **Timeout sémantika**: `HttpRequest.Builder.timeout(...)` funguje shodně pro `send` i `sendAsync` (request-level timeout), takže per-connector timeouty (bod 3) zůstanou beze změny.
- **`HttpTimeoutException` vs `CompletionException(HttpTimeoutException)`**: volající kód musí rozbalit příčinu, jinak `instanceof HttpTimeoutException` kontroly (pokud existují) přestanou matchovat.

---

## 11. Doporučené testy pro PR-6

1. Regresní varianta `SearchV2PreviewCancellationReproTest` s reálným (ne mockovaným) pomalým HTTP endpointem (např. lokální test server s uměle zpožděnou odpovědí) — potvrdit, že `cancel(true)` na `sendAsync`-based future skutečně ukončí TCP spojení (ověřitelné např. přes server-side detekci uzavřeného spojení).
2. Test, že task, jehož `previewTimeoutMs` vyprší **zatímco ještě čeká ve frontě** (scénář z testu 2 výše), po opravě **buď skutečně proběhne** (pokud se fronta také zruší) **nebo je explicitně a viditelně reportován jako zrušený** — ne tiše přeskočen bez stopy, jak je tomu dnes.
3. Test, že zrušení jednoho pomalého kandidáta neovlivní paralelně běžící rychlé kandidáty na jiných vláknech poolu (regrese proti bodu 8. plánu PR-2.2/bulkhead).
4. Test propagace chyb (`CompletionException` unwrap) pro alespoň jeden konektor po přechodu na `sendAsync`.
5. Zátěžový/chaos test: simulovaný výpadek jednoho zdroje (permanentní timeout) nesmí vyhladovět preview verifikaci pro ostatní zdroje v běžícím produkčním provozu (ověření explicitně navrhovaného bulkheadu z PR-8).

---

## 12. Skutečný stav po PR-6 až PR-7c (implementováno, aktualizace 2026-07-28)

Sekce 1–11 výše popisují **problém a jeho analýzu před opravou** (PR-2). Tato sekce popisuje
**skutečně implementovaný, aktuální mechanismus** v `PreviewRequestArbiter`/`SearchV2PreviewVerifier`.
Kdekoli se tyto dvě sekce rozcházejí, je rozhodující sekce 12.

### 12.1 Deterministický lifecycle (nahrazuje dřívější timing-margin heuristiku)

Dřívější verze (PR-7b) rozlišovala `BULKHEAD_REJECTED` od `TIMEOUT` pomocí bezpečnostního marginu
(bulkhead dostal o něco méně času na čekání než plný timeout, aby jeho vlastní rozhodnutí "stihlo"
vyhrát závod proti nezávisle naplánovanému generickému timeoutu). **Toto už neplatí.** Žádný margin,
žádné porovnávání dvou hodin nikde v `PreviewRequestArbiter` neexistuje.

Místo toho má každý pokus explicitní, atomicky sledovaný stav (`PreviewRequestArbiter.State`, navenek
zjednodušeno na čtyři fáze `Phase`):

```
CREATED → WAITING_FOR_CAPACITY → DISPATCHED → TERMINAL
```

- **CREATED** — arbiter právě vytvořen, nic se ještě nestalo.
- **WAITING_FOR_CAPACITY** — pouze když je `SEARCH_PREVIEW_BULKHEAD_ENABLED=true`: čeká se na bulkhead
  permit (`beginAdmission()`). Když je bulkhead vypnutý, tato fáze se **přeskakuje úplně**
  (`admittedWithoutPermit()` jde rovnou CREATED → DISPATCHED) — nový lifecycle tak není vůbec aktivní,
  pokud bulkhead není zapnutý (žádný nový feature flag nebyl zaveden, viz 12.6).
- **DISPATCHED** — permit získán (nebo bulkhead vypnutý) — od tohoto okamžiku běží execution timer a
  skutečná práce (HTTP volání nebo fallback) se smí spustit.
- **TERMINAL** — jeden z pěti výsledků (`SUCCESS`/`FAILURE`/`TIMEOUT`/`CANCELLED`/`BULKHEAD_REJECTED`)
  už byl rozhodnut; nic dalšího už nemůže vyhrát.

Implementační detail, který dřívější (dvou-atomikovou) verzi dělal nedeterministickou: "která fáze
právě platí" a "co bylo rozhodnuto" musí být **jedna a tatáž atomická proměnná**
(`AtomicReference<State>`), ne dvě aktualizované jedna po druhé — jinak vzniká okno, kde jiné vlákno
vidí fázi už jako TERMINAL, ale výsledek (`decided`) ještě není zapsaný. Toto byl skutečný, testem
odhalený bug během vývoje této opravy (flaky `raceBetweenSuccessAndExecutionTimeoutAlwaysProducesExactlyOneWinnerNeverBoth`,
opraveno sloučením do jednoho `state` pole).

### 12.2 Admission timeout vs. execution timeout

Dva nezávisle plánované časovače, každý svázaný přesně s jednou fází:

| | Admission timer | Execution timer |
|---|---|---|
| Kdy se plánuje | Ihned při vytvoření arbitera, jen pokud je bulkhead zapnutý | Až v okamžiku, kdy fáze skutečně přejde na DISPATCHED (permit získán, nebo bulkhead vypnutý) |
| Co smí rozhodnout | `BULKHEAD_REJECTED` — pouze pokud fáze je ještě CREATED/WAITING_FOR_CAPACITY | `TIMEOUT` — pouze pokud fáze je ještě DISPATCHED |
| Hodnota rozpočtu | Stejná `timeoutMs` (PR-9 tiered/resolved hodnota) — **žádná nová konfigurovatelná hodnota nebyla zavedena**; toto je "nejmenší bezpečný mechanismus založený na existující konfiguraci" | Stejná `timeoutMs`, ale počítaná od nuly od okamžiku DISPATCHED, ne od vytvoření požadavku |
| Co se stane, když prohraje | Tichý no-op (CAS selže, protože fáze už je DISPATCHED/TERMINAL) | Tichý no-op (CAS selže) + `lateCompletionIgnored` telemetrie, pokud už bylo rozhodnuto jinak |

Klíčový důsledek: pomalé čekání na permit (admission) už **nikdy** neukrajuje z časového rozpočtu na
samotné vykonání (execution) — a naopak. To je přesně to, co dřívější jediný generický timeout (běžící
od vytvoření požadavku bez ohledu na to, zda se ještě čeká na permit) nezaručoval.

Vedlejší, vědomě přijatý důsledek: nejhorší případ celkové latence jednoho pokusu při zapnutém
bulkheadu se zvyšuje z `timeoutMs` na až `2 × timeoutMs` (plný admission rozpočet, pak plný execution
rozpočet, pokud permit dorazí těsně na hraně). Toto je záměrná cena za odstranění marginu — determinismus
namísto těsnějšího, ale nespolehlivého horního odhadu.

### 12.3 Outcome → bucket mapování (aktuální, PR-7c)

| Outcome | Bucket | preview_state (wire) |
|---|---|---|
| `VERIFIED` | verified | (řízeno polem `ok=true`) |
| `POSSIBLE` | **unverified** (PR-7c — dříve mimo obě pole) | blank/neznámý stav, ok=false, rows≠0 |
| `TIMEOUT` | unverified | `timeout` |
| `CANCELLED` | unverified | `cancelled` |
| `TRANSPORT_FAILURE` | unverified | `error`, `sync_failed` (viz 12.8 — reálná produkční cesta pro HTTP 429/5xx/connection) |
| `UNSUPPORTED` | unverified | `unsupported` |
| `INTERNAL_FAILURE` | unverified | `internal_error` |
| `CIRCUIT_OPEN` | unverified | `circuit_open` |
| `BULKHEAD_REJECTED` | unverified | `bulkhead_rejected` |
| `EMPTY` | rejected | ok=false, rows=0, žádný technický stav |
| `STRUCTURALLY_INVALID` | rejected | `structurally_invalid` (přijímá i starý alias `invalid` na vstupu, viz 12.5) |
| `NOT_CHECKED` | žádné (viz 12.4) | (status map je `null`/prázdná) |

### 12.4 POSSIBLE a NOT_CHECKED

`POSSIBLE` (nejednoznačný/neznámý technický stav, ale preview *proběhlo*) je od PR-7c součástí
`unverified` bucketu — je to "relevantní, ale nepotvrzené", stejně jako timeout nebo selhání spojení, a
nesmí tiše zmizet.

`NOT_CHECKED` **zůstává mimo obě pole** — po prošetření skutečné sémantiky se ukázalo, že je to
legitimní stav ve dvou situacích: (a) `metadata_only` režim (preview se záměrně vůbec nespouští), (b)
kandidát je za hranicí malého scan-okna, které `SearchV2PreviewVerifier.verify(...)` skutečně
kontroluje (typicky `min(limit, 8)` nejvýše hodnocených kandidátů) — nikdy nebyl poslán k ověření,
protože k tomu záměrně nedošlo, ne kvůli chybě. `SearchV2Service.warnIfCheckedCandidateIsUnclassifiable(...)`
navíc hlídá invariantu: pokud by se `NOT_CHECKED` objevil u kandidáta, který **byl** uvnitř skutečně
kontrolovaného okna (index < `verified.checkedCount()`), jde o anomálii — emituje se asynchronní
telemetrická událost (`preview_not_checked_invariant_violation`), ale kandidát se **nezařazuje**
automaticky do `unverified` bez důkazu o správné sémantice.

### 12.5 Přejmenování INVALID → STRUCTURALLY_INVALID

Starý název `INVALID` (PR-7b) čtenáře sváděl k dojmu sémantické/byznysové chyby ("tento dotaz je
neplatný"). Ve skutečnosti jde o strukturálně neúplného kandidáta (chybí `source` nebo `series_id`) —
detekováno **před** jakýmkoli pokusem o preview, nikdy nejde o transportní ani sémantickou chybu, a
nikdy neovlivňuje circuit breaker ani bulkhead. Kanonický název od PR-7c: `STRUCTURALLY_INVALID`
(wire hodnota `"structurally_invalid"`). `SearchV2PreviewOutcome.classify(...)` přijímá starý
řetězec `"invalid"` jako vstupní alias (pro případná už uložená telemetrická data), ale nikde v
kódu se už neprodukuje — všechny nové zápisy používají kanonický název.

### 12.6 Feature flagy a jejich vztah k lifecycle

- `SEARCH_PREVIEW_BULKHEAD_ENABLED` — řídí, zda je fáze `WAITING_FOR_CAPACITY` a admission timer vůbec
  aktivní. **Žádný nový flag nebyl zaveden** pro deterministický lifecycle — je to correctness fix
  existující bulkhead funkce, ne nová funkce sama o sobě. Při vypnutém bulkheadu je chování bajtově
  identické stavu před PR-8 (jeden timer od vytvoření požadavku).
- `SEARCH_V2_PREVIEW_UNVERIFIED_RESULTS_ENABLED` — řídí zařazení `POSSIBLE`/ostatních unverified
  outcomes do pole `unverified` v odpovědi a přepnutí story na `unverified_notice` mód. Při vypnutí je
  odpověď bajtově identická stavu před PR-7b/7c.

### 12.7 Bezpečné chování story pro `unverified_only`

Když neexistuje žádný verifikovaný výsledek, ale existuje aspoň jeden unverified kandidát (bucket
podle 12.3), `SearchV2Service` **nikdy nevolá** `CatalogSearchAnswerService.composeStory(...)`
(a tedy ani LLM) — místo toho volá `composeUnverifiedNotice(...)`, čistě deterministickou šablonu,
která smí sdělit pouze: že byly nalezeny potenciálně relevantní řady, že se je nepodařilo ověřit,
bezpečně agregovanou kategorii důvodu (timeout / dočasná nedostupnost / technická chyba / přerušení —
nikdy syrový `preview_reason` řetězec), a že výsledek není potvrzený. Toto platí i při
`use_ai_story=true` — "může změnit formulaci, ale nesmí interpretovat" je zajištěno tím, že šablona
sama vůbec nikdy nevolá LLM v tomto režimu, ne tím, že by LLM dostal přísnější instrukce.

### 12.8 Oprava: `sync_failed` byl chybně klasifikován jako EMPTY (fix po validační fázi)

**Nález z validační fáze (Fáze 7):** `CatalogPreviewOrchestrator.preview(...)` a
`previewAsyncIfSupported(...)` směrují **každou** HTTP-úrovňovou chybu konektoru (429, 5xx,
connection error — cokoli, kde `ConnectorFetchResult.isSuccess()` je false) přes
`PreviewResponseBuilder.buildError(...)`, který nastaví `preview_state = "sync_failed"` a
`rows = []` — nikoli `preview_state = "error"`, jak předpokládaly všechny dřívější testy simulující
selhání konektoru ručně sestaveným statusem. Toto je **skutečná produkční cesta** pro každé reálné
HTTP selhání, ne okrajový případ.

Před opravou `SearchV2PreviewOutcome.classify(...)` řetězec `"sync_failed"` nerozpoznávalo, spadlo do
výchozí větve, uvidělo `rows == 0` a klasifikovalo kandidáta jako `EMPTY` (rejected bucket — "potvrzeno,
tato řada neexistuje") místo `TRANSPORT_FAILURE` (unverified bucket — "relevantní, ale nepotvrzené").
Zároveň `SearchV2PreviewVerifier.recordBreakerOutcome(...)` počítalo jako selhání konektoru jen
`"timeout"`/`"error"`, takže `sync_failed` se nikdy nezapočítal do circuit breakeru — breaker se nikdy
neotevřel na reálná HTTP 429/5xx selhání přicházející touto cestou.

**Oprava (minimální, bez architektonického refactoringu):**

1. `SearchV2PreviewOutcome.classify(...)` — přidán `case "sync_failed"` do stejné větve jako `"error"`,
   obojí mapuje na `TRANSPORT_FAILURE`. `sync_failed` už nikdy nedopadne do výchozí `EMPTY`/`POSSIBLE`
   větve.
2. Nový sdílený bucket-helper `SearchV2PreviewOutcome.isBreakerFailure(String outcome)` — vrací true
   pouze pro `TIMEOUT` a `TRANSPORT_FAILURE` (stejný vzor jako existující `isUnverifiedBucket`/
   `isRejectedBucket`).
3. `SearchV2PreviewVerifier.recordBreakerOutcome(...)` byl centralizován na kanonický
   `SearchV2PreviewOutcome.classify(status)` + `isBreakerFailure(...)` místo porovnávání syrových
   řetězců `preview_state`. Politika breakeru se nezměnila pro žádný jiný outcome — `CIRCUIT_OPEN`,
   `BULKHEAD_REJECTED`, `INTERNAL_FAILURE`, `CANCELLED`, `UNSUPPORTED`, `STRUCTURALLY_INVALID`,
   `EMPTY`, `POSSIBLE`, `NOT_CHECKED` zůstávají neutrální (jako dosud), `VERIFIED` zůstává success.

**HTTP 429 vs. HTTP 5xx:** obojí dnes produkuje bajtově identický tvar `preview_state = "sync_failed"`
(HTTP status kód se ukládá do `http_status`, ale nikdy neovlivňuje `preview_state`). Tato oprava tedy
klasifikuje 429 i 5xx stejně (`TRANSPORT_FAILURE`) a obojí se počítá do circuit breakeru stejně — bez
rozlišení. Diferencovaná rate-limit politika pro 429 (např. jiný threshold, `Retry-After`, jiný
cooldown) je možné budoucí follow-up, **není součástí této opravy** — threshold, cooldown a retry
logika zůstávají nezměněné.

**Testy:** `SyncFailedOutcomeMisclassificationReproTest` (přepsán z reprodukce bugu na regresní test
opraveného chování), nové klasifikační testy v `SearchV2PreviewOutcomeTest`, nové scenáře unverified
odpovědi v `SearchV2ServiceRuntimeTest`, a nové breaker integrační testy v
`SearchV2PreviewCircuitBreakerIntegrationTest` (včetně přímého testu HTTP 429 přes reálnou
`PreviewResponseBuilder.buildError(...)` cestu).

**Známé omezení:** `CANCELLED` (pre-transport zrušení přes `PreviewRequestArbiter.cancelIfPending`) je
dnes prokázáno jako breaker-neutrální pouze na úrovni čisté klasifikace
(`isBreakerFailure(CANCELLED) == false`) — `cancelIfPending` nemá v současné verzi `SearchV2PreviewVerifier`
žádnou produkční cestu, která by ho reálně vyvolala (je to připravená, ale nezapojená schopnost pro
budoucí zrušení požadavku klientem). Toto není touto opravou zaváděno ani měněno.
