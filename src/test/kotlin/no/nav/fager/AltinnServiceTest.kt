package no.nav.fager

import kotlinx.coroutines.test.runTest
import no.nav.fager.altinn.*
import no.nav.fager.altinn.AltinnService.AltinnTilgangerResultat
import no.nav.fager.fakes.clients.FakeAltinn2Client
import no.nav.fager.fakes.clients.FakeAltinn3Client
import no.nav.fager.fakes.clients.FakeRedisClient
import no.nav.fager.redis.RedisConfig
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val altinn3Client = FakeAltinn3Client(resourceOwner_AuthorizedPartiesHandler = {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    authorizedRoles = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        })
        val resourceRegistry = ResourceRegistry(FakeAltinn3Client(), RedisConfig.local(), null).also {
            it.updatePolicySubjectsForKnownResources {
                listOf(
                    PolicySubject(
                        urn = "urn:altinn:rolecode:lede",
                        type = "urn:altinn:rolecode",
                        value = "lede",
                    )
                )
            }
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val fnr = "16120101181"
        altinnService.hentTilganger(fnr, Filter.empty, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr) == 1)
        assertTrue(
            altinnRedisClient.getCallCountWithArgs(
                altinnRedisClient::set.name, fnr,
                AltinnTilgangerResultat(
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

        assertTrue(altinn3Client.getCallCountWithArgs(altinn3Client::resourceOwner_AuthorizedParties.name, fnr) == 1)
        assertTrue(altinn2Client.getCallCountWithArgs(altinn2Client::hentAltinn2Tilganger.name, fnr) == 1)
    }


    @Test
    fun `cache entry eksisterer, klienter kalles ikke`() = runTest {
        val altinnRedisClient = FakeRedisClient(
            mutableMapOf(
                "16120101181" to AltinnTilgangerResultat(
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
            )
        )
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
        val altinn3Client = FakeAltinn3Client(resourceOwner_AuthorizedPartiesHandler = {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    authorizedRoles = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        })
        val resourceRegistry = ResourceRegistry(FakeAltinn3Client(), RedisConfig.local(), null).also {
            it.updatePolicySubjectsForKnownResources {
                listOf(
                    PolicySubject(
                        urn = "urn:altinn:rolecode:lede",
                        type = "urn:altinn:rolecode",
                        value = "lede",
                    )
                )
            }
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val fnr = "16120101181"
        altinnService.hentTilganger(fnr, Filter.empty, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr) == 1)
        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::set.name) == 0)

        assertTrue(altinn3Client.getCallCount(altinn3Client::resourceOwner_AuthorizedParties.name) == 0)
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
        val altinn3Client = FakeAltinn3Client(resourceOwner_AuthorizedPartiesHandler = {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    authorizedRoles = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        })
        val resourceRegistry = ResourceRegistry(FakeAltinn3Client(), RedisConfig.local(), null).also {
            it.updatePolicySubjectsForKnownResources {
                listOf(
                    PolicySubject(
                        urn = "urn:altinn:rolecode:lede",
                        type = "urn:altinn:rolecode",
                        value = "lede",
                    )
                )
            }
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val fnr = "16120101181"
        altinnService.hentTilganger(fnr, Filter.empty, this)

        assertTrue(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, fnr) == 1)
        assertTrue(altinnRedisClient.getCallCount(altinnRedisClient::set.name) == 0)

        assertTrue(altinn3Client.getCallCountWithArgs(altinn3Client::resourceOwner_AuthorizedParties.name, fnr) == 1)
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
        val altinn3Client = FakeAltinn3Client(resourceOwner_AuthorizedPartiesHandler = {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("test-fager"),
                    authorizedRoles = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                )
            )
        })
        val resourceRegistry = ResourceRegistry(FakeAltinn3Client(), RedisConfig.local(), null).also {
            it.updatePolicySubjectsForKnownResources {
                listOf(
                    PolicySubject(
                        urn = "urn:altinn:rolecode:lede",
                        type = "urn:altinn:rolecode",
                        value = "lede",
                    )
                )
            }
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val fnr1 = "16120101181"
        val fnr2 = "26903848935"

        altinnService.hentTilganger(fnr1, Filter.empty, this)
        altinnService.hentTilganger(fnr2, Filter.empty, this)

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

        val altinn3Client = FakeAltinn3Client(resourceOwner_AuthorizedPartiesHandler = {
            listOf(
                AuthorizedParty(
                    name = "ET ANNET REGNSKAP",
                    organizationNumber = "111111111",
                    authorizedResources = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                    authorizedRoles = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
            )
        })
        val resourceRegistry = ResourceRegistry(FakeAltinn3Client(), RedisConfig.local(), null).also {
            it.updatePolicySubjectsForKnownResources {
                listOf(
                    PolicySubject(
                        urn = "urn:altinn:rolecode:lede",
                        type = "urn:altinn:rolecode",
                        value = "lede",
                    )
                )
            }
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val fnr = "16120101181"
        val tilganger = altinnService.hentTilganger(fnr, Filter.empty, this)

        assertTrue(tilganger.altinnTilganger.count() == 1)
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.count() == 1)
        assertTrue(tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.first() == "5810:1")
    }


    @Test
    fun `Altinn 2 tilganger inkluderes sammen med tilganger mappet fra altinn 3 ressurser`() = runTest {
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
        val altinn3Client = FakeAltinn3Client(resourceOwner_AuthorizedPartiesHandler = {
            listOf(
                AuthorizedParty(
                    name = "SLEMMESTAD OG STAVERN REGNSKAP",
                    organizationNumber = "910825496",
                    authorizedResources = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                    authorizedRoles = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
                AuthorizedParty(
                    name = "ET ANNET REGNSKAP",
                    organizationNumber = "111111111",
                    authorizedResources = setOf(),
                    authorizedRoles = setOf("LEDE"),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
            )
        })
        val resourceRegistry = ResourceRegistry(FakeAltinn3Client(), RedisConfig.local(), null).also {
            it.updatePolicySubjectsForKnownResources { resourceId ->
                when (resourceId) {
                    "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger" ->
                        listOf(
                            PolicySubject(
                                urn = "urn:altinn:rolecode:lede",
                                type = "urn:altinn:rolecode",
                                value = "lede",
                            )
                        )

                    else -> listOf() // ingen policy subjects for andre ressurser
                }

            }
        }


        val fnr = "16120101181"
        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val tilganger = altinnService.hentTilganger(fnr, Filter.empty, this)
        assertEquals(2, tilganger.altinnTilganger.size)

        tilganger.altinnTilganger.first { it.orgnr == "910825496" }.let { slemmestad ->
            // 4936:1 fra altinn2, 5810:1 fra altinn3 resource basert på altinn3 til altinn 2 mapping
            assertEquals(setOf("4936:1", "5810:1"), slemmestad.altinn2Tilganger)
        }

        tilganger.altinnTilganger.first { it.orgnr == "111111111" }.let { annet ->
            // 5810:1 fra altinn3 role(LEDE) basert på altinn3 til altinn 2 mapping
            assertEquals(setOf("5810:1"), annet.altinn2Tilganger)
        }
    }

    @Test
    fun `filter fungerer med og uten cache hit`() = runTest {
        val altinnRedisClient = FakeRedisClient()
        val altinn2Client = FakeAltinn2Client {
            Altinn2Tilganger(
                isError = false,
                orgNrTilTjenester = mapOf(
                    "2.1" to listOf(
                        Altinn2Tjeneste(
                            serviceCode = "4936",
                            serviceEdition = "1"
                        )
                    ),
                    "3.1" to listOf(
                        Altinn2Tjeneste(
                            serviceCode = "2896",
                            serviceEdition = "87"
                        )
                    )
                )
            )
        }
        val altinn3Client = FakeAltinn3Client(resourceOwner_AuthorizedPartiesHandler = {
            listOf(
                AuthorizedParty(
                    name = "1",
                    organizationNumber = "1",
                    authorizedResources = setOf(),
                    authorizedRoles = setOf(),
                    subunits = listOf(
                        AuthorizedParty(
                            name = "1.1",
                            organizationNumber = "1.1",
                            authorizedResources = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                            authorizedRoles = setOf(),
                            subunits = listOf(),
                            unitType = "BEDR",
                            type = "Business",
                            isDeleted = false,
                        )
                    ),
                    unitType = "AS",
                    type = "Business",
                    isDeleted = false,
                ),
                AuthorizedParty(
                    name = "2",
                    organizationNumber = "2",
                    authorizedResources = setOf(),
                    authorizedRoles = setOf(),
                    subunits = listOf(
                        AuthorizedParty(
                            name = "2.1",
                            organizationNumber = "2.1",
                            authorizedResources = setOf(),
                            authorizedRoles = setOf(),
                            subunits = listOf(),
                            unitType = "BEDR",
                            type = "Business",
                            isDeleted = false,
                        )
                    ),
                    unitType = "AS",
                    type = "Business",
                    isDeleted = false,
                ),
                AuthorizedParty(
                    name = "3",
                    organizationNumber = "3",
                    authorizedResources = setOf(),
                    authorizedRoles = setOf(),
                    subunits = listOf(
                        AuthorizedParty(
                            name = "3.1",
                            organizationNumber = "3.1",
                            authorizedResources = setOf(),
                            authorizedRoles = setOf(),
                            subunits = listOf(),
                            unitType = "BEDR",
                            type = "Business",
                            isDeleted = false,
                        )
                    ),
                    unitType = "AS",
                    type = "Business",
                    isDeleted = false,
                ),
            )
        })
        val resourceRegistry = ResourceRegistry(FakeAltinn3Client(), RedisConfig.local(), null).also {
            it.updatePolicySubjectsForKnownResources {
                listOf()
            }
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val fnr = "42"
        val tilganger = altinnService.hentTilganger(fnr, Filter(setOf("4936:1")), this)
        assertEquals(1, tilganger.altinnTilganger.size)
        assertEquals("2", tilganger.altinnTilganger.first().orgnr)
        assertEquals("2.1", tilganger.altinnTilganger.first().underenheter.first().orgnr)

        val tilganger2 = altinnService.hentTilganger(fnr, Filter(setOf("4936:1")), this)
        assertEquals(1, tilganger2.altinnTilganger.size)
        assertEquals("2", tilganger.altinnTilganger.first().orgnr)
        assertEquals("2.1", tilganger.altinnTilganger.first().underenheter.first().orgnr)
    }

}