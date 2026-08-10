# OpenAI Responses API Migration Plan

Date: 2026-07-13

This change intentionally keeps Search V2 on Chat Completions. The current fix stabilizes timeout configuration, strict structured output, diagnostics, prompt size, and fallback behavior first.

## Goal

Evaluate whether moving Search V2 planner calls from Chat Completions to Responses API improves latency, reliability, tracing, or future tool support without changing search semantics.

## Proposed Feature Flag

Add a disabled-by-default flag:

```text
SEARCH_V2_LLM_ENDPOINT=chat_completions
```

Future values:

```text
chat_completions
responses
```

## Migration Steps

1. Add a second client path behind `SEARCH_V2_LLM_ENDPOINT=responses`.
2. Keep the same JSON Schema and planner prompt.
3. Keep the same model unless a separate eval explicitly changes it.
4. Preserve the current `llm_planner` trace fields so the UI and eval tooling do not need a second contract.
5. Run the same planner latency benchmark and planner matrix:
   - `outputs/query_planner_latency_after.json`
   - `outputs/query_planner_eval.json`
6. Run Search V2 holdout, exact entity, sidecar smoke, backend tests, and frontend check.
7. Compare:
   - P50/P90/P95/max latency
   - timeout rate
   - schema compliance
   - concept accuracy
   - geo accuracy
   - source-family routing accuracy
   - Search V2 regression metrics

## Acceptance

Responses API should only become default if it is at least as good as Chat Completions on:

- planner success rate
- timeout rate
- schema compliance
- concept and geo accuracy
- source routing
- latency
- operational trace clarity

## Rollback

Set:

```text
SEARCH_V2_LLM_ENDPOINT=chat_completions
```

No schema or planner result format should need rollback if the Responses API adapter preserves the same internal `CompletionResult` contract.
