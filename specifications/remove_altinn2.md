# Specification: Remove all Altinn 2 integration

## Background

The last Altinn 2 TjenesteDefinisjon `4596:1` ("Sykmelding - Oppgi naermeste leder med personalansvar") is being deleted on **15. juni 2026 kl 12:00**. After that date, there are no remaining Altinn 2 services to query. All Altinn 2 integration code, configuration, and tests should be removed.

The Altinn 3 resource `nav_syfo_oppgi-narmesteleder` already exists in the ResourceRegistry with an empty `altinn2Tjeneste` mapping, meaning this service has already been migrated in Altinn 3 and does not rely on Altinn 2 data.

## Scope

Remove **all** Altinn 2 HTTP calls, client code, data models, configuration, Maskinporten scope, NAIS outbound rules, and test infrastructure. Preserve the Altinn 2 service-code-to-resource-id mappings in `ResourceRegistry.kt` since those are used to enrich Altinn 3 responses with legacy service codes for consumers still filtering on them.

## Changes by file

### 1. Delete file: `src/main/kotlin/no/nav/fager/altinn/Altinn2Client.kt`

Delete the entire file. This removes:
- `Altinn2Config` (env var reader for `ALTINN_2_BASE_URL`, `ALTINN_2_API_KEY`)
- `Altinn2Tilganger` data class (the aggregated result of Altinn 2 calls)
- `Altinn2Tjeneste` data class (`serviceCode` + `serviceEdition`)
- `Altinn2Client` interface
- `Altinn2ClientImpl` class (HTTP client, Maskinporten auth, pagination, metadata validation)
- `Altinn2Reportee` serializable class
- `Altinn2TjenesteDefinisjon` class
- `tjenester` list (contained only `4596:1`)
- `Altinn2Tjenester` val (derived list of `"serviceCode:serviceEdition"` strings)
- Private helper `ReporteeResult`

**Important:** `Altinn2Tjeneste` is also used in `ResourceRegistry.kt` for the `altinn2Tjeneste` field on `Resource`. 
Replace it with a simple inline pair/string since it's only used for mapping

### 2. Modify: `src/main/kotlin/no/nav/fager/altinn/AltinnService.kt`

#### Remove `altinn2Client` dependency
- Remove constructor parameter `private val altinn2Client: Altinn2Client`
- Remove the import of `Altinn2Client`

#### Simplify `hentTilgangerFraAltinn(fnr)`
- Remove the parallel `altinn2TilgangerJob = async { altinn2Client.hentAltinn2Tilganger(fnr) }` call
- Remove `altinn2TilgangerJob.await()` and the `altinn2Tilganger` variable
- The Altinn 3 call remains: `altinn3Client.resourceOwner_AuthorizedParties(fnr)`

#### Simplify result merging
- Remove the `orgnrTilAltinn2Mapped` block that combines Altinn 2 direct results with Altinn 3-mapped results
- Keep the logic that maps Altinn 3 resources to Altinn 2 service codes via `resourceRegistry.resourceIdToAltinn2Tjeneste` (this is the Altinn 3 -> legacy Altinn 2 mapping, still needed for consumers)
- In `mapToHierarchy`, the `altinn2Tilganger` parameter should now come purely from the Altinn 3 resource mapping (no more direct Altinn 2 calls to merge)

#### Simplify `isError`
- Remove `altinn2Tilganger.isError` from the error computation. Only `altinn3TilgangerResult.isFailure` matters.

#### Simplify team logging
- Remove `altinn2Tilganger` from the team logger call

### 3. Modify: `src/main/kotlin/no/nav/fager/Api.kt`

#### `AltinnTilgang` data class
- **Keep** the `altinn2Tilganger: Set<String>` field. This field is part of the public API response and is populated from Altinn 3 resource-to-Altinn 2 mappings in `ResourceRegistry`. Removing it would be a breaking API change. Consumers may still filter on Altinn 2 service codes.

#### `Filter` data class
- **Keep** the `altinn2Tilganger: Set<String>` filter field for the same reason. The filter validation against `KnownAltinn2Tjenester` remains valid since those are derived from `ResourceRegistry`.

#### `AltinnTilgangerResponse`
- No changes needed. The `orgNrTilTilganger` and `tilgangTilOrgNr` maps already combine both sets.

### 4. Modify: `src/main/kotlin/no/nav/fager/Application.kt`

#### Remove Altinn 2 client initialization
- Remove the `altinn2Client` block (lines 218-228) which creates `Altinn2ClientImpl` and launches the background `validerKjenteTjenesterFinnesIMetadata()` coroutine
- Remove `Altinn2Config.nais()` from `ktorConfig` parameters
- Remove `altinn2Config` parameter from `ktorConfig` function signature
- Remove `altinn2Config` from the `main()` call to `ktorConfig`

#### Update AltinnService construction
- Remove `altinn2Client` from the `AltinnService(...)` constructor call

#### Remove imports
- Remove `Altinn2ClientImpl`, `Altinn2Config` imports

### 5. Modify: `src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt`

#### Keep `Resource` entries and their `altinn2Tjeneste` mappings
The `resourceIdToAltinn2Tjeneste` map is still needed. It maps Altinn 3 resources to legacy Altinn 2 service codes so that consumers can filter on `altinn2Tilganger` without breaking.

#### Keep `KnownAltinn2Tjenester`
This is still used for filter validation in `Api.kt`.

#### Handle `Altinn2Tjeneste` import
Since `Altinn2Client.kt` is deleted, `Altinn2Tjeneste` must be defined here (or inline). Move the data class:
```kotlin
data class Altinn2Tjeneste(
    val serviceCode: String,
    val serviceEdition: String,
)
```

#### Remove `Altinn2Tjenester` reference
`KnownAltinn2Tjenester` currently includes `Altinn2Tjenester` (from `Altinn2Client.kt`) in addition to the resource-mapped ones. Since `Altinn2Tjenester` came from the `tjenester` list (which only contained `4596:1`). `4596:1` Currently `nav_syfo_oppgi-narmesteleder` has `altinn2Tjeneste = listOf()` (empty). No consumers need to filter on `4596:1` anymore, simply remove the `Altinn2Tjenester` union from `KnownAltinn2Tjenester`

### 6. Modify NAIS configs

#### `nais/prod-gcp-altinn-tilganger.yaml`
- Remove env var `ALTINN_2_BASE_URL` (line 52)
- Remove Maskinporten scope `altinn:serviceowner/reportees` (line 69). Keep `altinn:accessmanagement/authorizedparties.resourceowner` (used by Altinn 3).
- Remove outbound external host `www.altinn.no` (line 133). Keep `platform.altinn.no` (used by Altinn 3).
- Remove or update the `altinn-tilganger` secret reference (line 56) if `ALTINN_2_API_KEY` is the only secret in it. If the secret also contains other values, just stop reading `ALTINN_2_API_KEY` in code (already handled by deleting `Altinn2Config`).

#### `nais/dev-gcp-altinn-tilganger.yaml`
- Remove env var `ALTINN_2_BASE_URL` (line 44)
- Remove Maskinporten scope `altinn:serviceowner/reportees` (line 63)
- Remove outbound external host `tt02.altinn.no` (line 135). Keep `platform.tt02.altinn.no`.
- Same secret consideration as prod.

### 7. Delete test file: `src/test/kotlin/no/nav/fager/altinn/Altinn2ClientTest.kt`

Delete entirely. Tests the Altinn 2 client null-handling which no longer exists.

### 8. Delete test file: `src/test/kotlin/no/nav/fager/fakes/clients/FakeAltinn2Client.kt`

Delete entirely. Mock implementation of the removed `Altinn2Client` interface.

### 9. Modify test: `src/test/kotlin/no/nav/fager/AltinnServiceTest.kt`

All 6 tests construct a `FakeAltinn2Client` and pass it to `AltinnService`. Update each test:
- Remove `FakeAltinn2Client` construction
- Remove `altinn2Client` from `AltinnService(...)` constructor
- Remove assertions that check `altinn2Client` call counts
- Update expected `AltinnTilgangerResultat` values: `altinn2Tilganger` in `AltinnTilgang` should now only contain values from the Altinn 3-to-Altinn 2 resource mapping (via `resourceIdToAltinn2Tjeneste`), not from direct Altinn 2 calls
- In `cache entry settes`: the expected `altinn2Tilganger` for orgnr `910825496` was `setOf("4936:1")` from a direct Altinn 2 call. After removal, this will be empty unless the Altinn 3 resources for that party map to `4936:1`.
- In `Beriker mappede altinn 2 tjenester fra altinn 3 ressurs`: this test already validates the Altinn 3-to-Altinn 2 mapping path and should mostly work as-is after removing the `altinn2Client` wiring.
- In `Altinn 2 tilganger inkluderes sammen med tilganger mappet fra altinn 3 ressurser`: update expected values since there are no longer direct Altinn 2 contributions.

### 10. Modify test: `src/test/kotlin/no/nav/fager/AltinnTilgangerResultatTest.kt`

This file tests filtering logic on pre-built `AltinnTilgangerResultat` objects. The test data contains `altinn2Tilganger` values like `"4596:1"`, `"4936:1"`, `"5902:1"`, etc.

**These tests can remain mostly unchanged** since they test the filtering logic, not the Altinn 2 client. The `altinn2Tilganger` field still exists in the API model. However:
- Review if any hardcoded values (like `"4936:1"` in filters) need to be in `KnownAltinn2Tjenester` for the `Filter` constructor validation. If `Filter` init validation still runs, ensure the test values are valid. Consider whether tests should use values from the resource-mapped set only.
- The large `sampleJSON` contains Altinn 2 service codes. This is fine as test fixture data.

### 11. Modify: `src/main/resources/openapi.yaml`

#### Update description
- Remove or rewrite the section "Litt om migrering fra altinn 2 til altinn 3" (lines 67-81) since the migration is complete.

#### Update examples
- Remove examples that filter on `altinn2Tilganger: ["4936:1"]` or update them to use a currently-mapped Altinn 2 service code.
- Consider keeping the `altinn2Tilganger` field documented since it remains in the API (populated from Altinn 3 resource mappings).

### 12. Check for any remaining references

After all changes, grep the codebase for:
- `Altinn2Client` - should have no references
- `Altinn2Config` - should have no references
- `altinn2Config` - should have no references
- `ALTINN_2_BASE_URL` - should have no references
- `ALTINN_2_API_KEY` - should have no references
- `hentAltinn2Tilganger` - should have no references
- `validerKjenteTjenesterFinnesIMetadata` - should have no references
- `serviceowner/reportees` - should have no references
- `Altinn2TjenesteDefinisjon` - should have no references

## What to preserve (do NOT remove)

1. **`altinn2Tilganger` field in `AltinnTilgang`** - public API field, populated from Altinn 3 resource mappings
2. **`altinn2Tilganger` field in `Filter`** - consumers may still filter on legacy service codes
3. **`resourceIdToAltinn2Tjeneste` in `ResourceRegistry`** - maps Altinn 3 resources to legacy Altinn 2 service codes
4. **`KnownAltinn2Tjenester`** - used for filter validation
5. **`Altinn2Tjeneste` data class** - move to `ResourceRegistry.kt`
6. **All `Resource` entries in `KnownResources`** with their `altinn2Tjeneste` lists

## Cache invalidation

Bump `CACHE_VERSION` in `AltinnService.kt` (currently `"v1"`) to `"v2"` to invalidate cached entries that contain Altinn 2 direct call results. This ensures all users get fresh responses from the Altinn 3-only path after deployment.

## Deployment considerations

- Deploy **after** 15. juni 2026 12:00 when `4596:1` is confirmed deleted
- Before deploying, verify that `esyfo-narmesteleder` (team-esyfo) and any other consumers of `4596:1` have migrated to using the Altinn 3 resource `nav_syfo_oppgi-narmesteleder`
- The secret `altinn-tilganger` in NAIS may still be referenced by `envFrom`. If `ALTINN_2_API_KEY` is the only value in it, the secret reference can be removed. Otherwise, just leave it (unused env vars are harmless).

## Testing

1. Run existing test suite after changes
2. Verify the app starts and `/internal/isready` returns 200
3. Verify `POST /altinn-tilganger` returns correct Altinn 3 tilganger with mapped Altinn 2 service codes
4. Verify filtering on `altinn2Tilganger` still works (e.g. `"5810:1"` should still match orgs with `nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger`)
5. Verify no outbound calls to `www.altinn.no` / `tt02.altinn.no` are made

## Summary of files

| File | Action |
|------|--------|
| `src/main/kotlin/no/nav/fager/altinn/Altinn2Client.kt` | **Delete** |
| `src/main/kotlin/no/nav/fager/altinn/AltinnService.kt` | Modify - remove Altinn 2 client call and merging |
| `src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt` | Modify - move `Altinn2Tjeneste`, update `KnownAltinn2Tjenester` |
| `src/main/kotlin/no/nav/fager/Api.kt` | No changes (keep `altinn2Tilganger` in API) |
| `src/main/kotlin/no/nav/fager/Application.kt` | Modify - remove Altinn 2 client setup and config |
| `src/main/resources/openapi.yaml` | Modify - update docs and examples |
| `nais/prod-gcp-altinn-tilganger.yaml` | Modify - remove Altinn 2 env/scope/outbound |
| `nais/dev-gcp-altinn-tilganger.yaml` | Modify - remove Altinn 2 env/scope/outbound |
| `src/test/kotlin/.../Altinn2ClientTest.kt` | **Delete** |
| `src/test/kotlin/.../FakeAltinn2Client.kt` | **Delete** |
| `src/test/kotlin/.../AltinnServiceTest.kt` | Modify - remove Altinn 2 client from tests |
| `src/test/kotlin/.../AltinnTilgangerResultatTest.kt` | Review - may need minor filter value updates |
