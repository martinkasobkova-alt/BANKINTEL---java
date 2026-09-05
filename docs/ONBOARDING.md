# Předání a onboarding

Tenhle dokument je pro člověka, který kód nepsal a má ho převzít. Popisuje to, **co není vidět
z kódu ani z map** — rozhodnutí, pasti a provozní chování. Architekturu popisuje
[`APP_MAP.md`](APP_MAP.md), orientaci v souborech [`CODE_MAP.md`](CODE_MAP.md), spuštění
[README](../README.md).

---

## 1. První den

```powershell
.\start-dev.ps1
```

Backend na 8080, frontend na 5173, přihlášení `admin@bankintel.local` / `admin123`.
Databáze je **embedded PostgreSQL** (zonky) — žádný Docker ani lokální Postgres není potřeba.

Co si projít v tomto pořadí:

1. [`APP_MAP.md`](APP_MAP.md) — co aplikace dělá a z čeho se skládá.
2. Klikni si appku: `/search/catalog` (hledání), `/explore` (Manager Explorer), `/my-dashboard`.
3. [`SEARCH_MAP.md`](SEARCH_MAP.md) — vyhledávání je jádro produktu i největší balík kódu.
4. [`FTS_AND_SIDECAR.md`](FTS_AND_SIDECAR.md) — až budeš sahat na index.

---

## 2. Pasti, které tě jinak čekají

### Autorizace **není** ve `SecurityConfig`

`SecurityConfig` končí na `anyRequest().permitAll()`. Filtr řetěz řeší jen *autentizaci*
(`JwtAuthFilter` naplní `SecurityContext` z cookie), ale **nic neomezuje přístup podle cesty**.

Oprávnění se vynucují až v controllerech:
- `adminAccess.requireAdmin()` — hodí 403, když uživatel není admin.
- ruční dohledání aktuálního uživatele tam, kde jde o vlastní data.

> **Nový endpoint je proto ve výchozím stavu veřejný.** Když přidáváš cokoli, co má být chráněné,
> musíš si o kontrolu explicitně říct. Tohle je nejsnáze přehlédnutelná věc v celém repu.

Který endpoint je veřejný záměrně a kde přesně kontrola sedí — [`AUTHORIZATION.md`](AUTHORIZATION.md).

### Pořadí bezpečnostních filtrů je křehké

Filtry se kotví k `UsernamePasswordAuthenticationFilter`, ne jeden k druhému — Spring Security
neumí seřadit vlastní filtr relativně k jinému vlastnímu filtru. Pořadí vložení = pořadí
vykonání (rate-limit → jwt → csrf). Když to přeházíš, appka spadne při startu.

### `OPENAI_MODEL` přebije všechny ostatní volby modelu

`OpenAiClient.modelFor()` má zastaralé pole `legacyModel` napojené na `OPENAI_MODEL`. Pokud je
tahle proměnná nastavená, **ignorují se** `OPENAI_MODEL_CHAT`, `_PLANNER` i `_RERANKER` a všechno
jede na jednom modelu. Když ladíš model a nic se neděje, koukni sem první.

### Naplánované joby běží jen na jedné instanci

Většina jobů v `BankIntelScheduler` je podmíněná `BANKINTEL_SCHEDULER_LEADER=1`. V produkci to
musí být nastavené na **právě jedné** replice. Výjimky jsou vědomé: RSS sync a
`connectorHealthProbe` běží všude (u health probe proto, že konektivita se může lišit instanci
od instance).

### Konfigurace má tři vrstvy

`BankIntelEnvVars.get()` čte v pořadí: **proměnná prostředí → system property → `.env` soubor**.
Když se hodnota „nechytá", většinou ji přebíjí něco výš v tomto pořadí.

`gradlew bootRun` navíc **natvrdo vynucuje profil `local`** (v `build.gradle.kts`). Port se mění
přes `PORT`, ne přes profil.

### Schéma se nemění z entit

`spring.jpa.hibernate.ddl-auto: validate`. Změna entity bez odpovídající Flyway migrace
(`db/migration/V1__…` až `V12__…`) shodí start aplikace. Migrace se přidávají, nikdy neupravují.

### MongoDB autokonfigurace je vypnutá

`MongoAutoConfiguration` je v `application.yml` v `exclude`. Mongo se používá přímo přes driver
v cache Manager Exploreru, ne přes Spring Data.

---

## 3. Když něco nefunguje

| Příznak | Kde hledat |
|---------|-----------|
| Hledání nevrací nic / málo | Chybí FTS index — `data/` **není** v repu, viz [README](../README.md#️-data--fts-index--nejsou-v-repozitáři) |
| Graf je prázdný u jednoho zdroje | `GET /api/health/connectors` — zdroj může být `down` nebo `misconfigured` |
| AI odpovědi jsou useknuté | `GET /api/health/ai-usage` → `truncated_responses`; zvyš `OPENAI_MAX_COMPLETION_TOKENS_CHAT` |
| AI nefunguje vůbec | Chybí `OPENAI_API_KEY`, nebo `OPENAI_COMMENTARY` je falsy — appka pak jede na heuristikách |
| AI odpovídá, ale kvalita spadla | Nejspíš běží failover na lokálním modelu — zkontroluj `calls_by_provider` na `/api/health/ai-usage` |
| Špatná čísla v grafu | Parsery v `connector/ConnectorParseSupport` — viz [`TESTING.md`](TESTING.md) |
| Aplikace nenastartuje v `prod` | `StartupSecurityValidator` odmítá výchozí `JWT_SECRET` a další insecure defaulty |

---

## 4. Jak dělat změny bezpečně

```bash
cd backend-java && ./gradlew test           # celá sada, ~4 min (zvedá embedded Postgres)
cd backend-java && ./gradlew jacocoTestReport   # + pokrytí do build/reports/jacoco/
cd frontend && npm run test                 # vitest
```

- **Změna schématu** → nová Flyway migrace, nikdy úprava existující.
- **Nový endpoint** → rozmysli si autorizaci (viz past výše), přidej do
  [`API_CONTRACT.md`](API_CONTRACT.md).
- **Nové volání OpenAI** → jde přes `OpenAiClient`, nikdy přímo. Klient řeší stropy tokenů,
  timeouty, retry, failover na lokální model a měření spotřeby. Jediné, co failover nemá, je
  `webSearch()` — tam se testuje `isOpenAiConfigured()`, ne `isConfigured()`.
- **Nový externí zdroj** → konektor implementuje `BaseConnector`, registruje se v
  `ConnectorFactory` a **přidej ho i do `ConnectorHealthService.TARGETS`**, jinak o jeho výpadku
  nebudeš vědět.

---

## 5. Co je vědomě nedodělané

- **Mobilní Expo klient** z původní Python aplikace není portovaný a portovat se neplánuje.
- **Pokrytí testy je nerovnoměrné** — 44,7 % instrukcí, s velkými nulami v CRUD a exportech.
  Změřený stav a priority jsou v [`TESTING.md`](TESTING.md).
- **Kvalita záložního modelu.** Failover na lokální model existuje (viz níže), ale je to pojistka
  proti výpadku, ne rovnocenná náhrada — české komentáře z lokálního modelu budou slabší.

---

## 6. Historie rozhodnutí

Proč search v2 vypadá, jak vypadá, co se zkoušelo a zahodilo — [`archive/`](archive/README.md).
Ty dokumenty **nepopisují dnešní stav kódu**, ale drží kontext, který jinde není.
