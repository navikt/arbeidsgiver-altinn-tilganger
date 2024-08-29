package no.nav.fager.fakes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.fager.AuthConfig

val oauth2MockServer = AuthConfig(
    clientId = "local:fager:arbeidsgiver-altinn-tilganger",
    issuer = "http://localhost:9000/tokenx",
    jwksUri = "http://localhost:9000/tokenx/jwks",
)

/** Add authorization token for the given user.
 *
 * The required service is started by running `docker-compose up`.
 * Users are defined in `/mock-oauth2-config.json`.
 */
suspend fun HttpRequestBuilder.authorization(subject: String) {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
    val response = client.post("http://localhost:9000/tokenx/token") {
        header("content-type", "application/x-www-form-urlencoded")
        setBody("""grant_type=client_credentials&client_id=1234&client_secret=1234&sub=$subject""".trimIndent())
    }
    val token = response.body<AccessToken>().accessToken
    header("Authorization", "Bearer $token")
}

@Serializable
private class AccessToken(
    @SerialName("access_token") val accessToken: String,
)

