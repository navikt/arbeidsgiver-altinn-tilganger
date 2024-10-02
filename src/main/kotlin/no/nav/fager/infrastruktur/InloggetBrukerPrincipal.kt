package no.nav.fager.infrastruktur

import io.ktor.server.auth.*
import kotlinx.serialization.Serializable

@Serializable
class InloggetBrukerPrincipal(
    val fnr: String,
    val clientId: String,
) : Principal