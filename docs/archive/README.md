# Archiv — historické dokumenty

Tyto soubory jsou **záznamem o tom, jak se k dnešnímu stavu došlo**, ne popisem toho, jak systém
funguje teď. Jde o audity, evaluace, diagnostiky a plány, které svůj účel splnily.

**Nepoužívejte je jako zdroj pravdy.** Popisují stav kódu v okamžiku svého vzniku a od té doby se
implementace posunula. Čísla, cesty k souborům a názvy tříd v nich mohou být zastaralé.

Aktuální popis systému je v nadřazené složce [`docs/`](../):

| Dokument | Co popisuje |
| --- | --- |
| [`ONBOARDING.md`](../ONBOARDING.md) | Předání projektu — pasti a provozní chování |
| [`APP_MAP.md`](../APP_MAP.md) | Mapa aplikace jako celku |
| [`CODE_MAP.md`](../CODE_MAP.md) | Orientace v kódu + stav portu |
| [`API_CONTRACT.md`](../API_CONTRACT.md) | Kontrakt backendového API |
| [`TESTING.md`](../TESTING.md) | Změřené pokrytí a priority testů |
| [`SEARCH_MAP.md`](../SEARCH_MAP.md) | Vyhledávací vrstva |
| [`MANAGER_EXPLORER_MAP.md`](../MANAGER_EXPLORER_MAP.md) | Manager Explorer |
| [`FTS_AND_SIDECAR.md`](../FTS_AND_SIDECAR.md) | Fulltext index a sidecar |
| [`MIGRATION_MAP.md`](../MIGRATION_MAP.md) | Původ funkcí v původní Python aplikaci |
| [`derived_real_wages_methodology.md`](../derived_real_wages_methodology.md) | Metodika odvozených reálných mezd |
| [`search_v2_exact_entity_architecture.md`](../search_v2_exact_entity_architecture.md) | Architektura exact-entity vyhledávání |
| [`search_v2_geo_constraint_architecture.md`](../search_v2_geo_constraint_architecture.md) | Architektura geo omezení |

## Proč se to nesmazalo

Archiv drží kontext k rozhodnutím, která nejsou nikde jinde zapsaná — proč search v2 vypadá,
jak vypadá, které varianty se zkoušely a proč se zahodily. Pro nového člena týmu je to při
nastupování cenné; jako návod k dnešnímu kódu je to zavádějící. Proto oddělené, ne smazané.
