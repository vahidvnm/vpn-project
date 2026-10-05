package com.vpnproject.app.engine

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.vpnproject.app.core.ImportedConfig
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs a short Xray-core "real delay" probe without creating Android VpnService/TUN.
 *
 * This is intentionally separate from Connect & Verify: it starts a temporary
 * core with only the selected outbound and asks libXray to fetch a small HTTP
 * URL through that outbound. It is closer to v2rayNG's real-delay check than a
 * raw TCP/TLS endpoint probe, but the final Android VPN route is still verified
 * only by XrayVpnService after Connect.
 */
class XrayRealDelayTester(private val context: Context) : CoreCallbackHandler {
    fun measure(
        config: ImportedConfig,
        verifyUrls: List<String> = DEFAULT_VERIFY_URLS,
        dnsServers: List<String> = DEFAULT_DNS_SERVERS,
        muxEnabled: Boolean = false,
        muxConcurrency: Int = DEFAULT_MUX_CONCURRENCY,
        logLevel: String = DEFAULT_LOG_LEVEL
    ): XrayRealDelayResult {
        val runtime = V2RayRuntimeConfigBuilder.buildDelayProbe(
            config = config,
            dnsServers = dnsServers,
            muxEnabled = muxEnabled,
            muxConcurrency = muxConcurrency,
            logLevel = logLevel
        )
        val urls = verifyUrls.filter { it.startsWith("http://") || it.startsWith("https://") }
            .ifEmpty { DEFAULT_VERIFY_URLS }
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey())
        val controller = Libv2ray.newCoreController(this)
        return try {
            controller.startLoop(runtime.configJson, NO_TUN_FD)
            Thread.sleep(START_DELAY_MS)
            val attempts = mutableListOf<String>()
            for (url in urls) {
                val result = measureDelayWithTimeout(controller, url, VERIFY_URL_TIMEOUT_MS)
                if (result.delayMs != null && result.delayMs >= 0L) {
                    return XrayRealDelayResult(
                        reachable = true,
                        latencyMs = result.delayMs,
                        detail = "Real delay OK via ${url.hostLabel()}: ${result.delayMs}ms. ${runtime.note}"
                    )
                }
                attempts += "${url.hostLabel()}: ${result.error ?: "no delay"}"
                if (result.error?.startsWith("timed out") == true) break
            }
            XrayRealDelayResult(
                reachable = false,
                latencyMs = null,
                detail = "Real delay failed: ${attempts.joinToString("; ").ifBlank { "no successful probe" }}"
            )
        } catch (error: Exception) {
            XrayRealDelayResult(
                reachable = false,
                latencyMs = null,
                detail = "Real delay failed: ${error.message ?: error.javaClass.simpleName}"
            )
        } finally {
            runCatching { controller.stopLoop() }
        }
    }

    override fun startup(): Long = 0L

    override fun shutdown(): Long = 0L

    override fun onEmitStatus(l: Long, s: String?): Long = 0L

    private fun measureDelayWithTimeout(controller: CoreController, url: String, timeoutMs: Long): DelayProbeResult {
        val queue = ArrayBlockingQueue<DelayProbeResult>(1)
        Thread({
            val result = try {
                val delay = controller.measureDelay(url)
                if (delay >= 0L) DelayProbeResult(delayMs = delay) else DelayProbeResult(error = "returned $delay")
            } catch (error: Exception) {
                DelayProbeResult(error = error.message ?: error.javaClass.simpleName)
            }
            queue.offer(result)
        }, "xray-real-delay-${url.hostLabel()}").apply {
            isDaemon = true
            start()
        }
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: DelayProbeResult(error = "timed out after ${timeoutMs / 1000}s")
    }

    private fun xudpBaseKey(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: context.packageName
        val rawKey = androidId.toByteArray(Charsets.UTF_8).copyOf(32)
        return Base64.encodeToString(rawKey, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun String.hostLabel(): String = removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

    private data class DelayProbeResult(
        val delayMs: Long? = null,
        val error: String? = null
    )

    private companion object {
        const val NO_TUN_FD = -1
        const val START_DELAY_MS = 350L
        const val VERIFY_URL_TIMEOUT_MS = 12_000L
        val DEFAULT_VERIFY_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
        val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8", "localhost")
        const val DEFAULT_MUX_CONCURRENCY = 8
        const val DEFAULT_LOG_LEVEL = "warning"
    }
}

data class XrayRealDelayResult(
    val reachable: Boolean,
    val latencyMs: Long?,
    val detail: String
)
