package no.nav.fager.fakes.clients

import no.nav.fager.altinn.Altinn3Client
import no.nav.fager.altinn.AuthorizedParty
import no.nav.fager.altinn.CreateServiceOwnerRequest
import no.nav.fager.altinn.DelegationRequestResponse
import no.nav.fager.altinn.DelegationRequestStatus
import no.nav.fager.altinn.PolicySubject
import no.nav.fager.fakes.FakeClientBase

class FakeAltinn3Client(
    private val resourceOwner_AuthorizedPartiesHandler: () -> List<AuthorizedParty> = { listOf() },
    private val resourceRegistry_PolicySubjectsHandler: (resourceId: String) -> List<PolicySubject> = { listOf() },
    private val serviceOwner_CreateDelegationRequestHandler: (CreateServiceOwnerRequest) -> DelegationRequestResponse = { DelegationRequestResponse() },
    private val serviceOwner_GetDelegationRequestStatusHandler: (String) -> DelegationRequestStatus = { DelegationRequestStatus.None },
) : Altinn3Client, FakeClientBase() {

    override suspend fun resourceOwner_AuthorizedParties(fnr: String): Result<List<AuthorizedParty>> {
        addFunctionCall(this::resourceOwner_AuthorizedParties.name, fnr)
        return Result.success(resourceOwner_AuthorizedPartiesHandler())
    }

    override suspend fun resourceRegistry_PolicySubjects(resourceId: String): Result<List<PolicySubject>> {
        addFunctionCall(this::resourceRegistry_PolicySubjects.name, resourceId)
        return Result.success(resourceRegistry_PolicySubjectsHandler(resourceId))
    }

    override suspend fun serviceOwner_CreateDelegationRequest(request: CreateServiceOwnerRequest): Result<DelegationRequestResponse> {
        addFunctionCall(this::serviceOwner_CreateDelegationRequest.name, request)
        return Result.success(serviceOwner_CreateDelegationRequestHandler(request))
    }

    override suspend fun serviceOwner_GetDelegationRequestStatus(id: String): Result<DelegationRequestStatus> {
        addFunctionCall(this::serviceOwner_GetDelegationRequestStatus.name, id)
        return Result.success(serviceOwner_GetDelegationRequestStatusHandler(id))
    }
}