# Oprávnění a veřejné endpointy

> **Přečti si to dřív, než přidáš endpoint.** Ve výchozím stavu je nový endpoint **veřejný** a nic
> tě na to neupozorní.

## Jak to funguje

`SecurityConfig` končí na `anyRequest().permitAll()`. Filtry řeší jen **autentizaci** —
`JwtAuthFilter` naplní `SecurityContext` z cookie, `AuthRateLimitFilter` omezí frekvenci. Cestu
podle URL **nikdo neomezuje**.

Oprávnění se proto vynucují až v aplikačním kódu, a to na dvou různých místech:

| Kde | Jak | Kdy se to používá |
|-----|-----|-------------------|
| Controller | `adminAccess.requireAdmin()`, `currentUser.requireUserEntity()` | většina |
| Servisní vrstva | totéž uvnitř service metody | `AlphaVantageCatalogService`, `CatalogAvailabilityService` a dalších ~11 |

**To je ta past.** Kontrola může být na obou místech, takže z controlleru samotného nepoznáš, jestli
endpoint chráněný je. Zapomenutá kontrola se neprojeví chybou — endpoint prostě mlčky nekontroluje
nic.

### Konvence pro nový kód

Kontrolu piš **do controlleru**, hned prvním řádkem metody. Ne proto, že by servisní vrstva byla
špatně, ale aby šlo oprávnění přečíst na jednom místě. Když ji z nějakého důvodu musíš dát do
služby, napiš to do javadocu controlleru.

## Změřený stav (2026-08-22)

Ze 404 endpointů:

| Kde sedí kontrola | Počet |
|-------------------|------:|
| controller | 206 |
| servisní vrstva | 13 |
| jen rate limit | 20 |
| **žádná** | **165** |

Těch 165 bez kontroly je **z drtivé většiny v pořádku** — jde o veřejné čtení katalogu, což je
záměr produktu. Problém nikdy nebyl v počtu, ale v tom, že to z kódu nešlo rozeznat. Proto ten
zbytek dokumentu.

## Veřejné záměrně

Tyhle endpointy **mají** být dostupné bez přihlášení. Je to výkladní skříň produktu — návštěvník
si musí projít katalog a vidět graf dřív, než se rozhodne registrovat.

- `GET /api/catalog/*` — vyhledávání, našeptávač, status, náhledy
- `POST /api/catalog/search`, `/search/multi`, `/preview` — POST jen kvůli tělu dotazu, jde o čtení
- `GET /api/{fred,eurostat,ecb,ecb2,imf,bis,oecd3,data360,arad,alphavantage}/catalog/**` — stromy zdrojů
- `POST /api/auth/{login,register,logout,refresh,forgot-password,reset-password,verify-email,resend-verification}` — nemohou vyžadovat přihlášení; brzdou je rate limit
- `GET /api/health`, `/api/health/connectors`, `/api/health/ai-usage` — pro uptime monitoring
- `POST /api/bug-reports` — hlášení chyby musí jít podat i bez účtu

## Veřejné, ale stojí peníze

Volají OpenAI. Zůstávají veřejné (jsou to akviziční funkce), ale **každý má strop v
`AuthRateLimitFilter`** — jinak by vaši útratu za tokeny mohl utrácet kdokoli.

| Endpoint | Limit / min / IP |
|----------|-----------------:|
| `/api/catalog/deep-search`, `/search-v2`, `/search-v2/evaluate` | 8 |
| `/api/catalog/deep-search/{followup,results-chat,results-intent,source-route}` | 8 |
| `/api/catalog/{explain-series,explain-series/ask,related-series,source-route}` | 8 |
| `/api/explore/{sector,sector/refine,summarize,summarize/followup}` | 8 |
| `/api/magazines/ai/{chat,search}` | 8 |
| `/api/chart-agent/ask` | 8 |
| `/api/explore/{query-understanding,related-suggestions,country-suggestions,manager/analysis-plan}` | 20 |
| `/api/chart-agent/intent` | 20 |

Nižší limit mají drahé syntézy, vyšší lehčí pomocné volání, která se v jednom uživatelském toku
volají několikrát.

> **Přidáváš endpoint, který volá `OpenAiClient`?** Buď vyžaduj přihlášení, nebo ho zapiš do
> `AuthRateLimitFilter.limitForPath()`. Hlídá to `AiEndpointExposureTest`.

## Admin-only

Drahé nebo destruktivní operace. Vynuceno přes `adminAccess.requireAdmin()`.

- `POST /api/catalog/search-v2/sidecar/{rebuild,optimize}`, `/vector/rebuild` — přestavby indexů
- `POST /api/{arad,eurostat}/catalog/add-source`, Alpha Vantage totéž ve službě — zápis do sdílené
  tabulky zdrojů
- `GET /api/arad/catalog/live-check`
- `POST /api/health/connectors/probe`
- `/api/admin/**`

## Co se opravilo 2026-08-22

| Endpoint | Bylo | Je |
|----------|------|-----|
| `POST /api/catalog/search-v2/sidecar/rebuild` | bez kontroly kdekoli | admin |
| `POST /api/catalog/search-v2/sidecar/optimize` | bez kontroly kdekoli | admin |
| `POST /api/catalog/search-v2/vector/rebuild` | bez kontroly kdekoli | admin |
| `POST /api/eurostat/catalog/add-source` | bez kontroly kdekoli | admin |
| 19 AI endpointů | bez stropu | rate limit 8 nebo 20 / min / IP |

Přestavby indexů nevolá žádné UI, takže je zamčení nijak neovlivnilo. **Eurostat `add-source` UI
volá** — tlačítko na `EurostatCatalogPage` teď nepřihlášenému nebo neadminovi vrátí 403, stejně
jako už dřív vracelo u ARAD. Pokud to má být jinak, je to tady k revizi.

## Co zůstalo k rozhodnutí

Tyhle zápisy nemají kontrolu a nejsou to čtení. Nechal jsem je být, protože u nich neumím
rozhodnout, jestli jde o záměr:

- `POST /api/{arad,eurostat,oecd3}/catalog/refresh` — přenačte katalog zdroje. Volá to UI stránka
  katalogu, takže zamčení by změnilo chování; zároveň jde o operaci, kterou lze spouštět opakovaně.
- `POST /api/sources/{id}/…` v `SourcesController` — stojí za projití po jednom.
- `POST /api/formulas/validate`, `/api/imf/catalog/validate`, `/api/stocks/search`,
  `/api/data360/catalog/metadata` — validace a lookupy, pravděpodobně neškodné.

## Jak si to ověřit

`AiEndpointExposureTest` hlídá, že AI endpointy mají strop a že admin guardy nezmizely. Kompletní
inventuru si můžeš přegenerovat — postup je v [`TESTING.md`](TESTING.md#inventura-oprávnění).
