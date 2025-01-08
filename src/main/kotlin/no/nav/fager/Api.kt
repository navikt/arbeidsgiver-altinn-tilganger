package no.nav.fager

import kotlinx.serialization.Serializable
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.KnownAltinn2Tjenester
import no.nav.fager.altinn.KnownResourceIds

@Serializable
data class AltinnTilgang(
    val orgnr: String,
    val altinn3Tilganger: Set<String>,
    val altinn2Tilganger: Set<String>,
    val underenheter: List<AltinnTilgang>,
    val navn: String,
    val organisasjonsform: String,
)

annotation class Example(val value: String)

annotation class Description(val value: String)

@Serializable
data class AltinnTilgangerResponse(
    val isError: Boolean,
    val hierarki: List<AltinnTilgang>,
    val orgNrTilTilganger: Map<String, Set<String>>,
    val tilgangTilOrgNr: Map<String, Set<String>>,
) {
    companion object {
        fun AltinnService.AltinnTilgangerResultat.toResponse(): AltinnTilgangerResponse {
            val orgNrTilTilganger: Map<String, Set<String>> =
                this.altinnTilganger.flatMap { it.underenheter }
                    .associate {
                        it.orgnr to it.altinn2Tilganger + it.altinn3Tilganger
                    }

            val tilgangToOrgNr = orgNrTilTilganger.flatMap { (orgNr, tjenester) ->
                tjenester.map { it to orgNr }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }


            return AltinnTilgangerResponse(
                isError = this.isError,
                hierarki = this.altinnTilganger,
                orgNrTilTilganger = orgNrTilTilganger,
                tilgangTilOrgNr = tilgangToOrgNr,
            )
        }
    }
}

@Serializable
data class Filter(
    val altinn2Tilganger: Set<String> = emptySet(),
    val altinn3Tilganger: Set<String> = emptySet(),
) {
    init {
        altinn2Tilganger.forEach {
            require(KnownAltinn2Tjenester.contains(it)) {
                "Invalid value in Altinn 2 filter '$it'. Valid Altinn 2 filters: $KnownAltinn2Tjenester"
            }
        }
        altinn3Tilganger.forEach {
            require(KnownResourceIds.contains(it)) {
                "Invalid value in Altinn 3 filter '$it'. Valid Altinn 3 filters: $KnownResourceIds"
            }
        }
    }

    val isEmpty: Boolean
        get() = altinn2Tilganger.isEmpty() && altinn3Tilganger.isEmpty()

    companion object {
        val empty = Filter(emptySet(), emptySet())
    }
}

@Serializable
sealed class AltinnTilgangerRequestBase {
    abstract val filter: Filter
}

@Serializable
data class AltinnTilgangerRequest(
    override val filter: Filter = Filter.empty,
) : AltinnTilgangerRequestBase()

@Serializable
data class AltinnTilgangerM2MRequest(
    val fnr: String,
    override val filter: Filter = Filter.empty,
) : AltinnTilgangerRequestBase()