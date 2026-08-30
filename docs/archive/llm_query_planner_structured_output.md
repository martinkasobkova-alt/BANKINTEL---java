# LLM Query Planner Structured Output

Date: 2026-07-13

## Contract

Search V2 planner now uses Chat Completions strict JSON Schema output instead of plain JSON mode.

The schema requires:

- `normalized_query`
- `intent`
- `required_concepts`
- `measure_types`
- `geographies`
- `geo_memberships`
- `catalog_families`
- `preferred_sources`
- `excluded_sources`
- `query_variants`
- `confidence`
- `clarification_required`
- `clarification_question`

`additionalProperties` is disabled at the root and for query variants.

## Planner Scope

The planner only decides:

- required concepts
- measure types
- geography
- catalog families
- source routing preferences
- query variants
- clarification need

It does not receive or return catalog records or data values.

## Prompt Reduction

The old prompt included a full JSON example and longer procedural instructions. The new prompt relies on the API schema and contains only operational planning rules. The source capability context was also compacted by removing long notes and keeping source, catalog family, and entity type capability data.

Measured request size:

- Before average: 7089 chars, approx. 1773 tokens
- After average: 4904 chars, measured average 1362 prompt tokens
- System prompt: 1943 chars after compaction

## Matrix Results

Artifact:

- `outputs/query_planner_eval.json`
- `outputs/query_planner_eval.csv`
- `outputs/interest_rate_austria_llm_output_valid.json`

Metrics:

- Planner success rate: 1.0
- Timeout rate: 0.0
- Fallback rate: 0.0
- Schema compliance: 1.0
- Concept accuracy: 1.0
- Geo accuracy: 1.0
- Source-family routing accuracy: 1.0
- P50: 1881 ms
- P90: 2635 ms
- P95: 2725 ms
- Max: 2725 ms

Covered queries:

- `urokova mira Rakousko`
- `mira nezamestnanosti Rakousko`
- `mira inflace Rakousko`
- `hypotecni sazby Nemecko`
- `sazby vkladu Francie`
- `vynos statniho dluhopisu Italie`
- `urokove sazby Polsko`
- `urokove sazby USA`
- `inflace Rusko`
- `Nasdaq-100`
- `EUR/USD`
- `HDP Polsko`
- `prumyslova vyroba Nemecko`
- `cena zlata`
- `ROE bank Cesko`

## Example

For `urokova mira Rakousko`, the valid LLM structured output contains:

- `required_concepts`: `interest_rate`
- `geographies`: `AT`
- `geo_memberships`: `EU`, `euro_area`, `OECD`
- `catalog_families`: includes `interest_rates`
- `preferred_sources`: includes `ecb2`

The final LLM output sets `clarification_required=false`; no unemployment sibling concept appears in required concepts or variants.
