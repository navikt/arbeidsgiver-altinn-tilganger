# Specification: Role display names on the metadata API

Extends the existing `GET /resource-metadata` endpoint defined in
[metadata_api.md](metadata_api.md). Read that spec first — this document only
describes the delta.

## Summary

Add a new top-level `roles` field to the `/resource-metadata` response. It is
a map of role **legacy code → details** (`name`, `description`). The key is the
role code from `policySubject.value` (e.g. `"dagl"`, `"lede"`), the same form
already used in each resource's `grantedByRoles` list. Looking up the display
name for a granted role is therefore a direct map access:
`roles[roleCode]`. The set of keys is the union of all role codes referenced
across all resources.

**Data source is the `/meta/info/roles` endpoint** — one HTTP call returning
all known roles (~155 entries, <50 KB). The list includes both Altinn 3 native
roles and legacy CCR roles (Enhetsregisteret). Only roles with a non-null
`legacyRoleCode` that matches a value referenced by some resource's policy
subjects are included in the response.

**Scope of cached/exposed data:** the in-memory index and the response only
include roles actually referenced by some resource in `KnownResources` (i.e.
legacy codes appearing in policy subjects with
`type == "urn:altinn:rolecode"`). The rest are discarded after indexing.

The shape is intentionally simple and extensible so we can add more fields
(e.g. `urn`, `isKeyRole`, …) later without a breaking change.

## Updated response shape

```json
{
  "resources": {
    "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger": {
      "metadata": { "title": { "nb": "…" }, "rightDescription": { "nb": "…" } },
      "grantedByRoles": ["dagl", "lede"],
      "grantedByAccessPackages": ["regnskapsforer-lonn"]
    }
  },
  "accessPackages": {
    "regnskapsforer-lonn": { "name": "…", "description": "…", "area": { "urn": "…" } }
  },
  "roles": {
    "dagl": {
      "name": "Daglig leder",
      "description": "Daglig leder for virksomheten"
    },
    "lede": {
      "name": "Styrets leder",
      "description": "Leder i styret for virksomheten"
    }
  }
}
```

The `resources` and `accessPackages` halves are **unchanged**.
`grantedByRoles` keeps its current form: stripped legacy codes from
`policySubject.value` (`"dagl"`, `"lede"`). The new `roles` map uses the
same codes as keys, so consumers do a direct lookup:

```
roles[roleCode]   //  e.g. roles["dagl"]
```

### Field semantics — `roles` entry

```jsonc
{
  "name": "string | null",
  "description": "string | null"
}
```

Per-field rules:

- `name` — pass through Altinn's `name` field on the role object. Altinn
  returns this as a plain string (not a `{nb, nn, en}` object). Expose as
  a plain string; if Altinn adds localization later, bump this spec.
- `description` — pass through Altinn's `description` field.
- Fields not yet needed (`urn`, `code`, `isKeyRole`, `provider`, …) are
  intentionally dropped for now. Add them later as needed without a
  breaking change.

If a role code appears in `grantedByRoles` but is not found in the `/roles`
response (i.e. Altinn has no entry with that `legacyRoleCode`), the entry is
**omitted** from the `roles` map and a warning is logged. The resource entry
still lists the code. This avoids a single mismatch breaking the whole
endpoint.

Ordering: `roles` map iterates codes in alphabetical order so the JSON output
is deterministic.

## Data source

One Altinn Access Management endpoint, **no authentication required**:

```
GET {altinn3.baseUrl}/accessmanagement/api/v1/meta/info/roles
```

Live: <https://platform.altinn.no/accessmanagement/api/v1/meta/info/roles>.

Source code:
[`RolesController.cs` in altinn-authorization-tmp](https://github.com/Altinn/altinn-authorization-tmp/blob/main/src/apps/Altinn.AccessManagement/src/Altinn.AccessManagement.Api.Metadata/Controllers/RolesController.cs).

- **Auth:** none. Use the existing unauthenticated `resourceRegistryClient`
  from
  [Altinn3Client.kt](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt),
  same as `accessManagement_AccessPackagesExport`.
- **Response:** a flat JSON **array** of role objects. As of 2026-05 there
  are ~155 entries; payload is under 50 KB.
- **Shape** (only the fields we use; everything else is dropped via
  `ignoreUnknownKeys = true`):

```jsonc
[
  {
    "id": "ba1c261c-…",
    "name": "Daglig leder",
    "code": "daglig-leder",
    "description": "Daglig leder for virksomheten",
    "urn": "urn:altinn:external-role:ccr:daglig-leder",
    "legacyRoleCode": "dagl",
    // provider, isKeyRole, isResourcePolicyAvailable, legacyUrn — ignored
  }
]
```

The **key mapping** for the lookup: `legacyRoleCode` (e.g. `"dagl"`)
corresponds 1-to-1 to `policySubject.value` for subjects with
`type == "urn:altinn:rolecode"`. This is the same value that already
appears in each resource's `grantedByRoles` list.

Roles without a `legacyRoleCode` (pure Altinn 3 roles like
`"rettighetshaver"`, `"agent"`, `"hovedadministrator"`) will not be indexed,
and will not appear in `grantedByRoles` for any current resource.

### Filtering — which codes we expose

Even though `/roles` returns the full catalog, the index and the response
only include entries referenced by some resource in `KnownResources`. The
reference set is derived **dynamically** at runtime from the existing
`ResourceRegistry.getPolicySubjects()` snapshot:

```kotlin
private fun referencedLegacyCodes(): Set<String> =
    resourceRegistry.getPolicySubjects()
        .values
        .flatten()
        .filter { it.type == "urn:altinn:rolecode" }
        .map { it.value }
        .toSet()
```

This means if a new resource is added to `KnownResources` with a new role
code, the next refresh tick picks it up automatically — no redeploy needed.

## Implementation

### 1. New DTO

In
[Altinn3Client.kt](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt):

```kotlin
@Serializable
data class RoleExport(
    val id: String? = null,
    val name: String? = null,
    val code: String? = null,
    val description: String? = null,
    val urn: String? = null,
    val legacyRoleCode: String? = null,
)
```

The public response DTO added to
[ResourceMetadataApi.kt](src/main/kotlin/no/nav/fager/altinn/ResourceMetadataApi.kt):

```kotlin
@Serializable
data class RoleDetails(
    val name: String? = null,
    val description: String? = null,
)
```

And the response class gains a new field:

```kotlin
@Serializable
data class ResourceMetadataResponse(
    val resources: Map<String, ResourceMetadataEntry>,
    val accessPackages: Map<String, AccessPackageDetails> = emptyMap(),
    val roles: Map<String, RoleDetails> = emptyMap(),   // NEW
)
```

Keeping `RoleExport` (upstream) and `RoleDetails` (public) separate lets us
evolve the response independently of Altinn's payload shape.

### 2. Extend `Altinn3Client`

Add to the interface and implementation in
[Altinn3Client.kt](src/main/kotlin/no/nav/fager/altinn/Altinn3Client.kt):

```kotlin
suspend fun accessManagement_Roles(): Result<List<RoleExport>>
```

Implementation mirrors `accessManagement_AccessPackagesExport` — plain GET
via the existing unauthenticated `resourceRegistryClient`, `runCatching{…}`:

```kotlin
appendPathSegments("/accessmanagement/api/v1/meta/info/roles")
```

Top-level response is a JSON array —
`httpResponse.body<List<RoleExport>>()`.

### 3. New `RoleRegistry`

New file:
`src/main/kotlin/no/nav/fager/altinn/RoleRegistry.kt`. Mirrors
`AccessPackageRegistry` — a single cache entry (the whole `/roles` list)
plus a filtered in-memory index.

```kotlin
class RoleRegistry(
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
        metricsName = "role-registry",
        redisClient = redisConfig.createClient<List<RoleExport>>("role-export-v1"),
        loader = { _ -> altinn3Client.accessManagement_Roles().getOrThrow() },
        cacheTTL = cacheTTL.toJavaDuration(),
    )

    // legacyRoleCode (e.g. "dagl") → RoleExport
    // Filtered to only roles referenced by KnownResources.
    private val byLegacyCode = AtomicReference<Map<String, RoleExport>>(emptyMap())

    fun getRoles(): Map<String, RoleExport> = byLegacyCode.get()
}
```

#### Building the filtered index

After every successful fetch, rebuild:

```kotlin
private fun rebuildIndex(roles: List<RoleExport>) {
    val keep = referencedLegacyCodes()
    val index = mutableMapOf<String, RoleExport>()
    for (role in roles) {
        val code = role.legacyRoleCode ?: continue
        if (code !in keep) continue
        index[code] = role
    }
    byLegacyCode.set(index)

    val missing = keep - index.keys
    if (missing.isNotEmpty()) {
        log.warn("Referenced role codes not found in Altinn /roles: {}", missing)
    }
}
```

The unfiltered list is what's cached in Redis. We do not persist the
filtered view — `referencedLegacyCodes()` can change at runtime, and the
filter is cheap to reapply.

#### Background loops

Two loops, mirroring `AccessPackageRegistry`:

1. **Warm-up loop** — runs until `isReady` flips true.
   - Wait for `resourceRegistry.isReady()`. Until then, sleep 100 ms and
     retry.
   - Call `cache.get("_roles")`, wrapped in `retryWithBackoff`.
   - On success, `rebuildIndex(roles)` and flip `isReady = true`.

2. **Refresh loop** — runs until shutdown.
   - `cache.update("_roles")` to force a fresh upstream fetch.
   - On success, `rebuildIndex(roles)`. Sleep `cacheRefreshInterval`
     (10 minutes).
   - On failure, log and back off to 5 s.

#### Readiness gating

`RoleRegistry` registers with `Health` separately from `ResourceRegistry`
and `AccessPackageRegistry`. The app is ready when all three are ready.
`RoleRegistry`'s own readiness depends on `ResourceRegistry` being ready
first (enforced by the warm-up loop's `resourceRegistry.isReady()` poll).

### 4. Wire it up

In [Application.kt](src/main/kotlin/no/nav/fager/Application.kt), next to
`accessPackageRegistry`:

```kotlin
val roleRegistry = RoleRegistry(
    altinn3Client = altinn3Client,
    resourceRegistry = resourceRegistry,
    redisConfig = redisConfig,
    backgroundCoroutineScope = backgroundScope,
).also { Health.register(it) }
```

Update the `/resource-metadata` route to pass the role snapshot to the
builder:

```kotlin
buildResourceMetadataResponse(
    metadata = resourceRegistry.getResourceMetadata(),
    policySubjects = resourceRegistry.getPolicySubjects(),
    accessPackageIndex = accessPackageRegistry.getAccessPackages(),
    roleIndex = roleRegistry.getRoles(),   // NEW
)
```

### 5. Update `buildResourceMetadataResponse`

In [ResourceMetadataApi.kt](src/main/kotlin/no/nav/fager/altinn/ResourceMetadataApi.kt):

```kotlin
fun buildResourceMetadataResponse(
    metadata: Map<ResourceId, ResourceRegistryResource?>,
    policySubjects: Map<ResourceId, List<PolicySubject>>,
    accessPackageIndex: Map<String, IndexedAccessPackage> = emptyMap(),
    roleIndex: Map<String, RoleExport> = emptyMap(),   // NEW
): ResourceMetadataResponse
```

Additional steps inside:

1. While building `resources`, accumulate `allReferencedRoleCodes` (same as
   `grantedByRoles` values).
2. After the `accessPackages` map is built, build the `roles` map:
   ```kotlin
   val rolesMap = allReferencedRoleCodes.sorted().mapNotNull { code ->
       val role = roleIndex[code] ?: return@mapNotNull null
       code to RoleDetails(name = role.name, description = role.description)
   }.toMap(linkedMapOf())
   ```
3. Return `ResourceMetadataResponse(resources, accessPackagesMap, rolesMap)`.

### 6. OpenAPI

Same as the parent spec: **do not document this endpoint in
[src/main/resources/openapi.yaml](src/main/resources/openapi.yaml).** It is
a hidden route.

## Test plan

Tests live in `src/test/kotlin/no/nav/fager/ResourceMetadataTest.kt`. Follow
the existing `testWithSharedFakeApplication { … }` pattern.

1. **Fake stub** — extend
   [FakeApplication.kt](src/test/kotlin/no/nav/fager/fakes/FakeApplication.kt)
   to stub `GET /accessmanagement/api/v1/meta/info/roles` with a small
   fixture that contains:
   - Two referenced roles: `legacyRoleCode = "dagl"` (name `"Daglig leder"`)
     and `legacyRoleCode = "lede"` (name `"Styrets leder"`).
   - One unreferenced role: `legacyRoleCode = "unreferenced"` — this must
     NOT appear in the response.

2. **`roles` map present** — `GET /resource-metadata` returns 200 with a
   non-empty `roles` map.

3. **Expected display names** — `roles["dagl"].name == "Daglig leder"`,
   `roles["lede"].name == "Styrets leder"`.

4. **Direct lookup contract** — for every resource and every code in
   `grantedByRoles`, assert `roles[code]` is non-null. Guards against key
   drift.

5. **Unreferenced roles filtered** — assert `roles["unreferenced"]` is null,
   even though the stub returned it.

6. **Missing details tolerated** — unit test: a role code referenced by a
   resource but absent from `roleIndex` should:
   - still appear in that resource's `grantedByRoles`
   - have no entry under `roles[code]`

7. **`buildResourceMetadataResponse` unit test** — pass a `roleIndex` with
   one entry and assert `roles` map in the response is populated correctly
   with `name` and `description`.

8. **JSON shape** — assert the response JSON includes a `"roles"` key at
   the top level alongside `"resources"` and `"accessPackages"`, with the
   expected content for the known fixture roles.
