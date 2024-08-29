package no.nav.fager

import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AltinnTilgangerTest {


    @Test
    fun `henter altinn tilganger`() = testApplication {
        externalServices {
            hosts("https://maskinporten.dummy") {
                routing {
                    post("/maskinporten/token") {
                        call.respondText(
                            """
                           {
                             "access_token": "foo",
                             "expires_in": 3600
                           } 
                        """, ContentType.Application.Json
                        )
                    }
                }
            }

            hosts("http://altinn.test") {
                routing {
                    post("/resourceowner/authorizedparties") {
                        call.respondText(
                            """
                                [
                                  {
                                    "partyUuid": "8a884071-8921-4ecf-93a8-c8502df5e3f8",
                                    "name": "Ola Nordmann",
                                    "organizationNumber": "string",
                                    "personId": "01017012345",
                                    "type": "Person",
                                    "partyId": 50001337,
                                    "unitType": "string",
                                    "isDeleted": false,
                                    "onlyHierarchyElementWithNoAccess": false,
                                    "authorizedResources": [
                                      [
                                        "app_org_appname",
                                        "someresourceid"
                                      ]
                                    ],
                                    "authorizedRoles": [
                                      "PRIV"
                                    ],
                                    "subunits": [
                                      "string"
                                    ]
                                  }
                                ]
                        """, ContentType.Application.Json
                        )
                    }
                }
            }
        }

        application {
            mockKtorConfig(
                httpClientEngine = this@testApplication.client.engine
            )
        }

        client.post("/altinn-tilganger") {
            authorization(subject = "acr-high-11111111111")
            contentType(ContentType.Application.Json)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}

