package no.nav.fager.altinn

import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.schemakenerator.core.annotations.Description
import io.github.smiley4.schemakenerator.core.annotations.Example
import io.github.smiley4.schemakenerator.core.annotations.Deprecated
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Timer
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.InloggetBrukerPrincipal
import no.nav.fager.altinn.AltinnTilgangerResponse.Companion.toResponse
import no.nav.fager.infrastruktur.Metrics
import no.nav.fager.infrastruktur.coRecord
import java.util.concurrent.ConcurrentHashMap

private val clientTaggedTimerTimer = ConcurrentHashMap<String, Timer>()
private fun withTimer(clientId: String): Timer =
    clientTaggedTimerTimer.computeIfAbsent(clientId) {
        Timer.builder("altinn_tilganger_responsetid")
            .tag("klientapp", it)
            .publishPercentileHistogram()
            .register(Metrics.meterRegistry)
    }

fun Route.routeAltinnTilganger(altinnService: AltinnService) {
    authenticate {
        // TODO: it may be useful to be able to set a filter with service/resource (and optionally orgnr) as input
        // a lot of other teams often only care about a single service/resource

        post("/altinn-tilganger", {
            description = "Hent tilganger fra Altinn for innlogget bruker."
            request {
                // todo document optional callid header
            }
            response {
                HttpStatusCode.OK to {
                    description = "Successful Request"
                    body<AltinnTilgangerResponse> {
                        exampleRef("Successful Respons", "tilganger_success")
                    }
                }
            }
        }) {
            val fnr = call.principal<InloggetBrukerPrincipal>()!!.fnr
            val clientId = call.principal<InloggetBrukerPrincipal>()!!.clientId
            withTimer(clientId).coRecord {
                val tilganger = altinnService.hentTilganger(fnr, this)
                call.respond(tilganger.toResponse())
            }
        }
    }
}


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
