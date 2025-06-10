package no.nav.fager

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import no.nav.fager.fakes.FakeApplication
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AltinnTilgangerTest {
    companion object {
        @JvmField
        @RegisterExtension
        val app = FakeApplication(
            clientConfig = {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        )
    }

    private val fnr = generateSequence { "${(11111111111..99999999999).random()}" }.iterator()

    @Test
    fun `henter altinn tilganger`() = app.runTest {
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText(
                //language=json
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
                    "authorizedResources": ["test-fager"],
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
                        "authorizedRoles": [
                          "DAGL"
                        ],
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
        val altinn2Responses = listOf(
            //language=json
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
            //language=json
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
                call.request.queryParameters["${'$'}skip"]?.toIntOrNull()?.let {
                    call.respondText(
                        altinn2Responses.getOrNull(it) ?: "[]", ContentType.Application.Json
                    )
                }
            }
        }

        val assertResponse: (AltinnTilgangerResponse) -> Unit = {
            assertEquals(true, it.isError)
            assertEquals(2, it.hierarki[0].underenheter.size)
            assertEquals(setOf("test-fager"), it.hierarki[0].underenheter[0].altinn3Tilganger)
            assertEquals(setOf("4936:1"), it.hierarki[0].underenheter[0].altinn2Tilganger)
            assertEquals(setOf("910825496", "910825554"), it.tilgangTilOrgNr["4936:1"])
            assertEquals(setOf("test-fager", "4936:1"), it.orgNrTilTilganger["910825496"])
            assertEquals(
                setOf(
                    "4936:1",
                    "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                    "5810:1",
                ),
                it.orgNrTilTilganger["910825554"]
            )
            assertEquals(
                setOf("910825554"),
                it.tilgangTilOrgNr["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"]
            )
            assertEquals(
                setOf("910825554"),
                it.tilgangTilOrgNr["5810:1"]
            )
            assertEquals(
                setOf("810825472", "910825496"),
                it.tilgangTilOrgNr["test-fager"]
            )
        }

        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:${fnr.next()}")
            contentType(ContentType.Application.Json)
            setBody("")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)

        client.post("/m2m/altinn-tilganger") {
            header("Authorization", "Bearer fakem2mtoken")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "fnr": "${fnr.next()}"
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)
    }

    @Test
    fun `henter altinn tilganger for filter`() = app.runTest {
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText(
                //language=json
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
                        "partyUuid": "${UUID.randomUUID()}",
                        "name": "NOPE OG NOPE",
                        "organizationNumber": "123412344",
                        "personId": null,
                        "partyId": 12341234,
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
                          "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
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
                        "authorizedRoles": [
                          "DAGL"
                        ],
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
                          "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"
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
        val altinn2Responses = listOf(
            //language=json
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
            //language=json
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
                call.request.queryParameters["${'$'}skip"]?.toIntOrNull()?.let {
                    call.respondText(
                        altinn2Responses.getOrNull(it) ?: "[]", ContentType.Application.Json
                    )
                }
            } else {
                call.respondText("[]", ContentType.Application.Json)
            }
        }

        val assertResponse: (AltinnTilgangerResponse) -> Unit = {
            assertEquals(false, it.isError)
            assertEquals(2, it.hierarki[0].underenheter.size)
            assertEquals(
                setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                it.hierarki[0].underenheter[0].altinn3Tilganger
            )
            assertEquals(setOf("4936:1", "5810:1"), it.hierarki[0].underenheter[0].altinn2Tilganger)
            assertEquals(setOf("910825496", "910825554"), it.tilgangTilOrgNr["4936:1"])
            assertEquals(
                setOf(
                    "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                    "4936:1",
                    "5810:1",
                ),
                it.orgNrTilTilganger["910825496"]
            )
            assertEquals(
                setOf(
                    "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
                    "4936:1",
                    "5810:1",
                ), it.orgNrTilTilganger["910825554"]
            )
        }

        repeat(2) {
            client.post("/altinn-tilganger") {
                header("Authorization", "Bearer idporten-loa-high:11111111111")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "filter": {
                        "altinn2Tilganger": ["4936:1"],
                        "altinn3Tilganger": ["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"]
                    }
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>().also(assertResponse)

            client.post("/m2m/altinn-tilganger") {
                header("Authorization", "Bearer fakem2mtoken")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "fnr": "some-fnr",
                    "filter": {
                        "altinn2Tilganger": ["4936:1"],
                        "altinn3Tilganger": ["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"]
                    }
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>().also(assertResponse)

            client.post("/altinn-tilganger") {
                header("Authorization", "Bearer idporten-loa-high:11111111111")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "filter": {
                        "altinn3Tilganger": ["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"]
                    }
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>().also(assertResponse)

            client.post("/m2m/altinn-tilganger") {
                header("Authorization", "Bearer fakem2mtoken")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "fnr": "some-fnr",
                    "filter": {
                        "altinn3Tilganger": ["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"]
                    }
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>().also(assertResponse)

            client.post("/altinn-tilganger") {
                header("Authorization", "Bearer idporten-loa-high:11111111111")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "filter": {
                        "altinn2Tilganger": ["5810:1"]
                    }
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>().also(assertResponse)

            client.post("/m2m/altinn-tilganger") {
                header("Authorization", "Bearer fakem2mtoken")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "fnr": "some-fnr",
                    "filter": {
                        "altinn2Tilganger": ["5810:1"]
                    }
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>().also(assertResponse)

            client.post("/altinn-tilganger") {
                header("Authorization", "Bearer idporten-loa-high:11111111111")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "filter": {}
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>()

            client.post("/m2m/altinn-tilganger") {
                header("Authorization", "Bearer fakem2mtoken")
                contentType(ContentType.Application.Json)
                setBody(
                    //language=json
                    """
                {
                    "fnr": "some-fnr",
                    "filter": {}
                }
                """.trimIndent()
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<AltinnTilgangerResponse>()
        }
    }

    @Test
    fun `ugyldig filter gir feilmelding`() = app.runTest {
        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:11111111111")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "filter": {
                        "altinn2Tilganger": ["4936:1", "foo:bar"],
                        "altinn3Tilganger": ["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"]
                    }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:11111111111")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "filter": {
                        "altinn2Tilganger": ["4936:1"],
                        "altinn3Tilganger": ["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger", "foobar"]
                    }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }

        client.post("/m2m/altinn-tilganger") {
            header("Authorization", "Bearer fakem2mtoken")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "fnr": "some-fnr",
                    "filter": {
                        "altinn2Tilganger": ["4936:1", "foo:bar"],
                        "altinn3Tilganger": ["test-fager"]
                    }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }

        client.post("/m2m/altinn-tilganger") {
            header("Authorization", "Bearer fakem2mtoken")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "fnr": "some-fnr",
                    "filter": {
                        "altinn2Tilganger": ["4936:1"],
                        "altinn3Tilganger": ["nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger", "foobar"]
                    }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `inkluderSlettede = true returnerer også slettede underenheter for altinn3`() = app.runTest {
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText(
                //language=json
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
                    "authorizedResources": ["test-fager"],
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
                        "authorizedRoles": [
                          "DAGL"
                        ],
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

        val assertResponse: (AltinnTilgangerResponse) -> Unit = { virksomhet ->
            val hovedenhet = virksomhet.hierarki[0]
            val underenheter = hovedenhet.underenheter
            val aktiv1 = underenheter.first { it.orgnr == "910825496" }
            val aktiv2 = underenheter.first { it.orgnr == "910825554" }
            val slettet = underenheter.first { it.orgnr == "910825999" }

            assertEquals(3, underenheter.size)
            assertFalse(hovedenhet.erSlettet)
            assertFalse(aktiv1.erSlettet)
            assertFalse(aktiv2.erSlettet)
            assertTrue(slettet.erSlettet)
        }

        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:${fnr.next()}")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                  "filter": {
                    "inkluderSlettede": true
                  }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)

        client.post("/m2m/altinn-tilganger") {
            header("Authorization", "Bearer fakem2mtoken")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "fnr": "${fnr.next()}",
                    "filter": {
                      "inkluderSlettede": true
                    }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)
    }

    @Test
    fun `inkluderSlettede = true returnerer også slettede hovedenheter for altinn3`() = app.runTest {
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText(
                //language=json
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
                    "isDeleted": true,
                    "onlyHierarchyElementWithNoAccess": true,
                    "authorizedResources": ["test-fager"],
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
                        "authorizedRoles": [
                          "DAGL"
                        ],
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

        val assertResponse: (AltinnTilgangerResponse) -> Unit = { virksomhet ->
            val hovedenhet = virksomhet.hierarki[0]
            val underenheter = hovedenhet.underenheter
            val aktiv1 = underenheter.first { it.orgnr == "910825496" }
            val aktiv2 = underenheter.first { it.orgnr == "910825554" }
            val slettet = underenheter.first { it.orgnr == "910825999" }

            assertTrue(hovedenhet.erSlettet)
            assertEquals(3, underenheter.size)
            assertFalse(aktiv1.erSlettet)
            assertFalse(aktiv2.erSlettet)
            assertTrue(slettet.erSlettet)
        }

        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:${fnr.next()}")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                  "filter": {
                    "inkluderSlettede": true
                  }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)

        client.post("/m2m/altinn-tilganger") {
            header("Authorization", "Bearer fakem2mtoken")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "fnr": "${fnr.next()}",
                    "filter": {
                      "inkluderSlettede": true
                    }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)
    }

    @Test
    fun `inkluderSlettede = false returnerer ikke slettede hovedenheter for altinn3`() = app.runTest {
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText(
                //language=json
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
                    "isDeleted": true,
                    "onlyHierarchyElementWithNoAccess": true,
                    "authorizedResources": ["test-fager"],
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
                        "authorizedRoles": [
                          "DAGL"
                        ],
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

        val assertResponse: (AltinnTilgangerResponse) -> Unit = { virksomhet ->
            assertTrue(virksomhet.hierarki.isEmpty())
            assertTrue(virksomhet.tilgangTilOrgNr.isEmpty())
            assertTrue(virksomhet.orgNrTilTilganger.isEmpty())
        }

        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:${fnr.next()}")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                  "filter": {
                    "inkluderSlettede": false
                  }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)

        client.post("/m2m/altinn-tilganger") {
            header("Authorization", "Bearer fakem2mtoken")
            contentType(ContentType.Application.Json)
            setBody(
                //language=json
                """
                {
                    "fnr": "${fnr.next()}",
                    "filter": {
                      "inkluderSlettede": false
                    }
                }
                """.trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)
    }

    @Test
    fun `returnerer ikke enhet fra Altinn 2 dersom den ikke finnes i Altinn 3`() = app.runTest {
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText(
                //language=json
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
                    "authorizedResources": ["test-fager"],
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
        val altinn2Responses = listOf(
            //language=json
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
            //language=json
            """
            [
                {
                    "Name": "SLEMMESTAD OG STAVERN REGNSKAP SLETTET",
                    "Type": "Business",
                    "OrganizationNumber": "910825554",
                    "ParentOrganizationNumber": "810825472",
                    "OrganizationForm": "BEDR",
                    "Status": "Inactive"
                }
            ]
            """,
        )

        app.altinn2Response(Get, "/api/serviceowner/reportees") {
            if (call.request.queryParameters["serviceCode"] == "4936") {
                call.request.queryParameters["${'$'}skip"]?.toIntOrNull()?.let {
                    call.respondText(
                        altinn2Responses.getOrNull(it) ?: "[]", ContentType.Application.Json
                    )
                }
            }
        }

        val assertResponse: (AltinnTilgangerResponse) -> Unit = {
            assertEquals(1, it.hierarki[0].underenheter.size)
            assertEquals(1, it.hierarki.size)
        }

        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:${fnr.next()}")
            contentType(ContentType.Application.Json)
            setBody("")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<AltinnTilgangerResponse>().also(assertResponse)
    }
}