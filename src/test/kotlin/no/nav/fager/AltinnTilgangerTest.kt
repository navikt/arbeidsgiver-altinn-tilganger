package no.nav.fager

import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import no.nav.fager.fakes.FakeApplication
import no.nav.fager.fakes.authorization
import kotlin.test.Test
import kotlin.test.assertEquals

class AltinnTilgangerTest {
    companion object {
        @JvmField
        @org.junit.ClassRule
        val app = FakeApplication()
    }

    @Test
    fun `henter altinn tilganger`() = app.runTest {
        app.altinnResponse(Post, "/resourceowner/authorizedparties") {
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


        client.post("/altinn-tilganger") {
            authorization(subject = "acr-high-11111111111")
            contentType(ContentType.Application.Json)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

}

