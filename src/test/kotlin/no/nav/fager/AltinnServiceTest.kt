package no.nav.fager

import kotlinx.coroutines.test.runTest
import no.nav.fager.altinn.Altinn2Tilganger
import no.nav.fager.altinn.Altinn2Tjeneste
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.AltinnTilgang
import no.nav.fager.altinn.AuthorizedParty
import no.nav.fager.fakes.clients.FakeAltinn2Client
import no.nav.fager.fakes.clients.FakeAltinn3Client
import no.nav.fager.fakes.clients.FakeRedisClient
import kotlin.test.Test
import kotlin.test.assertTrue

class AltinnServiceTest {
    @Test
    fun `cache entry settes`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client {
            Altinn2Tilganger(
                isError = false,
                orgNrTilTjenester = mapOf(
                    "910825496" to listOf(
                        Altinn2Tjeneste(
                            serviceCode = "4936",
                            serviceEdition = "1"
                        )
                    )
                )
            )
        }
        val altinn3Client = FakeAltinn3Client {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, emptyMap())

        val fnr = "16120101181"
        altinnService.hentTilganger(fnr, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr) == 1)
        assertTrue(
            altinnRedisClient.getCallCountWithArgs(
                altinnRedisClient::set.name, fnr,
                AltinnService.AltinnTilgangerResultat(
                    isError = false,
                    altinnTilganger = listOf(
                        AltinnTilgang(
                            orgnr = "910825496",
                            altinn3Tilganger = setOf("test-fager"),
                            altinn2Tilganger = setOf("4936:1"),
                            underenheter = listOf(),
                            navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                            organisasjonsform = "BEDR"
                        )
                    )
                )
            ) == 1
        )

        assertTrue(altinn3Client.getCallCountWithArgs(altinn3Client::hentAuthorizedParties.name, fnr) == 1)
        assertTrue(altinn2Client.getCallCountWithArgs(altinn2Client::hentAltinn2Tilganger.name, fnr) == 1)
    }


    @Test
    fun `cache entry eksisterer, klienter kalles ikke`() = runTest {
        val altinnRedisClient = FakeRedisClient { AltinnService.AltinnTilgangerResultat(false, listOf()) }
        val altinn2Client = FakeAltinn2Client {
            Altinn2Tilganger(
                isError = false,
                orgNrTilTjenester = mapOf(
                    "910825496" to listOf(
                        Altinn2Tjeneste(
                            serviceCode = "4936",
                            serviceEdition = "1"
                        )
                    )
                )
            )
        }
        val altinn3Client = FakeAltinn3Client {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        }


        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, emptyMap())

        val fnr = "16120101181"
        altinnService.hentTilganger(fnr, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr) == 1)
        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::set.name) == 0)

        assertTrue(altinn3Client.getCallCount(altinn3Client::hentAuthorizedParties.name) == 0)
        assertTrue(altinn2Client.getCallCount(altinn2Client::hentAltinn2Tilganger.name) == 0)
    }

    @Test
    fun `cache entry settes ikke på grunn av feil i altinn2 respons`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client {
            Altinn2Tilganger(
                isError = true,
                orgNrTilTjenester = mapOf(
                    "910825496" to listOf(
                        Altinn2Tjeneste(
                            serviceCode = "4936",
                            serviceEdition = "1"
                        )
                    )
                )
            )
        }
        val altinn3Client = FakeAltinn3Client {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, emptyMap())

        val fnr = "16120101181"
        altinnService.hentTilganger(fnr, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr) == 1)
        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::set.name) == 0)

        assertTrue(altinn3Client.getCallCountWithArgs(altinn3Client::hentAuthorizedParties.name, fnr) == 1)
        assertTrue(altinn2Client.getCallCountWithArgs(altinn2Client::hentAltinn2Tilganger.name, fnr) == 1)
    }

    @Test
    fun `cache treffes men ikke på tvers av fnr`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client {
            Altinn2Tilganger(
                isError = false,
                orgNrTilTjenester = mapOf(
                    "910825496" to listOf(
                        Altinn2Tjeneste(
                            serviceCode = "4936",
                            serviceEdition = "1"
                        )
                    )
                )
            )
        }
        val altinn3Client = FakeAltinn3Client {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, emptyMap())

        val fnr1 = "16120101181"
        val fnr2 = "26903848935"

        altinnService.hentTilganger(fnr1, this)
        altinnService.hentTilganger(fnr2, this)

        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::get.name) == 2)
        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr1) == 1)
        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr2) == 1)


    }

    @Test
    fun `Beriker mappede altinn 2 tjenester fra altinn 3 ressurs`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client {
            Altinn2Tilganger(
                isError = false,
                orgNrTilTjenester = emptyMap()
            )
        }

        val altinn3Client = FakeAltinn3Client {
            listOf(
                AuthorizedParty(
                    name = "ET ANNET REGNSKAP",
                    organizationNumber = "111111111",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
            )
        }
        val altinn3TilAltinn2Map = mapOf(
            Pair(
                "test-fager",
                listOf(Altinn2Tjeneste("5810", "1"))
            )
        )

        val fnr = "16120101181"
        val altinnService =
            AltinnService(altinn2Client, altinn3Client, altinnRedisClient, altinn3TilAltinn2Map)

        val tilganger = altinnService.hentTilganger(fnr, this)

        assertTrue(tilganger.altinnTilganger.count() == 1)
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.count() == 1)
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.first() == "5810:1")
    }


    @Test
    fun `Altinn 2 respons velges over lokal Altinn 2 mapping ved duplikate tjenester`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client {
            Altinn2Tilganger(
                isError = false,
                orgNrTilTjenester = mapOf(
                    "910825496" to listOf(
                        Altinn2Tjeneste(
                            serviceCode = "4936",
                            serviceEdition = "1"
                        )
                    )
                )
            )
        }
        val altinn3Client = FakeAltinn3Client {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
                AuthorizedParty(
                    name = "ET ANNET REGNSKAP",
                    organizationNumber = "111111111",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
            )
        }
        val altinn3TilAltinn2Map = mapOf(
            Pair(
                "test-fager",
                listOf(Altinn2Tjeneste("5810", "1"))
            )
        )

        val fnr = "16120101181"
        val altinnService =
            AltinnService(altinn2Client, altinn3Client, altinnRedisClient, altinn3TilAltinn2Map)

        val tilganger = altinnService.hentTilganger(fnr, this)
        assertTrue(tilganger.altinnTilganger.count() == 2)
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "910825496" }.altinn2Tilganger.count() == 1)
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "910825496" }.altinn2Tilganger.first() == "4936:1")
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.count() == 1)
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.first() == "5810:1")
    }
}