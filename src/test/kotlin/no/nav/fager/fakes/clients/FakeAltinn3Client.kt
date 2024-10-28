package no.nav.fager.fakes.clients

import no.nav.fager.altinn.Altinn3Client
import no.nav.fager.altinn.AuthorizedParty
import no.nav.fager.altinn.PolicySubject
import no.nav.fager.fakes.FakeClientBase

class FakeAltinn3Client(
    private val resourceOwner_AuthorizedPartiesHandler: () -> List<AuthorizedParty> = { listOf() },
    private val resourceRegistry_PolicySubjectsHandler: (resourceId: String) -> List<PolicySubject> = { listOf() },
) : Altinn3Client, FakeClientBase() {

    override suspend fun resourceOwner_AuthorizedParties(fnr: String): List<AuthorizedParty> {
        addFunctionCall(this::resourceOwner_AuthorizedParties.name, fnr)
        return resourceOwner_AuthorizedPartiesHandler()
    }

    override suspend fun resourceRegistry_PolicySubjects(resourceId: String): Result<List<PolicySubject>> {
        addFunctionCall(this::resourceRegistry_PolicySubjects.name, resourceId)
        return Result.success(resourceRegistry_PolicySubjectsHandler(resourceId))
    }
}