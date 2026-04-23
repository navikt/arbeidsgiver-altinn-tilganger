package no.nav.fager

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.fager.altinn.KnownResourceIds
import no.nav.fager.altinn.ResourceMetadataResponse
import no.nav.fager.altinn.buildResourceMetadataResponse
import no.nav.fager.altinn.PolicySubject
import no.nav.fager.altinn.ResourceRegistryResource
import no.nav.fager.fakes.testWithFakeApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

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

        // access packages extracted
        assertEquals(listOf("regnskapsforer-lonn"), entry.grantedByAccessPackages)
    }

    @Test
    fun `resource-metadata never contains urn prefix in response`() = testWithFakeApplication {
        val responseText = client.get("/resource-metadata").bodyAsText()
        assertFalse(responseText.contains("urn:altinn:"), "Response should not contain 'urn:altinn:' prefix")
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
}


