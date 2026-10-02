package com.vpnproject.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import com.vpnproject.app.core.V2RaySubscriptionParser
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
import com.vpnproject.app.profile.SubscriptionGroup
import com.vpnproject.app.profile.VpnProfile
import com.vpnproject.app.profile.VpnProfileKind
import com.vpnproject.app.profile.VpnProfileEndpoint
import com.vpnproject.app.vpn.AutoVpnService
import com.vpnproject.app.vpn.WireGuardVpnService
import com.vpnproject.app.vpn.XrayVpnService
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var hubStatusTitle: TextView
    private lateinit var hubStatusDetail: TextView
    private lateinit var connectionStatsText: TextView
    private lateinit var selectedProfileText: TextView
    private lateinit var primaryActionButton: PowerRingButton
    private lateinit var protectionBadge: TextView
    private lateinit var liveStatsBadge: TextView
    private lateinit var homeProfileIconText: TextView
    private lateinit var homeProfileNameText: TextView
    private lateinit var homeProfileMetaText: TextView
    private lateinit var statLatencyText: TextView
    private lateinit var statDownText: TextView
    private lateinit var statUpText: TextView
    private lateinit var statEngineText: TextView
    private lateinit var autoTestToggleButton: TextView
    private lateinit var autoTestStatusText: TextView
    private var autoTestEnabled = true
    private var autoTestInFlight = false
    private lateinit var favoriteActionButton: Button
    private lateinit var locationSearchInput: EditText
    private var locationSearchQuery = ""
    private var selectedLocationGroupFilter = LOCATION_FILTER_ALL
    private lateinit var navHomeButton: Button
    private lateinit var navProfilesButton: Button
    private lateinit var navToolsButton: Button
    private lateinit var homeSection: LinearLayout
    private lateinit var profilesSection: LinearLayout
    private lateinit var toolsSection: LinearLayout
    private lateinit var advancedToggleButton: Button
    private lateinit var advancedPanel: LinearLayout
    private lateinit var advancedDiagnostics: TextView
    private lateinit var settingsConnectionSummaryText: TextView
    private lateinit var settingsAutoTestValueText: TextView
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
    private lateinit var subscriptionGroupContainer: LinearLayout
    private var pendingVpnAction = PendingVpnAction.NONE
    private var pendingOpenVpnConfigText: String? = null
    private var pendingOpenVpnConfigName: String = "vpn-project-pinned.ovpn"
    private val endpointDiscovery by lazy { EndpointDiscovery() }
    private val endpointHealthChecker by lazy { EndpointHealthChecker() }
    private val runtimeConfigPreparer by lazy { RuntimeConfigPreparer(endpointDiscovery) }
    private val profileStore by lazy { SecureProfileStore(this) }
    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val appSettings by lazy { getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE) }
    private val routeHealthCache = RouteHealthCache()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        autoTestEnabled = appSettings.getBoolean(KEY_AUTO_TEST_ENABLED, true)

        window.statusBarColor = 0xFFEAF6FF.toInt()
        window.navigationBarColor = 0xFFFFFFFF.toInt()

        val appRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, statusBarTopPadding(), 0, 0)
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
            setPadding(dp(12), dp(12), dp(12), dp(10))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        content.addView(createTopBar())
        status = TextView(this).apply {
            text = "Ready. Import your own config, then connect."
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF64748B.toInt())
            visibility = View.GONE
        }
        content.addView(status)

        homeSection = createSectionContainer()
        profilesSection = createSectionContainer()
        toolsSection = createSectionContainer()
        content.addView(homeSection)
        content.addView(profilesSection)
        content.addView(toolsSection)

        homeSection.addView(createCompactHomeDashboard())
        homeSection.addView(createSelectedConfigsCard())

        val profileCard = createCard()
        profileCard.addView(sectionLabel("Locations"))
        selectedProfileText = TextView(this).apply {
            text = "No profile selected yet."
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF0F172A.toInt())
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        profileCard.addView(selectedProfileText)
        profileCard.addView(createLocationSearchCard())
        subscriptionGroupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }
        profileCard.addView(subscriptionGroupContainer)
        profileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        profileCard.addView(profileListContainer)
        profilesSection.addView(profileCard)

        val settingsCard = createCard()
        settingsCard.addView(sectionLabel("Settings"))
        settingsCard.addView(TextView(this).apply {
            text = "Normal controls stay simple. Technical diagnostics are tucked into Advanced."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(8), 0, dp(8), dp(10))
        })
        settingsConnectionSummaryText = TextView(this).apply {
            text = "Status: tap Refresh status"
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF334155.toInt())
            background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 18)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        settingsCard.addView(settingsConnectionSummaryText)
        settingsAutoTestValueText = TextView(this).apply { text = if (autoTestEnabled) "ON" else "OFF" }
        settingsCard.addView(settingsRow("↻", "Refresh status", "Update VPN state, traffic, and verification") { showEngineStatus() })
        settingsCard.addView(settingsRow("◷", "Test latency", "Choose quick ping or real VPN latency for selected config") {
            showLatencyTestSheet(
                anchorProfile = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull(),
                candidates = currentVisibleProfilesForTesting(),
                title = "Test latency"
            )
        })
        settingsCard.addView(settingsRow("A", "Auto ping", "Optional background ping-ranking after imports", settingsAutoTestValueText) { toggleAutoTest() })
        settingsCard.addView(settingsRow("★", "Ping-rank configs", "Quick-test saved configs and select the fastest reachable one") { rankSavedProfilesAndSelectBest("settings") })
        settingsCard.addView(settingsRow("+", "Add configs", "Clipboard, file, or subscription URL") { showAddConfigMenu() })
        settingsCard.addView(settingsRow("🛡", "Kill switch", "Use Android Always-on VPN for stricter blocking") { showKillSwitchInfoSheet() })
        advancedToggleButton = createActionButton("Show advanced tools") { toggleAdvancedPanel() }
        settingsCard.addView(advancedToggleButton)
        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        advancedVisible = false
        advancedPanel.addView(sectionLabel("Advanced"))
        advancedDiagnostics = TextView(this).apply {
            text = "Advanced diagnostics will appear here after refresh/probe."
            textSize = 12.5f
            gravity = Gravity.START
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF334155.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showDiagnosticsLogSheet() }
            background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }
        advancedPanel.addView(advancedDiagnostics)
        advancedPanel.addView(settingsRow("▤", "Open diagnostics log", "Full technical output in a scrollable sheet") { showDiagnosticsLogSheet() })
        advancedPanel.addView(settingsRow("▣", "VPN permission", "Prepare Android system VPN approval") { requestVpnPermission(PendingVpnAction.NONE) })
        advancedPanel.addView(settingsRow("◷", "Load latest profile", "Select the newest encrypted local config") { loadLatestProfile() })
        advancedPanel.addView(settingsRow("☰", "Saved profiles report", "Text report for troubleshooting only") { showSavedProfiles() })
        advancedPanel.addView(settingsRow("◉", "OpenVPN TCP handoff", "Save a pinned .ovpn for external clients") { prepareAndSaveOpenVpnConfig() })
        advancedPanel.addView(settingsRow("▶", "Start bootstrap VPN", "Technical TUN bootstrap check") { requestVpnPermission(PendingVpnAction.BOOTSTRAP) })
        advancedPanel.addView(settingsRow("■", "Stop bootstrap VPN", "Stop only the technical bootstrap tunnel") { stopBootstrapVpn() })
        settingsCard.addView(advancedPanel)
        toolsSection.addView(settingsCard)
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                navigationBarBottomPadding() + dp(28)
            )
        })

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
                setMargins(dp(18), dp(4), dp(18), navigationBarBottomPadding() + dp(12))
            }
        }
        navHomeButton = createNavButton("⌂\nHome") { showSection(AppSection.HOME) }
        navProfilesButton = createNavButton("◎\nLocations") { showSection(AppSection.PROFILES) }
        navToolsButton = createNavButton("⚙\nSettings") { showSection(AppSection.TOOLS) }
        navRow.addView(navHomeButton)
        navRow.addView(navProfilesButton)
        navRow.addView(navToolsButton)
        appRoot.addView(navRow)

        setContentView(appRoot)

        updateAutoTestToggle()
        restoreLatestProfileMetadata()
        refreshProfileButtons()
        updateDashboardSummary()
        refreshAutoTestSummary()
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
        setPadding(0, 0, 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(TextView(this@MainActivity).apply {
            text = "VPN"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            background = verticalGradient(0xFF2563EB.toInt(), 0xFF06B6D4.toInt(), 0xFF22C55E.toInt(), radiusDp = 18)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                setMargins(0, 0, dp(10), 0)
            }
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "MultiVPN"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(0xFF0F172A.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = "Your configs • Smart connect"
                textSize = 12f
                includeFontPadding = false
                setTextColor(0xFF64748B.toInt())
            })
        })
        addView(headerIconButton("+") { showAddConfigMenu() })
    }

    private fun headerIconButton(textValue: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textValue
        textSize = if (textValue == "+") 28f else 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(if (textValue == "+") 0xFF2563EB.toInt() else 0xFF0F172A.toInt())
        background = roundedBackground(0xEFFFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 18)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(dp(6), 0, 0, 0)
        }
        setOnClickListener { onClick() }
    }

    private fun createCompactHomeDashboard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, 0, 0, dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val hero = FrameLayout(this@MainActivity).apply {
            background = roundedBackground(0x00FFFFFF, 0x00FFFFFF, radiusDp = 30)
            clipToOutline = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(270)
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }
        hero.addView(ScenicBackgroundView(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        hero.addView(createProtectionCard(), FrameLayout.LayoutParams(dp(108), dp(132), Gravity.START or Gravity.TOP).apply {
            setMargins(0, dp(4), 0, 0)
        })
        hero.addView(createSpeedCard(), FrameLayout.LayoutParams(dp(104), dp(138), Gravity.END or Gravity.TOP).apply {
            setMargins(0, dp(4), 0, 0)
        })

        primaryActionButton = PowerRingButton(this@MainActivity).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { handlePrimaryAction() }
        }
        hero.addView(primaryActionButton, FrameLayout.LayoutParams(dp(132), dp(132), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(36)
        })

        hubStatusTitle = TextView(this@MainActivity).apply {
            text = "Disconnected"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFF0F172A.toInt())
        }
        hero.addView(hubStatusTitle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.TOP).apply {
            topMargin = dp(174)
        })

        hubStatusDetail = TextView(this@MainActivity).apply {
            text = "Pick a profile, then tap the power button."
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF059669.toInt())
        }
        hero.addView(hubStatusDetail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42), Gravity.TOP).apply {
            topMargin = dp(202)
            leftMargin = dp(42)
            rightMargin = dp(42)
        })
        addView(hero)

        connectionStatsText = TextView(this@MainActivity).apply {
            text = "Verified: no • Traffic: 0 B down / 0 B up"
            visibility = View.GONE
        }
        addView(connectionStatsText)
    }

    private fun createProtectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), dp(9), dp(7), dp(9))
        background = roundedBackground(0xDFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), radiusDp = 20)
        protectionBadge = TextView(this@MainActivity).apply {
            text = "✓  Protected  ›"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF047857.toInt())
            maxLines = 1
        }
        addView(protectionBadge)
        listOf(
            "◎  Real IP Hidden",
            "▣  Encrypted Traffic",
            "◌  No Logs",
            "✦  Kill Switch Active"
        ).forEach { row ->
            addView(TextView(this@MainActivity).apply {
                text = row
                textSize = 9.5f
                setTextColor(0xFF64748B.toInt())
                maxLines = 1
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun createSpeedCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = roundedBackground(0xDFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), radiusDp = 20)
        addView(MiniChartView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        })
        statDownText = speedLine("↓", "0 B", "Download", 0xFF10B981.toInt())
        statUpText = speedLine("↑", "0 B", "Upload", 0xFF7C3AED.toInt())
        statLatencyText = speedLine("◷", "--", "Ping", 0xFF2563EB.toInt())
        liveStatsBadge = TextView(this@MainActivity).apply { visibility = View.GONE }
        statEngineText = TextView(this@MainActivity).apply {
            text = "Engine\nAuto"
            visibility = View.GONE
        }
        addView(statDownText)
        addView(statUpText)
        addView(statLatencyText)
        addView(liveStatsBadge)
        addView(statEngineText)
    }

    private fun speedLine(icon: String, value: String, label: String, color: Int): TextView = TextView(this).apply {
        text = "$icon  $value\n     $label"
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setTextColor(0xFF0F172A.toInt())
        setPadding(0, dp(5), 0, 0)
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

    private fun createSelectedConfigsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = roundedBackground(0xEFFFFFFF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 24)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            compactSelectorWidth(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(10))
        }

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(8), dp(6), dp(6), dp(6))
            background = roundedBackground(0xF7FFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
            homeProfileIconText = TextView(this@MainActivity).apply {
                text = "🌐"
                textSize = 18f
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = roundedBackground(0xFFE0F2FE.toInt(), 0xFFBAE6FD.toInt(), radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            }
            addView(homeProfileIconText)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                homeProfileNameText = TextView(this@MainActivity).apply {
                    text = "Choose location"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF0F172A.toInt())
                }
                homeProfileMetaText = TextView(this@MainActivity).apply {
                    text = "Tap to pick"
                    textSize = 10f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF64748B.toInt())
                    setPadding(0, dp(2), 0, 0)
                }
                addView(homeProfileNameText)
                addView(homeProfileMetaText)
            })
            addView(floatingTestButton(compact = true) {
                showLatencyTestSheet(
                    anchorProfile = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull(),
                    candidates = currentVisibleProfilesForTesting(),
                    title = "Test selected config"
                )
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(40)).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "⌄"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFF2563EB.toInt())
                background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
        })
        setOnClickListener { showConfigSelectorSheet() }
        setOnLongClickListener {
            selectedProfile?.let { showProfileActionsSheet(it) } ?: showConfigSelectorSheet()
            true
        }
    }

    private fun createAutoTestCard(): LinearLayout = createCard().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "Smart auto test"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF0F172A.toInt())
                })
                autoTestStatusText = TextView(this@MainActivity).apply {
                    text = "Ranks saved configs and selects the best reachable one"
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF64748B.toInt())
                }
                addView(autoTestStatusText)
            })
            autoTestToggleButton = TextView(this@MainActivity).apply {
                text = "ON"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = roundedBackground(0xFF10B981.toInt(), 0xFF059669.toInt(), radiusDp = 18)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleAutoTest() }
            }
            addView(autoTestToggleButton)
            addView(TextView(this@MainActivity).apply {
                text = "Rank"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFF2563EB.toInt())
                background = roundedBackground(0xFFEFF6FF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 18)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { rankSavedProfilesAndSelectBest("manual") }
            })
        })
    }

    private fun toggleAutoTest() {
        autoTestEnabled = !autoTestEnabled
        appSettings.edit().putBoolean(KEY_AUTO_TEST_ENABLED, autoTestEnabled).apply()
        updateAutoTestToggle()
        setAutoTestStatus(if (autoTestEnabled) "Auto test enabled" else "Auto test disabled")
        if (autoTestEnabled) maybeAutoRankBestProfile("toggle")
    }

    private fun updateAutoTestToggle() {
        if (::autoTestToggleButton.isInitialized) {
            autoTestToggleButton.text = if (autoTestEnabled) "ON" else "OFF"
            autoTestToggleButton.setTextColor(if (autoTestEnabled) 0xFFFFFFFF.toInt() else 0xFF475569.toInt())
            autoTestToggleButton.background = roundedBackground(
                fillColor = if (autoTestEnabled) 0xFF10B981.toInt() else 0xFFE2E8F0.toInt(),
                strokeColor = if (autoTestEnabled) 0xFF059669.toInt() else 0xFFCBD5E1.toInt(),
                radiusDp = 18
            )
        }
        if (::settingsAutoTestValueText.isInitialized) {
            settingsAutoTestValueText.text = if (autoTestEnabled) "ON" else "OFF"
            settingsAutoTestValueText.setTextColor(if (autoTestEnabled) 0xFFFFFFFF.toInt() else 0xFF475569.toInt())
            settingsAutoTestValueText.background = roundedBackground(
                fillColor = if (autoTestEnabled) 0xFF10B981.toInt() else 0xFFE2E8F0.toInt(),
                strokeColor = if (autoTestEnabled) 0xFF059669.toInt() else 0xFFCBD5E1.toInt(),
                radiusDp = 14
            )
        }
    }

    private fun setAutoTestStatus(message: String) {
        if (::autoTestStatusText.isInitialized) autoTestStatusText.text = message.shortUi(88)
        status.text = message
    }

    private fun refreshAutoTestSummary() {
        if (!::autoTestStatusText.isInitialized || autoTestInFlight) return
        autoTestStatusText.text = when {
            !autoTestEnabled -> "Auto test disabled"
            selectedProfile?.lastTestedEpochMs != null -> selectedProfile?.lastTestLabel()?.shortUi(88)
                ?: "Last test saved for selected config"
            else -> "Ranks saved configs and selects the best reachable one"
        }
    }

    private fun autoTestsShouldPauseForLiveVpn(): Boolean = isLiveState(currentHubStatus().state)

    private fun pauseAutoTestsForConnection(message: String = "Auto test paused while VPN is running") {
        if (autoTestInFlight) autoTestInFlight = false
        setAutoTestStatus(message)
    }

    private fun maybeAutoTestSelectedConfig(reason: String) {
        if (!autoTestEnabled || autoTestsShouldPauseForLiveVpn()) return
        mainHandler.postDelayed({ autoTestSelectedConfig(reason) }, 350L)
    }

    private fun maybeAutoRankBestProfile(reason: String) {
        if (!autoTestEnabled || autoTestsShouldPauseForLiveVpn()) return
        mainHandler.postDelayed({ rankSavedProfilesAndSelectBest(reason) }, 450L)
    }

    private fun autoTestSelectedConfig(reason: String) {
        val testLabel = if (reason.contains("ping", ignoreCase = true) || reason == "actions") "Ping test" else "Auto ping"
        if (autoTestsShouldPauseForLiveVpn()) {
            setAutoTestStatus("$testLabel paused while VPN is running")
            return
        }
        if (autoTestInFlight) {
            setAutoTestStatus("A latency test is already running")
            return
        }
        val profile = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull()
        val config = importedConfig ?: profile?.let { loadProfileConfig(it) }
        if (config == null) {
            setAutoTestStatus("$testLabel: add or select a config first")
            return
        }
        autoTestInFlight = true
        setAutoTestStatus("$testLabel running for ${compactProfileTitle(profile, fallback = config.kind.name)}...")
        val network = currentNetworkLabel()
        Thread {
            val summary = try {
                probeConfigSummary(config)
            } catch (e: Exception) {
                ConfigProbeSummary(
                    report = "Resolve/probe failed: ${e.message ?: e.javaClass.simpleName}",
                    okCount = 0,
                    failedCount = 1,
                    bestLatencyMs = null,
                    bestScore = null,
                    checkedAtEpochMs = System.currentTimeMillis()
                )
            }
            val updatedProfile = profile?.let { testedProfile ->
                runCatching {
                    profileStore.markTested(
                        profileId = testedProfile.id,
                        testedAtEpochMs = summary.checkedAtEpochMs,
                        success = summary.reachable,
                        network = network,
                        latencyMs = summary.bestLatencyMs,
                        score = summary.bestScore
                    )
                }.getOrNull()
            }
            val message = autoTestSummaryText(summary, prefix = testLabel)
            runOnUiThread {
                autoTestInFlight = false
                if (updatedProfile != null && (selectedProfileId == null || selectedProfileId == updatedProfile.id)) {
                    selectedProfile = updatedProfile
                    selectedProfileId = updatedProfile.id
                }
                setAutoTestStatus(message)
                if (::advancedDiagnostics.isInitialized) advancedDiagnostics.text = summary.report
                refreshProfileButtons(syncVerified = false)
                updateDashboardSummary()
            }
        }.start()
    }

    private fun rankSavedProfilesAndSelectBest(reason: String, autoSelect: Boolean = true) {
        val savedProfiles = runCatching { profileStore.listProfiles() }.getOrElse { error ->
            setAutoTestStatus("Could not load profiles for ping ranking: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        rankProfilesAndSelectBest(
            inputProfiles = savedProfiles,
            reason = reason,
            autoSelect = autoSelect,
            scopeLabel = "saved configs"
        )
    }

    private fun rankProfilesAndSelectBest(
        inputProfiles: List<VpnProfile>,
        reason: String,
        autoSelect: Boolean = true,
        scopeLabel: String = "configs"
    ) {
        if (autoTestsShouldPauseForLiveVpn()) {
            setAutoTestStatus("Ping ranking paused while VPN is running")
            return
        }
        if (autoTestInFlight) {
            setAutoTestStatus("A latency test is already running")
            return
        }
        val uniqueProfiles = inputProfiles.distinctBy { it.id }
        if (uniqueProfiles.isEmpty()) {
            setAutoTestStatus("Ping ranking: add configs first")
            return
        }
        val rankedInput = uniqueProfiles.sortedWith(profileRankingComparator()).take(MAX_AUTO_RANK_PROFILES)
        autoTestInFlight = true
        setAutoTestStatus("Ping testing ${rankedInput.size} ${scopeLabel.shortUi(32)}...")
        val network = currentNetworkLabel()
        Thread {
            val results = mutableListOf<ProfileProbeResult>()
            rankedInput.forEachIndexed { index, profile ->
                if (!autoTestInFlight || autoTestsShouldPauseForLiveVpn()) {
                    mainHandler.post {
                        autoTestInFlight = false
                        setAutoTestStatus("Ping ranking paused while VPN is running")
                        refreshAutoTestSummary()
                    }
                    return@Thread
                }
                mainHandler.post {
                    if (autoTestInFlight) {
                        setAutoTestStatus("Ping ${index + 1}/${rankedInput.size}: ${compactProfileTitle(profile)}")
                    }
                }
                val config = loadProfileConfigQuiet(profile)
                val summary = if (config == null) {
                    ConfigProbeSummary(
                        report = "Could not decrypt or parse ${profile.displayName}.",
                        okCount = 0,
                        failedCount = 1,
                        bestLatencyMs = null,
                        bestScore = null,
                        checkedAtEpochMs = System.currentTimeMillis()
                    )
                } else {
                    runCatching { probeConfigSummary(config) }.getOrElse { error ->
                        ConfigProbeSummary(
                            report = "Resolve/probe failed for ${profile.displayName}: ${error.message ?: error.javaClass.simpleName}",
                            okCount = 0,
                            failedCount = 1,
                            bestLatencyMs = null,
                            bestScore = null,
                            checkedAtEpochMs = System.currentTimeMillis()
                        )
                    }
                }
                val updatedProfile = runCatching {
                    profileStore.markTested(
                        profileId = profile.id,
                        testedAtEpochMs = summary.checkedAtEpochMs,
                        success = summary.reachable,
                        network = network,
                        latencyMs = summary.bestLatencyMs,
                        score = summary.bestScore
                    )
                }.getOrNull() ?: profile
                results += ProfileProbeResult(updatedProfile, config, summary)
            }
            val best = results.filter { it.summary.reachable }
                .minWithOrNull(compareBy<ProfileProbeResult> { it.summary.bestScore ?: Int.MAX_VALUE }
                    .thenBy { it.summary.bestLatencyMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.profile.favorite })
            val report = buildAutoRankingReport(results, best, reason)
            val message = when {
                best != null && autoSelect -> "Best ping selected: ${compactProfileTitle(best.profile)}${best.summary.bestLatencyMs?.let { " • ${it}ms" }.orEmpty()}"
                best != null -> "Best ping: ${compactProfileTitle(best.profile)}${best.summary.bestLatencyMs?.let { " • ${it}ms" }.orEmpty()}"
                else -> "Ping ranking found no reachable endpoints"
            }
            runOnUiThread {
                autoTestInFlight = false
                if (best != null && autoSelect) applyRankedProfileSelection(best)
                setAutoTestStatus(message)
                if (::advancedDiagnostics.isInitialized) advancedDiagnostics.text = report
                refreshProfileButtons(syncVerified = false)
                updateDashboardSummary()
                if (best != null && autoSelect) showSection(AppSection.HOME)
            }
        }.start()
    }

    private fun probeConfigSummary(config: ImportedConfig): ConfigProbeSummary {
        val report = buildResolveAndProbeReport(config)
        val okMatches = Regex("Probe: [^\\n]+ OK, latency (\\d+)ms, score ([0-9]+|n/a)")
            .findAll(report)
            .toList()
        val best = okMatches.mapNotNull { match ->
            val latency = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val score = match.groupValues[2].toIntOrNull() ?: latency.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            latency to score
        }.minWithOrNull(compareBy<Pair<Long, Int>> { it.second }.thenBy { it.first })
        val failedCount = report.lineSequence().count { it.contains(" failed", ignoreCase = true) }
        return ConfigProbeSummary(
            report = report,
            okCount = okMatches.size,
            failedCount = failedCount,
            bestLatencyMs = best?.first,
            bestScore = best?.second,
            checkedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun autoTestSummaryText(summary: ConfigProbeSummary, prefix: String): String = when {
        summary.okCount > 0 -> "$prefix passed: ${summary.okCount} reachable endpoint${if (summary.okCount == 1) "" else "s"}" +
            summary.bestLatencyMs?.let { " • best ${it}ms" }.orEmpty()
        summary.failedCount > 0 -> "$prefix finished: ${summary.failedCount} failed probe${if (summary.failedCount == 1) "" else "s"}"
        else -> "$prefix finished: see Settings diagnostics"
    }

    private fun applyRankedProfileSelection(result: ProfileProbeResult): Boolean {
        val config = result.config ?: loadProfileConfig(result.profile) ?: return false
        val refreshed = runCatching {
            profileStore.markTested(
                profileId = result.profile.id,
                testedAtEpochMs = result.summary.checkedAtEpochMs,
                success = result.summary.reachable,
                network = result.profile.lastTestNetwork,
                latencyMs = result.summary.bestLatencyMs,
                score = result.summary.bestScore
            )
        }.getOrNull() ?: result.profile
        importedConfig = config
        selectedProfileId = refreshed.id
        selectedProfile = refreshed
        activeConnectionProfileId = null
        return true
    }

    private fun loadProfileConfigQuiet(profile: VpnProfile): ImportedConfig? {
        val raw = runCatching { profileStore.loadRawConfig(profile.id) }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        return runCatching { ConfigImporter.parse(raw, profile.name) }.getOrNull()
    }

    private fun currentVisibleProfilesForTesting(limit: Int = MAX_AUTO_RANK_PROFILES): List<VpnProfile> {
        val allProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        normalizeLocationGroupFilter(groups)
        val query = locationSearchQuery.trim()
        val filtered = if (query.isBlank()) allProfiles else allProfiles.filter { matchesLocationSearch(it, query) }
        val scoped = profilesForLocationFilter(filtered, groups)
        val anchor = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull()
        return (listOfNotNull(anchor) + scoped)
            .distinctBy { it.id }
            .sortedWith(profileRankingComparator())
            .take(limit)
    }

    private fun showLatencyTestSheet(
        anchorProfile: VpnProfile?,
        candidates: List<VpnProfile>,
        title: String
    ) {
        val uniqueCandidates = (listOfNotNull(anchorProfile) + candidates)
            .distinctBy { it.id }
            .sortedWith(profileRankingComparator())
        val target = anchorProfile ?: uniqueCandidates.firstOrNull()
        showBottomSheet(
            title = title,
            subtitle = "Ping is quick. Real latency connects VPN and verifies outbound delay."
        ) { dialog ->
            addView(TextView(this@MainActivity).apply {
                text = "Ping test = fast TCP/TLS endpoint check before connecting. Real latency = start the VPN tunnel and measure the verified route."
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(4), dp(8), dp(4), dp(4))
            })
            if (target != null) {
                addView(bottomSheetActionRow("◷", "Ping test", "Quick ping for ${compactProfileTitle(target).shortUi(24)}") {
                    dialog.dismiss()
                    runPingTestForProfile(target)
                })
                addView(bottomSheetActionRow("✓", "Real latency test", "Connect this profile and verify true VPN delay") {
                    dialog.dismiss()
                    runRealLatencyTestForProfile(target)
                })
            } else {
                addView(TextView(this@MainActivity).apply {
                    text = "No saved config is available to test yet. Use + to add a config or subscription."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF64748B.toInt())
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                })
            }
            if (uniqueCandidates.size > 1) {
                val count = uniqueCandidates.take(MAX_AUTO_RANK_PROFILES).size
                addView(bottomSheetActionRow("★", "Ping-rank this list", "Quick-test $count configs and select the fastest reachable one") {
                    dialog.dismiss()
                    rankProfilesAndSelectBest(
                        inputProfiles = uniqueCandidates,
                        reason = "test sheet",
                        autoSelect = true,
                        scopeLabel = "visible configs"
                    )
                })
            }
        }
    }

    private fun selectProfileForTest(profile: VpnProfile): Boolean {
        val config = loadProfileConfig(profile) ?: return false
        importedConfig = config
        selectedProfileId = profile.id
        selectedProfile = profile
        refreshProfileButtons(syncVerified = false)
        updateDashboardSummary()
        return true
    }

    private fun runPingTestForProfile(profile: VpnProfile) {
        if (!selectProfileForTest(profile)) return
        autoTestSelectedConfig("manual-ping")
    }

    private fun runRealLatencyTestForProfile(profile: VpnProfile) {
        val hubBeforeSelection = currentHubStatus()
        val selectedBefore = selectedProfileId
        val activeBefore = activeConnectionProfileId
        val sameActiveProfile = activeBefore == profile.id || (activeBefore == null && selectedBefore == profile.id)
        if (isLiveState(hubBeforeSelection.state) && !sameActiveProfile) {
            status.text = "Stop the current VPN first, then run real latency for ${compactProfileTitle(profile)}."
            return
        }
        if (!selectProfileForTest(profile)) return
        val hub = currentHubStatus()
        if (sameActiveProfile && hub.state == VpnHubConnectionState.CONNECTED && hub.verified) {
            recordVerifiedProfileIfNeeded(hub)
            status.text = "Real latency verified: ${hub.latencyMs?.let { "${it}ms" } ?: "connected"}${hub.activeEngine?.let { " • ${engineLabel(it)}" }.orEmpty()}"
            refreshProfileButtons(syncVerified = false)
            updateDashboardSummary()
            return
        }
        if (sameActiveProfile && isLiveState(hub.state)) {
            status.text = "Real latency test is already running for ${compactProfileTitle(profile)}. Wait for verification to finish."
            showSection(AppSection.HOME)
            return
        }
        if (autoTestInFlight) autoTestInFlight = false
        status.text = "Starting real latency test for ${compactProfileTitle(profile)}. Android will verify after the tunnel connects."
        requestVpnPermission(PendingVpnAction.IMPORTED_ENGINE)
    }

    private fun buildAutoRankingReport(
        results: List<ProfileProbeResult>,
        best: ProfileProbeResult?,
        reason: String
    ): String {
        val sorted = results.sortedWith(profileProbeResultComparator())
        val lines = mutableListOf<String>()
        lines += "Ping ranking (${sorted.size} config${if (sorted.size == 1) "" else "s"}, reason: $reason)."
        lines += currentNetworkDiagnosticNote()
        lines += "Note: ping test ranks endpoint reachability only. Real latency is measured after the VPN tunnel connects."
        if (best != null) {
            lines += "Best now: ${compactProfileTitle(best.profile)}${best.summary.bestLatencyMs?.let { " • ${it}ms" }.orEmpty()}"
        }
        sorted.forEachIndexed { index, result ->
            val summary = result.summary
            val state = if (summary.reachable) {
                "OK${summary.bestLatencyMs?.let { " ${it}ms" }.orEmpty()}${summary.bestScore?.let { " score $it" }.orEmpty()}"
            } else {
                "failed"
            }
            val selected = if (result.profile.id == best?.profile?.id) " ← best" else ""
            lines += "${index + 1}. ${compactProfileTitle(result.profile)} — $state$selected"
        }
        return lines.joinToString("\n")
    }

    private fun profileProbeResultComparator(): Comparator<ProfileProbeResult> =
        compareByDescending<ProfileProbeResult> { it.summary.reachable }
            .thenBy { it.summary.bestScore ?: Int.MAX_VALUE }
            .thenBy { it.summary.bestLatencyMs ?: Long.MAX_VALUE }
            .thenByDescending { it.profile.favorite }
            .thenByDescending { it.profile.lastVerifiedEpochMs ?: 0L }

    private fun profileRankingComparator(): Comparator<VpnProfile> =
        compareByDescending<VpnProfile> { it.lastTestSuccess == true }
            .thenBy { it.lastTestScore ?: Int.MAX_VALUE }
            .thenBy { it.lastTestLatencyMs ?: Long.MAX_VALUE }
            .thenByDescending { it.lastVerifiedEpochMs ?: 0L }
            .thenByDescending { it.favorite }
            .thenByDescending { it.updatedAtEpochMs }

    private fun createLocationSearchCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(10), dp(8), dp(8), dp(8))
        background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 22)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(8))
        }
        addView(TextView(this@MainActivity).apply {
            text = "⌕"
            textSize = 20f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFF2563EB.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(40))
        })
        locationSearchInput = EditText(this@MainActivity).apply {
            hint = "Search locations or configs"
            setSingleLine(true)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(0xFF0F172A.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    locationSearchQuery = s?.toString().orEmpty()
                    refreshProfileButtons(syncVerified = false)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(locationSearchInput)
        addView(floatingTestButton(compact = true) {
            showLatencyTestSheet(
                anchorProfile = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull(),
                candidates = currentVisibleProfilesForTesting(),
                title = "Test visible configs"
            )
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        })
        addView(TextView(this@MainActivity).apply {
            text = "×"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFF64748B.toInt())
            background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { if (::locationSearchInput.isInitialized) locationSearchInput.setText("") }
        })
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = roundedBackground(0xEFFFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 24)
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
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setAllCaps(false)
        setLineSpacing(0f, 0.95f)
        setTextColor(0xFF0F172A.toInt())
        background = roundedBackground(0xFFE2E8F0.toInt(), 0xFFCBD5E1.toInt(), radiusDp = 22)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            0,
            dp(64),
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

    private fun floatingTestButton(compact: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = "◷"
        textSize = if (compact) 17f else 19f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFFFFFFFF.toInt())
        background = roundedBackground(0xFF2563EB.toInt(), 0xFF1D4ED8.toInt(), radiusDp = if (compact) 17 else 20)
        elevation = dp(2).toFloat()
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        contentDescription = "Test latency"
    }

    private fun settingsRow(
        icon: String,
        title: String,
        subtitle: String,
        valueView: TextView? = null,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 20)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(6), 0, dp(4))
        }
        addView(TextView(this@MainActivity).apply {
            text = icon
            textSize = 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFF2563EB.toInt())
            background = roundedBackground(0xFFEFF6FF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 16)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                setMargins(0, 0, dp(10), 0)
            }
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF0F172A.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF64748B.toInt())
            })
        })
        val trailing = valueView ?: TextView(this@MainActivity).apply {
            text = "›"
            textSize = 24f
            includeFontPadding = false
            setTextColor(0xFF94A3B8.toInt())
        }
        trailing.gravity = Gravity.CENTER
        trailing.typeface = Typeface.DEFAULT_BOLD
        if (valueView != null) {
            trailing.textSize = 11f
            trailing.setPadding(dp(10), dp(5), dp(10), dp(5))
            trailing.setTextColor(if (autoTestEnabled) 0xFFFFFFFF.toInt() else 0xFF475569.toInt())
            trailing.background = roundedBackground(
                fillColor = if (autoTestEnabled) 0xFF10B981.toInt() else 0xFFE2E8F0.toInt(),
                strokeColor = if (autoTestEnabled) 0xFF059669.toInt() else 0xFFCBD5E1.toInt(),
                radiusDp = 14
            )
            trailing.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                setMargins(dp(8), 0, 0, 0)
            }
        } else {
            trailing.layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(trailing)
        isClickable = true
        isFocusable = true
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
            "Hide advanced tools"
        } else {
            "Show advanced tools"
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
                    status.text = "No profile selected. Use the top + to add a config first."
                    showSection(AppSection.PROFILES)
                } else {
                    pauseAutoTestsForConnection("Auto test paused while connecting")
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
        if (::statLatencyText.isInitialized) statLatencyText.text = "◷  ${hub.latencyMs?.let { "${it} ms" } ?: "--"}\n     Ping"
        if (::statEngineText.isInitialized) statEngineText.text = "Engine\n${hub.activeEngine?.let { engineLabel(it) } ?: "Auto"}"
        if (::statDownText.isInitialized) statDownText.text = "↓  $down\n     Download"
        if (::statUpText.isInitialized) statUpText.text = "↑  $up\n     Upload"

        if (::protectionBadge.isInitialized) {
            val protectionText = when (hub.state) {
                VpnHubConnectionState.CONNECTED -> "✓  Protected  ›"
                VpnHubConnectionState.CONNECTING,
                VpnHubConnectionState.RUNNING_UNVERIFIED -> "✓  Checking  ›"
                VpnHubConnectionState.FAILED -> "!  Failed  ›"
                VpnHubConnectionState.IDLE,
                VpnHubConnectionState.STOPPED -> "✓  Offline  ›"
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
            liveStatsBadge.text = if (active) "$down / $up" else "0 B"
            liveStatsBadge.setTextColor(if (active) 0xFF2563EB.toInt() else 0xFF64748B.toInt())
        }

        primaryActionButton.setActive(active)
        updateSelectedProfileSummary()
        updateProfileActionButtons()
        refreshAutoTestSummary()
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
        val profile = activeConnectionProfileId?.let { profileStore.profile(it) } ?: selectedProfile
        if (::selectedProfileText.isInitialized) {
            selectedProfileText.text = if (profile == null) {
                "No profile selected yet. Use the top + or pick a saved profile."
            } else {
                selectedProfileSummary(profile)
            }
        }
        if (::homeProfileNameText.isInitialized) {
            if (::homeProfileIconText.isInitialized) homeProfileIconText.text = profile?.let { profileFlagOrIcon(it) } ?: "🌐"
            homeProfileNameText.text = profile?.let { compactProfileTitle(it).shortUi(24) } ?: "Choose location"
            homeProfileMetaText.text = profile?.let { homeProfileMeta(it) } ?: "Tap to pick"
        }
    }

    private fun homeProfileMeta(profile: VpnProfile): String {
        val subtitle = compactProfileSubtitle(profile)
        val health = profile.lastVerifiedLatencyMs?.let { "${it}ms verified" }
            ?: profile.lastTestMiniLabel()
        return listOfNotNull(subtitle, health).joinToString(" • ").ifBlank { "Ready" }.shortUi(30)
    }

    private fun compactProfileTitle(profile: VpnProfile?, fallback: String): String =
        profile?.let { compactProfileTitle(it) } ?: fallback.shortUi(24)

    private fun compactProfileTitle(profile: VpnProfile): String {
        val base = primaryProfileNameSegment(profile.displayName.cleanProfileLabel())
        val title = base.substringBefore("/")
            .substringBefore("(")
            .substringBefore("~")
            .replace("✨", "")
            .replace("✦", "")
            .replace("✅", "")
            .trim(' ', '•', '-', '·')
            .trim()
        return title.ifBlank { profile.endpoints.firstOrNull()?.host?.shortHost() ?: profile.kind.displayName }.shortUi(24)
    }

    private fun compactProfileSubtitle(profile: VpnProfile): String? {
        val base = primaryProfileNameSegment(profile.displayName.cleanProfileLabel())
        val afterSlash = base.substringAfter("/", "")
            .substringBefore("(")
            .substringBefore("~")
            .replace("✨", "")
            .replace("✦", "")
            .replace("✅", "")
            .trim(' ', '•', '-', '·')
            .trim()
            .takeIf { it.isNotBlank() }
        return afterSlash?.shortUi(18)
            ?: profile.endpoints.firstOrNull()?.let { "${it.host.shortHost().shortUi(16)}:${it.port}" }
    }

    private fun profileFlagOrIcon(profile: VpnProfile): String {
        val label = profile.displayName.cleanProfileLabel()
        return listOf("🇮🇷", "🇳🇱", "🇺🇸", "🇩🇪", "🇫🇷", "🇬🇧", "🇹🇷", "🇦🇪", "🇷🇺", "🇸🇬")
            .firstOrNull { label.contains(it) }
            ?: when (profile.kind) {
                VpnProfileKind.XRAY -> "✦"
                VpnProfileKind.WIREGUARD -> "◎"
                VpnProfileKind.OPENVPN -> "◉"
                VpnProfileKind.UNKNOWN -> "🌐"
            }
    }

    private fun profileRowSubtitle(profile: VpnProfile): String {
        val detail = compactProfileSubtitle(profile)?.shortUi(20)
        val health = profile.lastVerifiedLatencyMs?.let { "${it}ms verified" }
            ?: profile.lastTestMiniLabel()
        val network = profile.lastVerifiedNetwork?.takeIf { it.isNotBlank() }
            ?: profile.lastTestNetwork?.takeIf { it.isNotBlank() }
        return listOfNotNull(detail, health, network?.shortUi(9)).joinToString(" • ").ifBlank { "Saved config" }
    }

    private fun primaryProfileNameSegment(name: String): String {
        val segments = name.cleanProfileLabel().collapseLabelWhitespace()
            .split("•")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return segments.firstOrNull { segment ->
            val lower = segment.lowercase(java.util.Locale.US)
            segment.any { it.isLetterOrDigit() } &&
                !lower.contains("v2ray") &&
                !lower.contains("xray") &&
                !lower.contains("embedded") &&
                !lower.startsWith("v2ray_")
        } ?: segments.firstOrNull().orEmpty().ifBlank { name }
    }

    private fun showConfigSelectorSheet() {
        val profiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        showBottomSheet(
            title = "Choose location",
            subtitle = "Tap to select. Subscription groups stay separated."
        ) { dialog ->
            addView(bottomSheetActionRow("+", "Add config", "Clipboard, file, or subscription") {
                dialog.dismiss()
                showAddConfigMenu()
            })
            if (profiles.isEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = "No saved configs yet. Use + to add one."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF64748B.toInt())
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                })
                return@showBottomSheet
            }
            val listContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            addGroupedProfileRowsToSheet(listContainer, profiles, groups, dialog)
            addView(ScrollView(this@MainActivity).apply {
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (profiles.size > 5) dp(380) else ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(listContainer)
            })
        }
    }

    private fun addGroupedProfileRowsToSheet(
        container: LinearLayout,
        profiles: List<VpnProfile>,
        groups: List<SubscriptionGroup>,
        dialog: Dialog
    ) {
        val shownIds = mutableSetOf<String>()
        var shown = 0
        fun addSheetSection(title: String, sectionProfiles: List<VpnProfile>) {
            if (shown >= MAX_PROFILE_SHEET_CHOICES) return
            val unique = sectionProfiles.filter { it.id !in shownIds }
            if (unique.isEmpty()) return
            val limited = unique.take(MAX_PROFILE_SHEET_CHOICES - shown)
            container.addView(createLocationSectionLabel(title, limited.size))
            limited.forEach { profile ->
                shownIds += profile.id
                shown++
                container.addView(profileListRow(
                    profile = profile,
                    compact = true,
                    onSelect = {
                        dialog.dismiss()
                        loadProfile(profile)
                    },
                    onActions = {
                        dialog.dismiss()
                        showProfileActionsSheet(profile)
                    }
                ))
            }
        }

        val subscriptionProfileIds = groups.flatMap { it.profileIds }.toSet()
        addSheetSection("Recommended", profiles.filter { it.lastTestSuccess == true }.sortedWith(profileRankingComparator()).take(MAX_RECOMMENDED_PROFILES))
        if (groups.isNotEmpty()) {
            container.addView(createLocationSectionLabel("Subscription profiles", groups.size))
            groups.take(MAX_SUBSCRIPTION_GROUP_BUTTONS).forEach { group ->
                val groupCount = subscriptionProfiles(group).size
                container.addView(bottomSheetActionRow("▦", group.displayName.cleanProfileLabel().shortUi(26), "$groupCount configs • open grouped list") {
                    dialog.dismiss()
                    showSubscriptionGroupProfilesSheet(group)
                })
            }
        }
        addSheetSection("Manual configs", profiles.filter { it.id !in subscriptionProfileIds }.sortedWith(profileRankingComparator()))
        if (profiles.size > shown) {
            container.addView(TextView(this@MainActivity).apply {
                text = "Subscription configs are separated into tabs. Use Locations search for a specific config."
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(10), dp(10), dp(10), dp(4))
            })
        }
    }

    private fun showProfileActionsSheet(profile: VpnProfile) {
        selectedProfile = profile
        selectedProfileId = profile.id
        updateDashboardSummary()
        showBottomSheet(
            title = compactProfileTitle(profile),
            subtitle = compactProfileSubtitle(profile)?.shortUi(34) ?: "Saved config"
        ) { dialog ->
            addView(bottomSheetActionRow("✓", "Select", "Use this config on Home") {
                dialog.dismiss()
                loadProfile(profile)
            })
            addView(bottomSheetActionRow("◷", "Test latency", "Choose ping or real VPN latency") {
                dialog.dismiss()
                showLatencyTestSheet(
                    anchorProfile = profile,
                    candidates = listOf(profile),
                    title = "Test ${compactProfileTitle(profile).shortUi(18)}"
                )
            })
            addView(bottomSheetActionRow("✎", "Rename", "Change display name") {
                dialog.dismiss()
                promptRenameSelectedProfile()
            })
            addView(bottomSheetActionRow("★", if (profile.favorite) "Unfavorite" else "Favorite", "Prioritize this config") {
                dialog.dismiss()
                toggleSelectedFavorite()
            })
            addView(bottomSheetActionRow("ℹ", "Details", "Open Locations for metadata") {
                dialog.dismiss()
                showSection(AppSection.PROFILES)
            })
            addView(bottomSheetActionRow("×", "Delete", "Remove encrypted local config") {
                dialog.dismiss()
                confirmDeleteSelectedProfile()
            })
        }
    }

    private fun showBottomSheet(
        title: String,
        subtitle: String? = null,
        buildContent: LinearLayout.(Dialog) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(18), dp(10), dp(18), navigationBarBottomPadding() + dp(14))
            background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 30)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(this@MainActivity).apply {
                text = ""
                background = roundedBackground(0xFFCBD5E1.toInt(), 0xFFCBD5E1.toInt(), radiusDp = 4)
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(5)).apply {
                    setMargins(0, 0, 0, dp(12))
                }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = title.shortUi(42)
                        textSize = 18f
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(0xFF0F172A.toInt())
                    })
                    subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                        addView(TextView(this@MainActivity).apply {
                            text = sub.shortUi(70)
                            textSize = 12f
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(0xFF64748B.toInt())
                        })
                    }
                })
                addView(TextView(this@MainActivity).apply {
                    text = "×"
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(0xFF64748B.toInt())
                    background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 18)
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        setMargins(dp(10), 0, 0, 0)
                    }
                    setOnClickListener { dialog.dismiss() }
                })
            })
            buildContent(dialog)
        }
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun bottomSheetActionRow(icon: String, title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(8), 0, 0)
            }
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFF2563EB.toInt())
                background = roundedBackground(0xFFEFF6FF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 15)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    setMargins(0, 0, dp(10), 0)
                }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF0F172A.toInt())
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF64748B.toInt())
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 24f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFF94A3B8.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun profileListRow(
        profile: VpnProfile,
        compact: Boolean,
        onSelect: () -> Unit,
        onActions: () -> Unit = { showProfileActionsSheet(profile) }
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = roundedBackground(
                fillColor = if (profile.id == selectedProfileId) 0xFFEFF6FF.toInt() else 0xFFF8FAFC.toInt(),
                strokeColor = if (profile.id == selectedProfileId) 0xFF93C5FD.toInt() else 0xFFE2E8F0.toInt(),
                radiusDp = if (compact) 16 else 20
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (compact) dp(58) else dp(68)
            ).apply {
                setMargins(0, dp(6), 0, 0)
            }
            addView(TextView(this@MainActivity).apply {
                text = profileFlagOrIcon(profile)
                textSize = if (compact) 18f else 20f
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = roundedBackground(0xFFE0F2FE.toInt(), 0xFFBAE6FD.toInt(), radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    setMargins(0, 0, dp(10), 0)
                }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = compactProfileTitle(profile)
                    textSize = if (compact) 13f else 14f
                    typeface = if (profile.id == selectedProfileId) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF0F172A.toInt())
                })
                addView(TextView(this@MainActivity).apply {
                    text = profileRowSubtitle(profile)
                    textSize = if (compact) 10f else 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF64748B.toInt())
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = profileStatusLabel(profile)
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(profileStatusTextColor(profile))
                background = roundedBackground(profileStatusFillColor(profile), profileStatusStrokeColor(profile), radiusDp = 12)
                setPadding(dp(7), dp(4), dp(7), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                    setMargins(dp(6), 0, dp(4), 0)
                }
            })
            addView(floatingTestButton(compact = true) {
                showLatencyTestSheet(
                    anchorProfile = profile,
                    candidates = listOf(profile),
                    title = "Test ${compactProfileTitle(profile).shortUi(18)}"
                )
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = if (profile.id == selectedProfileId) "✓" else "⋯"
                textSize = if (profile.id == selectedProfileId) 15f else 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (profile.id == selectedProfileId) 0xFF2563EB.toInt() else 0xFF94A3B8.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT)
                setOnClickListener { onActions() }
            })
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelect() }
            setOnLongClickListener {
                onActions()
                true
            }
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

    private fun profileStatusLabel(profile: VpnProfile): String = when {
        profile.id == selectedProfileId -> "Selected"
        profile.lastVerifiedEpochMs != null -> "Good"
        profile.lastTestSuccess == true -> "Fast"
        profile.favorite -> "Fav"
        profile.lastTestSuccess == false -> "Fail"
        else -> "New"
    }

    private fun profileStatusFillColor(profile: VpnProfile): Int = when {
        profile.id == selectedProfileId -> 0xFFEFF6FF.toInt()
        profile.lastVerifiedEpochMs != null -> 0xFFD1FAE5.toInt()
        profile.lastTestSuccess == true -> 0xFFDCFCE7.toInt()
        profile.favorite -> 0xFFFEF3C7.toInt()
        profile.lastTestSuccess == false -> 0xFFFEE2E2.toInt()
        else -> 0xFFF1F5F9.toInt()
    }

    private fun profileStatusStrokeColor(profile: VpnProfile): Int = when {
        profile.id == selectedProfileId -> 0xFF93C5FD.toInt()
        profile.lastVerifiedEpochMs != null -> 0xFF6EE7B7.toInt()
        profile.lastTestSuccess == true -> 0xFF86EFAC.toInt()
        profile.favorite -> 0xFFFCD34D.toInt()
        profile.lastTestSuccess == false -> 0xFFFCA5A5.toInt()
        else -> 0xFFE2E8F0.toInt()
    }

    private fun profileStatusTextColor(profile: VpnProfile): Int = when {
        profile.id == selectedProfileId -> 0xFF2563EB.toInt()
        profile.lastVerifiedEpochMs != null -> 0xFF047857.toInt()
        profile.lastTestSuccess == true -> 0xFF15803D.toInt()
        profile.favorite -> 0xFF92400E.toInt()
        profile.lastTestSuccess == false -> 0xFFB91C1C.toInt()
        else -> 0xFF64748B.toInt()
    }

    private fun matchesLocationSearch(profile: VpnProfile, query: String): Boolean {
        val terms = query.replace(Regex("\\s+"), " ").trim().lowercase(java.util.Locale.US)
            .split(" ")
            .filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val haystack = listOfNotNull(
            profile.displayName.cleanProfileLabel(),
            compactProfileTitle(profile),
            compactProfileSubtitle(profile),
            profile.kind.displayName,
            profile.lastVerifiedNetwork,
            profile.lastTestNetwork,
            profile.lastTestLabel(),
            profile.endpoints.joinToString(" ") { "${it.protocol} ${it.host}:${it.port}" }
        ).joinToString(" ").lowercase(java.util.Locale.US)
        return terms.all { haystack.contains(it) }
    }

    private fun createLocationSectionLabel(title: String, count: Int): TextView = TextView(this).apply {
        text = "$title  $count"
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(0xFF64748B.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(12), 0, dp(2))
        }
    }

    private fun addLocationSection(container: LinearLayout, title: String, profiles: List<VpnProfile>, remaining: Int): Int {
        if (profiles.isEmpty() || remaining <= 0) return 0
        val shown = profiles.take(remaining)
        container.addView(createLocationSectionLabel(title, shown.size))
        shown.forEach { profile ->
            container.addView(profileListRow(profile, compact = false, onSelect = { loadProfile(profile) }))
        }
        return shown.size
    }

    private fun selectedProfileSummary(profile: VpnProfile): String {
        val favorite = if (profile.favorite) "★ " else ""
        val subtitle = compactProfileSubtitle(profile)?.shortUi(28) ?: "Saved config"
        val verified = profile.lastVerifiedLabel()?.shortUi(32)
        return listOfNotNull(
            "$favorite${compactProfileTitle(profile).shortUi(30)}",
            subtitle,
            verified
        ).joinToString("\n")
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
        primaryActionButton.setActive(false)
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
        if (::settingsConnectionSummaryText.isInitialized) {
            val parts = listOfNotNull(
                hub.title,
                if (hub.verified) "Verified" else "Not verified",
                hub.latencyMs?.let { "${it}ms" },
                hub.activeEngine?.let { engineLabel(it) }
            )
            settingsConnectionSummaryText.text = parts.joinToString(" • ")
        }
        if (::advancedDiagnostics.isInitialized) {
            advancedDiagnostics.text = engineDiagnosticsText(wg, xray)
        }
    }

    private fun showDiagnosticsLogSheet() {
        val log = if (::advancedDiagnostics.isInitialized) {
            advancedDiagnostics.text?.toString().orEmpty().ifBlank { "No diagnostics yet." }
        } else {
            "No diagnostics yet."
        }
        showBottomSheet(
            title = "Diagnostics log",
            subtitle = "Technical details for troubleshooting."
        ) { dialog ->
            addView(ScrollView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(420)
                ).apply {
                    setMargins(0, dp(10), 0, dp(8))
                }
                addView(TextView(this@MainActivity).apply {
                    text = log
                    textSize = 12f
                    setTextColor(0xFF334155.toInt())
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
                })
            })
            addView(bottomSheetActionRow("✓", "Close", "Return to Settings") {
                dialog.dismiss()
            })
        }
    }

    private fun showKillSwitchInfoSheet() {
        showBottomSheet(
            title = "Kill switch",
            subtitle = "Android controls this at system level."
        ) { dialog ->
            addView(TextView(this@MainActivity).apply {
                text = "For strict leak protection, open Android VPN settings for this app and enable Always-on VPN and Block connections without VPN. This app should not fake a kill switch toggle until it can enforce it reliably."
                textSize = 13f
                setTextColor(0xFF334155.toInt())
                setPadding(dp(8), dp(12), dp(8), dp(8))
            })
            addView(bottomSheetActionRow("✓", "Got it", "Keep settings honest and enforceable") {
                dialog.dismiss()
            })
        }
    }

    private fun showAddConfigMenu() {
        showBottomSheet(
            title = "Add config",
            subtitle = "Only use your own or provider-approved configs."
        ) { dialog ->
            addView(bottomSheetActionRow("⌘", "Paste from clipboard", "Config link, subscription URL, or subscription text") {
                dialog.dismiss()
                importConfigFromClipboard()
            })
            addView(bottomSheetActionRow("□", "Import from file", "Pick a .conf, .ovpn, or text file") {
                dialog.dismiss()
                openConfigPicker()
            })
            addView(bottomSheetActionRow("↻", "Add subscription URL", "Encrypted provider subscription group") {
                dialog.dismiss()
                promptAddSubscriptionGroup()
            })
            addView(bottomSheetActionRow("◷", "Load latest saved", "Select the newest local profile") {
                dialog.dismiss()
                loadLatestProfile()
            })
        }
    }

    private fun promptAddSubscriptionGroup() {
        showBottomSheet(
            title = "Add subscription group",
            subtitle = "Encrypted local URL. Never shown in diagnostics."
        ) { dialog ->
            addView(TextView(this@MainActivity).apply {
                text = "Only paste your own or provider-approved subscription URL. The app stores it encrypted on this phone."
                textSize = 12.5f
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(8), dp(10), dp(8), dp(6))
            })
            val nameInput = EditText(this@MainActivity).apply {
                hint = "Group name, e.g. Provider A"
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(true)
                setPadding(dp(14), 0, dp(14), 0)
                background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54)
                ).apply {
                    setMargins(0, dp(8), 0, dp(8))
                }
            }
            val urlInput = EditText(this@MainActivity).apply {
                hint = "https://provider.example/sub/..."
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
                setPadding(dp(14), 0, dp(14), 0)
                background = roundedBackground(0xFFF8FAFC.toInt(), 0xFFE2E8F0.toInt(), radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54)
                ).apply {
                    setMargins(0, 0, 0, dp(8))
                }
            }
            addView(nameInput)
            addView(urlInput)
            addView(bottomSheetActionRow("↻", "Fetch subscription", "Download profiles and save them encrypted") {
                val url = urlInput.text?.toString().orEmpty().trim()
                val name = nameInput.text?.toString().orEmpty().trim().ifBlank { "Subscription" }
                if (url.isBlank()) {
                    status.text = "Subscription URL is empty."
                    return@bottomSheetActionRow
                }
                dialog.dismiss()
                addOrRefreshSubscriptionGroup(name, url, existingGroup = null)
            })
            addView(bottomSheetActionRow("×", "Cancel", "Close without saving") {
                dialog.dismiss()
            })
        }
    }

    private fun refreshSubscriptionGroup(group: SubscriptionGroup) {
        val url = profileStore.loadSubscriptionUrl(group.id)
        if (url.isNullOrBlank()) {
            status.text = "${group.displayName} was imported from clipboard and has no refresh URL. Copy the subscription again and use Paste from clipboard to refresh it."
            return
        }
        addOrRefreshSubscriptionGroup(group.displayName, url, existingGroup = group)
    }

    private fun addOrRefreshSubscriptionGroup(
        name: String,
        url: String,
        existingGroup: SubscriptionGroup?
    ) {
        if (url.isBlank()) {
            status.text = "Subscription URL is empty."
            return
        }
        status.text = "Fetching subscription group ${name.shortUi(28)}..."
        Thread {
            val result = runCatching { syncSubscriptionGroupBlocking(name, url, existingGroup) }
            runOnUiThread {
                result.fold(
                    onSuccess = { sync ->
                        selectedLocationGroupFilter = sync.group.id
                        sync.profiles.firstOrNull()?.let { profile ->
                            selectedProfile = profile
                            selectedProfileId = profile.id
                            importedConfig = loadProfileConfig(profile)
                        }
                        refreshProfileButtons()
                        updateDashboardSummary()
                        status.text = "Subscription group ${sync.group.displayName.shortUi(28)} synced: ${sync.profiles.size} profiles saved" +
                            if (sync.skippedCount > 0) ", ${sync.skippedCount} skipped." else "."
                        showSection(AppSection.PROFILES)
                        maybeAutoRankBestProfile("subscription")
                    },
                    onFailure = { error ->
                        status.text = "Subscription sync failed: ${error.message ?: error.javaClass.simpleName}"
                        refreshProfileButtons()
                    }
                )
            }
        }.start()
    }

    private fun addClipboardSubscriptionGroup(name: String, subscriptionText: String) {
        status.text = "Importing subscription from clipboard..."
        Thread {
            val result = runCatching { syncClipboardSubscriptionGroupBlocking(name, subscriptionText) }
            runOnUiThread {
                result.fold(
                    onSuccess = { sync ->
                        selectedLocationGroupFilter = sync.group.id
                        sync.profiles.firstOrNull()?.let { profile ->
                            selectedProfile = profile
                            selectedProfileId = profile.id
                            importedConfig = loadProfileConfig(profile)
                        }
                        refreshProfileButtons()
                        updateDashboardSummary()
                        status.text = "Clipboard subscription ${sync.group.displayName.shortUi(28)} imported: ${sync.profiles.size} profiles saved" +
                            if (sync.skippedCount > 0) ", ${sync.skippedCount} skipped." else "."
                        showSection(AppSection.PROFILES)
                        maybeAutoRankBestProfile("clipboard subscription")
                    },
                    onFailure = { error ->
                        status.text = "Clipboard subscription import failed: ${error.message ?: error.javaClass.simpleName}"
                        refreshProfileButtons()
                    }
                )
            }
        }.start()
    }

    private fun syncSubscriptionGroupBlocking(
        name: String,
        url: String,
        existingGroup: SubscriptionGroup?
    ): SubscriptionSyncResult {
        val subscriptionText = fetchSubscriptionText(url)
        val storedGroup = profileStore.saveSubscriptionGroup(existingGroup?.displayName ?: name, url)
        return syncSubscriptionTextIntoGroup(storedGroup, subscriptionText, resultVerb = "Synced")
    }

    private fun syncClipboardSubscriptionGroupBlocking(
        name: String,
        subscriptionText: String
    ): SubscriptionSyncResult {
        val storedGroup = profileStore.saveClipboardSubscriptionGroup(name, subscriptionText)
        return syncSubscriptionTextIntoGroup(storedGroup, subscriptionText, resultVerb = "Imported from clipboard")
    }

    private fun syncSubscriptionTextIntoGroup(
        storedGroup: SubscriptionGroup,
        subscriptionText: String,
        resultVerb: String
    ): SubscriptionSyncResult {
        val links = V2RaySubscriptionParser.extractLinks(subscriptionText)
        if (links.isEmpty()) {
            throw ConfigParseException("Subscription contains no supported vless/vmess/trojan/ss links.")
        }

        val savedProfiles = mutableListOf<VpnProfile>()
        var skipped = 0
        links.take(MAX_SUBSCRIPTION_LINKS).forEachIndexed { index, link ->
            val saved = runCatching {
                val config = ConfigImporter.parse(link)
                val displayName = config.name?.takeIf { it.isNotBlank() } ?: "${storedGroup.displayName} #${index + 1}"
                val profileId = profileStore.stableSubscriptionProfileId(storedGroup.id, link)
                profileStore.saveImportedConfig(config, displayName, stableProfileId = profileId)
            }.getOrNull()
            if (saved == null) skipped++ else savedProfiles += saved
        }
        skipped += (links.size - links.take(MAX_SUBSCRIPTION_LINKS).size).coerceAtLeast(0)
        if (savedProfiles.isEmpty()) {
            throw ConfigParseException("Subscription was read, but none of its links could be imported.")
        }

        val resultText = "$resultVerb ${savedProfiles.size}/${links.size} profiles"
        val updatedGroup = profileStore.markSubscriptionSynced(
            groupId = storedGroup.id,
            profileIds = savedProfiles.map { it.id },
            result = resultText
        ) ?: storedGroup
        return SubscriptionSyncResult(updatedGroup, savedProfiles, links.size, skipped)
    }

    private fun fetchSubscriptionText(urlText: String): String {
        val parsedUrl = URL(urlText.trim())
        val protocol = parsedUrl.protocol.lowercase()
        if (protocol != "https" && protocol != "http") {
            throw ConfigParseException("Subscription URL must start with https:// or http://")
        }
        val connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = SUBSCRIPTION_TIMEOUT_MS
            readTimeout = SUBSCRIPTION_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VPN-Hub-Android/1.0")
            setRequestProperty("Accept", "text/plain, application/octet-stream, */*")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ConfigParseException("Subscription server returned HTTP $code")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SUBSCRIPTION_BYTES) {
                        throw ConfigParseException("Subscription is too large for this preview build.")
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
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
            status.text = "Clipboard is empty. Copy a config link, subscription URL, OpenVPN, or WireGuard config first."
            return
        }

        if (looksLikeSubscriptionUrl(clipText)) {
            addOrRefreshSubscriptionGroup(subscriptionNameFromUrl(clipText), clipText, existingGroup = null)
            return
        }

        val subscriptionLinks = V2RaySubscriptionParser.extractLinks(clipText)
        val directSingleLink = subscriptionLinks.size == 1 && startsWithSingleV2RayLink(clipText)
        if (subscriptionLinks.isNotEmpty() && !directSingleLink) {
            addClipboardSubscriptionGroup("Clipboard subscription", clipText)
            return
        }

        importConfigText(clipText, "clipboard")
    }

    private fun looksLikeSubscriptionUrl(text: String): Boolean {
        val candidate = text.trim()
        if (candidate.contains(Regex("\\s"))) return false
        return runCatching {
            val parsed = URL(candidate)
            parsed.protocol.equals("https", ignoreCase = true) || parsed.protocol.equals("http", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun subscriptionNameFromUrl(text: String): String = runCatching {
        val host = URL(text.trim()).host
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        host?.substringBefore('.')?.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
            ?: "Clipboard subscription"
    }.getOrDefault("Clipboard subscription")

    private fun startsWithSingleV2RayLink(text: String): Boolean {
        val normalized = text.trim()
        return listOf("vless://", "vmess://", "trojan://", "ss://").any { prefix ->
            normalized.startsWith(prefix, ignoreCase = true)
        } && !normalized.contains(Regex("\\s"))
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
            maybeAutoTestSelectedConfig("import")
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
            status.text = "No saved profiles yet. Use the top + to add one."
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
        val allProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        normalizeLocationGroupFilter(groups)
        val query = locationSearchQuery.trim()
        val profiles = allProfiles.filter { matchesLocationSearch(it, query) }
        val scopedProfiles = profilesForLocationFilter(profiles, groups)
        refreshSubscriptionGroupButtons(groups, allProfiles, profiles)

        profileListContainer.addView(TextView(this).apply {
            val scope = locationFilterLabel(groups)
            text = when {
                allProfiles.isEmpty() -> "No saved configs yet."
                query.isNotBlank() -> "$scope search (${scopedProfiles.size}/${allProfiles.size})"
                else -> "$scope configs (${scopedProfiles.size})"
            }
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(4), 0, dp(4))
        })
        if (allProfiles.isNotEmpty() && scopedProfiles.isEmpty()) {
            profileListContainer.addView(TextView(this).apply {
                text = when {
                    query.isNotBlank() -> "No matching configs in ${locationFilterLabel(groups)}. Try another tab, country, operator, or host."
                    selectedLocationGroupFilter == LOCATION_FILTER_MANUAL -> "No manual configs. Subscription configs are in their own tabs above."
                    selectedLocationGroupFilter != LOCATION_FILTER_ALL -> "This subscription profile has no saved configs yet. Refresh it or paste the subscription again."
                    else -> "No configs in this tab yet."
                }
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }

        val shownIds = mutableSetOf<String>()
        var shown = 0
        fun addUniqueSection(title: String, sectionProfiles: List<VpnProfile>) {
            if (shown >= MAX_PROFILE_BUTTONS) return
            val unique = sectionProfiles.filter { it.id !in shownIds }
            val limited = unique.take(MAX_PROFILE_BUTTONS - shown)
            if (limited.isEmpty()) return
            shownIds.addAll(limited.map { it.id })
            shown += addLocationSection(profileListContainer, title, limited, MAX_PROFILE_BUTTONS - shown)
        }

        val rankedScoped = scopedProfiles.sortedWith(profileRankingComparator())
        when (selectedLocationGroupFilter) {
            LOCATION_FILTER_ALL -> {
                if (query.isBlank()) {
                    addUniqueSection("Recommended", rankedScoped.filter { it.lastTestSuccess == true }.take(MAX_RECOMMENDED_PROFILES))
                    addUniqueSection("All configs", rankedScoped)
                } else {
                    addUniqueSection("Search results", rankedScoped)
                }
            }
            LOCATION_FILTER_MANUAL -> addUniqueSection(
                if (query.isBlank()) "Manual configs" else "Manual results",
                rankedScoped
            )
            else -> addUniqueSection(
                "Subscription • ${locationFilterLabel(groups).shortUi(24)}",
                rankedScoped
            )
        }

        if (scopedProfiles.size > shown) {
            profileListContainer.addView(TextView(this).apply {
                text = "Showing $shown of ${scopedProfiles.size} in ${locationFilterLabel(groups)}. Use search or another tab to narrow the list."
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(10), dp(10), dp(10), dp(4))
            })
        }
        updateDashboardSummary()
    }

    private fun normalizeLocationGroupFilter(groups: List<SubscriptionGroup>) {
        if (selectedLocationGroupFilter == LOCATION_FILTER_ALL || selectedLocationGroupFilter == LOCATION_FILTER_MANUAL) return
        if (groups.none { it.id == selectedLocationGroupFilter }) {
            selectedLocationGroupFilter = LOCATION_FILTER_ALL
        }
    }

    private fun profilesForLocationFilter(
        profiles: List<VpnProfile>,
        groups: List<SubscriptionGroup>
    ): List<VpnProfile> {
        val subscriptionProfileIds = groups.flatMap { it.profileIds }.toSet()
        val profileById = profiles.associateBy { it.id }
        return when (selectedLocationGroupFilter) {
            LOCATION_FILTER_ALL -> profiles
            LOCATION_FILTER_MANUAL -> profiles.filter { it.id !in subscriptionProfileIds }
            else -> groups.firstOrNull { it.id == selectedLocationGroupFilter }
                ?.profileIds
                ?.mapNotNull { profileById[it] }
                .orEmpty()
        }
    }

    private fun locationFilterLabel(groups: List<SubscriptionGroup>): String = when (selectedLocationGroupFilter) {
        LOCATION_FILTER_ALL -> "All"
        LOCATION_FILTER_MANUAL -> "Manual"
        else -> groups.firstOrNull { it.id == selectedLocationGroupFilter }
            ?.displayName
            ?.cleanProfileLabel()
            ?.ifBlank { "Subscription" }
            ?: "Subscription"
    }

    private fun refreshSubscriptionGroupButtons(
        groups: List<SubscriptionGroup>,
        allProfiles: List<VpnProfile>,
        filteredProfiles: List<VpnProfile>
    ) {
        if (!::subscriptionGroupContainer.isInitialized) return
        subscriptionGroupContainer.removeAllViews()
        normalizeLocationGroupFilter(groups)
        val allProfileIds = allProfiles.map { it.id }.toSet()
        val subscriptionProfileIds = groups.flatMap { it.profileIds }.toSet()
        val manualCount = allProfiles.count { it.id !in subscriptionProfileIds }
        val filteredCount = profilesForLocationFilter(filteredProfiles, groups).size
        subscriptionGroupContainer.addView(TextView(this).apply {
            text = if (groups.isEmpty()) {
                "Profile tabs: add subscriptions with +"
            } else {
                "Profile tabs  ${groups.size} subscription${if (groups.size == 1) "" else "s"}"
            }
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(6), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(2), dp(4), dp(2), dp(4))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tabRow.addView(locationFilterTab("All", allProfiles.size, selectedLocationGroupFilter == LOCATION_FILTER_ALL) {
            selectedLocationGroupFilter = LOCATION_FILTER_ALL
            refreshProfileButtons(syncVerified = false)
        })
        if (manualCount > 0 || groups.isEmpty()) {
            tabRow.addView(locationFilterTab("Manual", manualCount, selectedLocationGroupFilter == LOCATION_FILTER_MANUAL) {
                selectedLocationGroupFilter = LOCATION_FILTER_MANUAL
                refreshProfileButtons(syncVerified = false)
            })
        }
        groups.take(MAX_SUBSCRIPTION_GROUP_BUTTONS).forEach { group ->
            val groupCount = group.profileIds.count { it in allProfileIds }
            tabRow.addView(locationFilterTab(group.displayName.cleanProfileLabel().shortUi(16), groupCount, selectedLocationGroupFilter == group.id) {
                selectedLocationGroupFilter = group.id
                refreshProfileButtons(syncVerified = false)
            })
        }
        subscriptionGroupContainer.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(tabRow)
        })
        groups.firstOrNull { it.id == selectedLocationGroupFilter }?.let { group ->
            subscriptionGroupContainer.addView(selectedSubscriptionGroupRow(group, filteredCount))
        } ?: if (groups.isNotEmpty()) {
            subscriptionGroupContainer.addView(TextView(this).apply {
                text = "All shows a capped list. Tap a subscription tab to view only that profile queue."
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(2))
            })
        }
        if (groups.size > MAX_SUBSCRIPTION_GROUP_BUTTONS) {
            subscriptionGroupContainer.addView(TextView(this).apply {
                text = "Showing ${MAX_SUBSCRIPTION_GROUP_BUTTONS} of ${groups.size} subscription tabs."
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(2))
            })
        }
    }

    private fun locationFilterTab(
        label: String,
        count: Int,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = "${label.shortUi(18)} ($count)"
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        background = roundedBackground(
            fillColor = if (selected) 0xFF2563EB.toInt() else 0xFFF8FAFC.toInt(),
            strokeColor = if (selected) 0xFF1D4ED8.toInt() else 0xFFD8EAFE.toInt(),
            radiusDp = 18
        )
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun selectedSubscriptionGroupRow(group: SubscriptionGroup, visibleCount: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xFFEFF6FF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(5), 0, dp(2))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showSubscriptionGroupProfilesSheet(group) }
            addView(TextView(this@MainActivity).apply {
                text = "▦"
                textSize = 17f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFF2563EB.toInt())
                background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 14)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(0, 0, dp(8), 0) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = group.displayName.cleanProfileLabel().shortUi(28)
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF0F172A.toInt())
                })
                addView(TextView(this@MainActivity).apply {
                    text = "$visibleCount visible • ${subscriptionGroupSubtitle(group).shortUi(44)}"
                    textSize = 10.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF64748B.toInt())
                })
            })
            addView(floatingTestButton(compact = true) {
                val profiles = subscriptionProfiles(group)
                showLatencyTestSheet(
                    anchorProfile = profiles.sortedWith(profileRankingComparator()).firstOrNull(),
                    candidates = profiles,
                    title = "Test ${group.displayName.shortUi(18)}"
                )
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(5), 0, dp(5), 0) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "↻"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFF2563EB.toInt())
                background = roundedBackground(0xFFFFFFFF.toInt(), 0xFFD8EAFE.toInt(), radiusDp = 14)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                isClickable = true
                isFocusable = true
                setOnClickListener { refreshSubscriptionGroup(group) }
            })
        }

    private fun subscriptionProfiles(group: SubscriptionGroup): List<VpnProfile> =
        group.profileIds.mapNotNull { id -> profileStore.profile(id) }

    private fun showSubscriptionGroupProfilesSheet(group: SubscriptionGroup) {
        val profiles = subscriptionProfiles(group).sortedWith(profileRankingComparator())
        showBottomSheet(
            title = group.displayName.cleanProfileLabel().shortUi(32),
            subtitle = "${profiles.size} configs in this subscription profile"
        ) { dialog ->
            addView(bottomSheetActionRow("◷", "Test this subscription", "Ping-rank the best ${profiles.take(MAX_AUTO_RANK_PROFILES).size} configs") {
                dialog.dismiss()
                showLatencyTestSheet(
                    anchorProfile = profiles.firstOrNull(),
                    candidates = profiles,
                    title = "Test ${group.displayName.shortUi(18)}"
                )
            })
            addView(bottomSheetActionRow("↻", "Refresh subscription", subscriptionGroupSubtitle(group)) {
                dialog.dismiss()
                refreshSubscriptionGroup(group)
            })
            if (profiles.isEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = "No configs are saved in this group yet. Refresh it or paste the subscription again."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF64748B.toInt())
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                })
                return@showBottomSheet
            }
            val listContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            profiles.take(MAX_GROUP_PROFILE_PREVIEW).forEach { profile ->
                listContainer.addView(profileListRow(
                    profile = profile,
                    compact = true,
                    onSelect = {
                        dialog.dismiss()
                        loadProfile(profile)
                    },
                    onActions = {
                        dialog.dismiss()
                        showProfileActionsSheet(profile)
                    }
                ))
            }
            if (profiles.size > MAX_GROUP_PROFILE_PREVIEW) {
                listContainer.addView(TextView(this@MainActivity).apply {
                    text = "Showing ${MAX_GROUP_PROFILE_PREVIEW} best of ${profiles.size}. Use Locations search for a specific country/operator."
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF64748B.toInt())
                    setPadding(dp(8), dp(10), dp(8), dp(4))
                })
            }
            addView(ScrollView(this@MainActivity).apply {
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (profiles.size > 4) dp(360) else ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(listContainer)
            })
        }
    }

    private fun subscriptionGroupSubtitle(group: SubscriptionGroup): String {
        val syncText = group.lastSyncEpochMs?.let { "${group.profileIds.size} profiles" } ?: "Not synced yet"
        val result = group.lastResult?.takeIf { it.isNotBlank() }?.shortUi(36)
        return listOfNotNull(syncText, result).joinToString(" • ")
    }

    private fun loadLatestProfile() {
        val profile = runCatching { profileStore.latestProfile() }.getOrElse { error ->
            status.text = "Could not load latest profile metadata: ${error.message ?: error.javaClass.simpleName}"
            return
        }
        if (profile == null) {
            status.text = "No saved profile found. Use the top + to add one."
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
        refreshAutoTestSummary()
        showSection(AppSection.HOME)
        maybeAutoTestSelectedConfig("select")
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
        val profile = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull()
        val config = importedConfig ?: profile?.let { loadProfileConfig(it) }
        if (config == null) {
            status.text = "Import an OpenVPN, WireGuard, or V2Ray/Xray config first."
            return
        }

        showSection(AppSection.TOOLS)
        setAdvancedVisible(true)
        status.text = "Running advanced endpoint diagnostics..."
        advancedDiagnostics.text = "Resolving endpoints with DNS-over-HTTPS and probing candidates. If DoH is blocked, V2Ray/TCP endpoints can also be direct-probed without pinning."
        val network = currentNetworkLabel()
        Thread {
            val summary = try {
                probeConfigSummary(config)
            } catch (e: Exception) {
                ConfigProbeSummary(
                    report = "Resolve/probe failed: ${e.message ?: e.javaClass.simpleName}",
                    okCount = 0,
                    failedCount = 1,
                    bestLatencyMs = null,
                    bestScore = null,
                    checkedAtEpochMs = System.currentTimeMillis()
                )
            }
            val updatedProfile = profile?.let { testedProfile ->
                runCatching {
                    profileStore.markTested(
                        profileId = testedProfile.id,
                        testedAtEpochMs = summary.checkedAtEpochMs,
                        success = summary.reachable,
                        network = network,
                        latencyMs = summary.bestLatencyMs,
                        score = summary.bestScore
                    )
                }.getOrNull()
            }
            runOnUiThread {
                if (updatedProfile != null && (selectedProfileId == null || selectedProfileId == updatedProfile.id)) {
                    selectedProfile = updatedProfile
                    selectedProfileId = updatedProfile.id
                }
                advancedDiagnostics.text = summary.report
                status.text = "Advanced diagnostics completed. See Settings > Advanced."
                refreshProfileButtons(syncVerified = false)
                updateDashboardSummary()
                refreshAutoTestSummary()
            }
        }.start()
    }

    private fun buildResolveAndProbeReport(config: ImportedConfig): String {
        val lines = mutableListOf<String>()
        val networkKey = NetworkKey(NetworkType.UNKNOWN, "manual-ui")
        lines += "Endpoint test results for ${config.kind}${config.name?.let { " ($it)" } ?: ""}:"
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

    private fun VpnProfile.lastTestMiniLabel(): String? {
        if (lastTestedEpochMs == null) return null
        return if (lastTestSuccess == true) {
            lastTestLatencyMs?.let { "Test ${it}ms" } ?: "Test OK"
        } else {
            "Test failed"
        }
    }

    private fun VpnProfile.lastTestLabel(): String? {
        if (lastTestedEpochMs == null) return null
        val result = if (lastTestSuccess == true) {
            lastTestLatencyMs?.let { "OK ${it}ms" } ?: "OK"
        } else {
            "failed"
        }
        val score = lastTestScore?.let { " • score $it" }.orEmpty()
        val network = lastTestNetwork?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        return "Last test: $result$score$network"
    }

    private fun String.cleanProfileLabel(): String {
        val decoded = StringBuilder(length)
        var index = 0
        while (index < length) {
            val current = this[index]
            if (current == '\\' && index + 1 < length) {
                when (this[index + 1]) {
                    'u' -> {
                        val hexStart = index + 2
                        val hexEnd = hexStart + 4
                        if (hexEnd <= length) {
                            val hex = substring(hexStart, hexEnd)
                            if (hex.all { Character.digit(it, 16) >= 0 }) {
                                decoded.append(hex.toInt(16).toChar())
                                index = hexEnd
                                continue
                            }
                        }
                        var skip = hexStart
                        while (skip < length && skip < hexStart + 4 && Character.digit(this[skip], 16) >= 0) skip++
                        index = skip
                        continue
                    }
                    'n', 'r', 't' -> {
                        decoded.append(' ')
                        index += 2
                        continue
                    }
                    '\\' -> {
                        index++
                        continue
                    }
                    else -> {
                        index++
                        continue
                    }
                }
            }
            decoded.append(if (current.isISOControl()) ' ' else current)
            index++
        }
        return decoded.toString().collapseLabelWhitespace()
    }

    private fun String.collapseLabelWhitespace(): String {
        val compact = StringBuilder(length)
        var previousWasSpace = false
        for (char in this) {
            val normalized = if (char.isWhitespace() || char.isISOControl()) ' ' else char
            if (normalized == ' ') {
                if (!previousWasSpace) compact.append(' ')
                previousWasSpace = true
            } else {
                compact.append(normalized)
                previousWasSpace = false
            }
        }
        return compact.toString().trim()
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

    private fun compactSelectorWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return (screenWidth * 0.54f).toInt().coerceIn(dp(188), dp(260))
    }

    private fun statusBarTopPadding(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun navigationBarBottomPadding(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(16)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class SubscriptionSyncResult(
        val group: SubscriptionGroup,
        val profiles: List<VpnProfile>,
        val linkCount: Int,
        val skippedCount: Int
    )

    private data class ConfigProbeSummary(
        val report: String,
        val okCount: Int,
        val failedCount: Int,
        val bestLatencyMs: Long?,
        val bestScore: Int?,
        val checkedAtEpochMs: Long
    ) {
        val reachable: Boolean get() = okCount > 0
    }

    private data class ProfileProbeResult(
        val profile: VpnProfile,
        val config: ImportedConfig?,
        val summary: ConfigProbeSummary
    )

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
        const val MAX_PROFILE_BUTTONS = 40
        const val MAX_PROFILE_SHEET_CHOICES = 40
        const val MAX_SUBSCRIPTION_GROUP_BUTTONS = 20
        const val MAX_GROUP_PROFILE_PREVIEW = 16
        const val MAX_RECOMMENDED_PROFILES = 5
        const val MAX_AUTO_RANK_PROFILES = 12
        const val MAX_SUBSCRIPTION_LINKS = 80
        const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
        const val SUBSCRIPTION_TIMEOUT_MS = 15_000
        const val LIVE_REFRESH_CONNECTED_MS = 2_000L
        const val LIVE_REFRESH_IDLE_MS = 6_000L
        const val SETTINGS_PREFS_NAME = "vpn_project_settings"
        const val KEY_AUTO_TEST_ENABLED = "auto_test_enabled"
        const val LOCATION_FILTER_ALL = "all"
        const val LOCATION_FILTER_MANUAL = "manual"
    }
}

private class PowerRingButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false

    fun setActive(value: Boolean) {
        active = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.coerceAtMost(height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size * 0.42f

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, radius * 1.35f, intArrayOf(0x66FFFFFF, 0x22FFFFFF, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.22f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = size * 0.075f
        paint.shader = LinearGradient(cx - radius, cy + radius, cx + radius, cy - radius, intArrayOf(0xFF2563EB.toInt(), 0xFF06B6D4.toInt(), 0xFF22C55E.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        paint.strokeWidth = size * 0.018f
        paint.color = if (active) 0x9934D399.toInt() else 0x8842A5F5.toInt()
        canvas.drawCircle(cx, cy, radius * 1.12f, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0xEFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius * 0.74f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.045f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF0F172A.toInt()
        canvas.drawLine(cx, cy - radius * 0.36f, cx, cy + radius * 0.08f, paint)
        val iconRect = RectF(cx - radius * 0.30f, cy - radius * 0.12f, cx + radius * 0.30f, cy + radius * 0.48f)
        canvas.drawArc(iconRect, 125f, 290f, false, paint)
    }
}

private class MiniChartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val path = Path().apply {
            moveTo(w * 0.05f, h * 0.72f)
            cubicTo(w * 0.20f, h * 0.35f, w * 0.30f, h * 0.70f, w * 0.42f, h * 0.48f)
            cubicTo(w * 0.55f, h * 0.22f, w * 0.60f, h * 0.82f, w * 0.72f, h * 0.56f)
            cubicTo(w * 0.82f, h * 0.35f, w * 0.88f, h * 0.18f, w * 0.96f, h * 0.36f)
        }
        paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFF0EA5E9.toInt(), 0xFF2563EB.toInt(), 0xFF22C55E.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
    }
}

private class ScenicBackgroundView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 32f * resources.displayMetrics.density
        clipPath.reset()
        clipPath.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFFEAF7FF.toInt(), 0xFFF8FBFF.toInt(), 0xFFE0F2FE.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(w * 0.58f, h * 0.42f, w * 0.34f, intArrayOf(0x77FFFFFF, 0x22FDE68A, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(w * 0.58f, h * 0.42f, w * 0.36f, paint)
        paint.shader = null

        drawMountain(canvas, w, h, 0xFFC7D2FE.toInt(), 0.42f, 0.78f)
        drawMountain(canvas, w, h, 0xFF93C5FD.toInt(), 0.52f, 0.84f)
        drawMountain(canvas, w, h, 0xFF64748B.toInt(), 0.62f, 0.90f)

        paint.shader = LinearGradient(0f, h * 0.68f, 0f, h, intArrayOf(0xCCDBEAFE.toInt(), 0xFFE0F2FE.toInt(), 0xFFFFFFFF.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * 0.64f, w, h, paint)
        paint.shader = null

        paint.color = 0x6638BDF8
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        for (i in 0..5) {
            val y = h * (0.72f + i * 0.045f)
            canvas.drawLine(w * 0.05f, y, w * 0.95f, y + (i % 2) * 3f, paint)
        }
        canvas.restore()
    }

    private fun drawMountain(canvas: Canvas, w: Float, h: Float, color: Int, peak: Float, base: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color
        val path = Path().apply {
            moveTo(-w * 0.05f, h * base)
            lineTo(w * 0.18f, h * (peak + 0.14f))
            lineTo(w * 0.34f, h * peak)
            lineTo(w * 0.52f, h * (peak + 0.16f))
            lineTo(w * 0.70f, h * (peak + 0.06f))
            lineTo(w * 1.05f, h * base)
            close()
        }
        canvas.drawPath(path, paint)
        paint.color = 0x99FFFFFF.toInt()
        val snow = Path().apply {
            moveTo(w * 0.34f, h * peak)
            lineTo(w * 0.29f, h * (peak + 0.07f))
            lineTo(w * 0.39f, h * (peak + 0.06f))
            close()
        }
        canvas.drawPath(snow, paint)
    }
}
