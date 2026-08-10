# Manager Explorer — E2E audit (noční test, 2026-08-03)

Testováno živě jako přihlášený uživatel (`admin@bankintel.local`, admin/subscriber) na
`localhost:5173/explore`, plus přímé měření backendu a čtení jeho vlastních timing logů.
Všechna čísla jsou **naměřená**, ne odhadovaná.

---

## P0 — BLOCKER: po dokončení analýzy spadne celá stránka na bílou obrazovku

**Manager Explorer v aktuálním stavu nedokončí ani jeden běh.** Uživatel čeká minuty a pak
přijde o celý výsledek.

Zachyceno 2× nezávisle, podruhé na čisté stránce bez jakékoli mé instrumentace:
```
Uncaught TypeError: (coverageRows || []) is not iterable
  @ /src/features/manager-explorer/pages/ExplorePage.jsx
```
Po chybě je `document.getElementById('root').innerHTML.length === 0` — React strom se odmountuje.

### Příčina: nesoulad kontraktu backend ↔ frontend

Backend posílá `series_coverage` jako **objekt**
(`ExploreSummarizeService.java:112`, `ExploreInstantThenDetailService.java:82`):
```java
result.put("series_coverage", Map.of(
        "loaded", fetch.loaded().size(), "failed", fetch.failed().size(), "requested", ...));
```
Ověřeno na reálné odpovědi dokončeného jobu:
```json
"series_coverage": {"loaded": 0, "failed": 1, "requested": 1}
```

Frontend to iteruje jako **pole** (`ExplorePage.jsx:1704`, volané z `:2264`):
```js
for (const row of coverageRows || [])   // {} je truthy → "|| []" se nikdy neuplatní → TypeError
```
Deterministické — spadne to při každém běhu, který dojde k výsledku.

### Stejný nesoulad má i druhý, tichý důsledek
`ExplorePage.jsx:2306` a `:2696` si naopak typ **ověřují** (`Array.isArray(...)`). Protože backend
posílá objekt, jsou vždy false → sekce **„Všechny řady ve zpracování (N)"** (ř. 2696–2703) se
nikdy nevykreslí. Mrtvá funkce.

Že 2 ze 3 míst typ kontrolují a třetí ne naznačuje, že se o nejednoznačnosti vědělo, jen se
nedotáhla.

### Doporučení
Nejrychlejší odblokování: na ř. 1704 `Array.isArray(coverageRows) ? coverageRows : []`.
Čistší dlouhodobě: sjednotit kontrakt — `series_coverage` jako pole řádků a
`{loaded,failed,requested}` nechat v už existujícím `fetch_summary`.

---

## P1 — Výkon: jeden dotaz trvá 32–168 s

8 realistických manažerských dotazů přes `POST /api/explore/sector`:

| dotaz | čas | ukazatelů |
|---|---:|---:|
| US_technologie | 37,1 s | 3 |
| CZ_energetika | 42,4 s | 4 |
| CZ_banky_ziskovost | 47,8 s | 10 |
| EU_nemovitosti | 121,8 s | 5 |
| CZ_maloobchod | 136,2 s | 5 |
| CZ_inflace | 162,6 s | 8 |
| DE_automotive | 168,1 s | 4 |

Na **nezatíženém** backendu, 3× stejný dotaz: 71,6 / 71,4 / 70,2 s — velmi stabilní.

### Kde ten čas je — z vlastních timing logů backendu

Tři identické běhy (`ziskovost ceskych bank a uverovani`), log `CatalogDeepSearchService`:

| fáze | běh 1 | běh 2 | běh 3 | podíl |
|---|---:|---:|---:|---:|
| `plan_ms` | 2 034 | 2 469 | 2 426 | 3 % |
| **`lanes_ms`** | **53 996** | **52 457** | **53 045** | **~75 %** |
| `preview_ms` | 12 420 | 12 392 | 12 402 | 18 % |
| `answer_ms` | 2 954 | 2 531 | 2 314 | 4 % |
| **total** | **71 404** | **69 849** | **70 187** | |

**`lanes_ms` je 75 % celého času.** Všechno ostatní je šum.

### Hlavní viník uvnitř lanes: zdroj IMF

Lanes běží paralelně (`Executors.newVirtualThreadPerTaskExecutor()`), spojují se přes `allOf` —
takže `lanes_ms` = **nejpomalejší jediná lane**. Per-lane logy, dva po sobě jdoucí identické běhy:

| zdroj | fts_ms (běh 2) | fts_ms (běh 3) |
|---|---:|---:|
| ecb2 | — | 3 684 |
| arad | 3 871 | 3 903 |
| csu | 4 281 | 4 252 |
| worldbank | 6 343 | — |
| eurostat | 7 018 | 6 955 |
| data360 | 15 170 | 14 490 |
| fred | 15 607 | 15 099 |
| **imf** | **31 829** | **30 878** |

IMF je ~8× pomalejší než arad/csu a **ani nestihne dokončit své termy** (`terms_done=3/5`).
Ve vzorku `source_statuses` přitom IMF vrátil `count=0` — tedy nejdražší lane nepřispěla ničím.
(Tohle je 1 vzorek statusů proti 3 konzistentním vzorkům časů — čas je jistý, nulový přínos
ověřený jednou.)

**Odhad dopadu:** kdyby IMF lane měla timeout na úrovni druhého nejpomalejšího zdroje (~15 s),
`lanes_ms` klesne z ~53 s na ~20 s → **celkem ~70 s → ~37 s**. To je zdaleka největší
jednotlivá páka v celém modulu.

Souvisí: 4 z 9 zdrojů vrátily ve vzorku 0 výsledků (`imf`, `eurostat`, `csu`, `oecd4`), a přitom
každý odpaluje 14 dotazů. Stojí za ověření, jestli jde o mezeru v indexu nebo o mapování názvu
zdroje (Explorer žádá `ecb`, ve statusech je `ecb2`).

### Druhá páka: `preview_ms` ≈ 12,4 s (18 %)
Velmi stabilní napříč běhy. Explorer v tomhle kroku ověřuje náhledy kandidátů; stojí za zvážení,
jestli je to v discovery fázi nutné v plném rozsahu.

### Třetí (malá) páka: Explorer si nechá generovat AI odpověď, kterou zahodí
`ExploreDiscoveryService.discover()` čte z výsledku **pouze** `verified` a `possible`, ale
deep-search volá bez `use_ai_story` (default `true`, `SearchV2Service.java:260`), takže se pokaždé
složí `answer`, který nikdo nepřečte. Na této cestě stojí **~2,6 s (4 %)**.

> Pozn. k metodice: nejdřív jsem tenhle efekt naměřil jako −26 % (párově, 6× A/B, B vyhrálo 6/6).
> Jenže to bylo na endpointu `/api/catalog/deep-search`, který jde **jinou** cestou
> (`search_engine: v2`, v logu se vůbec neobjeví). Explorer jde přes legacy
> `CatalogDeepSearchService`, kde je celkový čas 70 s místo 10 s, takže tytéž ~2,6 s jsou jen 4 %.
> Uvádím obojí, aby bylo jasné, že úspora je reálná, ale na Exploreru **není** hlavní páka.

### Čtvrtá páka: série se stahují sériově
`ExploreSummarizeFetchService.java:51` — `fetchBatch` je čistě sekvenční smyčka až přes
`DEFAULT_MAX_SERIES = 14` řad, kde `fetchOne` může jít na živý konektor nebo mirror:
```java
for (ExploreSummarizeSeriesItem item : items) { ... fetchOne(ref, country); }
```
Zbytek pipeline paralelní **je** (virtuální vlákna) — tady se ten vzor jen neaplikoval.
Týká se kroku „summarize", ne discovery.

### Bez efektu: zúžení počtu zdrojů a cache
- **Zúžení na 4 zdroje** jsem změřil: jednou −5 %, podruhé +23 %. Protože lanes běží paralelně,
  počet zdrojů nerozhoduje — rozhoduje ten nejpomalejší. **Sem neinvestovat.**
- **Opakovaný identický dotaz nezrychlí** (3× 71,6/71,4/70,2 s; a 6× ~10,2 s na druhé cestě).
  Retrieval cache existuje (`SearchV2Service.java:860`), ale její klíč obsahuje
  `plan.firstPassSearchTerms()` z nedeterministického LLM plánovače, takže se prakticky
  netrefuje. Pro Explorer, kde jeden dotaz stojí přes minutu, by cache na úrovni celé odpovědi
  dávala velký smysl.

---

## P2 — Kvalita: stejný dotaz vrací pokaždé jiný počet výsledků

6 identických dotazů za sebou vrátilo `verified` = **6, 5, 4, 3, 3, 5**. Dvojnásobný rozptyl
bez jakékoli změny vstupu. V bench sadě totéž: „ziskovost bank" dalo jednou 1 ukazatel, jindy 10.

Zdroj nedeterminismu je LLM plánovač (`planner=openai`), který generuje jiné vyhledávací termy
při každém běhu. Pro manažerský nástroj, kde si člověk výsledek ukládá a vrací se k němu, je
tohle problém důvěryhodnosti — dvakrát stejná otázka, dvakrát jiná odpověď.

---

## P3 — UX: ukazatel průběhu je dekorace, ne skutečný stav

Uživatel během čekání vidí, co vypadá jako živý per-zdrojový průběh
(`EUROSTAT — Podnikatelská demografie`, `PRÁVĚ PROCHÁZÍM → IMF — Tržby a poptávka`).
Ve skutečnosti je to modulární aritmetika nad dvěma hardcoded seznamy
(`ExplorePage.jsx:534-547`):
```js
const titles = [...sectorTitles, ...GENERIC_SCAN_TITLES];
lines.push({ source: SCAN_SOURCES[i % SCAN_SOURCES.length], title: titles[i % titles.length] });
```
Dvojice zdroj×kategorie tedy nemají **žádnou** vazbu na realitu. Odhad zbývajícího času je taky
konstanta — `const SCAN_ESTIMATE_SEC = 70;` (`:130`). V testu slíbil „zbývá ~42 s" a pak přešel na
„stále prohledávám · 151 s".

**Přitom reálná data existují a jen se zahazují.** Backend přes SSE posílá skutečný stav
(`ExploreStreamService.java:71-85`):
```java
sendProgress(emitter, "source_started",  Map.of("source", source, ...));
sendProgress(emitter, "source_finished", Map.of("source", source, "candidates", lane.getOrDefault("count", 0), ...));
```
Frontendová knihovna `exploreSectorStream.js` má připravený callback **`onSourceStatus`** — ale
`ExplorePage.jsx` (ř. 4419-4424) předává jen `onPreset`, `onPartial` a `onQuickPreview`.
`onSourceStatus` nepředává nikdo.

→ Navázat `onSourceStatus`, zobrazovat skutečné zdroje a skutečné počty, a odhad počítat
z dokončených lanes místo fixních 70 s. Vzhledem k tomu, že IMF trvá 31 s, by uživatel aspoň
viděl, na co se čeká.

Drobnost: `source_finished` posílá `"status", "ok"` natvrdo i pro lane, která selhala
(ve vzorku měly **všechny** lanes `ok: false`) — takže i po navázání by stav lhal.

---

## P4 — Menší nálezy

**Mrtvý endpoint.** `POST /api/explore/manager/analysis-plan` (`ExploreController.java:132`
+ `ManagerAnalysisPlanService.java`, 440 řádků) nemá ve frontendu jediného volajícího.
Dokončit napojení, nebo odstranit.

**Nikdy nevyplněná pole.** `analysis_score.composite`, `index_hits`, `source_statuses`
a `top_sources_used` se v `ExploreSectorContract` (ř. 81–90) nastaví jako placeholder a
`mergeIndicators` je nikdy nepřepíše → ve všech 8 bench bězích `score = None`, `index_hits = 0`.
Skóre se počítá až v kroku summarize, takže na `/sector` to nejspíš vadit nemusí — ale pole,
která se nikdy nenaplní, matou konzumenta API.

**`country` se bez `geo_mode` tiše zahodí.** `{"country":"CZ"}` bez `geo_mode` → „Svět (globální
kontext)", `country_codes: []`. S `geo_mode:"countries"` → správně. Frontend `geo_mode` vždy
posílá, takže **uživatele se to netýká**; jde jen o robustnost API.

---

## Souhrn priorit

| # | Nález | Dopad | Náročnost |
|---|---|---|---|
| P0 | `series_coverage` objekt vs. pole → bílá obrazovka | **modul nepoužitelný** | triviální (1 řádek) |
| P0b | Sekce „Všechny řady ve zpracování" se nikdy nezobrazí | mrtvá funkce | malá |
| P1a | **IMF lane 31 s blokuje celý dotaz** | **~70 s → ~37 s** | malá (timeout/vyřadit) |
| P1b | `preview_ms` 12,4 s v discovery fázi | 18 % času | střední |
| P1c | Žádná cache celé odpovědi (opakovaný dotaz = plná cena) | velký u opakování | střední |
| P1d | Zahozená AI odpověď (`use_ai_story`) | ~2,6 s (4 %) | triviální (1 řádek) |
| P1e | Sériový fetch 14 řad v summarize | násobné u summarize | střední |
| P2 | Stejný dotaz → 3–6 výsledků (LLM nedeterminismus) | důvěryhodnost | produktové rozhodnutí |
| P3 | Fake progress + fixní odhad 70 s | ztráta důvěry, data existují | malá–střední |
| P4 | Mrtvý endpoint, nevyplněná pole, geo robustnost | úklid | malá |

**Doporučené pořadí:** P0 (odblokovat — bez toho nemá smysl nic dalšího) → P1a (největší
zrychlení za nejmenší práci) → P3 (uživatel aspoň uvidí pravdu o čekání) → P1c/P1b → zbytek.

---

### Metodika a poctivost měření
- Backend + frontend lokálně, přihlášeno jako admin/subscriber.
- Latence: 8 dotazů skriptem (souběžná zátěž → pesimistické) + 3 izolované běhy na nezatíženém
  backendu + čtení vlastních timing logů backendu (`plan_ms/lanes_ms/preview_ms/answer_ms`).
- A/B `use_ai_story`: párově, 6 opakování, střídavě A/B na stejném dotazu.
- **Dvě korekce, které jsem si udělal sám:**
  1. První bílou obrazovku jsem odmítl uznat jako bug, protože jsem měl zpatchovaný `window.fetch`
     — uznal jsem ji až po reprodukci na čisté stránce.
  2. První A/B měření (`−35 až −45 %`) jsem zahodil: tříletý dotaz ukázal opačný trend a následně
     se ukázalo, že testovaný endpoint jde jinou cestou než Explorer. Finální číslo je ~4 %.
- Co jsem **neověřil**: nulový přínos IMF lane mám z 1 vzorku `source_statuses` (časy z 3 vzorků);
  před vyřazením IMF bych to potvrdil na větším vzorku dotazů.
