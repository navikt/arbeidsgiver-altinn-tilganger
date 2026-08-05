# Specification: Harden Texas auth client against upstream IdP outages

## Background

On 2026-08-05, ID-porten was hit by a DDoS which cascaded into elevated latency at
Maskinporten. Trace `2b9c85` (`Trace-2b9c85-2026-08-05 10_20_06.json` at repo root)
shows a single `POST /altinn-tilganger` taking **13.65 s** end-to-end, all of it
spent looping on `texas /api/v1/token`:

| Attempt | Offset (ms) | Duration (ms) | Result |
|--------:|------------:|--------------:|--------|
| 1 | 97.7  | 1333 | 500 – `Request failed after 3 retries … maskinporten.no/token … operation timed out` |
| 2 | 1692.4 | 3822 | same |
| 3 | 5769.0 | 3787 | same |
| 4 | 9811.0 | 3820 | same |

Two nested retry cycles multiplied each other:

- **Texas → Maskinporten**: 3 retries internal to texas.
- **arbeidsgiver-altinn-tilganger → Texas**: `retryOnServerErrors(3)` in
  `defaultHttpClient` (`Http.kt:28`) — 4 attempts total against a texas endpoint
  that had already exhausted its own retries.

The failure never surfaced:

- `TexasAuthClientPlugin.onRequest` (`Texas.kt:235`) threw a generic `Exception`.
- `Altinn3ClientImpl.resourceOwner_AuthorizedParties` (`Altinn3Client.kt:80`)
  wrapped the call in `runCatching { … }`, converting the exception to
  `Result.failure`.
- `AltinnService.hentTilgangerFraAltinn` (`AltinnService.kt:57-70`) folded
  `onFailure = { emptyList() }` — silently returning an empty parties list.
- The client got HTTP 200 with an empty result. No ERROR log line was written,
  so no alert fired.

## Scope

Three targeted changes:

1. Add a `customizeRetry` override block to `defaultHttpClient` and use it in
   `AuthClient` to disable server-error retries against texas.
2. Install an explicit `HttpTimeout` on the texas `AuthClient` with **request,
   connect, and socket all set to 1000 ms** ("across the board" = all three
   timeout values, not all clients). Other `defaultHttpClient` callers are
   **not** touched.
3. Emit `log.error(...)` in `AltinnService` when the Altinn call returns
   `Result.failure`, so the graceful fallback still produces an alertable log
   line.

Out of scope: alert rules (nais/PrometheusRule), retry-jitter tuning, changes
to the Altinn 3 client's timeout (`Altinn3Client.kt:66-68` stays at
`requestTimeoutMillis = 60_000`), changes to texas itself.

## Changes

### 1. `AuthClient` uses a client that overrides the retry policy

Files:
- `src/main/kotlin/no/nav/fager/infrastruktur/Http.kt`
- `src/main/kotlin/no/nav/fager/texas/Texas.kt`

Follow the existing `customizeLogging` / `customizeMetrics` pattern in
`Http.kt`: add a `customizeRetry` block, invoked at the **end** of the
`HttpRequestRetry` config so callers can override any of the defaults
(including setting `retryOnServerErrors(0)`):

```kotlin
fun defaultHttpClient(
    customizeLogging: LoggingConfig.() -> Unit = { },
    customizeMetrics: HttpClientMetricsFeature.Config.() -> Unit = {},
    customizeRetry: HttpRequestRetryConfig.() -> Unit = { },
    configure: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) = HttpClient(CIO) {
    expectSuccess = true
    install(HttpRequestRetry) {
        retryOnServerErrors(3)
        retryOnExceptionIf(3) { _, cause -> /* unchanged */ }
        delayMillis { 250L }
        customizeRetry()          // ← runs last: callers can override defaults
    }
    // ... rest unchanged
}
```

Notes on placement:

- `customizeRetry()` is invoked **after** the defaults so a caller passing
  `retryOnServerErrors(0)` fully overrides the default `retryOnServerErrors(3)`
  (both call `setter`-style methods on `HttpRequestRetryConfig`; last write
  wins).
- The block is positioned between `customizeMetrics` and `configure` to keep
  the "customize a plugin's config" parameters grouped and `configure` (which
  operates on the whole `HttpClientConfig`) last.
- Import: `io.ktor.client.plugins.HttpRequestRetryConfig`.

In `Texas.kt`, change the `AuthClient` default. Retry override goes in
`customizeRetry`; timeout goes in `configure` (see change 2):

```kotlin
class AuthClient(
    private val config: TexasAuthConfig,
    private val provider: IdentityProvider,
    private val httpClient: HttpClient = defaultHttpClient(
        customizeRetry = {
            retryOnServerErrors(0)
        }
    ) {
        install(HttpTimeout) {
            requestTimeoutMillis = 1_000
            connectTimeoutMillis = 1_000
            socketTimeoutMillis  = 1_000
        }
    },
) { /* rest unchanged */ }
```

Rationale: texas is a local sidecar-style token endpoint that already retries
against the IdP. When it returns 5xx it has given up; a further client-side
retry cannot succeed and only multiplies latency (~4× in this trace). Network
exceptions (`SocketTimeoutException`, `ConnectTimeoutException`, …) remain
retried via `retryOnExceptionIf(3)` — that path is unchanged.

Choosing `customizeRetry` (a lambda on `HttpRequestRetryConfig`) over a
scalar `retryOnServerErrors: Int = 3` parameter matches the abstraction level
already used by `customizeLogging` and `customizeMetrics` in the same
function, and leaves room for future overrides (`retryOnException`,
`delayMillis`, `modifyRequest`) without further parameter churn.

### 2. `HttpTimeout` on the texas `AuthClient` — 1000 ms across the board

File: `src/main/kotlin/no/nav/fager/texas/Texas.kt`
(Import: `io.ktor.client.plugins.HttpTimeout`.)

"Across the board" here means **all three timeout values** are 1000 ms:

```kotlin
install(HttpTimeout) {
    requestTimeoutMillis = 1_000
    connectTimeoutMillis = 1_000
    socketTimeoutMillis  = 1_000
}
```

This is scoped to the texas `AuthClient` only. `defaultHttpClient` itself is
**not** modified to install `HttpTimeout` globally, and `Altinn3ClientImpl`'s
existing `install(HttpTimeout) { requestTimeoutMillis = 60_000 }`
(`Altinn3Client.kt:66-68`) stays as-is.

Ktor `HttpTimeout` interacts with `HttpRequestRetry` as follows:
`requestTimeoutMillis` is per-attempt. With `retryOnServerErrors = 0` and
`retryOnExceptionIf(3)` retained, the worst case for a texas call is
`1 attempt on 5xx` or `up to 4 attempts × 1 s + 3 × 250 ms delay ≈ 4.75 s` if
each attempt throws a retryable network exception. Under a repeat of the
2026-08-05 outage (texas returning 500 after ~1.3–3.8 s), the client-side call
is bounded to a single ~1 s window instead of the observed ~13.5 s.

### 3. `log.error` in `AltinnService.onFailure`

File: `src/main/kotlin/no/nav/fager/altinn/AltinnService.kt`

Currently (`AltinnService.kt:53-70`):

```kotlin
internal suspend fun hentTilgangerFraAltinn(fnr: String) =
    timer.coRecord {
        coroutineScope {
            val altinn3TilgangerResult = altinn3Client.resourceOwner_AuthorizedParties(fnr)
            val altinn3Tilganger = altinn3TilgangerResult.fold(
                onSuccess = { altinn3tilganger -> /* … */ },
                onFailure = { emptyList() }
            )
            // ...
        }
    }
```

Add a `log = logger()` field to the class (alongside the existing `teamLogger`)
and log the failure with the exception:

```kotlin
class AltinnService(
    private val altinn3Client: Altinn3Client,
    private val redisClient: AltinnTilgangerRedisClient,
    private val resourceRegistry: ResourceRegistry,
) {
    // ...
    private val log = logger()
    private val teamLogger = teamLogger()
    // ...

    internal suspend fun hentTilgangerFraAltinn(fnr: String) =
        timer.coRecord {
            coroutineScope {
                val altinn3TilgangerResult = altinn3Client.resourceOwner_AuthorizedParties(fnr)
                val altinn3Tilganger = altinn3TilgangerResult.fold(
                    onSuccess = { altinn3tilganger -> /* unchanged */ },
                    onFailure = { e ->
                        log.error("Klarte ikke hente authorizedParties fra Altinn 3", e)
                        emptyList()
                    }
                )
                // ... rest unchanged
            }
        }
```

Notes:

- Use `logger()` (`Logging.kt:218`), **not** `teamLogger()` — the team logger
  routes to Elastic/team-logs and is filtered out of the primary log stream
  used for alerting.
- The message must **not** contain the `fnr` (PII). The `MaskingAppender` will
  mask 11-digit sequences, but the compile-time contract is: no user
  identifiers in the ERROR log line. Only the exception (which contains the
  upstream URL and cause chain) is safe to log.
- The graceful fallback (returning `emptyList()` and continuing with
  `isError = true`) is **preserved**. The behavioural change is: one ERROR log
  line per failed Altinn 3 call. Alerting can then be driven from the standard
  ERROR-log alert path.

## Non-goals / explicitly unchanged

- HTTP response body and status of `POST /altinn-tilganger` — still 200 with
  `isError = true` in the payload when Altinn 3 fails. Callers depend on this.
- `altinnservice.altinn{result="isError"}` metric — still emitted; no change.
- `TexasAuthClientPlugin` in `Texas.kt:224-238` — still throws the same generic
  exception. Logging is added one layer up in `AltinnService` where all texas
  failure paths converge.
- `retryOnExceptionIf(3)` — still retries on `SocketTimeoutException`,
  `ConnectTimeoutException`, `EOFException`, `SSLHandshakeException`,
  `ClosedReceiveChannelException`. Unchanged.
- `defaultHttpClient` `delayMillis { 250L }` — unchanged. Exponential
  backoff/jitter is a separate follow-up.
- `Altinn3ClientImpl.resourceOwnerClient` `HttpTimeout { requestTimeoutMillis = 60_000 }`
  — unchanged. Only the texas `AuthClient` gets the 1000 ms timeout.
- `defaultHttpClient` itself does **not** install `HttpTimeout`. Timeout is
  configured per-client via the `configure` block.

## Verification

1. `defaultHttpClient(customizeRetry = { retryOnServerErrors(0) })` used by
   `AuthClient`: unit test that a texas 500 response causes exactly 1 outbound
   call (no retries) and surfaces as an exception to the caller.
   `retryOnExceptionIf` must still fire on `SocketTimeoutException` (add or
   keep an existing test). Add a small direct test on `defaultHttpClient` that
   `customizeRetry` runs after the defaults (i.e. the override takes effect).
2. `HttpTimeout` of 1000 ms on the texas `AuthClient`: unit test that a
   request against a slow stub (>1 s response) throws
   `HttpRequestTimeoutException`. The Altinn 3 client still has its 60 s
   `requestTimeoutMillis` — verify a separate test does not regress there.
3. `log.error` in `AltinnService`: unit test that `hentTilgangerFraAltinn`
   with a failing `altinn3Client` (e.g. `Result.failure(RuntimeException("boom"))`)
   writes exactly one ERROR-level log event containing the exception and
   returns `AltinnTilgangerResultat(isError = true, altinnTilganger = emptyList())`.
   No `fnr` in the log message.
4. Existing tests in `AltinnServiceTest.kt`, `AltinnTilgangerTest.kt`,
   `TexasTest.kt` continue to pass.

## Files touched

- `src/main/kotlin/no/nav/fager/infrastruktur/Http.kt` — add
  `customizeRetry: HttpRequestRetryConfig.() -> Unit = { }` parameter to
  `defaultHttpClient`; invoke it at the end of the `install(HttpRequestRetry)`
  block. Add `io.ktor.client.plugins.HttpRequestRetryConfig` import. No other
  changes.
- `src/main/kotlin/no/nav/fager/texas/Texas.kt` — change `AuthClient` default
  `httpClient` to
  `defaultHttpClient(customizeRetry = { retryOnServerErrors(0) }) { install(HttpTimeout) { requestTimeoutMillis = 1_000; connectTimeoutMillis = 1_000; socketTimeoutMillis = 1_000 } }`.
  Add `io.ktor.client.plugins.HttpTimeout` import.
- `src/main/kotlin/no/nav/fager/altinn/AltinnService.kt` — add `log = logger()`
  field; replace `onFailure = { emptyList() }` with
  `onFailure = { e -> log.error("Klarte ikke hente authorizedParties fra Altinn 3", e); emptyList() }`.
- `src/test/kotlin/no/nav/fager/…` — add/adjust tests per "Verification"
  above.
