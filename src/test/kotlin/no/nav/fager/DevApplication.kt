package no.nav.fager

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer


fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        ktorConfig(
            authConfig = AuthConfig(
                clientId = "dev-gcp:fager:arbeidsgiver-altinn-tilganger",
                issuer = "https://tokenx.dev-gcp.nav.cloud.nais.io",
                jwksUri = "https://tokenx.dev-gcp.nav.cloud.nais.io/jwks",
            ),
            maskinportenConfig = maskinportenMockConfig,
            redisConfig = localRedisConfig,
        )
    })
        .start(wait = true)
}

