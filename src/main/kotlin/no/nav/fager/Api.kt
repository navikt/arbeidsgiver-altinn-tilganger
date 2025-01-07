package no.nav.fager

import io.github.smiley4.schemakenerator.core.annotations.Description
import io.github.smiley4.schemakenerator.core.annotations.Example
import kotlinx.serialization.Serializable
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.KnownAltinn2Tjenester
import no.nav.fager.altinn.KnownResourceIds

@Description("Brukerens tilganger til Altinn 2 og Altinn 3 for en organisasjon")
@Serializable
data class AltinnTilgang(
    @Description("Organisasjonsnummer")
    @Example("11223344")
    val orgnr: String,
    @Description("Tilganger til Altinn 3")
    val altinn3Tilganger: Set<String>,
    @Description("Tilganger til Altinn 2")
    val altinn2Tilganger: Set<String>,
    @Description("list av underenheter til denne organisasjonen hvor brukeren har tilganger")
    val underenheter: List<AltinnTilgang>,
    @Description("Navn på organisasjonen")
    val navn: String,
    @Description("Organisasjonsform. se https://www.brreg.no/bedrift/organisasjonsformer/")
    @Example("BEDR")
    val organisasjonsform: String,
)

@Serializable
data class AltinnTilgangerResponse(
    @Description("Om det var en feil ved henting av tilganger. Dersom denne er true kan det bety at ikke alle tilganger er hentet.")
    val isError: Boolean,
    @Description("Organisasjonshierarkiet med brukerens tilganger")
    val hierarki: List<AltinnTilgang>,
    @Description("Map fra organisasjonsnummer til tilganger. Convenience for å slå opp tilganger på orgnummer.")
    val orgNrTilTilganger: Map<String, Set<String>>,
    @Description("Map fra tilgang til organisasjonsnummer. Convenience for å slå opp orgnummer på tilgang.")
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

@Description(
    """
Filter for hvilke tilganger som skal hentes. Dersom flere filter er angitt tolkes dette som logisk OR.
Trenger dere en annen logikk kan dere kontakte teamet så kan vi prioritere å støtte det. 

Filterne har et hardkodet sett med gyldige verdier. 
Dersom dere angir en verdi utenfor det gyldige settet vil det returneres en HTTP 400 feil som angir den ugyldige verdien, samt hva som er tillatt.
Gyldige verdier varierer i dev og prod for noen servicecode/version kombinasjoner. Derfor er de ikke dokumentert her.
De gyldige verdiene finner dere i kildekoden: https://github.com/navikt/arbeidsgiver-altinn-tilganger/blob/main/src/main/kotlin/no/nav/fager/altinn/ResourceRegistry.kt#L18. 
KnownAltinn2Tjenester (altinn2) og KnownResourceIds (altinn3)  

Dersom dere trenger andre verdier kan dere kontakte teamet så legger vi dem til.

Til slutt en liten caveat: Filtrering gjøres på løvnoder/virksomheter. Her antas det at overordnede enhet ikke har tilganger som ikke er delegert til en av sine underenheter.
Logikken er nå slik at dersom en parent har tjenesten, men ingen av underenhetene har den så vil parenten også fjernes.
Dette er for å hindre at en overordnet enhet blir til laveste nivå.
Dette er en antakelse vi har gjort basert på observasjoner i dev. 
Dersom det viser seg at tjenester delegeres ekspisitt på overordnet nivå og det er noe dere trenger at vi støtter ta kontakt med oss. 
    """
)
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