package no.nav.fager.redis

import kotlinx.serialization.Serializable
import kotlin.test.Test

class RedisCodecTest {

    @Test
    fun `redis codec does not fail when cache contains unknown fields`() {
        val codec = createCodec<DummyData>("test-namespace")
        val jsonWithUnknownFields = """{"knownField":"knownValue","unknownField":"unknownValue"}"""

        val deserialized = codec.decodeValue(jsonWithUnknownFields)

        assert(deserialized.knownField == "knownValue")
    }
    
    @Serializable
    private data class DummyData(val knownField: String)
}
