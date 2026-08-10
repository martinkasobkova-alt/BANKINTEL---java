# API contract — parity checklist

The Java backend must expose **identical** HTTP contracts to the FastAPI app so the lean frontend (and later mobile) can swap backend URL only.

Reference: `../Bankoapp-main/Bankoapp-main/backend/routes/` + `backend/models.py`

## Global rules

| Topic | Python behaviour | Java must match |
|-------|------------------|-----------------|
| Base path | `/api/...` | same |
| Auth | JWT in `access_token` + `refresh_token` httpOnly cookies | same cookie names |
| CSRF | Double-submit cookie `csrf_token` + header | TODO |
| Mobile | Header `X-Bankoapp-Client: mobile` → tokens in JSON body | same |
| Errors | `{ "detail": "..." }` or `{ "detail": { "message", "code" } }` | same shape |
| SSE | `text/event-stream`, named events | same event names |
| Mongo IDs | String field `id` on documents (not `_id`) | query by `id` |
| Field names | snake_case in JSON | `@JsonProperty` where needed |

## Health

| Method | Path | Status |
|--------|------|--------|
| GET | `/health` | [x] Java |

## Auth (`/api/auth`)

| Method | Path | Status |
|--------|------|--------|
| POST | `/login` | [x] |
| GET | `/me` | [x] |
| POST | `/logout` | [x] |
| POST | `/register` | [ ] |
| POST | `/verify-email` | [ ] |
| GET | `/verify-email?token=` | [ ] |
| POST | `/resend-verification` | [ ] |
| POST | `/forgot-password` | [ ] |
| POST | `/reset-password` | [ ] |
| POST | `/refresh` | [ ] |

## Catalog / AI search (`/api/catalog`) — Java rewrite

| Area | Key endpoints | Status |
|------|---------------|--------|
| Deep search | POST `/deep-search`, SSE streams | [ ] |
| Classic | GET `/search`, POST `/search` | [ ] |
| Suggest | GET `/suggest` | [ ] |
| Preview | POST `/preview`, `/preview/batch` | [ ] |
| Follow-up | POST `/follow-up` | [ ] |
| Warmup | GET `/warmup`, `/warmup/status` | [ ] |

Full endpoint list: grep `@router` in `catalog_*_routes.py` during each sprint.

## Explore (`/api/explore`)

All routes from `explore_routes.py` — port with integration tests comparing JSON shape to Python golden files.

## Generating OpenAPI from reference (one-time)

From the **original** backend (do not commit output to original repo):

```powershell
cd ..\Bankoapp-main\backend
.\.venv\Scripts\python -c "from server import app; import json; open('../../BankIntel-v2/docs/openapi-reference.json','w').write(json.dumps(app.openapi(), indent=2))"
```

Use `openapi-reference.json` for contract tests in Java (`springdoc` + snapshot tests).

## Parity verification

For each ported controller:

1. Capture request/response from Python (pytest or manual)
2. Same request against Java
3. Assert JSON equality (ignore volatile fields: timestamps, request IDs)

Store golden files under `backend-java/src/test/resources/parity/`.
