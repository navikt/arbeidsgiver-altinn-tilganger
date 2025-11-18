package no.nav.fager.altinn

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.server.response.respondText
import no.nav.fager.fakes.fake
import no.nav.fager.fakes.testWithFakeApi
import no.nav.fager.texas.TexasAuthConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse


class Altinn2ClientTest {

    @Test
    fun `altinn 2 klient håndterer null verdier`() = testWithFakeApi { fakeTexas ->
        fakeTexas.stubs[HttpMethod.Post to "/token"] = {
            call.respondText(
                //language=json
                """
                {
                  "access_token": "fake-token",
                  "expires_in": 3600
                }
                """.trimIndent(),
                ContentType.Application.Json
            )
        }

        testWithFakeApi { fakeAltinn2 ->
            val responses = mutableListOf(
                //language=json
                """[
            {
              "Name": "Nore Ply",
              "Type": "Person",
              "OrganizationNumber": null,
              "ParentOrganizationNumber": null,
              "OrganizationForm": null,
              "Status": null
            },
            {
              "Name": "Nora Ply",
              "Type": "Person"
            }
            ]""".trimIndent(),
            )
            fakeAltinn2.stubs[Get to "/api/serviceowner/reportees"] = {
                call.respondText(
                    responses.removeFirstOrNull() ?: "[]", ContentType.Application.Json
                )
            }

            val altinn2Client = Altinn2ClientImpl(
                altinn2Config = Altinn2Config.fake(fakeAltinn2),
                texasAuthConfig = TexasAuthConfig.fake(fakeTexas)
            )

            altinn2Client.hentAltinn2Tilganger("42").let {
                assertFalse(it.isError)
                assertEquals(0, it.orgNrTilTjenester.size)
            }
        }
    }
}