package no.nav.fager

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class Altinn3Config(
    val baseUrl: String,
    val ocpApimSubscriptionKey: String,
) {
    /** OBS: Verdien [ocpApimSubscriptionKey] er en *secret*. Pass på at den ikke blir logget! */
    override fun toString() = """Altinn3Config(baseUrl: $baseUrl, ocpApimSubscriptionKey: SECRET)"""
}

/** Swagger for api:
 * https://docs.altinn.studio/api/accessmanagement/resourceowneropenapi/#/Authorized%20Parties/post_resourceowner_authorizedparties
 *
 * Se også:
 * https://docs.altinn.studio/nb/authorization/guides/integrating-link-service/
 **/
class Altinn3Client(
    val altinn3Config: Altinn3Config,
    val maskinporten: Maskinporten,
    httpClientEngine: HttpClientEngine,
) {
    private val httpClient = HttpClient(httpClientEngine) {
        install(MaskinportenPlugin) {
            maskinporten = this@Altinn3Client.maskinporten
        }

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun hentAuthorizedParties(fnr: String): String {
        val httpResponse = httpClient.post("${altinn3Config.baseUrl}/resourceowner/authorizedparties") {
            accept(ContentType.Application.Json)
            setBody(
                """
                    {
                        "type": "urn:altinn:person:identifier-no",
                        "value": "$fnr"
                    }
                """
            )
        }

        httpResponse.status.isSuccess()
        return ""
    }
}
