package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.SnolcConfig
import org.olcbox.app.desktop.DesktopPaths
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The SNOLC client as a subprocess (native/snolc-<os>-<arch>), mirroring OlcboxVpnService's
 * startSnolcCore: a SOCKS5 with the session credentials, remote carrier/protection,
 * domains resolved through the tunnel or specified DNS, and auth token in the environment.
 */
internal class DesktopSnolc(
    private val log: (String) -> Unit,
) {
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    suspend fun start(
        config: SnolcConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        tomlContent: String,
    ) = withContext(Dispatchers.IO) {
        stop()
        val binary = DesktopNativeAssets.resolveSnolcBinary()
        val appDataDir = DesktopPaths.appDataDir()
        val snolcDir = appDataDir.resolve("snolc")
        Files.createDirectories(snolcDir)
        val configFile = snolcDir.resolve("snolc_client.toml").toFile()
        configFile.writeText(tomlContent)

        val cmd = listOf(binary.toString(), "run", configFile.absolutePath)
        log("Starting SNOLC (${config.summary()}) on $listenHost:$listenPort, dns=${config.dnsServer.ifBlank { "device" }}")

        val started = ProcessBuilder(cmd).redirectErrorStream(true).apply {
            if (config.authToken.isNotBlank()) {
                environment()["SNOLC_AUTH_TOKEN"] = config.authToken
            }
            if (socksUsername.isNotBlank()) {
                environment()["SNOLC_SOCKS_USER"] = socksUsername
            }
            if (socksPassword.isNotBlank()) {
                environment()["SNOLC_SOCKS_PASS"] = socksPassword
            }
        }.start()

        process = started
        Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) log("snolc: ${line.trimEnd()}")
                }
            }
        }.apply {
            isDaemon = true
            name = "snolc-log"
            start()
        }
    }

    /** Exit code text for a start that never opened its port. */
    fun exitDescription(): String = process?.let {
        if (it.isAlive) "still starting" else "exited with ${it.exitValue()}"
    } ?: "not started"

    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            if (!running.waitFor(2_000, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
                running.waitFor(2_000, TimeUnit.MILLISECONDS)
            }
        }.onFailure { log("SNOLC stop failed: ${it.message}") }
    }
}
