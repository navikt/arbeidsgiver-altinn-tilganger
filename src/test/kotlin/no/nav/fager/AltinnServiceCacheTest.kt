package no.nav.fager

import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class AltinnServiceCacheTest {


    @ParameterizedTest
    @MethodSource("testData")
    fun `cacher altinn tilganger`(altinn2Tilgagner: Altinn2Tilganger, authorizedParties: List<AuthoririzedParty>, cacheGetCalls: Int, cacheSetCalls: Int){
        val altinn2ClientMock = mockk<Altinn2Client>()
        coEvery { altinn2ClientMock.hentAltinn2Tilganger(any()) } returns
    }


    companion object {
        @JvmStatic
        fun testData(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Altinn2Tilganger(isError = true, orgNrTilTjenester = mapOf())),
                Arguments.of(),
                Arguments.of(),
            )
        }
    }
}

