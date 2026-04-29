package no.nav.fager

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.fager.altinn.AccessPackageArea
import no.nav.fager.altinn.AccessPackageDetails
import no.nav.fager.altinn.AccessPackageExportArea
import no.nav.fager.altinn.AccessPackageExportGroup
import no.nav.fager.altinn.AccessPackageExportPackage
import no.nav.fager.altinn.IndexedAccessPackage
import no.nav.fager.altinn.KnownResourceIds
import no.nav.fager.altinn.PolicySubject
import no.nav.fager.altinn.ResourceMetadataResponse
import no.nav.fager.altinn.ResourceRegistryResource
import no.nav.fager.altinn.buildResourceMetadataResponse
import no.nav.fager.fakes.testWithFakeApplication
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceMetadataTest {

    @Test
    fun `GET resource-metadata returns 200 with all known resources`() = testWithFakeApplication {
        val response = client.get("/resource-metadata")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.body<ResourceMetadataResponse>()
        // every known resource id should be present
        KnownResourceIds.distinct().forEach { resourceId ->
            assertNotNull(body.resources[resourceId], "Missing resource: $resourceId")
        }
    }

    @Test
    fun `GET resource-metadata requires no authentication`() = testWithFakeApplication {
        // No Authorization header
        val response = client.get("/resource-metadata")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `resource-metadata passes through metadata fields`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        val permitteringId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
        val entry = body.resources[permitteringId]!!
        assertEquals(permitteringId, entry.metadata.identifier)
        assertNotNull(entry.metadata.title.nb)
        assertNotNull(entry.metadata.title.nn)
        assertNotNull(entry.metadata.title.en)
        assertEquals("GenericAccessResource", entry.metadata.resourceType)
        assertEquals("Completed", entry.metadata.status)
        assertEquals(true, entry.metadata.delegable)
    }

    @Test
    fun `resource-metadata extracts roles and access packages correctly`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        val permitteringId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
        val entry = body.resources[permitteringId]!!

        // roles extracted and sorted
        assertEquals(listOf("dagl", "lede"), entry.grantedByRoles)

        // access packages contain stripped ids (unchanged shape)
        assertEquals(listOf("regnskapsforer-lonn"), entry.grantedByAccessPackages)
    }

    @Test
    fun `grantedByAccessPackages uses stripped ids not full urns`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        val permitteringId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
        val entry = body.resources[permitteringId]!!

        // shape unchanged: stripped ids, no urn prefix
        entry.grantedByAccessPackages.forEach { id ->
            assertFalse(id.startsWith("urn:"), "grantedByAccessPackages should use stripped ids, got: $id")
        }
    }

    @Test
    fun `distinct urn namespaces - package keys vs area urns`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        // Package keys use urn:altinn:accesspackage: prefix
        body.accessPackages.keys.forEach { key ->
            assertTrue(key.startsWith("urn:altinn:accesspackage:"), "Package key should start with urn:altinn:accesspackage:, got: $key")
        }

        // Area urns use accesspackage:area: prefix (no urn:altinn:)
        body.accessPackages.values.forEach { pkg ->
            pkg.area?.urn?.let { areaUrn ->
                assertTrue(areaUrn.startsWith("accesspackage:area:"), "Area urn should start with accesspackage:area:, got: $areaUrn")
                assertFalse(areaUrn.startsWith("urn:altinn:"), "Area urn should NOT start with urn:altinn:, got: $areaUrn")
            }
        }
    }

    @Test
    fun `grantedByRoles still contains stripped values not urns`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        val permitteringId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
        val entry = body.resources[permitteringId]!!

        // roles should NOT contain urn prefix
        entry.grantedByRoles.forEach { role ->
            assertFalse(role.startsWith("urn:"), "Role should be stripped value, got: $role")
        }
    }

    @Test
    fun `resource-metadata is deterministic`() = testWithFakeApplication {
        val response1 = client.get("/resource-metadata").bodyAsText()
        val response2 = client.get("/resource-metadata").bodyAsText()
        assertEquals(response1, response2, "Response should be deterministic across calls")
    }

    @Test
    fun `buildResourceMetadataResponse fails on missing metadata`() {
        val metadata = mapOf("some-resource" to null as ResourceRegistryResource?)
        val policySubjects = mapOf("some-resource" to emptyList<PolicySubject>())

        assertFailsWith<IllegalStateException> {
            buildResourceMetadataResponse(metadata, policySubjects)
        }
    }

    @Test
    fun `resource-metadata is not in openapi yaml`() {
        val openapiContent = File("src/main/resources/openapi.yaml").readText()
        assertFalse(openapiContent.contains("resource-metadata"), "resource-metadata should not appear in openapi.yaml")
    }

    @Test
    fun `altinn-tilganger still requires auth`() = testWithFakeApplication {
        // No Authorization header for altinn-tilganger should fail
        val response = client.post("/altinn-tilganger") {
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `resource-metadata contains accessPackages map keyed by full urn`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        // accessPackages keys are full urns derived from grantedByAccessPackages stripped ids
        val allReferencedUrns = body.resources.values
            .flatMap { it.grantedByAccessPackages }
            .map { "urn:altinn:accesspackage:$it" }
            .toSet()

        assertEquals(allReferencedUrns, body.accessPackages.keys)
    }

    @Test
    fun `accessPackages contains expected details`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        val pkg = body.accessPackages["urn:altinn:accesspackage:regnskapsforer-lonn"]
        assertNotNull(pkg, "Expected access package details for regnskapsforer-lonn")
        assertEquals("Regnskapsfører lønn", pkg.name)
        assertEquals("Denne fullmakten gir tilgang til lønnstjenester for regnskapsførere.", pkg.description)
        assertNotNull(pkg.area)
        assertEquals("accesspackage:area:skatt_avgift_regnskap_og_toll", pkg.area!!.urn)
        assertEquals("Skatt, avgift, regnskap og toll", pkg.area!!.name)
    }

    @Test
    fun `accessPackages area is a singular object not a list`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        body.accessPackages.values.forEach { pkg ->
            // area is nullable object - not a list (enforced by type system, but also check the JSON)
            assertNotNull(pkg.area, "Expected area to be non-null for referenced packages in test fixture")
        }
    }

    @Test
    fun `unreferenced access packages are filtered from response`() = testWithFakeApplication {
        val body = client.get("/resource-metadata").body<ResourceMetadataResponse>()

        // These packages exist in the export fixture but are not referenced by any resource
        assertNull(body.accessPackages["urn:altinn:accesspackage:unreferenced-pkg-1"])
        assertNull(body.accessPackages["urn:altinn:accesspackage:unreferenced-pkg-2"])
        assertNull(body.accessPackages["urn:altinn:accesspackage:unreferenced-pkg-3"])
    }

    @Test
    fun `buildResourceMetadataResponse handles multi-area by keeping first occurrence`() {
        // Synthetic test: same urn under two different areas
        val area1 = AccessPackageExportArea(
            id = "area-1", name = "Area One", urn = "accesspackage:area:one", description = "First area"
        )
        val pkg = AccessPackageExportPackage(
            id = "pkg-1", urn = "urn:altinn:accesspackage:multi-area-pkg",
            name = "Multi Area Pkg", description = "Appears under two areas"
        )

        val index = mapOf(
            "urn:altinn:accesspackage:multi-area-pkg" to IndexedAccessPackage(
                pkg = pkg,
                area = area1, // first occurrence wins
            )
        )

        // Provide metadata for all known resources
        val metadata = KnownResourceIds.distinct().associateWith { resourceId ->
            ResourceRegistryResource(identifier = resourceId)
        }
        val policySubjects = KnownResourceIds.distinct().associateWith { resourceId ->
            if (resourceId == "test-fager") listOf(
                PolicySubject(
                    type = "urn:altinn:accesspackage",
                    value = "multi-area-pkg",
                    urn = "urn:altinn:accesspackage:multi-area-pkg"
                )
            ) else emptyList()
        }

        val response = buildResourceMetadataResponse(metadata, policySubjects, index)
        val details = response.accessPackages["urn:altinn:accesspackage:multi-area-pkg"]
        assertNotNull(details)
        // area should be the first occurrence
        assertEquals("accesspackage:area:one", details.area?.urn)
        assertEquals("Area One", details.area?.name)
    }

    @Test
    fun `missing access package details are tolerated`() {
        // urn referenced by resource but not in export
        val metadata = KnownResourceIds.distinct().associateWith { resourceId ->
            ResourceRegistryResource(identifier = resourceId)
        }
        val policySubjects = KnownResourceIds.distinct().associateWith { resourceId ->
            if (resourceId == "test-fager") listOf(
                PolicySubject(
                    type = "urn:altinn:accesspackage",
                    value = "missing-pkg",
                    urn = "urn:altinn:accesspackage:missing-pkg"
                )
            ) else emptyList()
        }

        val response = buildResourceMetadataResponse(metadata, policySubjects, emptyMap())

        // The stripped id still appears in grantedByAccessPackages
        assertTrue(response.resources["test-fager"]!!.grantedByAccessPackages.contains("missing-pkg"))
        // But the full urn is absent from the top-level accessPackages map
        assertFalse(response.accessPackages.containsKey("urn:altinn:accesspackage:missing-pkg"))
    }

    @Test
    fun `resource-metadata response shape matches expected JSON`() = testWithFakeApplication {
        val responseText = client.get("/resource-metadata").bodyAsText()

        //language=json
        val expected = """
        {
          "resources": {
            "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger": {
              "metadata": {
                "identifier": "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                "title": {
                  "nb": "Tittel for nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                  "nn": "Tittel nn for nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                  "en": "Title for nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
                },
                "rightDescription": {
                  "nb": "Rettighet for nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                  "nn": "Rettighet nn for nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                  "en": "Right for nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
                },
                "resourceType": "GenericAccessResource",
                "status": "Completed",
                "delegable": true
              },
              "grantedByRoles": ["dagl", "lede"],
              "grantedByAccessPackages": ["regnskapsforer-lonn"]
            },
            "nav_sosialtjenester_digisos-avtale": {
              "metadata": {
                "identifier": "nav_sosialtjenester_digisos-avtale",
                "title": {
                  "nb": "Tittel for nav_sosialtjenester_digisos-avtale",
                  "nn": "Tittel nn for nav_sosialtjenester_digisos-avtale",
                  "en": "Title for nav_sosialtjenester_digisos-avtale"
                },
                "rightDescription": {
                  "nb": "Rettighet for nav_sosialtjenester_digisos-avtale",
                  "nn": "Rettighet nn for nav_sosialtjenester_digisos-avtale",
                  "en": "Right for nav_sosialtjenester_digisos-avtale"
                },
                "resourceType": "GenericAccessResource",
                "status": "Completed",
                "delegable": true
              },
              "grantedByRoles": [],
              "grantedByAccessPackages": []
            }
          },
          "accessPackages": {
            "urn:altinn:accesspackage:regnskapsforer-lonn": {
              "name": "Regnskapsfører lønn",
              "description": "Denne fullmakten gir tilgang til lønnstjenester for regnskapsførere.",
              "area": {
                "urn": "accesspackage:area:skatt_avgift_regnskap_og_toll",
                "name": "Skatt, avgift, regnskap og toll",
                "description": "Tilgangspakker knyttet til skatt, avgift, regnskap og toll."
              }
            }
          }
        }
        """.trimIndent()

        JSONAssert.assertEquals(expected, responseText, JSONCompareMode.LENIENT)
    }
}




