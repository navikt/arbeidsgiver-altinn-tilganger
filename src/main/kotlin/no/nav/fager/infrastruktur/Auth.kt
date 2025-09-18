package no.nav.fager.infrastruktur

import no.nav.fager.texas.TokenIntrospectionResponse

interface Principal

class InnloggetBrukerPrincipal(
    val fnr: String,
    val clientId: String,
) : Principal {
    companion object {
        fun validate(token: TokenIntrospectionResponse): InnloggetBrukerPrincipal? = with(token) {
            val acrValid = other["acr"].let {
                it in listOf(
                    "idporten-loa-high", "Level4",
                    "idporten-loa-substantial", "Level3",
                )
            }
            val pid = other["pid"]!!
            val clientId = other["client_id"]!!

            if (acrValid && pid is String && clientId is String) {
                return InnloggetBrukerPrincipal(
                    fnr = pid,
                    clientId = clientId,
                )
            }

            return null
        }
    }
}

class AutentisertM2MPrincipal(
    val clientId: String,
) : Principal {
    companion object {
        fun validate(token: TokenIntrospectionResponse): AutentisertM2MPrincipal? = with(token) {
            val clientId = other["azp_name"]

            if (clientId is String) {
                return AutentisertM2MPrincipal(
                    clientId = clientId,
                )
            }

            return null
        }
    }
}