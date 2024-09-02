package no.nav.fager

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.path
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Altinn3Config(
    val baseUrl: String,
    val ocpApimSubscriptionKey: String,
) {
    /** OBS: Verdien [ocpApimSubscriptionKey] er en *secret*. Pass på at den ikke blir logget! */
    override fun toString() = """Altinn3Config(baseUrl: $baseUrl, ocpApimSubscriptionKey: SECRET)"""

    companion object {
        fun nais() = Altinn3Config(
            baseUrl = System.getenv("ALTINN_3_API_BASE_URL"),
            ocpApimSubscriptionKey = System.getenv("OCP_APIM_SUBSCRIPTION_KEY"),
        )
    }
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
) {
    private val httpClient = HttpClient(CIO) {
        install(MaskinportenPlugin) {
            maskinporten = this@Altinn3Client.maskinporten
        }

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        install(Logging) {
            sanitizeHeader {
                true
            }
        }
    }

    suspend fun hentAuthorizedParties(fnr: String): List<AuthoririzedParty> {
        val baseUrl = URLBuilder(altinn3Config.baseUrl)
        val httpResponse =
            httpClient.post {
                url {
                    takeFrom(altinn3Config.baseUrl)
                    appendPathSegments("/accessmanagement/api/v1/resourceowner/authorizedparties")
                    parameters.append("includeAltinn2", "true")
                }
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                header("ocp-apim-subscription-key", altinn3Config.ocpApimSubscriptionKey)
                setBody(
                    mapOf(
                        "type" to "urn:altinn:person:identifier-no",
                        "value" to fnr
                    )
                )
            }

        if (!httpResponse.status.isSuccess())
            error("oh noes not ok")

        return httpResponse.body()
    }
}

@Suppress("unused")
@Serializable
class AuthoririzedParty(
    val organizationNumber: String?,
    val authorizedResources: List<String>,
    val subunits: List<AuthoririzedParty>
)