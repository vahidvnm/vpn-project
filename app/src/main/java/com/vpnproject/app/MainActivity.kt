package com.vpnproject.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vpnproject.app.core.ConfigImporter
import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.ConfigParseException
import com.vpnproject.app.core.EndpointCandidate
import com.vpnproject.app.core.EndpointDiscovery
import com.vpnproject.app.core.EndpointHealthChecker
import com.vpnproject.app.core.HealthResult
import com.vpnproject.app.core.ImportedConfig
import com.vpnproject.app.core.IpClassifier
import com.vpnproject.app.core.NetworkKey
import com.vpnproject.app.core.NetworkType
import com.vpnproject.app.core.ProbeKind
import com.vpnproject.app.core.ResolvedEndpointCandidate
import com.vpnproject.app.core.RouteHealthCache
import com.vpnproject.app.core.VpnProtocol
import com.vpnproject.app.engine.RuntimeConfigPreparer
import com.vpnproject.app.engine.RuntimeConfigSelection
import com.vpnproject.app.vpn.AutoVpnService
import com.vpnproject.app.vpn.WireGuardVpnService

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var importedConfig: ImportedConfig? = null
    private var pendingVpnAction = PendingVpnAction.NONE
    private var pendingOpenVpnConfigText: String? = null
    private var pendingOpenVpnConfigName: String = "vpn-project-pinned.ovpn"
    private val endpointDiscovery by lazy { EndpointDiscovery() }
    private val endpointHealthChecker by lazy { EndpointHealthChecker() }
    private val runtimeConfigPreparer by lazy { RuntimeConfigPreparer(endpointDiscovery) }
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
            text = "Import a user-owned OpenVPN, WireGuard, or V2Ray/Xray config; probe endpoints; start WireGuard where UDP works; or prepare OpenVPN TCP fallback configs."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(12), 0, dp(24))
        })

        status = TextView(this).apply {
            text = "Phase 4: WireGuard is only useful where UDP works. For Iran, OpenVPN TCP/443 and V2Ray/Xray-style fallbacks are the next test paths."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Prepare VPN permission"
            setOnClickListener { requestVpnPermission(PendingVpnAction.NONE) }
        })

        root.addView(Button(this).apply {
            text = "Import OpenVPN / WireGuard / V2Ray config"
            setOnClickListener { openConfigPicker() }
        })

        root.addView(Button(this).apply {
            text = "Resolve & probe imported endpoints"
            setOnClickListener { resolveAndProbeImportedConfig() }
        })

        root.addView(Button(this).apply {
            text = "Save pinned OpenVPN TCP config"
            setOnClickListener { prepareAndSaveOpenVpnConfig() }
        })

        root.addView(Button(this).apply {
            text = "Start WireGuard engine from imported config"
            setOnClickListener { requestVpnPermission(PendingVpnAction.WIREGUARD) }
        })

        root.addView(Button(this).apply {
            text = "Stop WireGuard engine"
            setOnClickListener { stopWireGuardEngine() }
        })

        root.addView(Button(this).apply {
            text = "Refresh WireGuard status"
            setOnClickListener { showWireGuardStatus() }
        })

        root.addView(Button(this).apply {
            text = "Start TUN bootstrap VPN"
            setOnClickListener { requestVpnPermission(PendingVpnAction.BOOTSTRAP) }
        })

        root.addView(Button(this).apply {
            text = "Stop bootstrap VPN"
            setOnClickListener { stopBootstrapVpn() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Android framework, acceptable for this no-AndroidX skeleton.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VPN_PERMISSION_REQUEST -> {
                val action = pendingVpnAction
                pendingVpnAction = PendingVpnAction.NONE
                if (resultCode == RESULT_OK) {
                    runVpnAction(action)
                } else {
                    status.text = "VPN permission was not granted."
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
            EXPORT_OPENVPN_REQUEST -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    writePendingOpenVpnConfig(uri)
                } else {
                    status.text = "OpenVPN export was cancelled."
                }
            }
        }
    }

    private fun requestVpnPermission(action: PendingVpnAction) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingVpnAction = action
            startActivityForResult(intent, VPN_PERMISSION_REQUEST)
        } else {
            runVpnAction(action)
        }
    }

    private fun runVpnAction(action: PendingVpnAction) {
        when (action) {
            PendingVpnAction.NONE -> status.text = "VPN permission is granted."
            PendingVpnAction.BOOTSTRAP -> startBootstrapVpnService()
            PendingVpnAction.WIREGUARD -> prepareAndStartWireGuardEngine()
        }
    }

    private fun startBootstrapVpnService() {
        val intent = Intent(this, AutoVpnService::class.java).apply {
            action = AutoVpnService.ACTION_START
        }
        startForegroundServiceCompat(intent)
        status.text = "Starting TUN bootstrap VPN. Warning: it owns the full IPv4 route but does not forward traffic until an OpenVPN/WireGuard engine is integrated. Use Stop to return to normal networking."
    }

    private fun stopBootstrapVpn() {
        val intent = Intent(this, AutoVpnService::class.java).apply {
            action = AutoVpnService.ACTION_STOP
        }
        startService(intent)
        status.text = "Stop requested for bootstrap VPN."
    }

    private fun prepareAndSaveOpenVpnConfig() {
        val config = importedConfig
        if (config == null) {
            status.text = "Import an OpenVPN config first."
            return
        }
        if (config.kind != ConfigKind.OPENVPN) {
            status.text = "This action is for OpenVPN configs. Imported config is ${config.kind}."
            return
        }

        status.text = "Preparing pinned OpenVPN config. For Iran, official OpenVPN TCP/443 profiles are usually more useful than WireGuard UDP."
        Thread {
            val result = runCatching { runtimeConfigPreparer.prepareOpenVpn(config) }
            runOnUiThread {
                result.fold(
                    onSuccess = { selection -> promptSaveOpenVpnConfig(selection, config) },
                    onFailure = { error ->
                        status.text = "OpenVPN pinned config failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                )
            }
        }.start()
    }

    private fun promptSaveOpenVpnConfig(selection: RuntimeConfigSelection, config: ImportedConfig) {
        pendingOpenVpnConfigText = selection.configText
        pendingOpenVpnConfigName = safeOpenVpnExportName(config.name)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-openvpn-profile"
            putExtra(Intent.EXTRA_TITLE, pendingOpenVpnConfigName)
        }
        status.text = "Prepared OpenVPN handoff config.\n${selection.note}\n\nSave it, then open/import it in an official OpenVPN-compatible Android client while internal OpenVPN engine licensing is pending."
        startActivityForResult(intent, EXPORT_OPENVPN_REQUEST)
    }

    private fun writePendingOpenVpnConfig(uri: Uri) {
        val text = pendingOpenVpnConfigText
        if (text == null) {
            status.text = "No prepared OpenVPN config is waiting to be saved."
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(text)
            } ?: throw ConfigParseException("Could not open selected output file.")
            status.text = "Pinned OpenVPN config saved as $pendingOpenVpnConfigName. Import it in an OpenVPN client and prefer TCP/443 profiles when available."
        } catch (e: Exception) {
            status.text = "OpenVPN export failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            pendingOpenVpnConfigText = null
        }
    }

    private fun prepareAndStartWireGuardEngine() {
        val config = importedConfig
        if (config == null) {
            status.text = "Import a WireGuard config first."
            return
        }
        if (config.kind != ConfigKind.WIREGUARD) {
            status.text = "The only embedded real engine right now is WireGuard. This imported config is ${config.kind}; OpenVPN/Xray engines are pending, but endpoint probing is available."
            return
        }

        status.text = "Preparing WireGuard runtime config: resolving endpoint and pinning a public IPv4 candidate..."
        Thread {
            val result = runCatching { runtimeConfigPreparer.prepareWireGuard(config) }
            runOnUiThread {
                result.fold(
                    onSuccess = { selection -> startWireGuardEngine(selection, config) },
                    onFailure = { error ->
                        status.text = "WireGuard runtime config failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                )
            }
        }.start()
    }

    private fun startWireGuardEngine(selection: RuntimeConfigSelection, config: ImportedConfig) {
        val intent = Intent(this, WireGuardVpnService::class.java).apply {
            action = WireGuardVpnService.ACTION_START
            putExtra(WireGuardVpnService.EXTRA_CONFIG_TEXT, selection.configText)
            putExtra(WireGuardVpnService.EXTRA_TUNNEL_NAME, safeTunnelName(config.name))
            putExtra(WireGuardVpnService.EXTRA_NOTE, selection.note)
        }
        startForegroundServiceCompat(intent)
        status.text = "Starting WireGuard engine.\n${selection.note}\n\nVerification starts in the background now: RX/TX traffic plus public egress IP. Tap Refresh WireGuard status after a few seconds."
    }

    private fun stopWireGuardEngine() {
        val intent = Intent(this, WireGuardVpnService::class.java).apply {
            action = WireGuardVpnService.ACTION_STOP
        }
        startService(intent)
        val last = WireGuardVpnService.lastStatus
        status.text = "Stop requested for WireGuard engine. Last status: ${last.state} — ${last.message}"
    }

    private fun showWireGuardStatus() {
        val last = WireGuardVpnService.lastStatus
        status.text = "WireGuard status: ${last.state}\n" +
            "Verified: ${if (last.verified) "yes" else "no"}\n" +
            "Message: ${last.message}" +
            (last.detail?.let { "\nDetail: $it" } ?: "") +
            (last.egressIp?.let { "\nEgress IP: $it" } ?: "") +
            "\nRX/TX: ${last.rxBytes ?: 0} / ${last.txBytes ?: 0} bytes"
    }

    private fun openConfigPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/x-openvpn-profile", "text/plain", "text/*")
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
            val nextStep = when (config.kind) {
                ConfigKind.WIREGUARD ->
                    "\n\nNext: tap Start WireGuard engine from imported config. If UDP is blocked, try an official OpenVPN TCP/443 or V2Ray/Xray config instead."
                ConfigKind.OPENVPN ->
                    "\n\nNext: tap Save pinned OpenVPN TCP config, then import it in an OpenVPN-compatible client while the internal OpenVPN engine is pending."
                ConfigKind.V2RAY ->
                    "\n\nNext: tap Resolve & probe imported endpoints. Internal Xray/V2Ray engine integration is pending, so keep using your trusted external client for real connections."
                else -> ""
            }
            status.text = "Imported ${config.kind} config${name?.let { " ($it)" } ?: ""}.\n" +
                "Endpoints found: ${config.endpoints.size}\n$endpointLines" +
                "\n\nOpenVPN auth-user-pass line: ${if (config.hasAuthUserPass) "yes" else "not detected"}" +
                nextStep +
                warnings
        } catch (e: Exception) {
            importedConfig = null
            status.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun resolveAndProbeImportedConfig() {
        val config = importedConfig
        if (config == null) {
            status.text = "Import an OpenVPN, WireGuard, or V2Ray/Xray config first."
            return
        }

        status.text = "Resolving endpoints with DNS-over-HTTPS and probing candidates. If DoH is blocked, V2Ray/TCP endpoints can also be direct-probed without pinning."
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
        lines += "Phase 2/4 results for ${config.kind}${config.name?.let { " ($it)" } ?: ""}:"

        for (endpoint in config.endpoints) {
            lines += ""
            lines += "Endpoint: ${endpoint.protocol} ${endpoint.host}:${endpoint.port}"
            val discovery = endpointDiscovery.discover(endpoint)
            if (discovery.fromCache) lines += "DNS: cache hit"
            val candidates = if (discovery.resolved.isEmpty()) {
                val direct = directSystemProbeCandidate(endpoint)
                if (direct != null) {
                    lines += "No DoH public IPv4 candidate found; trying a direct diagnostic probe through Android/system DNS without IP pinning."
                    listOf(direct)
                } else {
                    lines += if (discovery.errors.any { it.contains("public IPv6 literal") }) {
                        "Public IPv6 literal endpoint; IPv4 pinning is not needed. The WireGuard engine can try the original endpoint."
                    } else {
                        "No public IPv4 candidate found."
                    }
                    discovery.errors.take(MAX_ERRORS_PER_ENDPOINT).forEach { lines += "DNS note: $it" }
                    continue
                }
            } else {
                discovery.resolved
            }

            for (resolved in candidates.take(MAX_IPS_PER_ENDPOINT)) {
                lines += resolved.describe()
                val health = endpointHealthChecker.checkBestEffort(resolved)
                val adjustedScore = routeHealthCache.scoreFor(resolved, networkKey, health.latencyMs) ?: health.score
                routeHealthCache.record(endpoint, networkKey, health)
                lines += health.describe(adjustedScore)
            }

            discovery.errors.take(MAX_ERRORS_PER_ENDPOINT).forEach { lines += "DNS note: $it" }
        }

        lines += ""
        lines += "Note: UDP/WireGuard candidates are finally validated by the WireGuard engine handshake. TCP/TLS probes do not fake VPN success. V2Ray/Xray probes only check the front endpoint, not credentials or full proxy login yet."
        return lines.joinToString("\n")
    }

    private fun directSystemProbeCandidate(endpoint: EndpointCandidate): ResolvedEndpointCandidate? {
        if (IpClassifier.isIpv4Literal(endpoint.host) || IpClassifier.isIpv6Literal(endpoint.host)) return null
        val canProbeDirectly = when (endpoint.protocol) {
            VpnProtocol.OPENVPN_TCP,
            VpnProtocol.V2RAY_TLS,
            VpnProtocol.V2RAY_TCP,
            VpnProtocol.V2RAY_REALITY,
            VpnProtocol.V2RAY_UNKNOWN,
            VpnProtocol.UNKNOWN -> true
            VpnProtocol.OPENVPN_UDP,
            VpnProtocol.WIREGUARD -> false
        }
        if (!canProbeDirectly) return null
        return ResolvedEndpointCandidate(
            endpoint = endpoint,
            ip = endpoint.host,
            provider = null,
            ttlSeconds = 0L,
            expiresAtEpochMs = 0L
        )
    }

    private fun ResolvedEndpointCandidate.describe(): String {
        val providerName = provider?.displayName ?: if (IpClassifier.isIpv4Literal(ip) || IpClassifier.isIpv6Literal(ip)) {
            "literal/imported IP"
        } else {
            "direct system DNS (not pinned)"
        }
        val ttlText = if (ttlSeconds > 0) ", TTL ${ttlSeconds}s" else ""
        return "Target: $ip via $providerName$ttlText"
    }

    private fun HealthResult.describe(scoreValue: Int?): String {
        return when {
            probeKind == ProbeKind.UNSUPPORTED -> "Probe: skipped — ${reason.orEmpty()}"
            reachable -> "Probe: $probeKind OK, latency ${latencyMs ?: 0}ms, score ${scoreValue ?: "n/a"}"
            else -> "Probe: $probeKind failed — ${reason.orEmpty()}"
        }
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun safeTunnelName(name: String?): String = name
        ?.substringBeforeLast('.')
        ?.replace(Regex("[^A-Za-z0-9_=+.-]"), "-")
        ?.take(30)
        ?.takeIf { it.isNotBlank() }
        ?: "vpn-project-wg"

    private fun safeOpenVpnExportName(name: String?): String {
        val base = name
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9_=+.-]"), "-")
            ?.take(40)
            ?.takeIf { it.isNotBlank() }
            ?: "vpn-project-openvpn"
        return "$base-pinned.ovpn"
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

    private enum class PendingVpnAction {
        NONE,
        BOOTSTRAP,
        WIREGUARD
    }

    private companion object {
        const val VPN_PERMISSION_REQUEST = 1001
        const val IMPORT_CONFIG_REQUEST = 1002
        const val EXPORT_OPENVPN_REQUEST = 1003
        const val MAX_IPS_PER_ENDPOINT = 4
        const val MAX_ERRORS_PER_ENDPOINT = 3
    }
}
