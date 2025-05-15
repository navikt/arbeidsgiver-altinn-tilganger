package no.nav.fager.altinn

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.defaultHttpClient
import no.nav.fager.infrastruktur.logger
import no.nav.fager.texas.AuthClient
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuthClientPlugin
import no.nav.fager.texas.TexasAuthConfig

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

@Suppress("FunctionName")
interface Altinn3Client {
    suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>>
    suspend fun resourceRegistry_PolicySubjects(resourceId: String): Result<List<PolicySubject>>
}


class Altinn3ClientImpl(
    val altinn3Config: Altinn3Config,
    val texasAuthConfig: TexasAuthConfig,
) : Altinn3Client {

    private val resourceOwnerClient = defaultHttpClient {
        install(TexasAuthClientPlugin) {
            authClient = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN)
            fetchToken = { it.token("altinn:accessmanagement/authorizedparties.resourceowner") }
        }
    }

    /**
     * Swagger for api:
     * https://docs.altinn.studio/api/accessmanagement/resourceowneropenapi/#/Authorized%20Parties/post_resourceowner_authorizedparties
     *
     * Se også:
     * https://docs.altinn.studio/nb/authorization/guides/integrating-link-service/
     **/
    override suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>> = runCatching {
        val httpResponse = resourceOwnerClient.post {
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
    }

    private val resourceRegistryClient = defaultHttpClient()

    override suspend fun resourceRegistry_PolicySubjects(resourceId: String) = runCatching {
        val httpResponse = resourceRegistryClient.get {
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


