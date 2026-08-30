# LLM Query Planner Timeout Fix

Date: 2026-07-13

## Root Cause

Search V2 planner used OpenAI Chat Completions with a hardcoded 5 second request timeout. Full planner requests timed out before any model response arrived, so the query planner returned `llm_unavailable:IllegalStateException` and the deterministic fallback expanded generic tokens such as `mira`.

The failure was an HTTP request timeout, not missing configuration, not schema validation, and not JSON parsing.

## Before

- Provider: OpenAI
- Endpoint: `POST https://api.openai.com/v1/chat/completions`
- Model: `gpt-5.4-nano`
- Structured output mode: `json_object`
- Connect timeout: effectively 15000 ms
- Request timeout: 5000 ms hardcoded
- Sample count: 30
- P50: 5008 ms
- P90: 5014 ms
- P95: 5014 ms
- Max: 5054 ms
- Success rate: 0.0
- Timeout rate: 1.0
- Fallback rate: 1.0
- Average input size: 7089 chars, approx. 1773 tokens

Artifacts:
- `outputs/query_planner_latency_before.json`
- `outputs/query_planner_latency_before.csv`

## After

- Provider: OpenAI
- Endpoint: `POST https://api.openai.com/v1/chat/completions`
- Model: `gpt-5.4-nano`
- Structured output mode: `json_schema`
- Connect timeout: `SEARCH_V2_LLM_CONNECT_TIMEOUT_MS`, default 3000 ms
- Request timeout: `SEARCH_V2_LLM_REQUEST_TIMEOUT_MS`, default 12000 ms
- Reasoning effort: `SEARCH_V2_LLM_REASONING_EFFORT`, default `none`
- Sample count: 50
- P50: 1855 ms
- P90: 2180 ms
- P95: 2526 ms
- Max: 6957 ms
- Success rate: 1.0
- Timeout rate: 0.0
- Fallback rate: 0.0
- Average input size: 4904 chars, measured average 1362 prompt tokens
- System prompt size: 1943 chars

Artifacts:
- `outputs/query_planner_latency_after.json`
- `outputs/query_planner_latency_after.csv`

## Error Handling

The client now distinguishes:

- `LLM_CONNECT_TIMEOUT`
- `LLM_REQUEST_TIMEOUT`
- `LLM_RATE_LIMIT`
- `LLM_SERVER_ERROR`
- `LLM_AUTH_ERROR`
- `LLM_CLIENT_ERROR`
- `LLM_SCHEMA_ERROR`
- `LLM_PARSE_ERROR`
- `LLM_EMPTY_RESPONSE`
- `LLM_NOT_CONFIGURED`
- `LLM_UNKNOWN_ERROR`

Planner errors are surfaced in `llm_planner.error_type` and fallback state is surfaced in `fallback_trace`.

## Retry Policy

There is at most one retry, only for timeout, 429, or 5xx-style failures, and only if enough request budget remains after a short bounded backoff. No long synchronous retry loop was added.

## Fallback Safety

The deterministic fallback remains a safety net. It no longer uses generic single tokens such as `rate`, `mira`, `index`, `value`, `data`, or `series` as standalone concept drivers. The fallback trace exposes `concept_confidence` and `safe_to_search`.
