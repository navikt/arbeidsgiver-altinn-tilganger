# Specification: Resource metadata API

## Summary

Add a new **open (no auth)** HTTP endpoint that returns, for every resource in
`KnownResources`, the resource's metadata from Altinn Resource Registry
(`title`, `rightDescription`, …) together with a flattened view of the policy
subjects — `grantedByRoles` and `grantedByAccessPackages`.

The endpoint is intended as a public "what tilganger/resources exist under NAV"
listing that consumer teams can use without setting up authentication.

## Endpoint

```
GET /resource-metadata
```

- **Authentication:** none. The route MUST NOT install `TexasAuth`.
- **Caching:** response is derived from already-cached data (see
  [Data sources](#data-sources)). The handler does no outbound HTTP itself.
- **Content type:** `application/json`
- **Status codes:**
  - `200 OK` — always, as long as the app is ready.
  - (fallback) `500 Internal Server Error` via existing `StatusPages` plugin.

### Response shape

```json
{
  "resources": {
    "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger": {
      "metadata": {
        "title": {
          "nb": "Innsyn i permitterings- og nedbemanningsmeldinger",
          "nn": "…",
          "en": "…"
        },
        "rightDescription": {
          "nb": "…",
          "nn": "…",
          "en": "…"
        },
        "resourceType": "GenericAccessResource",
        "status": "Completed",
        "delegable": true
      },
      "grantedByRoles": ["dagl", "lede"],
      "grantedByAccessPackages": ["regnskapsforer-lonn"]
    }
  }
}
```

Top-level field name is `resources`. The map key is the
`resourceId` from `KnownResources`.

Per resource:

- `metadata` — a projection of the Altinn resource document (see
  [Data sources](#data-sources)). Use the raw Altinn shape for the fields
  we pass through; do not translate or rename.
- `grantedByRoles` — de-duplicated list of role codes, derived from policy
  subjects where `type == "urn:altinn:rolecode"`. Use `policySubject.value`
  verbatim (it is already the stripped id — e.g. `"dagl"`, `"knuf"`,
  `"a0241"` — not the full `urn:altinn:rolecode:…` urn).
- `grantedByAccessPackages` — de-duplicated list of access-package
  identifiers, derived from policy subjects where
  `type == "urn:altinn:accesspackage"`. Use `policySubject.value` verbatim
  (e.g. `"regnskapsforer-lonn"`, not the full urn).

Policy subjects with any other `type` are ignored. The full `urn` field is
never exposed in the response — only the stripped id from `value`.

Reference: example input from Altinn
`GET /resourceregistry/api/v1/resource/{resourceId}/policy/subjects`

```json
{
  "links": {},
  "data": [
    { "type": "urn:altinn:rolecode",      "value": "knuf",                "urn": "urn:altinn:rolecode:knuf" },
    { "type": "urn:altinn:rolecode",      "value": "loper",               "urn": "urn:altinn:rolecode:loper" },
    { "type": "urn:altinn:accesspackage", "value": "regnskapsforer-lonn", "urn": "urn:altinn:accesspackage:regnskapsforer-lonn" }
  ]
}
```

maps to `grantedByRoles: ["knuf", "loper"]`,
`grantedByAccessPackages: ["regnskapsforer-lonn"]`.

Ordering: response should be deterministic. Iterate `KnownResources` in
declaration order; sort `grantedByRoles` and `grantedByAccessPackages`
alphabetically.

### Visibility

The endpoint is **intentionally undocumented** in
[src/main/resources/openapi.yaml](src/main/resources/openapi.yaml). Do not add
it there, and do not reference it from the Swagger UI landing page. It is a
hidden/internal-consumer route — discoverable only by teams we point at it
directly. Keep the route handler itself minimal so there is nothing in the
Swagger doc to forget to remove.

## Data sources

Two Altinn Resource Registry endpoints are involved. Both are unauthenticated
from our side (the existing `resourceRegistryClient` in
[Altinn3Client.kt](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt) is a
plain `defaultHttpClient()` with no token plugin).

1. **Resource document** — NEW, needs to be added to `Altinn3Client`.
   ```
   GET {altinn3.baseUrl}/resourceregistry/api/v1/resource/{resourceId}
   ```
   Response contains localized `title` and `rightDescription` objects
   (`{nb, nn, en}`), plus top-level fields like `resourceType`, `status`,
   `delegable`, `identifier`. Only a subset is exposed in our response.

2. **Policy subjects** — already implemented. See
   `Altinn3Client.resourceRegistry_PolicySubjects` in
   [Altinn3Client.kt:125](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt:125).
   Already cached via `ResourceRegistry.cache` with a 30-minute TTL and kept
   fresh by a background job — we reuse this directly, do not re-fetch.

## Implementation

### 1. New DTOs (in [ResourceRegistry.kt](src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt) or a new `ResourceMetadataApi.kt`)

Use `kotlinx.serialization`, consistent with the rest of the codebase.

```kotlin
@Serializable
data class LocalizedText(
    val nb: String? = null,
    val nn: String? = null,
    val en: String? = null,
)

@Serializable
data class ResourceRegistryResource(
    val identifier: String,
    val title: LocalizedText = LocalizedText(),
    val rightDescription: LocalizedText = LocalizedText(),
    val resourceType: String? = null,
    val status: String? = null,
    val delegable: Boolean? = null,
)
```

The Altinn response has many more fields; mark all non-essential ones with
defaults so unknown fields don't break deserialization. Configure
`Json { ignoreUnknownKeys = true }` where this DTO is decoded
(`resourceRegistryClient` is a separate `HttpClient` — configure its
`ContentNegotiation` / `Json` accordingly, *or* decode manually with a
permissive `Json` instance).

### 2. Extend `Altinn3Client`

Add to the interface and implementation in
[Altinn3Client.kt](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt):

```kotlin
suspend fun resourceRegistry_Resource(resourceId: String): Result<ResourceRegistryResource>
```

Implementation mirrors `resourceRegistry_PolicySubjects` — plain GET via
`resourceRegistryClient`, no auth header, `runCatching { … }`.

Path: `/resourceregistry/api/v1/resource/{resourceId}`.

### 3. Extend `ResourceRegistry`

Location: [ResourceRegistry.kt](src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt).

**Reuse the existing `policySubjectsPerResourceId` `AtomicReference`** — do
not introduce a parallel policy-subjects state or a second background loop
for subjects. The existing field (declared at
[ResourceRegistry.kt:218](src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt:218))
is already populated and refreshed by
`updatePolicySubjectsForKnownResources`; the new endpoint reads from it
unchanged.

What to add:

1. **A second `RedisLoadingCache<ResourceRegistryResource>`** for the
   resource document, alongside the existing policy-subject cache:
   - `metricsName = "resource-registry-resource"`
   - redis key namespace, e.g. `resource-registry-resource-v1`
   - same `cacheTTL` (30 min)
   - loader: `altinn3Client.resourceRegistry_Resource(resourceId).getOrThrow()`

2. **A second `AtomicReference<Map<ResourceId, ResourceRegistryResource>>`**
   (e.g. `resourceMetadataPerResourceId`) with the same initialization
   pattern as `policySubjectsPerResourceId`:
   ```kotlin
   private val resourceMetadataPerResourceId = AtomicReference(
       KnownResources.associate { it.resourceId to null as ResourceRegistryResource? }
   )
   ```

3. **A parallel update function** — either generalize
   `updatePolicySubjectsForKnownResources` into a generic
   `updatePerKnownResource<T>(state, fetcher)` helper used by both flows, or
   add a sibling `updateResourceMetadataForKnownResources`. Do NOT fold the
   resource-document fetch into the policy-subjects loop — they should fail
   independently so one broken upstream doesn't stall the other.

4. **Readiness gating** — the existing `isReady` flag currently flips true
   once policy subjects are warmed. Extend it so it only flips true once
   **both** the policy-subject map and the resource-metadata map have been
   warmed at least once. Startup sequence:
   - launch existing policy-subjects warmup loop (unchanged)
   - launch new resource-metadata warmup loop
   - `isReady = policySubjectsReady && resourceMetadataReady`

5. **Public accessors** — expose read-only views over the two
   `AtomicReference`s. The existing `policySubjectsPerResourceId` is
   `private`; add a public getter rather than changing its visibility so
   callers can't mutate it:
   ```kotlin
   fun getPolicySubjects(): Map<ResourceId, List<PolicySubject>> =
       policySubjectsPerResourceId.get()

   fun getResourceMetadata(): Map<ResourceId, ResourceRegistryResource?> =
       resourceMetadataPerResourceId.get()
   ```

Rationale: the open endpoint must not fan out to Altinn on the request path —
it returns a pre-computed snapshot. Reusing `policySubjectsPerResourceId`
means we pay zero extra cost for the subjects half of the response; the
existing cache is already kept fresh for `AltinnService`.

### 4. New response builder

In a new file or alongside `Api.kt`:

```kotlin
@Serializable
data class ResourceMetadataResponse(
    val resources: Map<String, ResourceMetadataEntry>,
)

@Serializable
data class ResourceMetadataEntry(
    val metadata: ResourceRegistryResource,
    val grantedByRoles: Set<String>,
    val grantedByAccessPackages: Set<String>,
)
```

Builder function — takes the two snapshots read from
`ResourceRegistry.getResourceMetadata()` and
`ResourceRegistry.getPolicySubjects()` (the latter is the existing
`policySubjectsPerResourceId` state, reused):

```kotlin
fun buildResourceMetadataResponse(
    metadata: Map<ResourceId, ResourceRegistryResource?>,
    policySubjects: Map<ResourceId, List<PolicySubject>>,
): ResourceMetadataResponse
```

Rules:

- Iterate `KnownResources` in declaration order.
- if `metadata[resourceId]` is missing fail the request and log an error.
- For each resource, partition policy subjects by `type`:
  - `"urn:altinn:rolecode"` → `grantedByRoles = subjects.map { it.value }.distinct().sorted()`
  - `"urn:altinn:accesspackage"` → `grantedByAccessPackages = subjects.map { it.value }.distinct().sorted()`
- Never emit `policySubject.urn` — only the stripped `value`.

### 5. Wire up the route

In [Application.kt](src/main/kotlin/no/nav/fager/Application.kt) inside the
existing `routing { … }` block, add a top-level route with **no** `TexasAuth`
install:

```kotlin
route("resource-metadata") {
    get {
        call.respond(
            buildResourceMetadataResponse(
                metadata = resourceRegistry.getResourceMetadata(),
                policySubjects = resourceRegistry.getPolicySubjects(),
            )
        )
    }
}
```

Place it next to `swaggerUI(...)` — i.e. outside both `/m2m`, `/whoami`,
`/altinn-tilganger` blocks — to make it obvious the route is unauthenticated.

### 6. OpenAPI

**Do not add this endpoint to
[src/main/resources/openapi.yaml](src/main/resources/openapi.yaml).** The
route is hidden by design — it must not appear in the Swagger UI.

## Test plan

Tests live in `src/test/kotlin/no/nav/fager/`. Follow the
`testWithFakeApplication { app -> … }` pattern used in
[AltinnTilgangerTest.kt](src/test/kotlin/no/nav/fager/AltinnTilgangerTest.kt).

1. **Fake stubs** — extend
   [FakeApplication.kt:48](src/test/kotlin/no/nav/fager/fakes/FakeApplication.kt:48)
   so that every `resourceId` in `KnownResourceIds` also has a stub for
   `GET /resourceregistry/api/v1/resource/{resourceId}` returning a canned
   resource document. The stubs for policy subjects already exist.

2. **Happy path** — `GET /resource-metadata` returns 200 with `resources`
   containing an entry for every `KnownResourceId` in declaration order.
   Assert metadata fields pass through and role/access-package extraction
   works for the one known "roles" stub
   (`nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger`
   → `grantedByRoles = ["dagl", "lede"]`, using `value` verbatim).

3. **No auth required** — hit the endpoint with no `Authorization` header
   and assert 200. Contrast with `/altinn-tilganger` which returns 401.

4. **Unknown Altinn fields ignored** — stub the resource document with extra
   unknown fields and verify deserialization does not throw.

5. **Access package extraction** — add a stub with
   `type = "urn:altinn:accesspackage"` (e.g.
   `value = "regnskapsforer-lonn"`) and assert it flows into
   `grantedByAccessPackages` (not `grantedByRoles`) with the raw `value`.
   Also assert the response never contains the string `"urn:altinn:"` — we
   only expose stripped ids.

6. **Determinism** — call twice, assert byte-equal JSON output.

7. **Unit test** for `buildResourceMetadataResponse` covering the
   missing-metadata-for-resource branch.

8. **Swagger hidden** — assert `/resource-metadata` does NOT appear in
   [src/main/resources/openapi.yaml](src/main/resources/openapi.yaml)
   (simple grep-style test) so a future contributor cannot accidentally
   document it.

## Out of scope

- Localization negotiation (`Accept-Language`). We return all three locales.
- Pagination / filtering. The full known-resources map is small (~30 items).
- Rate limiting. The endpoint is O(1) after warmup and reads from memory.
- Exposing resource-registry fields beyond the ones listed in
  `ResourceRegistryResource`. If a consumer needs more, add them explicitly.
