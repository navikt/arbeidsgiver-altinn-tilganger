# Specification: Access package details on the metadata API

Extends the existing `GET /resource-metadata` endpoint defined in
[metadata_api.md](metadata_api.md). Read that spec first — this document only
describes the delta.

## Summary

Add a new top-level `accessPackages` field to the `/resource-metadata`
response. It is a map of access-package **urn → details** (`name`,
`description`, `areas`, …). The set of keys is exactly the union of all
access packages referenced from any resource's `grantedByAccessPackages`.

**Data source is the `/export` endpoint** (one HTTP call returning the full
catalog, ~700 KB). Per-urn endpoints (`/package/urn/{urn}`) are explicitly
out of scope.

**Scope of cached/exposed data:** even though `/export` returns the whole
catalog, we filter the in-memory index and the response down to access
packages actually referenced by some resource in `KnownResources` (i.e.
urns that appear in policy subjects with
`type == "urn:altinn:accesspackage"`). Today that is roughly a dozen urns
out of the ~90 Altinn returns. The rest are discarded after parsing.

`area` is a **single object** (nullable). Altinn's `/export` payload places
each access package under exactly one area today (verified against tt02 —
no urn appears under multiple areas), so we expose `area` as a singular
field rather than a list. If Altinn ever emits the same urn under multiple
areas, we log a warning and pick one deterministically (first occurrence
when walking groups in document order). Promoting `area` back to plural
would be a breaking change at that point — accept that trade-off for the
cleaner shape today.

The shape is intentionally extensible so we can add more fields (e.g.
`isDelegable`, `type`, …) later without a breaking change.

## Updated response shape

```json
{
  "resources": {
    "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger": {
      "metadata": { "title": { "nb": "…" }, "rightDescription": { "nb": "…" } },
      "grantedByRoles": ["dagl", "lede"],
      "grantedByAccessPackages": ["revisorattesterer"]
    }
  },
  "accessPackages": {
    "urn:altinn:accesspackage:revisorattesterer": {
      "name": "Revisorattesterer",
      "description": "Denne fullmakten gir tilgang til alle tjenester som krever revisorattestering. …",
      "area": {
        "urn": "accesspackage:area:skatt_avgift_regnskap_og_toll",
        "name": "Skatt, avgift, regnskap og toll",
        "description": "Dette fullmaktsområdet omfatter tilgangspakker knyttet til skatt, avgift, regnskap og toll."
      }
    }
  }
}
```

The `resources` half is **unchanged** from
[metadata_api.md](metadata_api.md). In particular, `grantedByAccessPackages`
keeps its current form: stripped ids from `policySubject.value`
(`"revisorattesterer"`), not full urns. Consumers who want details look
them up by reconstructing the urn:

```
"urn:altinn:accesspackage:" + id   →   accessPackages[urn]
```

The prefix is constant and well-known (`urn:altinn:accesspackage:` for every
access package), so embedding it in every list entry would be redundant.

### Two distinct urn namespaces

The response carries two completely unrelated urn forms — do not conflate
them:

| Urn                                                         | What it identifies      | Where it appears                           |
| ----------------------------------------------------------- | ----------------------- | ------------------------------------------ |
| `urn:altinn:accesspackage:<id>`                             | An access **package**   | Keys of the `accessPackages` map           |
| `accesspackage:area:<id>`                                   | An access-package **area** | `accessPackages.<key>.area.urn`         |

Note the area urn does not start with `urn:altinn:` — it is a different
namespace owned by Altinn's access-package taxonomy. Pass both through
verbatim, do not normalize, and do not try to derive one from the other.

### Field semantics — `accessPackages` entry

```jsonc
{
  "name": "string",
  "description": "string",
  "area": {
    "urn": "string",
    "name": "string",
    "description": "string"
  }
}
```

Per-field rules:

- `name`, `description` — pass through Altinn's `name` and `description`
  on the package object. Altinn returns these as plain strings (not a
  `{nb,nn,en}` object like `ResourceRegistryResource.title`). Expose as
  plain strings to start; if Altinn switches to localized objects later,
  bump this spec.
- `area` — the area under which this package appears in the `/export`
  payload. Found by walking `groups[].areas[].packages[]` and matching by
  package urn. Nullable: omit / serialize as `null` if the package has no
  parent area (defensive — should not happen in practice).
- If the same package urn somehow appears under more than one area in a
  future `/export` payload, log a warning naming all the areas seen and
  pick the first occurrence in document order (groups iterated in array
  order, then areas in array order). Do NOT silently drop or shuffle.
- The `area` object contains `urn`, `name`, `description`. Drop Altinn's
  `iconUrl`, `group`, `packages`, `id` — out of scope for now.

If a urn appears in `grantedByAccessPackages` but is not present in the
`/export` payload, the entry is **omitted** from the `accessPackages` map
and a warning is logged. The resource entry still lists the urn — a
consumer that finds a urn with no details should fall back to "unknown
package" UX. This avoids a single mismatch breaking the whole endpoint.

Ordering: `accessPackages` map iterates urns in alphabetical order so the
JSON output is deterministic.

## Data source

One Altinn endpoint:

```
GET {altinn3.baseUrl}/accessmanagement/api/v1/meta/info/accesspackages/export
```

Documented at
<https://docs.altinn.studio/swagger/altinn-platform-accessmanagement-v1-metadata.json>.
Live (test): <https://platform.tt02.altinn.no/accessmanagement/api/v1/meta/info/accesspackages/export>.

This endpoint is a hard requirement. Per-urn endpoints
(`/package/urn/{urn}`) must NOT be used.

- **Auth:** none. Use a plain `defaultHttpClient()`, same pattern as the
  existing `resourceRegistryClient` in
  [Altinn3Client.kt:124](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt:124).
- **Response:** an **array** (despite the swagger declaring a single
  `AreaGroupDto`) of group objects. As of 2026-04 there are 2 groups
  (`"Allment"`, `"Bransje"`), ~13 areas total, ~90 access packages, total
  payload ~600–700 KB.
- **Shape:**
  ```jsonc
  [
    {
      "id": "…", "name": "Allment", "urn": null, "description": "…", "type": "Organisasjon",
      "areas": [
        {
          "id": "…", "name": "Skatt, avgift, regnskap og toll",
          "urn": "accesspackage:area:skatt_avgift_regnskap_og_toll",
          "description": "…", "iconUrl": "…", "group": null,
          "packages": [
            {
              "id": "…", "name": "Revisorattesterer",
              "urn": "urn:altinn:accesspackage:revisorattesterer",
              "description": "…",
              "isDelegable": true, "isAssignable": true, "isResourcePolicyAvailable": true,
              "area": { /* identical copy of the parent area, with empty packages and null group */ },
              "type": { /* … */ },
              "resources": null
            }
          ]
        }
      ]
    }
  ]
  ```

### Filtering — which urns we expose

Even though `/export` returns the whole catalog, our index and the
response only include access-package urns referenced by some resource in
`KnownResources`. The reference set is derived **dynamically** at runtime
from the existing `ResourceRegistry.getPolicySubjects()` snapshot:

```kotlin
val referencedUrns: Set<String> = resourceRegistry.getPolicySubjects()
    .values
    .flatten()
    .filter { it.type == "urn:altinn:accesspackage" }
    .map { it.urn }
    .toSet()
```

This means `AccessPackageRegistry` depends on `ResourceRegistry` being
ready (`ResourceRegistry.isReady() == true`) before it can decide what to
keep. If a new resource is added to `KnownResources` later and pulls in a
previously-unknown access package, the next refresh tick picks it up
automatically — no redeploy needed, and no extra HTTP call (the next
`/export` already contains it).

### Multi-area defensive handling

Today no urn shows up under multiple areas in `/export`. We rely on this:
the public response shape is a singular `area` object. If Altinn ever
changes that, the rebuild logic logs a warning and keeps the first
occurrence in document order — but the response stays singular until we
bump the public shape.

## Implementation

### 1. New DTOs

In a new file, e.g.
`src/main/kotlin/no/nav/fager/altinn/AccessPackageRegistry.kt`:

```kotlin
@Serializable
data class AccessPackageExportGroup(
    val id: String? = null,
    val name: String? = null,
    val urn: String? = null,
    val description: String? = null,
    val type: String? = null,
    val areas: List<AccessPackageExportArea> = emptyList(),
)

@Serializable
data class AccessPackageExportArea(
    val id: String? = null,
    val name: String? = null,
    val urn: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val packages: List<AccessPackageExportPackage> = emptyList(),
    // intentionally drop: group
)

@Serializable
data class AccessPackageExportPackage(
    val id: String? = null,
    val urn: String,
    val name: String? = null,
    val description: String? = null,
    // intentionally drop: isDelegable, isAssignable, isResourcePolicyAvailable,
    //                     area (we use the parent area instead), type, resources
)
```

These mirror Altinn's `/export` payload shape (only the fields we currently
use; everything else is dropped). Configure the `Json` instance with
`ignoreUnknownKeys = true` for forward compatibility.

The public response DTOs (added to `ResourceMetadataApi.kt`):

```kotlin
@Serializable
data class ResourceMetadataResponse(
    val resources: Map<String, ResourceMetadataEntry>,
    val accessPackages: Map<String, AccessPackageDetails>,  // NEW
)

@Serializable
data class AccessPackageDetails(
    val name: String? = null,
    val description: String? = null,
    val area: AccessPackageArea? = null,
)

@Serializable
data class AccessPackageArea(
    val urn: String? = null,
    val name: String? = null,
    val description: String? = null,
)
```

Keeping the upstream DTOs separate from the public DTOs lets us evolve the
response (e.g. add `isDelegable` later) without leaking unrelated Altinn
fields by accident.

### 2. Extend `Altinn3Client`

Add to the interface and implementation in
[Altinn3Client.kt](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt):

```kotlin
suspend fun accessManagement_AccessPackagesExport(): Result<List<AccessPackageExportGroup>>
```

Implementation mirrors `resourceRegistry_Resource` — plain GET via the
existing unauthenticated `resourceRegistryClient` (or a new sibling
`accessManagementClient` if we want logical separation), `runCatching {…}`:

```kotlin
appendPathSegments("/accessmanagement/api/v1/meta/info/accesspackages/export")
```

Top-level response is a JSON array — `httpResponse.body<List<AccessPackageExportGroup>>()`.

### 3. New `AccessPackageRegistry`

New file:
`src/main/kotlin/no/nav/fager/altinn/AccessPackageRegistry.kt`. Mirrors the
existing `ResourceRegistry` pattern in
[ResourceRegistry.kt](src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt),
but with **a single cache entry** (the whole `/export` payload) instead of
a per-urn cache.

```kotlin
class AccessPackageRegistry(
    private val altinn3Client: Altinn3Client,
    private val resourceRegistry: ResourceRegistry,
    redisConfig: RedisConfig,
    backgroundCoroutineScope: CoroutineScope?,
) : RequiresReady {
    @Volatile private var isReady = false
    override fun isReady() = isReady

    val cacheTTL = 30.minutes
    val cacheRefreshInterval = cacheTTL / 3

    private val cache = RedisLoadingCache(
        metricsName = "access-package-registry",
        redisClient = redisConfig.createClient<List<AccessPackageExportGroup>>("access-package-export-v1"),
        loader = { _ -> altinn3Client.accessManagement_AccessPackagesExport().getOrThrow() },
        cacheTTL = cacheTTL.toJavaDuration(),
    )

    // urn → indexed view: package + the area it appears under.
    // Already filtered to referenced urns only.
    private val byUrn = AtomicReference<Map<String, IndexedAccessPackage>>(emptyMap())

    fun getAccessPackages(): Map<String, IndexedAccessPackage> = byUrn.get()
    // … background warm-up + refresh loop, see below …
}

data class IndexedAccessPackage(
    val pkg: AccessPackageExportPackage,
    val area: AccessPackageExportArea?,
)
```

#### Determining which urns to keep

```kotlin
private fun referencedUrns(): Set<String> =
    resourceRegistry.getPolicySubjects()
        .values
        .flatten()
        .filter { it.type == "urn:altinn:accesspackage" }
        .map { it.urn }
        .toSet()
```

#### Building the filtered index

After every successful `/export` fetch, rebuild the in-memory snapshot:

```kotlin
private fun rebuildIndex(groups: List<AccessPackageExportGroup>) {
    val keep = referencedUrns()

    val pkgByUrn = mutableMapOf<String, AccessPackageExportPackage>()
    val areaByUrn = mutableMapOf<String, AccessPackageExportArea>()
    val multiAreaSeen = mutableMapOf<String, MutableList<String?>>()

    for (group in groups) {
        for (area in group.areas) {
            for (pkg in area.packages) {
                if (pkg.urn !in keep) continue   // <-- the filter
                if (pkgByUrn.putIfAbsent(pkg.urn, pkg) == null) {
                    areaByUrn[pkg.urn] = area    // first occurrence wins
                } else {
                    multiAreaSeen
                        .getOrPut(pkg.urn) { mutableListOf(areaByUrn[pkg.urn]?.urn) }
                        .add(area.urn)
                }
            }
        }
    }

    multiAreaSeen.forEach { (urn, areas) ->
        log.warn(
            "Access-package {} appears under multiple areas in /export: {}. Keeping first.",
            urn, areas,
        )
    }

    byUrn.set(
        pkgByUrn.mapValues { (urn, pkg) ->
            IndexedAccessPackage(pkg = pkg, area = areaByUrn[urn])
        }
    )

    // For observability: any referenced urn that wasn't found in /export
    val missing = keep - pkgByUrn.keys
    if (missing.isNotEmpty()) {
        log.warn("Referenced access-package urns not found in Altinn /export: {}", missing)
    }
}
```

The unfiltered `groups` list is what's cached in Redis. We do not persist
the filtered view — `referencedUrns()` can change at runtime as the
policy-subjects snapshot updates, and the filter is cheap to reapply.

#### Background loops

Two loops, mirroring `ResourceRegistry`:

1. **Warm-up loop** — runs until `isReady` flips true.
   - Wait for `resourceRegistry.isReady()`. Until then, sleep 100 ms and
     retry, like the existing pattern at
     [ResourceRegistry.kt:227](src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt:227).
   - Call `cache.get(KEY)` (where `KEY` is a single fixed string like
     `"_export"`), wrapped in `retryWithBackoff`.
   - On success, `rebuildIndex(groups)` and flip `isReady = true`.

2. **Refresh loop** — runs until shutdown.
   - `cache.update(KEY)` to force a fresh upstream fetch.
   - On success, `rebuildIndex(groups)`. Sleep `cacheRefreshInterval`
     (10 minutes).
   - On failure, log and back off to 5 s, matching the existing
     ResourceRegistry pattern at
     [ResourceRegistry.kt:237](src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt:237).

   Also rebuild the index opportunistically when `referencedUrns()`
   changes (i.e. ResourceRegistry's policy subjects refreshed and
   surfaced new urns). Cheapest version: always rebuild on every
   refresh tick, even if we didn't refetch — `rebuildIndex` is in-memory
   only when fed the previously-cached payload.

#### Readiness gating

`AccessPackageRegistry` registers with `Health` separately from
`ResourceRegistry`. The app is ready when both are ready. Internally,
`AccessPackageRegistry`'s readiness depends on `ResourceRegistry`'s
readiness (it can't decide which urns to keep otherwise) — that ordering
is enforced by the warm-up loop's `resourceRegistry.isReady()` poll, not
by constructor wiring.

#### Retries

Use the existing `retryWithBackoff` from
[ResourceRegistry.kt:281](src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt:281).
Either extract it to a shared utility (recommended) or duplicate it.

### 4. Wire it up

In [Application.kt](src/main/kotlin/no/nav/fager/Application.kt), next to
the existing `resourceRegistry`:

```kotlin
val accessPackageRegistry = AccessPackageRegistry(
    altinn3Client = altinn3Client,
    resourceRegistry = resourceRegistry,
    redisConfig = redisConfig,
    backgroundCoroutineScope = backgroundScope,
).also { Health.register(it) }
```

Update the `/resource-metadata` route to pass the access-package snapshot
to the builder.

### 5. Update `buildResourceMetadataResponse`

In [ResourceMetadataApi.kt](src/main/kotlin/no/nav/fager/altinn/ResourceMetadataApi.kt):

```kotlin
fun buildResourceMetadataResponse(
    metadata: Map<ResourceId, ResourceRegistryResource?>,
    policySubjects: Map<ResourceId, List<PolicySubject>>,
    accessPackages: Map<String, IndexedAccessPackage>,  // NEW, urn-keyed
): ResourceMetadataResponse
```

Steps inside:

1. Build the `resources` map exactly as today —
   `grantedByAccessPackages` keeps using `policySubject.value` (stripped
   id), unchanged from
   [ResourceMetadataApi.kt:45](src/main/kotlin/no/nav/fager/altinn/ResourceMetadataApi.kt:45).
2. Compute the set of access-package **urns** referenced across all
   resources by walking `policySubjects` and reading `policySubject.urn`
   directly (this is the full urn we use as a map key — separate from the
   stripped value emitted in `grantedByAccessPackages`).
3. For each referenced urn, look up `accessPackages[urn]`:
   - present → emit
     ```kotlin
     AccessPackageDetails(
         name = idx.pkg.name,
         description = idx.pkg.description,
         area = idx.area?.let {
             AccessPackageArea(it.urn, it.name, it.description)
         },
     )
     ```
   - missing → skip + log a warning. Rate-limit the warning (e.g. via a
     `Set<String>` of already-warned urns) to avoid log spam.
4. Emit the `accessPackages` map sorted alphabetically by urn for
   determinism.

### 6. OpenAPI

Same as the parent spec: **do not document this endpoint in
[src/main/resources/openapi.yaml](src/main/resources/openapi.yaml).** It is
a hidden route.

## Test plan

Tests live in `src/test/kotlin/no/nav/fager/`. Follow the existing
`testWithFakeApplication { app -> … }` pattern and the
[ResourceMetadataTest.kt](src/test/kotlin/no/nav/fager/ResourceMetadataTest.kt)
structure.

1. **Fake stub** — extend
   [FakeApplication.kt](src/test/kotlin/no/nav/fager/fakes/FakeApplication.kt)
   to stub
   `GET /accessmanagement/api/v1/meta/info/accesspackages/export` with a
   small fixture that contains:
   - 2 groups, 2 areas, 4 packages
   - At least one package whose urn is referenced by a fake policy-subject
     stub (so it shows up in `grantedByAccessPackages`)
   - At least two packages that are **not** referenced by any policy
     subject — these must NOT appear in the response

2. **Happy path** — `GET /resource-metadata` returns 200 with a non-empty
   `accessPackages` map. Keys are full package urns
   (`urn:altinn:accesspackage:…`). For each resource that has
   `grantedByAccessPackages` entries, every stripped id in that list
   resolves to `accessPackages["urn:altinn:accesspackage:" + id]`. Each
   `accessPackages` entry has the expected `name`, `description`, and
   `area` (singular object, matching the area the package was nested
   under in the fixture).

3. **Filter applied** — assert that the unreferenced packages from the
   fixture do **not** appear in the response's `accessPackages` map, even
   though `/export` returned them.

4. **`grantedByAccessPackages` shape unchanged** — assert that
   `grantedByAccessPackages` still contains stripped ids
   (e.g. `"revisorattesterer"`), NOT full urns. Guards against any
   well-meaning refactor that might try to "improve consistency" by
   pushing urns into the resource entries.

5. **Single area assumption locked in** — for every entry in
   `accessPackages`, assert `area` is a non-null object (not a list).
   Locks in the singular shape so a future refactor can't quietly
   reintroduce a list.

6. **Defensive multi-area handling** — synthetic test only: stub a
   fixture where one referenced urn appears under TWO areas. Assert:
   - the response still serializes `area` as a singular object
   - the chosen `area` is the first occurrence in document order
   - a warning was logged naming both area urns

7. **Distinct urn namespaces** — assert that every key in `accessPackages`
   starts with `urn:altinn:accesspackage:`, and every `area.urn` (where
   present) starts with `accesspackage:area:`. Locks in the namespace
   distinction documented in the response shape section.

8. **Missing details tolerated** — a urn referenced by a resource but
   absent from the `/export` fixture should:
   - still appear in that resource's `grantedByAccessPackages` (stripped
     id form, unchanged)
   - have no entry under `accessPackages` for the corresponding urn
   - cause a warning to be logged

9. **Unknown Altinn fields ignored** — stub the export with extra unknown
   fields on group, area, and package levels and assert deserialization
   succeeds.

10. **No change to existing role / access-package shape** —
    [ResourceMetadataTest.kt](src/test/kotlin/no/nav/fager/ResourceMetadataTest.kt)
    assertions for `grantedByRoles` and `grantedByAccessPackages` should
    pass UNCHANGED after this feature lands. Run the existing test suite
    to confirm.

11. **Single HTTP call** — instrument the fake to count hits on
    `/accessmanagement/api/v1/meta/info/accesspackages/export`. Assert
    that during a normal lifecycle (warm-up + a few request calls), the
    endpoint is hit at most once per refresh interval — not per request,
    not per urn.

12. **Determinism** — call twice; assert byte-equal JSON output. Both
    `resources` and `accessPackages` keys should be in their respective
    sorted orders.

13. **Swagger hidden** — same grep test as the parent spec (already
    covered, no new test needed unless we want to be explicit).

## Out of scope

- Localization for `name` / `description`. Pass through whatever Altinn
  returns. If Altinn moves to localized objects, address then.
- Caching access-package details longer than the existing 30-minute TTL.
- Exposing extra `PackageDto` fields (`isDelegable`, `isAssignable`,
  `isResourcePolicyAvailable`, `type`, `resources`). Add when a consumer
  asks.
- Exposing `iconUrl` and `group` on areas. Add when a consumer asks.
- Linking access packages back to resources (reverse index). Consumers can
  derive this client-side from the existing `resources` →
  `grantedByAccessPackages` edges.
