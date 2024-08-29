package no.nav.fager

import io.ktor.client.engine.HttpClientEngine
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        mockKtorConfig()
    })
        .start(wait = true)
}

fun Application.mockKtorConfig(
    authConfig: AuthConfig = oauth2MockServer,
    maskinportenConfig: MaskinportenConfig = maskinportenMockConfig,
    redisConfig: RedisConfig = localRedisConfig,
    httpClientEngine: HttpClientEngine = io.ktor.client.engine.cio.CIO.create(),
) {
    ktorConfig(
        authConfig = authConfig,
        maskinportenConfig = maskinportenConfig,
        httpClientEngine = httpClientEngine,
        redisConfig = redisConfig
    )
}