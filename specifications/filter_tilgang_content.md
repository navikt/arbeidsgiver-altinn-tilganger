# Specification: Filter tilgang content based on filter

## Background

Today, when a `Filter` is provided in a request, `filterRecursive` correctly removes organisations
that have no matching tilganger. However, the **content** of the `altinn2Tilganger` and
`altinn3Tilganger` sets on the *retained* nodes is returned in full — including tilganger outside
the filter.

In addition, no cross-referencing is performed between Altinn 2 and Altinn 3: a consumer filtering
on `nav_sykepenger_inntektsmelding` (Altinn 3) currently receives an empty `altinn2Tilganger` even
though `4936:1` is the known Altinn 2 counterpart of that resource.  The reverse also holds: a
consumer filtering on `4936:1` (Altinn 2) currently receives an empty `altinn3Tilganger` even
though all four `nav_xx_inntektsmelding` resources map to that code.

### Constraint: altinn2Tilganger is always derived

Since the Altinn 2 direct integration has been removed (see `remove_altinn2.md`), all
`altinn2Tilganger` values are **exclusively derived** from `altinn3Tilganger` via the
`KnownResources` mapping in `ResourceRegistry.kt`. It is therefore impossible for an organisation
node to have a non-empty `altinn2Tilganger` with an empty `altinn3Tilganger`. All test scenarios
and behaviour examples in this specification reflect this constraint.

## Scope

Change the filtering logic in `AltinnService.kt` so that:

1. Returned `altinn2Tilganger` and `altinn3Tilganger` on every retained node are restricted to the
   tilganger that are covered by the filter (direct or via cross-reference).
2. Altinn 2 ↔ Altinn 3 cross-references are resolved using the static `KnownResources` mapping
   already present in `ResourceRegistry.kt`.
3. `roller` and `tilgangspakker` are **not** affected by this change.
4. **Inclusion of organisations** (which nodes are kept in the hierarchy) is **unchanged** — the
   existing predicate based on the original `filter.altinn2Tilganger` / `filter.altinn3Tilganger`
   values is preserved.

## Cross-reference rules

### Altinn 3 filter → expand Altinn 2

For each Altinn 3 resource in the filter, include the Altinn 2 service codes that map *from* that
resource.

Example:
```
filter.altinn3 = { "nav_sykepenger_inntektsmelding" }
effectiveAltinn2 = { "4936:1" }   ← via KnownResources: nav_sykepenger_inntektsmelding → 4936:1
```

The Altinn 2 codes from *other* Altinn 3 resources that coincidentally share the same code are
**not** included:

```
nav_foreldrepenger_inntektsmelding also maps to 4936:1
→ but nav_foreldrepenger_inntektsmelding is NOT in the filter → not returned
```

### Altinn 2 filter → expand Altinn 3 (1-to-many)

For each Altinn 2 service code in the filter, include **all** Altinn 3 resources that map to that
code.

Example:
```
filter.altinn2 = { "4936:1" }
effectiveAltinn3 = {
    "nav_foreldrepenger_inntektsmelding",
    "nav_sykepenger_inntektsmelding",
    "nav_sykepenger_fritak-arbeidsgiverperiode",
    "nav_sykdom-i-familien_inntektsmelding",
}   ← all resources with altinn2Tjeneste = ["4936:1"] in KnownResources
```

## Algorithm

### Step 1 — Compute effective sets (once, in `filter()`)

```
effectiveAltinn3 = filter.altinn3Tilganger
                 ∪ { resourceId | KnownResources[resourceId].altinn2Tjeneste ∩ filter.altinn2Tilganger ≠ ∅ }

effectiveAltinn2 = filter.altinn2Tilganger
                 ∪ { a2 | ∃ resourceId ∈ filter.altinn3Tilganger: a2 ∈ KnownResources[resourceId].altinn2Tjeneste }
```

Early-return when `filter.isEmpty` — no stripping needed.

### Step 2 — Updated `filterRecursive`

Pass `effectiveAltinn3` and `effectiveAltinn2` alongside the original filter.

```
For each AltinnTilgang node:
  1. Skip if erSlettet && !inkluderSlettede  (unchanged)
  2. Recurse into underenheter  (unchanged)
  3. Inclusion check uses ORIGINAL filter values  (unchanged):
       matcherAltinn2 = node.altinn2Tilganger ∩ filter.altinn2Tilganger ≠ ∅
       matcherAltinn3 = node.altinn3Tilganger ∩ filter.altinn3Tilganger ≠ ∅
       keep if matcherAltinn2 || matcherAltinn3 || filtrerteUnderenheter.isNotEmpty()
  4. Content stripping uses EFFECTIVE sets:
       returnedAltinn2 = node.altinn2Tilganger ∩ effectiveAltinn2
       returnedAltinn3 = node.altinn3Tilganger ∩ effectiveAltinn3
```

## Behaviour table

| Filter | Org has | Inclusion | Returned altinn3 | Returned altinn2 |
|--------|---------|-----------|-----------------|-----------------|
| altinn3=`nav_sykepenger_inntektsmelding` | altinn3=`{nav_sykepenger, nav_foreldrepenger}`, altinn2=`{4936:1}` | ✅ (altinn3 match) | `{nav_sykepenger}` | `{4936:1}` |
| altinn2=`4936:1` | altinn3=`{nav_sykepenger, nav_foreldrepenger}`, altinn2=`{4936:1}` | ✅ (altinn2 match) | `{nav_sykepenger, nav_foreldrepenger}` | `{4936:1}` |
| altinn3=`nav_sykepenger_inntektsmelding` | altinn3=`{nav_foreldrepenger}`, altinn2=`{4936:1}` | ❌ (no altinn3 match; altinn2 not in original filter) | — | — |
| altinn2=`4936:1` | altinn3=`{nav_sykepenger_inntektsmelding, nav_arbeidsforhold_aa-registeret-innsyn-arbeidsgiver}`, altinn2=`{4936:1, 5441:1}` | ✅ (altinn2 match) | `{nav_sykepenger_inntektsmelding}` | `{4936:1}` |
| empty filter | anything | ✅ | unchanged | unchanged |

## Files to change

### `src/main/kotlin/no/nav/fager/altinn/AltinnService.kt`

#### `AltinnTilgangerResultat.filter()`

Replace the current single-line body with:

1. Early-return if `filter.isEmpty`.
2. Build `altinn2ToAltinn3` reverse map from `KnownResources`.
3. Compute `effectiveAltinn3` and `effectiveAltinn2`.
4. Call updated `filterRecursive` with effective sets.

#### `filterRecursive`

Change signature to accept `inkluderSlettede: Boolean`, `effectiveAltinn3: Set<String>`,
`effectiveAltinn2: Set<String>` (remove the `Filter` parameter — only the three values above
are needed inside the function).

Apply content-stripping via `intersect` on the effective sets.
Inclusion predicate uses the original filter (passed as arguments or pre-computed in `filter()`).

### `src/test/kotlin/no/nav/fager/AltinnTilgangerResultatTest.kt`

Add the following test cases:

1. **Altinn 2 filter expands to related Altinn 3 resources**
   - Input: org with `altinn3={nav_sykepenger_inntektsmelding, nav_foreldrepenger_inntektsmelding}`,
     `altinn2={4936:1}`
   - Filter: `altinn2={4936:1}`
   - Expected: both altinn3 resources returned, `altinn2={4936:1}`

2. **Altinn 3 filter includes mapped Altinn 2 but not sibling Altinn 3**
   - Input: org with `altinn3={nav_sykepenger_inntektsmelding, nav_foreldrepenger_inntektsmelding}`,
     `altinn2={4936:1}`
   - Filter: `altinn3={nav_sykepenger_inntektsmelding}`
   - Expected: `altinn3={nav_sykepenger_inntektsmelding}`, `altinn2={4936:1}`

3. **Org excluded when it only has sibling Altinn 3 resource**
   - Input: org with `altinn3={nav_foreldrepenger_inntektsmelding}`, `altinn2={4936:1}`
   - Filter: `altinn3={nav_sykepenger_inntektsmelding}`
   - Expected: org not in result (inclusion predicate: nav_foreldrepenger ∉ original filter.altinn3)

4. **Altinn 3 filter strips unrelated Altinn 2**
   - Input: org with `altinn3={nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger, nav_sykepenger_inntektsmelding}`,
     `altinn2={5810:1, 4936:1}`
   - Filter: `altinn3={nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger}`
   - Expected: `altinn3={nav_permittering-...}`, `altinn2={5810:1}` (4936:1 stripped, since
     `nav_sykepenger_inntektsmelding` is not in the filter)

## Existing tests

All existing tests in `AltinnTilgangerResultatTest.kt` check only which `orgnr` values are returned
(`alleOrgn()`), not the content of tilganger sets. The inclusion predicate is unchanged, so these
tests continue to pass without modification.

The `tilgangspakker`-assertion in the `nav_sykepenger_inntektsmelding` test also passes unchanged
since `tilgangspakker` is not stripped.

Note: the large `sampleJSON` fixture in the test file contains organisations with `altinn3Tilganger: []`
and non-empty `altinn2Tilganger`. This data was captured before the Altinn 2 direct integration was
removed and no longer reflects how production data looks. The fixture is only used to verify inclusion
logic (which `orgnr` values survive a filter), so the stale data does not affect correctness.

## What is NOT in scope

- Changes to `AltinnTilgang` data model (no new fields).
- Changes to `AltinnTilgangerResponse` and derived maps (`orgNrTilTilganger`, `tilgangTilOrgNr`) —
  these are derived from the returned hierarchy and will automatically reflect filtered content.
- Changes to `Filter` validation (KnownAltinn2Tjenester / KnownResourceIds checks unchanged).
- Changes to the cache key or `CACHE_VERSION` — the filter is applied **after** cache lookup, so
  cached data remains correct.
- Changes to `roller` or `tilgangspakker` fields.
