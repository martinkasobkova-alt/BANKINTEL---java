# BankIntel v2

**Konsolidovaná datová platforma pro bankovnictví a makroekonomiku.** Aplikace sjednocuje
veřejné ekonomické datové zdroje (ČSÚ, Eurostat, ČNB/ARAD, ECB, IMF, BIS, OECD, FRED, World Bank
Data360, akcie) do jednoho vyhledávání, náhledu grafů, dashboardů a AI asistence nad daty. Součástí
je i čtečka PDF časopisu, zprávy/podcasty a administrace.

> Backend je **Java 21 / Spring Boot 3.4**, frontend **React 19 / Vite**. Data se drží v PostgreSQL,
> fulltextový katalogový index v SQLite (FTS5) + Lucene, cache Manager Exploreru v MongoDB.

---

## Pro koho je tento dokument

Repozitář je připravený k technické revizi. Pokud se v aplikaci potřebuješ zorientovat, začni tady
a pokračuj mapami v `docs/`:

| Chci pochopit… | Otevři |
|----------------|--------|
| **Přebírám projekt — co potřebuju vědět a na co si dát pozor** | [`docs/ONBOARDING.md`](docs/ONBOARDING.md) |
| **Který endpoint je veřejný a proč** | [`docs/AUTHORIZATION.md`](docs/AUTHORIZATION.md) |
| **Celkovou architekturu a moduly** | [`docs/APP_MAP.md`](docs/APP_MAP.md) |
| **Jak funguje vyhledávání (FTS, deep-search, search-v2, sidecar, konektory)** | [`docs/SEARCH_MAP.md`](docs/SEARCH_MAP.md) |
| **Manager Explorer (procházení zdrojů → řady → graf)** | [`docs/MANAGER_EXPLORER_MAP.md`](docs/MANAGER_EXPLORER_MAP.md) |
| **FTS index + sidecar do hloubky (kde bývají chyby)** | [`docs/FTS_AND_SIDECAR.md`](docs/FTS_AND_SIDECAR.md) |
| **Provoz: dostupnost zdrojů a útrata za AI** | [sekce Monitoring](#monitoring-a-provoz) |
| **Historické audity, evaluace a plány** | [`docs/archive/`](docs/archive/README.md) — *nepopisují dnešní stav* |

---

## Architektura ve zkratce

```
                    ┌──────────────────────────────────────────────┐
  React 19 / Vite   │  frontend/  (features/, components/, lib/)    │
  (port 5173)       │  proxy /api → backend                        │
                    └───────────────┬──────────────────────────────┘
                                    │ HTTP (cookies JWT + CSRF)
                    ┌───────────────▼──────────────────────────────┐
  Spring Boot 3.4   │  backend-java/  cz.bankintel.*                │
  (port 8080)       │  controller → service → { connector | search}│
                    └───┬───────────┬───────────────┬──────────────┘
                        │           │               │
             PostgreSQL │   SQLite FTS5 + Lucene     │  externí API zdrojů
             (Flyway)   │   (katalogový index /data) │  (ČSÚ, Eurostat, ECB…)
                        │           │               │
                        │        MongoDB (manager_series_cache)   OpenAI (AI vrstva)
```

- **Backend** (`backend-java/`) — vrstvy `controller/ → service/ → connector/ | search/`. Konektory
  tahají živá data z externích API, `search/` řeší katalogové vyhledávání a náhledy.
- **Frontend** (`frontend/`) — feature-based struktura (`src/features/<modul>/`), sdílené
  komponenty v `src/components/`, integrace v `src/lib/`.
- **Perzistence** — PostgreSQL (schéma spravuje Flyway, migrace `V1__…` až `V12__…`), katalogový
  fulltext v SQLite (`classic_catalog_search.sqlite` + sidecar `search_v2_sidecar.sqlite`),
  cache Eurostat/ARAD řad pro Explorer v MongoDB.
- **AI vrstva** — `search/openai/OpenAiClient` (OpenAI Chat/Responses API). Bez klíče appka běží,
  jen AI odpovědi degradují na heuristiky.

Detailní rozpad viz [`docs/APP_MAP.md`](docs/APP_MAP.md).

---

## Rychlý start (lokální vývoj)

Předpoklady: **JDK 21**, **Node 20+**, Windows PowerShell (skript) nebo libovolný shell (ruční
spuštění). Databáze pro lokální vývoj je **embedded PostgreSQL** (zonky) — není potřeba Docker ani
externí Postgres.

### Varianta A — jedním skriptem (Windows)

```powershell
.\start-dev.ps1
```

Skript založí `backend-java/.env` z `.env.example` (pokud chybí), spustí backend (`gradlew bootRun`,
profil `local`, embedded Postgres) a frontend (`npm run dev`).

### Varianta B — ručně

```bash
# 1) backend  → http://localhost:8080
cd backend-java
cp .env.example .env          # vyplň reálné klíče jen tady (soubor je v .gitignore)
./gradlew bootRun             # profil 'local' je vynucený v build.gradle.kts

# 2) frontend → http://localhost:5173
cd ../frontend
npm install
npm run dev
```

- **Přihlášení (dev seed):** `admin@bankintel.local` / `admin123`
- **Health:** `GET http://localhost:8080/actuator/health` a `GET /health` (viz též
  [Monitoring a provoz](#monitoring-a-provoz))
- Frontend proxuje `/api` na backend; pokud běží backend na jiném portu, nastav
  `REACT_APP_PROXY_TARGET` (viz `frontend/vite.config`).
- Port backendu je `PORT` (default `8080`). Pro jiný port: `PORT=8081 ./gradlew bootRun`.

### Testy

```bash
cd backend-java && ./gradlew test              # JUnit 5, ~4 min (zvedá embedded Postgres)
cd backend-java && ./gradlew jacocoTestReport  # + pokrytí do build/reports/jacoco/
cd frontend && npm run test                    # vitest
```

Změřený stav pokrytí a priority, kudy ho doplňovat, jsou v [`docs/TESTING.md`](docs/TESTING.md).

---

## Konfigurace (`.env`)

Reálné klíče patří **jen** do `backend-java/.env` (v `.gitignore`, do repa se nikdy necommitne).
Šablona se všemi proměnnými je v [`backend-java/.env.example`](backend-java/.env.example). Nejdůležitější:

| Proměnná | Účel |
|----------|------|
| `JWT_SECRET` | podpis JWT cookies (v produkci generuje Render) |
| `BANKINTEL_DATA_DIR`, `CATALOG_SEARCH_INDEX_DIR` | kořen dat a katalogových indexů |
| `CLASSIC_CATALOG_FTS_DB` | cesta k SQLite FTS indexu |
| `SEARCH_ENGINE_VERSION` (`v2`), `SEARCH_CATALOG_INDEX` (`sidecar`) | volba search enginu |
| `SEARCH_CATALOG_SIDECAR_DIR`, `SEARCH_CATALOG_SIDECAR_FTS_DB` | sidecar index |
| `FTS_INDEX_SNAPSHOT_URL` | volitelně: stažení předpřipraveného FTS indexu při prvním startu |
| `OPENAI_API_KEY` (+ `OPENAI_MODEL_*`, `OPENAI_COMMENTARY`) | AI vyhledávání, vysvětlení, plánovač |
| `OPENAI_MAX_COMPLETION_TOKENS_CHAT` (4000), `…_PLANNER` (900), `…_RERANKER` (3000), `OPENAI_MAX_OUTPUT_TOKENS_WEB_SEARCH` (4000) | strop účtovaného výstupu na jedno volání |
| `ARAD_API_KEY`, `FRED_API_KEY`, `IMF_API_KEY`, `GIE_API_KEY`, `ALPHAVANTAGE_API_KEY` | klíče zdrojů |

`.env` se do Spring `Environment` načítá přes `config/BankIntelEnvEnvironmentPostProcessor`.

---

## ⚠️ Data & FTS index — NEJSOU v repozitáři

Katalogový fulltextový index a per-source JSONL jsou **řádově gigabajty** a záměrně **nejsou**
verzované (adresář `data/` je v `.gitignore`). Bez indexu se aplikace spustí, ale katalogové
vyhledávání běží v omezeném/prázdném režimu.

Jak index získat:
1. **Snapshot** — nastav `FTS_INDEX_SNAPSHOT_URL` na `.gz`/`.zip` s předpřipraveným
   `classic_catalog_search.sqlite`; `search/FtsIndexBootstrapRunner` ho stáhne při prvním startu.
2. **Vlastní build** — skripty v `scripts/` a `tools/` (indexace a evaluace); postup a struktura
   indexu jsou v [`docs/FTS_AND_SIDECAR.md`](docs/FTS_AND_SIDECAR.md) a (historicky)
   [`docs/archive/search_v2_fts_index_audit.md`](docs/archive/search_v2_fts_index_audit.md).

---

## Monitoring a provoz

Dvě věci, které dřív selhávaly potichu — výpadek externího zdroje a útrata za AI — mají teď
vlastní endpointy.

### Dostupnost datových zdrojů

`BankIntelScheduler.connectorHealthProbe()` každou hodinu obvolá základní URL všech 11 externích
zdrojů (ARAD, ČSÚ, Eurostat, ECB, IMF, BIS, OECD, Data360, FRED, Pink Sheet, TradingEconomics).

| Endpoint | Co dělá |
|----------|---------|
| `GET /api/health/connectors` | poslední známý stav, bez síťového provozu — vhodné pro uptime monitoring |
| `POST /api/health/connectors/probe` | vynutí okamžitou kontrolu (**jen admin**) |

Stavy: `up` (zdroj odpovídá), `down` (5xx, timeout nebo nedostupný host), `misconfigured`
(chybí API klíč — konfigurační chyba, ne výpadek). Celkový `status` je `ok` / `degraded` / `down`.
Nedostupný zdroj se navíc loguje jako `WARN`, takže na něj jde navěsit alerting.

> Kontroluje se **dosažitelnost**, ne správnost dat. Odpověď pod HTTP 500 se počítá jako `up` —
> datová API běžně vrací na holou base URL 400/404 a to pořád dokazuje, že DNS, TLS i služba žijí.
> Sonda **není** leader-gated: konektivita se může lišit instanci od instance.

### Útrata za AI

Každé volání OpenAI posílá `max_completion_tokens` (dřív ho posílal jen planner a reranker, takže
chat syntéza běžela bez stropu). Limity se ladí proměnnými z tabulky výše.

`GET /api/health/ai-usage` vrací spotřebu tokenů po úlohách (`chat`, `planner`, `reranker`,
`web_search`) od startu procesu. Když strop odpověď skutečně uřízne, `OpenAiUsageMeter` to
zaloguje jako `WARN` a započítá do `truncated_responses` — podle toho se pozná, že je limit
nastavený moc nízko.

### Záložní AI model při výpadku OpenAI

Když OpenAI odpoví 5xx, vyprší timeout, dojde limit nebo neprojde klíč, volání se **automaticky
zopakuje na lokálním modelu** (Ollama nebo vLLM — cokoli s OpenAI-kompatibilním
`/v1/chat/completions`). Ve výchozím stavu je to **vypnuté**.

| Proměnná | Význam |
|----------|--------|
| `BANKINTEL_LLM_FALLBACK_ENABLED` | `1` zapne failover (jinak se nic z tohohle nespustí) |
| `BANKINTEL_LLM_FALLBACK_BASE_URL` | např. `http://localhost:11434` nebo `.../v1` — obojí projde |
| `BANKINTEL_LLM_FALLBACK_MODEL_CHAT` | model pro syntézu; slouží i jako záloha pro planner/reranker |
| `BANKINTEL_LLM_FALLBACK_MODEL_PLANNER`, `…_RERANKER` | volitelně jiný (menší) model |
| `BANKINTEL_LLM_FALLBACK_API_KEY` | volitelné; Ollama ho ignoruje, vLLM ho může vyžadovat |
| `BANKINTEL_LLM_FALLBACK_REQUEST_TIMEOUT_MS` | vlastní časový budget failoveru (default 30 s) |

Co je potřeba vědět, než to zapnete:

- **Failover řeší dostupnost, ne kvalitu.** Lokální model bude na českých komentářích slabší.
  V `calls_by_provider` na `/api/health/ai-usage` je vidět, kolik volání šlo kudy — skok
  u `local` znamená, že OpenAI zlobí.
- **Failover se spustí jen u výpadkových chyb** (timeout, 5xx, rate limit, auth, prázdná odpověď).
  Chyba schématu nebo 4xx je naše vada a na jiném modelu dopadne stejně, takže se nepřepíná.
- **Striktní `json_schema` se degraduje na `json_object`** — lokální runtimy vynucení schématu
  nezvládají spolehlivě. Volající dostane JSON, ale bez záruky tvaru.
- **Latence u planneru naroste.** Failover má vlastní budget místo zbytku po primárním pokusu;
  u vyhledávání to v nejhorším případě prodlouží odezvu. Je to vědomá výměna za dostupnost.
- **Web search zálohu nemá** — hostovaná služba OpenAI nemá lokální ekvivalent.
- Bez `OPENAI_API_KEY`, ale se zapnutým failoverem, jede AI vrstva **celá** na lokálním modelu.

---

## Nasazení

- **Docker** — `backend-java/Dockerfile` (multi-stage: build `gradle:8.14-jdk21` → runtime
  `eclipse-temurin:21-jre-alpine`, non-root, `PORT=8080`, healthcheck `/actuator/health`).
- **Render** — [`render.yaml`](render.yaml) (Blueprint): web service + managed PostgreSQL + 20 GB
  perzistentní disk `/data` pro FTS index; profil `prod`, `COOKIE_SECURE=true`, generovaný
  `JWT_SECRET`. Nočními joby (viz níže) obsluhuje jedna instance (`BANKINTEL_SCHEDULER_LEADER=1`).
- **docker-compose.yml** — lokální varianta s Postgresem.

---

## Struktura repozitáře

```
BankIntel-v2/
├─ backend-java/            # Spring Boot 3.4 (Java 21) — cz.bankintel.*
│  ├─ src/main/java/cz/bankintel/
│  │  ├─ controller/        # REST /api/** (auth, catalog, chartagent, explore, sources, …)
│  │  ├─ service/           # byznys logika po doménách
│  │  ├─ connector/         # živá data z externích API (BaseConnector + ConnectorFactory)
│  │  ├─ search/            # katalogové hledání: classic FTS, deep-search, v2/, openai/
│  │  ├─ explore/           # Manager Explorer + manager_series_cache (Mongo)
│  │  ├─ security/          # JWT + CSRF (JwtAuthFilter, CsrfFilter, AuthCookieService)
│  │  ├─ config/            # profily, embedded Postgres, scheduler, .env loader
│  │  ├─ domain/            # JPA entity + DTO
│  │  └─ repository/        # Spring Data JPA
│  ├─ src/main/resources/db/migration/   # Flyway V1__…V12__
│  └─ Dockerfile, build.gradle.kts
├─ frontend/                # React 19 + Vite
│  └─ src/features/<modul>/ # ai-search, manager-explorer, dashboard, arad-chart, admin, …
├─ docs/                    # aktuální mapy systému (viz tabulka nahoře)
│  └─ archive/              # historické audity a plány — NE popis dnešního stavu
├─ scripts/, tools/         # indexace a evaluace vyhledávání
├─ render.yaml, docker-compose.yml, start-dev.ps1
└─ .env.example (šablona; reálné .env se necommituje)
```

---

## Stav aplikace

Jádrové cesty jsou funkční a živě ověřené: autentizace + admin, katalogové vyhledávání (classic +
AI deep-search + search-v2), živý náhled 9+ zdrojů, dashboardy a widgety, Manager Explorer, AI nad
grafem/výsledky, čtečka PDF časopisu, sync ARAD/FRED/Eurostat/ČSÚ. Některé okrajové moduly jsou
záměrně jen částečné — konkrétní stav je v `docs/`, historie rozhodnutí v
[`docs/archive/`](docs/archive/README.md).

---

## Bezpečnostní poznámka

`.env`, klíče a lokální `data/` se do repozitáře nikdy necommitují (viz `.gitignore`). `.env.example`
obsahuje jen zástupné hodnoty. Před nasazením nastav vlastní `JWT_SECRET` a produkční profil `prod`
(fail-fast `StartupSecurityValidator` odmítne insecure defaulty).
