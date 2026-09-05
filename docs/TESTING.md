# Testy a pokrytí

Tenhle dokument nahrazuje dohady o tom, kde testy chybí, **změřeným stavem**.

```bash
cd backend-java && ./gradlew jacocoTestReport
# HTML: backend-java/build/reports/jacoco/test/html/index.html
# CSV:  backend-java/build/reports/jacoco/test/jacocoTestReport.csv
```

JaCoCo je zapojený jen jako **diagnostika, ne jako brána** — žádný práh nezhazuje build. Z měření
jsou vyloučené entity, DTO, `*Config` a `*Properties`, aby čísla neředily třídy, které nemá smysl
testovat.

---

## Výchozí stav (měřeno 2026-08-21)

**Celkové pokrytí instrukcí: 44,7 %** (133 338 z 298 418).

### Kde je to nejhorší

Řazeno podle počtu nepokrytých instrukcí — tedy podle toho, kolik kódu reálně není osaháno:

| Balíček | Pokrytí | Nepokryto |
|---------|--------:|----------:|
| `service.homepage` | 6,1 % | 6 387 |
| `sources.imf` | 8,0 % | 6 177 |
| `controller.sources` | 7,6 % | 5 413 |
| `service.sources` | 0,3 % | 5 217 |
| `sources.ecb` | 25,9 % | 5 065 |
| `service.me` | 6,7 % | 4 829 |
| `service.content` | 1,1 % | 4 273 |
| `sources.commodities` | 1,0 % | 4 153 |
| `sources.bis` | 1,4 % | 3 537 |
| `service.magazine` | 10,5 % | 3 211 |
| `service.userdata` | 0,0 % | 2 859 |
| `service.chat` | 0,0 % | 2 397 |
| `service.export` | 0,0 % | 1 578 |
| `service.sync` | 0,3 % | 1 532 |

### Co z toho plyne — a co je jinak, než se čekalo

- **Konektory nejsou hlavní díra.** Balíček `connector` má 32,8 %, tedy zhruba průměr repozitáře.
  Poznámka, která ho označovala za nejslabší místo, neseděla.
- **Vyhledávání je naopak nejlépe otestované velké místo** (66,3 %). Vede v absolutním počtu
  nepokrytých instrukcí jen proto, že je zdaleka největší.
- **Skutečné nuly jsou v CRUD a exportech**: `service.userdata`, `service.chat`,
  `service.export`, `controller.dashboard` a `service.sync` jsou na 0–0,3 %.

### Největší zcela nepokryté třídy

| Nepokryto | Třída |
|----------:|-------|
| 2 071 | `connector.ConnectorParseSupport` — *v tomto kole sníženo na 59,4 % pokrytí, viz níže* |
| 1 798 | `sources.ecb.EcbCatalogService` |
| 1 688 | `service.userdata.UserDataParseService` |
| 1 560 | `sources.commodities.WorldbankPinkSheetXlsxParser` |
| 1 536 | `service.chartagent.ChartAnalyticsEngine` |
| 1 351 | `controller.sources.TradingEconomicsController` |
| 1 316 | `service.sources.SourceConnectorMapper` |
| 1 296 | `connector.FileUploadReadSupport` |
| 1 144 | `sources.ecb.Ecb2CatalogService` |
| 844 | `service.content.ArticleService` |

---

## Doporučené pořadí, kudy pokrytí doplňovat

Ne podle velikosti, ale podle **ceny selhání**. U datové platformy je nejdražší chyba ta, která
vyrobí špatné číslo a nikdo si toho nevšimne.

### 1. Parsery (nejvyšší priorita)

Čisté funkce vstup → výstup, nulová infrastruktura, a přesně tady vzniká tichá datová korupce:

- `ConnectorParseSupport` — **0 % → 59,4 %**, viz `ConnectorParseSupportTest` (19 testů). Pokryté
  jsou cesty BIS (SDMX XML), IMF (SDMX-JSON), CSV preview a Data360. **Nepokrytý zbytek je větev
  OECD** (`flattenOecdSdmxRecords`, `flattenOecdIndexedObservations`, `collectOecdObsRecursive`) —
  to je nejbližší další krok ve stejném souboru.
- `WorldbankPinkSheetXlsxParser` — parsování XLSX komodit.
- `UserDataParseService` — uživatelem nahrávaná data.
- `FileUploadReadSupport` — čtení nahraných souborů.

### 2. Exporty

`service.export` je na 0 %. Export je to, co uživatel odnáší ven a čeho si sám nevšimne, když je
špatně. `ExportPdfWriter` a `ExportSpreadsheetWriter` jdou testovat proti fixturám.

### 3. CRUD a oprávnění

`service.userdata`, `service.chat`, `controller.dashboard`. Tady nejde ani tak o výpočty jako
o **kdo co smí** — a protože autorizace se vynucuje až v controllerech
(viz [`ONBOARDING.md`](ONBOARDING.md#autorizace-není-ve-securityconfig)), regresi v oprávnění
nechytí nic jiného než test.

### 4. Katalogové služby zdrojů

`sources.ecb`, `sources.imf`, `sources.bis`, `service.sources`. Velký objem, ale nižší cena
selhání — chyba se většinou projeví jako prázdný strom, ne jako špatné číslo.

---

## Co se v tomhle kole doplnilo

| Testy | Co pokrývají |
|-------|--------------|
| `ConnectorParseSupportTest` (19) | SDMX XML (BIS), SDMX-JSON (IMF), CSV preview, Data360 — včetně desetinné čárky, značky `.` pro chybějící hodnotu a rozpadu na prázdný výsledek u vadného vstupu |
| `ConnectorHealthServiceTest` (6) | Sonda dostupnosti zdrojů proti lokálnímu serveru — `up`/`down`/`misconfigured` |
| `OpenAiCompletionCapTest` (5) | Strop tokenů u všech úloh a měření spotřeby |
| `LocalLlmFallbackClientTest` (8) | Failover na lokální model — dialekt requestu (`max_tokens`, žádný `reasoning_effort`), tvary base URL, klasifikace chyb, výchozí vypnutí |

Testy konektorů a sondy jsou **hermetické** — běží proti fixturám a loopback serveru, ne proti
reálným API. Sada nesmí zčervenat kvůli tomu, že má ČNB výpadek.

## Inventura oprávnění

```bash
python scripts/authz_audit.py
```

Projde všech ~404 endpointů a rozřadí je podle toho, kde se u nich vynucuje oprávnění: v
controlleru, v servisní vrstvě, jen přes rate limit, nebo nikde. Rozhodnutí, který endpoint je
veřejný záměrně, jsou v [`AUTHORIZATION.md`](AUTHORIZATION.md).

> **Ber to jako vodítko, ne jako důkaz.** Detekce v servisní vrstvě se ptá jen, jestli ta třída
> někde obsahuje kontrolu — ne jestli kontroluje právě volaná metoda. Při sestavování inventury
> vyšly tři „díry" jako falešný poplach, protože kontrolu držela služba. Než něco označíš za
> problém, otevři si to.

Průběžně to hlídá `AiEndpointExposureTest`: každý endpoint volající OpenAI musí mít strop
v `AuthRateLimitFilter`, přestavby indexů a zápis zdroje musí mít `requireAdmin()`.

Dopad na měření:

| | Před | Po |
|--|-----:|---:|
| Celkem | 44,68 % | **45,09 %** |
| balíček `connector` | 32,8 % | **40,4 %** |
| `ConnectorParseSupport` | 0 % | **59,4 %** |
| `ConnectorHealthService` | — | 96,1 % |
| `OpenAiUsageMeter` | — | 85,3 % |

Celkové číslo se zvedlo jen o 0,4 p. b. — a to je v pořádku. Cílem nebylo hnát procento nahoru,
ale zakrýt konkrétní místo, kde tichá chyba mění čísla v grafu.
