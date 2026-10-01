package com.vpnproject.app

import android.app.Activity
import android.app.AlertDialog
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
import android.text.InputType
import android.text.TextUtils
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.vpnproject.app.profile.VpnProfileEndpoint
import com.vpnproject.app.vpn.AutoVpnService
import com.vpnproject.app.vpn.WireGuardVpnService
import com.vpnproject.app.vpn.XrayVpnService

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var hubStatusTitle: TextView
    private lateinit var hubStatusDetail: TextView
    private lateinit var connectionStatsText: TextView
    private lateinit var selectedProfileText: TextView
    private lateinit var primaryActionButton: TextView
    private lateinit var protectionBadge: TextView
    private lateinit var liveStatsBadge: TextView
    private lateinit var homeProfileNameText: TextView
    private lateinit var homeProfileMetaText: TextView
    private lateinit var statLatencyText: TextView
    private lateinit var statDownText: TextView
    private lateinit var statUpText: TextView
    private lateinit var statEngineText: TextView
    private lateinit var favoriteActionButton: Button
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
    private var liveRefreshRunning = false
    private val dashboardRefreshRunnable = object : Runnable {
        override fun run() {
            refreshDashboardLive()
            if (liveRefreshRunning) {
                val hub = currentHubStatus()
                val delayMs = if (isLiveState(hub.state)) LIVE_REFRESH_CONNECTED_MS else LIVE_REFRESH_IDLE_MS
                mainHandler.postDelayed(this, delayMs)
            }
        }
    }
    private var importedConfig: ImportedConfig? = null
    private var selectedProfileId: String? = null
    private var selectedProfile: VpnProfile? = null
    private var activeConnectionProfileId: String? = null
    private var lastRecordedVerificationKey: String? = null
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

        window.statusBarColor = 0xFFEAF6FF.toInt()
        window.navigationBarColor = 0xFFFFFFFF.toInt()

        val appRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = verticalGradient(0xFFEAF6FF.toInt(), 0xFFF8FBFF.toInt(), 0xFFFFFFFF.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(18), dp(22), dp(18), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        content.addView(createTopBar())
        content.addView(createIdentityPill())
        status = TextView(this).apply {
            text = "Ready. Import your own config, then connect."
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF64748B.toInt())
            background = roundedBackground(0x99FFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 18)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(14))
            }
        }
        content.addView(status)

        homeSection = createSectionContainer()
        profilesSection = createSectionContainer()
        toolsSection = createSectionContainer()
        content.addView(homeSection)
        content.addView(profilesSection)
        content.addView(toolsSection)

        val heroCard = createHeroCard()
        heroCard.addView(createHeroTopRow())

        primaryActionButton = TextView(this).apply {
            text = "START\nConnect"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            gravity = Gravity.CENTER
            background = powerButtonBackground(active = false)
            isClickable = true
            isFocusable = true
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(176), dp(176)).apply {
                setMargins(0, dp(18), 0, dp(14))
            }
            setOnClickListener { handlePrimaryAction() }
        }
        heroCard.addView(primaryActionButton)

        hubStatusTitle = TextView(this).apply {
            text = "Ready"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFF0F172A.toInt())
        }
        heroCard.addView(hubStatusTitle)

        hubStatusDetail = TextView(this).apply {
            text = "Pick a profile, then tap the circle."
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF475569.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }
        heroCard.addView(hubStatusDetail)

        heroCard.addView(createStatsGrid())
        heroCard.addView(createSelectedProfilePanel())

        homeSection.addView(heroCard)
        homeSection.addView(createProtocolCard())
        homeSection.addView(createQuickActionsCard())

        val profileCard = createCard()
        profileCard.addView(sectionLabel("My configs"))
        selectedProfileText = TextView(this).apply {
            text = "No profile selected yet."
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            setPadding(dp(8), 0, dp(8), dp(12))
        }
        profileCard.addView(selectedProfileText)
        profileCard.addView(createActionButton("Paste config from clipboard", primary = true) { importConfigFromClipboard() })
        profileCard.addView(createActionButton("Import config file") { openConfigPicker() })
        profileCard.addView(createProfileManageRow())
        profileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        profileCard.addView(profileListContainer)
        profilesSection.addView(profileCard)

        val toolsCard = createCard()
        toolsCard.addView(sectionLabel("Toolkit"))
        toolsCard.addView(TextView(this).apply {
            text = "Helpful checks stay here. Technical output is hidden until you need it."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(8), 0, dp(8), dp(10))
        })
        toolsCard.addView(createActionButton("Refresh connection status", primary = true) { showEngineStatus() })
        toolsCard.addView(createActionButton("Prepare Android VPN permission") { requestVpnPermission(PendingVpnAction.NONE) })
        toolsCard.addView(createActionButton("Load latest saved profile") { loadLatestProfile() })
        toolsCard.addView(createActionButton("Probe selected endpoints") { resolveAndProbeImportedConfig() })
        advancedToggleButton = createActionButton("Show technical diagnostics") { toggleAdvancedPanel() }
        toolsCard.addView(advancedToggleButton)
        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        advancedVisible = false
        advancedDiagnostics = TextView(this).apply {
            text = "Advanced diagnostics will appear here after refresh/probe."
            textSize = 13f
            gravity = Gravity.START
            setTextColor(0xFF334155.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }
        advancedPanel.addView(advancedDiagnostics)
        advancedPanel.addView(createActionButton("Show saved profiles") { showSavedProfiles() })
        advancedPanel.addView(createActionButton("Save pinned OpenVPN TCP config") { prepareAndSaveOpenVpnConfig() })
        advancedPanel.addView(createActionButton("Start TUN bootstrap VPN") { requestVpnPermission(PendingVpnAction.BOOTSTRAP) })
        advancedPanel.addView(createActionButton("Stop bootstrap VPN") { stopBootstrapVpn() })
        toolsCard.addView(advancedPanel)
        toolsSection.addView(toolsCard)

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(content)
        }
        appRoot.addView(scrollView)

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xF7FFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 30)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(18), dp(4), dp(18), dp(12))
            }
        }
        navHomeButton = createNavButton("Home") { showSection(AppSection.HOME) }
        navProfilesButton = createNavButton("Profiles") { showSection(AppSection.PROFILES) }
        navToolsButton = createNavButton("Tools") { showSection(AppSection.TOOLS) }
        navRow.addView(navHomeButton)
        navRow.addView(navProfilesButton)
        navRow.addView(navToolsButton)
        appRoot.addView(navRow)

        setContentView(appRoot)

        restoreLatestProfileMetadata()
        refreshProfileButtons()
        updateDashboardSummary()
        showSection(AppSection.HOME)
        startLiveDashboardRefresh()
    }

    override fun onResume() {
        super.onResume()
        startLiveDashboardRefresh()
    }

    override fun onPause() {
        stopLiveDashboardRefresh()
        super.onPause()
    }

    private fun createTopBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, 0, 0, dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(TextView(this@MainActivity).apply {
            text = "N"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF2563EB.toInt())
            background = roundedBackground(0xFFE0F2FE.toInt(), 0xFFBAE6FD.toInt(), radiusDp = 18)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        addView(TextView(this@MainActivity).apply {
            text = "VPN Hub\nSmart VPN connector"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, dp(8), 0)
            }
        })
        addView(topIconButton("PRO"))
        addView(topIconButton("SET"))
    }

    private fun createIdentityPill(): TextView = TextView(this).apply {
        text = "Fast • Private • Bring your own config"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(0xFF2563EB.toInt())
        background = roundedBackground(0x99FFFFFF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 22)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(14))
        }
    }

    private fun topIconButton(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFF0F172A.toInt())
        background = roundedBackground(0xF2FFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            setMargins(dp(5), 0, 0, 0)
        }
    }

    private fun createHeroCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = verticalGradient(0xFFFFFFFF.toInt(), 0xFFEAF7FF.toInt(), 0xFFF8FAFC.toInt(), radiusDp = 34)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(14))
        }
    }

    private fun createHeroTopRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        protectionBadge = statusBadge("SECURE", "Not connected", 0xFF64748B.toInt())
        liveStatsBadge = statusBadge("LIVE", "0 B", 0xFF2563EB.toInt())
        addView(protectionBadge)
        addView(liveStatsBadge)
    }

    private fun statusBadge(label: String, value: String, color: Int): TextView = TextView(this).apply {
        text = "$label\n$value"
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(color)
        background = roundedBackground(0xF2FFFFFF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 20)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
    }

    private fun createStatsGrid(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, dp(8), 0, dp(4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val top = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        statLatencyText = statTile("Latency", "--")
        statEngineText = statTile("Engine", "Auto")
        top.addView(statLatencyText)
        top.addView(statEngineText)
        addView(top)

        val bottom = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        statDownText = statTile("Down", "0 B")
        statUpText = statTile("Up", "0 B")
        bottom.addView(statDownText)
        bottom.addView(statUpText)
        addView(bottom)

        connectionStatsText = TextView(this@MainActivity).apply {
            text = "Verified: no • Traffic: 0 B down / 0 B up"
            visibility = View.GONE
        }
        addView(connectionStatsText)
    }

    private fun statTile(label: String, value: String): TextView = TextView(this).apply {
        text = "$label\n$value"
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFF0F172A.toInt())
        background = roundedBackground(0xBFFFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 18)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun createSelectedProfilePanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = roundedBackground(0xF7FFFFFF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 24)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(8), 0, dp(6))
        }
        addView(TextView(this@MainActivity).apply {
            text = "VPN"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFF2563EB.toInt())
            background = roundedBackground(0xFFE0F2FE.toInt(), 0xFFBAE6FD.toInt(), radiusDp = 18)
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                setMargins(0, 0, dp(12), 0)
            }
        })
        val textColumn = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        homeProfileNameText = TextView(this@MainActivity).apply {
            text = "Choose profile"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        homeProfileMetaText = TextView(this@MainActivity).apply {
            text = "Tap to import or select config"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textColumn.addView(homeProfileNameText)
        textColumn.addView(homeProfileMetaText)
        addView(textColumn)
        addView(TextView(this@MainActivity).apply {
            text = "Change"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF2563EB.toInt())
            background = roundedBackground(0xFFEFF6FF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 18)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        setOnClickListener { showSection(AppSection.PROFILES) }
    }

    private fun createProtocolCard(): LinearLayout = createCard().apply {
        addView(sectionLabel("Smart protocol"))
        addView(protocolRow(
            protocolTile("AUTO", "Best path", "Recommended", selected = true),
            protocolTile("XRAY", "Iran-first", "VLESS/VMess", selected = true)
        ))
        addView(protocolRow(
            protocolTile("OVPN", "TCP handoff", "Port 443", selected = false),
            protocolTile("WG", "Secondary", "UDP only", selected = false)
        ))
        addView(protocolRow(
            protocolTile("TRJ", "Trojan", "Xray", selected = false),
            protocolTile("REAL", "Reality", "Next", selected = false)
        ))
    }

    private fun protocolRow(vararg tiles: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tiles.forEach { addView(it) }
    }

    private fun protocolTile(label: String, title: String, subtitle: String, selected: Boolean): TextView = TextView(this).apply {
        text = "$label\n$title\n$subtitle"
        textSize = 12f
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(if (selected) 0xFF0F172A.toInt() else 0xFF475569.toInt())
        background = roundedBackground(
            fillColor = if (selected) 0xFFEFF6FF.toInt() else 0xF7FFFFFF.toInt(),
            strokeColor = if (selected) 0xFF2563EB.toInt() else 0xFFE2E8F0.toInt(),
            radiusDp = 22
        )
        setPadding(dp(8), dp(12), dp(8), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, dp(96), 1f).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }
    }

    private fun createQuickActionsCard(): LinearLayout = createCard().apply {
        addView(sectionLabel("Quick actions"))
        addView(createFeatureRow(
            featureTile("IMPORT", "Profiles", "Paste or file") { showSection(AppSection.PROFILES) },
            featureTile("CHECK", "Diagnostics", "Status tools") { showSection(AppSection.TOOLS) }
        ))
        addView(createFeatureRow(
            featureTile("SAFE", "Kill switch", "Android setting") { status.text = "Use Android Always-on VPN / Block connections without VPN for kill-switch behavior." },
            featureTile("IRAN", "Xray focus", "Best current path") { status.text = "Iran-first mode currently prioritizes embedded Xray/V2Ray configs. WireGuard remains secondary." }
        ))
    }

    private fun createFeatureRow(left: View, right: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(left)
        addView(right)
    }

    private fun featureTile(label: String, title: String, subtitle: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = "$label\n$title\n$subtitle"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFF0F172A.toInt())
        background = roundedBackground(0xF7FFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 24)
        setPadding(dp(8), dp(14), dp(8), dp(14))
        layoutParams = LinearLayout.LayoutParams(0, dp(112), 1f).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }
        setOnClickListener { onClick() }
    }

    private fun createProfileManageRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, dp(4), 0, 0)
        addView(createMiniActionButton("Rename") { promptRenameSelectedProfile() })
        favoriteActionButton = createMiniActionButton("Favorite") { toggleSelectedFavorite() }
        addView(favoriteActionButton)
        addView(createMiniActionButton("Delete") { confirmDeleteSelectedProfile() })
    }

    private fun createMiniActionButton(textValue: String, onClick: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = 12f
        setAllCaps(false)
        setTextColor(0xFF0F172A.toInt())
        background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(10), dp(6), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
        setOnClickListener { onClick() }
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 30)
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
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createNavButton(textValue: String, onClick: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setAllCaps(false)
        setTextColor(0xFF0F172A.toInt())
        background = roundedBackground(0xFFE2E8F0.toInt(), 0xFFCBD5E1.toInt(), radiusDp = 22)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), dp(12), dp(8), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            0,
            dp(56),
            1f
        ).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(textValue: String): TextView = TextView(this).apply {
        text = textValue.uppercase()
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(0xFF64748B.toInt())
        setPadding(0, 0, 0, dp(10))
    }

    private fun createActionButton(
        textValue: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = textValue
        textSize = if (primary) 15f else 14f
        typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setAllCaps(false)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        background = roundedBackground(
            fillColor = if (primary) 0xFF2563EB.toInt() else 0xFFF1F5F9.toInt(),
            strokeColor = if (primary) 0xFF1D4ED8.toInt() else 0xFFCBD5E1.toInt(),
            radiusDp = 18
        )
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(12), dp(10), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(3), dp(5), dp(3), dp(5))
        }
        setOnClickListener { onClick() }
    }

    private fun verticalGradient(
        topColor: Int,
        centerColor: Int,
        bottomColor: Int,
        radiusDp: Int = 0
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(topColor, centerColor, bottomColor)
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun powerButtonBackground(active: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (active) {
            intArrayOf(0xFF10B981.toInt(), 0xFF06B6D4.toInt(), 0xFF2563EB.toInt())
        } else {
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE0F2FE.toInt(), 0xFFECFEFF.toInt())
        }
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(8), if (active) 0x8834D399.toInt() else 0xAA5B7CFA.toInt())
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
            radiusDp = 22
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
            "Hide technical diagnostics"
        } else {
            "Show technical diagnostics"
        }
    }

    private fun startLiveDashboardRefresh() {
        if (!::hubStatusTitle.isInitialized) return
        liveRefreshRunning = true
        mainHandler.removeCallbacks(dashboardRefreshRunnable)
        mainHandler.post(dashboardRefreshRunnable)
    }

    private fun stopLiveDashboardRefresh() {
        liveRefreshRunning = false
        if (::hubStatusTitle.isInitialized) {
            mainHandler.removeCallbacks(dashboardRefreshRunnable)
        }
    }

    private fun refreshDashboardLive() {
        if (!::hubStatusTitle.isInitialized) return
        val hub = currentHubStatus()
        recordVerifiedProfileIfNeeded(hub)
        updateDashboardSummary()
        if (::advancedDiagnostics.isInitialized && advancedVisible && ::toolsSection.isInitialized && toolsSection.visibility == View.VISIBLE) {
            advancedDiagnostics.text = engineDiagnosticsText(WireGuardVpnService.lastStatus, XrayVpnService.lastStatus)
        }
    }

    private fun isLiveState(state: VpnHubConnectionState): Boolean = when (state) {
        VpnHubConnectionState.CONNECTED,
        VpnHubConnectionState.CONNECTING,
        VpnHubConnectionState.RUNNING_UNVERIFIED -> true
        VpnHubConnectionState.IDLE,
        VpnHubConnectionState.STOPPED,
        VpnHubConnectionState.FAILED -> false
    }

    private fun recordVerifiedProfileIfNeeded(
        hub: com.vpnproject.app.engine.VpnHubStatus,
        refreshProfiles: Boolean = true
    ) {
        if (!hub.verified || hub.state != VpnHubConnectionState.CONNECTED) return
        val profileId = activeConnectionProfileId ?: selectedProfileId ?: return
        val network = currentNetworkLabel()
        val key = "$profileId:${hub.activeEngine}:${hub.latencyMs ?: -1}:${network.orEmpty()}:${hub.verified}"
        if (lastRecordedVerificationKey == key) return
        val updated = runCatching {
            profileStore.markVerified(
                profileId = profileId,
                network = network,
                latencyMs = hub.latencyMs
            )
        }.getOrNull()
        if (updated != null) {
            lastRecordedVerificationKey = key
            if (selectedProfileId == updated.id) selectedProfile = updated
            activeConnectionProfileId = updated.id
            if (refreshProfiles) refreshProfileButtons(syncVerified = false)
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
        selectedProfile = activeConnectionProfileId?.let { profileStore.profile(it) } ?: selectedProfile
    )

    private fun updateDashboardSummary() {
        if (!::hubStatusTitle.isInitialized) return
        val hub = currentHubStatus()
        val active = hub.state == VpnHubConnectionState.CONNECTED ||
            hub.state == VpnHubConnectionState.CONNECTING ||
            hub.state == VpnHubConnectionState.RUNNING_UNVERIFIED

        hubStatusTitle.text = dashboardTitleFor(hub.state)
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
        hubStatusDetail.text = dashboardDetail(hub)
        connectionStatsText.text = buildStatsLine(hub.verified, hub.rxBytes, hub.txBytes, hub.egressIp, hub.activeEngine, hub.latencyMs)

        val down = formatBytes(hub.rxBytes ?: 0)
        val up = formatBytes(hub.txBytes ?: 0)
        if (::statLatencyText.isInitialized) statLatencyText.text = "Latency\n${hub.latencyMs?.let { "${it} ms" } ?: "--"}"
        if (::statEngineText.isInitialized) statEngineText.text = "Engine\n${hub.activeEngine?.let { engineLabel(it) } ?: "Auto"}"
        if (::statDownText.isInitialized) statDownText.text = "Down\n$down"
        if (::statUpText.isInitialized) statUpText.text = "Up\n$up"

        if (::protectionBadge.isInitialized) {
            val protectionText = when (hub.state) {
                VpnHubConnectionState.CONNECTED -> "SECURE\nProtected"
                VpnHubConnectionState.CONNECTING,
                VpnHubConnectionState.RUNNING_UNVERIFIED -> "SECURE\nChecking"
                VpnHubConnectionState.FAILED -> "SECURE\nFailed"
                VpnHubConnectionState.IDLE,
                VpnHubConnectionState.STOPPED -> "SECURE\nOffline"
            }
            val protectionColor = when (hub.state) {
                VpnHubConnectionState.CONNECTED -> 0xFF047857.toInt()
                VpnHubConnectionState.CONNECTING,
                VpnHubConnectionState.RUNNING_UNVERIFIED -> 0xFFB45309.toInt()
                VpnHubConnectionState.FAILED -> 0xFFB91C1C.toInt()
                VpnHubConnectionState.IDLE,
                VpnHubConnectionState.STOPPED -> 0xFF64748B.toInt()
            }
            protectionBadge.text = protectionText
            protectionBadge.setTextColor(protectionColor)
        }
        if (::liveStatsBadge.isInitialized) {
            liveStatsBadge.text = if (active) "LIVE\n$down / $up" else "LIVE\n0 B"
            liveStatsBadge.setTextColor(if (active) 0xFF2563EB.toInt() else 0xFF64748B.toInt())
        }

        primaryActionButton.text = if (active) "STOP\nDisconnect" else "START\nConnect"
        primaryActionButton.textSize = 22f
        primaryActionButton.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        primaryActionButton.background = powerButtonBackground(active)
        updateSelectedProfileSummary()
        updateProfileActionButtons()
    }

    private fun dashboardTitleFor(state: VpnHubConnectionState): String = when (state) {
        VpnHubConnectionState.CONNECTED -> "Protected"
        VpnHubConnectionState.CONNECTING -> "Connecting"
        VpnHubConnectionState.RUNNING_UNVERIFIED -> "Checking"
        VpnHubConnectionState.FAILED -> "Failed"
        VpnHubConnectionState.STOPPED -> "Disconnected"
        VpnHubConnectionState.IDLE -> "Ready"
    }

    private fun dashboardDetail(hub: com.vpnproject.app.engine.VpnHubStatus): String {
        val profile = activeConnectionProfileId?.let { profileStore.profile(it) } ?: selectedProfile
        val profileName = profile?.displayName?.shortUi(28)
        val engine = hub.activeEngine?.let { engineLabel(it) }
        return when (hub.state) {
            VpnHubConnectionState.CONNECTED -> listOfNotNull(
                profileName,
                engine,
                hub.latencyMs?.let { "${it} ms" },
                hub.egressIp?.let { "IP $it" }
            ).joinToString(" • ").ifBlank { "Tunnel verified and traffic is protected." }
            VpnHubConnectionState.CONNECTING -> "Starting tunnel and verifying internet access."
            VpnHubConnectionState.RUNNING_UNVERIFIED -> "Tunnel is running; waiting for verification."
            VpnHubConnectionState.FAILED -> hub.detail.shortUi(96)
            VpnHubConnectionState.STOPPED -> profileName?.let { "$it is ready. Tap START to reconnect." } ?: "No active tunnel."
            VpnHubConnectionState.IDLE -> profileName?.let { "$it is ready. Tap START to connect." } ?: "Import or select a profile to connect."
        }
    }

    private fun buildStatsLine(
        verified: Boolean,
        rxBytes: Long?,
        txBytes: Long?,
        egressIp: String?,
        engine: com.vpnproject.app.engine.EngineKind?,
        latencyMs: Long?
    ): String {
        val parts = mutableListOf(
            "Verified: ${if (verified) "yes" else "no"}",
            "Traffic: ${formatBytes(rxBytes ?: 0)} down / ${formatBytes(txBytes ?: 0)} up"
        )
        latencyMs?.let { parts += "Latency: ${it}ms" }
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
        val profile = selectedProfile
        if (::selectedProfileText.isInitialized) {
            selectedProfileText.text = if (profile == null) {
                "No profile selected yet. Import a config or pick a saved profile."
            } else {
                selectedProfileSummary(profile)
            }
        }
        if (::homeProfileNameText.isInitialized) {
            homeProfileNameText.text = profile?.displayName?.shortUi(32) ?: "Choose profile"
            homeProfileMetaText.text = profile?.let { homeProfileMeta(it) } ?: "Paste or import a config to begin"
        }
    }

    private fun homeProfileMeta(profile: VpnProfile): String {
        val engine = EngineRegistry.engineFor(profile.kind)
        val endpoint = profile.endpoints.firstOrNull()?.cleanEndpointLabel()?.shortUi(42)
        val verified = profile.lastVerifiedLabel()?.shortUi(42)
        return listOfNotNull(
            "${profile.kind.displayName} • ${engine.displayName}",
            endpoint,
            verified
        ).joinToString(" • ")
    }

    private fun updateProfileActionButtons() {
        if (!::favoriteActionButton.isInitialized) return
        val profile = selectedProfile
        favoriteActionButton.text = when {
            profile == null -> "Favorite"
            profile.favorite -> "Unfavorite"
            else -> "Favorite"
        }
    }

    private fun selectedProfileSummary(profile: VpnProfile): String {
        val engine = EngineRegistry.engineFor(profile.kind)
        val endpointText = profile.endpoints.firstOrNull()?.let { "\n${it.cleanEndpointLabel()}" }.orEmpty()
        val favoriteText = if (profile.favorite) "Favorite\n" else ""
        val verifiedText = profile.lastVerifiedLabel()?.let { "\n$it" }.orEmpty()
        return "$favoriteText${profile.displayName.shortUi(34)}\n${profile.kind.displayName} • ${engine.displayName}$endpointText$verifiedText"
    }

    private fun profileButtonLabel(profile: VpnProfile): String {
        val engine = EngineRegistry.engineFor(profile.kind)
        val marker = if (profile.id == selectedProfileId) "SELECTED • " else ""
        val favorite = if (profile.favorite) "FAV • " else ""
        val endpoint = profile.endpoints.firstOrNull()?.cleanEndpointLabel()?.shortUi(48)
        val verified = profile.lastVerifiedLabel()?.shortUi(56)
        return "$marker$favorite${profile.displayName.shortUi(28)}\n${profile.kind.displayName} • ${engine.displayName}" +
            (endpoint?.let { "\n$it" } ?: "") +
            (verified?.let { "\n$it" } ?: "")
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
        activeConnectionProfileId = selectedProfileId
        lastRecordedVerificationKey = null
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
        activeConnectionProfileId = null
        lastRecordedVerificationKey = null
        hubStatusTitle.text = "Disconnecting"
        hubStatusDetail.text = "Stopping WireGuard and Xray engines."
        primaryActionButton.text = "START\nConnect"
        primaryActionButton.setTextColor(0xFF0F172A.toInt())
        primaryActionButton.background = powerButtonBackground(active = false)
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
        val hub = currentHubStatus()
        recordVerifiedProfileIfNeeded(hub)
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

    private fun promptRenameSelectedProfile() {
        val profile = selectedProfile
        if (profile == null) {
            status.text = "No selected profile to rename. Pick a profile first."
            showSection(AppSection.PROFILES)
            return
        }

        val input = EditText(this).apply {
            setText(profile.displayName)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        AlertDialog.Builder(this)
            .setTitle("Rename profile")
            .setMessage("Choose a local display name. This does not change the provider config or credentials.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> renameSelectedProfile(input.text?.toString().orEmpty()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameSelectedProfile(newName: String) {
        val current = selectedProfile
        if (current == null) {
            status.text = "No selected profile to rename."
            return
        }
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            status.text = "Profile name cannot be empty."
            showSection(AppSection.PROFILES)
            return
        }
        runCatching { profileStore.renameProfile(current.id, trimmed) }.fold(
            onSuccess = { updated ->
                selectedProfile = updated ?: profileStore.profile(current.id) ?: current.copy(name = trimmed)
                selectedProfileId = selectedProfile?.id
                refreshProfileButtons()
                updateDashboardSummary()
                status.text = "Renamed profile to ${selectedProfile?.displayName ?: trimmed}."
                showSection(AppSection.PROFILES)
            },
            onFailure = { error ->
                status.text = "Could not rename profile ${current.displayName}: ${error.message ?: error.javaClass.simpleName}"
            }
        )
    }

    private fun toggleSelectedFavorite() {
        val current = selectedProfile
        if (current == null) {
            status.text = "No selected profile to favorite. Pick a profile first."
            showSection(AppSection.PROFILES)
            return
        }
        val newFavorite = !current.favorite
        runCatching { profileStore.setFavorite(current.id, newFavorite) }.fold(
            onSuccess = { updated ->
                selectedProfile = updated ?: current.copy(favorite = newFavorite)
                selectedProfileId = selectedProfile?.id
                refreshProfileButtons()
                updateDashboardSummary()
                status.text = if (newFavorite) {
                    "Marked ${selectedProfile?.displayName ?: current.displayName} as favorite."
                } else {
                    "Removed favorite mark from ${selectedProfile?.displayName ?: current.displayName}."
                }
                showSection(AppSection.PROFILES)
            },
            onFailure = { error ->
                status.text = "Could not update favorite for ${current.displayName}: ${error.message ?: error.javaClass.simpleName}"
            }
        )
    }

    private fun confirmDeleteSelectedProfile() {
        val profile = selectedProfile
        if (profile == null) {
            status.text = "No selected profile to delete. Pick a profile first."
            showSection(AppSection.PROFILES)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete profile?")
            .setMessage("Remove ${profile.displayName} from this phone? The encrypted local config for this profile will be deleted. Active VPN traffic is not stopped automatically.")
            .setPositiveButton("Delete") { _, _ -> deleteProfile(profile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteProfile(profile: VpnProfile) {
        runCatching { profileStore.deleteProfile(profile.id) }.fold(
            onSuccess = {
                if (selectedProfileId == profile.id) {
                    importedConfig = null
                    selectedProfile = runCatching { profileStore.latestProfile() }.getOrNull()
                    selectedProfileId = selectedProfile?.id
                }
                refreshProfileButtons()
                updateDashboardSummary()
                status.text = if (selectedProfile == null) {
                    "Deleted profile ${profile.displayName}. No saved profiles remain."
                } else {
                    "Deleted profile ${profile.displayName}. Latest remaining profile is selected."
                }
                showSection(AppSection.PROFILES)
            },
            onFailure = { error ->
                status.text = "Could not delete profile ${profile.displayName}: ${error.message ?: error.javaClass.simpleName}"
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

    private fun refreshProfileButtons(syncVerified: Boolean = true) {
        if (!::profileListContainer.isInitialized) return
        if (syncVerified) recordVerifiedProfileIfNeeded(currentHubStatus(), refreshProfiles = false)
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
            profileListContainer.addView(createActionButton(
                textValue = profileButtonLabel(profile)
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

    private fun currentNetworkLabel(): String? {
        return try {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return null
            val transports = mutableListOf<String>()
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "cellular"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "wifi"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "vpn"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "ethernet"
            transports.ifEmpty { listOf("unknown") }.joinToString("+")
        } catch (_: Exception) {
            null
        }
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

    private fun VpnProfile.lastVerifiedLabel(): String? {
        if (lastVerifiedEpochMs == null) return null
        val latency = lastVerifiedLatencyMs?.let { "${it}ms" } ?: "verified"
        val network = lastVerifiedNetwork?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        return "Last good: $latency$network"
    }

    private fun VpnProfileEndpoint.cleanEndpointLabel(): String {
        val shortHost = host.shortHost()
        return "$protocol $shortHost:$port"
    }

    private fun String.shortHost(): String {
        val compact = substringBefore('/').trim()
        return if (compact.length <= 44) compact else compact.take(21) + "…" + compact.takeLast(18)
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
        const val LIVE_REFRESH_CONNECTED_MS = 2_000L
        const val LIVE_REFRESH_IDLE_MS = 6_000L
    }
}
