package com.vpnproject.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.vpnproject.app.engine.EngineRegistry
import com.vpnproject.app.engine.RuntimeConfigPreparer
import com.vpnproject.app.engine.RuntimeConfigSelection
import com.vpnproject.app.engine.V2RayRuntimeConfigBuilder
import com.vpnproject.app.engine.VpnHubStatusMapper
import com.vpnproject.app.engine.V2RayRuntimeConfig
import com.vpnproject.app.profile.SecureProfileStore
import com.vpnproject.app.profile.VpnProfile
import com.vpnproject.app.vpn.AutoVpnService
import com.vpnproject.app.vpn.WireGuardVpnService
import com.vpnproject.app.vpn.XrayVpnService

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var importedConfig: ImportedConfig? = null
    private var selectedProfileId: String? = null
    private var selectedProfile: VpnProfile? = null
    private lateinit var profileListContainer: LinearLayout
    private var pendingVpnAction = PendingVpnAction.NONE
    private var pendingOpenVpnConfigText: String? = null
    private var pendingOpenVpnConfigName: String = "vpn-project-pinned.ovpn"
    private val endpointDiscovery by lazy { EndpointDiscovery() }
    private val endpointHealthChecker by lazy { EndpointHealthChecker() }
    private val runtimeConfigPreparer by lazy { RuntimeConfigPreparer(endpointDiscovery) }
    private val profileStore by lazy { SecureProfileStore(this) }
    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
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
            text = "Multi-engine VPN hub: import your own OpenVPN, WireGuard, or V2Ray/Xray config; save local profiles; connect with the best available engine."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(12), 0, dp(24))
        })

        status = TextView(this).apply {
            text = "Phase 4.5: Xray/V2Ray is verified on phone. Next step is turning this debug screen into a multi-engine VPN hub with local profiles."
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
            text = "Import OpenVPN / WireGuard / V2Ray file"
            setOnClickListener { openConfigPicker() }
        })

        root.addView(Button(this).apply {
            text = "Import config from clipboard"
            setOnClickListener { importConfigFromClipboard() }
        })

        root.addView(Button(this).apply {
            text = "Show saved profiles"
            setOnClickListener { showSavedProfiles() }
        })

        root.addView(Button(this).apply {
            text = "Load latest profile"
            setOnClickListener { loadLatestProfile() }
        })

        profileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(profileListContainer)
        refreshProfileButtons()

        root.addView(Button(this).apply {
            text = "Resolve & probe selected/imported endpoints"
            setOnClickListener { resolveAndProbeImportedConfig() }
        })

        root.addView(Button(this).apply {
            text = "Save pinned OpenVPN TCP config"
            setOnClickListener { prepareAndSaveOpenVpnConfig() }
        })

        root.addView(Button(this).apply {
            text = "Connect selected/imported profile"
            setOnClickListener { requestVpnPermission(PendingVpnAction.IMPORTED_ENGINE) }
        })

        root.addView(Button(this).apply {
            text = "Disconnect active engines"
            setOnClickListener { stopImportedEngines() }
        })

        root.addView(Button(this).apply {
            text = "Refresh connection status"
            setOnClickListener { showEngineStatus() }
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
            PendingVpnAction.IMPORTED_ENGINE -> prepareAndStartImportedEngine()
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

    private fun prepareAndStartImportedEngine() {
        val config = importedConfig ?: loadLatestProfileConfigForAction()
        if (config == null) {
            status.text = "Import or load a saved WireGuard or V2Ray/Xray profile first."
            return
        }
        when (config.kind) {
            ConfigKind.WIREGUARD -> prepareAndStartWireGuardEngine(config)
            ConfigKind.V2RAY -> prepareAndStartXrayEngine(config)
            ConfigKind.OPENVPN -> status.text = "OpenVPN is not embedded yet. Use Save pinned OpenVPN TCP config and import it in an OpenVPN client for now."
            ConfigKind.UNKNOWN -> status.text = "Unknown config kind cannot be started."
        }
    }

    private fun prepareAndStartWireGuardEngine(config: ImportedConfig) {
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
        startService(Intent(this, XrayVpnService::class.java).apply { action = XrayVpnService.ACTION_STOP })
        val intent = Intent(this, WireGuardVpnService::class.java).apply {
            action = WireGuardVpnService.ACTION_START
            putExtra(WireGuardVpnService.EXTRA_CONFIG_TEXT, selection.configText)
            putExtra(WireGuardVpnService.EXTRA_TUNNEL_NAME, safeTunnelName(config.name))
            putExtra(WireGuardVpnService.EXTRA_NOTE, selection.note)
        }
        startForegroundServiceCompat(intent)
        status.text = "Starting WireGuard engine.\n${selection.note}\n\nVerification starts in the background now: RX/TX traffic plus public egress IP. Engine status will refresh automatically."
        scheduleEngineStatusRefreshes()
    }

    private fun prepareAndStartXrayEngine(config: ImportedConfig) {
        status.text = "Preparing embedded Xray/V2Ray runtime config from the imported link..."
        Thread {
            val result = runCatching { V2RayRuntimeConfigBuilder.build(config) }
            runOnUiThread {
                result.fold(
                    onSuccess = { runtime -> startXrayEngine(runtime) },
                    onFailure = { error ->
                        status.text = "Xray runtime config failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                )
            }
        }.start()
    }

    private fun startXrayEngine(runtime: V2RayRuntimeConfig) {
        startService(Intent(this, WireGuardVpnService::class.java).apply { action = WireGuardVpnService.ACTION_STOP })
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG_JSON, runtime.configJson)
            putExtra(XrayVpnService.EXTRA_PROFILE_NAME, safeTunnelName(runtime.profileName))
            putExtra(XrayVpnService.EXTRA_NOTE, runtime.note)
        }
        startForegroundServiceCompat(intent)
        status.text = "Starting embedded Xray engine.\n${runtime.note}\n\nThe app will auto-refresh status shortly. If no VPN icon appears, the refreshed status should now show the exact Android/Xray failure instead of staying on this starting screen."
        scheduleEngineStatusRefreshes()
    }

    private fun stopImportedEngines() {
        startService(Intent(this, WireGuardVpnService::class.java).apply { action = WireGuardVpnService.ACTION_STOP })
        startService(Intent(this, XrayVpnService::class.java).apply { action = XrayVpnService.ACTION_STOP })
        status.text = "Stop requested for WireGuard and Xray engines."
    }

    private fun scheduleEngineStatusRefreshes() {
        listOf(1_500L, 3_500L, 7_000L, 12_000L, 22_000L, 40_000L, 65_000L, 90_000L).forEach { delayMs ->
            mainHandler.postDelayed({ showEngineStatus() }, delayMs)
        }
    }

    private fun showEngineStatus() {
        val wg = WireGuardVpnService.lastStatus
        val xray = XrayVpnService.lastStatus
        val hub = VpnHubStatusMapper.from(wg, xray, selectedProfile)
        status.text = "Hub status: ${hub.title}\n" +
            "Verified: ${if (hub.verified) "yes" else "no"}\n" +
            "Message: ${hub.detail}" +
            (hub.egressIp?.let { "\nEgress IP: $it" } ?: "") +
            (if (hub.rxBytes != null || hub.txBytes != null) "\nRX/TX: ${hub.rxBytes ?: 0} / ${hub.txBytes ?: 0} bytes" else "") +
            "\n\nAdvanced engine diagnostics:\n" +
            "WireGuard status: ${wg.state}\n" +
            "Verified: ${if (wg.verified) "yes" else "no"}\n" +
            "Message: ${wg.message}" +
            (wg.detail?.let { "\nDetail: $it" } ?: "") +
            (wg.egressIp?.let { "\nEgress IP: $it" } ?: "") +
            "\nRX/TX: ${wg.rxBytes ?: 0} / ${wg.txBytes ?: 0} bytes" +
            "\n\nXray status: ${xray.state}\n" +
            "Verified: ${if (xray.verified) "yes" else "no"}\n" +
            "Message: ${xray.message}" +
            (xray.detail?.let { "\nDetail: $it" } ?: "")
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
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw ConfigParseException("Could not read selected file.")
        } catch (e: Exception) {
            importedConfig = null
            status.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        importConfigText(text, displayName(uri))
    }

    private fun importConfigFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (clipText.isBlank()) {
            status.text = "Clipboard is empty. Copy a vless://, vmess://, trojan://, ss://, OpenVPN, or WireGuard config first."
            return
        }

        importConfigText(clipText, "clipboard")
    }

    private fun importConfigText(text: String, name: String?) {
        try {
            val config = ConfigImporter.parse(text, name)
            importedConfig = config
            val savedProfileLine = saveImportedProfile(config, name)
            val endpointLines = config.endpoints.joinToString("\n") { endpoint ->
                "• ${endpoint.protocol}  ${endpoint.host}:${endpoint.port}" +
                    (endpoint.verifyHost?.let { "  verify: $it" } ?: "")
            }
            val warnings = if (config.warnings.isEmpty()) "" else
                "\n\nWarnings:\n" + config.warnings.joinToString("\n") { "• $it" }
            val nextStep = when (config.kind) {
                ConfigKind.WIREGUARD ->
                    "\n\nNext: tap Start imported VPN engine. If UDP is blocked, try an official OpenVPN TCP/443 or V2Ray/Xray config instead."
                ConfigKind.OPENVPN ->
                    "\n\nNext: tap Save pinned OpenVPN TCP config, then import it in an OpenVPN-compatible client while the internal OpenVPN engine is pending."
                ConfigKind.V2RAY ->
                    "\n\nNext: tap Resolve & probe imported endpoints, then Start imported VPN engine to try the embedded Xray core."
                else -> ""
            }
            status.text = "Imported ${config.kind} config${name?.let { " ($it)" } ?: ""}.\n" +
                "Endpoints found: ${config.endpoints.size}\n$endpointLines" +
                savedProfileLine +
                "\n\nOpenVPN auth-user-pass line: ${if (config.hasAuthUserPass) "yes" else "not detected"}" +
                nextStep +
                warnings
        } catch (e: Exception) {
            importedConfig = null
            status.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }


    private fun saveImportedProfile(config: ImportedConfig, displayName: String?): String {
        return runCatching { profileStore.saveImportedConfig(config, displayName) }.fold(
            onSuccess = { profile ->
                selectedProfileId = profile.id
                selectedProfile = profile
                refreshProfileButtons()
                val engine = EngineRegistry.engineFor(profile.kind)
                "\n\nSaved local profile: ${profile.displayName}\nEngine: ${engine.displayName}${if (engine.startableInApp) "" else " (handoff)"}"
            },
            onFailure = { error ->
                "\n\nProfile store warning: config imported for this session, but saving failed (${error.message ?: error.javaClass.simpleName})."
            }
        )
    }

    private fun showSavedProfiles() {
        refreshProfileButtons()
        val profiles = runCatching { profileStore.listProfiles() }.getOrElse { error ->
            status.text = "Could not load saved profiles: ${error.message ?: error.javaClass.simpleName}"
            return
        }
        if (profiles.isEmpty()) {
            status.text = "No saved profiles yet. Import or paste a config first."
            return
        }
        status.text = "Saved VPN Hub profiles:\n" + profiles.joinToString("\n") { profile ->
            val marker = if (profile.id == selectedProfileId) "*" else "•"
            "$marker ${profile.summary()} — engine ${EngineRegistry.engineFor(profile.kind).displayName}"
        } + "\n\nTap a profile button, then Resolve/Connect. Profile secrets are stored encrypted with Android Keystore."
    }

    private fun refreshProfileButtons() {
        if (!::profileListContainer.isInitialized) return
        profileListContainer.removeAllViews()
        val profiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        profileListContainer.addView(TextView(this).apply {
            text = if (profiles.isEmpty()) "No saved profiles yet." else "Saved profiles (${profiles.size}): tap to select"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(4), 0, dp(4))
        })
        profiles.take(MAX_PROFILE_BUTTONS).forEach { profile ->
            val engine = EngineRegistry.engineFor(profile.kind)
            profileListContainer.addView(Button(this).apply {
                val marker = if (profile.id == selectedProfileId) "✓ " else ""
                text = "$marker${profile.displayName} — ${profile.kind.displayName} / ${engine.displayName}"
                setOnClickListener { loadProfile(profile) }
            })
        }
    }

    private fun loadLatestProfile() {
        val profile = runCatching { profileStore.latestProfile() }.getOrElse { error ->
            status.text = "Could not load latest profile metadata: ${error.message ?: error.javaClass.simpleName}"
            return
        }
        if (profile == null) {
            status.text = "No saved profile found. Import or paste a config first."
            return
        }
        loadProfile(profile)
    }

    private fun loadProfile(profile: VpnProfile) {
        val config = loadProfileConfig(profile) ?: return
        importedConfig = config
        selectedProfileId = profile.id
        selectedProfile = profile
        refreshProfileButtons()
        status.text = "Selected profile: ${profile.displayName}\n" +
            "Kind: ${profile.kind.displayName}\n" +
            "Engine: ${EngineRegistry.engineFor(profile.kind).displayName}\n" +
            "Endpoints: ${config.endpoints.size}\n" +
            profile.endpoints.joinToString("\n") { "• ${it.summary()}" } +
            "\n\nNext: Resolve & probe, or Connect selected/imported profile."
    }

    private fun loadLatestProfileConfigForAction(): ImportedConfig? {
        val profile = runCatching { profileStore.latestProfile() }.getOrNull() ?: return null
        val config = loadProfileConfig(profile) ?: return null
        importedConfig = config
        selectedProfileId = profile.id
        selectedProfile = profile
        refreshProfileButtons()
        return config
    }

    private fun loadProfileConfig(profile: VpnProfile): ImportedConfig? {
        val raw = runCatching { profileStore.loadRawConfig(profile.id) }.getOrElse { error ->
            status.text = "Could not decrypt profile ${profile.displayName}: ${error.message ?: error.javaClass.simpleName}"
            return null
        }
        if (raw.isNullOrBlank()) {
            status.text = "Saved profile ${profile.displayName} has no decryptable config. Import it again."
            return null
        }
        return try {
            ConfigImporter.parse(raw, profile.name)
        } catch (e: Exception) {
            status.text = "Saved profile ${profile.displayName} could not be parsed: ${e.message ?: e.javaClass.simpleName}"
            null
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
        lines += currentNetworkDiagnosticNote()

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

    private fun currentNetworkDiagnosticNote(): String {
        return try {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (capabilities == null) {
                "Network: unknown. If another VPN app is active, probes may not represent the raw Iran mobile path."
            } else {
                val transports = mutableListOf<String>()
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "cellular"
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "wifi"
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "vpn"
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "ethernet"
                val validated = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not-validated"
                val base = "Network: ${transports.ifEmpty { listOf("unknown") }.joinToString("+")} / $validated."
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    "$base Warning: Android reports an active VPN transport (possibly this app if Xray/WireGuard is already running). Diagnostic probes may be routed through the active tunnel instead of the raw mobile network. Stop VPNs first when testing censorship reachability."
                } else {
                    base
                }
            }
        } catch (e: Exception) {
            "Network: unavailable (${e.message ?: e.javaClass.simpleName})."
        }
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
        IMPORTED_ENGINE
    }

    private companion object {
        const val VPN_PERMISSION_REQUEST = 1001
        const val IMPORT_CONFIG_REQUEST = 1002
        const val EXPORT_OPENVPN_REQUEST = 1003
        const val MAX_IPS_PER_ENDPOINT = 4
        const val MAX_ERRORS_PER_ENDPOINT = 3
        const val MAX_PROFILE_BUTTONS = 5
    }
}
