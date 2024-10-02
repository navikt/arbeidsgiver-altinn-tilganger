package no.nav.fager.altinn

import io.micrometer.core.instrument.Counter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import no.nav.fager.redis.AltinnTilgangerRedisClient
import no.nav.fager.infrastruktur.Metrics
import no.nav.fager.infrastruktur.coRecord


class AltinnService(
    private val altinn2Client: Altinn2Client,
    private val altinn3Client: Altinn3Client,
    private val redisClient: AltinnTilgangerRedisClient
) {

    private val timer = Metrics.meterRegistry.timer("altinnservice.hentTilgangerFraAltinn")
    private val cacheHit = Counter.builder("altinnservice.cache").tag("result", "hit").register(Metrics.meterRegistry)
    private val cacheMiss = Counter.builder("altinnservice.cache").tag("result", "miss").register(Metrics.meterRegistry)

    suspend fun hentTilganger(fnr: String, scope: CoroutineScope) =
        redisClient.get(fnr)?.also {
            cacheHit.increment()
        } ?: run {
            cacheMiss.increment()
            hentTilgangerFraAltinn(fnr, scope).also {
                if (!it.isError) {
                    redisClient.set(fnr, it)
                }
            }
        }

    private suspend fun hentTilgangerFraAltinn(
        fnr: String,
        scope: CoroutineScope,
    ) = timer.coRecord {
        val altinn2TilgangerJob = scope.async { altinn2Client.hentAltinn2Tilganger(fnr) }
        val altinn3TilgangerJob = scope.async { altinn3Client.hentAuthorizedParties(fnr) }

        /* Ingen try-catch rundt .await() siden begge klientene håndterer alle exceptions internt. */
        val altinn2Tilganger = altinn2TilgangerJob.await()
        val altinn3Tilganger = altinn3TilgangerJob.await()

        AltinnTilgangerResultat(altinn2Tilganger.isError, mapToHierarchy(altinn3Tilganger, altinn2Tilganger))
    }

    private fun mapToHierarchy(
        authorizedParties: List<AuthoririzedParty>, altinn2Tilganger: Altinn2Tilganger
    ): List<AltinnTilgang> {

        return authorizedParties.filter { it.organizationNumber != null && it.unitType != null }
            .map { party ->
                AltinnTilgang(
                    orgNr = party.organizationNumber!!, // alle orgnr finnes i altinn3 pga includeAltinn2=true
                    name = party.name,
                    organizationForm = party.unitType!!,
                    altinn3Tilganger = party.authorizedResources,
                    altinn2Tilganger = altinn2Tilganger.orgNrTilTjenester[party.organizationNumber]?.map { """${it.serviceCode}:${it.serviceEdition}""" }
                        ?.toSet() ?: emptySet(),
                    underenheter = mapToHierarchy(party.subunits, altinn2Tilganger),
                )
            }
    }

    @Serializable
    data class AltinnTilgangerResultat(
        val isError: Boolean, val altinnTilganger: List<AltinnTilgang>
    )
}
