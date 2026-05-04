package no.nav.fager.altinn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.fager.infrastruktur.Health
import no.nav.fager.infrastruktur.RequiresReady
import no.nav.fager.infrastruktur.logger
import no.nav.fager.infrastruktur.rethrowIfCancellation
import no.nav.fager.redis.RedisConfig
import no.nav.fager.redis.RedisLoadingCache
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class RoleRegistry(
    private val altinn3Client: Altinn3Client,
    private val resourceRegistry: ResourceRegistry,
    redisConfig: RedisConfig,
    backgroundCoroutineScope: CoroutineScope?,
) : RequiresReady {

    private val log = logger()

    @Volatile
    private var isReady = false
    override fun isReady() = isReady

    val cacheTTL = 30.minutes
    val cacheRefreshInterval = cacheTTL / 3

    private val cache = RedisLoadingCache(
        metricsName = "role-registry",
        redisClient = redisConfig.createClient<List<RoleExport>>("role-export-v1"),
        loader = { _ -> altinn3Client.accessManagement_Roles().getOrThrow() },
        cacheTTL = cacheTTL.toJavaDuration(),
    )

    // legacyRoleCode (e.g. "dagl") → RoleExport
    private val byLegacyCode = AtomicReference<Map<String, RoleExport>>(emptyMap())

    fun getRoles(): Map<String, RoleExport> = byLegacyCode.get()

    private fun referencedLegacyCodes(): Set<String> =
        resourceRegistry.getPolicySubjects()
            .values
            .flatten()
            .filter { it.type == "urn:altinn:rolecode" }
            .map { it.value }
            .toSet()

    private fun rebuildIndex(roles: List<RoleExport>) {
        val keep = referencedLegacyCodes()

        val index = mutableMapOf<String, RoleExport>()
        for (role in roles) {
            val code = role.legacyRoleCode ?: continue
            if (code !in keep) continue
            index[code] = role
        }

        byLegacyCode.set(index)

        val missing = keep - index.keys
        if (missing.isNotEmpty()) {
            log.warn("Referenced role codes not found in Altinn /roles: {}", missing)
        }
    }

    init {
        // Warm-up loop
        backgroundCoroutineScope?.launch {
            while (!isReady && !Health.terminating) {
                if (!resourceRegistry.isReady()) {
                    delay(100.milliseconds)
                    continue
                }
                try {
                    val roles = retryWithBackoff { cache.get("_roles") }
                    rebuildIndex(roles.getOrThrow())
                    isReady = true
                    log.info("RoleRegistry ready. Indexed {} roles.", byLegacyCode.get().size)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    log.error("Failed to warm up RoleRegistry", e)
                    delay(100.milliseconds)
                }
            }
        }

        // Refresh loop
        backgroundCoroutineScope?.launch {
            while (!Health.terminating) {
                try {
                    val roles = cache.update("_roles")
                    rebuildIndex(roles)
                    log.info("Role export updated")
                    delay(cacheRefreshInterval)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    log.error("Failed to refresh role export. Retrying shortly.", e)
                    delay(5.seconds)
                }
            }
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = 5,
        initialDelayMs: Long = 500,
        maxDelayMs: Long = 5000,
        backoffFactor: Double = 2.0,
        action: suspend () -> T
    ): Result<T> {
        var currentDelayMs = initialDelayMs
        var lastException: Throwable? = null

        repeat(maxRetries) { attempt ->
            try {
                return Result.success(action())
            } catch (e: Exception) {
                e.rethrowIfCancellation()

                lastException = e
                if (attempt < maxRetries - 1) {
                    log.warn("Attempt ${attempt + 1} failed, retrying in ${currentDelayMs}ms", e)
                    delay(currentDelayMs.milliseconds)
                    currentDelayMs = minOf((currentDelayMs * backoffFactor).toLong(), maxDelayMs)
                }
            }
        }

        return Result.failure(lastException ?: IllegalStateException("Unknown error in retryWithBackoff"))
    }
}
