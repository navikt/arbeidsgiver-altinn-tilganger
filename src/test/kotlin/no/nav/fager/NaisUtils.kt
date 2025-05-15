package no.nav.fager

import io.ktor.util.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit


const val namespace = "fager"
const val app = "arbeidsgiver-altinn-tilganger"

@Suppress("unused")
enum class Cluster {
    `dev-gcp`,
    `prod-gcp`
}

class Proc {
    companion object {
        fun exec(
            cmd: Array<String>,
            envp: Array<String>? = null,
            silent: Boolean = true,
            timeout: Long = 5,
        ): String {
            if (!silent) {
                println(cmd.joinToString(" "))
            }
            val process = Runtime.getRuntime().exec(cmd.toList().toTypedArray<String>(), envp)
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

        @OptIn(DelicateCoroutinesApi::class)
        fun execBg(
            cmd: Array<String>,
            envp: Array<String>? = null,
        ): Process {
            println(cmd.joinToString(" "))
            val process = Runtime.getRuntime().exec(cmd.toList().toTypedArray<String>(), envp)
            GlobalScope.launch(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { println(it) }
                }
            }
            GlobalScope.launch(Dispatchers.IO) {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { println(it) }
                }
            }
            return process
        }
    }
}

class Kubectl(
    cluster: Cluster,
) {
    private val k = arrayOf("kubectl", "--context=$cluster", "--namespace=$namespace")

    private fun kubectl(vararg cmd: String) = arrayOf(*k, *cmd)

    private fun sexec(vararg args: String): String = Proc.exec(kubectl(*args))
    private fun exec(vararg args: String): String = Proc.exec(kubectl(*args), silent = false)
    private fun execBg(vararg args: String): Process = Proc.execBg(kubectl(*args))

    fun portForward(port: Int, isReady: () -> Boolean) {
        try {
            execBg(
                "port-forward", getPods().first(), "$port:$port",
            ).let {
                Runtime.getRuntime().addShutdownHook(object : Thread() {
                    override fun run() {
                        it.destroy()
                    }
                })
            }
            var attempts = 0
            while (!isReady() && attempts <= 5) {
                println("waiting for port forwarding to be available")
                attempts += 1
                Thread.sleep(200)
            }
        } catch (e: Exception) {
            println("Error running process: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getPods() = sexec(
        "get", "pods", "-l", "app=$app", "-o", "jsonpath={.items[*].metadata.name}"
    ).split(" ")

    fun scale(replicas: Int) {
        exec(
            "scale", "--replicas=$replicas", "deployment/${app}",
        ).let {
            println(it)
        }
    }

    fun getReplicas() = sexec(
        "get", "deployment", app, "-o", "jsonpath={@.spec.replicas}"
    ).toInt()

    fun findSecret(prefix: String) = sexec(
        "get", "secrets", "-o", "jsonpath={@.items[*].metadata.name}"
    )
        .split(" ")
        .find { it.startsWith(prefix) } ?: error("could not find secret with prefix $prefix")

    fun getSecrets(secretName: String) =
        sexec("get", "secret", secretName, "-o", "jsonpath={@.data}").let {
            Json.decodeFromString<Map<String, String>>(it)
                .mapValues { e -> e.value.decodeBase64String() }
        }

    fun getEnvVars(envVarPrefix: String) =
        sexec(
            "get",
            "deployment",
            app,
            "-o",
            "jsonpath={@.spec.template.spec.containers[?(@.name=='$app')].env}"
        ).let {
            Json.decodeFromString<List<Map<String, JsonElement>>>(it)
                .filter { entries ->
                    entries.containsKey("value")
                            && entries["name"]?.jsonPrimitive?.content?.startsWith(envVarPrefix) == true
                }
                .associate { entries ->
                    entries["name"]?.jsonPrimitive?.content to entries["value"]?.jsonPrimitive?.content
                }
        }
}

abstract class GcpEnv(
    cluster: Cluster,
) {
    val kubectl = Kubectl(cluster)

    fun portForward(port: Int, isReady: () -> Boolean) = kubectl.portForward(port, isReady)
    fun getPods() = kubectl.getPods()
    fun scale(replicas: Int) = kubectl.scale(replicas)
    fun getReplicas() = kubectl.getReplicas()

    fun getEnvVars(envVarPrefix: String) = kubectl.getEnvVars(envVarPrefix)
    fun getSecrets(secretName: String) = kubectl.getSecrets(secretName)
    fun findSecret(prefix: String) = kubectl.findSecret(prefix)
}

class DevGcpEnv(
) : GcpEnv(Cluster.`dev-gcp`)

class ProdGcpEnv() : GcpEnv(Cluster.`prod-gcp`)

/**
 * noen eksempler på bruk
 */
fun main() {

    val devGcp = DevGcpEnv()
    val texas = devGcp.getEnvVars("NAIS_TOKEN_")
    println("texas: ")
    println("  $texas")


    val brukerSecrets = DevGcpEnv().getSecrets("notifikasjon-bruker-api-secrets")
    println("notifikasjon-bruker-api-secrets: ")
    println("   $brukerSecrets")

}

