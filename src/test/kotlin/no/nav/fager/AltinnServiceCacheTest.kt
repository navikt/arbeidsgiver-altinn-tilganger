package no.nav.fager

import kotlinx.coroutines.test.runTest
import no.nav.fager.fakes.clients.FakeAltinn2Client
import no.nav.fager.fakes.clients.FakeAltinn3Client
import no.nav.fager.fakes.clients.FakeRedisClient
import kotlin.test.Test
import kotlin.test.assertTrue

class AltinnServiceCacheTest {
    @Test
    fun `cache entry settes`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client({
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
        })
        val altinn3Client = FakeAltinn3Client({
            listOf(
                AuthoririzedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business"
                )
            )
        })

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient)

        val fnr = "16120101181"
        val cacheKey = CacheKeyProvider.altinnTilgangerCacheKey(fnr)
        altinnService.hentTilganger(fnr, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey) == 1)
        assertTrue(
            altinnRedisClient.getCallCountWithArgs(
                altinnRedisClient::set.name, cacheKey,
                AltinnService.AltinnTilgangerResultat(
                    isError = false,
                    altinnTilganger = listOf(
                        AltinnTilgang(
                            orgNr = "910825496",
                            altinn3Tilganger = setOf("test-fager"),
                            altinn2Tilganger = setOf("4936:1"),
                            underenheter = listOf(),
                            name = "SLEMMESTAD OG STAVERN REGNSKAP",
                            organizationForm = "BEDR"
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
        val altinnRedisClient = FakeRedisClient({ AltinnService.AltinnTilgangerResultat(false, listOf()) })
        val altinn2Client = FakeAltinn2Client({
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
        })
        val altinn3Client = FakeAltinn3Client({
            listOf(
                AuthoririzedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business"
                )
            )
        })

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient)

        val fnr = "16120101181"
        val cacheKey = CacheKeyProvider.altinnTilgangerCacheKey(fnr)
        altinnService.hentTilganger(fnr, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey) == 1)
        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::set.name) == 0)

        assertTrue(altinn3Client.getCallCount(altinn3Client::hentAuthorizedParties.name) == 0)
        assertTrue(altinn2Client.getCallCount(altinn2Client::hentAltinn2Tilganger.name) == 0)
    }

    @Test
    fun `cache entry settes ikke på grunn av feil i altinn2 respons`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client({
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
        })
        val altinn3Client = FakeAltinn3Client({
            listOf(
                AuthoririzedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business"
                )
            )
        })

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient)

        val fnr = "16120101181"
        val cacheKey = CacheKeyProvider.altinnTilgangerCacheKey(fnr)
        altinnService.hentTilganger(fnr, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey) == 1)
        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::set.name) == 0)

        assertTrue(altinn3Client.getCallCountWithArgs(altinn3Client::hentAuthorizedParties.name, fnr) == 1)
        assertTrue(altinn2Client.getCallCountWithArgs(altinn2Client::hentAltinn2Tilganger.name, fnr) == 1)
    }

    @Test
    fun `cache treffes men ikke på tvers av fnr`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client({
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
        })
        val altinn3Client = FakeAltinn3Client({
            listOf(
                AuthoririzedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business"
                )
            )
        })

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient)

        val fnr1 = "16120101181"
        val fnr2 = "26903848935"

        val cacheKey1 = CacheKeyProvider.altinnTilgangerCacheKey(fnr1)
        val cacheKey2 = CacheKeyProvider.altinnTilgangerCacheKey(fnr2)

        altinnService.hentTilganger(fnr1, this)
        altinnService.hentTilganger(fnr2, this)

        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::get.name) == 2)
        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey1) == 1)
        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey2) == 1)


    }
}