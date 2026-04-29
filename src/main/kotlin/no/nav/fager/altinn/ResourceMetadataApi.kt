package no.nav.fager.altinn

import kotlinx.serialization.Serializable
import no.nav.fager.infrastruktur.logger
import org.slf4j.LoggerFactory

@Serializable
data class ResourceMetadataResponse(
    val resources: Map<String, ResourceMetadataEntry>,
)

@Serializable
data class ResourceMetadataEntry(
    val metadata: ResourceRegistryResource,
    val grantedByRoles: List<String>,
    val grantedByAccessPackages: List<String>,
)

private val log = LoggerFactory.getLogger("no.nav.fager.altinn.ResourceMetadataApi")

fun buildResourceMetadataResponse(
    metadata: Map<ResourceId, ResourceRegistryResource?>,
    policySubjects: Map<ResourceId, List<PolicySubject>>,
): ResourceMetadataResponse {
    val resources = linkedMapOf<String, ResourceMetadataEntry>()

    for (resource in KnownResources) {
        val resourceId = resource.resourceId
        if (resources.containsKey(resourceId)) continue

        val resourceMetadata = metadata[resourceId]
        if (resourceMetadata == null) {
            log.error("Missing resource metadata for $resourceId")
            throw IllegalStateException("Missing resource metadata for $resourceId")
        }

        val subjects = policySubjects[resourceId] ?: emptyList()

        val roles = subjects
            .filter { it.type == "urn:altinn:rolecode" }
            .map { it.value }
            .distinct()
            .sorted()

        val accessPackages = subjects
            .filter { it.type == "urn:altinn:accesspackage" }
            .map { it.value }
            .distinct()
            .sorted()

        resources[resourceId] = ResourceMetadataEntry(
            metadata = resourceMetadata,
            grantedByRoles = roles,
            grantedByAccessPackages = accessPackages,
        )
    }

    return ResourceMetadataResponse(resources = resources)
}


