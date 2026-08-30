# Manager Explorer — nezávislý technický audit v2 (2026-08-03)

Tento audit **nepřebírá žádné závěry z předchozího auditu** (`MANAGER_EXPLORER_AUDIT.md`). Každé
tvrzení níže je ověřeno znovu, buď čtením aktuálního kódu, nebo živým měřením proti běžícímu
backendu (`localhost:8081`) a frontendu (`localhost:5173`), včetně jednoho reálného průchodu
prohlížečem. Tam, kde je něco hypotéza a ne prokázaný fakt, je to výslovně označené jako
**HYPOTÉZA**. Přechozí audit se ukázal jako **neúplný** — minul největší strukturální problém
(dvojí běh discovery + SSE timeout, viz níže), protože měřil jen přímé endpointy, ne skutečnou
cestu, kterou jde frontend.

---

## 1. Kritické chyby

### 1.1 — Celá analýza se počítá DVAKRÁT a spojení může spadnout dřív, než dorazí výsledek

**Závažnost: Blocker.**

Skutečný tok, kterým frontend spouští analýzu (ověřeno síťovým záznamem v prohlížeči, ne
odhadem): `GET /explore/query-understanding` → `GET /explore/sector/preset-preview` →
**`GET /explore/sector/stream`** (SSE). Frontend **nikdy nevolá** čistý `POST /explore/sector`
jako první krok — jde vždy přes stream. To je zásadní metodická odchylka od předchozího auditu,
který měřil hlavně `POST /explore/sector` a `/catalog/deep-search` přímo.

Zdrojový kód `ExploreStreamService.java:59-118`:
```java
exploreDiscoveryService.discoverWithLanes(query, sector, false, (source, lane) -> {
    // ... posílá SSE source_started/source_finished/indicators_update ...
});
// ... o kus dál, ve stejné metodě ...
Map<String, Object> finalBody = sectorService.analyzeSector(request);
sendEvent(emitter, "search_finished", finalBody);
```

`discoverWithLanes()` má návratový typ **`void`** (`ExploreDiscoveryService.java:59`) — jeho
jediný účel je zavolat callback pro SSE progress, výsledek se nikam neukládá a je zahozen.
`sectorService.analyzeSector(request)` (řádek 115) interně volá **`exploreDiscoveryService.discover()`**
(`ExploreSectorService.java:83`) — druhou, zcela nezávislou metodu s vlastním návratovým typem
(`IndicatorBundle`), která přepočítá **celé discovery znovu od nuly** (vlastní LLM plánování,
vlastní FTS retrieval, vlastní rerank, vlastní preview-verifikaci).

**Živý důkaz (ne odhad):** vlastní timing log backendu (`CatalogDeepSearchService`) pro jeden
stream request se stejným dotazem ukazuje **dvě samostatné položky** za sebou:
```
09:43:26.684  ... total_ms=79833   (první běh — discoverWithLanes, zahozen)
09:44:43.943  ... total_ms=77259   (druhý běh — discover() uvnitř analyzeSector, skutečný výsledek)
```
Start mého SSE požadavku byl 09:42:06. Součet: ~157 sekund backendové práce pro JEDEN dotaz
uživatele — dvojnásobek toho, co by stačilo.

**K tomu druhý, nezávisle ověřený problém:** SSE emitter má natvrdo:
```java
private static final long SSE_TIMEOUT_MS = 120_000L;   // ExploreStreamService.java:19
```
120 sekund je **méně** než součet obou běhů (157 s v mém testu). Moje vlastní SSE spojení
(`curl -N`) bylo skutečně přerušeno přesně po 120,0 s, bez `search_finished` události — potvrzeno
dvakrát (na dvou různých dotazech, jednou 120,2 s, jednou přesně 120,0 s).

**Co se stane s uživatelem, quando spojení spadne — ověřeno čtením + živým testem:**
1. `es.onerror` v `exploreSectorStream.js:219` zavolá `fetchExploreSectorStreamFallback()`, která
   pošle `fetch(stejná URL, {headers:{Accept:"application/json"}})`.
2. **Živě ověřeno:** tenhle fallback request na `/explore/sector/stream` s `Accept: application/json`
   vrací **HTTP 406 Not Acceptable** (endpoint má natvrdo `produces = TEXT_EVENT_STREAM_VALUE`).
   Fallback tedy **vždy selže** — je to fakticky mrtvý kód.
3. `ExplorePage.jsx:4451`: když `streamResult.streamError` je true (což po kroku 2 vždy je),
   frontend spustí **třetí, nezávislý** pokus: `POST /explore/sector` — což znovu zavolá
   `discover()` potřetí, tentokrát bez timeoutu (`EXPLORE_LONG_REQUEST_TIMEOUT_MS = 0` =
   axios čeká nekonečně dlouho, `lib/api.js:58-59`).

**Čistý dopad:** appka se z tohohle nakonec obvykle vzpamatuje (třetí pokus nemá timeout), ale
uživatel u dotazů, kde součet dvou běhů překročí 120 s, čeká **~120 s zahozené práce + další
70-170 s třetího běhu = klidně 3-5 minut** na výsledek, který by při jediném čistém běhu trval
kolem 70-90 s. Tohle přesně souhlasí s tím, co uváděl předchozí audit (32-168 s na
`/explore/sector` samotný) — jenže to byl jen JEDEN ze tří běhů, které reálný uživatel ve
skutečnosti absolvuje.

**Očekávaný přínos opravy:** odstranění `discoverWithLanes()` (nebo jeho nahrazení odlehčenou
verzí, co jen vrací placeholder progress bez plné FTS/LLM pipeline) by **snížilo reálný čas
uživatele o 40-50 %** a odstranilo riziko SSE-timeoutové kaskády úplně.
**Náročnost opravy:** střední — `discoverWithLanes` a `discover()` sdílí většinu pipeline;
potřeba buď (a) předělat `discoverWithLanes` na odlehčený per-zdroj "ping" bez plného FTS/LLM,
nebo (b) spustit JEN `discover()` a odvodit progress eventy z jeho průběhu (vyžaduje probublání
callbacku do `CatalogDeepSearchService`, což tam pro `discover()` cestu možná chybí — nezjišťoval
jsem do detailu, protože scope by přesáhl audit).
**Riziko regrese:** střední — SSE progress UI (i když je z části stejně kosmetická, viz bod 4.1)
se dnes odvíjí od `discoverWithLanes` callbacku; jeho odstranění vyžaduje přepojit zdroj progress
eventů.

---

### 1.2 — Frontend spadne na bílou obrazovku po KAŽDÉ dokončené analýze (nesoulad kontraktu)

**Závažnost: Blocker.**

Ověřeno tře­mi nezávislými způsoby: (a) čtení aktuálního kódu, (b) živý payload z reálného
`/explore/summarize` jobu, (c) živá reprodukce v prohlížeči s čistým, nezatíženým listenerem
(`window.addEventListener('error', ...)`, žádné předchozí instrumentace).

Backend (`ExploreSummarizeService.java:112`, `ExploreInstantThenDetailService.java:82`):
```java
result.put("series_coverage", Map.of(
        "loaded", fetch.loaded().size(), "failed", fetch.failed().size(),
        "requested", request.selectedSeries() != null ? request.selectedSeries().size() : 0));
```
Živě ověřený skutečný payload dokončeného jobu (nová, nezávislá session, jiný dotaz než minule):
```json
"series_coverage": {"loaded": 1, "failed": 0, "requested": 1}
```
— **objekt**, ne pole.

Frontend (`ExplorePage.jsx:1701-1704`, volané z `useMemo` na řádku 2263-2266 uvnitř
`SummarizeResultDisplay`):
```js
function buildSeriesCoverageIndex(coverageRows) {
  const byTitle = new Map();
  for (const row of coverageRows || []) {   // {} je truthy → "|| []" se nikdy nepoužije
```

**Živá reprodukce (čistý test, žádná předchozí instrumentace):** po odeslání reálného dotazu
přes UI, po ~283 sekundách běhu, konzole zachytila:
```
Uncaught TypeError: (coverageRows || []) is not iterable
  at .../ExplorePage.jsx:2289
```
a `document.getElementById('root').innerHTML.length === 0` — potvrzeno i vizuálně (screenshot:
prázdná stránka, jen barva pozadí).

**Proč to nejde zachytit a zobrazit chybu místo pádu:** appka nemá žádný `ErrorBoundary`.
Ověřeno čtením `App.tsx` (jen `<Suspense>`, žádný error boundary) a `main.tsx`
(`createRoot(...).render(<StrictMode><App/></StrictMode>)`, taky bez error boundary). Jediné
error boundary v celém frontendu jsou `CatalogSearchErrorBoundary` (ai-search feature) a
`SafeRechartsContainer` — ani jeden nechrání `/explore`. To znamená: **jakákoliv budoucí
neošetřená výjimka kdekoliv v render stromu Manager Exploreru shodí celou appku stejným
způsobem** — tohle není izolovaný bug, je to systémová mezera.

**Vedlejší efekt stejného nesouladu:** `ExplorePage.jsx:2306` a `:2696` typ **správně**
kontrolují (`Array.isArray(result?.series_coverage)`) — a protože backend posílá objekt, jsou
tyhle podmínky vždy `false`. Sekce „Všechny řady ve zpracování (N)" se **nikdy nezobrazí**
(viz bod 1.3).

**Očekávaný přínos opravy:** eliminuje 100% pádů po dokončení analýzy — dnes modul **v praxi
nedokončí ani jeden běh**, takže tohle je předpoklad pro cokoliv dalšího.
**Náročnost opravy:** triviální — `Array.isArray(coverageRows) ? coverageRows : []` na řádku 1704
(1 řádek), případně navíc přidat `ErrorBoundary` kolem `/explore` route jako systémovou pojistku
(malá práce, velký bezpečnostní přínos do budoucna).
**Riziko regrese:** minimální pro opravu samotné iterace; přidání ErrorBoundary je čistě aditivní.

---

### 1.3 — Sekce „Všechny řady ve zpracování" a „klíčové poznatky" jsou trvale mrtvé (ne kvůli bugu, ale protože data nikdy nepřijdou)

**Závažnost: Medium** (žádný pád, jen ztracená funkčnost).

Kromě 1.2 jsem ověřil i DALŠÍ pole, která frontend čeká jako pole, ale backend je pro Manager
Explorer **nikdy neplní vůbec** (ne jen ve špatném tvaru — v žádném tvaru):

- `result.top_drivers` — použito v `extractInstantDrivers()` (`ExplorePage.jsx:195`). Grep přes
  celý backend: `top_drivers` existuje jen v `ForecastModelEngine.java` (jiná feature, ne
  Explorer). Pro Explorer je vždy `undefined`.
- `result.series_used` — použito na řádku 2258 (`SummarizeResultDisplay`). Grep: existuje jen
  v `CatalogSearchAnswerService.java` (obecné AI vyhledávání, ne Explorer). Pro Explorer vždy
  `undefined`.
- `analysis_score.score_drivers` / `top_positive_for_question` / `top_negative_for_question` —
  fallback zdroj pro `extractInstantDrivers()`. Grep přes všechny `explore/*.java`: tahle pole
  zapisuje **jen** `ExploreSectorContract.emptyAnalysisScore()` (prázdný placeholder) — žádná
  jiná služba (`ExploreSectionedSynthesisService`, `ExploreSummarizeService`,
  `ExploreInstantThenDetailService`) je nikdy nepočítá.

**Důsledek:** blok „klíčové poznatky" (top drivers) v hlavní odpovědi je pro Manager Explorer
**vždy prázdný** — ne kvůli chybě, ale protože je to nedodělaná funkce (backend ji nikdy
neimplementoval, jen frontend na ni má hotové zobrazení).

**Očekávaný přínos opravy:** buď dodělat backend (skutečná hodnota pro manažery — „proč tohle
skóre vyšlo takhle"), nebo odstranit mrtvý frontend kód a ušetřit čtenáři matení.
**Náročnost opravy:** dodělat backend = střední až velká (potřeba definovat, co „driver" pro
Explorer vůbec znamená); smazat mrtvý frontend kód = triviální.
**Riziko regrese:** nízké v obou směrech.

---

### 1.4 — Neošetřený re-render efekt způsobuje desítky zbytečných duplicitních requestů

**Závažnost: Medium.**

Toto je nový nález, který předchozí audit vůbec nezachytil — objevil jsem ho čistě živým
sledováním síťového provozu prohlížeče během jednoho běhu analýzy (ne kódovou inspekcí předem).

**Živý důkaz:** během jednoho ~5minutového běhu analýzy zachytil síťový log **desítky volání**
`POST /explore/related-suggestions` se stejným segmentem, a to i s **bajtově identickou**
odpovědí (ověřeno porovnáním dvou různých request ID z různých částí běhu — identický JSON).
Endpoint sám je levný (`ExploreAuxiliaryService.relatedSuggestions()` — čistě deterministický
in-memory lookup, žádné LLM volání), takže náklad na backend je malý, ale je to jasný signál
neošetřeného re-render cyklu.

**Kódová stopa:**
```js
// ExplorePage.jsx:3768-3778
useEffect(() => {
  const sec = String(sector || "").trim();
  if (!sec) { setRelationshipRelatedRows([]); return undefined; }
  const timer = window.setTimeout(() => { void refreshRelationshipRelatedRows(); }, 250);
  return () => window.clearTimeout(timer);
}, [sector, refreshRelationshipRelatedRows]);
```
`refreshRelationshipRelatedRows` je `useCallback` se závislostmi
`[combinedRelatedSegments, geoMode, sector, selectedContinent, selectedCountryCodes]`
(řádek 3766). **HYPOTÉZA** (neprošel jsem celý závislostní graf až k primitivním hodnotám,
protože komponenta má accessed 6459 řádků a desítky provázaných `useMemo`/`useCallback`): pokud
se identita `refreshRelationshipRelatedRows` mění při každém re-renderu (např. kvůli nestabilní
referenci v jedné z jejích závislostí), tenhle 250ms debounce efekt se spustí znovu při KAŽDÉM
re-renderu stránky — a stránka se re-renderuje velmi často během SSE streamu (viz 3.1).

**Očekávaný přínos opravy:** odstraní desítky zbytečných HTTP round-tripů za běh; samotný
compute-cost na backendu je malý, ale je to zbytečná zátěž na connection pool/thread pool
právě v okamžiku, kdy backend řeší mnohem dražší discovery práci (bod 1.1).
**Náročnost opravy:** malá až střední — vyžaduje dohledat přesný zdroj nestability v
dependency chainu (`combinedRelatedSegments` je memoizovaný string, takže podezření padá na
`selectedCountryCodes` nebo něco navazujícího na `countrySelections`/`unifiedRelatedItems`).
**Riziko regrese:** nízké — jde o opravu memoizace, ne o změnu chování.

---

## 2. Výkon

### 2.1 — Kde se čas skutečně tráví (vlastní timing log backendu, ne odhad)

Tři nezávislé, čerstvé měření na klidném backendu, stejný dotaz, přes přímý `/catalog/deep-search`
endpoint (pozor: **toto NENÍ cesta, kterou jde Explorer** — Explorer jde přes `discover()`, který
používá jinou orchestraci, `SearchV2Service`; číslo níže ukazuje strukturu nákladů, ne přesný
absolutní čas Exploreru):

| fáze | podíl (opakovaně, ~stabilní) |
|---|---:|
| `retrieval_ms` / `fts_ms` | ~20-40 % |
| `reranker_ms` (AI) | ~15-20 % |
| `preview_verification_ms` | ~15-20 % |
| `retry_ms` (druhý pokus při slabém pokrytí) | 0-35 % (podmíněné) |
| `planner_ms` (LLM plán) | ~10 % |
| `answer_ms` (AI odpověď — Explorer ji nepoužívá vůbec, viz 2.2) | ~10-15 % |

Skutečná cesta Exploreru (`CatalogDeepSearchService`, měřeno na `/explore/sector/stream`):
`plan_ms` 3,4-4,9 s, **`lanes_ms` 58-70 s (dominantní)**, `preview_ms` 1,5-13 s, `answer_ms`
2,6-3,0 s. Tohle je **JEDEN** ze dvou běhů, které se reálně stanou (viz 1.1) — čísla je tedy
třeba násobit ~2×, aby odpovídala reálné zkušenosti uživatele.

### 2.2 — Explorer si nechá generovat AI odpověď (`answer_ms`), kterou nikdy nepoužije

**Závažnost: Low-Medium** (potvrzeno, ale menší dopad než 1.1).

`ExploreDiscoveryService.discover()` čte z výsledku deep-searche pouze pole `verified`/`possible`
— nikdy `answer`. Deep-search je ale volán bez `use_ai_story:false`, což znamená defaultní
`true` (`SearchV2Service.java:260`) → LLM se pokaždé zavolá a vygeneruje text, který se zahodí.

**Nutná korekce k mému VLASTNÍMU dřívějšímu měření v této session:** poprvé jsem naměřil
úsporu `use_ai_story:false` jako výraznou (~26-45 %) — ale to bylo na endpointu
`/api/catalog/deep-search` přímo, který **jde jinou orchestrací** (`SearchV2Service`, "v2") než
Explorer (`CatalogDeepSearchService`, legacy). Na skutečné cestě Exploreru je `answer_ms` jen
2,6-3,0 s z celkových 70-90 s jednoho běhu — tedy **~3-4 %**, ne desítky procent. Uvádím obě
čísla, protože obě jsou pravdivá, jen pro různé cesty kódu — důležité je použít správnou cestu
pro správný závěr.

**Očekávaný přínos:** ~3 s na běh, ~6 s na dva běhy (viz 1.1) — malé, ale zadarmo.
**Náročnost opravy:** triviální (přidat `"use_ai_story": false` do payloadu, který
`ExploreDiscoveryService` posílá).
**Riziko regrese:** minimální — Explorer pole `answer` nikdy nečte.

### 2.3 — Sekvenční síťový fetch až 14 řad v kroku „summarize"

**Závažnost: Medium** (ověřeno čtením kódu, ne živě přeměřeno — časová náročnost by vyžadovala
připravit 14 reálných řad a to jsem v rámci tohoto auditu nedělal; považuji za HYPOTÉZU
kvantifikace, i když existence sekvenčního vzoru je jistá).

`ExploreSummarizeFetchService.java:44-51`:
```java
int cap = Math.max(1, Math.min(maxSeries, DEFAULT_MAX_SERIES));  // DEFAULT_MAX_SERIES = 14
for (ExploreSummarizeSeriesItem item : items) {
    if (loaded.size() >= cap) break;
    Optional<Map<String, Object>> fetched = fetchOne(ref, country);  // může jít na živý konektor
}
```
Čistě sekvenční smyčka až přes 14 položek, kde `fetchOne` může zavolat
`previewOrchestrator.fetchRecords()` (síťové/živé volání) nebo `managerFetchRegistry.tryFetch()`.
Pro kontrast: `ExploreSectionedSynthesisService.java:58-64` **stejný typ práce (AI volání na
sekci) paralelizuje správně** přes `CompletableFuture` — je to tedy jasně jen tenhle jeden
konkrétní kód, kde se paralelní vzor nepoužil, ne systémový problém napříč celou codebase.

**Očekávaný přínos opravy:** až ~N-násobné zrychlení kroku summarize/detail (N = počet řad, do
14), omezené jen tím, kolik konektorů skutečně vyžaduje živé volání vs. cache/mirror hit.
**Náročnost opravy:** malá — stejný vzor (`CompletableFuture` + `.join()`) už existuje jinde
v témže balíčku, dá se zkopírovat.
**Riziko regrese:** nízké, pokud `fetchOne` nemá sdílený mutable stav (nekontroloval jsem do
hloubky, ale funkce vrací hodnotu a nemá viditelné vedlejší efekty na sdílený stav kromě logování).

### 2.4 — Cache: funguje, ale nespolehlivě kvůli nedeterministickému LLM plánovači

**Závažnost: Low** (informativní nález, ne bug).

Živě změřeno (5 identických requestů za sebou na klidném backendu, stejný dotaz):

| rep | čas | verified count | výsledek |
|---|---:|---:|---|
| 1 | 20,0 s | 8 | sada A (LFSI/LRUN série) |
| 2 | 13,3 s | 8 | sada B (ZAMG/MZDCRRT série) — **jiná než rep 1** |
| 3 | **0,03 s** | 8 | sada B — **identická s rep 2** |
| 4 | **0,03 s** | 8 | sada B |
| 5 | **0,02 s** | 8 | sada B |

**Nález:** cache **funguje** — jakmile se jednou vytvoří konkrétní cache klíč (odvozený mj.
z `plan.firstPassSearchTerms()`, což pochází z LLM plánovače), opakované identické požadavky
jsou téměř okamžité (bajtově identická odpověď, 20-800× rychlejší). Problém je, že LLM plánovač
**není deterministický** — rep 1 a rep 2 dostaly STEJNÝ vstupní dotaz, ale LLM vygeneroval jiné
vyhledávací termy pokaždé → jiný cache klíč → cache miss → jiná sada výsledků. Teprve jakmile se
"trefí" na stejný plán (rep 2), další opakování už jedou z cache.

**Vedlejší zjištění:** cachovaná odpověď obsahuje **zastaralé vlastní timing metriky** — JSON
tělo repu 5 (cache hit, 24 ms skutečného přenosu) hlásilo `"total_pipeline_ms": 13225` — číslo
z okamžiku, kdy byla odpověď PRVNĚ vypočítána (rep 2), ne skutečný čas obsloužení repu 5. Kdokoliv
čte `timings` z cachované odpovědi, dostane zavádějící (přemrštěná) čísla.

**Očekávaný přínos opravy:** cache sama o sobě nepotřebuje opravu (funguje podle návrhu); reálný
přínos by přišel ze stabilizace plánovače (viz 3. Kvalita výsledků) — to by ZVÝŠILO hit-rate
cache jako vedlejší efekt.
**Náročnost:** viz bod 3.1 (jde o stejný root cause).
**Riziko regrese:** n/a — toto není návrh na změnu, jen popis stavu.

---

## 3. Kvalita výsledků

### 3.1 — Stejný dotaz nevrací stejné výsledky (potvrzeno, root cause = LLM plánovač)

**Závažnost: Medium-High** (dopad na důvěryhodnost u manažerského nástroje, kde se výsledek
typicky ukládá/sdílí a člověk očekává reprodukovatelnost).

Viz tabulka v 2.4 — 2 z 5 identických požadavků vrátily jinou sadu ukazatelů (rep 1 vs. rep 2),
přestože šlo o **byte-identický request**. Response obsahuje `"planner": "openai"` — plán se
generuje LLM voláním, a LLM výstup (vyhledávací termy) není deterministický ani při teplotě
blízké nule (běžná vlastnost LLM API). Cache klíč je odvozen z těchto termů
(`SearchV2Service.java` — `cacheKey` obsahuje `plan.firstPassSearchTerms()`), takže dvě různá
volání plánovače pro identický vstup vytvoří dva různé cache klíče a dvě různé sady výsledků.

**Toto NENÍ problém v retrievalu/FTS ani v AI reranku** — je to specificky v kroku plánování
(generování vyhledávacích termů). Ověřeno tím, že cache hits (rep 3-5) dávaly bajtově identické
výsledky — pokud by šlo o nedeterminismus v rerankeru/retrievalu, ani cache hits by nebyly stabilní.

**Očekávaný přínos opravy:** stabilnější, důvěryhodnější výsledky napříč opakovanými dotazy +
vyšší cache hit-rate jako vedlejší efekt.
**Náročnost opravy:** střední až velká — vyžaduje buď snížit teplotu/nedeterminismus LLM volání
(rychlé, ale ne zaručené — LLM API nejsou 100% deterministická ani na teplotě 0), nebo přejít na
deterministický/hybridní plánovač (větší práce, ale jistější).
**Riziko regrese:** střední — změna plánovací logiky může ovlivnit recall/relevanci pro edge-case
dotazy, které dnes náhodou fungují díky konkrétní LLM odpovědi.

---

## 4. UX

### 4.1 — Progress bar je čistě kosmetická animace, nesouvisí se skutečným stavem backendu

**Závažnost: Medium** (ověřeno znovu, nezávisle, čtením kódu — potvrzuji topline nález
předchozího auditu, ale s doplněním, že SAMOTNÉ animace/intervaly jsou implementovány čistě
— žádný memory leak, viz 5.1).

`ExplorePage.jsx:119,130,455,532-546`:
```js
const SCAN_SOURCES = ["EUROSTAT", "ECB", "IMF", "OECD", "CSU", "ARAD", "BIS", "WORLDBANK"];
const SCAN_ESTIMATE_SEC = 70;
function buildSectorScanLines(sector) {
  const titles = [...sectorTitles, ...GENERIC_SCAN_TITLES];
  for (let i = 0; i < titles.length; i += 1) {
    lines.push({ source: SCAN_SOURCES[i % SCAN_SOURCES.length], title: titles[i % titles.length] });
  }
  ...
}
```
Dvojice zdroj×kategorie jsou čistá modulární aritmetika nad dvěma hardcoded seznamy — **žádná
vazba na to, co backend skutečně dělá**. Odhad zbývajícího času je konstanta (70 s), přestože
naměřené reálné časy (viz 2.1) sahají od 70 do 168 s **na jeden běh** — a reálně uživatel čeká na
DVA běhy (bod 1.1), tedy klidně 150-300 s.

**Backend přitom posílá skutečný stav** (`ExploreStreamService.java:69-95`):
```java
sendProgress(emitter, "source_started", Map.of("source", source, ...));
sendProgress(emitter, "source_finished", Map.of("source", source, "candidates", lane.getOrDefault("count", 0), ...));
```
**Živě ověřeno** (SSE capture): tyhle eventy skutečně chodí, s reálnými názvy zdrojů a reálnými
počty kandidátů (`arad: 18 kandidátů`, `worldbank: 0`, atd.).

`exploreSectorStream.js:52,184-187` má pro tohle připravený callback `onSourceStatus`:
```js
if (["source_started","source_finished","source_timeout","source_error"].includes(event)
    && typeof onSourceStatus === "function") {
  onSourceStatus(msg);
}
```
Ale skutečné volání `runExploreSectorStream({...})` v `ExplorePage.jsx:4414-4429` předává jen
`onPreset`, `onPartial`, `onQuickPreview` — **`onSourceStatus` mezi nimi není**. Ověřeno přímým
čtením volajícího místa, ne jen existence callbacku v knihovně.

**Menší vedlejší bug ve stejném mechanismu:** `source_finished` posílá natvrdo `"status": "ok"`
i pro lane, která nic nenašla (`candidates: 0`) — tedy i po navázání `onSourceStatus` by bylo
potřeba to opravit, jinak by UI lhalo, že všechno bylo „ok".

**Očekávaný přínos opravy:** uživatel by viděl skutečný postup (které zdroje se prohledávají,
kolik toho našly) místo nesouvisející animace — zvlášť důležité vzhledem k tomu, jak dlouho
čekání reálně trvá (2-5 minut).
**Náročnost opravy:** malá až střední — navázat `onSourceStatus`, opravit `"status":"ok"` bug,
přepočítat odhad z reálného počtu dokončených zdrojů místo konstanty.
**Riziko regrese:** nízké — čistě aditivní/kosmetická změna zobrazení, nemění výpočetní logiku.

### 4.2 — Interní debug telemetrie (přesné ms) je natvrdo v produkčním UI

**Závažnost: Low.** Nový nález, nebyl v předchozím auditu.

`ExplorePage.jsx:5313-5323`:
```jsx
{exploreStepTimings ? (
  <div className="text-[10px] text-slate-500 font-mono">
    QU {exploreStepTimings.query_understanding_ms} ms
    {exploreStepTimings.curated_plan_ms ? ` · plán ${exploreStepTimings.curated_plan_ms} ms` : ""}
  </div>
) : null}
```
Žádná podmínka na `import.meta.env.DEV` ani jiný gate — **tohle se zobrazuje úplně každému
uživateli v produkci**, natvrdo, jako malý šedý monospace text pod polem s dotazem. Živě ověřeno
screenshotem: `QU 2728 ms · plán 126609 ms`.

**Očekávaný přínos opravy:** čistší produkční UI, žádné zbytečné vystavení interní architektury
(časování jednotlivých backend kroků) komukoliv, kdo se dívá na obrazovku nebo dělá screenshot.
**Náročnost opravy:** triviální — buď smazat, nebo obalit `import.meta.env.DEV` podmínkou.
**Riziko regrese:** žádné.

---

## 5. Architektura

### 5.1 — Frontend: žádné memory leaky v časovačích, ale žádná memoizace child komponent

**Závažnost: Low.**

Prošel jsem VŠECH 8 míst v `ExplorePage.jsx`, kde se vytváří `setInterval`/`setTimeout`
(řádky 571, 577, 580, 715, 718, 2887, 3325, 3774) — **všech 7 relevantních (2887 je Promise-based
delay bez potřeby úklidu) má odpovídající `clearInterval`/`clearTimeout` v cleanup funkci
useEffectu.** Žádný memory leak v časovačích. `EventSource` v `exploreSectorStream.js` se
korektně zavírá (`es.close()`) ve všech třech cestách (done/finishEarly/onerror).

**Co ALE ověřeno je:** `ExplorePage.jsx` importuje ~14 sub-komponent z `components/explore/` a
ani jedna z nich **není** obalená v `React.memo` na straně volajícího (0 výskytů `memo(` v
souboru). `applyExploreSectorPayload` (voláno jednou pro **každou** SSE `indicators_update`
zprávu — živě naměřeno 9-27× za jeden běh) volá 6-8 samostatných `setState` volání
(`ExplorePage.jsx:4098-4142`). To znamená: 9-27× za běh se celý, obrovský (6459řádkový)
komponentový strom re-renderuje, i v případech, kdy naprostá většina těchto zpráv nemění
zobrazený obsah (v mém SSE zachytávání zůstávaly `sector_indicators`/`macro_indicators` prázdné
až do úplně poslední zprávy).

**HYPOTÉZA** (nemám profiler trace, jen strukturální důkaz): skutečný dopad na výkon prohlížeče
je pravděpodobně malý v absolutních ms (žádost neobsahuje těžké komponenty jako grafy během
scanning fáze), ale je to změřitelně zbytečná práce, která se sčítá s vlastní 65ms animací
scanneru (bod 4.1) — dohromady dost časté re-rendery po celou dobu čekání.

**Očekávaný přínos opravy:** malý, ale nenulový — méně zbytečné CPU práce prohlížeče během
2-5minutového čekání.
**Náročnost opravy:** střední — vyžadovalo by rozdělit `applyExploreSectorPayload`na dávkovější
update nebo memoizovat těžké child komponenty.
**Riziko regrese:** střední — `ExplorePage.jsx` je jeden obří soubor s hodně sdíleným stavem;
memoizace vyžaduje opatrnost, aby se něco nepřestalo aktualizovat.

### 5.2 — Mrtvý endpoint `/manager/analysis-plan`

**Závažnost: Low** (úklid, ne funkční bug). Znovu ověřeno nezávisle, širším gnu grep přes CELÝ
`BankIntel-v2` strom (ne jen `frontend/src`), ne jen frontend adresář jako minule.

```
grep -rln "analysis-plan|analysisPlan|ManagerAnalysisPlan" .  → 0 výsledků mimo backend Java
```
`POST /api/explore/manager/analysis-plan` (`ExploreController.java:132`) a jeho service
(`ManagerAnalysisPlanService.java`) nemají **žádného** volajícího nikde ve frontendu.

**Očekávaný přínos:** úklid, menší attack surface, méně kódu k udržování.
**Náročnost:** malá (smazat endpoint + service, pokud se fakt nepoužívá jinde — např. externí
integrace, kterou jsem nemohl z tohoto repa vidět).
**Riziko regrese:** nízké, ALE doporučuji nejdřív potvrdit, že to nevolá nic mimo tento frontend
(mobilní appka, jiný klient) — to jsem neměl jak ověřit.

### 5.3 — `Thread.sleep(800ms)` na request-handling vlákně

**Závažnost: Low.** Nový drobný nález.

`ExploreSummarizeService.java:22-36`:
```java
CompletableFuture.runAsync(() -> runJob(job));
Thread.sleep(ExploreConstants.SUMMARIZE_SYNC_WAIT_MS);  // = 800L, ExploreConstants.java:7
return statusOrResult(jobId);
```
Job se pustí asynchronně, ale servlet vlákno, které obsluhuje request, **záměrně čeká 800 ms**
(pravděpodobně proto, aby rychlé joby stihly doběhnout a klient nemusel dělat extra polling
round-trip). Funkčně to není špatně, ale blokuje jedno vlákno z Tomcat thread poolu na 800 ms
na každý `/explore/summarize` request bez ohledu na to, jestli to k něčemu bylo.

**Očekávaný přínos opravy:** marginální — jen relevantní při vysoké souběžnosti (mnoho
uživatelů startuje summarize najednou), kdy by to mohlo tlačit na velikost thread poolu.
**Náročnost opravy:** malá (nahradit `Thread.sleep` neblokujícím scheduled callbackem), ale
nejde o prioritu.
**Riziko regrese:** nízké.

### 5.4 — Sequential vs. parallel: nekonzistentní vzor v rámci stejného balíčku

Viz bod 2.3 — `ExploreSummarizeFetchService.fetchBatch()` je sekvenční,
`ExploreSectionedSynthesisService.synthesizeSections()` (řádky 58-78) je správně paralelizovaná
přes `CompletableFuture`. Uvádím to zde jako architekturní pozorování: vzor pro paralelizaci
**existuje a funguje** jinde ve stejném balíčku — oprava 2.3 by ho jen měla zkopírovat, ne
vymýšlet nový.

---

## 6. Cache — souhrn (viz 2.4 pro detail)

Funguje na úrovni celé odpovědi (potvrzeno: opakovaný identický request po „vychladnutí" plánu
je 20-800× rychlejší, bajtově identický výstup). Hit-rate je omezen nedeterminismem LLM
plánovače (bod 3.1), ne chybou v cachovacím mechanismu samotném. Vedlejší bug: cachovaná
odpověď nese zastaralé `timings` hodnoty z okamžiku prvního výpočtu — matoucí pro každého, kdo
čte timing telemetrii z cachovaného requestu.

---

## 7. Paralelizace — souhrn

- **Špatně (sekvenční, mělo by být paralelní):** `ExploreSummarizeFetchService.fetchBatch()` —
  až 14 síťových volání za sebou (bod 2.3).
- **Dobře (už paralelní):** `ExploreSectionedSynthesisService` — N AI volání přes
  `CompletableFuture`, `ExploreDiscoveryService.discover()`/`discoverWithLanes()` — per-zdroj
  lanes přes `Executors.newVirtualThreadPerTaskExecutor()`.
- **Zásadní problém NENÍ v paralelizaci uvnitř jednoho běhu discovery** (to funguje dobře) —
  je v tom, že se **celý paralelní běh spustí dvakrát nezávisle na sobě** (bod 1.1).

---

## 8. Frontend — souhrn (viz 5.1 pro detail)

Žádné memory leaky v časovačích/EventSource. Potvrzený, ale nekvantifikovaný (HYPOTÉZA na
přesný dopad) re-render overhead z chybějící memoizace + časté SSE-driven state updaty.
Potvrzený, živě zachycený bug se zbytečnými duplicitními requesty (bod 1.4).

---

## 9. Backend — souhrn

Žádné přímé SQL/JDBC volání v `explore` balíčku (data vrstva je delegovaná do `search`/`catalog`
vrstvy, mimo scope tohoto auditu). Jeden `Thread.sleep` (bod 5.3, nízká závažnost). LLM volání
soustředěná do 5 míst (`ExploreQueryUnderstandingService`, `ExploreFollowupService`,
`ExploreSectionedSynthesisService` ×2, `ExploreSectorService`) — žádné z nich se nevolá zbytečně
navíc KROMĚ zahozené `answer` odpovědi v discovery (bod 2.2). Hlavní backendový problém není
jedno pomalé volání, ale **strukturální zdvojení celé discovery pipeline** (bod 1.1).

---

## Prioritizovaný seznam oprav (poměr přínos/pracnost, nejvyšší první)

| # | Nález | Závažnost | Přínos | Pracnost | Riziko regrese | Poměr |
|---|---|---|---|---|---|---|
| 1 | **1.2** — `series_coverage` pole vs. objekt → pád na bílo | Blocker | Modul dnes prakticky nefunguje vůbec | Triviální (1 řádek) | Minimální | ★★★★★ |
| 2 | **2.2** — zahozená AI odpověď v discovery (`use_ai_story:false`) | Low-Medium | ~3 s/běh (×2 běhy = ~6 s) zadarmo | Triviální (1 řádek) | Minimální | ★★★★★ |
| 3 | **4.2** — debug telemetrie v produkci | Low | Čistší UI, žádný leak interní architektury | Triviální | Žádné | ★★★★ |
| 4 | **1.1** — dvojí běh discovery + SSE timeout 120s | Blocker | **−40 až −50 % reálného času uživatele**, konec timeout-kaskády | Střední | Střední | ★★★★ |
| 5 | **1.4** — re-render smyčka → desítky duplicitních requestů | Medium | Méně zátěže na backend právě když je nejvytíženější | Malá-střední | Nízké | ★★★★ |
| 6 | **4.1** — navázat skutečný SSE progress místo fake animace | Medium | Uživatel vidí pravdu o 2-5min čekání | Malá-střední | Nízké | ★★★ |
| 7 | **2.3** — paralelizovat fetch až 14 řad v summarize | Medium | Až N-násobné zrychlení kroku summarize | Malá (vzor už existuje v repu) | Nízké | ★★★ |
| 8 | **5.2** — smazat mrtvý endpoint `/manager/analysis-plan` | Low | Úklid, menší attack surface | Malá | Nízké (ověřit externí klienty) | ★★ |
| 9 | **1.3** — dodělat nebo smazat `top_drivers`/`series_used` | Medium | Buď reálná hodnota, nebo méně matoucí kód | Střední (dodělat) / triviální (smazat) | Nízké | ★★ |
| 10 | **3.1** — stabilizovat LLM plánovač (determinismus) | Medium-High | Důvěryhodné, reprodukovatelné výsledky + vyšší cache hit-rate | Střední-velká | Střední | ★★ |
| 11 | **5.1** — memoizace child komponent | Low | Marginální úspora CPU prohlížeče | Střední | Střední | ★ |
| 12 | **5.3** — `Thread.sleep(800ms)` na servlet vlákně | Low | Marginální, jen při vysoké souběžnosti | Malá | Nízké | ★ |

**Doporučené pořadí realizace:** 1 → 2 → 3 (všechny triviální, hotové za hodinu, #1 je
předpoklad pro cokoliv dalšího) → **4** (největší jednotlivý win, ale vyžaduje skutečný
architektonický zásah — doporučuji tomu věnovat samostatnou iteraci) → 5 → 6 → 7 → zbytek podle
kapacity.

---

## Co jsem NEOVĚŘIL (transparentně přiznávám mezery)

- Nekontroloval jsem `search`/`catalog` vrstvu do hloubky na SQL/blokující I/O — to je mimo
  balíček `explore`, a předchozí sessions na tomhle projektu už tuhle vrstvu auditovaly
  opakovaně jinde; nechtěl jsem duplikovat práci mimo scope „Manager Explorer".
- Přesný root cause re-render smyčky v bodě 1.4 jsem netrasoval až k primitivní hodnotě (jen
  k `useCallback` závislostem) — označeno jako HYPOTÉZA.
- Dopad chybějící memoizace (5.1) jsem neměřil profilerem — jde o strukturální pozorování, ne
  změřený počet zbytečných ms.
- Neprošel jsem VŠECHNY funkce Manager Exploreru živě (např. upload vlastních souborů, "Pouze
  vybrané" scope, `/sector/refine`) — časový rozpočet auditu šel primárně do hlavního
  sector-analysis + summarize toku, protože tam padly nejzávažnější nálezy.
