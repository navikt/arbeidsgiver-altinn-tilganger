package no.nav.fager.altinn

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
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
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.kotlinx.json.json
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.fager.maskinporten.Maskinporten
import no.nav.fager.maskinporten.MaskinportenPlugin
import no.nav.fager.infrastruktur.logger
import javax.net.ssl.SSLHandshakeException

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

interface Altinn3Client{
    suspend fun hentAuthorizedParties(fnr: String): List<AuthorizedParty>
}

/** Swagger for api:
 * https://docs.altinn.studio/api/accessmanagement/resourceowneropenapi/#/Authorized%20Parties/post_resourceowner_authorizedparties
 *
 * Se også:
 * https://docs.altinn.studio/nb/authorization/guides/integrating-link-service/
 **/
class Altinn3ClientImpl(
    val altinn3Config: Altinn3Config,
    val maskinporten: Maskinporten,
) : Altinn3Client {
    private val log = logger()

    private val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(HttpRequestRetry) {
            maxRetries = 3
            retryOnExceptionIf { _, cause ->
                cause is SocketTimeoutException ||
                        cause is SSLHandshakeException
            }

            delayMillis { 250L }
        }

        install(MaskinportenPlugin) {
            maskinporten = this@Altinn3ClientImpl.maskinporten
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
    override suspend fun hentAuthorizedParties(fnr: String): List<AuthorizedParty> = try {
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
class AuthorizedParty(
    val organizationNumber: String?,
    val name: String,
    val type: String?,
    val unitType: String?,
    val authorizedResources: Set<String>,
    val isDeleted: Boolean,
    val subunits: List<AuthorizedParty>
)