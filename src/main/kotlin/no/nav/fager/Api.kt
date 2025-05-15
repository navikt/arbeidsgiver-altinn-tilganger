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
    val erSlettet: Boolean
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
                altinnTilganger.flatten { it }
                    .associate { it.orgnr to it.altinn2Tilganger + it.altinn3Tilganger }

            val tilgangToOrgNr = orgNrTilTilganger.flatMap { (orgNr, tjenester) ->
                tjenester.map { it to orgNr }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }


            return AltinnTilgangerResponse(
                isError = isError,
                hierarki = altinnTilganger,
                orgNrTilTilganger = orgNrTilTilganger,
                tilgangTilOrgNr = tilgangToOrgNr,
            )
        }
    }
}

fun <T> List<AltinnTilgang>.flatten(mapFn: (AltinnTilgang) -> T?): List<T> = flatMap { flatten(it, mapFn) }

fun <T> flatten(
    tilgang: AltinnTilgang,
    mapFn: (AltinnTilgang) -> T?
): List<T> = listOfNotNull(
    mapFn(tilgang)
) + tilgang.underenheter.flatMap { flatten(it, mapFn) }

@Serializable
data class Filter(
    val altinn2Tilganger: Set<String> = emptySet(),
    val altinn3Tilganger: Set<String> = emptySet(),
    val inkluderSlettede: Boolean = false,
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
        val empty = Filter(emptySet(), emptySet(), false)
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