package no.nav.fager.altinn

import io.micrometer.core.instrument.Counter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.fager.AltinnTilgang
import no.nav.fager.Filter
import no.nav.fager.infrastruktur.Metrics
import no.nav.fager.infrastruktur.coRecord
import no.nav.fager.redis.AltinnTilgangerRedisClient


class AltinnService(
    private val altinn2Client: Altinn2Client,
    private val altinn3Client: Altinn3Client,
    private val redisClient: AltinnTilgangerRedisClient,
    private val resourceRegistry: ResourceRegistry,
) {
    companion object {
        // Endre versjon for å invalidere eksisterende cache
        const val CACHE_VERSION = "v1"
    }

    private val timer = Metrics.meterRegistry.timer("altinnservice.hentTilgangerFraAltinn")
    private val cacheHit = Counter.builder("altinnservice.cache").tag("result", "hit").register(Metrics.meterRegistry)
    private val cacheMiss = Counter.builder("altinnservice.cache").tag("result", "miss").register(Metrics.meterRegistry)
    private val altinnCountOk =
        Counter.builder("altinnservice.hentTilgangerFraAltinn.counter")
            .tag("isError", "false")
            .register(Metrics.meterRegistry)
    private val altinnCountError =
        Counter.builder("altinnservice.hentTilgangerFraAltinn.counter")
            .tag("isError", "true")
            .register(Metrics.meterRegistry)

    suspend fun hentTilganger(
        fnr: String,
        filter: Filter = Filter.empty
    ): AltinnTilgangerResultat {
        val cacheKey = "$fnr-$CACHE_VERSION"
        val result = redisClient.get(cacheKey)?.also {
            cacheHit.increment()
        } ?: run {
            cacheMiss.increment()
            withContext(NonCancellable) { // Midlertidig workaround for å unngå cancellation exceptions (https://youtrack.jetbrains.com/projects/KTOR/issues/KTOR-8478/CIO-There-is-no-graceful-shutdown-when-calling-the-servers-stop-method)
                hentTilgangerFraAltinn(fnr).also {
                    if (it.isError) {
                        altinnCountError.increment()
                    } else {
                        altinnCountOk.increment()
                        redisClient.set(cacheKey, it)
                    }
                }
            }
        }

        return result.filter(filter)
    }

    internal suspend fun hentTilgangerFraAltinn(fnr: String) =
        timer.coRecord {
            coroutineScope {
                val altinn2TilgangerJob = async { altinn2Client.hentAltinn2Tilganger(fnr) }
                val altinn3TilgangerJob = async { altinn3Client.resourceOwner_AuthorizedParties(fnr) }

                val altinn2Tilganger = altinn2TilgangerJob.await()
                val altinn3TilgangerResult = altinn3TilgangerJob.await()
                val altinn3Tilganger = altinn3TilgangerResult.fold(
                    onSuccess = { altinn3tilganger ->
                        altinn3tilganger.addAuthorizedResourcesRecursive { party ->
                            // adds all resources from the resource registry for the roles and accesspackages the party has
                            // this must be done prior to mapping to Altinn2 services
                            (party.authorizedRolesAsUrn.flatMap { // TODO: replace with party.authorizedRoles.flatMap when new altinn api returns urns
                                resourceRegistry.getResourceIdForPolicySubject(it)
                            } + party.authorizedAccessPackagesAsUrn.flatMap { // TODO: replace with party.authorizedAccessPackages.flatMap when new altinn api returns urns
                                resourceRegistry.getResourceIdForPolicySubject(it)
                            }).toSet()
                        }
                    },
                    onFailure = { emptyList() }
                )


                val orgnrTilAltinn2Mapped = altinn3Tilganger.flatMap {
                    flatten(it) { party ->
                        if (party.organizationNumber == null || party.unitType == null) {
                            null
                        } else {
                            party.organizationNumber to party.authorizedResources.mapNotNull { resource ->
                                resourceRegistry.resourceIdToAltinn2Tjeneste[resource]
                            }.flatten()
                        }
                    }
                }.associate {
                    it.first to it.second + (altinn2Tilganger.orgNrTilTjenester[it.first] ?: emptyList())
                }

                AltinnTilgangerResultat(
                    altinn2Tilganger.isError || altinn3TilgangerResult.isFailure,
                    mapToHierarchy(
                        altinn3Tilganger,
                        Altinn2Tilganger(
                            altinn2Tilganger.isError,
                            orgnrTilAltinn2Mapped
                        )
                    )
                )
            }
        }

    private fun mapToHierarchy(
        authorizedParties: List<AuthorizedParty>,
        altinn2Tilganger: Altinn2Tilganger
    ): List<AltinnTilgang> {

        return authorizedParties
            .mapNotNull { party ->
                if (party.organizationNumber == null || party.unitType == null) {
                    null
                } else {
                    AltinnTilgang(
                        orgnr = party.organizationNumber, // alle orgnr finnes i altinn3 pga includeAltinn2=true
                        navn = party.name,
                        organisasjonsform = party.unitType,
                        altinn3Tilganger = party.authorizedResources,
                        altinn2Tilganger = altinn2Tilganger.orgNrTilTjenester[party.organizationNumber]?.map {
                            """${it.serviceCode}:${it.serviceEdition}"""
                        }?.toSet() ?: emptySet(),
                        underenheter = mapToHierarchy(party.subunits, altinn2Tilganger),
                        erSlettet = party.isDeleted
                    )
                }
            }
    }

    @Serializable
    data class AltinnTilgangerResultat(
        val isError: Boolean,
        val altinnTilganger: List<AltinnTilgang>
    ) {
        fun filter(filter: Filter): AltinnTilgangerResultat =
            AltinnTilgangerResultat(
                isError,
                altinnTilganger.filterRecursive(filter)
            )
    }
}

/**
 * Filtrerer rekursivt basert på angitt filter.
 * Her antas det at vi kun skal filtrere på løvnoder (virksomheter) og ikke på overenheter.
 * Dvs. at vi ikke forventer at en tjeneste kun er delegert på et overordnet nivå.
 * Oss bekjent gjør Nav tilgangsstyring på virksomheter og ikke på overordnet nivå.
 * Vi har ikke observert tilfeller i dev hvor en parent har tilgang som ikke finnes blant underenhetene,
 * men det betyr ikke at det ikke forekommer.
 *
 * Mao. vi filtrerer fra bunnen. Dersom en overordnet enhet har barn og alle disse fjernes pga filteret
 * så fjernes også overordnet enhet. Dette uavhengig om overordnet enhet har tilgangen definert eller ikke.
 */
private fun List<AltinnTilgang>.filterRecursive(filter: Filter): List<AltinnTilgang> =
    mapNotNull { tilgang ->
        if (!filter.inkluderSlettede && tilgang.erSlettet) return@mapNotNull null

        val filtrerteUnderenheter = tilgang.underenheter.filterRecursive(filter)

        val haddeUnderenheter = tilgang.underenheter.isNotEmpty()
        val alleUnderenheterFjernet = haddeUnderenheter && filtrerteUnderenheter.isEmpty()
        if (alleUnderenheterFjernet) return@mapNotNull null

        tilgang.copy(underenheter = filtrerteUnderenheter)
    }.filter { tilgang ->
        if (filter.isEmpty) return@filter true

        val matcherAltinn2 = tilgang.altinn2Tilganger.intersects(filter.altinn2Tilganger)
        val matcherAltinn3 = tilgang.altinn3Tilganger.intersects(filter.altinn3Tilganger)
        val harUnderenheter = tilgang.underenheter.isNotEmpty()

        harUnderenheter || matcherAltinn2 || matcherAltinn3
    }

private fun AuthorizedParty.addAuthorizedResourcesRecursive(
    addResources: (AuthorizedParty) -> Set<String>
): AuthorizedParty = AuthorizedParty(
    organizationNumber = organizationNumber,
    name = name,
    type = type,
    unitType = unitType,
    authorizedResources = authorizedResources + addResources(this),
    authorizedAccessPackages = authorizedAccessPackages,
    authorizedRoles = authorizedRoles,
    isDeleted = isDeleted,
    subunits = subunits.map { it.addAuthorizedResourcesRecursive(addResources) }
)

private fun List<AuthorizedParty>.addAuthorizedResourcesRecursive(
    addResources: (AuthorizedParty) -> Set<String>
): List<AuthorizedParty> = map { it.addAuthorizedResourcesRecursive(addResources) }

private fun <T> flatten(
    party: AuthorizedParty,
    mapFn: (AuthorizedParty) -> T?
): List<T> = listOfNotNull(
    mapFn(party)
) + party.subunits.flatMap { flatten(it, mapFn) }

private infix fun <T> Set<T>.intersects(other: Set<T>): Boolean =
    (this intersect other).isNotEmpty()