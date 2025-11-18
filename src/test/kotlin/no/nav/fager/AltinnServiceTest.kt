package no.nav.fager

import kotlinx.coroutines.test.runTest
import no.nav.fager.altinn.Altinn2Tilganger
import no.nav.fager.altinn.Altinn2Tjeneste
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.AltinnService.AltinnTilgangerResultat
import no.nav.fager.altinn.AuthorizedParty
import no.nav.fager.altinn.PolicySubject
import no.nav.fager.altinn.ResourceRegistry
import no.nav.fager.fakes.clients.FakeAltinn2Client
import no.nav.fager.fakes.clients.FakeAltinn3Client
import no.nav.fager.fakes.clients.FakeRedisClient
import no.nav.fager.redis.RedisConfig
import kotlin.test.Test
import kotlin.test.assertEquals

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
                    authorizedAccessPackages = setOf(),
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
                    ),
                )
            }
        }

        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val fnr = "16120101181"
        val cacheKey = "$fnr-${AltinnService.CACHE_VERSION}"

        altinnService.hentTilganger(fnr, Filter.empty)

        assertEquals(altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey), 1)
        assertEquals(
            altinnRedisClient.getCallCountWithArgs(
                altinnRedisClient::set.name, cacheKey,
                AltinnTilgangerResultat(
                    isError = false,
                    altinnTilganger = listOf(
                        AltinnTilgang(
                            orgnr = "910825496",
                            altinn3Tilganger = setOf("test-fager"),
                            altinn2Tilganger = setOf("4936:1"),
                            underenheter = listOf(),
                            navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                            organisasjonsform = "BEDR",
                            erSlettet = false
                        )
                    )
                )
            ), 1
        )

        assertEquals(altinn3Client.getCallCountWithArgs(altinn3Client::resourceOwner_AuthorizedParties.name, fnr), 1)
        assertEquals(altinn2Client.getCallCountWithArgs(altinn2Client::hentAltinn2Tilganger.name, fnr), 1)
    }


    @Test
    fun `cache entry eksisterer, klienter kalles ikke`() = runTest {
        val fnr = "16120101181"
        val cacheKey = "$fnr-${AltinnService.CACHE_VERSION}"
        val altinnRedisClient = FakeRedisClient(
            mutableMapOf(
                cacheKey to AltinnTilgangerResultat(
                    isError = false,
                    altinnTilganger = listOf(
                        AltinnTilgang(
                            orgnr = "910825496",
                            altinn3Tilganger = setOf("test-fager"),
                            altinn2Tilganger = setOf("4936:1"),
                            underenheter = listOf(),
                            navn = "SLEMMESTAD OG STAVERN REGNSKAP",
                            organisasjonsform = "BEDR",
                            erSlettet = false
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
                    authorizedAccessPackages = setOf(),
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

        altinnService.hentTilganger(fnr, Filter.empty)

        assertEquals(1, altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey))
        assertEquals(0, altinnRedisClient.getCallCount(altinnRedisClient::set.name))

        assertEquals(0, altinn3Client.getCallCount(altinn3Client::resourceOwner_AuthorizedParties.name))
        assertEquals(0, altinn2Client.getCallCount(altinn2Client::hentAltinn2Tilganger.name))
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
                    authorizedAccessPackages = setOf(),
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
        val cacheKey = "$fnr-${AltinnService.CACHE_VERSION}"
        altinnService.hentTilganger(fnr, Filter.empty)

        assertEquals(1, altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey))
        assertEquals(0, altinnRedisClient.getCallCount(altinnRedisClient::set.name))

        assertEquals(1, altinn3Client.getCallCountWithArgs(altinn3Client::resourceOwner_AuthorizedParties.name, fnr))
        assertEquals(1, altinn2Client.getCallCountWithArgs(altinn2Client::hentAltinn2Tilganger.name, fnr))
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
                    authorizedAccessPackages = setOf(),
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

        val cacheKey1 = "$fnr1-${AltinnService.CACHE_VERSION}"
        val cacheKey2 = "$fnr2-${AltinnService.CACHE_VERSION}"

        altinnService.hentTilganger(fnr1, Filter.empty)
        altinnService.hentTilganger(fnr2, Filter.empty)

        assertEquals(2, altinnRedisClient.getCallCount(altinnRedisClient::get.name))
        assertEquals(1, altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey1))
        assertEquals(1, altinnRedisClient.getCallCountWithArgs(altinnRedisClient::get.name, cacheKey2))


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
                    authorizedAccessPackages = setOf(),
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
        val tilganger = altinnService.hentTilganger(fnr, Filter.empty)

        assertEquals(1, tilganger.altinnTilganger.count())
        assertEquals(1, tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.count())
        assertEquals("5810:1", tilganger.altinnTilganger.first { it.orgnr == "111111111" }.altinn2Tilganger.first())
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
                    authorizedAccessPackages = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
                AuthorizedParty(
                    name = "ET ANNET SELSKAP",
                    organizationNumber = "111111111",
                    authorizedResources = setOf(),
                    authorizedRoles = setOf("LEDE"),
                    authorizedAccessPackages = setOf(),
                    subunits = listOf(),
                    unitType = "BEDR",
                    type = "Business",
                    isDeleted = false,
                ),
                AuthorizedParty(
                    name = "ENDA ET ANNET SELSKAP",
                    organizationNumber = "222222222",
                    authorizedResources = setOf(),
                    authorizedRoles = setOf(),
                    authorizedAccessPackages = setOf("lonn-personopplysninger-saerlig-kategori"),
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
                            ),
                        )

                    "nav_foreldrepenger_inntektsmelding" ->
                        listOf(
                            PolicySubject(
                                urn = "urn:altinn:accesspackage:lonn-personopplysninger-saerlig-kategori",
                                type = "urn:altinn:accesspackage",
                                value = "lonn-personopplysninger-saerlig-kategori",
                            ),
                        )

                    else -> listOf() // ingen policy subjects for andre ressurser
                }

            }
        }


        val fnr = "16120101181"
        val altinnService = AltinnService(altinn2Client, altinn3Client, altinnRedisClient, resourceRegistry)

        val tilganger = altinnService.hentTilganger(fnr, Filter.empty)
        assertEquals(3, tilganger.altinnTilganger.size)

        tilganger.altinnTilganger.first { it.orgnr == "910825496" }.let { slemmestad ->
            // 4936:1 fra altinn2, 5810:1 fra altinn3 resource basert på altinn3 til altinn 2 mapping
            assertEquals(setOf("4936:1", "5810:1"), slemmestad.altinn2Tilganger)
        }

        tilganger.altinnTilganger.first { it.orgnr == "111111111" }.let { annet ->
            // 5810:1 fra altinn3 role(LEDE) basert på altinn3 til altinn 2 mapping
            assertEquals(setOf("5810:1"), annet.altinn2Tilganger)
            // fra role LEDE via resource mapping
            assertEquals(
                setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                annet.altinn3Tilganger
            )
        }

        tilganger.altinnTilganger.first { it.orgnr == "222222222" }.let { endaAnnet ->
            // ingen altinn 2 tilganger
            assertEquals(setOf(), endaAnnet.altinn2Tilganger)
            // fra accessRightPackage via resource mapping
            assertEquals(setOf("nav_foreldrepenger_inntektsmelding"), endaAnnet.altinn3Tilganger)
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
                    authorizedAccessPackages = setOf(),
                    subunits = listOf(
                        AuthorizedParty(
                            name = "1.1",
                            organizationNumber = "1.1",
                            authorizedResources = setOf("nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger"),
                            authorizedRoles = setOf(),
                            authorizedAccessPackages = setOf(),
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
                    authorizedAccessPackages = setOf(),
                    subunits = listOf(
                        AuthorizedParty(
                            name = "2.1",
                            organizationNumber = "2.1",
                            authorizedResources = setOf(),
                            authorizedRoles = setOf(),
                            authorizedAccessPackages = setOf(),
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
                    authorizedAccessPackages = setOf(),
                    subunits = listOf(
                        AuthorizedParty(
                            name = "3.1",
                            organizationNumber = "3.1",
                            authorizedResources = setOf(),
                            authorizedRoles = setOf(),
                            authorizedAccessPackages = setOf(),
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
        val tilganger = altinnService.hentTilganger(fnr, Filter(setOf("4936:1")))
        assertEquals(1, tilganger.altinnTilganger.size)
        assertEquals("2", tilganger.altinnTilganger.first().orgnr)
        assertEquals("2.1", tilganger.altinnTilganger.first().underenheter.first().orgnr)

        val tilganger2 = altinnService.hentTilganger(fnr, Filter(setOf("4936:1")))
        assertEquals(1, tilganger2.altinnTilganger.size)
        assertEquals("2", tilganger.altinnTilganger.first().orgnr)
        assertEquals("2.1", tilganger.altinnTilganger.first().underenheter.first().orgnr)
    }

}