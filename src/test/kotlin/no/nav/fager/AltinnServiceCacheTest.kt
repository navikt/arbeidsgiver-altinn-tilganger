package no.nav.fager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AltinnServiceCacheTest {


    @Test
    fun `cache entry settes`() = runTest {
        val redisMock = mockk<AltinnTilgangerRedisClient>()
        coEvery { redisMock.get(any()) } returns null
        coEvery { redisMock.set(any(), any()) } returns Unit

        val altinn2ClientMock = mockk<Altinn2Client>()
        coEvery { altinn2ClientMock.hentAltinn2Tilganger(any()) } returns Altinn2Tilganger(isError = false, orgNrTilTjenester = mapOf("910825496" to listOf(Altinn2Tjeneste(serviceCode = "4936", serviceEdition = "1"))))

        val altinn3ClientMock = mockk<Altinn3Client>()
        coEvery { altinn3ClientMock.hentAuthorizedParties(any()) } returns listOf(AuthoririzedParty(name = "SLEMMESTAD OG STAVERN REGNSKAP", organizationNumber = "910825496", authorizedResources = setOf("test-fager"), subunits = listOf(), unitType = "BEDR", type = "Business" ))

        val altinnService = AltinnService(altinn2ClientMock, altinn3ClientMock, redisMock)

        altinnService.hentTilganger("123", this)

        coVerify(exactly = 1) {  redisMock.get(any()) }
        coVerify(exactly = 1) {  redisMock.set(any(), any()) }
        coVerify(exactly = 1) {  altinn2ClientMock.hentAltinn2Tilganger(any()) }
        coVerify(exactly = 1) {  altinn3ClientMock.hentAuthorizedParties(any()) }
    }

    @Test
    fun `cache entry eksisterer`() = runTest {
        val redisMock = mockk<AltinnTilgangerRedisClient>()
        coEvery { redisMock.get(any()) } returns AltinnService.AltinnTilgangerResultat(isError = false, altinnTilganger = listOf(AltinnTilgang("910825496", altinn3Tilganger = setOf("test-fager"), altinn2Tilganger = setOf("4936:1"), underenheter = listOf(), name = "SLEMMESTAD OG STAVERN REGNSKAP", organizationForm = "BEDR")))
        coEvery { redisMock.set(any(), any()) } returns Unit

        val altinn2ClientMock = mockk<Altinn2Client>()
        coEvery { altinn2ClientMock.hentAltinn2Tilganger(any()) } returns Altinn2Tilganger(isError = false, orgNrTilTjenester = mapOf("910825496" to listOf(Altinn2Tjeneste(serviceCode = "4936", serviceEdition = "1"))))

        val altinn3ClientMock = mockk<Altinn3Client>()
        coEvery { altinn3ClientMock.hentAuthorizedParties(any()) } returns listOf(AuthoririzedParty(name = "SLEMMESTAD OG STAVERN REGNSKAP", organizationNumber = "910825496", authorizedResources = setOf("test-fager"), subunits = listOf(), unitType = "BEDR", type = "Business" ))

        val altinnService = AltinnService(altinn2ClientMock, altinn3ClientMock, redisMock)

        altinnService.hentTilganger("123", this)

        coVerify(exactly = 1) {  redisMock.get(any()) }
        coVerify(exactly = 0) {  redisMock.set(any(), any()) }
        coVerify(exactly = 0) {  altinn2ClientMock.hentAltinn2Tilganger(any()) }
        coVerify(exactly = 0) {  altinn3ClientMock.hentAuthorizedParties(any()) }
    }

    @Test
    fun `cache entry settes ikke på grunn av feil i altinn2 respons`() = runTest {
        val redisMock = mockk<AltinnTilgangerRedisClient>()
        coEvery { redisMock.get(any()) } returns null
        coEvery { redisMock.set(any(), any()) } returns Unit

        val altinn2ClientMock = mockk<Altinn2Client>()
        coEvery { altinn2ClientMock.hentAltinn2Tilganger(any()) } returns Altinn2Tilganger(isError = true, orgNrTilTjenester = mapOf("910825496" to listOf(Altinn2Tjeneste(serviceCode = "4936", serviceEdition = "1"))))

        val altinn3ClientMock = mockk<Altinn3Client>()
        coEvery { altinn3ClientMock.hentAuthorizedParties(any()) } returns listOf(AuthoririzedParty(name = "SLEMMESTAD OG STAVERN REGNSKAP", organizationNumber = "910825496", authorizedResources = setOf("test-fager"), subunits = listOf(), unitType = "BEDR", type = "Business" ))

        val altinnService = AltinnService(altinn2ClientMock, altinn3ClientMock, redisMock)

        altinnService.hentTilganger("123", this)

        coVerify(exactly = 1) {  redisMock.get(any()) }
        coVerify(exactly = 0) {  redisMock.set(any(), any()) }
        coVerify(exactly = 1) {  altinn2ClientMock.hentAltinn2Tilganger(any()) }
        coVerify(exactly = 1) {  altinn3ClientMock.hentAuthorizedParties(any()) }
    }
}

