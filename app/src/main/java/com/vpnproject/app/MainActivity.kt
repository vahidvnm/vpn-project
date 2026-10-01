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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
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
import com.vpnproject.app.engine.EngineStatus
import com.vpnproject.app.engine.RuntimeConfigPreparer
import com.vpnproject.app.engine.RuntimeConfigSelection
import com.vpnproject.app.engine.V2RayRuntimeConfigBuilder
import com.vpnproject.app.engine.VpnHubConnectionState
import com.vpnproject.app.engine.VpnHubStatusMapper
import com.vpnproject.app.engine.V2RayRuntimeConfig
import com.vpnproject.app.profile.SecureProfileStore
import com.vpnproject.app.profile.VpnProfile
import com.vpnproject.app.vpn.AutoVpnService
import com.vpnproject.app.vpn.WireGuardVpnService
import com.vpnproject.app.vpn.XrayVpnService

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var hubStatusTitle: TextView
    private lateinit var hubStatusDetail: TextView
    private lateinit var connectionStatsText: TextView
    private lateinit var selectedProfileText: TextView
    private lateinit var primaryActionButton: Button
    private lateinit var navHomeButton: Button
    private lateinit var navProfilesButton: Button
    private lateinit var navToolsButton: Button
    private lateinit var homeSection: LinearLayout
    private lateinit var profilesSection: LinearLayout
    private lateinit var toolsSection: LinearLayout
    private lateinit var advancedToggleButton: Button
    private lateinit var advancedPanel: LinearLayout
    private lateinit var advancedDiagnostics: TextView
    private var advancedVisible = false
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
            setPadding(dp(20), dp(28), dp(20), dp(24))
            setBackgroundColor(0xFFF8FAFC.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(this).apply {
            text = "VPN Hub"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
        })

        root.addView(TextView(this).apply {
            text = "خانه ساده، پروفایل‌ها جدا، ابزارهای فنی جدا."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(10), 0, dp(14))
        })

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        navHomeButton = createNavButton("خانه") { showSection(AppSection.HOME) }
        navProfilesButton = createNavButton("پروفایل‌ها") { showSection(AppSection.PROFILES) }
        navToolsButton = createNavButton("ابزار") { showSection(AppSection.TOOLS) }
        navRow.addView(navHomeButton)
        navRow.addView(navProfilesButton)
        navRow.addView(navToolsButton)
        root.addView(navRow)

        homeSection = createSectionContainer()
        profilesSection = createSectionContainer()
        toolsSection = createSectionContainer()
        root.addView(homeSection)
        root.addView(profilesSection)
        root.addView(toolsSection)

        val statusCard = createCard()
        statusCard.addView(sectionLabel("Connection"))
        hubStatusTitle = TextView(this).apply {
            text = "Disconnected"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
        }
        statusCard.addView(hubStatusTitle)
        hubStatusDetail = TextView(this).apply {
            text = "Import or select a profile, then connect."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF334155.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        statusCard.addView(hubStatusDetail)
        connectionStatsText = TextView(this).apply {
            text = "Verified: no • Traffic: 0 B down / 0 B up"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 0, 0, dp(14))
        }
        statusCard.addView(connectionStatsText)
        primaryActionButton = createActionButton("Connect", primary = true) { handlePrimaryAction() }
        statusCard.addView(primaryActionButton)
        statusCard.addView(createActionButton("Refresh status") { showEngineStatus() })
        status = TextView(this).apply {
            text = "Ready. Paste/import a config, then tap Connect."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        statusCard.addView(status)
        homeSection.addView(statusCard)

        val quickCard = createCard()
        quickCard.addView(sectionLabel("Quick actions"))
        quickCard.addView(createActionButton("Paste / import config") { showSection(AppSection.PROFILES) })
        quickCard.addView(createActionButton("Diagnostics & tools") { showSection(AppSection.TOOLS) })
        homeSection.addView(quickCard)

        val profileCard = createCard()
        profileCard.addView(sectionLabel("Profiles"))
        selectedProfileText = TextView(this).apply {
            text = "No profile selected yet."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, 0, 0, dp(10))
        }
        profileCard.addView(selectedProfileText)
        profileCard.addView(createActionButton("Import config from clipboard") { importConfigFromClipboard() })
        profileCard.addView(createActionButton("Import OpenVPN / WireGuard / V2Ray file") { openConfigPicker() })
        profileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        profileCard.addView(profileListContainer)
        profilesSection.addView(profileCard)

        val advancedCard = createCard()
        advancedCard.addView(sectionLabel("Diagnostics & tools"))
        advancedToggleButton = createActionButton("Hide advanced diagnostics") { toggleAdvancedPanel() }
        advancedCard.addView(advancedToggleButton)
        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.VISIBLE
            setPadding(0, dp(10), 0, 0)
        }
        advancedVisible = true
        advancedDiagnostics = TextView(this).apply {
            text = "Advanced diagnostics will appear here after refresh/probe."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF334155.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        advancedPanel.addView(advancedDiagnostics)
        advancedPanel.addView(createActionButton("Refresh engine diagnostics") { showEngineStatus() })
        advancedPanel.addView(createActionButton("Prepare VPN permission") { requestVpnPermission(PendingVpnAction.NONE) })
        advancedPanel.addView(createActionButton("Show saved profiles") { showSavedProfiles() })
        advancedPanel.addView(createActionButton("Load latest profile") { loadLatestProfile() })
        advancedPanel.addView(createActionButton("Resolve & probe selected/imported endpoints") { resolveAndProbeImportedConfig() })
        advancedPanel.addView(createActionButton("Save pinned OpenVPN TCP config") { prepareAndSaveOpenVpnConfig() })
        advancedPanel.addView(createActionButton("Start TUN bootstrap VPN") { requestVpnPermission(PendingVpnAction.BOOTSTRAP) })
        advancedPanel.addView(createActionButton("Stop bootstrap VPN") { stopBootstrapVpn() })
        advancedCard.addView(advancedPanel)
        toolsSection.addView(advancedCard)

        setContentView(ScrollView(this).apply { addView(root) })

        restoreLatestProfileMetadata()
        refreshProfileButtons()
        updateDashboardSummary()
        showSection(AppSection.HOME)
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFE2E8F0.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(14))
        }
    }

    private fun createSectionContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createNavButton(textValue: String, onClick: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = 14f
        setAllCaps(false)
        setTextColor(0xFF0F172A.toInt())
        background = roundedBackground(0xFFE2E8F0.toInt(), 0xFFCBD5E1.toInt(), radiusDp = 14)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(textValue: String): TextView = TextView(this).apply {
        text = textValue.uppercase()
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(0xFF64748B.toInt())
        setPadding(0, 0, 0, dp(8))
    }

    private fun createActionButton(
        textValue: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = textValue
        textSize = if (primary) 18f else 15f
        setAllCaps(false)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        background = roundedBackground(
            fillColor = if (primary) 0xFF2563EB.toInt() else 0xFFE2E8F0.toInt(),
            strokeColor = if (primary) 0xFF1D4ED8.toInt() else 0xFFCBD5E1.toInt(),
            radiusDp = 12
        )
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(5), 0, dp(5))
        }
        setOnClickListener { onClick() }
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int = 18
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }

    private fun showSection(section: AppSection) {
        if (!::homeSection.isInitialized) return
        homeSection.visibility = if (section == AppSection.HOME) View.VISIBLE else View.GONE
        profilesSection.visibility = if (section == AppSection.PROFILES) View.VISIBLE else View.GONE
        toolsSection.visibility = if (section == AppSection.TOOLS) View.VISIBLE else View.GONE
        styleNavButton(navHomeButton, section == AppSection.HOME)
        styleNavButton(navProfilesButton, section == AppSection.PROFILES)
        styleNavButton(navToolsButton, section == AppSection.TOOLS)
        when (section) {
            AppSection.HOME -> updateDashboardSummary()
            AppSection.PROFILES -> refreshProfileButtons()
            AppSection.TOOLS -> showEngineStatus()
        }
    }

    private fun styleNavButton(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        button.background = roundedBackground(
            fillColor = if (selected) 0xFF0F172A.toInt() else 0xFFE2E8F0.toInt(),
            strokeColor = if (selected) 0xFF0F172A.toInt() else 0xFFCBD5E1.toInt(),
            radiusDp = 14
        )
    }

    private fun toggleAdvancedPanel() {
        setAdvancedVisible(!advancedVisible)
    }

    private fun setAdvancedVisible(visible: Boolean) {
        if (!::advancedPanel.isInitialized) return
        advancedVisible = visible
        advancedPanel.visibility = if (visible) View.VISIBLE else View.GONE
        advancedToggleButton.text = if (visible) {
            "Hide advanced diagnostics & tools"
        } else {
            "Show advanced diagnostics & tools"
        }
    }

    private fun handlePrimaryAction() {
        val hub = currentHubStatus()
        when (hub.state) {
            VpnHubConnectionState.CONNECTED,
            VpnHubConnectionState.CONNECTING,
            VpnHubConnectionState.RUNNING_UNVERIFIED -> stopImportedEngines()
            VpnHubConnectionState.IDLE,
            VpnHubConnectionState.STOPPED,
            VpnHubConnectionState.FAILED -> {
                val hasProfile = importedConfig != null || selectedProfile != null || runCatching { profileStore.latestProfile() }.getOrNull() != null
                if (!hasProfile) {
                    status.text = "No profile selected. Import a config from clipboard/file first."
                    showSection(AppSection.PROFILES)
                } else {
                    requestVpnPermission(PendingVpnAction.IMPORTED_ENGINE)
                }
            }
        }
    }

    private fun currentHubStatus() = VpnHubStatusMapper.from(
        wireGuard = WireGuardVpnService.lastStatus,
        xray = XrayVpnService.lastStatus,
        selectedProfile = selectedProfile
    )

    private fun updateDashboardSummary() {
        if (!::hubStatusTitle.isInitialized) return
        val hub = currentHubStatus()
        val active = hub.state == VpnHubConnectionState.CONNECTED ||
            hub.state == VpnHubConnectionState.CONNECTING ||
            hub.state == VpnHubConnectionState.RUNNING_UNVERIFIED

        hubStatusTitle.text = hub.title
        hubStatusTitle.setTextColor(
            when (hub.state) {
                VpnHubConnectionState.CONNECTED -> 0xFF047857.toInt()
                VpnHubConnectionState.CONNECTING,
                VpnHubConnectionState.RUNNING_UNVERIFIED -> 0xFFB45309.toInt()
                VpnHubConnectionState.FAILED -> 0xFFB91C1C.toInt()
                VpnHubConnectionState.IDLE,
                VpnHubConnectionState.STOPPED -> 0xFF0F172A.toInt()
            }
        )
        hubStatusDetail.text = hub.detail
        connectionStatsText.text = buildStatsLine(hub.verified, hub.rxBytes, hub.txBytes, hub.egressIp, hub.activeEngine)
        primaryActionButton.text = if (active) "Disconnect" else "Connect"
        primaryActionButton.setTextColor(0xFFFFFFFF.toInt())
        primaryActionButton.background = roundedBackground(
            fillColor = if (active) 0xFFDC2626.toInt() else 0xFF2563EB.toInt(),
            strokeColor = if (active) 0xFFB91C1C.toInt() else 0xFF1D4ED8.toInt(),
            radiusDp = 12
        )
        updateSelectedProfileSummary()
    }

    private fun buildStatsLine(
        verified: Boolean,
        rxBytes: Long?,
        txBytes: Long?,
        egressIp: String?,
        engine: com.vpnproject.app.engine.EngineKind?
    ): String {
        val parts = mutableListOf(
            "Verified: ${if (verified) "yes" else "no"}",
            "Traffic: ${formatBytes(rxBytes ?: 0)} down / ${formatBytes(txBytes ?: 0)} up"
        )
        engine?.let { parts += "Engine: ${engineLabel(it)}" }
        egressIp?.let { parts += "IP: $it" }
        return parts.joinToString(" • ")
    }

    private fun engineLabel(kind: com.vpnproject.app.engine.EngineKind): String = when (kind) {
        com.vpnproject.app.engine.EngineKind.XRAY_CORE -> "Xray"
        com.vpnproject.app.engine.EngineKind.WIREGUARD_GO -> "WireGuard"
        com.vpnproject.app.engine.EngineKind.OPENVPN_UNAVAILABLE -> "OpenVPN handoff"
    }

    private fun updateSelectedProfileSummary() {
        if (!::selectedProfileText.isInitialized) return
        val profile = selectedProfile
        selectedProfileText.text = if (profile == null) {
            "No profile selected yet. Import a config or pick a saved profile."
        } else {
            val engine = EngineRegistry.engineFor(profile.kind)
            val endpointText = profile.endpoints.take(2).joinToString("\n") { "• ${it.summary().shortUi(82)}" }
            "${profile.displayName}\n${profile.kind.displayName} • ${engine.displayName}" +
                if (endpointText.isBlank()) "" else "\n$endpointText"
        }
    }

    private fun restoreLatestProfileMetadata() {
        val profile = runCatching { profileStore.latestProfile() }.getOrNull() ?: return
        selectedProfile = profile
        selectedProfileId = profile.id
        status.text = "Latest saved profile selected. Tap Connect to start, or choose another profile below."
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${String.format(java.util.Locale.US, "%.1f", kb)} KB"
        val mb = kb / 1024.0
        return "${String.format(java.util.Locale.US, "%.1f", mb)} MB"
    }

    private fun engineDiagnosticsText(wireGuard: EngineStatus, xray: EngineStatus): String {
        return "Advanced engine diagnostics:\n" +
            "WireGuard status: ${wireGuard.state}\n" +
            "Verified: ${if (wireGuard.verified) "yes" else "no"}\n" +
            "Message: ${wireGuard.message}" +
            (wireGuard.detail?.let { "\nDetail: $it" } ?: "") +
            (wireGuard.egressIp?.let { "\nEgress IP: $it" } ?: "") +
            "\nRX/TX: ${wireGuard.rxBytes ?: 0} / ${wireGuard.txBytes ?: 0} bytes" +
            "\n\nXray status: ${xray.state}\n" +
            "Verified: ${if (xray.verified) "yes" else "no"}\n" +
            "Message: ${xray.message}" +
            (xray.detail?.let { "\nDetail: $it" } ?: "")
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
        val config = importedConfig ?: loadSelectedOrLatestProfileConfigForAction()
        if (config == null) {
            status.text = "Import or load a saved WireGuard or V2Ray/Xray profile first."
            updateDashboardSummary()
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
        hubStatusTitle.text = "Preparing"
        hubStatusDetail.text = "WireGuard is preparing its runtime config."
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
        status.text = "Starting WireGuard engine. Verification will refresh automatically."
        hubStatusTitle.text = "Connecting"
        hubStatusDetail.text = "WireGuard engine is starting. ${selection.note}"
        scheduleEngineStatusRefreshes()
    }

    private fun prepareAndStartXrayEngine(config: ImportedConfig) {
        status.text = "Preparing embedded Xray/V2Ray runtime config from the imported link..."
        hubStatusTitle.text = "Preparing"
        hubStatusDetail.text = "Embedded Xray is preparing its runtime config."
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
        status.text = "Starting embedded Xray engine. Verification will refresh automatically."
        hubStatusTitle.text = "Connecting"
        hubStatusDetail.text = "Embedded Xray is starting. ${runtime.note}"
        scheduleEngineStatusRefreshes()
    }

    private fun stopImportedEngines() {
        startService(Intent(this, WireGuardVpnService::class.java).apply { action = WireGuardVpnService.ACTION_STOP })
        startService(Intent(this, XrayVpnService::class.java).apply { action = XrayVpnService.ACTION_STOP })
        status.text = "Disconnect requested for active engines."
        hubStatusTitle.text = "Disconnecting"
        hubStatusDetail.text = "Stopping WireGuard and Xray engines."
        primaryActionButton.text = "Connect"
        primaryActionButton.background = roundedBackground(0xFF2563EB.toInt(), 0xFF1D4ED8.toInt(), radiusDp = 12)
        mainHandler.postDelayed({ showEngineStatus() }, 1_500L)
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
        updateDashboardSummary()
        status.text = "Latest status: ${hub.title}. Verified: ${if (hub.verified) "yes" else "no"}."
        if (::advancedDiagnostics.isInitialized) {
            advancedDiagnostics.text = engineDiagnosticsText(wg, xray)
        }
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
                    "\n\nNext: tap Connect. If UDP is blocked, try an official OpenVPN TCP/443 or V2Ray/Xray config instead."
                ConfigKind.OPENVPN ->
                    "\n\nNext: open Advanced, tap Save pinned OpenVPN TCP config, then import it in an OpenVPN-compatible client while the internal OpenVPN engine is pending."
                ConfigKind.V2RAY ->
                    "\n\nNext: tap Connect. Advanced endpoint probe is optional."
                else -> ""
            }
            status.text = "Imported ${config.kind} config${name?.let { " ($it)" } ?: ""}.\n" +
                "Endpoints found: ${config.endpoints.size}\n$endpointLines" +
                savedProfileLine +
                "\n\nOpenVPN auth-user-pass line: ${if (config.hasAuthUserPass) "yes" else "not detected"}" +
                nextStep +
                warnings
            showSection(AppSection.HOME)
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
        showSection(AppSection.PROFILES)
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
            profileListContainer.addView(createActionButton(
                textValue = "${if (profile.id == selectedProfileId) "✓ " else ""}${profile.displayName} — ${profile.kind.displayName} / ${engine.displayName}"
            ) { loadProfile(profile) })
        }
        updateDashboardSummary()
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
        status.text = "Selected profile: ${profile.displayName}. Tap Connect to start."
        showSection(AppSection.HOME)
    }

    private fun loadSelectedOrLatestProfileConfigForAction(): ImportedConfig? {
        val profile = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull() ?: return null
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
        val config = importedConfig ?: loadSelectedOrLatestProfileConfigForAction()
        if (config == null) {
            status.text = "Import an OpenVPN, WireGuard, or V2Ray/Xray config first."
            return
        }

        showSection(AppSection.TOOLS)
        setAdvancedVisible(true)
        status.text = "Running advanced endpoint diagnostics..."
        advancedDiagnostics.text = "Resolving endpoints with DNS-over-HTTPS and probing candidates. If DoH is blocked, V2Ray/TCP endpoints can also be direct-probed without pinning."
        Thread {
            val text = try {
                buildResolveAndProbeReport(config)
            } catch (e: Exception) {
                "Resolve/probe failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                advancedDiagnostics.text = text
                status.text = "Advanced diagnostics completed. See the expanded diagnostics panel."
            }
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

    private fun String.shortUi(maxLength: Int): String {
        val compact = replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxLength) compact else compact.take(maxLength - 1) + "…"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class AppSection {
        HOME,
        PROFILES,
        TOOLS
    }

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
