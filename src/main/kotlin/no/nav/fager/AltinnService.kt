package no.nav.fager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async


class AltinnService(private val altinn2Client: Altinn2Client, private val altinn3Client: Altinn3Client) {
    data class AltinnTilgangerResultat(
        val isError: Boolean,
        val altinnTilganger: List<AltinnTilgang>
    )

    suspend fun hentTilganger(fnr: String, scope: CoroutineScope) : AltinnTilgangerResultat {
        val altinn2TilgangerJob = scope.async { altinn2Client.hentAltinn2Tilganger(fnr) }
        val altinn3TilgangerJob = scope.async { altinn3Client.hentAuthorizedParties(fnr) }

        /* Ingen try-catch rundt .await() siden begge klientene håndterer alle exceptions internt. */
        val altinn2Tilganger = altinn2TilgangerJob.await()
        val altinn3Tilganger = altinn3TilgangerJob.await()

        return AltinnTilgangerResultat(altinn2Tilganger.isError, mapToHierarchy(altinn3Tilganger, altinn2Tilganger))
    }

    private fun mapToHierarchy(
        authorizedParties: List<AuthoririzedParty>,
        altinn2Tilganger: Altinn2Tilganger
    ): List<AltinnTilgang> {

        return authorizedParties
            .filter { it.organizationNumber != null && it.unitType != null } // er null for type=person
            .map { party ->
                AltinnTilgang(
                    orgNr = party.organizationNumber!!, // alle orgnr finnes i altinn3 pga includeAltinn2=true
                    name = party.name,
                    organizationForm = party.unitType!!,
                    altinn3Tilganger = party.authorizedResources,
                    altinn2Tilganger = altinn2Tilganger.orgNrTilTjenester[party.organizationNumber]
                        ?.map { """${it.serviceCode}:${it.serviceEdition}""" }?.toSet()
                        ?: emptySet(),
                    underenheter = mapToHierarchy(party.subunits, altinn2Tilganger),
                )
            }
    }

}