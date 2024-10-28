package no.nav.fager.altinn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.Service
import no.nav.fager.infrastruktur.logger
import no.nav.fager.redis.RedisConfig
import no.nav.fager.redis.RedisLoadingCache
import no.nav.fager.redis.createCodec
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val KnownResources = listOf(
    Resource(
        resourceId = "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-skjemaer",
        altinn2Tjeneste = listOf(Altinn2Tjeneste("5810", "1"))
    )
)

class ResourceRegistry(
    private val altinn3Client: Altinn3Client,
    redisConfig: RedisConfig,
    backgroundCoroutineScope: CoroutineScope?,
) : Service {

    private val log = logger()

    @Volatile
    private var isReady = false

    override fun isReady() = isReady

    private val cache = RedisLoadingCache(
        name = "resource-registry",
        redisConfig = redisConfig,
        codec = createCodec<List<PolicySubject>>(),
        loader = { s -> altinn3Client.resourceRegistry_PolicySubjects(s).getOrThrow() }
    )

    private val policySubjectsPerResourceId = KnownResources.associate { resource ->
        resource.resourceId to emptyList<PolicySubject>()
    }.toMutableMap()

    val resourceIdToAltinn2Tjeneste: Map<ResourceId, List<Altinn2Tjeneste>> = KnownResources.associate { resource ->
        resource.resourceId to resource.altinn2Tjeneste
    }

    init {
        backgroundCoroutineScope?.launch {
            updateLoop()
        }
    }

    fun getResourceIdForPolicySubject(urn: PolicySubjectUrn): List<ResourceId> =
        policySubjectsPerResourceId.filter { (_, policySubjects) ->
            policySubjects.any { it.urn == urn }
        }.map { it.key }

    suspend fun updatePolicySubjectsForKnownResources() {
        val results = KnownResources.map { resource ->
            // TODO: try lookup in redis first and only call altinn3Client if not found and cache result
            //resource to altinn3Client.resourceRegistry_PolicySubjects(resource.resourceId)
            resource to runCatching { cache.get(resource.resourceId) }
        }

        results.forEach { (resource, result) ->
            result.fold(
                onSuccess = { policySubjects ->
                    policySubjectsPerResourceId[resource.resourceId] = policySubjects
                },
                onFailure = { error ->
                    log.error("Failed to fetch policy subjects for resource ${resource.resourceId}", error)
                }
            )
        }

        if (!isReady && results.all { it.second.isSuccess }) {
            isReady = true
        }
    }

    private suspend fun updateLoop() {
        while (true) {
            updatePolicySubjectsForKnownResources()

            if (isReady) {
                delay(10.minutes)
            } else {
                delay(5.seconds)
            }
        }
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