# Search V2 FTS Index Audit

- FTS DB: `C:\Bankoapp-main\BankIntel-v2\data\catalog_search_indexes\classic_catalog_search.sqlite`
- FTS DB size MB: `9103.25`
- Duration seconds: `15.41`
- Sampling: `{'jsonl_sample_limit_per_source': 20000, 'quality_stats_are_sampled': True, 'sqlite_counts_are_exact_when_available': True}`

## Current Legacy FTS Observations

- Legacy raw Search V2 uses bm25(catalog_fts) without per-field weights; Search V1 applies an extra scoring pipeline after retrieval. Sidecar FTS uses separate canonical fields and explicit bm25 weights.
- The legacy index stores a serialized row JSON next to FTS fields; Search V2 raw retrieval does not expose canonical title/concept fields.
- Metadata coverage varies by source; missing geo/unit/frequency makes hard constraints and chart-ready defaults less reliable.

## Source Coverage

| Source | SQLite rows | Sample rows | No desc | No geo | No unit | No freq | No concepts | Code titles | Duplicates sample |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| arad | 9375 | 894 | 800 | 892 | 742 | 894 | 111 | 0 | 0 |
| bis | 850 | 34 | 30 | 30 | 30 | 30 | 14 | 1 | 0 |
| commodities | None | 46 | 46 | 46 | 0 | 0 | 0 | 0 | 0 |
| csu | 1625 | 1648 | 852 | 1648 | 1648 | 26 | 905 | 0 | 0 |
| data360 | 7785 | 10310 | 2742 | 10310 | 1108 | 964 | 5646 | 35 | 0 |
| ecb2 | 424536 | 180 | 67 | 124 | 126 | 126 | 29 | 0 | 0 |
| eurostat | 5854 | 7797 | 4270 | 7373 | 7380 | 7390 | 4088 | 0 | 0 |
| fred | 250064 | 10859 | 7498 | 10858 | 1 | 1 | 88 | 2 | 0 |
| imf | 19464 | 20000 | 9577 | 20000 | 20000 | 0 | 7908 | 133 | 0 |
| oecd4 | 995 | 991 | 110 | 986 | 986 | 986 | 231 | 1 | 0 |
| worldbank | None | 20000 | 0 | 0 | 20000 | 20000 | 20000 | 0 | 50 |
| yahoo_finance | None | 10 | 0 | 10 | 0 | 10 | 10 | 0 | 0 |

## Generated Files

- `outputs/search_v2_fts_index_audit.json`
- `outputs/search_v2_fts_index_audit.csv`
