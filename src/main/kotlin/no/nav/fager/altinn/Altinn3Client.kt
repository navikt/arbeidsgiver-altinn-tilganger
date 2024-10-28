package no.nav.fager.altinn

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.network.sockets.*
import io.ktor.serialization.kotlinx.json.*
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.fager.infrastruktur.logger
import no.nav.fager.maskinporten.Maskinporten
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

interface Altinn3Client {
    suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>>

    suspend fun resourceRegistry_PolicySubjects(resourceId: String): Result<List<PolicySubject>>
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
    override suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>> = runCatching {
        val httpResponse = httpClient.post {
            url {
                takeFrom(altinn3Config.baseUrl)
                appendPathSegments("/accessmanagement/api/v1/resourceowner/authorizedparties")
                parameters.append("includeAltinn2", "true")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            bearerAuth(maskinporten.accessToken())
            header("ocp-apim-subscription-key", altinn3Config.ocpApimSubscriptionKey)
            setBody(
                mapOf(
                    "type" to "urn:altinn:person:identifier-no",
                    "value" to fnr
                )
            )
        }

        httpResponse.body()
    }

    override suspend fun resourceRegistry_PolicySubjects(resourceId: String) = runCatching {
        val httpResponse = httpClient.post {
            url {
                takeFrom(altinn3Config.baseUrl)
                appendPathSegments("/resourceregistry/api/v1/resource/$resourceId/policy/subjects")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
        }

        httpResponse.body<PolicySubjectResponseWrapper>().data
    }
}

@Serializable
class AuthorizedParty(
    val organizationNumber: String?,
    val name: String,
    val type: String?,
    val unitType: String?,
    val authorizedResources: Set<String>,
    val authorizedRoles: Set<String>,
    val isDeleted: Boolean,
    val subunits: List<AuthorizedParty>
) {
    /**
     * in current api authorizedRoles is a list of  LEDE/DAGL etc, an upper case variant og PolicySubject.value
     * in v2 this will be a PolicySubjectUrn. So this is a temporary method to convert to urn.
     * this method should be removed when v2 is in place.
     */
    val authorizedRolesAsUrn: Set<String>
        get() = authorizedRoles.map { "urn:altinn:rolecode:${it.lowercase()}" }.toSet()
}


