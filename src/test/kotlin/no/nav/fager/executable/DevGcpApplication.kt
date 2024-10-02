package no.nav.fager.executable

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.decodeBase64String
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.fager.altinn.Altinn2Config
import no.nav.fager.altinn.Altinn3Config
import no.nav.fager.infrastruktur.AuthConfig
import no.nav.fager.maskinporten.MaskinportenConfig
import no.nav.fager.redis.RedisConfig
import no.nav.fager.ktorConfig
import java.util.concurrent.TimeUnit


fun main() {
    val resourceNames = findSecretResourceNames()
    fun findResourceName(prefix: String) = requireNotNull(resourceNames.find { it.startsWith(prefix) }) {
        "can't find $prefix secrets :'("
    }

    val tokenx = getSecrets(findResourceName("tokenx-arbeidsgiver-altinn-tilganger"))
    val maskinporten = getSecrets(findResourceName("maskinporten-arbeidsgiver-altinn-tilganger"))
    val redis = getSecrets(findResourceName("aiven-arbeidsgiver-altinn-tilganger"))
    val altinnTilganger = getSecrets("altinn-tilganger")

    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        ktorConfig(
            altinn3Config = Altinn3Config(
                baseUrl = "https://platform.tt02.altinn.no",
                ocpApimSubscriptionKey = altinnTilganger["OCP_APIM_SUBSCRIPTION_KEY"]!!,
            ),
            altinn2Config = Altinn2Config(
                baseUrl = "https://tt02.altinn.no",
                apiKey = altinnTilganger["ALTINN_2_API_KEY"]!!,
            ),
            authConfig = AuthConfig(
                clientId = tokenx["TOKEN_X_CLIENT_ID"]!!,
                issuer = tokenx["TOKEN_X_ISSUER"]!!,
                jwksUri = tokenx["TOKEN_X_JWKS_URI"]!!,
            ).also {
                println(it)
            },
            maskinportenConfig = MaskinportenConfig(
                clientId = maskinporten["MASKINPORTEN_CLIENT_ID"]!!,
                clientJwk = maskinporten["MASKINPORTEN_CLIENT_JWK"]!!,
                issuer = maskinporten["MASKINPORTEN_ISSUER"]!!,
                tokenEndpoint = maskinporten["MASKINPORTEN_TOKEN_ENDPOINT"]!!,
            ).also { println(it) },
            redisConfig = RedisConfig(
                uri = redis["REDIS_URI_TILGANGER"]!!,
                username = redis["REDIS_USERNAME_TILGANGER"]!!,
                password = redis["REDIS_PASSWORD_TILGANGER"]!!,
            ).also { println(it) },
        )
    })
        .start(wait = true)
}

private val kubectl = arrayOf("kubectl", "--context=dev-gcp", "--namespace=fager")

private fun findSecretResourceNames() = exec(
    *kubectl,
    "get", "pods", "-l", "app=arbeidsgiver-altinn-tilganger",
    "-o", """jsonpath=
        {'{'}
            "envFrom": { .items[0].spec.containers[?(@.name=="arbeidsgiver-altinn-tilganger")].envFrom },
            "env": { .items[0].spec.containers[?(@.name=="arbeidsgiver-altinn-tilganger")].env }
        {'}'}
        """.trimMargin()
).let { json ->
    val x = Json.decodeFromString<JsonElement>(json).jsonObject
    val envFromNames =
        x["envFrom"]!!.jsonArray.map { it.jsonObject["secretRef"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    val redisName =
        x["env"]!!.jsonArray.find { it.jsonObject["name"]!!.jsonPrimitive.content == "REDIS_USERNAME_TILGANGER" }!!
            .jsonObject["valueFrom"]!!.jsonObject["secretKeyRef"]!!.jsonObject["name"]!!.jsonPrimitive.content
    buildList {
        add(redisName)
        addAll(envFromNames)
    }
}

private fun getSecrets(secretName: String) =
    exec(*kubectl, "get", "secret", secretName, "-o", "jsonpath={@.data}").let {
        Json.decodeFromString<Map<String, String>>(it)
            .mapValues { e -> e.value.decodeBase64String() }
    }


private fun exec(vararg cmd: String): String {
    val timeout = 5L
    val process = Runtime.getRuntime().exec(cmd.toList().toTypedArray<String>())
    try {
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            error(
                """
                executing command timed out after $timeout seconds.
                command: ${cmd.joinToString(" ")}
                """.trimIndent()
            )
        }
        val exit = process.exitValue()
        val stderr = process.errorReader().readText()
        val stdout = process.inputReader().readText()
        if (exit != 0) {
            error(
                """
                command failed (exit value $exit): ${cmd.joinToString(" ")}
                stdout:
                ${stdout.prependIndent()}
                stderr:
                ${stderr.prependIndent()}
            """.trimIndent()
            )
        }
        return stdout
    } finally {
        process.destroy()
    }
}
