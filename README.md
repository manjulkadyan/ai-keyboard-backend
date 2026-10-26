# kb-api

Backend for the AI Keyboard Android app: Kotlin, Ktor 3, Netty, JDK 17. It has the auth chain, quota and spend limits, the error
model, a redacted access log, the OpenAPI contract and the AI endpoints `/v1/rewrite`, `/v1/polish` (SSE) and
`/v1/ai/report`.

## Run locally

```sh
AUTH_MODE=dev ./gradlew run
curl -i localhost:8080/healthz
curl -i -H 'X-Dev-Uid: dev-user-1' localhost:8080/v1/quota

# Canned model output, no Anthropic key needed:
AUTH_MODE=dev GATEWAY=fake ./gradlew run
curl -N -H 'X-Dev-Uid: u1' -H 'Content-Type: application/json' \
  -d '{"mode":"fix","locale":"en","text":"helo","client_req_id":"r1"}' localhost:8080/v1/rewrite
```

Behind a TLS-intercepting proxy, point Gradle at its truststore with `JAVA_TOOL_OPTIONS`.

## Tests

```sh
./gradlew test
```

This runs the unit and route tests, the OpenAPI contract test, the log-redaction test and `SecretScanTest`. No
network or Anthropic key is needed; AI routes use a fake gateway.

`FirestoreUsageStoreIT` runs only when `FIRESTORE_EMULATOR_HOST` is set:

```sh
gcloud emulators firestore start --host-port=localhost:8081 &
FIRESTORE_EMULATOR_HOST=localhost:8081 ./gradlew test
```

Prompt quality is measured separately by the offline eval harness (`./gradlew evalRun`). It calls the real API and
costs money, so it never runs in `test`. See [`evals/README.md`](evals/README.md).

## Layout

| Path | Contents |
|---|---|
| `src/main/kotlin/com/manjul/kbapi/` | `Main.kt` (wiring), `Config.kt` (env), `Errors.kt`, `Logging.kt`, `RequestId.kt` |
| `…/ai/` | Anthropic and fake gateways, prompt registry, rewrite/polish services, SSE relay |
| `…/auth/` | App Check and Firebase ID token verification, dev auth |
| `…/limits/` | Tiers, daily/monthly quota, per-uid and per-IP rate limits, hourly spend breaker |
| `…/report/` | Storage for `/v1/ai/report` |
| `…/routes/` | HTTP routes |
| `…/evals/` | Offline eval runner and graders |
| `prompts/` | Versioned system prompts, packaged as resources |
| `evals/` | Eval cases and docs |
| `deploy/` | Cloud Run config and deploy steps |

## Environment

| Variable | Default | Notes |
|---|---|---|
| `AUTH_MODE` | `firebase` | `dev` accepts `X-Dev-Uid` instead of App Check + ID token. Refused when `K_SERVICE` is set (Cloud Run). |
| `FIREBASE_PROJECT_ID` | `GOOGLE_CLOUD_PROJECT` | Required in firebase mode. |
| `FIREBASE_PROJECT_NUMBER` | | Required in firebase mode; App Check issuer/audience. |
| `ANTHROPIC_API_KEY` | | Secret Manager in prod. Never commit it. Unset: AI calls return 500 `internal`. |
| `GATEWAY` | `anthropic` | `fake` streams canned output; only allowed with `AUTH_MODE=dev`. |
| `REWRITE_MODEL` / `STEP_UP_MODEL` | `claude-haiku-5-5` / `claude-sonnet-5-5` | |
| `FREE_DAILY`, `PRO_DAILY`, `PRO_MONTHLY` | 10, 150, 3000 | |
| `UID_PER_MINUTE`, `IP_PER_MINUTE` | 10, 60 | |
| `TRUSTED_PROXY_HOPS` | 2 | Proxies appending to `X-Forwarded-For` before kb-api; 2 for the external HTTPS load balancer of deploy/README.md §6. The per-IP bucket keys on the entry this many from the end. |
| `HOURLY_SPEND_LIMIT_USD` | 20 | Spend breaker. |
| `AI_ENABLED` | `true` | Kill switch (503 `ai_paused`). |
| `MAX_INPUT_CHARS` | 4000 | |
| `PORT` | 8080 | |

## Contract and errors

`src/main/resources/openapi.yaml` is the API contract; `OpenApiContractTest` validates test traffic against it.
Errors are `{"error": code, ...}`: `too_long`/`bad_request` 400, `app_check`/`auth` 401, `quota` 402 (`resets_at`),
`rate_limited` 429 (`Retry-After`), `refused` 451, `ai_paused` 503, `overloaded` 503 (`Retry-After`, `retry_after`),
`internal` 500. Every response has `X-Request-Id`.

## AI calls

Prompts live in `prompts/<id>@<version>.md` (`rewrite@1`, `multi_tone@1`, `polish_translate@1`) and are packaged as
resources. Done events and variant responses carry the `prompt` ref. User text is wrapped in tags with `<`/`>` escaped.
The response is held until the first text delta, so upstream errors before it are real HTTP statuses (and refund the
quota); after it they become an SSE `error` event. A Haiku refusal before any text is retried once on
`STEP_UP_MODEL`; other models use the Messages beta server-side fallback (`fallbacksDefault`). Anthropic SDK
exceptions are mapped to an error code and their messages dropped.

## Privacy

Request and response text is never logged, stored or echoed. The access log is one JSON line
per call built from the `LogField` allow-list; exceptions are logged by class name only. `SecretScanTest` fails the
build on vendor keys or private keys anywhere in the tree.

## Deploy

Cloud Run, with Firestore, Secret Manager and an external HTTPS load balancer. See [`deploy/README.md`](deploy/README.md);
it has not been deployed yet.
