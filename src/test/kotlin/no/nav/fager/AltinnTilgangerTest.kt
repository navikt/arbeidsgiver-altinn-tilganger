package no.nav.fager

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import no.nav.fager.fakes.FakeApplication
import no.nav.fager.fakes.authorization
import kotlin.test.Test
import kotlin.test.assertEquals

class AltinnTilgangerTest {
    companion object {
        @JvmField
        @org.junit.ClassRule
        val app = FakeApplication(
            clientConfig = {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        )
    }

    @Test
    fun `henter altinn tilganger`() = app.runTest {
        app.altinnResponse(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText(
                """
                [
                  {
                    "partyUuid": "a1c831cf-c7b7-4e5e-9910-2ad9a05b4ec1",
                    "name": "MALMEFJORDEN OG RIDABU REGNSKAP",
                    "organizationNumber": "810825472",
                    "personId": null,
                    "partyId": 50166368,
                    "type": "Organization",
                    "unitType": "AS",
                    "isDeleted": false,
                    "onlyHierarchyElementWithNoAccess": true,
                    "authorizedResources": [],
                    "authorizedRoles": [],
                    "subunits": [
                      {
                        "partyUuid": "8656eab6-119b-4691-8a8a-6f51a203aba7",
                        "name": "SLEMMESTAD OG STAVERN REGNSKAP",
                        "organizationNumber": "910825496",
                        "personId": null,
                        "partyId": 50169034,
                        "type": "Organization",
                        "unitType": "BEDR",
                        "isDeleted": false,
                        "onlyHierarchyElementWithNoAccess": false,
                        "authorizedResources": [
                          "test-fager"
                        ],
                        "authorizedRoles": [],
                        "subunits": []
                      }
                    ]
                  }
                ]
                """.trimIndent(), ContentType.Application.Json
            )
        }
        app.altinn2Response(Get, "/api/serviceowner/reportees") {
            if (call.request.queryParameters["serviceCode"] == "4936")
                call.respondText(
                    """
                    [
                        {
                            "Name": "SLEMMESTAD OG STAVERN REGNSKAP",
                            "Type": "Business",
                            "OrganizationNumber": "910825496",
                            "ParentOrganizationNumber": "810825472",
                            "OrganizationForm": "BEDR",
                            "Status": "Active"
                        }
                    ]
                    """.trimIndent(), ContentType.Application.Json
                )
        }


        val tilganger: AltinnTilgangerResponse = client.post("/altinn-tilganger") {
            authorization(subject = "acr-high-11111111111")
            contentType(ContentType.Application.Json)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body()

        assertEquals(true, tilganger.isError)
        assertEquals(listOf("test-fager"), tilganger.hierarki[0].underenheter[0].altinn3Tilganger)
        assertEquals(listOf("4936:1"), tilganger.hierarki[0].underenheter[0].altinn2Tilganger)
        assertEquals(listOf("910825496"), tilganger.tilgangTilOrgNr["4936:1"])
        assertEquals(listOf("test-fager", "4936:1"), tilganger.orgNrTilTilganger["910825496"])
    }

}

