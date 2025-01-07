package no.nav.fager.altinn

import io.micrometer.core.instrument.Counter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
    private val timer = Metrics.meterRegistry.timer("altinnservice.hentTilgangerFraAltinn")
    private val cacheHit = Counter.builder("altinnservice.cache").tag("result", "hit").register(Metrics.meterRegistry)
    private val cacheMiss = Counter.builder("altinnservice.cache").tag("result", "miss").register(Metrics.meterRegistry)

    suspend fun hentTilganger(
        fnr: String,
        filter: Filter = Filter.empty,
        scope: CoroutineScope
    ) =
        redisClient.get(fnr)?.also {
            cacheHit.increment()
        } ?: run {
            cacheMiss.increment()
            hentTilgangerFraAltinn(fnr, scope).also {
                if (!it.isError) {
                    redisClient.set(fnr, it)
                }
            }
        }.filter(filter)

    private suspend fun hentTilgangerFraAltinn(
        fnr: String,
        scope: CoroutineScope,
    ) = timer.coRecord {
        val altinn2TilgangerJob = scope.async { altinn2Client.hentAltinn2Tilganger(fnr) }
        val altinn3TilgangerJob = scope.async { altinn3Client.resourceOwner_AuthorizedParties(fnr) }

        val altinn2Tilganger = altinn2TilgangerJob.await()
        val altinn3TilgangerResult = altinn3TilgangerJob.await()
        val altinn3Tilganger = altinn3TilgangerResult.fold(
            onSuccess = { altinn3tilganger ->
                altinn3tilganger.addAuthorizedResourcesRecursive { party ->
                    // adds all resources from the resource registry for the roles the party has
                    // this must be done prior to mapping to Altinn2 services
                    party.authorizedRolesAsUrn.flatMap { // TODO: replace with party.authorizedRoles.flatMap when new altinn api returns urns
                        resourceRegistry.getResourceIdForPolicySubject(it)
                    }.toSet()
                }
            },
            onFailure = { emptyList() }
        )


        val orgnrTilAltinn2Mapped = altinn3Tilganger.flatMap {
            flatten(it) { party ->
                if (party.organizationNumber == null || party.unitType == null || party.isDeleted) {
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

    private fun mapToHierarchy(
        authorizedParties: List<AuthorizedParty>,
        altinn2Tilganger: Altinn2Tilganger
    ): List<AltinnTilgang> {

        return authorizedParties
            .mapNotNull { party ->
                if (party.organizationNumber == null || party.unitType == null || party.isDeleted) {
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
                    )
                }
            }
    }

    @Serializable
    data class AltinnTilgangerResultat(
        val isError: Boolean,
        val altinnTilganger: List<AltinnTilgang>
    ) {
        fun filter(filter: Filter) = if (filter.isEmpty) {
            this
        } else {
            AltinnTilgangerResultat(
                isError,
                altinnTilganger.filterRecursive(filter)
            )
        }
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
private fun List<AltinnTilgang>.filterRecursive(filter: Filter, parent: AltinnTilgang? = null): List<AltinnTilgang> =
    mapNotNull {
        val underenheter = it.underenheter.filterRecursive(filter, it)
        val noneMatched = underenheter.isEmpty() && it.underenheter.isNotEmpty()
        if (noneMatched) {
            null
        } else {
            it.copy(underenheter = underenheter)
        }
    }.filterIndexed { idx, it ->
        val filterMatchAltinn2 = (it.altinn2Tilganger intersect filter.altinn2Tilganger).isNotEmpty()
        val filterMatchAltinn3 = (it.altinn3Tilganger intersect filter.altinn3Tilganger).isNotEmpty()
        val isLeaf = it.underenheter.isEmpty()

        // hopp over hvis dette ikke er en løvnøde (virksomhet)
        !isLeaf || filterMatchAltinn2 || filterMatchAltinn3
    }

private fun AuthorizedParty.addAuthorizedResourcesRecursive(
    addResources: (AuthorizedParty) -> Set<String>
): AuthorizedParty = AuthorizedParty(
    organizationNumber = organizationNumber,
    name = name,
    type = type,
    unitType = unitType,
    authorizedResources = authorizedResources + addResources(this),
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
