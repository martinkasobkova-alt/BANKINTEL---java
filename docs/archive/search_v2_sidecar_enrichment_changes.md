# Search V2 Sidecar Enrichment Changes

- Candidate funnel renamed to `retrieved_raw -> deduplicated_unique -> after_hard_constraints -> after_source_balancing -> after_candidate_limit`.
- Geo hard constraints are applied to raw candidates before re-running dedupe/source balancing.
- Blank-geo dimension-selectable sources now include Eurostat, ECB, BIS, IMF, OECD, Data360/World Bank; local ARAD/CSU remain fixed-geo protected.
- Sidecar documents now carry `industry_sector`, `nominal_real`, `dataset_family`, and `catalog_family`.
- ARAD policy-rate titles prefer the official indicator title over contradictory generated labels such as bond-yield labels.
- Taxonomy now contains core inflation, policy rates, industrial production, automotive production, and equity market price concepts.
- Bank-profit query expansion now targets net income / income-statement evidence instead of expanding to ROE.
- Deterministic fallback reranker now penalizes canonical semantic conflicts such as core/headline, real/nominal, policy/lending, market price/reserves, and equity/macro mismatches.
- Search V2 can route stock/equity intents to the separate `stocks` adapter without enabling semantic retrieval.