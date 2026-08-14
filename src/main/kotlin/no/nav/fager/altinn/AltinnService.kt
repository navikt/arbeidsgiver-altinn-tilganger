package no.nav.fager.altinn

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.fager.AltinnTilgang
import no.nav.fager.Filter
import no.nav.fager.infrastruktur.Metrics
import no.nav.fager.infrastruktur.coRecord
import no.nav.fager.infrastruktur.logger
import no.nav.fager.infrastruktur.teamLogger
import no.nav.fager.redis.AltinnTilgangerRedisClient


class AltinnService(
    private val altinn3Client: Altinn3Client,
    private val redisClient: AltinnTilgangerRedisClient,
    private val resourceRegistry: ResourceRegistry,
) {
    companion object {
        // Endre versjon for å invalidere eksisterende cache
        const val CACHE_VERSION = "v2"
    }

    private val timer = Metrics.meterRegistry.timer("altinnservice.hentTilgangerFraAltinn")
    private val cacheCount = Metrics.counter("altinnservice.cache")
    private val altinnCount = Metrics.counter("altinnservice.altinn")
    private val log = logger()
    private val teamLogger = teamLogger()

    suspend fun hentTilganger(
        fnr: String,
        filter: Filter = Filter.empty
    ): AltinnTilgangerResultat {
        val cacheKey = "$fnr-$CACHE_VERSION"
        val result = redisClient.get(cacheKey)?.also {
            cacheCount.increment("result" to "hit")
        } ?: run {
            cacheCount.increment("result" to "miss")

            withContext(NonCancellable) { // Midlertidig workaround for å unngå cancellation exceptions (https://youtrack.jetbrains.com/projects/KTOR/issues/KTOR-8478/CIO-There-is-no-graceful-shutdown-when-calling-the-servers-stop-method)
                hentTilgangerFraAltinn(fnr).also {
                    altinnCount.increment("result" to if (it.isError) "isError" else "isOk")
                    if (!it.isError) {
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
                val altinn3TilgangerResult = altinn3Client.resourceOwner_AuthorizedParties(fnr)
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
                    onFailure = { e ->
                        log.error("Klarte ikke hente authorizedParties fra Altinn 3", e)
                        emptyList()
                    }
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
                    it.first to it.second
                }

                AltinnTilgangerResultat(
                    altinn3TilgangerResult.isFailure,
                    mapToHierarchy(
                        altinn3Tilganger,
                        orgnrTilAltinn2Mapped
                    )
                ).also {
                    teamLogger.info(
                        "Hentet Altinn-tilganger for fnr={}, altinn3TilgangerResult={}, altinn3Tilganger={}, resultat={}",
                        fnr,
                        altinn3TilgangerResult.fold(
                            onSuccess = { r -> r },
                            onFailure = { "failure" }
                        ),
                        altinn3Tilganger,
                        it
                    )
                }
            }
        }

    private fun mapToHierarchy(
        authorizedParties: List<AuthorizedParty>,
        orgnrTilAltinn2Tjenester: Map<String, List<String>>
    ): List<AltinnTilgang> {

        return authorizedParties
            .mapNotNull { party ->
                if (party.organizationNumber == null || party.unitType == null) {
                    null
                } else {
                    AltinnTilgang(
                        orgnr = party.organizationNumber,
                        navn = party.name,
                        organisasjonsform = party.unitType,
                        altinn3Tilganger = party.authorizedResources,
                        altinn2Tilganger = orgnrTilAltinn2Tjenester[party.organizationNumber]
                            ?.toSet() ?: emptySet(),
                        roller = party.authorizedRoles,
                        tilgangspakker = party.authorizedAccessPackages,
                        underenheter = mapToHierarchy(party.subunits, orgnrTilAltinn2Tjenester),
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
        fun filter(filter: Filter): AltinnTilgangerResultat {
            if (filter.isEmpty) return this

            // Reverse map: Altinn 2-tjenestekode -> alle Altinn 3-ressurser som peker på koden
            val altinn2ToAltinn3: Map<String, Set<String>> = KnownResources
                .flatMap { resource -> resource.altinn2Tjeneste.map { it to resource.resourceId } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }

            // Altinn 3-ressurser i filteret + ressurser som mapper til Altinn 2-koder i filteret (1-til-mange)
            val effectiveAltinn3 = filter.altinn3Tilganger +
                filter.altinn2Tilganger.flatMap { altinn2ToAltinn3[it].orEmpty() }

            // Altinn 2-koder i filteret + koder som Altinn 3-ressursene i filteret mapper til
            val effectiveAltinn2 = filter.altinn2Tilganger +
                filter.altinn3Tilganger.flatMap { resourceId ->
                    KnownResources.filter { it.resourceId == resourceId }.flatMap { it.altinn2Tjeneste }
                }

            return AltinnTilgangerResultat(
                isError,
                altinnTilganger.filterRecursive(
                    inkluderSlettede = filter.inkluderSlettede,
                    filterAltinn2 = filter.altinn2Tilganger,
                    filterAltinn3 = filter.altinn3Tilganger,
                    effectiveAltinn2 = effectiveAltinn2,
                    effectiveAltinn3 = effectiveAltinn3,
                )
            )
        }
    }
}

/**
 * Filtrerer rekursivt basert på angitt filter.
 *
 * Inklusjon (hvilke noder som beholdes) avgjøres av de opprinnelige filterverdiene.
 * Innholdet (`altinn2Tilganger`/`altinn3Tilganger`) på beholdte noder begrenses til de
 * effektive settene, som inkluderer kryssreferanser mellom Altinn 2 og Altinn 3.
 */
private fun List<AltinnTilgang>.filterRecursive(
    inkluderSlettede: Boolean,
    filterAltinn2: Set<String>,
    filterAltinn3: Set<String>,
    effectiveAltinn2: Set<String>,
    effectiveAltinn3: Set<String>,
): List<AltinnTilgang> =
    mapNotNull { tilgang ->
        if (!inkluderSlettede && tilgang.erSlettet) return@mapNotNull null

        val filtrerteUnderenheter = tilgang.underenheter.filterRecursive(
            inkluderSlettede = inkluderSlettede,
            filterAltinn2 = filterAltinn2,
            filterAltinn3 = filterAltinn3,
            effectiveAltinn2 = effectiveAltinn2,
            effectiveAltinn3 = effectiveAltinn3,
        )
        tilgang.copy(underenheter = filtrerteUnderenheter)
    }.filter { tilgang ->
        val matcherAltinn2 = tilgang.altinn2Tilganger.intersects(filterAltinn2)
        val matcherAltinn3 = tilgang.altinn3Tilganger.intersects(filterAltinn3)
        val harUnderenheter = tilgang.underenheter.isNotEmpty()

        harUnderenheter || matcherAltinn2 || matcherAltinn3
    }.map { tilgang ->
        tilgang.copy(
            altinn2Tilganger = tilgang.altinn2Tilganger intersect effectiveAltinn2,
            altinn3Tilganger = tilgang.altinn3Tilganger intersect effectiveAltinn3,
        )
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
