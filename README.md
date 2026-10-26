# kb-api

Backend for the AI Keyboard (TRD §5): Kotlin, Ktor 3, Netty. AI rewrite and polish arrive in Task 7; this
scaffold has the auth chain, error model, redacted access log and the OpenAPI contract.

## Run locally

```sh
AUTH_MODE=dev ./gradlew run
curl -i localhost:8080/healthz
curl -i -H 'X-Dev-Uid: dev-user-1' localhost:8080/v1/quota
```

In the sandbox, prefix Gradle with the truststore `JAVA_TOOL_OPTIONS` (see the Android repo's plan).

## Environment

| Variable | Default | Notes |
|---|---|---|
| `AUTH_MODE` | `firebase` | `dev` accepts `X-Dev-Uid` instead of App Check + ID token. Refused when `K_SERVICE` is set (Cloud Run). |
| `FIREBASE_PROJECT_ID` | `GOOGLE_CLOUD_PROJECT` | Required in firebase mode. |
| `FIREBASE_PROJECT_NUMBER` | | Required in firebase mode; App Check issuer/audience. |
| `ANTHROPIC_API_KEY` | | Secret Manager in prod. Never commit it. |
| `REWRITE_MODEL` / `STEP_UP_MODEL` | `claude-haiku-5-5` / `claude-sonnet-5-5` | |
| `FREE_DAILY`, `PRO_DAILY`, `PRO_MONTHLY` | 10, 150, 3000 | |
| `UID_PER_MINUTE`, `IP_PER_MINUTE` | 10, 60 | |
| `HOURLY_SPEND_LIMIT_USD` | 20 | Spend breaker. |
| `AI_ENABLED` | `true` | Kill switch (503 `ai_paused`). |
| `MAX_INPUT_CHARS` | 4000 | |
| `PORT` | 8080 | |

## Contract and errors

`src/main/resources/openapi.yaml` is the API contract; `OpenApiContractTest` validates test traffic against it.
Errors are `{"error": code, ...}`: `too_long`/`bad_request` 400, `app_check`/`auth` 401, `quota` 402 (`resets_at`),
`rate_limited` 429 (`Retry-After`), `refused` 451, `ai_paused` 503, `internal` 500. Every response has `X-Request-Id`.

## Privacy

Request and response text is never logged, stored or echoed (TRD §5.4, PRD PC-10). The access log is one JSON line
per call built from the `LogField` allow-list; exceptions are logged by class name only. `SecretScanTest` fails the
build on vendor keys or private keys anywhere in the tree.

## Deploy

See `deploy/README.md` (not run in Sprint 3).
