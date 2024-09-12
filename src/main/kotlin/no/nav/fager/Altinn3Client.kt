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
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.opentelemetry.instrumentation.annotations.WithSpan
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
    private val log = logger()

    private val httpClient = HttpClient(CIO) {
        expectSuccess = true

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

    @WithSpan
    suspend fun hentAuthorizedParties(fnr: String): List<AuthoririzedParty> = try {
        val httpResponse = httpClient.post {
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

        httpResponse.body()
    } catch (e: Exception) {
        log.info("POST /authorized_parties kastet exception {}", e::class.qualifiedName, e)
        emptyList()
    }
}

@Suppress("unused")
@Serializable
class AuthoririzedParty(
    val organizationNumber: String?,
    val name: String,
    val type: String?,
    val unitType: String?,
    val authorizedResources: Set<String>,
    val subunits: List<AuthoririzedParty>
)