package no.nav.fager

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import no.nav.fager.altinn.AltinnTilgangerResponse
import no.nav.fager.fakes.FakeApplication
import no.nav.fager.fakes.authorization
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
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
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
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
                      },
                      {
                        "partyUuid": "8656bbb6-119b-4691-8a8a-6f51a203bba7",
                        "name": "SLEMMESTAD OG STAVERN REGNSKAP 2",
                        "organizationNumber": "910825554",
                        "personId": null,
                        "partyId": 50169035,
                        "type": "Organization",
                        "unitType": "BEDR",
                        "isDeleted": false,
                        "onlyHierarchyElementWithNoAccess": false,
                        "authorizedResources": [],
                        "authorizedRoles": [],
                        "subunits": []
                      },
                      {
                        "partyUuid": "7756eab6-119b-4691-8a8a-6f51a203aba7",
                        "name": "SLEMMESTAD OG STAVERN REGNSKAP SLETTET",
                        "organizationNumber": "910825999",
                        "personId": null,
                        "partyId": 50169333,
                        "type": "Organization",
                        "unitType": "BEDR",
                        "isDeleted": true,
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
        val altinn2Responses = mutableListOf(
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
            """,
            """
            [
                {
                    "Name": "SLEMMESTAD OG STAVERN REGNSKAP 2",
                    "Type": "Business",
                    "OrganizationNumber": "910825554",
                    "ParentOrganizationNumber": "810825472",
                    "OrganizationForm": "BEDR",
                    "Status": "Active"
                }
            ]
            """,
        )
        app.altinn2Response(Get, "/api/serviceowner/reportees") {
            if (call.request.queryParameters["serviceCode"] == "4936") {
                call.respondText(
                    altinn2Responses.removeFirstOrNull() ?: "[]", ContentType.Application.Json
                )
            }
        }

        val tilganger: AltinnTilgangerResponse = client.post("/altinn-tilganger") {
            authorization(subject = "acr-high-11111111111")
            contentType(ContentType.Application.Json)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body()

        assertEquals(true, tilganger.isError)
        assertEquals(2, tilganger.hierarki[0].underenheter.size)
        assertEquals(setOf("test-fager"), tilganger.hierarki[0].underenheter[0].altinn3Tilganger)
        assertEquals(setOf("4936:1"), tilganger.hierarki[0].underenheter[0].altinn2Tilganger)
        assertEquals(setOf("910825496", "910825554"), tilganger.tilgangTilOrgNr["4936:1"])
        assertEquals(setOf("test-fager", "4936:1"), tilganger.orgNrTilTilganger["910825496"])
        assertEquals(setOf("4936:1"), tilganger.orgNrTilTilganger["910825554"])
    }

    @Test
    fun `sjekk at skyggefelter finnes i json respons`() = app.runTest {
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
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
                      },
                      {
                        "partyUuid": "8656bbb6-119b-4691-8a8a-6f51a203bba7",
                        "name": "SLEMMESTAD OG STAVERN REGNSKAP 2",
                        "organizationNumber": "910825554",
                        "personId": null,
                        "partyId": 50169035,
                        "type": "Organization",
                        "unitType": "BEDR",
                        "isDeleted": false,
                        "onlyHierarchyElementWithNoAccess": false,
                        "authorizedResources": [],
                        "authorizedRoles": [],
                        "subunits": []
                      },
                      {
                        "partyUuid": "7756eab6-119b-4691-8a8a-6f51a203aba7",
                        "name": "SLEMMESTAD OG STAVERN REGNSKAP SLETTET",
                        "organizationNumber": "910825999",
                        "personId": null,
                        "partyId": 50169333,
                        "type": "Organization",
                        "unitType": "BEDR",
                        "isDeleted": true,
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

        val tilganger = client.post("/altinn-tilganger") {
            authorization(subject = "acr-high-11111111111")
            contentType(ContentType.Application.Json)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.bodyAsText()

        val expectedJson = """
        {
            "isError" : true,
            "hierarki" : [ {
            "orgnr" : "810825472",
            "orgNr" : "810825472",
            "altinn3Tilganger" : [ ],
            "altinn2Tilganger" : [ ],
            "underenheter" : [ {
              "orgnr" : "910825496",
              "orgNr" : "910825496",
              "altinn3Tilganger" : [ "test-fager" ],
              "altinn2Tilganger" : [ ],
              "underenheter" : [ ],
              "navn" : "SLEMMESTAD OG STAVERN REGNSKAP",
              "name" : "SLEMMESTAD OG STAVERN REGNSKAP",
              "organisasjonsform" : "BEDR",
              "organizationForm" : "BEDR"
            }, {
              "orgnr" : "910825554",
              "orgNr" : "910825554",
              "altinn3Tilganger" : [ ],
              "altinn2Tilganger" : [ ],
              "underenheter" : [ ],
              "navn" : "SLEMMESTAD OG STAVERN REGNSKAP 2",
              "name" : "SLEMMESTAD OG STAVERN REGNSKAP 2",
              "organisasjonsform" : "BEDR",
              "organizationForm" : "BEDR"
            } ],
            "navn" : "MALMEFJORDEN OG RIDABU REGNSKAP",
            "name" : "MALMEFJORDEN OG RIDABU REGNSKAP",
            "organisasjonsform" : "AS",
            "organizationForm" : "AS"
            } ],
            "orgNrTilTilganger" : {
            "910825496" : [ "test-fager" ],
            "910825554" : [ ]
            },
            "tilgangTilOrgNr" : {
            "test-fager" : [ "910825496" ]
            }
        }
        """.trimIndent()

        JSONAssert.assertEquals(expectedJson, tilganger, JSONCompareMode.LENIENT)
    }
}

