package com.vpnproject.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vpnproject.app.core.ConfigImporter
import com.vpnproject.app.core.ConfigParseException
import com.vpnproject.app.core.EndpointDiscovery
import com.vpnproject.app.core.EndpointHealthChecker
import com.vpnproject.app.core.HealthResult
import com.vpnproject.app.core.ImportedConfig
import com.vpnproject.app.core.NetworkKey
import com.vpnproject.app.core.NetworkType
import com.vpnproject.app.core.ProbeKind
import com.vpnproject.app.core.ResolvedEndpointCandidate
import com.vpnproject.app.core.RouteHealthCache

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var importedConfig: ImportedConfig? = null
    private val endpointDiscovery by lazy { EndpointDiscovery() }
    private val endpointHealthChecker by lazy { EndpointHealthChecker() }
    private val routeHealthCache = RouteHealthCache()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(this).apply {
            text = "VPN Project"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
        })

        root.addView(TextView(this).apply {
            text = "Import a user-owned OpenVPN or WireGuard config, resolve real public IPs with DoH, then run safe first-pass health probes."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(12), 0, dp(24))
        })

        status = TextView(this).apply {
            text = "Phase 2: import a config, then resolve and probe its endpoints."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Prepare VPN permission"
            setOnClickListener { requestVpnPermission() }
        })

        root.addView(Button(this).apply {
            text = "Import OpenVPN / WireGuard config"
            setOnClickListener { openConfigPicker() }
        })

        root.addView(Button(this).apply {
            text = "Resolve & probe imported endpoints"
            setOnClickListener { resolveAndProbeImportedConfig() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Android framework, acceptable for this no-AndroidX skeleton.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VPN_PERMISSION_REQUEST -> {
                status.text = if (resultCode == RESULT_OK) {
                    "VPN permission granted. Engine implementation comes in the next phases."
                } else {
                    "VPN permission was not granted."
                }
            }
            IMPORT_CONFIG_REQUEST -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    importConfig(uri)
                } else {
                    status.text = "No config selected."
                }
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_PERMISSION_REQUEST)
        } else {
            status.text = "VPN permission is already granted."
        }
    }

    private fun openConfigPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/x-openvpn-profile", "text/plain")
            )
        }
        startActivityForResult(intent, IMPORT_CONFIG_REQUEST)
    }

    private fun importConfig(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw ConfigParseException("Could not read selected file.")
            val name = displayName(uri)
            val config = ConfigImporter.parse(text, name)
            importedConfig = config
            val endpointLines = config.endpoints.joinToString("\n") { endpoint ->
                "• ${endpoint.protocol}  ${endpoint.host}:${endpoint.port}" +
                    (endpoint.verifyHost?.let { "  verify: $it" } ?: "")
            }
            val warnings = if (config.warnings.isEmpty()) "" else
                "\n\nWarnings:\n" + config.warnings.joinToString("\n") { "• $it" }
            status.text = "Imported ${config.kind} config${name?.let { " ($it)" } ?: ""}.\n" +
                "Endpoints found: ${config.endpoints.size}\n$endpointLines" +
                "\n\nAuth line: ${if (config.hasAuthUserPass) "yes" else "not detected"}" +
                "\n\nNext: tap Resolve & probe imported endpoints." +
                warnings
        } catch (e: Exception) {
            importedConfig = null
            status.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun resolveAndProbeImportedConfig() {
        val config = importedConfig
        if (config == null) {
            status.text = "Import an OpenVPN or WireGuard config first."
            return
        }

        status.text = "Resolving endpoints with DNS-over-HTTPS and probing public IP candidates..."
        Thread {
            val text = try {
                buildResolveAndProbeReport(config)
            } catch (e: Exception) {
                "Resolve/probe failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { status.text = text }
        }.start()
    }

    private fun buildResolveAndProbeReport(config: ImportedConfig): String {
        val lines = mutableListOf<String>()
        val networkKey = NetworkKey(NetworkType.UNKNOWN, "manual-ui")
        lines += "Phase 2 results for ${config.kind}${config.name?.let { " ($it)" } ?: ""}:"

        for (endpoint in config.endpoints) {
            lines += ""
            lines += "Endpoint: ${endpoint.protocol} ${endpoint.host}:${endpoint.port}"
            val discovery = endpointDiscovery.discover(endpoint)
            if (discovery.fromCache) lines += "DNS: cache hit"
            if (discovery.resolved.isEmpty()) {
                lines += "No public IPv4 candidate found."
                discovery.errors.take(MAX_ERRORS_PER_ENDPOINT).forEach { lines += "DNS note: $it" }
                continue
            }

            for (resolved in discovery.resolved.take(MAX_IPS_PER_ENDPOINT)) {
                lines += resolved.describe()
                val health = endpointHealthChecker.checkBestEffort(resolved)
                val adjustedScore = routeHealthCache.scoreFor(resolved, networkKey, health.latencyMs) ?: health.score
                routeHealthCache.record(endpoint, networkKey, health)
                lines += health.describe(adjustedScore)
            }

            discovery.errors.take(MAX_ERRORS_PER_ENDPOINT).forEach { lines += "DNS note: $it" }
        }

        lines += ""
        lines += "Note: UDP/WireGuard candidates need protocol-level handshake probes in the tunnel engine phase. TLS probe code is available and keeps hostname verification on; it is not used to fake success."
        return lines.joinToString("\n")
    }

    private fun ResolvedEndpointCandidate.describe(): String {
        val providerName = provider?.displayName ?: "literal/imported IP"
        return "IP: $ip via $providerName, TTL ${ttlSeconds}s"
    }

    private fun HealthResult.describe(scoreValue: Int?): String {
        return when {
            probeKind == ProbeKind.UNSUPPORTED -> "Probe: skipped — ${reason.orEmpty()}"
            reachable -> "Probe: $probeKind OK, latency ${latencyMs ?: 0}ms, score ${scoreValue ?: "n/a"}"
            else -> "Probe: $probeKind failed — ${reason.orEmpty()}"
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val VPN_PERMISSION_REQUEST = 1001
        const val IMPORT_CONFIG_REQUEST = 1002
        const val MAX_IPS_PER_ENDPOINT = 4
        const val MAX_ERRORS_PER_ENDPOINT = 3
    }
}
