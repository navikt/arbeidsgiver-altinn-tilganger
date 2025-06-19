package no.nav.fager.altinn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.RequiresReady
import no.nav.fager.infrastruktur.basedOnEnv
import no.nav.fager.infrastruktur.logger
import no.nav.fager.redis.RedisConfig
import no.nav.fager.redis.RedisLoadingCache
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Denne mappingen kunne vi kanskje slått opp automatisk fra https://platform.tt02.altinn.no/resourceregistry/api/v1/resource/search?Id=nav_arbeidsforhold
 * se feltet resourceReferences
 */
val KnownResources = basedOnEnv(
    prod = listOfNotNull(
        Resource(
            resourceId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5810", "1"))
        ),
        Resource(
            resourceId = "nav_sosialtjenester_digisos-avtale",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5867", "1"))
        ),
        Resource(
            resourceId = "nav_forebygge-og-redusere-sykefravar_sykefravarsstatistikk", //OBS! DENNE HAR SERVICE EDITION 2 I PROD
            altinn2Tjeneste = listOf(Altinn2Tjeneste("3403", "2"))
        ),
        Resource(
            resourceId = "nav_forebygge-og-redusere-sykefravar_samarbeid",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5934", "1"))
        )
    ),
    other = listOfNotNull(
        Resource(
            resourceId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5810", "1"))
        ),
        Resource(
            resourceId = "nav_sosialtjenester_digisos-avtale",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5867", "1"))
        ),
        Resource(
            resourceId = "nav_forebygge-og-redusere-sykefravar_sykefravarsstatistikk", //OBS! DENNE HAR SERVICE EDITION 2 I PROD
            altinn2Tjeneste = listOf(Altinn2Tjeneste("3403", "1"))
        ),
        Resource(
            resourceId = "nav_forebygge-og-redusere-sykefravar_samarbeid",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5934", "1"))
        ),
        Resource(
            resourceId = "nav_utbetaling_endre-kontonummer-refusjon-arbeidsgiver",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("2896", "87"))
        ),
        Resource(
            resourceId = "nav_tiltak_tiltaksrefusjon",
            altinn2Tjeneste = listOf() // ny tjeneste som før var del av 4936:1. tilgang til ny tjeneste betyr ikke tilgang til gammel tjeneste
        ),
        Resource(
            resourceId = "nav_arbeidsforhold_aa-registeret-innsyn-arbeidsgiver",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5441", "1"))
        ),
        Resource(
            resourceId = "nav_arbeidsforhold_aa-registeret-brukerstotte",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5441", "2"))
        ),
        Resource(
            resourceId = "nav_arbeidsforhold_aa-registeret-sok-tilgang",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5719", "1"))
        ),
        Resource(
            resourceId = "nav_arbeidsforhold_aa-registeret-oppslag-samarbeidspartnere",
            altinn2Tjeneste = listOf(Altinn2Tjeneste("5723", "1"))
        ),
    )
)

val KnownResourceIds = KnownResources.map { it.resourceId }
val KnownAltinn2Tjenester = (Altinn2Tjenester + KnownResources.flatMap {
    it.altinn2Tjeneste.map { t -> "${t.serviceCode}:${t.serviceEdition}" }
}).toSet()

class ResourceRegistry(
    private val altinn3Client: Altinn3Client,
    redisConfig: RedisConfig,
    backgroundCoroutineScope: CoroutineScope?,
) : RequiresReady {

    private val log = logger()

    @Volatile
    private var isReady = false

    override fun isReady() = isReady

    private val cache = RedisLoadingCache(
        name = "resource-registry",
        redisClient = redisConfig.createClient<List<PolicySubject>>("resource-registry"),
        loader = { s -> altinn3Client.resourceRegistry_PolicySubjects(s).getOrThrow() },
        cacheTTL = 7.days.toJavaDuration()
    )

    private val policySubjectsPerResourceId = KnownResources.associate { resource ->
        resource.resourceId to emptyList<PolicySubject>()
    }.toMutableMap()

    val resourceIdToAltinn2Tjeneste: Map<ResourceId, List<Altinn2Tjeneste>> = KnownResources.associate { resource ->
        resource.resourceId to resource.altinn2Tjeneste
    }

    init {
        backgroundCoroutineScope?.launch {
            while (!isReady) {
                isReady = updatePolicySubjectsForKnownResources { resourceId ->
                    this@ResourceRegistry.cache.get(resourceId)
                }
                delay(5.seconds)
            }
            log.info("ResourceRegistry isReady")
        }

        backgroundCoroutineScope?.launch {
            while (true) {
                val success = updatePolicySubjectsForKnownResources { resourceId -> cache.update(resourceId) }
                if (success) {
                    log.info("Policy subjects for kjente ressurser oppdatert")
                    delay(30.minutes)
                } else {
                    log.error("Kunne ikke oppdatere policy subjects for kjente ressurser. Prøver igjen fortløpende")
                    delay(5.seconds)
                }
            }
        }
    }

    fun getResourceIdForPolicySubject(urn: PolicySubjectUrn): List<ResourceId> =
        policySubjectsPerResourceId.filter { (_, policySubjects) ->
            policySubjects.any { it.urn == urn }
        }.map { it.key }

    suspend fun updatePolicySubjectsForKnownResources(
        fetcher: suspend ResourceRegistry.(resourceId: ResourceId) -> List<PolicySubject>
    ): Boolean {
        val results = KnownResources.map { resource ->
            val resourceId = resource.resourceId
            resource to runCatching { fetcher(resourceId) }
        }

        results.forEach { (resource, result) ->
            result.fold(
                onSuccess = { policySubjects ->
                    policySubjectsPerResourceId[resource.resourceId] = policySubjects
                },
                onFailure = { error ->
                    log.error("Feil ved henting av policy subjects for ${resource.resourceId}", error)
                }
            )
        }

        return results.none { it.second.isFailure }
    }
}

data class Resource(
    val resourceId: ResourceId,
    val altinn2Tjeneste: List<Altinn2Tjeneste>
)

typealias ResourceId = String


@Serializable
class PolicySubject(
    /**
     * e.g. urn:altinn:rolecode
     */
    val type: PolicySubjectType,
    /**
     * value of policysubject e.g. lede
     */
    val value: String,
    /**
     * urn of policysubject e.g. urn:altinn:rolecode:lede
     */
    val urn: PolicySubjectUrn,
)

@Serializable
class PolicySubjectResponseWrapper(
    val data: List<PolicySubject>
)


typealias PolicySubjectUrn = String
typealias PolicySubjectType = String