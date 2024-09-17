package no.nav.fager

import io.lettuce.core.SetArgs
import io.lettuce.core.codec.RedisCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Duration


class AltinnService(private val altinn2Client: Altinn2Client, private val altinn3Client: Altinn3Client, redisConfig: RedisConfig) {
    private val syncedRedisConnection = redisConfig.createClient().connect(AltinnTilgangerCacheCodec())

    @Serializable
    data class AltinnTilgangerResultat(
        val isError: Boolean,
        val altinnTilganger: List<AltinnTilgang>
    )

    suspend fun hentTilganger(fnr: String, scope: CoroutineScope) : AltinnTilgangerResultat {
        var altinnTilganger = syncedRedisConnection.sync().get(altinnTilgangerCacheKey(fnr))
        if (altinnTilganger === null) {
            altinnTilganger = hentTilgangerFraAltinn(fnr, scope)
            if (!altinnTilganger.isError){
                syncedRedisConnection.sync().set(altinnTilgangerCacheKey(fnr), altinnTilganger, SetArgs.Builder.ex(
                    Duration.ofMinutes(10)))
            }
        }

        return altinnTilganger
    }

    private suspend fun hentTilgangerFraAltinn(fnr: String, scope: CoroutineScope) : AltinnTilgangerResultat {
        val altinn2TilgangerJob = scope.async { altinn2Client.hentAltinn2Tilganger(fnr) }
        val altinn3TilgangerJob = scope.async { altinn3Client.hentAuthorizedParties(fnr) }

        /* Ingen try-catch rundt .await() siden begge klientene håndterer alle exceptions internt. */
        val altinn2Tilganger = altinn2TilgangerJob.await()
        val altinn3Tilganger = altinn3TilgangerJob.await()

        return AltinnTilgangerResultat(altinn2Tilganger.isError, mapToHierarchy(altinn3Tilganger, altinn2Tilganger))
    }

    private fun altinnTilgangerCacheKey(fnr: String) = "altinn-tilganger-$fnr"

    private fun mapToHierarchy(
        authorizedParties: List<AuthoririzedParty>,
        altinn2Tilganger: Altinn2Tilganger
    ): List<AltinnTilgang> {

        return authorizedParties
            .filter { it.organizationNumber != null && it.unitType != null } // er null for type=person
            .map { party ->
                AltinnTilgang(
                    orgNr = party.organizationNumber!!, // alle orgnr finnes i altinn3 pga includeAltinn2=true
                    name = party.name,
                    organizationForm = party.unitType!!,
                    altinn3Tilganger = party.authorizedResources,
                    altinn2Tilganger = altinn2Tilganger.orgNrTilTjenester[party.organizationNumber]
                        ?.map { """${it.serviceCode}:${it.serviceEdition}""" }?.toSet()
                        ?: emptySet(),
                    underenheter = mapToHierarchy(party.subunits, altinn2Tilganger),
                )
            }
    }

    class AltinnTilgangerCacheCodec : RedisCodec<String, AltinnTilgangerResultat> {
        private val charset: Charset = Charset.forName("UTF-8")

        override fun decodeKey(bytes: ByteBuffer): String {
            return charset.decode(bytes).toString()
        }

        @OptIn(ExperimentalSerializationApi::class)
        override fun decodeValue(bytes: ByteBuffer): AltinnTilgangerResultat? {
            try {
                val tilganger = Json.decodeFromStream<AltinnTilgangerResultat>(ByteArrayInputStream(bytes.array()))
                return tilganger
            } catch (e: Exception) {
                return null
            }
        }

        override fun encodeKey(key: String): ByteBuffer {
            return charset.encode(key)
        }

        @OptIn(ExperimentalSerializationApi::class)
        override fun encodeValue(value: AltinnTilgangerResultat?): ByteBuffer {
            try {
                val stream = ByteArrayOutputStream()
                Json.encodeToStream(value, stream)
                return ByteBuffer.wrap(stream.toByteArray())
            } catch (e: IOException) {
                return ByteBuffer.wrap(ByteArray(0))
            }
        }
    }
}
