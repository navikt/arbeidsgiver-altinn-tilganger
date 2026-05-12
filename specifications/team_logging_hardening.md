# Specification: Team-logs hardening

## Summary

Harden the team-logs pipeline in this service so it cannot OOM the pod when
`team-logs.nais-system:5170` is slow or unreachable, and so the reconnect-error
storm in that case does not flood pod stderr. Trim the highest-volume team-log
call sites (ktor HTTP-client logging at `LogLevel.ALL`, full `hentTilganger`
result on every successful call) so we don't *rely* on the appender to save
us under sustained load.

No new dependencies, no new files outside of this spec. One file changes:
`Logging.kt`.

## Why this exists

The team-logs feature was reverted twice already
([PR #109](https://github.com/navikt/arbeidsgiver-altinn-tilganger/pull/109),
matching revert in the consumer `min-side-arbeidsgiver-api`
[PR #430](https://github.com/navikt/min-side-arbeidsgiver-api/pull/430))
because pods went OOM and stderr filled with `java.net.ConnectException`
lines. The current `HEAD` is the post-revert restoration — i.e. the v1
implementation is back, the problems it caused are not yet fixed.

Root cause is the same in both repos:

- `LogstashTcpSocketAppender` defaults: `ringBufferSize=8192`,
  no `appendTimeout` (blocking append), busy/blocking wait strategy,
  30-second `reconnectionDelay`. When the TCP destination stalls, the ring
  buffer fills with `ILoggingEvent`s that retain every argument we passed.
  With `LogLevel.ALL` on the ktor HTTP clients and the full
  `AltinnTilgangerResultat` on every successful `hentTilganger` call, each
  retained event is many KB. 8 192 slots × ~50 KB ≈ ~400 MB of retained heap.
  Pod limit is 1 GiB. OOM.
- Logback's `StatusManager` prints a Status WARN/ERROR to stderr per failed
  reconnect attempt. Over a minutes-long team-logs outage that is hundreds
  of lines.

NAIS documents `LogstashTcpSocketAppender` to `team-logs.nais-system:5170` as
the only supported transport — see
<https://doc.nais.io/observability/logging/how-to/team-logs/>. There is no
stdout-based alternative. So we keep the transport and fix the
application-side behaviour:

1. **Bound the in-process queue and never block the caller.** Smaller ring
   buffer, `appendTimeout = 0`, non-spinning `waitStrategyType` → drop on
   overflow instead of growing heap, and never stall an HTTP handler waiting
   on the log socket.
2. **Keep full event volume.** We keep `LogLevel.ALL` on the ktor HTTP
   clients and the full `hentTilganger` result on every call. This is
   needed so we can verify that what we returned matches the Altinn
   response. The bounded ring buffer (deliverable 1) protects us against
   heap growth; dropped events under backpressure are acceptable for a
   debug-only stream.
3. **Tame reconnect spam.** Longer `reconnectionDelay`, and a
   `RateLimitedStatusListener` so logback's own status output cannot flood
   stderr.

A parallel v2 spec exists in the consumer at
`min-side-arbeidsgiver-api/specifications/team_logging.md`. The two specs
should land in lockstep so the `Logging.kt` files do not diverge in
non-trivial ways.

## Current state (post-revert)

```
src/main/kotlin/no/nav/fager/infrastruktur/Logging.kt          — v1 port, no hardening
src/main/kotlin/no/nav/fager/altinn/Altinn2Client.kt           — LogLevel.ALL → teamLogger
src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt           — LogLevel.ALL → teamLogger
src/main/kotlin/no/nav/fager/altinn/AltinnService.kt           — full hentTilganger result on every call
src/main/kotlin/no/nav/fager/Application.kt                    — "Team logging enabled" at startup (fine)
src/test/kotlin/no/nav/fager/infrastruktur/TeamLogTest.kt      — covers marker-routing invariant (keep)
src/main/resources/META-INF/services/ch.qos.logback.classic.spi.Configurator — points at LogConfig (fine)
nais/{dev,prod}-gcp-altinn-tilganger.yaml                      — outbound rule for logging.nais-system exists (fine)
```

So the scope of this spec is narrow: appender hardening only.

## Deliverables

### 1. Harden `LogstashTcpSocketAppender` in `Logging.kt`

File: `src/main/kotlin/no/nav/fager/infrastruktur/Logging.kt`

Inside `LogConfig.configure(lc)`, on the existing
`LogstashTcpSocketAppender().setup(lc) { ... }` block, set the following
properties **in addition to** what is there today. Do not change anything
else (the marker filter, custom fields, truncation pattern, masking
asymmetry are correct).

```kotlin
addAppender(LogstashTcpSocketAppender().setup(lc) {
    this.name = "TEAMLOGS"
    addDestination("team-logs.nais-system:5170")

    // --- hardening: bound queue, never block caller, no busy spin ---
    this.ringBufferSize = 1024                                                          // default 8192
    this.appendTimeout = ch.qos.logback.core.util.Duration.buildByMilliseconds(0.0)     // drop on full instead of blocking
    setWaitStrategyType("sleeping")                                                     // method (no getter → no synthetic property); no CPU burn on idle
    this.reconnectionDelay = ch.qos.logback.core.util.Duration.buildByMinutes(1.0)      // default 30s
    this.keepAliveDuration = ch.qos.logback.core.util.Duration.buildByMinutes(5.0)      // keep socket warm
    // --- end hardening ---

    this.encoder = LogstashEncoder().setup(lc) {
        // ... existing customFields, isIncludeContext = false,
        //     LoggingEventPatternJsonProvider truncation pattern unchanged ...
    }
    addFilter(/* existing ACCEPT-on-marker filter unchanged */)
})
```

API notes (logstash-logback-encoder 8.1, the version on the branch):

- `ringBufferSize` is a power-of-two `Int` on `AsyncDisruptorAppender`.
- `appendTimeout` expects `ch.qos.logback.core.util.Duration` (logback's
  type, **not** `java.time.Duration`). There is no `Duration.ZERO` constant;
  use `Duration.buildByMilliseconds(0.0)`. Zero means "do not wait if the
  ring buffer is full; drop the event and return." This is the load-bearing
  fix.
- `waitStrategyType` is exposed as `setWaitStrategyType(String)` only — no
  matching getter, so Kotlin does **not** synthesise a property. Call the
  setter as a method: `setWaitStrategyType("sleeping")`. Valid values
  include `"blocking"`, `"sleeping"`, `"yielding"`, `"busySpin"`,
  `"phasedBackoff{...}"`. `"sleeping"` is the cheap default for low-volume
  background logging.
- `reconnectionDelay` and `keepAliveDuration` on `LogstashTcpSocketAppender`
  also use logback's `ch.qos.logback.core.util.Duration`. They have matching
  getters, so the Kotlin property assignment works. Use the `buildBy*`
  factories rather than the string parser.

### 2. Rate-limited `StatusListener` (same file)

At the top of `LogConfig.configure(lc)`, before any appender is created:

```kotlin
override fun configure(lc: LoggerContext): ExecutionStatus {
    // Suppress repeated transport-error status messages from the TCP appender.
    // Without this, logback's StatusManager prints a connection error to
    // stderr on every failed reconnect, which is noisy and was a contributor
    // to v1 being rolled back.
    lc.statusManager.add(RateLimitedStatusListener(maxMessagesPerMinute = 6))

    // ... existing rootAppender / addAppender code unchanged ...
}
```

Add this class to the same file, next to `MaskingAppender`:

```kotlin
/**
 * StatusListener that rate-limits status messages to N per minute.
 *
 * Used to silence the reconnect-error storm from LogstashTcpSocketAppender
 * when team-logs.nais-system is briefly unreachable. Without this, every
 * reconnect attempt produces a Status WARN/ERROR that the StatusManager
 * prints to stderr.
 *
 * INFO-level status events (mostly lifecycle messages on boot) are not
 * rate-limited.
 */
class RateLimitedStatusListener(
    private val maxMessagesPerMinute: Int,
) : ch.qos.logback.core.status.StatusListener,
    ch.qos.logback.core.spi.LifeCycle {

    private val window = java.time.Duration.ofMinutes(1)
    private val timestamps = java.util.ArrayDeque<java.time.Instant>()
    private var started = false

    override fun addStatusEvent(status: ch.qos.logback.core.status.Status) {
        if (status.level < ch.qos.logback.core.status.Status.WARN) {
            System.err.println(status)
            return
        }
        val now = java.time.Instant.now()
        synchronized(timestamps) {
            while (timestamps.isNotEmpty() &&
                java.time.Duration.between(timestamps.peekFirst(), now) > window) {
                timestamps.pollFirst()
            }
            if (timestamps.size < maxMessagesPerMinute) {
                timestamps.addLast(now)
                System.err.println(status)
            }
            // else: drop silently
        }
    }

    override fun start() { started = true }
    override fun stop() { started = false }
    override fun isStarted(): Boolean = started
}
```

Six lines/minute is enough to know the transport is broken without drowning
pod logs. A genuinely-broken team-logs destination will still produce lines
every minute for as long as it is broken; we won't miss it.

### 3. Keep `LogLevel.ALL` body logging on the Altinn HTTP clients

Files:
- `src/main/kotlin/no/nav/fager/altinn/Altinn2Client.kt`
- `src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt`

**No change.** `LogLevel.ALL` is kept so we can compare what Altinn returned
with what we served to the caller. The bounded ring buffer (deliverable 1)
ensures this volume cannot OOM the pod — events are dropped under
backpressure rather than retained.

### 4. Keep full `hentTilganger` payload logging

File: `src/main/kotlin/no/nav/fager/altinn/AltinnService.kt`

**No change.** The existing full-payload `teamLogger.info(...)` on every
`hentTilganger` cache-miss call is kept. This is needed to verify
correctness of the returned result against the Altinn response during
support investigations. The bounded ring buffer protects memory.

### 5. No changes required to

- `META-INF/services/ch.qos.logback.classic.spi.Configurator`
- `MaskingAppender` (the four PII regexes + MDC masking)
- The marker filter on `ConsoleAppender` (DENY when marker present)
- `TEAM_LOG_MARKER`, `teamLogger()`, `MarkerLogger` — full machinery stays
- `Application.kt` (`log.info(TEAM_LOG_MARKER, "Team logging enabled")` runs
  once at startup)
- `TeamLogTest.kt` — keep, it covers the marker-routing invariant
- Both NAIS manifests — `logging.nais-system` outbound rule already present

## Acceptance criteria

1. `mvn -q package` succeeds. `TeamLogTest` still passes — the three tests
   exercise the marker filter and the `MarkerLogger` overloads, none of
   which are touched here.
2. Hitting any endpoint that goes through `Altinn2Client` / `Altinn3Client`
   produces ktor team-log lines that contain HTTP method, URL, status,
   and request/response bodies (`LogLevel.ALL` is kept).
3. A `hentTilganger` call that hits the upstream (cache miss, healthy
   backends) produces a full-payload team-log line with the complete
   result — needed for support investigations.
4. A `hentTilganger` call where Altinn 2 or Altinn 3 fails still produces
   the full-payload team-log line.
5. With `team-logs.nais-system` deliberately blocked (temporarily drop the
   `logging.nais-system` outbound rule, redeploy, then put it back), pod
   memory stays bounded under sustained traffic — the bounded ring buffer
   drops rather than retains. Stderr stays under ~6 reconnect-error lines
   per minute, confirming the status listener.
6. Restoring the outbound rule reconnects without a restart.
7. Truncation regex still works: `teamLog.info(...)` with a > 125 000-char
   argument produces a team-log line ending in `...truncated"`.

5 and 6 are nice-to-have manual verifications during the next dev deploy;
they are the failure-mode regression checks for the original revert.

## Out of scope

- Replacing the TCP appender. NAIS team-logs has no stdout-based transport.
- Reducing log volume (`LogLevel.ALL` or full `hentTilganger` payload).
  We intentionally keep full logging so we can verify correctness of
  returned results against Altinn responses during support investigations.
  The bounded ring buffer protects memory.
- A Micrometer gauge for ring-buffer fill ratio. Useful follow-up for early
  warning of pressure before drops start; not required for this spec.
- Tuning `writeBufferSize`, `writeTimeout`, or socket-level keep-alive
  options below the JVM defaults. The defaults are fine once the
  application-side queue is bounded.
- Diverging `Logging.kt` from the copy in `min-side-arbeidsgiver-api`. Keep
  the two in lockstep — the two v2 specs are written to match each other.

## Risks and mitigations

- **Risk: we drop team-log events under sustained backpressure.** Yes —
  that is the point. Team-logs is a debug-only stream; losing some lines
  is preferable to OOMing the pod or stalling an HTTP handler. The
  error-path payload log is the only "important" line, and it ships from
  a context where errors are already rare.
- **Risk: `appendTimeout = Duration.ZERO` semantics differ in a future
  logstash-logback-encoder upgrade.** As of 8.1 the documented behaviour
  is "do not wait." If a future version changes this, the bounded-memory
  acceptance test (criterion 5) will catch it; failure mode becomes
  "throughput drops", not "memory leak".
- **Risk: rate-limited status listener swallows a useful diagnostic.** Cap
  is 6/minute on WARN+. A broken transport will keep producing lines for as
  long as it is broken. INFO status events are not rate-limited.
- **Risk: team-logs is unmasked.** Unchanged. The marker filter on
  `ConsoleAppender` keeps the same content off stdout/Loki. `TeamLogTest`
  is the regression test that guards the boundary.

## Files touched

| Path | Change |
| --- | --- |
| `src/main/kotlin/no/nav/fager/infrastruktur/Logging.kt` | Add `ringBufferSize`, `appendTimeout`, `waitStrategyType`, `reconnectionDelay`, `keepAliveDuration` on the TCP appender. Add `RateLimitedStatusListener` class and register it on the `LoggerContext`. |

No NAIS manifest changes, no `pom.xml` change, no new files, no test
changes. `Altinn2Client.kt`, `Altinn3Client.kt`, and `AltinnService.kt`
are intentionally unchanged — full logging volume is kept.
