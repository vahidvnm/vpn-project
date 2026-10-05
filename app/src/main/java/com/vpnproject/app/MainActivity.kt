package com.vpnproject.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ClipData
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
import com.vpnproject.app.core.ClashConfigParser
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
import com.vpnproject.app.core.V2RayLinkInspector
import com.vpnproject.app.core.VpnProtocol
import com.vpnproject.app.engine.EngineRegistry
import com.vpnproject.app.engine.EngineStatus
import com.vpnproject.app.engine.RuntimeConfigPreparer
import com.vpnproject.app.engine.RuntimeConfigSelection
import com.vpnproject.app.engine.V2RayRuntimeConfigBuilder
import com.vpnproject.app.engine.VpnHubConnectionState
import com.vpnproject.app.engine.XrayRealDelayTester
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger


private object PearlPalette {
    val TRANSPARENT = 0x00000000
    val INK = 0xFF101014.toInt()
    val INK_SOFT = 0xFF2B2B31.toInt()
    val TEXT_MUTED = 0xFF575963.toInt()
    val TEXT_FAINT = 0xFF8D909A.toInt()
    val PEARL_WHITE = 0xFFFEFEFF.toInt()
    val PEARL_TOP = 0xFFF9F9FD.toInt()
    val PEARL_GHOST = 0xFFF5F5FA.toInt()
    val PEARL_MID = 0xFFEFEFF6.toInt()
    val PEARL_DEEP = 0xFFE5E6EE.toInt()
    val SHELL = 0xFFEEEFF5.toInt()
    val SHELL_DARK = 0xFFD3D5DF.toInt()
    val HAIRLINE = 0x1F000000
    val HAIRLINE_STRONG = 0x33000000
    val BORDER = 0xFF17171C.toInt()
    val CHAMPAGNE = 0xFFD9DEEF.toInt()
    val CHAMPAGNE_DARK = 0xFF56627D.toInt()
    val CHAMPAGNE_SOFT = 0xFFE9ECFF.toInt()
    val ACCENT_BLUE = 0xFF52699C.toInt()
    val ACCENT_LILAC = 0xFFA8B7FF.toInt()
    val ACCENT_CORAL = 0xFFE27A61.toInt()
    val ACCENT_MINT = 0xFFA5C96B.toInt()
    val ACCENT_SOFT = 0xFFE8ECFF.toInt()
    val ACCENT_WARM_SOFT = 0xFFFFEEE9.toInt()
    val CHAMPAGNE_GLOW = 0x22A8B7FF
    val CHAMPAGNE_RING = 0x55A8B0C8
    val CHAMPAGNE_RING_STRONG = 0x778D96B4
    val ERROR = 0xFF672626.toInt()
    val ERROR_SOFT = 0xFFF6EEEE.toInt()
    val ERROR_STROKE = 0xFFD9BCBC.toInt()
    val GLASS = 0xF9FEFEFF.toInt()
    val GLASS_LIGHT = 0xFAFEFEFF.toInt()
    val GLASS_HEAVY = 0xF1FEFEFF.toInt()
    val GLASS_SOFT = 0xE8FEFEFF.toInt()
    val GLASS_MEDIUM = 0xC8FEFEFF.toInt()
    val SHINE = 0x99FFFFFF.toInt()
    val SHINE_MEDIUM = 0x77FFFFFF
    val SHINE_SOFT = 0x66FFFFFF
    val SHINE_FAINT = 0x22FFFFFF
    val PEARL_WASH = 0xCCEEEFF6.toInt()
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var hubStatusTitle: TextView
    private lateinit var hubStatusDetail: TextView
    private lateinit var connectionStatsText: TextView
    private lateinit var selectedProfileText: TextView
    private lateinit var topProfileSummaryText: TextView
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
    private var autoTestEnabled = false
    private var autoTestInFlight = false
    private lateinit var settingsSmartFallbackValueText: TextView
    private var smartFallbackEnabled = false
    private var smartFallbackProfileQueue: MutableList<String> = mutableListOf()
    private var smartFallbackAttemptCount = 0
    private var smartFallbackInFlight = false
    private var smartFallbackSessionId = 0
    private var smartFallbackSuppressFailureUntilMs = 0L
    private lateinit var favoriteActionButton: Button
    private lateinit var locationTestStatusText: TextView
    private var locationSearchQuery = ""
    private var selectedLocationGroupFilter = LOCATION_FILTER_ALL
    private var selectedLocationRuntimeFilter = LOCATION_RUNTIME_ALL
    private var selectedLocationSortMode = LOCATION_SORT_RECOMMENDED
    private var locationRenderLimit = INITIAL_PROFILE_RENDER_ROWS
    private var locationRenderKey = ""
    private val profileRowStatusViews = mutableMapOf<String, TextView>()
    private val profileRowSubtitleViews = mutableMapOf<String, TextView>()
    private val xrayDescriptorCache = mutableMapOf<String, Pair<Long, V2RayLinkInspector.Descriptor?>>()
    private val profileLocationLabelCache = mutableMapOf<String, Pair<Long, LocationDisplayLabel>>()
    private val profileRuntimeCompatibilityCache = mutableMapOf<String, Pair<Long, ProfileRuntimeCompatibility>>()
    private val profileRuntimeDeepCompatibilityCache = mutableMapOf<String, Pair<Long, ProfileRuntimeCompatibility>>()
    private lateinit var navHomeButton: TextView
    private lateinit var navProfilesButton: TextView
    private lateinit var navToolsButton: TextView
    private lateinit var homeSection: LinearLayout
    private lateinit var profilesSection: LinearLayout
    private lateinit var toolsSection: LinearLayout
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
    private var lastRecordedFailureKey: String? = null
    private lateinit var profileListContainer: LinearLayout
    private lateinit var subscriptionGroupContainer: LinearLayout
    private var pendingVpnAction = PendingVpnAction.NONE
    private var pendingOpenVpnConfigText: String? = null
    private var pendingOpenVpnConfigName: String = "vpn-project-pinned.ovpn"
    private val endpointDiscovery by lazy { EndpointDiscovery() }
    private val endpointHealthChecker by lazy { EndpointHealthChecker() }
    private val xrayRealDelayTester by lazy { XrayRealDelayTester(this) }
    private val runtimeConfigPreparer by lazy { RuntimeConfigPreparer(endpointDiscovery) }
    private val profileStore by lazy { SecureProfileStore(this) }
    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val appSettings by lazy { getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE) }
    private val routeHealthCache = RouteHealthCache()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        autoTestEnabled = appSettings.getBoolean(KEY_AUTO_TEST_ENABLED, false)
        smartFallbackEnabled = appSettings.getBoolean(KEY_SMART_FALLBACK_ENABLED, false)
        loadLocationViewPrefs()

        window.statusBarColor = PearlPalette.PEARL_TOP
        window.navigationBarColor = PearlPalette.PEARL_WHITE

        val appRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, statusBarTopPadding(), 0, 0)
            background = verticalGradient(PearlPalette.PEARL_TOP, PearlPalette.PEARL_GHOST, PearlPalette.PEARL_WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(12), dp(6), dp(12), dp(10))
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
            setTextColor(PearlPalette.TEXT_MUTED)
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

        val profileCard = createCard().apply {
            setPadding(dp(10), dp(4), dp(10), dp(10))
        }
        subscriptionGroupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        profileCard.addView(subscriptionGroupContainer)
        profileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        profileCard.addView(profileListContainer)
        profilesSection.addView(profileCard)

        val settingsCard = createCard()
        settingsCard.addView(sectionLabel("Settings"))
        settingsCard.addView(TextView(this).apply {
            text = "Use top + to add configs. Tests and fallbacks stay capped and OFF until enabled."
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(PearlPalette.TEXT_MUTED)
            setPadding(dp(8), 0, dp(8), dp(8))
        })
        settingsConnectionSummaryText = TextView(this).apply {
            text = "Status: tap Refresh status"
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(PearlPalette.INK_SOFT)
            background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 18)
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
        settingsSmartFallbackValueText = TextView(this).apply { text = if (smartFallbackEnabled) "ON" else "OFF" }
        settingsCard.addView(settingsRow("↻", "Refresh status", "Update VPN state, traffic, and verification") { showEngineStatus() })
        settingsCard.addView(settingsRow("✓", "Auto latency", "OFF by default. When ON, selected configs run quick no-VPN latency after import/select", settingsAutoTestValueText) { toggleAutoTest() })
        settingsCard.addView(settingsRow("⇢", "Smart fallback", "OFF by default. If Connect fails, try a few nearby configs only", settingsSmartFallbackValueText) { toggleSmartFallback() })
        settingsCard.addView(settingsRow("◷", "Test settings", "Real delay URL, queue limits, and live row updates") { showTestSettingsSheet() })
        settingsCard.addView(settingsRow("▦", "Subscriptions", "Groups, refresh all, load more, and search") { showSubscriptionSettingsSheet() })
        settingsCard.addView(settingsRow("⇄", "Routing & DNS", "DNS, per-app bypass, kill switch, and Xray controls") { showRoutingSettingsSheet() })
        settingsCard.addView(settingsRow("▤", "Diagnostics / logs", "Status, safe report, and full technical log") { showDiagnosticsHubSheet() })
        settingsCard.addView(settingsRow("⋯", "Advanced tools", "Technical tools and full diagnostics, hidden by default") { toggleAdvancedPanel() })
        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        advancedVisible = false
        advancedPanel.addView(sectionLabel("Advanced"))
        advancedPanel.addView(settingsHintText("Rare tools are grouped here so Settings stays clean. Diagnostics and routing have their own rows above."))
        advancedDiagnostics = TextView(this).apply {
            text = "Advanced diagnostics will appear here after refresh/probe."
            textSize = 12.5f
            gravity = Gravity.START
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(PearlPalette.INK_SOFT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showDiagnosticsLogSheet() }
            background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
        }
        advancedPanel.addView(settingsRow("▣", "Technical tools", "VPN permission, OpenVPN handoff, and bootstrap lab checks") { showTechnicalToolsSheet() })
        advancedPanel.addView(settingsRow("▤", "Full diagnostics log", "Open the current technical log in a scrollable sheet") { showDiagnosticsLogSheet() })
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
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = roundedBackground(PearlPalette.GLASS_LIGHT, PearlPalette.HAIRLINE, radiusDp = 32)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dp(14).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(22), dp(2), dp(22), navigationBarBottomPadding() + dp(10))
            }
        }
        navHomeButton = createNavButton("⌂\nHome") { showSection(AppSection.HOME) }
        navProfilesButton = createNavButton("◉\nLocations") { showSection(AppSection.PROFILES) }
        navToolsButton = createNavButton("⚙\nSettings") { showSection(AppSection.TOOLS) }
        navRow.addView(navHomeButton)
        navRow.addView(navProfilesButton)
        navRow.addView(navToolsButton)
        appRoot.addView(navRow)

        setContentView(appRoot)

        updateAutoTestToggle()
        updateSmartFallbackToggle()
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
        setPadding(0, 0, 0, dp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        topProfileSummaryText = TextView(this@MainActivity).apply {
            text = "◎  No config • tap +"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(PearlPalette.INK)
            background = roundedBackground(PearlPalette.GLASS, PearlPalette.HAIRLINE, radiusDp = 22)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dp(3).toFloat()
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(0, 0, dp(10), 0)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showSection(AppSection.PROFILES) }
        }
        addView(topProfileSummaryText)
        addView(headerIconButton("+") { showAddConfigMenu() })
    }

    private fun headerIconButton(textValue: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textValue
        textSize = if (textValue == "+") 28f else 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(if (textValue == "+") PearlPalette.INK else PearlPalette.INK)
        background = roundedBackground(PearlPalette.GLASS, PearlPalette.HAIRLINE, radiusDp = 20)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dp(6).toFloat()
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            setMargins(0, 0, 0, 0)
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
            background = roundedBackground(PearlPalette.TRANSPARENT, PearlPalette.TRANSPARENT, radiusDp = 30)
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
            setTextColor(PearlPalette.INK)
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
            setTextColor(PearlPalette.BORDER)
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
        background = roundedBackground(PearlPalette.GLASS_SOFT, PearlPalette.HAIRLINE, radiusDp = 20)
        protectionBadge = TextView(this@MainActivity).apply {
            text = "✓  Protected  ›"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PearlPalette.INK)
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
                setTextColor(PearlPalette.TEXT_MUTED)
                maxLines = 1
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun createSpeedCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = roundedBackground(PearlPalette.GLASS_SOFT, PearlPalette.HAIRLINE, radiusDp = 20)
        addView(MiniChartView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        })
        statDownText = speedLine("↓", "0 B", "Download", PearlPalette.INK)
        statUpText = speedLine("↑", "0 B", "Upload", PearlPalette.CHAMPAGNE_DARK)
        statLatencyText = speedLine("◷", "--", "Ping", PearlPalette.INK)
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
        setTextColor(PearlPalette.INK)
        setPadding(0, dp(5), 0, 0)
    }

    private fun createHeroCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = verticalGradient(PearlPalette.PEARL_WHITE, PearlPalette.PEARL_MID, PearlPalette.PEARL_GHOST, radiusDp = 34)
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
        protectionBadge = statusBadge("SECURE", "Not connected", PearlPalette.TEXT_MUTED)
        liveStatsBadge = statusBadge("LIVE", "0 B", PearlPalette.INK)
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
        background = roundedBackground(PearlPalette.GLASS_HEAVY, PearlPalette.HAIRLINE, radiusDp = 20)
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
        setTextColor(PearlPalette.INK)
        background = roundedBackground(PearlPalette.GLASS_MEDIUM, PearlPalette.HAIRLINE, radiusDp = 18)
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
        background = roundedBackground(PearlPalette.GLASS, PearlPalette.HAIRLINE, radiusDp = 24)
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
            setTextColor(PearlPalette.INK)
            background = roundedBackground(PearlPalette.PEARL_MID, PearlPalette.HAIRLINE, radiusDp = 18)
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
            setTextColor(PearlPalette.INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        homeProfileMetaText = TextView(this@MainActivity).apply {
            text = "Tap to import or select config"
            textSize = 12f
            setTextColor(PearlPalette.TEXT_MUTED)
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
            setTextColor(PearlPalette.ACCENT_BLUE)
            background = roundedBackground(PearlPalette.ACCENT_SOFT, PearlPalette.HAIRLINE, radiusDp = 18)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        setOnClickListener { showSection(AppSection.PROFILES) }
    }

    private fun createSelectedConfigsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = roundedBackground(PearlPalette.GLASS_HEAVY, PearlPalette.HAIRLINE, radiusDp = 24)
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
            background = roundedBackground(PearlPalette.GLASS, PearlPalette.HAIRLINE, radiusDp = 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
            homeProfileIconText = TextView(this@MainActivity).apply {
                text = "◎"
                textSize = 18f
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = roundedBackground(PearlPalette.PEARL_WHITE, PearlPalette.HAIRLINE, radiusDp = 16)
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
                    setTextColor(PearlPalette.INK)
                }
                homeProfileMetaText = TextView(this@MainActivity).apply {
                    text = "Tap to pick"
                    textSize = 10f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(PearlPalette.TEXT_MUTED)
                    setPadding(0, dp(2), 0, 0)
                }
                addView(homeProfileNameText)
                addView(homeProfileMetaText)
            })
            addView(TextView(this@MainActivity).apply {
                text = "⌄"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(PearlPalette.INK)
                background = roundedBackground(PearlPalette.PEARL_WHITE, PearlPalette.HAIRLINE, radiusDp = 16)
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
                    setTextColor(PearlPalette.INK)
                })
                autoTestStatusText = TextView(this@MainActivity).apply {
                    text = "Ranks saved configs and selects the best reachable one"
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(PearlPalette.TEXT_MUTED)
                }
                addView(autoTestStatusText)
            })
            autoTestToggleButton = TextView(this@MainActivity).apply {
                text = "ON"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(PearlPalette.PEARL_WHITE)
                background = roundedBackground(PearlPalette.INK, PearlPalette.BORDER, radiusDp = 18)
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
                setTextColor(PearlPalette.INK)
                background = roundedBackground(PearlPalette.CHAMPAGNE_SOFT, PearlPalette.HAIRLINE, radiusDp = 18)
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
        setAutoTestStatus(
            if (autoTestEnabled) "Auto latency enabled: selected configs will run quick no-VPN latency."
            else "Auto latency disabled. Queue tests stay manual and capped."
        )
    }

    private fun updateAutoTestToggle() {
        if (::autoTestToggleButton.isInitialized) {
            autoTestToggleButton.text = if (autoTestEnabled) "ON" else "OFF"
            autoTestToggleButton.setTextColor(if (autoTestEnabled) PearlPalette.PEARL_WHITE else PearlPalette.INK_SOFT)
            autoTestToggleButton.background = roundedBackground(
                fillColor = if (autoTestEnabled) PearlPalette.INK else PearlPalette.PEARL_MID,
                strokeColor = if (autoTestEnabled) PearlPalette.BORDER else PearlPalette.HAIRLINE,
                radiusDp = 18
            )
        }
        if (::settingsAutoTestValueText.isInitialized) {
            settingsAutoTestValueText.text = if (autoTestEnabled) "ON" else "OFF"
            settingsAutoTestValueText.setTextColor(if (autoTestEnabled) PearlPalette.PEARL_WHITE else PearlPalette.INK_SOFT)
            settingsAutoTestValueText.background = roundedBackground(
                fillColor = if (autoTestEnabled) PearlPalette.INK else PearlPalette.PEARL_MID,
                strokeColor = if (autoTestEnabled) PearlPalette.BORDER else PearlPalette.HAIRLINE,
                radiusDp = 14
            )
        }
    }

    private fun toggleSmartFallback() {
        smartFallbackEnabled = !smartFallbackEnabled
        appSettings.edit().putBoolean(KEY_SMART_FALLBACK_ENABLED, smartFallbackEnabled).apply()
        if (!smartFallbackEnabled) clearSmartFallbackSession()
        updateSmartFallbackToggle()
        setActionStatus(
            if (smartFallbackEnabled) {
                "Smart fallback enabled: after a failed Connect, try up to $MAX_SMART_FALLBACK_ATTEMPTS nearby configs; no queue-wide testing."
            } else {
                "Smart fallback disabled. Connect verifies only the selected config."
            }
        )
    }

    private fun updateSmartFallbackToggle() {
        if (::settingsSmartFallbackValueText.isInitialized) {
            settingsSmartFallbackValueText.text = if (smartFallbackEnabled) "ON" else "OFF"
            settingsSmartFallbackValueText.setTextColor(if (smartFallbackEnabled) PearlPalette.PEARL_WHITE else PearlPalette.INK_SOFT)
            settingsSmartFallbackValueText.background = roundedBackground(
                fillColor = if (smartFallbackEnabled) PearlPalette.INK else PearlPalette.PEARL_MID,
                strokeColor = if (smartFallbackEnabled) PearlPalette.BORDER else PearlPalette.HAIRLINE,
                radiusDp = 14
            )
        }
    }

    private fun setActionStatus(message: String) {
        if (::topProfileSummaryText.isInitialized) {
            topProfileSummaryText.text = message.shortUi(72)
        }
        if (::locationTestStatusText.isInitialized) {
            locationTestStatusText.text = message.shortUi(110)
            locationTestStatusText.visibility = View.GONE
        }
        status.text = message
        status.visibility = View.GONE
    }

    private fun setAutoTestStatus(message: String) {
        if (::autoTestStatusText.isInitialized) autoTestStatusText.text = message.shortUi(88)
        setActionStatus(message)
    }

    private fun refreshAutoTestSummary() {
        if (!::autoTestStatusText.isInitialized || autoTestInFlight) return
        autoTestStatusText.text = when {
            !autoTestEnabled -> "Auto latency OFF"
            selectedProfile?.lastVerifiedEpochMs != null -> selectedProfile?.lastVerifiedLabel()?.shortUi(88)
                ?: "Last real latency saved for selected config"
            else -> "Auto latency ON for selected config only (no VPN connect)"
        }
    }

    private fun autoTestsShouldPauseForLiveVpn(): Boolean = isLiveState(currentHubStatus().state)

    private fun pauseAutoTestsForConnection(message: String = "Auto test paused while VPN is running") {
        if (autoTestInFlight) autoTestInFlight = false
        setAutoTestStatus(message)
    }

    private fun maybeAutoTestSelectedConfig(reason: String) {
        if (!autoTestEnabled || autoTestsShouldPauseForLiveVpn()) return
        val profile = selectedProfile ?: runCatching { profileStore.latestProfile() }.getOrNull() ?: return
        mainHandler.postDelayed({
            if (autoTestEnabled && !autoTestsShouldPauseForLiveVpn()) {
                setActionStatus("Auto latency: quick testing ${compactProfileTitle(profile).shortUi(28)} without connecting")
                runQuickLatencyTestForProfile(profile, "Auto latency")
            }
        }, 450L)
    }

    private fun maybeAutoRankBestProfile(reason: String) {
        // Queue-wide ranking is never automatic; subscriptions can contain hundreds or thousands of configs.
        // Use Locations > Queue tools for a capped manual ping test.
    }

    private fun autoTestSelectedConfig(reason: String, testLabel: String = "Quick check") {
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
                        score = summary.bestScore,
                        testKind = testKindForLabel(testLabel)
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
        scopeLabel: String = "configs",
        testLabel: String = "Quick check"
    ) {
        if (autoTestsShouldPauseForLiveVpn()) {
            setAutoTestStatus("$testLabel paused while VPN is running")
            return
        }
        if (autoTestInFlight) {
            setAutoTestStatus("A latency test is already running")
            return
        }
        val uniqueProfiles = inputProfiles.distinctBy { it.id }
        if (uniqueProfiles.isEmpty()) {
            setAutoTestStatus("$testLabel: add configs first")
            return
        }
        val rankedInput = uniqueProfiles.sortedWith(profileRankingComparator()).take(quickCheckProfileLimit())
        autoTestInFlight = true
        setAutoTestStatus("$testLabel testing ${rankedInput.size} ${scopeLabel.shortUi(32)} without connecting...")
        val network = currentNetworkLabel()
        Thread {
            val total = rankedInput.size
            val results = Collections.synchronizedList(mutableListOf<ProfileProbeResult>())
            val started = AtomicInteger(0)
            val finished = AtomicInteger(0)
            val workerCount = minOf(MAX_PARALLEL_PING_TESTS, total).coerceAtLeast(1)
            val executor = Executors.newFixedThreadPool(workerCount)
            val latch = CountDownLatch(total)

            rankedInput.forEach { profile ->
                executor.execute {
                    try {
                        if (!autoTestInFlight || autoTestsShouldPauseForLiveVpn()) return@execute
                        val startedIndex = started.incrementAndGet()
                        mainHandler.post {
                            if (autoTestInFlight) {
                                markProfileRowTesting(profile, testLabel)
                                setAutoTestStatus("$testLabel ${finished.get()}/$total • testing $startedIndex/$total: ${compactProfileTitle(profile)}")
                            }
                        }
                        val result = probeProfileForRanking(profile, network)
                        results.add(result)
                        val done = finished.incrementAndGet()
                        val state = if (result.summary.reachable) {
                            result.summary.bestLatencyMs?.let { "OK ${it}ms" } ?: "OK"
                        } else {
                            "failed"
                        }
                        mainHandler.post {
                            if (autoTestInFlight) {
                                updateProfileRowMetadata(result.profile)
                                setAutoTestStatus("$testLabel $done/$total: ${compactProfileTitle(result.profile)} • $state")
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdownNow()

            if (!autoTestInFlight) return@Thread
            if (autoTestsShouldPauseForLiveVpn()) {
                mainHandler.post {
                    autoTestInFlight = false
                    setAutoTestStatus("$testLabel paused while VPN is running")
                    refreshAutoTestSummary()
                }
                return@Thread
            }

            val resultSnapshot = synchronized(results) { results.toList() }
            val best = resultSnapshot.filter { it.summary.reachable }
                .minWithOrNull(compareBy<ProfileProbeResult> { it.summary.bestScore ?: Int.MAX_VALUE }
                    .thenBy { it.summary.bestLatencyMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.profile.favorite })
            val report = buildAutoRankingReport(resultSnapshot, best, reason)
            val message = when {
                best != null && autoSelect -> "$testLabel selected best: ${compactProfileTitle(best.profile)}${best.summary.bestLatencyMs?.let { " • ${it}ms" }.orEmpty()}"
                best != null -> "$testLabel best: ${compactProfileTitle(best.profile)}${best.summary.bestLatencyMs?.let { " • ${it}ms" }.orEmpty()}"
                else -> "$testLabel found no reachable endpoints"
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

    private fun probeProfileForRanking(profile: VpnProfile, network: String?): ProfileProbeResult {
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
                score = summary.bestScore,
                testKind = TEST_KIND_QUICK
            )
        }.getOrNull() ?: profile
        return ProfileProbeResult(updatedProfile, config, summary)
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
                score = result.summary.bestScore,
                testKind = result.profile.lastTestKind ?: TEST_KIND_QUICK
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

    private fun activeLocationProfiles(
        storedProfiles: List<VpnProfile>,
        groups: List<SubscriptionGroup>
    ): List<VpnProfile> {
        if (groups.isEmpty()) {
            return storedProfiles.filterNot { it.id.startsWith(SUBSCRIPTION_PROFILE_PREFIX) }
        }
        val byId = storedProfiles.associateBy { it.id }
        return groups
            .flatMap { group -> group.profileIds.mapNotNull { id -> byId[id] } }
            .distinctBy { it.id }
    }

    private fun currentVisibleProfilesForTesting(limit: Int = MAX_AUTO_RANK_PROFILES): List<VpnProfile> {
        val storedProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        val allProfiles = activeLocationProfiles(storedProfiles, groups)
        normalizeLocationGroupFilter(groups)
        val query = locationSearchQuery.trim()
        val filtered = if (query.isBlank()) allProfiles else allProfiles.filter { matchesLocationSearch(it, query) }
        val scoped = profilesForLocationFilter(filtered, groups)
        val activeIds = allProfiles.map { it.id }.toSet()
        val anchor = selectedProfile?.takeIf { it.id in activeIds }
            ?: runCatching { profileStore.latestProfile() }.getOrNull()?.takeIf { it.id in activeIds }
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
            subtitle = "Quick tests do not start VPN. They measure endpoint latency before connecting."
        ) { dialog ->
            addView(TextView(this@MainActivity).apply {
                text = "Quick check is a fast endpoint probe. Real delay starts a temporary Xray core without Android VPN. Connect still performs final VPN/TUN verification."
                textSize = 12f
                setTextColor(PearlPalette.TEXT_MUTED)
                setPadding(dp(4), dp(8), dp(4), dp(4))
            })
            if (target != null) {
                addView(bottomSheetActionRow("◷", "Quick check", "Fast endpoint reachability for ${compactProfileTitle(target).shortUi(24)}") {
                    dialog.dismiss()
                    runPingTestForProfile(target)
                })
                addView(bottomSheetActionRow("✓", "Real delay", "Xray-core proxy delay for ${compactProfileTitle(target).shortUi(24)} before VPN connect") {
                    dialog.dismiss()
                    runRealDelayForProfile(target)
                })
            } else {
                addView(TextView(this@MainActivity).apply {
                    text = "No saved config is available to test yet. Use + to add a config or subscription."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(PearlPalette.TEXT_MUTED)
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                })
            }
            if (uniqueCandidates.size > 1) {
                val count = uniqueCandidates.take(quickCheckProfileLimit()).size
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
        runQuickLatencyTestForProfile(profile, "Quick check")
    }

    private fun runQuickLatencyTestForProfile(profile: VpnProfile, label: String = "Quick check") {
        if (!selectProfileForTest(profile)) return
        autoTestSelectedConfig("manual-${label.lowercase(java.util.Locale.US).replace(" ", "-")}", testLabel = label)
    }

    private fun runRealDelayForProfile(profile: VpnProfile) {
        if (autoTestsShouldPauseForLiveVpn()) {
            setAutoTestStatus("Real delay paused while VPN is running")
            return
        }
        if (autoTestInFlight) {
            setAutoTestStatus("A test is already running")
            return
        }
        if (!selectProfileForTest(profile)) return
        val config = importedConfig ?: loadProfileConfig(profile) ?: return
        autoTestInFlight = true
        setAutoTestStatus("Real delay running for ${compactProfileTitle(profile)} with Xray core...")
        val network = currentNetworkLabel()
        Thread {
            val result = xrayRealDelayTester.measure(
                config = config,
                verifyUrls = realDelayVerifyUrls(),
                dnsServers = vpnDnsServers(includeLocalhost = true),
                muxEnabled = xrayMuxEnabled(),
                muxConcurrency = xrayMuxConcurrency(),
                logLevel = xrayLogLevel()
            )
            val updated = runCatching {
                profileStore.markTested(
                    profileId = profile.id,
                    testedAtEpochMs = System.currentTimeMillis(),
                    success = result.reachable,
                    network = network,
                    latencyMs = result.latencyMs,
                    score = result.latencyMs?.let { com.vpnproject.app.core.HealthScorer.score(it) },
                    testKind = TEST_KIND_REAL
                )
            }.getOrNull()
            runOnUiThread {
                autoTestInFlight = false
                if (updated != null && (selectedProfileId == null || selectedProfileId == updated.id)) {
                    selectedProfile = updated
                    selectedProfileId = updated.id
                }
                setAutoTestStatus(if (result.reachable) "Real delay OK: ${result.latencyMs}ms" else result.detail.shortUi(90))
                if (::advancedDiagnostics.isInitialized) advancedDiagnostics.text = result.detail
                refreshProfileButtons(syncVerified = false)
                updateDashboardSummary()
            }
        }.start()
    }

    private fun runRealDelayForLocationFilter(filter: String) {
        val storedProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        val allProfiles = activeLocationProfiles(storedProfiles, groups)
        selectedLocationGroupFilter = filter
        normalizeLocationGroupFilter(groups)
        saveLocationViewPrefs()
        val query = locationSearchQuery.trim()
        val filtered = if (query.isBlank()) allProfiles else allProfiles.filter { matchesLocationSearch(it, query) }
        val grouped = profilesForLocationFilter(filtered, groups)
        val scoped = applyLocationRuntimeFilter(grouped).sortedWith(locationSortComparator())
        val scope = locationFilterLabel(groups)
        refreshProfileButtons(syncVerified = false)
        if (scoped.isEmpty()) {
            setActionStatus("No configs in $scope to test.")
            return
        }
        if (autoTestsShouldPauseForLiveVpn()) {
            setAutoTestStatus("Real delay paused while VPN is running")
            return
        }
        if (autoTestInFlight) {
            setAutoTestStatus("A test is already running")
            return
        }
        val candidates = scoped.take(realDelayProfileLimit())
        autoTestInFlight = true
        setAutoTestStatus("Real delay testing ${candidates.size}/${scoped.size} configs in ${scope.shortUi(20)} / ${locationRuntimeFilterLabel()} with Xray core...")
        val network = currentNetworkLabel()
        Thread {
            val results = mutableListOf<ProfileProbeResult>()
            candidates.forEachIndexed { index, profile ->
                if (!autoTestInFlight || autoTestsShouldPauseForLiveVpn()) return@forEachIndexed
                mainHandler.post {
                    if (autoTestInFlight) {
                        markProfileRowTesting(profile, "Real delay")
                        setAutoTestStatus("Real delay ${index + 1}/${candidates.size}: ${compactProfileTitle(profile)}")
                    }
                }
                val config = loadProfileConfigQuiet(profile)
                val delayResult = if (config != null) xrayRealDelayTester.measure(
                    config = config,
                    verifyUrls = realDelayVerifyUrls(),
                    dnsServers = vpnDnsServers(includeLocalhost = true),
                    muxEnabled = xrayMuxEnabled(),
                    muxConcurrency = xrayMuxConcurrency(),
                    logLevel = xrayLogLevel()
                ) else null
                val summary = ConfigProbeSummary(
                    report = delayResult?.detail ?: "Could not decrypt or parse ${profile.displayName}.",
                    okCount = if (delayResult?.reachable == true) 1 else 0,
                    failedCount = if (delayResult?.reachable == true) 0 else 1,
                    bestLatencyMs = delayResult?.latencyMs,
                    bestScore = delayResult?.latencyMs?.let { com.vpnproject.app.core.HealthScorer.score(it) },
                    checkedAtEpochMs = System.currentTimeMillis()
                )
                val updatedProfile = runCatching {
                    profileStore.markTested(
                        profileId = profile.id,
                        testedAtEpochMs = summary.checkedAtEpochMs,
                        success = summary.reachable,
                        network = network,
                        latencyMs = summary.bestLatencyMs,
                        score = summary.bestScore,
                        testKind = TEST_KIND_REAL
                    )
                }.getOrNull() ?: profile
                results += ProfileProbeResult(updatedProfile, config, summary)
                val done = index + 1
                val state = if (summary.reachable) summary.bestLatencyMs?.let { "OK ${it}ms" } ?: "OK" else "failed"
                mainHandler.post {
                    if (autoTestInFlight) {
                        updateProfileRowMetadata(updatedProfile)
                        setAutoTestStatus("Real delay $done/${candidates.size}: ${compactProfileTitle(updatedProfile)} • $state")
                    }
                }
            }
            val best = results.filter { it.summary.reachable }
                .minWithOrNull(compareBy<ProfileProbeResult> { it.summary.bestScore ?: Int.MAX_VALUE }
                    .thenBy { it.summary.bestLatencyMs ?: Long.MAX_VALUE })
            val report = buildAutoRankingReport(results, best, "real delay $scope")
            runOnUiThread {
                autoTestInFlight = false
                setAutoTestStatus(best?.let { "Real delay best: ${compactProfileTitle(it.profile)} • ${it.summary.bestLatencyMs}ms" }
                    ?: "Real delay found no reachable configs")
                if (::advancedDiagnostics.isInitialized) advancedDiagnostics.text = report
                refreshProfileButtons(syncVerified = false)
                updateDashboardSummary()
            }
        }.start()
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
        lines += "Note: Quick check is a fast no-VPN endpoint probe. Real delay uses a temporary Xray core before VPN. Full VPN/TUN verification happens only when you connect."
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

    private fun profileRankingComparator(network: String? = currentNetworkLabel()): Comparator<VpnProfile> =
        compareBy<VpnProfile> { profileRecommendationBucket(it, network) }
            .thenBy { profileLatencySortValue(it, network) }
            .thenBy { profileLatencyState(it, network)?.score ?: it.lastTestScore ?: Int.MAX_VALUE }
            .thenByDescending { it.favorite }
            .thenByDescending { it.lastVerifiedEpochMs ?: 0L }
            .thenByDescending { it.updatedAtEpochMs }

    private fun locationSortComparator(): Comparator<VpnProfile> {
        val network = currentNetworkLabel()
        return when (selectedLocationSortMode) {
            LOCATION_SORT_NEWEST -> compareByDescending<VpnProfile> { it.updatedAtEpochMs }
                .thenByDescending { it.favorite }
                .thenBy { compactProfileTitle(it).lowercase(java.util.Locale.US) }
            LOCATION_SORT_LATENCY -> compareBy<VpnProfile> { profileLatencySortBucket(it, network) }
                .thenBy { profileLatencySortValue(it, network) }
                .thenByDescending { it.favorite }
                .thenByDescending { it.updatedAtEpochMs }
            LOCATION_SORT_RUNTIME_READY -> compareByDescending<VpnProfile> { isXrayReadyProfile(it) }
                .then(profileRankingComparator(network))
            else -> profileRankingComparator(network)
        }
    }

    private fun profileKnownLatency(profile: VpnProfile): Long? =
        profileLatencyState(profile)?.latencyMs

    private fun locationSortLabel(): String = when (selectedLocationSortMode) {
        LOCATION_SORT_NEWEST -> "Newest"
        LOCATION_SORT_LATENCY -> "Latency"
        LOCATION_SORT_RUNTIME_READY -> "Runtime-ready first"
        else -> "Recommended"
    }

    private fun locationSortDescription(): String = when (selectedLocationSortMode) {
        LOCATION_SORT_NEWEST -> "Newest saved/refreshed configs first"
        LOCATION_SORT_LATENCY -> "Fresh same-network Quick/Real/Verified latency first; old or other-network results are lower"
        LOCATION_SORT_RUNTIME_READY -> "Xray-ready/mapped profiles first, then recommended order"
        else -> "Fresh same-network tested/verified profiles first"
    }

    private fun createLocationSearchCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, 0, 0, dp(2))
        background = ColorDrawable(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(2))
        }
        selectedProfileText.layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            setMargins(0, 0, dp(6), 0)
        }
        addView(selectedProfileText)
        addView(TextView(this@MainActivity).apply {
            text = "⌕"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(PearlPalette.INK)
            background = roundedBackground(
                fillColor = if (locationSearchQuery.isBlank()) PearlPalette.PEARL_WHITE else PearlPalette.CHAMPAGNE_SOFT,
                strokeColor = PearlPalette.HAIRLINE,
                radiusDp = 18
            )
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Search locations"
            setOnClickListener { showLocationSearchSheet() }
        })
        if (locationSearchQuery.isNotBlank()) {
            addView(TextView(this@MainActivity).apply {
                text = "×"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(PearlPalette.TEXT_MUTED)
                background = roundedBackground(PearlPalette.PEARL_WHITE, PearlPalette.HAIRLINE, radiusDp = 18)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    setMargins(dp(2), 0, 0, 0)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    locationSearchQuery = ""
                    refreshProfileButtons(syncVerified = false)
                }
            })
        }
    }

    private fun showLocationSearchSheet() {
        val searchInput = EditText(this).apply {
            hint = "Country, operator, host..."
            setSingleLine(true)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(PearlPalette.INK)
            setHintTextColor(PearlPalette.TEXT_FAINT)
            background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 18)
            setPadding(dp(12), 0, dp(12), 0)
            setText(locationSearchQuery)
            selectAll()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                setMargins(0, dp(10), 0, dp(4))
            }
        }
        showBottomSheet(
            title = "Search locations",
            subtitle = "Search stays collapsed so test/refresh controls stay next to the tabs."
        ) { dialog ->
            addView(searchInput)
            addView(bottomSheetActionRow("⌕", "Apply search", "Filter the current subscription queue") {
                locationSearchQuery = searchInput.text?.toString().orEmpty().trim()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
            if (locationSearchQuery.isNotBlank()) {
                addView(bottomSheetActionRow("×", "Clear search", "Show the full queue again") {
                    locationSearchQuery = ""
                    dialog.dismiss()
                    refreshProfileButtons(syncVerified = false)
                })
            }
        }
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = roundedBackground(PearlPalette.GLASS_HEAVY, PearlPalette.HAIRLINE, radiusDp = 24)
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

    private fun createNavButton(textValue: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textValue
        textSize = 11.8f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setLineSpacing(0f, 0.92f)
        setTextColor(PearlPalette.INK)
        background = roundedBackground(PearlPalette.TRANSPARENT, PearlPalette.TRANSPARENT, radiusDp = 24)
        setPadding(dp(4), dp(5), dp(4), dp(5))
        layoutParams = LinearLayout.LayoutParams(
            0,
            dp(54),
            1f
        ).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(textValue: String): TextView = TextView(this).apply {
        text = textValue.uppercase()
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(PearlPalette.TEXT_MUTED)
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
        setTextColor(if (primary) PearlPalette.PEARL_WHITE else PearlPalette.INK)
        background = roundedBackground(
            fillColor = if (primary) PearlPalette.INK else PearlPalette.PEARL_MID,
            strokeColor = if (primary) PearlPalette.BORDER else PearlPalette.SHELL_DARK,
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
        setTextColor(PearlPalette.PEARL_WHITE)
        background = roundedBackground(PearlPalette.INK, PearlPalette.BORDER, radiusDp = if (compact) 17 else 20)
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
        background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 20)
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
            setTextColor(PearlPalette.ACCENT_BLUE)
            background = roundedBackground(PearlPalette.ACCENT_SOFT, PearlPalette.HAIRLINE, radiusDp = 16)
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
                setTextColor(PearlPalette.INK)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(PearlPalette.TEXT_MUTED)
            })
        })
        val trailing = valueView ?: TextView(this@MainActivity).apply {
            text = "›"
            textSize = 24f
            includeFontPadding = false
            setTextColor(PearlPalette.TEXT_FAINT)
        }
        trailing.gravity = Gravity.CENTER
        trailing.typeface = Typeface.DEFAULT_BOLD
        if (valueView != null) {
            trailing.textSize = 11f
            trailing.setPadding(dp(10), dp(5), dp(10), dp(5))
            trailing.setTextColor(if (autoTestEnabled) PearlPalette.PEARL_WHITE else PearlPalette.INK_SOFT)
            trailing.background = roundedBackground(
                fillColor = if (autoTestEnabled) PearlPalette.INK else PearlPalette.PEARL_MID,
                strokeColor = if (autoTestEnabled) PearlPalette.BORDER else PearlPalette.HAIRLINE,
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

    private fun showTestSettingsSheet() {
        showBottomSheet(
            title = "Test settings",
            subtitle = "v2rayNG-style test knobs, kept safe for big subscriptions."
        ) { dialog ->
            addView(settingsHintText(
                "Quick check is a no-VPN endpoint probe. Real delay starts temporary Xray core, but final VPN/TUN verification still happens only after Connect."
            ))
            addView(bottomSheetActionRow("◷", "Quick check batch", "${quickCheckProfileLimit()} configs • $MAX_PARALLEL_PING_TESTS parallel workers") {
                dialog.dismiss()
                promptIntegerSetting(
                    title = "Quick check batch",
                    subtitle = "How many configs Queue tools can quick-test at once. Keep this modest for 1000+ subscriptions.",
                    currentValue = quickCheckProfileLimit(),
                    minValue = 1,
                    maxValue = MAX_QUICK_CHECK_SETTING_LIMIT,
                    onSave = { value ->
                        appSettings.edit().putInt(KEY_QUICK_CHECK_LIMIT, value).apply()
                        setActionStatus("Quick check batch set to $value configs.")
                    }
                )
            })
            addView(bottomSheetActionRow("✓", "Real delay batch", "${realDelayProfileLimit()} configs • temporary Xray core") {
                dialog.dismiss()
                promptIntegerSetting(
                    title = "Real delay batch",
                    subtitle = "Real delay is heavier than Quick check. Use small values on older phones.",
                    currentValue = realDelayProfileLimit(),
                    minValue = 1,
                    maxValue = MAX_REAL_DELAY_SETTING_LIMIT,
                    onSave = { value ->
                        appSettings.edit().putInt(KEY_REAL_DELAY_LIMIT, value).apply()
                        setActionStatus("Real delay batch set to $value configs.")
                    }
                )
            })
            addView(bottomSheetActionRow("URL", "Real delay URL", realDelayVerifyUrls().joinToString(", ") { it.hostLabel() }.shortUi(62)) {
                dialog.dismiss()
                promptRealDelayUrls()
            })
            addView(bottomSheetActionRow("↺", "Reset test defaults", "Quick 36, Real delay 8, generate_204 URLs") {
                dialog.dismiss()
                appSettings.edit()
                    .remove(KEY_QUICK_CHECK_LIMIT)
                    .remove(KEY_REAL_DELAY_LIMIT)
                    .remove(KEY_REAL_DELAY_URLS)
                    .apply()
                setActionStatus("Test settings reset to safe defaults.")
            })
        }
    }

    private fun showSubscriptionSettingsSheet() {
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        val storedProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val subscriptionIds = groups.flatMap { it.profileIds }.toSet()
        showBottomSheet(
            title = "Subscriptions",
            subtitle = "${groups.size} group${if (groups.size == 1) "" else "s"} • ${subscriptionIds.size} saved subscription configs"
        ) { dialog ->
            addView(settingsHintText("Subscriptions are user/provider-provided and stored encrypted. Large lists are rendered progressively. Last tab, runtime filter, and sort are remembered; search is session-only."))
            addView(bottomSheetActionRow("+", "Add subscription URL", "Use the main + flow and compact import picker") {
                dialog.dismiss()
                promptAddSubscriptionGroup()
            })
            addView(bottomSheetActionRow("↻", "Refresh all subscriptions", "Update every saved refreshable URL") {
                dialog.dismiss()
                refreshAllSubscriptionGroups()
            })
            addView(bottomSheetActionRow("▦", "Open Locations", "Manage tabs, Queue tools, Load more, and search") {
                dialog.dismiss()
                showSection(AppSection.PROFILES)
            })
            addView(bottomSheetActionRow("⌕", "Search configs", "Filter country, operator, transport, or host") {
                dialog.dismiss()
                showSection(AppSection.PROFILES)
                showLocationSearchSheet()
            })
            addView(bottomSheetActionRow("↺", "Reset Locations view", "Back to All configs, Recommended sort, no active search") {
                dialog.dismiss()
                resetLocationViewPrefs(clearSearch = true)
                refreshProfileButtons(syncVerified = false)
                setActionStatus("Locations view reset to All + Recommended. Search cleared.")
                showSection(AppSection.PROFILES)
            })
            if (groups.isEmpty() && storedProfiles.isEmpty()) {
                addView(settingsHintText("No configs yet. Add a subscription URL, paste configs, or import a file from +."))
            }
        }
    }

    private fun showRoutingSettingsSheet() {
        showBottomSheet(
            title = "Routing & DNS",
            subtitle = "Xray/VPN routing controls without cluttering Home."
        ) { dialog ->
            addView(settingsHintText("These settings apply to embedded Xray connections. WireGuard/OpenVPN fallback behavior depends on their own configs/clients."))
            addView(bottomSheetActionRow("DNS", "VPN DNS", vpnDnsServers(includeLocalhost = false).joinToString(", ")) {
                dialog.dismiss()
                showDnsSettingsSheet()
            })
            val bypassCount = bypassAppPackages().size
            addView(bottomSheetActionRow("APP", "Bypass apps", if (bypassCount == 0) "No extra app bypasses; only this app bypasses itself" else "$bypassCount app package${if (bypassCount == 1) "" else "s"} bypass VPN") {
                dialog.dismiss()
                showPerAppRoutingSheet()
            })
            addView(bottomSheetActionRow("LAN", "Bypass LAN / private IPs", "ON in Xray routing: private ranges go direct") {
                setActionStatus("LAN/private IP bypass is currently ON for Xray routing to keep local network access safer.")
            })
            addView(bottomSheetActionRow("🛡", "Kill switch", "Use Android Always-on VPN and lockdown for stricter blocking") {
                dialog.dismiss()
                showKillSwitchInfoSheet()
            })
            addView(bottomSheetActionRow("▣", "VPN permission", "Prepare Android system VPN approval") {
                dialog.dismiss()
                requestVpnPermission(PendingVpnAction.NONE)
            })
            addView(bottomSheetActionRow("ADV", "Advanced Xray", "Sniffing ${if (xraySniffingEnabled()) "ON" else "OFF"} • Mux ${if (xrayMuxEnabled()) "ON" else "OFF"} • log ${xrayLogLevel()}") {
                dialog.dismiss()
                showAdvancedXraySettingsSheet()
            })
        }
    }

    private fun showDnsSettingsSheet() {
        showBottomSheet(
            title = "VPN DNS",
            subtitle = "Used by Android VPN interface and Xray core DNS."
        ) { dialog ->
            addView(settingsHintText("Enter public IPv4 DNS servers, one per line. Defaults are 1.1.1.1 and 8.8.8.8. Xray also keeps localhost internally for fallback."))
            val input = EditText(this@MainActivity).apply {
                setText(vpnDnsServers(includeLocalhost = false).joinToString("\n"))
                textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 2
                maxLines = 4
                setSingleLine(false)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, dp(8)) }
            }
            addView(input)
            addView(bottomSheetActionRow("✓", "Save DNS", "Apply to future Xray connects and Real delay probes") {
                val dns = parseDnsServers(input.text?.toString().orEmpty())
                if (dns.isEmpty()) {
                    setActionStatus("Add at least one IPv4 DNS server, e.g. 1.1.1.1")
                    return@bottomSheetActionRow
                }
                dialog.dismiss()
                appSettings.edit().putString(KEY_VPN_DNS_SERVERS, dns.joinToString("\n")).apply()
                setActionStatus("VPN DNS saved: ${dns.joinToString(", ")}. Reconnect to apply.")
            })
            addView(bottomSheetActionRow("↺", "Reset DNS", "Use 1.1.1.1 and 8.8.8.8") {
                dialog.dismiss()
                appSettings.edit().remove(KEY_VPN_DNS_SERVERS).apply()
                setActionStatus("VPN DNS reset to defaults. Reconnect to apply.")
            })
        }
    }

    private fun showPerAppRoutingSheet() {
        showBottomSheet(
            title = "Bypass apps",
            subtitle = "Package names here bypass the Android VPN tunnel."
        ) { dialog ->
            addView(settingsHintText("This is the safe first per-app mode: excluded apps go direct, while the rest of the phone uses the VPN. One package name per line."))
            val input = EditText(this@MainActivity).apply {
                setText(bypassAppPackages().joinToString("\n"))
                hint = "com.example.app\norg.telegram.messenger"
                textSize = 13.5f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3
                maxLines = 6
                setSingleLine(false)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, dp(8)) }
            }
            addView(input)
            addView(bottomSheetActionRow("✓", "Save bypass list", "Reconnect Xray to apply") {
                val packages = parsePackageNameList(input.text?.toString().orEmpty())
                dialog.dismiss()
                appSettings.edit().putString(KEY_BYPASS_PACKAGES, packages.joinToString("\n")).apply()
                setActionStatus(if (packages.isEmpty()) "Bypass app list cleared. Reconnect to apply." else "${packages.size} bypass app package${if (packages.size == 1) "" else "s"} saved. Reconnect to apply.")
            })
            addView(bottomSheetActionRow("☰", "Show package-name help", "Copies a small sample of installed package names") {
                copyInstalledPackageSample()
            })
            addView(bottomSheetActionRow("×", "Clear bypass apps", "Only this app will bypass itself") {
                dialog.dismiss()
                appSettings.edit().remove(KEY_BYPASS_PACKAGES).apply()
                setActionStatus("Bypass app list cleared. Reconnect to apply.")
            })
        }
    }

    private fun showAdvancedXraySettingsSheet() {
        showBottomSheet(
            title = "Advanced Xray",
            subtitle = "Power-user toggles. Defaults are safest for Iran-first MVP."
        ) { dialog ->
            addView(settingsHintText("Change these only when a provider or test result suggests it. Reconnect after changing runtime options."))
            addView(bottomSheetActionRow("SNI", "Sniffing", if (xraySniffingEnabled()) "ON • detect HTTP/TLS/QUIC destination domains" else "OFF • no destination sniffing") {
                dialog.dismiss()
                appSettings.edit().putBoolean(KEY_XRAY_SNIFFING, !xraySniffingEnabled()).apply()
                setActionStatus("Xray sniffing ${if (xraySniffingEnabled()) "enabled" else "disabled"}. Reconnect to apply.")
            })
            addView(bottomSheetActionRow("MUX", "Mux", if (xrayMuxEnabled()) "ON • concurrency ${xrayMuxConcurrency()}" else "OFF • safest default") {
                dialog.dismiss()
                appSettings.edit().putBoolean(KEY_XRAY_MUX_ENABLED, !xrayMuxEnabled()).apply()
                setActionStatus("Xray Mux ${if (xrayMuxEnabled()) "enabled" else "disabled"}. Reconnect to apply.")
            })
            addView(bottomSheetActionRow("#", "Mux concurrency", "Current ${xrayMuxConcurrency()} • only used when Mux is ON") {
                dialog.dismiss()
                promptIntegerSetting(
                    title = "Mux concurrency",
                    subtitle = "Higher values can help or hurt depending on server/provider. Keep default unless needed.",
                    currentValue = xrayMuxConcurrency(),
                    minValue = MIN_XRAY_MUX_CONCURRENCY,
                    maxValue = MAX_XRAY_MUX_CONCURRENCY,
                    onSave = { value ->
                        appSettings.edit().putInt(KEY_XRAY_MUX_CONCURRENCY, value).apply()
                        setActionStatus("Mux concurrency set to $value. Reconnect to apply.")
                    }
                )
            })
            addView(bottomSheetActionRow("LOG", "Log level", xrayLogLevel()) {
                dialog.dismiss()
                showXrayLogLevelSheet()
            })
            addView(bottomSheetActionRow("FRG", "Fragment", "Planned • stays OFF until safely mapped for Xray") {
                setActionStatus("Fragment is planned for a later advanced pass; it will stay OFF by default.")
            })
            addView(bottomSheetActionRow("DNS", "FakeDNS", "Planned • advanced only") {
                setActionStatus("FakeDNS is planned for a later advanced pass; it can break some apps if enabled blindly.")
            })
            addView(bottomSheetActionRow("↺", "Reset Xray advanced", "Sniffing ON, Mux OFF, log warning") {
                dialog.dismiss()
                appSettings.edit()
                    .remove(KEY_XRAY_SNIFFING)
                    .remove(KEY_XRAY_MUX_ENABLED)
                    .remove(KEY_XRAY_MUX_CONCURRENCY)
                    .remove(KEY_XRAY_LOG_LEVEL)
                    .apply()
                setActionStatus("Advanced Xray settings reset. Reconnect to apply.")
            })
        }
    }

    private fun showXrayLogLevelSheet() {
        showBottomSheet(
            title = "Xray log level",
            subtitle = "Warning is recommended; debug can be noisy."
        ) { dialog ->
            XRAY_LOG_LEVELS.forEach { level ->
                addView(bottomSheetActionRow(if (level == xrayLogLevel()) "✓" else "LOG", level, if (level == "warning") "Recommended default" else "Set Xray core loglevel to $level") {
                    dialog.dismiss()
                    appSettings.edit().putString(KEY_XRAY_LOG_LEVEL, level).apply()
                    setActionStatus("Xray log level set to $level. Reconnect to apply.")
                })
            }
        }
    }

    private fun showDiagnosticsHubSheet() {
        val hub = currentHubStatus()
        showBottomSheet(
            title = "Diagnostics / logs",
            subtitle = "Safe summaries only; secrets are not copied."
        ) { dialog ->
            addView(settingsHintText("${hub.title}: ${hub.detail.shortUi(90)}"))
            addView(bottomSheetActionRow("↻", "Refresh status", "Update VPN state, traffic, and verification") {
                dialog.dismiss()
                showEngineStatus()
            })
            addView(bottomSheetActionRow("▤", "Open diagnostics log", "Full technical output in a scrollable sheet") {
                dialog.dismiss()
                showDiagnosticsLogSheet()
            })
            addView(bottomSheetActionRow("⧉", "Copy safe report", "Counts, selected profile, status, and engine diagnostics") {
                dialog.dismiss()
                copySafeDiagnosticsReport()
            })
            addView(bottomSheetActionRow("☰", "Saved profiles report", "Human-readable local profile summary") {
                dialog.dismiss()
                showSavedProfiles()
            })
        }
    }

    private fun showTechnicalToolsSheet() {
        showBottomSheet(
            title = "Technical tools",
            subtitle = "Rare actions kept away from the main Settings list."
        ) { dialog ->
            addView(settingsHintText("Use these only for setup, troubleshooting, or fallback handoff. They do not auto-test big queues."))
            addView(bottomSheetActionRow("▣", "VPN permission", "Prepare Android system VPN approval") {
                dialog.dismiss()
                requestVpnPermission(PendingVpnAction.NONE)
            })
            addView(bottomSheetActionRow("◷", "Load latest profile", "Select the newest encrypted local config") {
                dialog.dismiss()
                loadLatestProfile()
            })
            addView(bottomSheetActionRow("◉", "OpenVPN TCP handoff", "Save a pinned .ovpn for external clients") {
                dialog.dismiss()
                prepareAndSaveOpenVpnConfig()
            })
            addView(bottomSheetActionRow("▶", "Start bootstrap VPN", "Technical TUN bootstrap check") {
                dialog.dismiss()
                requestVpnPermission(PendingVpnAction.BOOTSTRAP)
            })
            addView(bottomSheetActionRow("■", "Stop bootstrap VPN", "Stop only the technical bootstrap tunnel") {
                dialog.dismiss()
                stopBootstrapVpn()
            })
        }
    }

    private fun promptIntegerSetting(
        title: String,
        subtitle: String,
        currentValue: Int,
        minValue: Int,
        maxValue: Int,
        onSave: (Int) -> Unit
    ) {
        showBottomSheet(title = title, subtitle = subtitle) { dialog ->
            val input = EditText(this@MainActivity).apply {
                setText(currentValue.toString())
                textSize = 16f
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setPadding(dp(14), 0, dp(14), 0)
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54)
                ).apply { setMargins(0, dp(10), 0, dp(8)) }
            }
            addView(input)
            addView(settingsHintText("Allowed range: $minValue–$maxValue. Lower numbers keep 1000+ config subscriptions responsive."))
            addView(bottomSheetActionRow("✓", "Save", "Apply this limit to future Queue tools tests") {
                val value = input.text?.toString().orEmpty().trim().toIntOrNull()
                if (value == null || value !in minValue..maxValue) {
                    setActionStatus("$title must be between $minValue and $maxValue.")
                    return@bottomSheetActionRow
                }
                dialog.dismiss()
                onSave(value)
            })
            addView(bottomSheetActionRow("×", "Cancel", "Keep current value") { dialog.dismiss() })
        }
    }

    private fun promptRealDelayUrls() {
        showBottomSheet(
            title = "Real delay URL",
            subtitle = "One or more HTTP/HTTPS URLs. First successful generate_204-style URL wins."
        ) { dialog ->
            val input = EditText(this@MainActivity).apply {
                setText(realDelayVerifyUrls().joinToString("\n"))
                textSize = 13.5f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3
                maxLines = 5
                setSingleLine(false)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, dp(8)) }
            }
            addView(input)
            addView(settingsHintText("Use URLs like https://www.gstatic.com/generate_204. Put each URL on a new line."))
            addView(bottomSheetActionRow("✓", "Save URLs", "Use these URLs for pre-connect Real delay") {
                val urls = parseRealDelayUrls(input.text?.toString().orEmpty())
                if (urls.isEmpty()) {
                    setActionStatus("Add at least one http:// or https:// URL.")
                    return@bottomSheetActionRow
                }
                dialog.dismiss()
                appSettings.edit().putString(KEY_REAL_DELAY_URLS, urls.joinToString("\n")).apply()
                setActionStatus("Real delay URL list saved: ${urls.joinToString(", ") { it.hostLabel() }}")
            })
            addView(bottomSheetActionRow("↺", "Use defaults", "gstatic, Google generate_204, Cloudflare cp") {
                dialog.dismiss()
                appSettings.edit().remove(KEY_REAL_DELAY_URLS).apply()
                setActionStatus("Real delay URLs reset to defaults.")
            })
        }
    }

    private fun settingsHintText(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 12f
        setTextColor(PearlPalette.TEXT_MUTED)
        setPadding(dp(6), dp(8), dp(6), dp(4))
    }

    private fun quickCheckProfileLimit(): Int = appSettings
        .getInt(KEY_QUICK_CHECK_LIMIT, MAX_AUTO_RANK_PROFILES)
        .coerceIn(1, MAX_QUICK_CHECK_SETTING_LIMIT)

    private fun realDelayProfileLimit(): Int = appSettings
        .getInt(KEY_REAL_DELAY_LIMIT, MAX_REAL_DELAY_PROFILES)
        .coerceIn(1, MAX_REAL_DELAY_SETTING_LIMIT)

    private fun realDelayVerifyUrls(): List<String> = parseRealDelayUrls(
        appSettings.getString(KEY_REAL_DELAY_URLS, null).orEmpty()
    ).ifEmpty { DEFAULT_REAL_DELAY_URLS }

    private fun parseRealDelayUrls(raw: String): List<String> = raw
        .lineSequence()
        .flatMap { it.split(',', ';', ' ').asSequence() }
        .map { it.trim() }
        .filter { it.startsWith("https://") || it.startsWith("http://") }
        .distinct()
        .take(MAX_REAL_DELAY_URLS)
        .toList()

    private fun String.hostLabel(): String = removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

    private fun vpnDnsServers(includeLocalhost: Boolean): List<String> {
        val saved = appSettings.getString(KEY_VPN_DNS_SERVERS, null).orEmpty()
        val base = parseDnsServers(saved).ifEmpty { DEFAULT_VPN_DNS_SERVERS }
        return if (includeLocalhost) (base + "localhost").distinct() else base
    }

    private fun parseDnsServers(raw: String): List<String> = raw
        .lineSequence()
        .flatMap { it.split(',', ';', ' ').asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { it == "localhost" || isIpv4Address(it) }
        .filter { it != "0.0.0.0" }
        .filterNot { it == "localhost" }
        .distinct()
        .take(MAX_VPN_DNS_SERVERS)
        .toList()

    private fun isIpv4Address(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotBlank() && part.length <= 3 && part.all { it.isDigit() } && (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    private fun bypassAppPackages(): List<String> = parsePackageNameList(
        appSettings.getString(KEY_BYPASS_PACKAGES, null).orEmpty()
    )

    private fun parsePackageNameList(raw: String): List<String> = raw
        .lineSequence()
        .flatMap { it.split(',', ';', ' ').asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { PACKAGE_NAME_REGEX.matches(it) }
        .filterNot { it == packageName }
        .distinct()
        .take(MAX_BYPASS_PACKAGES)
        .toList()

    private fun xraySniffingEnabled(): Boolean = appSettings.getBoolean(KEY_XRAY_SNIFFING, true)

    private fun xrayMuxEnabled(): Boolean = appSettings.getBoolean(KEY_XRAY_MUX_ENABLED, false)

    private fun xrayMuxConcurrency(): Int = appSettings
        .getInt(KEY_XRAY_MUX_CONCURRENCY, DEFAULT_XRAY_MUX_CONCURRENCY)
        .coerceIn(MIN_XRAY_MUX_CONCURRENCY, MAX_XRAY_MUX_CONCURRENCY)

    private fun xrayLogLevel(): String = appSettings
        .getString(KEY_XRAY_LOG_LEVEL, DEFAULT_XRAY_LOG_LEVEL)
        ?.takeIf { it in XRAY_LOG_LEVELS }
        ?: DEFAULT_XRAY_LOG_LEVEL

    private fun copyInstalledPackageSample() {
        val packages = runCatching {
            packageManager.getInstalledApplications(0)
                .asSequence()
                .map { info ->
                    val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(info.packageName)
                    "$label — ${info.packageName}"
                }
                .sortedBy { it.lowercase(java.util.Locale.US) }
                .take(80)
                .toList()
        }.getOrDefault(emptyList())
        val text = if (packages.isEmpty()) {
            "No package list available from Android package manager."
        } else {
            "Installed package-name sample for VPN bypass:\n" + packages.joinToString("\n")
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Package names", text))
        setActionStatus("Copied package-name sample. Paste only the packages you want to bypass VPN.")
    }

    private fun copySafeDiagnosticsReport() {
        val storedProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        val hub = currentHubStatus()
        val report = buildString {
            appendLine("VPN Hub safe diagnostics")
            appendLine("Status: ${hub.title}")
            appendLine("Detail: ${hub.detail}")
            appendLine("Verified: ${hub.verified}")
            appendLine("Engine: ${hub.activeEngine?.let { engineLabel(it) } ?: "none"}")
            appendLine("Latency: ${hub.latencyMs?.let { "${it}ms" } ?: "n/a"}")
            appendLine("Selected: ${selectedProfile?.displayName?.cleanProfileLabel()?.shortUi(48) ?: "none"}")
            appendLine("Profiles: ${storedProfiles.size}")
            appendLine("Subscription groups: ${groups.size}")
            appendLine("Quick check limit: ${quickCheckProfileLimit()}")
            appendLine("Real delay limit: ${realDelayProfileLimit()}")
            appendLine("Smart fallback: ${smartFallbackEnabled} (max $MAX_SMART_FALLBACK_ATTEMPTS)")
            appendLine("Real delay URLs: ${realDelayVerifyUrls().joinToString(", ") { it.hostLabel() }}")
            appendLine("VPN DNS: ${vpnDnsServers(includeLocalhost = false).joinToString(", ")}")
            appendLine("Bypass app packages: ${bypassAppPackages().size}")
            appendLine("Xray sniffing: ${xraySniffingEnabled()}")
            appendLine("Xray mux: ${xrayMuxEnabled()} concurrency ${xrayMuxConcurrency()}")
            appendLine("Xray log level: ${xrayLogLevel()}")
            appendLine()
            appendLine(engineDiagnosticsText(WireGuardVpnService.lastStatus, XrayVpnService.lastStatus))
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("VPN Hub safe diagnostics", report))
        setActionStatus("Safe diagnostics copied. Secrets/raw configs were not included.")
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
            intArrayOf(PearlPalette.INK, PearlPalette.CHAMPAGNE, PearlPalette.INK)
        } else {
            intArrayOf(PearlPalette.PEARL_WHITE, PearlPalette.PEARL_MID, PearlPalette.PEARL_WHITE)
        }
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(8), if (active) PearlPalette.CHAMPAGNE_RING else PearlPalette.CHAMPAGNE_RING_STRONG)
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

    private fun styleNavButton(button: TextView, selected: Boolean) {
        button.setTextColor(if (selected) PearlPalette.PEARL_WHITE else PearlPalette.TEXT_MUTED)
        button.background = if (selected) {
            verticalGradient(PearlPalette.BORDER, PearlPalette.INK, PearlPalette.INK, radiusDp = 26)
        } else {
            roundedBackground(PearlPalette.TRANSPARENT, PearlPalette.TRANSPARENT, radiusDp = 24)
        }
        (button.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = if (selected) dp(58) else dp(50)
            params.setMargins(dp(3), if (selected) 0 else dp(4), dp(3), if (selected) 0 else dp(4))
            button.layoutParams = params
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.elevation = if (selected) dp(10).toFloat() else 0f
        }
    }

    private fun toggleAdvancedPanel() {
        setAdvancedVisible(!advancedVisible)
    }

    private fun setAdvancedVisible(visible: Boolean) {
        if (!::advancedPanel.isInitialized) return
        advancedVisible = visible
        advancedPanel.visibility = if (visible) View.VISIBLE else View.GONE
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
        recordConnectionFailureIfNeeded(hub)
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
            lastRecordedFailureKey = null
            clearSmartFallbackSession()
            if (selectedProfileId == updated.id) selectedProfile = updated
            activeConnectionProfileId = updated.id
            if (refreshProfiles) refreshProfileButtons(syncVerified = false)
        }
    }

    private fun recordConnectionFailureIfNeeded(
        hub: com.vpnproject.app.engine.VpnHubStatus,
        refreshProfiles: Boolean = true
    ) {
        if (hub.state != VpnHubConnectionState.FAILED) return
        if (smartFallbackInFlight || System.currentTimeMillis() < smartFallbackSuppressFailureUntilMs) return
        val profileId = activeConnectionProfileId ?: selectedProfileId ?: return
        val network = currentNetworkLabel()
        val key = "$profileId:${hub.activeEngine}:${hub.title}:${hub.detail.shortUi(72)}:${network.orEmpty()}"
        if (lastRecordedFailureKey == key) return
        val updated = runCatching {
            profileStore.markTested(
                profileId = profileId,
                testedAtEpochMs = System.currentTimeMillis(),
                success = false,
                network = network,
                latencyMs = null,
                score = null,
                testKind = TEST_KIND_CONNECT
            )
        }.getOrNull()
        if (updated != null) {
            lastRecordedFailureKey = key
            if (selectedProfileId == updated.id) selectedProfile = updated
            activeConnectionProfileId = updated.id
            if (refreshProfiles) refreshProfileButtons(syncVerified = false)
        }
        maybeStartSmartFallback(hub.detail)
    }

    private fun beginSmartFallbackSession() {
        clearSmartFallbackSession()
        if (!smartFallbackEnabled) return
        val anchorId = selectedProfileId ?: selectedProfile?.id ?: return
        val queue = smartFallbackCandidateProfiles(anchorId)
            .filter { it.id != anchorId }
            .filter { it.isConnectableByEmbeddedEngines() }
            .map { it.id }
            .take(MAX_SMART_FALLBACK_ATTEMPTS)
        smartFallbackProfileQueue = queue.toMutableList()
        smartFallbackAttemptCount = 0
        if (queue.isNotEmpty()) {
            setActionStatus("Smart fallback armed: ${queue.size} nearby config${if (queue.size == 1) "" else "s"} available if Connect fails.")
        }
    }

    private fun clearSmartFallbackSession() {
        smartFallbackSessionId += 1
        smartFallbackProfileQueue.clear()
        smartFallbackAttemptCount = 0
        smartFallbackInFlight = false
        smartFallbackSuppressFailureUntilMs = 0L
    }

    private fun maybeStartSmartFallback(reason: String): Boolean {
        if (!smartFallbackEnabled || smartFallbackInFlight || smartFallbackProfileQueue.isEmpty()) return false
        if (VpnService.prepare(this) != null) {
            clearSmartFallbackSession()
            setActionStatus("Smart fallback stopped: Android VPN permission is missing.")
            return false
        }

        while (smartFallbackProfileQueue.isNotEmpty()) {
            val nextId = smartFallbackProfileQueue.removeAt(0)
            val profile = runCatching { profileStore.profile(nextId) }.getOrNull() ?: continue
            if (!profile.isConnectableByEmbeddedEngines()) continue
            val config = loadProfileConfigQuiet(profile) ?: continue
            smartFallbackInFlight = true
            smartFallbackAttemptCount += 1
            importedConfig = config
            selectedProfileId = profile.id
            selectedProfile = profile
            activeConnectionProfileId = null
            lastRecordedVerificationKey = null
            lastRecordedFailureKey = null
            val delayMs = SMART_FALLBACK_BASE_DELAY_MS * smartFallbackAttemptCount
            val sessionId = smartFallbackSessionId
            smartFallbackSuppressFailureUntilMs = System.currentTimeMillis() + delayMs + SMART_FALLBACK_SUPPRESS_FAILURE_MS
            val nextLabel = compactProfileTitle(profile)
            setActionStatus("Smart fallback ${smartFallbackAttemptCount}/$MAX_SMART_FALLBACK_ATTEMPTS: trying $nextLabel after failure. Reason: ${reason.shortUi(48)}")
            refreshProfileButtons(syncVerified = false)
            updateDashboardSummary()
            mainHandler.postDelayed({
                smartFallbackInFlight = false
                if (smartFallbackEnabled && smartFallbackSessionId == sessionId && selectedProfileId == profile.id && !isLiveState(currentHubStatus().state)) {
                    prepareAndStartImportedEngine(isSmartFallbackAttempt = true)
                }
            }, delayMs)
            return true
        }

        clearSmartFallbackSession()
        setActionStatus("Smart fallback finished: no more nearby connectable configs in this small capped queue.")
        return false
    }

    private fun smartFallbackCandidateProfiles(anchorId: String): List<VpnProfile> {
        val storedProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        val byId = storedProfiles.associateBy { it.id }
        val sameGroup = groups.firstOrNull { anchorId in it.profileIds }
        val candidates = if (sameGroup != null) {
            sameGroup.profileIds.mapNotNull { byId[it] }
        } else {
            val subscriptionProfileIds = groups.flatMap { it.profileIds }.toSet()
            storedProfiles.filter { it.id !in subscriptionProfileIds }
                .ifEmpty { activeLocationProfiles(storedProfiles, groups) }
        }
        return candidates
            .distinctBy { it.id }
            .sortedWith(profileRankingComparator())
            .take(MAX_SMART_FALLBACK_CANDIDATES)
    }

    private fun VpnProfile.isConnectableByEmbeddedEngines(): Boolean = when (kind) {
        VpnProfileKind.XRAY,
        VpnProfileKind.SING_BOX,
        VpnProfileKind.CLASH,
        VpnProfileKind.WIREGUARD -> true
        VpnProfileKind.OPENVPN,
        VpnProfileKind.UNKNOWN -> false
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
                VpnHubConnectionState.CONNECTED -> PearlPalette.INK
                VpnHubConnectionState.CONNECTING,
                VpnHubConnectionState.RUNNING_UNVERIFIED -> PearlPalette.CHAMPAGNE_DARK
                VpnHubConnectionState.FAILED -> PearlPalette.ERROR
                VpnHubConnectionState.IDLE,
                VpnHubConnectionState.STOPPED -> PearlPalette.INK
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
                VpnHubConnectionState.CONNECTED -> PearlPalette.INK
                VpnHubConnectionState.CONNECTING,
                VpnHubConnectionState.RUNNING_UNVERIFIED -> PearlPalette.CHAMPAGNE_DARK
                VpnHubConnectionState.FAILED -> PearlPalette.ERROR
                VpnHubConnectionState.IDLE,
                VpnHubConnectionState.STOPPED -> PearlPalette.TEXT_MUTED
            }
            protectionBadge.text = protectionText
            protectionBadge.setTextColor(protectionColor)
        }
        if (::liveStatsBadge.isInitialized) {
            liveStatsBadge.text = if (active) "$down / $up" else "0 B"
            liveStatsBadge.setTextColor(if (active) PearlPalette.INK else PearlPalette.TEXT_MUTED)
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
        com.vpnproject.app.engine.EngineKind.SING_BOX_EXPERIMENTAL -> "sing-box"
        com.vpnproject.app.engine.EngineKind.CLASH_IMPORT -> "Clash"
        com.vpnproject.app.engine.EngineKind.WIREGUARD_GO -> "WireGuard"
        com.vpnproject.app.engine.EngineKind.OPENVPN_UNAVAILABLE -> "OpenVPN handoff"
    }

    private enum class ProfileRuntimeTone {
        READY,
        INFO,
        WARNING,
        BLOCKED
    }

    private data class ProfileRuntimeCompatibility(
        val badge: String,
        val detail: String,
        val connectReady: Boolean,
        val tone: ProfileRuntimeTone
    )

    private data class LocationDisplayLabel(
        val title: String,
        val subtitle: String?
    )

    private data class LocationCountry(
        val flag: String,
        val name: String,
        val aliases: List<String>
    )

    private data class ProfileLatencyState(
        val label: String,
        val latencyMs: Long?,
        val success: Boolean?,
        val checkedAtEpochMs: Long?,
        val network: String?,
        val kindRank: Int,
        val score: Int? = null
    )

    private fun updateSelectedProfileSummary() {
        val profile = activeConnectionProfileId?.let { profileStore.profile(it) } ?: selectedProfile
        val topSummary = if (profile == null) {
            "◎  No config • tap +"
        } else {
            topProfileSummary(profile)
        }
        if (::topProfileSummaryText.isInitialized) {
            topProfileSummaryText.text = topSummary
        }
        if (::selectedProfileText.isInitialized) {
            selectedProfileText.text = if (profile == null) {
                "No profile selected • use + or pick a location"
            } else {
                locationSelectedProfileSummary(profile)
            }
        }
        if (::homeProfileNameText.isInitialized) {
            if (::homeProfileIconText.isInitialized) homeProfileIconText.text = profile?.let { profileFlagOrIcon(it) } ?: "◎"
            homeProfileNameText.text = profile?.let { compactProfileTitle(it).shortUi(24) } ?: "Choose location"
            homeProfileMetaText.text = profile?.let { homeProfileMeta(it) } ?: "Tap to pick"
        }
    }

    private fun topProfileSummary(profile: VpnProfile): String {
        val hub = currentHubStatus()
        val liveForProfile = activeConnectionProfileId == profile.id && isLiveState(hub.state)
        val compatibility = profileRuntimeCompatibility(profile)
        val savedHealth = profileLatencyMiniLabel(profile)
        val health = when {
            !compatibility.connectReady -> compatibility.detail
            liveForProfile && hub.latencyMs != null -> "Verified ${hub.latencyMs}ms"
            savedHealth != null -> savedHealth
            else -> compatibility.detail
        }
        return listOfNotNull(
            profileFlagOrIcon(profile),
            compactProfileTitle(profile).shortUi(24),
            profileTransportLabel(profile),
            health
        ).joinToString(" • ")
    }

    private fun homeProfileMeta(profile: VpnProfile): String {
        val subtitle = compactProfileSubtitle(profile)
        val compatibility = profileRuntimeCompatibility(profile)
        val health = profileLatencyMiniLabel(profile)
        val network = profileLatencyNetworkTag(profile)
        val runtime = if (compatibility.connectReady && health != null) null else compatibility.detail
        return listOfNotNull(subtitle, runtime, health, network).joinToString(" • ").ifBlank { "Ready" }.shortUi(34)
    }

    private fun compactProfileTitle(profile: VpnProfile?, fallback: String): String =
        profile?.let { compactProfileTitle(it) } ?: fallback.shortUi(24)

    private fun compactProfileTitle(profile: VpnProfile): String =
        profileLocationLabel(profile).title.shortUi(26)

    private fun compactProfileSubtitle(profile: VpnProfile): String? =
        profileLocationLabel(profile).subtitle?.shortUi(24)
            ?: profile.endpoints.firstOrNull()?.let { "${it.host.shortHost().shortUi(16)}:${it.port}" }

    private fun profileFlagOrIcon(profile: VpnProfile): String = profileLocationCountry(profile)?.flag
        ?: when (profile.kind) {
            VpnProfileKind.XRAY -> "✦"
            VpnProfileKind.SING_BOX -> "◇"
            VpnProfileKind.CLASH -> "◆"
            VpnProfileKind.WIREGUARD -> "◎"
            VpnProfileKind.OPENVPN -> "◉"
            VpnProfileKind.UNKNOWN -> "◎"
        }

    private fun profileLocationLabel(profile: VpnProfile): LocationDisplayLabel {
        val cached = profileLocationLabelCache[profile.id]
        if (cached != null && cached.first == profile.updatedAtEpochMs) return cached.second
        val country = profileLocationCountry(profile)
        val segments = profileLocationSegments(profile)
        val displaySegments = segments
            .map { it.withoutCountryWords(country) }
            .filter { segment -> segment.isNotBlank() && !segment.matchesCountry(country) && !segment.isNoisyLocationSegment() }
            .distinctBy { it.lowercase(java.util.Locale.US) }
        val focused = displaySegments.firstOrNull()
        val fallback = fallbackCompactProfileTitle(profile)
        val title = when {
            country != null && focused != null -> "${country.name} • ${focused.shortUi(14)}"
            country != null -> country.name
            focused != null -> focused
            else -> fallback
        }.ifBlank { fallback }
        val subtitle = displaySegments.firstOrNull { segment -> segment != focused }
        val label = LocationDisplayLabel(title = title, subtitle = subtitle)
        profileLocationLabelCache[profile.id] = profile.updatedAtEpochMs to label
        return label
    }

    private fun fallbackCompactProfileTitle(profile: VpnProfile): String {
        val inspectedName = profileXrayDescriptor(profile)?.displayName
        val base = primaryProfileNameSegment(profile.displayName.cleanProfileLabel())
        val title = base.substringBefore("/")
            .substringBefore("(")
            .substringBefore("~")
            .replace("✨", "")
            .replace("✦", "")
            .replace("✅", "")
            .withoutFlagEmojis()
            .trim(' ', '•', '-', '·')
            .trim()
        return (inspectedName ?: title)
            .withoutFlagEmojis()
            .ifBlank { profile.endpoints.firstOrNull()?.host?.shortHost() ?: profile.kind.displayName }
            .shortUi(24)
    }

    private fun profileLocationCountry(profile: VpnProfile): LocationCountry? {
        val label = locationSourceLabel(profile)
        return LOCATION_COUNTRIES.firstOrNull { label.contains(it.flag) }
            ?: LOCATION_COUNTRIES.firstOrNull { country ->
                country.aliases.any { alias -> label.containsLocationAlias(alias) }
            }
    }

    private fun profileLocationSegments(profile: VpnProfile): List<String> = locationSourceLabel(profile)
        .replace('|', '•')
        .replace('/', '•')
        .replace('\\', '•')
        .replace('~', '•')
        .replace('—', '•')
        .replace('–', '•')
        .replace(Regex("\\s+-\\s+"), " • ")
        .replace(Regex("[\\[\\]{}()<>]+"), " • ")
        .withoutFlagEmojis()
        .split('•')
        .mapNotNull { it.cleanLocationSegment().takeIf { segment -> segment.isNotBlank() } }
        .distinctBy { it.lowercase(java.util.Locale.US) }
        .take(4)

    private fun locationSourceLabel(profile: VpnProfile): String = listOfNotNull(
        profileXrayDescriptor(profile)?.displayName,
        profile.displayName.cleanProfileLabel()
    ).joinToString(" • ")

    private fun String.cleanLocationSegment(): String = replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^[#0-9.\\-•·]+"), "")
        .replace(Regex("(?i)\\b(vless|vmess|trojan|xray|v2ray|vpn|proxy|config|subscription|sub|profile|clash|sing-box|hiddify|nekobox)\\b"), "")
        .replace(Regex("(?i)\\b(tls|xtls|grpc|websocket|ws|tcp|httpupgrade|xhttp|splithttp|reality|cdn)\\b"), "")
        .trim(' ', '•', '-', '·', ':')
        .collapseLabelWhitespace()

    private fun String.isNoisyLocationSegment(): Boolean {
        val lower = lowercase(java.util.Locale.US)
        return lower.isBlank() ||
            lower.length < 2 ||
            lower.startsWith("http") ||
            lower.contains("://") ||
            lower.contains("@") ||
            lower.contains("=") ||
            Regex("^[a-f0-9]{8,}(-[a-f0-9]{4,}){1,}$", RegexOption.IGNORE_CASE).matches(lower) ||
            lower in setOf("server", "node", "new", "free", "vip", "premium", "direct")
    }

    private fun String.matchesCountry(country: LocationCountry?): Boolean {
        if (country == null) return false
        val lower = lowercase(java.util.Locale.US)
        return lower == country.name.lowercase(java.util.Locale.US) ||
            country.aliases.take(3).any { alias -> lower.containsLocationAlias(alias) }
    }

    private fun String.withoutCountryWords(country: LocationCountry?): String {
        if (country == null) return this
        var cleaned = this
        val removable = (listOf(country.name) + country.aliases.take(3)).distinctBy { it.lowercase(java.util.Locale.US) }
        removable.sortedByDescending { it.length }.forEach { alias ->
            cleaned = cleaned.replace(Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE), " ")
        }
        return cleaned.trim(' ', '•', '-', '·', ':').collapseLabelWhitespace()
    }

    private fun String.containsLocationAlias(alias: String): Boolean {
        val lower = lowercase(java.util.Locale.US)
        val normalizedAlias = alias.lowercase(java.util.Locale.US)
        if (normalizedAlias.any { it !in 'a'..'z' && it !in '0'..'9' && it != ' ' }) {
            return contains(alias)
        }
        return if (normalizedAlias.length <= 3) {
            lower.split(Regex("[^a-z0-9]+"))
                .any { it == normalizedAlias }
        } else {
            lower.contains(normalizedAlias)
        }
    }

    private fun profileRowSubtitle(profile: VpnProfile): String {
        val transport = profileTransportLabel(profile)
        val detail = compactProfileSubtitle(profile)?.shortUi(18)
        val compatibility = profileRuntimeCompatibility(profile)
        val runtime = compatibility.detail.shortUi(18)
        val health = profileLatencyMiniLabel(profile)
        val network = profileLatencyNetworkTag(profile)
        return listOfNotNull(transport, runtime, detail, health, network?.shortUi(11)).joinToString(" • ").ifBlank { "Saved config" }
    }

    private fun profileLatencyStates(profile: VpnProfile): List<ProfileLatencyState> = buildList {
        profile.lastVerifiedEpochMs?.let { verifiedAt ->
            add(ProfileLatencyState(
                label = "Verified",
                latencyMs = profile.lastVerifiedLatencyMs,
                success = true,
                checkedAtEpochMs = verifiedAt,
                network = profile.lastVerifiedNetwork,
                kindRank = 0
            ))
        }
        if (profile.lastTestedEpochMs != null || profile.lastTestSuccess != null) {
            val kind = profileTestKindLabel(profile.lastTestKind)
            add(ProfileLatencyState(
                label = kind,
                latencyMs = profile.lastTestLatencyMs,
                success = profile.lastTestSuccess,
                checkedAtEpochMs = profile.lastTestedEpochMs,
                network = profile.lastTestNetwork,
                kindRank = profileTestKindRank(profile.lastTestKind),
                score = profile.lastTestScore
            ))
        }
        profile.testNetworkHistory?.let { addAll(profileLatencyHistoryStates(it)) }
    }

    private fun profileLatencyHistoryStates(history: String): List<ProfileLatencyState> = history
        .split(';')
        .mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size < 6) return@mapNotNull null
            val kind = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val success = when (parts.getOrNull(2)) {
                "1" -> true
                "0" -> false
                else -> null
            }
            ProfileLatencyState(
                label = profileTestKindLabel(kind),
                latencyMs = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it >= 0L },
                success = success,
                checkedAtEpochMs = parts.getOrNull(5)?.toLongOrNull(),
                network = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
                kindRank = profileTestKindRank(kind),
                score = parts.getOrNull(4)?.toIntOrNull()
            )
        }

    private fun profileLatencyState(
        profile: VpnProfile,
        currentNetwork: String? = currentNetworkLabel()
    ): ProfileLatencyState? = profileLatencyStates(profile).minWithOrNull(
        compareBy<ProfileLatencyState> { profileLatencyStateBucket(it, currentNetwork) }
            .thenBy { it.kindRank }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }
            .thenByDescending { it.checkedAtEpochMs ?: 0L }
    )

    private fun profileRecommendationBucket(profile: VpnProfile, currentNetwork: String?): Int {
        val state = profileLatencyState(profile, currentNetwork) ?: return 50
        return if (state.success == true) profileLatencyStateBucket(state, currentNetwork) else 70
    }

    private fun profileLatencySortBucket(profile: VpnProfile, currentNetwork: String?): Int {
        val state = profileLatencyState(profile, currentNetwork) ?: return 80
        return if (state.success == true && state.latencyMs != null) profileLatencyStateBucket(state, currentNetwork) else 90
    }

    private fun profileLatencySortValue(profile: VpnProfile, currentNetwork: String?): Long =
        profileLatencyState(profile, currentNetwork)?.takeIf { it.success == true }?.latencyMs ?: Long.MAX_VALUE

    private fun profileLatencyStateBucket(state: ProfileLatencyState, currentNetwork: String?): Int {
        val fresh = isLatencyFresh(state.checkedAtEpochMs)
        val sameNetwork = isSameNetworkLabel(state.network, currentNetwork)
        val hasLatency = state.latencyMs != null
        return when {
            state.success == false && fresh && sameNetwork && state.label == "Connect" -> -1
            state.success == true && hasLatency && fresh && sameNetwork -> state.kindRank
            state.success == true && hasLatency && fresh -> 10 + state.kindRank
            state.success == false && fresh && sameNetwork -> 18
            state.success == true && hasLatency && sameNetwork -> 20 + state.kindRank
            state.success == true && hasLatency -> 30 + state.kindRank
            state.success == true && fresh && sameNetwork -> 40 + state.kindRank
            state.success == true -> 45 + state.kindRank
            state.success == false && fresh -> 60
            state.success == false -> 70
            else -> 80
        }
    }

    private fun profileHasGoodLatencySignal(profile: VpnProfile): Boolean =
        profileLatencyState(profile)?.success == true

    private fun profileLatencyMiniLabel(profile: VpnProfile): String? {
        val currentNetwork = currentNetworkLabel()
        val state = profileLatencyState(profile, currentNetwork) ?: return null
        val status = if (state.success == true) {
            state.latencyMs?.let { "${state.label} ${it}ms" } ?: "${state.label} OK"
        } else {
            "${state.label} failed"
        }
        val suffixes = mutableListOf<String>()
        if (!isLatencyFresh(state.checkedAtEpochMs)) suffixes += "old"
        if (!isSameNetworkLabel(state.network, currentNetwork) && !state.network.isNullOrBlank() && !currentNetwork.isNullOrBlank()) {
            suffixes += "other net"
        }
        return (listOf(status) + suffixes).joinToString(" ").shortUi(24)
    }

    private fun profileLatencyNetworkTag(profile: VpnProfile): String? =
        profileLatencyState(profile)?.network?.let { compactNetworkLabel(it) }

    private fun profileLatencyDetailLine(profile: VpnProfile): String? {
        val currentNetwork = currentNetworkLabel()
        val state = profileLatencyState(profile, currentNetwork) ?: return null
        val result = if (state.success == true) {
            state.latencyMs?.let { "${it}ms" } ?: "OK"
        } else {
            "failed"
        }
        val age = if (isLatencyFresh(state.checkedAtEpochMs)) "fresh" else "old"
        val network = compactNetworkLabel(state.network.orEmpty())?.let { " • $it" }.orEmpty()
        val mismatch = if (!isSameNetworkLabel(state.network, currentNetwork) && !state.network.isNullOrBlank() && !currentNetwork.isNullOrBlank()) {
            " • other network"
        } else ""
        return "Last ${state.label}: $result • $age$network$mismatch"
    }

    private fun testKindForLabel(label: String): String =
        if (label.contains("real", ignoreCase = true)) TEST_KIND_REAL else TEST_KIND_QUICK

    private fun profileTestKindLabel(kind: String?): String = when (kind) {
        TEST_KIND_VERIFIED -> "Verified"
        TEST_KIND_REAL -> "Real"
        TEST_KIND_CONNECT -> "Connect"
        TEST_KIND_QUICK -> "Quick"
        else -> "Test"
    }

    private fun profileTestKindRank(kind: String?): Int = when (kind) {
        TEST_KIND_VERIFIED -> 0
        TEST_KIND_REAL -> 1
        TEST_KIND_QUICK -> 2
        TEST_KIND_CONNECT -> 3
        else -> 4
    }

    private fun isLatencyFresh(epochMs: Long?): Boolean =
        epochMs != null && System.currentTimeMillis() - epochMs <= PROFILE_TEST_FRESH_MS

    private fun isSameNetworkLabel(left: String?, right: String?): Boolean {
        val normalizedLeft = normalizeNetworkLabel(left)
        val normalizedRight = normalizeNetworkLabel(right)
        return normalizedLeft != null && normalizedLeft == normalizedRight
    }

    private fun normalizeNetworkLabel(value: String?): String? {
        val parts = value.orEmpty()
            .split('+')
            .map { it.trim().lowercase(java.util.Locale.US) }
            .filter { it.isNotBlank() && it != "vpn" }
        return if (parts.isEmpty()) null else parts.joinToString("+")
    }

    private fun compactNetworkLabel(value: String): String? = normalizeNetworkLabel(value)?.let { normalized ->
        when (normalized) {
            "wifi" -> "Wi‑Fi"
            "cellular" -> "cell"
            "ethernet" -> "ethernet"
            else -> normalized.shortUi(10)
        }
    }

    private fun profileRuntimeLabel(profile: VpnProfile): String? = profileRuntimeCompatibility(profile).detail.shortUi(18)

    private fun profileTransportLabel(profile: VpnProfile): String? = when (profile.kind) {
        VpnProfileKind.XRAY -> profileXrayDescriptor(profile)?.shortLabel
        VpnProfileKind.SING_BOX -> profile.endpoints.firstOrNull()?.protocol?.let { singBoxProtocolLabel(it) } ?: "sing-box"
        VpnProfileKind.CLASH -> profile.endpoints.firstOrNull()?.protocol?.let { clashProtocolLabel(it) } ?: "Clash"
        else -> null
    }

    private fun clashProtocolLabel(protocolName: String): String = when (runCatching { VpnProtocol.valueOf(protocolName) }.getOrNull()) {
        VpnProtocol.CLASH_REALITY -> "Clash Reality"
        VpnProtocol.CLASH_TLS -> "Clash TLS"
        VpnProtocol.CLASH_TCP -> "Clash TCP"
        VpnProtocol.CLASH_UNKNOWN -> "Clash"
        else -> "Clash"
    }

    private fun singBoxProtocolLabel(protocolName: String): String = when (runCatching { VpnProtocol.valueOf(protocolName) }.getOrNull()) {
        VpnProtocol.SING_BOX_REALITY -> "sing-box Reality"
        VpnProtocol.SING_BOX_TLS -> "sing-box TLS"
        VpnProtocol.SING_BOX_TCP -> "sing-box TCP"
        VpnProtocol.SING_BOX_UNKNOWN -> "sing-box"
        else -> "sing-box"
    }

    private fun profileXrayDescriptor(profile: VpnProfile): V2RayLinkInspector.Descriptor? {
        if (profile.kind != VpnProfileKind.XRAY) return null
        val cached = xrayDescriptorCache[profile.id]
        if (cached != null && cached.first == profile.updatedAtEpochMs) return cached.second
        val descriptor = runCatching { profileStore.loadRawConfig(profile.id) }
            .getOrNull()
            ?.let { raw -> V2RayLinkInspector.inspect(raw) }
        xrayDescriptorCache[profile.id] = profile.updatedAtEpochMs to descriptor
        return descriptor
    }

    private fun profileRuntimeCompatibility(profile: VpnProfile): ProfileRuntimeCompatibility {
        val deepCached = profileRuntimeDeepCompatibilityCache[profile.id]
        if (deepCached != null && deepCached.first == profile.updatedAtEpochMs) return deepCached.second
        val cached = profileRuntimeCompatibilityCache[profile.id]
        if (cached != null && cached.first == profile.updatedAtEpochMs) return cached.second
        val compatibility = buildLightweightProfileRuntimeCompatibility(profile)
        profileRuntimeCompatibilityCache[profile.id] = profile.updatedAtEpochMs to compatibility
        return compatibility
    }

    private fun profileRuntimeCompatibilityDeep(profile: VpnProfile): ProfileRuntimeCompatibility {
        val cached = profileRuntimeDeepCompatibilityCache[profile.id]
        if (cached != null && cached.first == profile.updatedAtEpochMs) return cached.second
        val compatibility = buildDeepProfileRuntimeCompatibility(profile)
        profileRuntimeDeepCompatibilityCache[profile.id] = profile.updatedAtEpochMs to compatibility
        return compatibility
    }

    private fun buildLightweightProfileRuntimeCompatibility(profile: VpnProfile): ProfileRuntimeCompatibility = when (profile.kind) {
        VpnProfileKind.XRAY -> profileXrayDescriptor(profile)?.let { descriptor ->
            if (descriptor.runtimeSupported) {
                ProfileRuntimeCompatibility("Xray", "Xray ready", connectReady = true, tone = ProfileRuntimeTone.READY)
            } else {
                compatibilityFromIssue(profile, listOf(descriptor.runtimeIssue ?: "unsupported Xray field"))
            }
        } ?: ProfileRuntimeCompatibility("Xray", "Xray ready", connectReady = true, tone = ProfileRuntimeTone.READY)
        VpnProfileKind.SING_BOX -> lightweightMappedCompatibility(profile)
        VpnProfileKind.CLASH -> lightweightMappedCompatibility(profile)
        VpnProfileKind.WIREGUARD -> ProfileRuntimeCompatibility(
            badge = "WG",
            detail = "Advanced fallback",
            connectReady = true,
            tone = ProfileRuntimeTone.INFO
        )
        VpnProfileKind.OPENVPN -> ProfileRuntimeCompatibility(
            badge = "Handoff",
            detail = "OpenVPN handoff",
            connectReady = false,
            tone = ProfileRuntimeTone.INFO
        )
        VpnProfileKind.UNKNOWN -> ProfileRuntimeCompatibility(
            badge = "Issue",
            detail = "Unknown runtime",
            connectReady = false,
            tone = ProfileRuntimeTone.WARNING
        )
    }

    private fun lightweightMappedCompatibility(profile: VpnProfile): ProfileRuntimeCompatibility {
        val protocol = profile.endpoints.firstOrNull()?.protocol
        return when {
            protocol.isNullOrBlank() -> ProfileRuntimeCompatibility("Issue", "Missing endpoint", connectReady = false, tone = ProfileRuntimeTone.WARNING)
            protocol?.endsWith("_UNKNOWN") == true -> ProfileRuntimeCompatibility("Map", "Check mapper", connectReady = false, tone = ProfileRuntimeTone.WARNING)
            else -> ProfileRuntimeCompatibility("Xray", "Xray mapped", connectReady = true, tone = ProfileRuntimeTone.READY)
        }
    }

    private fun buildDeepProfileRuntimeCompatibility(profile: VpnProfile): ProfileRuntimeCompatibility = when (profile.kind) {
        VpnProfileKind.XRAY,
        VpnProfileKind.SING_BOX,
        VpnProfileKind.CLASH -> buildXrayMappedCompatibility(profile)
        VpnProfileKind.WIREGUARD,
        VpnProfileKind.OPENVPN,
        VpnProfileKind.UNKNOWN -> buildLightweightProfileRuntimeCompatibility(profile)
    }

    private fun buildXrayMappedCompatibility(profile: VpnProfile): ProfileRuntimeCompatibility {
        val raw = runCatching { profileStore.loadRawConfig(profile.id) }.getOrNull()
            ?: return ProfileRuntimeCompatibility("Issue", "Missing raw config", connectReady = false, tone = ProfileRuntimeTone.WARNING)
        val imported = runCatching { ConfigImporter.parse(raw, profile.name) }
            .getOrElse { error ->
                return compatibilityFromIssue(
                    profile = profile,
                    messages = listOf(error.message ?: error.javaClass.simpleName)
                )
            }
        val runtime = runCatching { V2RayRuntimeConfigBuilder.buildDelayProbe(imported) }
        return runtime.fold(
            onSuccess = {
                ProfileRuntimeCompatibility(
                    badge = "Xray",
                    detail = if (profile.kind == VpnProfileKind.XRAY) "Xray ready" else "Xray mapped",
                    connectReady = true,
                    tone = ProfileRuntimeTone.READY
                )
            },
            onFailure = { error ->
                compatibilityFromIssue(
                    profile = profile,
                    messages = imported.warnings + (error.message ?: error.javaClass.simpleName)
                )
            }
        )
    }

    private fun compatibilityFromIssue(profile: VpnProfile, messages: List<String>): ProfileRuntimeCompatibility {
        val joined = messages.joinToString(" ").lowercase(java.util.Locale.US)
        return when {
            joined.contains("missing public") || joined.contains("public_key/pbk") || joined.contains("public-key/pbk") || joined.contains("pbk/publickey") ->
                ProfileRuntimeCompatibility("Key", "Missing Reality key", connectReady = false, tone = ProfileRuntimeTone.BLOCKED)
            joined.contains("missing uuid/password") || joined.contains("missing login") || joined.contains("missing credential") ->
                ProfileRuntimeCompatibility("Key", "Missing login", connectReady = false, tone = ProfileRuntimeTone.BLOCKED)
            joined.contains("unsupported clash proxy types") ->
                ProfileRuntimeCompatibility("Engine", "Needs Clash engine", connectReady = false, tone = ProfileRuntimeTone.INFO)
            joined.contains("unsupported sing-box outbound types") ->
                ProfileRuntimeCompatibility("Engine", "Needs sing-box engine", connectReady = false, tone = ProfileRuntimeTone.INFO)
            joined.contains("unsupported") && joined.contains("transport") && listOf("quic", "kcp", "mkcp", "http3", "h3").any { joined.contains(it) } ->
                ProfileRuntimeCompatibility("UDP", "UDP transport", connectReady = false, tone = ProfileRuntimeTone.WARNING)
            joined.contains("unsupported") && joined.contains("transport") ->
                ProfileRuntimeCompatibility("Map", "Transport mapper", connectReady = false, tone = ProfileRuntimeTone.WARNING)
            joined.contains("unsupported") && joined.contains("security") ->
                ProfileRuntimeCompatibility("Sec", "Unsupported security", connectReady = false, tone = ProfileRuntimeTone.WARNING)
            joined.contains("no xray-compatible") || joined.contains("no supported") ->
                ProfileRuntimeCompatibility("Map", fallbackMapperDetail(profile), connectReady = false, tone = ProfileRuntimeTone.WARNING)
            else -> ProfileRuntimeCompatibility("Issue", "Runtime issue", connectReady = false, tone = ProfileRuntimeTone.WARNING)
        }
    }

    private fun fallbackMapperDetail(profile: VpnProfile): String = when (profile.kind) {
        VpnProfileKind.CLASH -> "Needs Clash mapper"
        VpnProfileKind.SING_BOX -> "Needs sing-box mapper"
        VpnProfileKind.XRAY -> "Needs Xray support"
        else -> "Needs mapper"
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
                    setTextColor(PearlPalette.TEXT_MUTED)
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
        addSheetSection("Recommended", profiles.filter { profileHasGoodLatencySignal(it) }.sortedWith(profileRankingComparator()).take(MAX_RECOMMENDED_PROFILES))
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
        if (groups.isEmpty()) {
            addSheetSection("Imported configs", profiles.filter { it.id !in subscriptionProfileIds }.sortedWith(profileRankingComparator()))
        }
        if (profiles.size > shown) {
            container.addView(TextView(this@MainActivity).apply {
                text = "Subscription configs are separated into tabs. Use Locations search for a specific config."
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(PearlPalette.TEXT_MUTED)
                setPadding(dp(10), dp(10), dp(10), dp(4))
            })
        }
    }

    private fun showProfileActionsSheet(profile: VpnProfile) {
        selectedProfile = profile
        selectedProfileId = profile.id
        updateDashboardSummary()
        val compatibility = profileRuntimeCompatibilityDeep(profile)
        showBottomSheet(
            title = compactProfileTitle(profile),
            subtitle = compactProfileSubtitle(profile)?.shortUi(34) ?: "Saved config"
        ) { dialog ->
            addView(bottomSheetActionRow("✓", "Select", "Use this config on Home") {
                dialog.dismiss()
                loadProfile(profile)
            })
            addView(bottomSheetActionRow("ⓘ", "Runtime details", "${compatibility.badge} • ${compatibility.detail}".shortUi(58)) {
                dialog.dismiss()
                showProfileRuntimeDetailsSheet(profile)
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

    private fun showProfileRuntimeDetailsSheet(profile: VpnProfile) {
        val compatibility = profileRuntimeCompatibilityDeep(profile)
        showBottomSheet(
            title = "Runtime details",
            subtitle = "${compatibility.badge} • ${compatibility.detail}".shortUi(70)
        ) { dialog ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 18)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, dp(2)) }
                profileRuntimeDetailLines(profile, compatibility).forEach { line ->
                    addView(TextView(this@MainActivity).apply {
                        text = line
                        textSize = 12f
                        setTextColor(PearlPalette.TEXT_MUTED)
                        setPadding(0, dp(3), 0, dp(3))
                    })
                }
            })
            addView(settingsHintText("No secrets are shown here. Quick check is no-VPN; Real delay starts temporary Xray; full VPN verification happens only after Connect."))
            addView(bottomSheetActionRow("✓", "Select", "Use this config on Home") {
                dialog.dismiss()
                loadProfile(profile)
            })
            if (compatibility.connectReady && profile.kind in setOf(VpnProfileKind.XRAY, VpnProfileKind.SING_BOX, VpnProfileKind.CLASH)) {
                addView(bottomSheetActionRow("◷", "Real delay", "Run Xray-core proxy delay before VPN connect") {
                    dialog.dismiss()
                    runRealDelayForProfile(profile)
                })
            }
            addView(bottomSheetActionRow("‹", "Back", "Return to profile actions") {
                dialog.dismiss()
                showProfileActionsSheet(profile)
            })
        }
    }

    private fun profileRuntimeDetailLines(
        profile: VpnProfile,
        compatibility: ProfileRuntimeCompatibility
    ): List<String> = buildList {
        add("Status: ${compatibility.detail} (${compatibility.badge})")
        add("Profile type: ${profile.kind.displayName}")
        add("Connect path: ${profileConnectPath(profile, compatibility)}")
        profileTransportLabel(profile)?.takeIf { it.isNotBlank() }?.let { add("Transport: $it") }
        profile.endpoints.firstOrNull()?.let { endpoint ->
            add("Endpoint: ${endpoint.host.shortHost()}:${endpoint.port}")
            endpoint.verifyHost?.takeIf { it.isNotBlank() }?.let { add("Verify host: ${it.shortHost()}") }
        }
        profileLatencyDetailLine(profile)?.let { add(it) }
        if (!compatibility.connectReady) add("Next step: ${runtimeFixHint(compatibility, profile)}")
    }

    private fun profileConnectPath(
        profile: VpnProfile,
        compatibility: ProfileRuntimeCompatibility
    ): String = when {
        compatibility.connectReady && profile.kind == VpnProfileKind.XRAY -> "Embedded Xray direct"
        compatibility.connectReady && profile.kind == VpnProfileKind.SING_BOX -> "Mapped through embedded Xray"
        compatibility.connectReady && profile.kind == VpnProfileKind.CLASH -> "Mapped through embedded Xray"
        compatibility.connectReady && profile.kind == VpnProfileKind.WIREGUARD -> "WireGuard advanced fallback"
        profile.kind == VpnProfileKind.OPENVPN -> "External OpenVPN handoff"
        profile.kind == VpnProfileKind.CLASH && compatibility.badge == "Engine" -> "Needs full Clash-compatible engine"
        profile.kind == VpnProfileKind.SING_BOX && compatibility.badge == "Engine" -> "Needs full sing-box runtime"
        else -> "Not start-ready in this build"
    }

    private fun runtimeFixHint(
        compatibility: ProfileRuntimeCompatibility,
        profile: VpnProfile
    ): String = when (compatibility.badge) {
        "Key" -> if (compatibility.detail.contains("Reality", ignoreCase = true)) {
            "Ask the provider for the full REALITY link including pbk/publicKey."
        } else {
            "Import the complete user-owned profile including uuid/password."
        }
        "UDP" -> "Prefer provider profiles using TCP, WebSocket, gRPC, H2, HTTPUpgrade, or XHTTP."
        "Engine" -> when (profile.kind) {
            VpnProfileKind.CLASH -> "Use an Xray-compatible Clash proxy or wait for a native Clash engine."
            VpnProfileKind.SING_BOX -> "Use an Xray-compatible outbound or wait for a native sing-box runtime."
            else -> "Use a profile supported by the embedded runtime."
        }
        "Map" -> "This profile needs an additional mapper before Connect can start it."
        "Sec" -> "Use none, TLS, or REALITY security when importing for embedded Xray."
        "Handoff" -> "Export/use this config in an external OpenVPN client for now."
        else -> "Try another provider profile or re-import the full config."
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
            setPadding(dp(18), dp(10), dp(18), dp(18))
            background = roundedBackground(PearlPalette.PEARL_WHITE, PearlPalette.HAIRLINE, radiusDp = 30)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(this@MainActivity).apply {
                text = ""
                background = roundedBackground(PearlPalette.SHELL_DARK, PearlPalette.SHELL_DARK, radiusDp = 4)
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
                        setTextColor(PearlPalette.INK)
                    })
                    subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                        addView(TextView(this@MainActivity).apply {
                            text = sub.shortUi(70)
                            textSize = 12f
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(PearlPalette.TEXT_MUTED)
                        })
                    }
                })
                addView(TextView(this@MainActivity).apply {
                    text = "×"
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(PearlPalette.TEXT_MUTED)
                    background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 18)
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
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply {
                y = navigationBarBottomPadding() + dp(78)
            }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun bottomSheetActionRow(icon: String, title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 18)
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
                setTextColor(PearlPalette.ACCENT_BLUE)
                background = roundedBackground(PearlPalette.ACCENT_SOFT, PearlPalette.HAIRLINE, radiusDp = 15)
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
                    setTextColor(PearlPalette.INK)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(PearlPalette.TEXT_MUTED)
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 24f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(PearlPalette.TEXT_FAINT)
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
                fillColor = if (profile.id == selectedProfileId) PearlPalette.CHAMPAGNE_SOFT else PearlPalette.PEARL_GHOST,
                strokeColor = if (profile.id == selectedProfileId) PearlPalette.BORDER else PearlPalette.HAIRLINE,
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
                background = roundedBackground(PearlPalette.PEARL_WHITE, PearlPalette.HAIRLINE, radiusDp = 16)
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
                    setTextColor(PearlPalette.INK)
                })
                val subtitleView = TextView(this@MainActivity).apply {
                    text = profileRowSubtitle(profile)
                    textSize = if (compact) 10f else 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(PearlPalette.TEXT_MUTED)
                }
                profileRowSubtitleViews[profile.id] = subtitleView
                addView(subtitleView)
            })
            val statusView = TextView(this@MainActivity).apply {
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
            }
            profileRowStatusViews[profile.id] = statusView
            addView(statusView)
            addView(TextView(this@MainActivity).apply {
                text = if (profile.id == selectedProfileId) "✓" else "⋯"
                textSize = if (profile.id == selectedProfileId) 15f else 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (profile.id == selectedProfileId) PearlPalette.INK else PearlPalette.TEXT_FAINT)
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

    private fun markProfileRowTesting(profile: VpnProfile, label: String) {
        profileRowSubtitleViews[profile.id]?.text = "${label.shortUi(18)} running…"
        profileRowStatusViews[profile.id]?.let { statusView ->
            statusView.text = "…"
            statusView.setTextColor(PearlPalette.ACCENT_BLUE)
            statusView.background = roundedBackground(PearlPalette.ACCENT_SOFT, PearlPalette.ACCENT_LILAC, radiusDp = 12)
        }
    }

    private fun updateProfileRowMetadata(profile: VpnProfile) {
        profileRowSubtitleViews[profile.id]?.text = profileRowSubtitle(profile)
        profileRowStatusViews[profile.id]?.let { statusView ->
            statusView.text = profileStatusLabel(profile)
            statusView.setTextColor(profileStatusTextColor(profile))
            statusView.background = roundedBackground(
                profileStatusFillColor(profile),
                profileStatusStrokeColor(profile),
                radiusDp = 12
            )
        }
        if (selectedProfileId == profile.id) selectedProfile = profile
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

    private fun profileStatusLabel(profile: VpnProfile): String {
        val compatibility = profileRuntimeCompatibility(profile)
        val signal = profileLatencyState(profile)
        val stale = signal != null && !isLatencyFresh(signal.checkedAtEpochMs)
        return when {
            !compatibility.connectReady -> compatibility.badge
            signal?.success == false -> if (stale) "Fail old" else "Fail"
            signal?.latencyMs != null -> if (stale) "${signal.latencyMs} old" else "${signal.latencyMs}ms"
            profile.id == selectedProfileId -> "Selected"
            signal?.success == true -> if (stale) "Good old" else signal.label
            profile.favorite -> "Fav"
            compatibility.badge.isNotBlank() -> compatibility.badge
            else -> "New"
        }.shortUi(10)
    }

    private fun profileStatusFillColor(profile: VpnProfile): Int {
        val compatibility = profileRuntimeCompatibility(profile)
        val signal = profileLatencyState(profile)
        return when {
            compatibility.tone == ProfileRuntimeTone.BLOCKED -> PearlPalette.ERROR_SOFT
            compatibility.tone == ProfileRuntimeTone.WARNING -> PearlPalette.CHAMPAGNE_SOFT
            profile.id == selectedProfileId && signal?.success == false -> PearlPalette.ERROR_SOFT
            profile.id == selectedProfileId -> PearlPalette.CHAMPAGNE_SOFT
            signal?.success == true -> PearlPalette.CHAMPAGNE_SOFT
            profile.favorite -> PearlPalette.CHAMPAGNE_SOFT
            signal?.success == false -> PearlPalette.ERROR_SOFT
            else -> PearlPalette.PEARL_MID
        }
    }

    private fun profileStatusStrokeColor(profile: VpnProfile): Int {
        val compatibility = profileRuntimeCompatibility(profile)
        val signal = profileLatencyState(profile)
        return when {
            compatibility.tone == ProfileRuntimeTone.BLOCKED -> PearlPalette.ERROR_STROKE
            compatibility.tone == ProfileRuntimeTone.WARNING -> PearlPalette.CHAMPAGNE
            compatibility.tone == ProfileRuntimeTone.READY && signal?.success != true -> PearlPalette.HAIRLINE
            profile.id == selectedProfileId && signal?.success == false -> PearlPalette.ERROR_STROKE
            profile.id == selectedProfileId -> PearlPalette.BORDER
            signal?.success == true -> PearlPalette.SHELL_DARK
            profile.favorite -> PearlPalette.CHAMPAGNE
            signal?.success == false -> PearlPalette.ERROR_STROKE
            else -> PearlPalette.HAIRLINE
        }
    }

    private fun profileStatusTextColor(profile: VpnProfile): Int {
        val compatibility = profileRuntimeCompatibility(profile)
        val signal = profileLatencyState(profile)
        return when {
            compatibility.tone == ProfileRuntimeTone.BLOCKED -> PearlPalette.ERROR
            compatibility.tone == ProfileRuntimeTone.WARNING -> PearlPalette.CHAMPAGNE_DARK
            profile.id == selectedProfileId && signal?.success == false -> PearlPalette.ERROR
            profile.id == selectedProfileId -> PearlPalette.INK
            signal?.success == true -> PearlPalette.BORDER
            profile.favorite -> PearlPalette.CHAMPAGNE_DARK
            signal?.success == false -> PearlPalette.ERROR
            else -> PearlPalette.TEXT_MUTED
        }
    }

    private fun profileNeedsXrayMapper(profile: VpnProfile): Boolean {
        val compatibility = profileRuntimeCompatibility(profile)
        return !compatibility.connectReady && compatibility.tone == ProfileRuntimeTone.WARNING
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
            profileTransportLabel(profile),
            profileXrayDescriptor(profile)?.displayName,
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
        setTextColor(PearlPalette.TEXT_MUTED)
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

    private fun locationSelectedProfileSummary(profile: VpnProfile): String {
        val health = profile.lastVerifiedLatencyMs?.let { "Good ${it}ms" }
            ?: profile.lastTestLatencyMs?.let { "Ping ${it}ms" }
            ?: profile.lastTestSuccess?.let { if (it) "Ping" else "Fail" }
            ?: "Ready"
        val title = compactProfileTitle(profile).shortUi(22)
        val subtitle = compactProfileSubtitle(profile)?.shortUi(18)
        val transport = profileTransportLabel(profile)
        val runtime = profileRuntimeLabel(profile)
        return listOfNotNull(profileFlagOrIcon(profile), title, transport, runtime, subtitle, health)
            .joinToString(" • ")
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

    private fun prepareAndStartImportedEngine(isSmartFallbackAttempt: Boolean = false) {
        val config = importedConfig ?: loadSelectedOrLatestProfileConfigForAction()
        if (config == null) {
            status.text = "Import or pick a saved V2Ray/Xray profile first. WireGuard/OpenVPN remain advanced fallback imports only."
            updateDashboardSummary()
            return
        }
        if (!isSmartFallbackAttempt) beginSmartFallbackSession()
        lastRecordedVerificationKey = null
        when (config.kind) {
            ConfigKind.WIREGUARD -> prepareAndStartWireGuardEngine(config)
            ConfigKind.V2RAY,
            ConfigKind.SING_BOX,
            ConfigKind.CLASH -> prepareAndStartXrayEngine(config)
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
                        val message = "WireGuard runtime config failed: ${error.message ?: error.javaClass.simpleName}"
                        status.text = message
                        maybeStartSmartFallback(message)
                    }
                )
            }
        }.start()
    }

    private fun startWireGuardEngine(selection: RuntimeConfigSelection, config: ImportedConfig) {
        activeConnectionProfileId = selectedProfileId
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
        status.text = "Preparing embedded Xray runtime config from the selected profile..."
        hubStatusTitle.text = "Preparing"
        hubStatusDetail.text = "Embedded Xray is preparing a runtime config. V2Ray links start directly; supported sing-box/Clash proxies are mapped to Xray."
        Thread {
            val result = runCatching {
                V2RayRuntimeConfigBuilder.build(
                    config = config,
                    dnsServers = vpnDnsServers(includeLocalhost = true),
                    sniffingEnabled = xraySniffingEnabled(),
                    muxEnabled = xrayMuxEnabled(),
                    muxConcurrency = xrayMuxConcurrency(),
                    logLevel = xrayLogLevel(),
                    localHttpProxyPort = allocateXrayLocalProxyPort()
                )
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { runtime -> startXrayEngine(runtime) },
                    onFailure = { error ->
                        activeConnectionProfileId = null
                        val message = "Xray runtime config failed: ${error.message ?: error.javaClass.simpleName}"
                        status.text = message
                        hubStatusTitle.text = "Needs mapper"
                        hubStatusDetail.text = "This config stayed saved, but embedded Xray cannot start it until the unsupported field is mapped."
                        maybeStartSmartFallback(message)
                    }
                )
            }
        }.start()
    }

    private fun allocateXrayLocalProxyPort(): Int? = runCatching {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.localPort.takeIf { it in 1024..65535 }
        }
    }.getOrNull()

    private fun startXrayEngine(runtime: V2RayRuntimeConfig) {
        activeConnectionProfileId = selectedProfileId
        startService(Intent(this, WireGuardVpnService::class.java).apply { action = WireGuardVpnService.ACTION_STOP })
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG_JSON, runtime.configJson)
            putExtra(XrayVpnService.EXTRA_PROFILE_NAME, safeTunnelName(runtime.profileName))
            putExtra(XrayVpnService.EXTRA_NOTE, runtime.note)
            runtime.localHttpProxyPort?.let { putExtra(XrayVpnService.EXTRA_LOCAL_HTTP_PROXY_PORT, it) }
            putStringArrayListExtra(XrayVpnService.EXTRA_DNS_SERVERS, ArrayList(vpnDnsServers(includeLocalhost = false)))
            putStringArrayListExtra(XrayVpnService.EXTRA_BYPASS_PACKAGES, ArrayList(bypassAppPackages()))
        }
        startForegroundServiceCompat(intent)
        status.text = "Starting embedded Xray engine. Verification will refresh automatically."
        hubStatusTitle.text = "Connecting"
        hubStatusDetail.text = "Embedded Xray is starting. ${runtime.note}"
        scheduleEngineStatusRefreshes()
    }

    private fun stopImportedEngines() {
        clearSmartFallbackSession()
        startService(Intent(this, WireGuardVpnService::class.java).apply { action = WireGuardVpnService.ACTION_STOP })
        startService(Intent(this, XrayVpnService::class.java).apply { action = XrayVpnService.ACTION_STOP })
        status.text = "Disconnect requested for active engines."
        activeConnectionProfileId = null
        lastRecordedVerificationKey = null
        lastRecordedFailureKey = null
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
        recordConnectionFailureIfNeeded(hub)
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
                    setTextColor(PearlPalette.INK_SOFT)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
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
                setTextColor(PearlPalette.INK_SOFT)
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
            addView(bottomSheetActionRow("□", "Import from file", "Pick .json, .yaml, .conf, .ovpn, or text file") {
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
                setTextColor(PearlPalette.TEXT_MUTED)
                setPadding(dp(8), dp(10), dp(8), dp(6))
            })
            val nameInput = EditText(this@MainActivity).apply {
                hint = "Group name, e.g. Provider A"
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(true)
                setPadding(dp(14), 0, dp(14), 0)
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
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
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
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
                    setActionStatus("Subscription URL is empty.")
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
            setActionStatus("${group.displayName} was imported from clipboard and has no refresh URL. Copy the subscription again and use Paste from clipboard to refresh it.")
            return
        }
        addOrRefreshSubscriptionGroup(group.displayName, url, existingGroup = group)
    }

    private fun refreshAllSubscriptionGroups() {
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        if (groups.isEmpty()) {
            setActionStatus("No subscription links to refresh. Use + to add one.")
            return
        }
        val refreshable = groups.mapNotNull { group ->
            profileStore.loadSubscriptionUrl(group.id)?.takeIf { it.isNotBlank() }?.let { url -> group to url }
        }
        if (refreshable.isEmpty()) {
            setActionStatus("These subscription profiles came from clipboard text and have no saved refresh URL. Copy the subscription again and use Paste from clipboard.")
            return
        }
        setActionStatus("Refreshing ${refreshable.size} subscription link${if (refreshable.size == 1) "" else "s"}...")
        Thread {
            val synced = mutableListOf<SubscriptionSyncResult>()
            val failures = mutableListOf<String>()
            refreshable.forEach { (group, url) ->
                runCatching { syncSubscriptionGroupBlocking(group.displayName, url, existingGroup = group) }
                    .onSuccess { synced += it }
                    .onFailure { error -> failures += "${group.displayName.shortUi(18)}: ${error.message ?: error.javaClass.simpleName}" }
            }
            runOnUiThread {
                synced.lastOrNull()?.group?.let { group ->
                    if (selectedLocationGroupFilter != LOCATION_FILTER_ALL && selectedLocationGroupFilter != LOCATION_FILTER_MANUAL) {
                        selectedLocationGroupFilter = group.id
                        saveLocationViewPrefs()
                    }
                }
                refreshProfileButtons()
                updateDashboardSummary()
                val saved = synced.sumOf { it.profiles.size }
                val skippedClipboard = groups.size - refreshable.size
                setActionStatus(buildString {
                    append("Refreshed ${synced.size}/${refreshable.size} subscription link")
                    append(if (refreshable.size == 1) "" else "s")
                    append(" • $saved profiles saved")
                    if (skippedClipboard > 0) append(" • $skippedClipboard clipboard-only skipped")
                    if (failures.isNotEmpty()) append(" • ${failures.size} failed")
                })
                if (failures.isNotEmpty() && ::advancedDiagnostics.isInitialized) {
                    advancedDiagnostics.text = "Subscription refresh failures:\n" + failures.joinToString("\n")
                }
            }
        }.start()
    }

    private fun addOrRefreshSubscriptionGroup(
        name: String,
        url: String,
        existingGroup: SubscriptionGroup?
    ) {
        if (url.isBlank()) {
            setActionStatus("Subscription URL is empty.")
            return
        }
        if (existingGroup == null) {
            prepareNewSubscriptionImport(name, url)
        } else {
            startSubscriptionUrlSync(name, url, existingGroup, requestedLimit = null)
        }
    }

    private fun prepareNewSubscriptionImport(name: String, url: String) {
        setActionStatus("Fetching subscription group ${name.shortUi(28)}...")
        Thread {
            val result = runCatching {
                val subscriptionText = fetchSubscriptionText(url)
                val candidates = subscriptionCandidateTexts(subscriptionText)
                if (candidates.isEmpty()) {
                    throw ConfigParseException("Subscription contains no supported vless/vmess/trojan/ss links or Clash YAML proxies.")
                }
                SubscriptionImportPreview(
                    subscriptionText = subscriptionText,
                    totalCount = candidates.size,
                    transportSummary = summarizeSubscriptionCandidates(subscriptionText, candidates)
                )
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { preview ->
                        if (preview.totalCount > MAX_SUBSCRIPTION_LINKS) {
                            showSubscriptionImportLimitSheet(name, url, preview)
                        } else {
                            startFetchedSubscriptionSync(name, url, existingGroup = null, subscriptionText = preview.subscriptionText, requestedLimit = preview.totalCount)
                        }
                    },
                    onFailure = { error ->
                        setActionStatus("Subscription sync failed: ${error.message ?: error.javaClass.simpleName}")
                        refreshProfileButtons()
                    }
                )
            }
        }.start()
    }

    private fun showSubscriptionImportLimitSheet(
        name: String,
        url: String,
        preview: SubscriptionImportPreview
    ) {
        val safeAll = preview.totalCount.coerceAtMost(MAX_SUBSCRIPTION_TOTAL_PROFILES)
        showBottomSheet(
            title = "Large subscription found",
            subtitle = "${preview.totalCount} configs detected. Choose how many to save now."
        ) { dialog ->
            addView(TextView(this@MainActivity).apply {
                text = "Import fewer configs for a lighter phone list, or import all when you want the full provider queue. Queue tests stay manual and capped."
                textSize = 12.5f
                setTextColor(PearlPalette.TEXT_MUTED)
                setPadding(dp(8), dp(10), dp(8), dp(6))
            })
            val recommended = clampSubscriptionImportLimit(MAX_SUBSCRIPTION_LINKS, preview.totalCount)
            addView(bottomSheetActionRow("◎", "Recommended", "Save $recommended/${preview.totalCount} now. Fastest import; use Load more later.") {
                dialog.dismiss()
                startFetchedSubscriptionSync(name, url, existingGroup = null, subscriptionText = preview.subscriptionText, requestedLimit = recommended)
            })
            addView(bottomSheetActionRow("#", "Custom / all", "Type any amount up to $safeAll. Enter $safeAll for all available in this build.") {
                dialog.dismiss()
                promptCustomSubscriptionImportLimit(name, url, preview)
            })
        }
    }

    private fun promptCustomSubscriptionImportLimit(
        name: String,
        url: String,
        preview: SubscriptionImportPreview
    ) {
        val maxAllowed = preview.totalCount.coerceAtMost(MAX_SUBSCRIPTION_TOTAL_PROFILES)
        showBottomSheet(
            title = "Custom import amount",
            subtitle = "${preview.totalCount} configs detected. Enter 1 to $maxAllowed."
        ) { dialog ->
            val amountInput = EditText(this@MainActivity).apply {
                hint = "e.g. 300"
                textSize = 14f
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setText(maxAllowed.toString())
                setPadding(dp(14), 0, dp(14), 0)
                background = roundedBackground(PearlPalette.PEARL_GHOST, PearlPalette.HAIRLINE, radiusDp = 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54)
                ).apply { setMargins(0, dp(8), 0, dp(8)) }
            }
            addView(amountInput)
            addView(bottomSheetActionRow("✓", "Import custom amount", "Save the requested number now. Use $maxAllowed for all available in this build") {
                val requested = amountInput.text?.toString()?.trim()?.toIntOrNull()
                if (requested == null || requested <= 0) {
                    setActionStatus("Enter a valid number between 1 and $maxAllowed.")
                    return@bottomSheetActionRow
                }
                val limit = clampSubscriptionImportLimit(requested, preview.totalCount)
                dialog.dismiss()
                startFetchedSubscriptionSync(name, url, existingGroup = null, subscriptionText = preview.subscriptionText, requestedLimit = limit)
            })
            addView(bottomSheetActionRow("×", "Cancel", "Back without saving") {
                dialog.dismiss()
                setActionStatus("Subscription import cancelled.")
            })
        }
    }

    private fun startSubscriptionUrlSync(
        name: String,
        url: String,
        existingGroup: SubscriptionGroup?,
        requestedLimit: Int?
    ) {
        setActionStatus("Fetching subscription group ${name.shortUi(28)}...")
        Thread {
            val result = runCatching { syncSubscriptionGroupBlocking(name, url, existingGroup, requestedLimit = requestedLimit) }
            runOnUiThread {
                result.fold(
                    onSuccess = { sync -> handleSubscriptionSyncSuccess(sync) },
                    onFailure = { error -> handleSubscriptionSyncFailure(error) }
                )
            }
        }.start()
    }

    private fun startFetchedSubscriptionSync(
        name: String,
        url: String,
        existingGroup: SubscriptionGroup?,
        subscriptionText: String,
        requestedLimit: Int
    ) {
        setActionStatus("Saving ${requestedLimit} configs for ${name.shortUi(28)}...")
        Thread {
            val result = runCatching { syncFetchedSubscriptionGroupBlocking(name, url, existingGroup, subscriptionText, requestedLimit) }
            runOnUiThread {
                result.fold(
                    onSuccess = { sync -> handleSubscriptionSyncSuccess(sync) },
                    onFailure = { error -> handleSubscriptionSyncFailure(error) }
                )
            }
        }.start()
    }

    private fun handleSubscriptionSyncSuccess(sync: SubscriptionSyncResult) {
        selectedLocationGroupFilter = sync.group.id
        saveLocationViewPrefs()
        sync.profiles.firstOrNull()?.let { profile ->
            selectedProfile = profile
            selectedProfileId = profile.id
            importedConfig = loadProfileConfig(profile)
        }
        refreshProfileButtons()
        updateDashboardSummary()
        setActionStatus("Subscription group ${sync.group.displayName.shortUi(28)} synced: ${sync.profiles.size}/${sync.linkCount} profiles saved" +
            sync.transportSummary.statusSuffix() +
            if (sync.skippedCount > 0) ", ${sync.skippedCount} skipped." else ".")
        showSection(AppSection.PROFILES)
        maybeAutoRankBestProfile("subscription")
    }

    private fun handleSubscriptionSyncFailure(error: Throwable) {
        setActionStatus("Subscription sync failed: ${error.message ?: error.javaClass.simpleName}")
        refreshProfileButtons()
    }

    private fun loadMoreSubscriptionGroup(group: SubscriptionGroup) {
        val url = profileStore.loadSubscriptionUrl(group.id)
        if (url.isNullOrBlank()) {
            setActionStatus("${group.displayName.cleanProfileLabel().shortUi(24)} was imported from clipboard and cannot load more. Paste or add its URL again.")
            return
        }
        val nextLimit = nextSubscriptionProfileLimit(group.profileIds.size)
        if (nextLimit <= group.profileIds.size) {
            setActionStatus("${group.displayName.cleanProfileLabel().shortUi(24)} already reached the preview limit of $MAX_SUBSCRIPTION_TOTAL_PROFILES configs. Use search or refresh instead of loading thousands at once.")
            return
        }
        setActionStatus("Loading ${nextLimit - group.profileIds.size} more configs for ${group.displayName.cleanProfileLabel().shortUi(24)}...")
        Thread {
            val result = runCatching {
                syncSubscriptionGroupBlocking(group.displayName, url, existingGroup = group, requestedLimit = nextLimit)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { sync ->
                        selectedLocationGroupFilter = sync.group.id
                        saveLocationViewPrefs()
                        refreshProfileButtons()
                        updateDashboardSummary()
                        setActionStatus("Loaded ${sync.profiles.size}/${sync.linkCount} configs for ${sync.group.displayName.shortUi(28)}" +
                            sync.transportSummary.statusSuffix() +
                            if (sync.skippedCount > 0) ", ${sync.skippedCount} still outside the current cap." else ".")
                        showSection(AppSection.PROFILES)
                    },
                    onFailure = { error ->
                        setActionStatus("Load more failed: ${error.message ?: error.javaClass.simpleName}")
                        refreshProfileButtons()
                    }
                )
            }
        }.start()
    }

    private fun addClipboardSubscriptionGroup(name: String, subscriptionText: String) {
        setActionStatus("Importing subscription from clipboard...")
        Thread {
            val result = runCatching { syncClipboardSubscriptionGroupBlocking(name, subscriptionText) }
            runOnUiThread {
                result.fold(
                    onSuccess = { sync ->
                        selectedLocationGroupFilter = sync.group.id
                        saveLocationViewPrefs()
                        sync.profiles.firstOrNull()?.let { profile ->
                            selectedProfile = profile
                            selectedProfileId = profile.id
                            importedConfig = loadProfileConfig(profile)
                        }
                        refreshProfileButtons()
                        updateDashboardSummary()
                        setActionStatus("Clipboard subscription ${sync.group.displayName.shortUi(28)} imported: ${sync.profiles.size} profiles saved" +
                            sync.transportSummary.statusSuffix() +
                            if (sync.skippedCount > 0) ", ${sync.skippedCount} skipped." else ".")
                        showSection(AppSection.PROFILES)
                        maybeAutoRankBestProfile("clipboard subscription")
                    },
                    onFailure = { error ->
                        setActionStatus("Clipboard subscription import failed: ${error.message ?: error.javaClass.simpleName}")
                        refreshProfileButtons()
                    }
                )
            }
        }.start()
    }

    private fun syncSubscriptionGroupBlocking(
        name: String,
        url: String,
        existingGroup: SubscriptionGroup?,
        requestedLimit: Int? = null
    ): SubscriptionSyncResult {
        val subscriptionText = fetchSubscriptionText(url)
        return syncFetchedSubscriptionGroupBlocking(name, url, existingGroup, subscriptionText, requestedLimit)
    }

    private fun syncFetchedSubscriptionGroupBlocking(
        name: String,
        url: String,
        existingGroup: SubscriptionGroup?,
        subscriptionText: String,
        requestedLimit: Int? = null
    ): SubscriptionSyncResult {
        val existingName = existingGroup?.displayName?.cleanProfileLabel()
        val savedName = when {
            existingName.isNullOrBlank() -> name
            existingName.equals("Raw", ignoreCase = true) || existingName.equals("Subscription", ignoreCase = true) -> subscriptionNameFromUrl(url)
            else -> existingName
        }
        val storedGroup = profileStore.saveSubscriptionGroup(savedName, url)
        val candidateCount = subscriptionCandidateTexts(subscriptionText).size.takeIf { it > 0 }
        val profileLimit = requestedLimit
            ?: existingGroup?.profileIds?.size?.coerceAtLeast(MAX_SUBSCRIPTION_LINKS)
            ?: MAX_SUBSCRIPTION_LINKS
        return syncSubscriptionTextIntoGroup(
            storedGroup = storedGroup,
            subscriptionText = subscriptionText,
            resultVerb = "Synced",
            profileLimit = candidateCount?.let { profileLimit.coerceAtMost(it) } ?: profileLimit
        )
    }

    private fun syncClipboardSubscriptionGroupBlocking(
        name: String,
        subscriptionText: String
    ): SubscriptionSyncResult {
        val storedGroup = profileStore.saveClipboardSubscriptionGroup(name, subscriptionText)
        return syncSubscriptionTextIntoGroup(storedGroup, subscriptionText, resultVerb = "Imported from clipboard", profileLimit = MAX_SUBSCRIPTION_LINKS)
    }

    private fun subscriptionCandidateTexts(subscriptionText: String): List<String> {
        val links = V2RaySubscriptionParser.extractLinks(subscriptionText)
        return if (links.isNotEmpty()) links else ClashConfigParser.splitProxyTexts(subscriptionText)
    }

    private fun summarizeSubscriptionCandidates(subscriptionText: String, candidates: List<String>): String? {
        val links = V2RaySubscriptionParser.extractLinks(subscriptionText)
        return if (links.isNotEmpty()) summarizeTransportLabels(links) else summarizeClashProxyTexts(candidates)
    }

    private fun clampSubscriptionImportLimit(requested: Int, totalCount: Int): Int =
        requested.coerceIn(1, totalCount.coerceAtMost(MAX_SUBSCRIPTION_TOTAL_PROFILES).coerceAtLeast(1))

    private fun syncSubscriptionTextIntoGroup(
        storedGroup: SubscriptionGroup,
        subscriptionText: String,
        resultVerb: String,
        profileLimit: Int
    ): SubscriptionSyncResult {
        val candidates = subscriptionCandidateTexts(subscriptionText)
        if (candidates.isEmpty()) {
            throw ConfigParseException("Subscription contains no supported vless/vmess/trojan/ss links or Clash YAML proxies.")
        }

        val transportSummary = summarizeSubscriptionCandidates(subscriptionText, candidates)
        val importLimit = profileLimit.coerceIn(1, MAX_SUBSCRIPTION_TOTAL_PROFILES)
        val importCandidates = candidates.take(importLimit)
        val savedProfiles = mutableListOf<VpnProfile>()
        var skipped = 0
        importCandidates.forEachIndexed { index, candidateText ->
            val saved = runCatching {
                val config = ConfigImporter.parse(candidateText)
                val displayName = subscriptionProfileDisplayName(storedGroup, candidateText, config, index)
                val profileId = profileStore.stableSubscriptionProfileId(storedGroup.id, candidateText)
                profileStore.saveImportedConfig(config, displayName, stableProfileId = profileId)
            }.getOrNull()
            if (saved == null) skipped++ else savedProfiles += saved
        }
        skipped += (candidates.size - importCandidates.size).coerceAtLeast(0)
        if (savedProfiles.isEmpty()) {
            throw ConfigParseException("Subscription was read, but none of its profiles could be imported.")
        }

        val savedProfileIds = savedProfiles.map { it.id }
        val savedProfileIdSet = savedProfileIds.toSet()
        val staleProfileIds = storedGroup.profileIds.filterNot { it in savedProfileIdSet }
        staleProfileIds.forEach { staleId -> runCatching { profileStore.deleteProfile(staleId) } }
        val resultText = "$resultVerb ${savedProfiles.size}/${candidates.size} profiles" + transportSummary.statusSuffix()
        val updatedGroup = profileStore.markSubscriptionSynced(
            groupId = storedGroup.id,
            profileIds = savedProfileIds,
            result = resultText
        ) ?: storedGroup
        return SubscriptionSyncResult(updatedGroup, savedProfiles, candidates.size, skipped, transportSummary)
    }

    private fun subscriptionProfileDisplayName(
        group: SubscriptionGroup,
        link: String,
        config: ImportedConfig,
        index: Int
    ): String {
        val descriptor = V2RayLinkInspector.inspectLink(link)
        val inspectedName = descriptor?.displayName
            ?: V2RayLinkInspector.safeDisplayName(config.name)
        if (!inspectedName.isNullOrBlank()) return inspectedName.shortUi(72)
        val groupName = group.displayName.cleanProfileLabel().shortUi(28).ifBlank { "Subscription" }
        val transport = descriptor?.shortLabel?.takeIf { it.isNotBlank() }
        return listOfNotNull(groupName, "#${index + 1}", transport).joinToString(" • ").shortUi(72)
    }

    private fun summarizeTransportLabels(links: List<String>): String? {
        val counts = links.asSequence()
            .mapNotNull { link -> V2RayLinkInspector.inspectLink(link)?.shortLabel }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(4)
        if (counts.isEmpty()) return null
        val hidden = (links.size - counts.sumOf { it.value }).coerceAtLeast(0)
        return counts.joinToString(", ") { (label, count) -> "$label $count" } +
            if (hidden > 0) ", +$hidden other" else ""
    }

    private fun summarizeClashProxyTexts(proxyTexts: List<String>): String? {
        val counts = proxyTexts.take(MAX_SUBSCRIPTION_LINKS).asSequence()
            .mapNotNull { text -> runCatching { ConfigImporter.parse(text).warnings }.getOrNull() }
            .mapNotNull { warnings -> warnings.firstOrNull { it.startsWith("Detected Clash proxies:") } }
            .mapNotNull { warning -> warning.substringAfter("Detected Clash proxies:", "").substringBefore(". Secrets").trim().takeIf { it.isNotBlank() } }
            .map { label -> Regex("\\s+\\d+$").replace(label, "") }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(4)
        if (counts.isEmpty()) return "Clash ${proxyTexts.size}"
        val hidden = (proxyTexts.size - counts.sumOf { it.value }).coerceAtLeast(0)
        return counts.joinToString(", ") { (label, count) -> "$label $count" } +
            if (hidden > 0) ", +$hidden other" else ""
    }

    private fun String?.statusSuffix(): String = this
        ?.takeIf { it.isNotBlank() }
        ?.let { " • $it" }
        .orEmpty()

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
            status.text = "Clipboard is empty. Copy a V2Ray/Xray link, subscription URL, or advanced fallback config first."
            return
        }

        normalizedSubscriptionUrl(clipText)?.let { subscriptionUrl ->
            addOrRefreshSubscriptionGroup(subscriptionNameFromUrl(subscriptionUrl), subscriptionUrl, existingGroup = null)
            return
        }

        val subscriptionLinks = V2RaySubscriptionParser.extractLinks(clipText)
        val directSingleLink = subscriptionLinks.size == 1 && startsWithSingleV2RayLink(clipText)
        if (subscriptionLinks.isNotEmpty() && !directSingleLink) {
            addClipboardSubscriptionGroup("Clipboard subscription", clipText)
            return
        }

        importConfigText(clipText, null)
    }

    private fun normalizedSubscriptionUrl(text: String): String? {
        val candidate = text.trim()
        if (candidate.isBlank()) return null
        if (candidate.isHttpUrl()) return candidate
        val directLinkSchemes = listOf("vless://", "vmess://", "trojan://", "ss://")
        if (directLinkSchemes.none { candidate.startsWith(it, ignoreCase = true) }) {
            firstHttpUrlInText(candidate)?.let { return it }
        }
        if (candidate.contains(Regex("\\s"))) return null

        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(java.util.Locale.US) ?: return null
        if (scheme !in setOf("hiddify", "v2rayng", "nekobox", "clash", "clashmeta", "sing-box", "singbox", "stash")) {
            return null
        }

        val queryUrl = runCatching {
            listOf("url", "link", "sub", "subscription", "config")
                .firstNotNullOfOrNull { key -> uri.getQueryParameter(key)?.takeIf { it.isHttpUrl() } }
        }.getOrNull()
        if (queryUrl != null) return queryUrl

        return firstHttpUrlInText(Uri.decode(candidate))
    }

    private fun firstHttpUrlInText(text: String): String? {
        val httpsIndex = text.indexOf("https://", ignoreCase = true).takeIf { it >= 0 }
        val httpIndex = text.indexOf("http://", ignoreCase = true).takeIf { it >= 0 }
        val start = listOfNotNull(httpsIndex, httpIndex).minOrNull() ?: return null
        val end = text.indexOfFirstFrom(start) { char ->
            char.isWhitespace() || char == '"' || char == '\'' || char == '<' || char == '>'
        }.let { if (it < 0) text.length else it }
        return text.substring(start, end).trimEnd(',', ';', ')', ']', '}').takeIf { it.isHttpUrl() }
    }

    private fun String.isHttpUrl(): Boolean = runCatching {
        val parsed = URL(trim())
        parsed.protocol.equals("https", ignoreCase = true) || parsed.protocol.equals("http", ignoreCase = true)
    }.getOrDefault(false)

    private inline fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (index in startIndex until length) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun subscriptionNameFromUrl(text: String): String = runCatching {
        val url = URL(text.trim())
        val host = url.host.removePrefix("www.").takeIf { it.isNotBlank() }
        val pathParts = url.path.split('/').filter { it.isNotBlank() }
        val fileName = pathParts.lastOrNull()
            ?.substringBeforeLast('.', missingDelimiterValue = pathParts.lastOrNull().orEmpty())
            ?.humanizeSubscriptionToken()
        val isRawGithub = host?.equals("raw.githubusercontent.com", ignoreCase = true) == true
        when {
            isRawGithub && fileName?.equals("Clash", ignoreCase = true) == true -> "Clash"
            isRawGithub && !fileName.isNullOrBlank() -> fileName
            isRawGithub -> pathParts.getOrNull(1)?.humanizeSubscriptionToken()
            !fileName.isNullOrBlank() && fileName.length in 4..24 -> fileName
            else -> host?.substringBefore('.')?.humanizeSubscriptionToken()
        } ?: "Clipboard subscription"
    }.getOrDefault("Clipboard subscription")

    private fun String.humanizeSubscriptionToken(): String = replace('-', ' ')
        .replace('_', ' ')
        .substringBefore('?')
        .collapseLabelWhitespace()
        .split(' ')
        .filter { it.isNotBlank() && it.lowercase(java.util.Locale.US) !in setOf("main", "master", "verified", "raw") }
        .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it } }
        .ifBlank { this.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it } }

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
            val savedProfileLine = saveImportedProfile(config, importedProfileDisplayName(config, text, name))
            val endpointLines = config.endpoints.joinToString("\n") { endpoint ->
                "• ${endpoint.protocol}  ${endpoint.host}:${endpoint.port}" +
                    (endpoint.verifyHost?.let { "  verify: $it" } ?: "")
            }
            val warnings = if (config.warnings.isEmpty()) "" else
                "\n\nWarnings:\n" + config.warnings.joinToString("\n") { "• $it" }
            val nextStep = when (config.kind) {
                ConfigKind.WIREGUARD ->
                    "\n\nNext: WireGuard is kept as an advanced fallback only; if UDP is blocked, import a V2Ray/Xray profile instead."
                ConfigKind.OPENVPN ->
                    "\n\nNext: OpenVPN is an advanced handoff path; save a pinned TCP config and import it in an OpenVPN-compatible client if you explicitly need it."
                ConfigKind.V2RAY ->
                    "\n\nNext: tap Connect. Advanced endpoint probe is optional."
                ConfigKind.SING_BOX ->
                    "\n\nNext: tap Connect to try the first Xray-compatible sing-box outbound. Unsupported sing-box features stay saved for diagnostics until the embedded sing-box engine lands."
                ConfigKind.CLASH ->
                    "\n\nNext: tap Connect to try the first Xray-compatible Clash proxy. Unsupported Clash features stay saved for diagnostics until the full mapper lands."
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


    private fun importedProfileDisplayName(config: ImportedConfig, rawText: String, externalName: String?): String? {
        if (config.kind == ConfigKind.V2RAY) {
            V2RayLinkInspector.inspect(rawText)?.displayName?.let { return it }
            if (externalName.isNullOrBlank()) {
                V2RayLinkInspector.safeDisplayName(config.name)?.let { return it }
            }
        }
        if ((config.kind == ConfigKind.SING_BOX || config.kind == ConfigKind.CLASH) && externalName.isNullOrBlank()) {
            V2RayLinkInspector.safeDisplayName(config.name)?.let { return it }
        }
        return externalName ?: config.name
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
        profileRowStatusViews.clear()
        profileRowSubtitleViews.clear()
        profileListContainer.removeAllViews()
        val storedProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        val allProfiles = activeLocationProfiles(storedProfiles, groups)
        normalizeLocationGroupFilter(groups)
        val query = locationSearchQuery.trim()
        val profiles = allProfiles.filter { matchesLocationSearch(it, query) }
        val groupedProfiles = profilesForLocationFilter(profiles, groups)
        val scopedProfiles = applyLocationRuntimeFilter(groupedProfiles)
        refreshSubscriptionGroupButtons(groups, allProfiles, profiles, scopedProfiles)
        val currentRenderKey = locationRenderStateKey(groups, allProfiles.size, scopedProfiles.size, query)
        if (currentRenderKey != locationRenderKey) {
            locationRenderKey = currentRenderKey
            locationRenderLimit = INITIAL_PROFILE_RENDER_ROWS
        }
        val effectiveProfileButtonLimit = if (scopedProfiles.size > INITIAL_PROFILE_RENDER_ROWS) {
            locationRenderLimit.coerceIn(INITIAL_PROFILE_RENDER_ROWS, MAX_PROFILE_BUTTONS)
        } else {
            MAX_PROFILE_BUTTONS
        }

        if (allProfiles.isEmpty() || query.isNotBlank()) {
            profileListContainer.addView(TextView(this).apply {
                val scope = locationFilterLabel(groups)
                text = if (allProfiles.isEmpty()) {
                    "No saved configs yet."
                } else {
                    "$scope search (${scopedProfiles.size}/${allProfiles.size})"
                }
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(PearlPalette.INK_SOFT)
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        if (allProfiles.isNotEmpty() && scopedProfiles.isEmpty()) {
            profileListContainer.addView(TextView(this).apply {
                text = when {
                    selectedLocationRuntimeFilter != LOCATION_RUNTIME_ALL -> "No ${locationRuntimeFilterLabel()} configs in ${locationFilterLabel(groups)}. Change Runtime filter in Queue tools."
                    query.isNotBlank() -> "No matching configs in ${locationFilterLabel(groups)}. Try another tab, country, operator, or host."
                    selectedLocationGroupFilter == LOCATION_FILTER_MANUAL -> "No imported configs in this view."
                    selectedLocationGroupFilter != LOCATION_FILTER_ALL -> "This subscription profile has no saved configs yet. Refresh it or paste the subscription again."
                    else -> "No configs in this tab yet."
                }
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(PearlPalette.TEXT_MUTED)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }

        val shownIds = mutableSetOf<String>()
        var shown = 0
        fun addUniqueSection(title: String, sectionProfiles: List<VpnProfile>) {
            if (shown >= effectiveProfileButtonLimit) return
            val unique = sectionProfiles.filter { it.id !in shownIds }
            val limited = unique.take(effectiveProfileButtonLimit - shown)
            if (limited.isEmpty()) return
            shownIds.addAll(limited.map { it.id })
            shown += addLocationSection(profileListContainer, title, limited, effectiveProfileButtonLimit - shown)
        }

        val rankedScoped = scopedProfiles.sortedWith(locationSortComparator())
        when (selectedLocationGroupFilter) {
            LOCATION_FILTER_ALL -> {
                if (query.isBlank()) {
                    addUniqueSection("Recommended", rankedScoped.filter { profileHasGoodLatencySignal(it) }.take(MAX_RECOMMENDED_PROFILES))
                    addUniqueSection("All configs", rankedScoped)
                } else {
                    addUniqueSection("Search results", rankedScoped)
                }
            }
            LOCATION_FILTER_MANUAL -> addUniqueSection(
                if (query.isBlank()) "Imported configs" else "Imported results",
                rankedScoped
            )
            else -> addUniqueSection(
                "Subscription • ${locationFilterLabel(groups).shortUi(24)}",
                rankedScoped
            )
        }

        if (scopedProfiles.size > shown) {
            profileListContainer.addView(locationRenderMoreButton(shown, scopedProfiles.size, locationFilterLabel(groups)))
        }
        updateDashboardSummary()
    }

    private fun locationRenderStateKey(
        groups: List<SubscriptionGroup>,
        allCount: Int,
        scopedCount: Int,
        query: String
    ): String = listOf(
        selectedLocationGroupFilter,
        selectedLocationRuntimeFilter,
        selectedLocationSortMode,
        query.lowercase(java.util.Locale.US),
        allCount.toString(),
        scopedCount.toString(),
        groups.joinToString("|") { "${it.id}:${it.profileIds.size}:${subscriptionTotalCount(it) ?: -1}" }
    ).joinToString("#")

    private fun locationRenderMoreButton(shown: Int, total: Int, scope: String): TextView = TextView(this).apply {
        val next = minOf(total, MAX_PROFILE_BUTTONS, shown + PROFILE_RENDER_STEP)
        text = if (next >= total || next >= MAX_PROFILE_BUTTONS) {
            "Showing $shown of $total in ${scope.shortUi(20)} • tap to show all visible rows"
        } else {
            "Showing $shown of $total in ${scope.shortUi(20)} • tap +${next - shown} • long-press all (slower)"
        }
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(PearlPalette.ACCENT_BLUE)
        background = roundedBackground(PearlPalette.ACCENT_SOFT, PearlPalette.HAIRLINE, radiusDp = 18)
        setPadding(dp(10), dp(11), dp(10), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(10), 0, dp(4))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            locationRenderLimit = next.coerceAtLeast(INITIAL_PROFILE_RENDER_ROWS)
            refreshProfileButtons(syncVerified = false)
        }
        setOnLongClickListener {
            locationRenderLimit = minOf(total, MAX_PROFILE_BUTTONS).coerceAtLeast(INITIAL_PROFILE_RENDER_ROWS)
            refreshProfileButtons(syncVerified = false)
            true
        }
    }

    private fun loadLocationViewPrefs() {
        selectedLocationGroupFilter = sanitizeLocationGroupFilter(
            appSettings.getString(KEY_LOCATION_GROUP_FILTER, LOCATION_FILTER_ALL)
        )
        selectedLocationRuntimeFilter = sanitizeLocationRuntimeFilter(
            appSettings.getString(KEY_LOCATION_RUNTIME_FILTER, LOCATION_RUNTIME_ALL)
        )
        selectedLocationSortMode = sanitizeLocationSortMode(
            appSettings.getString(KEY_LOCATION_SORT_MODE, LOCATION_SORT_RECOMMENDED)
        )
        locationSearchQuery = ""
    }

    private fun saveLocationViewPrefs() {
        appSettings.edit()
            .putString(KEY_LOCATION_GROUP_FILTER, sanitizeLocationGroupFilter(selectedLocationGroupFilter))
            .putString(KEY_LOCATION_RUNTIME_FILTER, sanitizeLocationRuntimeFilter(selectedLocationRuntimeFilter))
            .putString(KEY_LOCATION_SORT_MODE, sanitizeLocationSortMode(selectedLocationSortMode))
            .apply()
    }

    private fun resetLocationViewPrefs(clearSearch: Boolean) {
        selectedLocationGroupFilter = LOCATION_FILTER_ALL
        selectedLocationRuntimeFilter = LOCATION_RUNTIME_ALL
        selectedLocationSortMode = LOCATION_SORT_RECOMMENDED
        if (clearSearch) locationSearchQuery = ""
        saveLocationViewPrefs()
    }

    private fun sanitizeLocationGroupFilter(value: String?): String = when {
        value.isNullOrBlank() -> LOCATION_FILTER_ALL
        value == LOCATION_FILTER_MANUAL -> LOCATION_FILTER_ALL
        else -> value
    }

    private fun sanitizeLocationRuntimeFilter(value: String?): String = when (value) {
        LOCATION_RUNTIME_READY -> LOCATION_RUNTIME_READY
        LOCATION_RUNTIME_ATTENTION -> LOCATION_RUNTIME_ATTENTION
        else -> LOCATION_RUNTIME_ALL
    }

    private fun sanitizeLocationSortMode(value: String?): String = when (value) {
        LOCATION_SORT_NEWEST -> LOCATION_SORT_NEWEST
        LOCATION_SORT_LATENCY -> LOCATION_SORT_LATENCY
        LOCATION_SORT_RUNTIME_READY -> LOCATION_SORT_RUNTIME_READY
        else -> LOCATION_SORT_RECOMMENDED
    }

    private fun normalizeLocationGroupFilter(groups: List<SubscriptionGroup>) {
        val before = selectedLocationGroupFilter
        selectedLocationGroupFilter = sanitizeLocationGroupFilter(selectedLocationGroupFilter)
        if (selectedLocationGroupFilter != LOCATION_FILTER_ALL && groups.none { it.id == selectedLocationGroupFilter }) {
            selectedLocationGroupFilter = LOCATION_FILTER_ALL
        }
        selectedLocationRuntimeFilter = sanitizeLocationRuntimeFilter(selectedLocationRuntimeFilter)
        selectedLocationSortMode = sanitizeLocationSortMode(selectedLocationSortMode)
        if (selectedLocationGroupFilter != before) saveLocationViewPrefs()
    }

    private fun profilesForLocationFilter(
        profiles: List<VpnProfile>,
        groups: List<SubscriptionGroup>
    ): List<VpnProfile> {
        val subscriptionProfileIds = groups.flatMap { it.profileIds }.toSet()
        val profileById = profiles.associateBy { it.id }
        return when (selectedLocationGroupFilter) {
            LOCATION_FILTER_ALL -> if (groups.isEmpty()) profiles else subscriptionProfileIds.mapNotNull { profileById[it] }
            LOCATION_FILTER_MANUAL -> profiles
            else -> groups.firstOrNull { it.id == selectedLocationGroupFilter }
                ?.profileIds
                ?.mapNotNull { profileById[it] }
                .orEmpty()
        }
    }

    private fun applyLocationRuntimeFilter(profiles: List<VpnProfile>): List<VpnProfile> = when (selectedLocationRuntimeFilter) {
        LOCATION_RUNTIME_READY -> profiles.filter { isXrayReadyProfile(it) }
        LOCATION_RUNTIME_ATTENTION -> profiles.filter { !isXrayReadyProfile(it) }
        else -> profiles
    }

    private fun isXrayReadyProfile(profile: VpnProfile): Boolean =
        profile.kind in setOf(VpnProfileKind.XRAY, VpnProfileKind.SING_BOX, VpnProfileKind.CLASH) &&
            profileRuntimeCompatibility(profile).connectReady

    private fun locationRuntimeFilterLabel(): String = when (selectedLocationRuntimeFilter) {
        LOCATION_RUNTIME_READY -> "Xray-ready"
        LOCATION_RUNTIME_ATTENTION -> "Needs attention"
        else -> "All runtime"
    }

    private fun locationRuntimeFilterDescription(): String = when (selectedLocationRuntimeFilter) {
        LOCATION_RUNTIME_READY -> "Only profiles currently startable or mapped through embedded runtime"
        LOCATION_RUNTIME_ATTENTION -> "Only profiles with missing keys, mapper/runtime needs, or handoff-only paths"
        else -> "Showing every config in the selected tab"
    }

    private fun locationFilterLabel(groups: List<SubscriptionGroup>): String = when (selectedLocationGroupFilter) {
        LOCATION_FILTER_ALL -> "All"
        LOCATION_FILTER_MANUAL -> "Imported"
        else -> groups.firstOrNull { it.id == selectedLocationGroupFilter }
            ?.displayName
            ?.cleanProfileLabel()
            ?.ifBlank { "Subscription" }
            ?: "Subscription"
    }

    private fun testLocationFilter(filter: String, label: String = "Quick check") {
        val storedProfiles = runCatching { profileStore.listProfiles() }.getOrDefault(emptyList())
        val groups = runCatching { profileStore.listSubscriptionGroups() }.getOrDefault(emptyList())
        val allProfiles = activeLocationProfiles(storedProfiles, groups)
        selectedLocationGroupFilter = filter
        normalizeLocationGroupFilter(groups)
        saveLocationViewPrefs()
        val query = locationSearchQuery.trim()
        val filtered = if (query.isBlank()) allProfiles else allProfiles.filter { matchesLocationSearch(it, query) }
        val grouped = profilesForLocationFilter(filtered, groups)
        val scoped = applyLocationRuntimeFilter(grouped).sortedWith(locationSortComparator())
        val scope = locationFilterLabel(groups)
        refreshProfileButtons(syncVerified = false)
        if (scoped.isEmpty()) {
            setActionStatus("No configs in $scope to test.")
            return
        }
        val capped = scoped.take(quickCheckProfileLimit()).size
        setActionStatus("$label for $scope / ${locationRuntimeFilterLabel()}: testing $capped/${scoped.size} configs without connecting. Large queues are never auto-tested.")
        rankProfilesAndSelectBest(
            inputProfiles = scoped,
            reason = "queue tools $scope $label",
            autoSelect = false,
            scopeLabel = scope.shortUi(28),
            testLabel = label
        )
    }

    private fun refreshSubscriptionGroupButtons(
        groups: List<SubscriptionGroup>,
        allProfiles: List<VpnProfile>,
        filteredProfiles: List<VpnProfile>,
        visibleScopedProfiles: List<VpnProfile>
    ) {
        if (!::subscriptionGroupContainer.isInitialized) return
        subscriptionGroupContainer.removeAllViews()
        normalizeLocationGroupFilter(groups)
        val allProfileIds = allProfiles.map { it.id }.toSet()
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
        val allTotal = groups.mapNotNull { subscriptionTotalCount(it) }.takeIf { it.isNotEmpty() }?.sum()
        tabRow.addView(locationFilterTab("All", subscriptionCountLabel(allProfiles.size, allTotal), selectedLocationGroupFilter == LOCATION_FILTER_ALL) {
            selectedLocationGroupFilter = LOCATION_FILTER_ALL
            saveLocationViewPrefs()
            refreshProfileButtons(syncVerified = false)
        })
        groups.take(MAX_SUBSCRIPTION_GROUP_BUTTONS).forEach { group ->
            val groupCount = group.profileIds.count { it in allProfileIds }
            tabRow.addView(locationFilterTab(group.displayName.cleanProfileLabel().shortUi(16), subscriptionCountLabel(groupCount, subscriptionTotalCount(group)), selectedLocationGroupFilter == group.id) {
                selectedLocationGroupFilter = group.id
                saveLocationViewPrefs()
                refreshProfileButtons(syncVerified = false)
            })
        }
        val groupedScoped = profilesForLocationFilter(filteredProfiles, groups)
        val scoped = visibleScopedProfiles
        val activeGroup = groups.firstOrNull { it.id == selectedLocationGroupFilter }
        val scope = locationFilterLabel(groups)
        subscriptionGroupContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, 0, 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(tabRow)
            })
            addView(queueMenuButton {
                showQueueToolsSheet(groups, scoped, activeGroup, scope)
            })
        })
        if (selectedLocationRuntimeFilter != LOCATION_RUNTIME_ALL || selectedLocationSortMode != LOCATION_SORT_RECOMMENDED) {
            subscriptionGroupContainer.addView(TextView(this).apply {
                text = "Runtime: ${locationRuntimeFilterLabel()} • Sort: ${locationSortLabel()} • ${scoped.size}/${groupedScoped.size} in ${scope.shortUi(18)} • tap reset"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(PearlPalette.TEXT_MUTED)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener {
                    selectedLocationRuntimeFilter = LOCATION_RUNTIME_ALL
                    selectedLocationSortMode = LOCATION_SORT_RECOMMENDED
                    saveLocationViewPrefs()
                    refreshProfileButtons(syncVerified = false)
                }
            })
        }
        if (groups.size > MAX_SUBSCRIPTION_GROUP_BUTTONS) {
            subscriptionGroupContainer.addView(TextView(this).apply {
                text = "Showing ${MAX_SUBSCRIPTION_GROUP_BUTTONS} of ${groups.size} subscription tabs."
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(PearlPalette.TEXT_MUTED)
                setPadding(dp(8), dp(4), dp(8), dp(2))
            })
        }
    }

    private fun queueMenuButton(onClick: () -> Unit): TextView = TextView(this).apply {
        text = "⋯"
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        includeFontPadding = false
        setTextColor(PearlPalette.ACCENT_BLUE)
        background = roundedBackground(PearlPalette.ACCENT_SOFT, PearlPalette.HAIRLINE, radiusDp = 17)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(34)).apply {
            setMargins(dp(5), 0, 0, 0)
        }
        isClickable = true
        isFocusable = true
        contentDescription = "Queue tools menu"
        setOnClickListener { onClick() }
    }

    private fun showQueueToolsSheet(
        groups: List<SubscriptionGroup>,
        scopedProfiles: List<VpnProfile>,
        activeGroup: SubscriptionGroup?,
        scope: String
    ) {
        val capped = scopedProfiles.take(quickCheckProfileLimit()).size
        showBottomSheet(
            title = "Queue tools",
            subtitle = "$scope • ${locationRuntimeFilterLabel()} • sort ${locationSortLabel()} • testing capped at $capped/${scopedProfiles.size}"
        ) { dialog ->
            addView(bottomSheetActionRow("◎", "Runtime filter", locationRuntimeFilterDescription()) {
                dialog.dismiss()
                showLocationRuntimeFilterSheet()
            })
            addView(bottomSheetActionRow("↕", "Sort", locationSortDescription()) {
                dialog.dismiss()
                showLocationSortSheet()
            })
            addView(bottomSheetActionRow("◷", "Quick check", "Fast endpoint reachability for up to $capped configs in this queue") {
                dialog.dismiss()
                testLocationFilter(selectedLocationGroupFilter, label = "Quick check")
            })
            val realDelayCap = scopedProfiles.take(realDelayProfileLimit()).size
            addView(bottomSheetActionRow("✓", "Real delay", "Xray-core proxy delay for up to $realDelayCap configs before VPN connect") {
                dialog.dismiss()
                runRealDelayForLocationFilter(selectedLocationGroupFilter)
            })
            if (activeGroup != null) {
                val total = subscriptionTotalCount(activeGroup)
                val canLoadMore = !profileStore.loadSubscriptionUrl(activeGroup.id).isNullOrBlank() &&
                    activeGroup.profileIds.size < MAX_SUBSCRIPTION_TOTAL_PROFILES &&
                    (total == null || activeGroup.profileIds.size < total)
                if (canLoadMore) {
                    val nextLimit = nextSubscriptionProfileLimit(activeGroup.profileIds.size)
                    addView(bottomSheetActionRow("＋", "Load more configs", "Increase this subscription from ${activeGroup.profileIds.size} to up to $nextLimit saved configs") {
                        dialog.dismiss()
                        loadMoreSubscriptionGroup(activeGroup)
                    })
                }
            }
            if (groups.isNotEmpty()) {
                addView(bottomSheetActionRow("↻", if (activeGroup == null) "Refresh all subscriptions" else "Refresh ${activeGroup.displayName.cleanProfileLabel().shortUi(24)}", if (activeGroup == null) "Update all saved subscription URLs" else "Update only the selected subscription queue") {
                    dialog.dismiss()
                    if (activeGroup == null) refreshAllSubscriptionGroups() else refreshSubscriptionGroup(activeGroup)
                })
            }
            addView(bottomSheetActionRow("⌕", "Search", "Filter country, operator, or host") {
                dialog.dismiss()
                showLocationSearchSheet()
            })
        }
    }

    private fun showLocationRuntimeFilterSheet() {
        showBottomSheet(
            title = "Runtime filter",
            subtitle = "Filter visible rows only. It does not test or connect the queue."
        ) { dialog ->
            addView(bottomSheetActionRow(if (selectedLocationRuntimeFilter == LOCATION_RUNTIME_ALL) "✓" else "◎", "All configs", "Show every config in the selected tab") {
                selectedLocationRuntimeFilter = LOCATION_RUNTIME_ALL
                saveLocationViewPrefs()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
            addView(bottomSheetActionRow(if (selectedLocationRuntimeFilter == LOCATION_RUNTIME_READY) "✓" else "X", "Only Xray-ready", "Show profiles that are startable or mapped through the embedded runtime") {
                selectedLocationRuntimeFilter = LOCATION_RUNTIME_READY
                saveLocationViewPrefs()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
            addView(bottomSheetActionRow(if (selectedLocationRuntimeFilter == LOCATION_RUNTIME_ATTENTION) "✓" else "!", "Needs attention", "Show missing-key, mapper/runtime-needed, or handoff-only profiles") {
                selectedLocationRuntimeFilter = LOCATION_RUNTIME_ATTENTION
                saveLocationViewPrefs()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
        }
    }

    private fun showLocationSortSheet() {
        showBottomSheet(
            title = "Sort locations",
            subtitle = "Sorting only reorders visible rows; it does not test or connect the queue."
        ) { dialog ->
            addView(bottomSheetActionRow(if (selectedLocationSortMode == LOCATION_SORT_RECOMMENDED) "✓" else "★", "Recommended", "Saved successful/verified and favorites first") {
                selectedLocationSortMode = LOCATION_SORT_RECOMMENDED
                saveLocationViewPrefs()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
            addView(bottomSheetActionRow(if (selectedLocationSortMode == LOCATION_SORT_NEWEST) "✓" else "↻", "Newest", "Newest saved/refreshed configs first") {
                selectedLocationSortMode = LOCATION_SORT_NEWEST
                saveLocationViewPrefs()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
            addView(bottomSheetActionRow(if (selectedLocationSortMode == LOCATION_SORT_LATENCY) "✓" else "◷", "Latency", "Lowest saved Quick/Real-delay latency first") {
                selectedLocationSortMode = LOCATION_SORT_LATENCY
                saveLocationViewPrefs()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
            addView(bottomSheetActionRow(if (selectedLocationSortMode == LOCATION_SORT_RUNTIME_READY) "✓" else "X", "Runtime-ready first", "Xray-ready/mapped profiles first without running tests") {
                selectedLocationSortMode = LOCATION_SORT_RUNTIME_READY
                saveLocationViewPrefs()
                dialog.dismiss()
                refreshProfileButtons(syncVerified = false)
            })
        }
    }

    private fun locationFilterTab(
        label: String,
        countLabel: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = "${label.shortUi(18)} ($countLabel)"
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (selected) PearlPalette.ACCENT_BLUE else PearlPalette.INK)
        background = roundedBackground(
            fillColor = if (selected) PearlPalette.ACCENT_SOFT else PearlPalette.PEARL_WHITE,
            strokeColor = if (selected) PearlPalette.ACCENT_LILAC else PearlPalette.HAIRLINE,
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
            background = roundedBackground(PearlPalette.ACCENT_SOFT, PearlPalette.HAIRLINE, radiusDp = 18)
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
                setTextColor(PearlPalette.ACCENT_BLUE)
                background = roundedBackground(PearlPalette.PEARL_WHITE, PearlPalette.HAIRLINE, radiusDp = 14)
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
                    setTextColor(PearlPalette.INK)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "$visibleCount visible • ${subscriptionGroupSubtitle(group).shortUi(44)}"
                    textSize = 10.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(PearlPalette.TEXT_MUTED)
                })
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
            if (profiles.isEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = "No configs are saved in this group yet. Refresh it or paste the subscription again."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(PearlPalette.TEXT_MUTED)
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
                    setTextColor(PearlPalette.TEXT_MUTED)
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

    private fun nextSubscriptionProfileLimit(currentCount: Int): Int =
        (if (currentCount < MAX_SUBSCRIPTION_LINKS) MAX_SUBSCRIPTION_LINKS else currentCount + SUBSCRIPTION_LOAD_MORE_STEP)
            .coerceAtMost(MAX_SUBSCRIPTION_TOTAL_PROFILES)

    private fun subscriptionCountLabel(loaded: Int, total: Int?): String =
        if (total != null && total > loaded) "$loaded/$total" else loaded.toString()

    private fun subscriptionTotalCount(group: SubscriptionGroup): Int? = Regex("\\b\\d+/(\\d+) profiles")
        .find(group.lastResult.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    private fun subscriptionGroupSubtitle(group: SubscriptionGroup): String {
        val total = subscriptionTotalCount(group)
        val syncText = group.lastSyncEpochMs?.let {
            if (total != null && total > group.profileIds.size) "${group.profileIds.size}/$total loaded" else "${group.profileIds.size} profiles"
        } ?: "Not synced yet"
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
            status.text = "Import a V2Ray/Xray config first, or explicitly import WireGuard/OpenVPN as advanced fallbacks."
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
                        score = summary.bestScore,
                        testKind = TEST_KIND_QUICK
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
        config.warnings.take(8).forEach { warning -> lines += "Import note: $warning" }

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
            VpnProtocol.SING_BOX_TLS,
            VpnProtocol.SING_BOX_TCP,
            VpnProtocol.SING_BOX_REALITY,
            VpnProtocol.SING_BOX_UNKNOWN,
            VpnProtocol.CLASH_TLS,
            VpnProtocol.CLASH_TCP,
            VpnProtocol.CLASH_REALITY,
            VpnProtocol.CLASH_UNKNOWN,
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
        val verifiedAt = lastVerifiedEpochMs ?: return null
        val state = ProfileLatencyState(
            label = "Verified",
            latencyMs = lastVerifiedLatencyMs,
            success = true,
            checkedAtEpochMs = verifiedAt,
            network = lastVerifiedNetwork,
            kindRank = 0
        )
        val result = state.latencyMs?.let { "${it}ms" } ?: "verified"
        val age = if (isLatencyFresh(state.checkedAtEpochMs)) "fresh" else "old"
        val network = state.network?.takeIf { it.isNotBlank() }?.let { " • ${compactNetworkLabel(it) ?: it}" }.orEmpty()
        return "Last good: $result • $age$network"
    }

    private fun VpnProfile.lastTestMiniLabel(): String? = profileLatencyMiniLabel(this)

    private fun VpnProfile.lastTestLabel(): String? {
        val state = profileLatencyState(this) ?: return null
        val result = if (state.success == true) {
            state.latencyMs?.let { "OK ${it}ms" } ?: "OK"
        } else {
            "failed"
        }
        val score = state.score?.let { " • score $it" }.orEmpty()
        val age = if (isLatencyFresh(state.checkedAtEpochMs)) " • fresh" else " • old"
        val network = state.network?.takeIf { it.isNotBlank() }?.let { " • ${compactNetworkLabel(it) ?: it}" }.orEmpty()
        return "Last ${state.label}: $result$score$age$network"
    }

    private fun String.withoutFlagEmojis(): String {
        val cleaned = StringBuilder(length)
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            if (codePoint !in 0x1F1E6..0x1F1FF) {
                cleaned.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return cleaned.toString().collapseLabelWhitespace()
    }

    private fun String.cleanProfileLabel(): String {
        val decoded = StringBuilder(length)
        var index = 0
        while (index < length) {
            val current = this[index]
            if (current == '\\' && index + 2 < length && this[index + 1] == '\\' && (this[index + 2] == 'u' || this[index + 2] == 'U')) {
                index++
                continue
            }
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
                    'U' -> {
                        val hexStart = index + 2
                        val hexEnd = hexStart + 8
                        if (hexEnd <= length) {
                            val hex = substring(hexStart, hexEnd)
                            if (hex.all { Character.digit(it, 16) >= 0 }) {
                                val codePoint = hex.toInt(16)
                                if (Character.isValidCodePoint(codePoint)) {
                                    decoded.append(String(Character.toChars(codePoint)))
                                    index = hexEnd
                                    continue
                                }
                            }
                        }
                        var skip = hexStart
                        while (skip < length && skip < hexStart + 8 && Character.digit(this[skip], 16) >= 0) skip++
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

    private data class SubscriptionImportPreview(
        val subscriptionText: String,
        val totalCount: Int,
        val transportSummary: String?
    )

    private data class SubscriptionSyncResult(
        val group: SubscriptionGroup,
        val profiles: List<VpnProfile>,
        val linkCount: Int,
        val skippedCount: Int,
        val transportSummary: String?
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
        const val INITIAL_PROFILE_RENDER_ROWS = 160
        const val PROFILE_RENDER_STEP = 160
        const val MAX_PROFILE_BUTTONS = 2_000
        const val MAX_PROFILE_SHEET_CHOICES = 40
        const val MAX_SUBSCRIPTION_GROUP_BUTTONS = 20
        const val MAX_GROUP_PROFILE_PREVIEW = 16
        const val MAX_RECOMMENDED_PROFILES = 5
        const val MAX_AUTO_RANK_PROFILES = 36
        const val MAX_REAL_DELAY_PROFILES = 8
        const val MAX_SMART_FALLBACK_ATTEMPTS = 3
        const val MAX_SMART_FALLBACK_CANDIDATES = 24
        const val SMART_FALLBACK_BASE_DELAY_MS = 1_500L
        const val SMART_FALLBACK_SUPPRESS_FAILURE_MS = 5_000L
        const val MAX_QUICK_CHECK_SETTING_LIMIT = 200
        const val MAX_REAL_DELAY_SETTING_LIMIT = 32
        const val MAX_REAL_DELAY_URLS = 4
        const val MAX_VPN_DNS_SERVERS = 4
        const val MAX_BYPASS_PACKAGES = 64
        const val MAX_PARALLEL_PING_TESTS = 6
        const val SUBSCRIPTION_PROFILE_PREFIX = "sub-profile-"
        const val MAX_SUBSCRIPTION_LINKS = 80
        const val SUBSCRIPTION_LOAD_MORE_STEP = 80
        const val MAX_SUBSCRIPTION_TOTAL_PROFILES = 2_000
        const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
        const val SUBSCRIPTION_TIMEOUT_MS = 15_000
        const val LIVE_REFRESH_CONNECTED_MS = 2_000L
        const val LIVE_REFRESH_IDLE_MS = 6_000L
        const val PROFILE_TEST_FRESH_MS = 6 * 60 * 60 * 1000L
        const val TEST_KIND_QUICK = "quick"
        const val TEST_KIND_REAL = "real"
        const val TEST_KIND_CONNECT = "connect"
        const val TEST_KIND_VERIFIED = "verified"
        const val SETTINGS_PREFS_NAME = "vpn_project_settings"
        const val KEY_AUTO_TEST_ENABLED = "auto_test_enabled"
        const val KEY_SMART_FALLBACK_ENABLED = "smart_fallback_enabled"
        const val KEY_QUICK_CHECK_LIMIT = "quick_check_limit"
        const val KEY_REAL_DELAY_LIMIT = "real_delay_limit"
        const val KEY_REAL_DELAY_URLS = "real_delay_urls"
        const val KEY_VPN_DNS_SERVERS = "vpn_dns_servers"
        const val KEY_BYPASS_PACKAGES = "bypass_packages"
        const val KEY_XRAY_SNIFFING = "xray_sniffing"
        const val KEY_XRAY_MUX_ENABLED = "xray_mux_enabled"
        const val KEY_XRAY_MUX_CONCURRENCY = "xray_mux_concurrency"
        const val KEY_XRAY_LOG_LEVEL = "xray_log_level"
        const val KEY_LOCATION_GROUP_FILTER = "location_group_filter"
        const val KEY_LOCATION_RUNTIME_FILTER = "location_runtime_filter"
        const val KEY_LOCATION_SORT_MODE = "location_sort_mode"
        const val DEFAULT_XRAY_MUX_CONCURRENCY = 8
        const val MIN_XRAY_MUX_CONCURRENCY = 1
        const val MAX_XRAY_MUX_CONCURRENCY = 32
        const val DEFAULT_XRAY_LOG_LEVEL = "warning"
        val XRAY_LOG_LEVELS = listOf("warning", "error", "info", "debug", "none")
        val DEFAULT_VPN_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
        val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val DEFAULT_REAL_DELAY_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
        val LOCATION_COUNTRIES = listOf(
            LocationCountry("🇮🇷", "Iran", listOf("iran", "ir", "tehran", "ایران", "تهران")),
            LocationCountry("🇳🇱", "Netherlands", listOf("netherlands", "nederland", "nl", "amsterdam", "rotterdam")),
            LocationCountry("🇩🇪", "Germany", listOf("germany", "deutschland", "de", "frankfurt", "berlin", "nuremberg", "falkenstein")),
            LocationCountry("🇺🇸", "United States", listOf("united states", "usa", "us", "america", "new york", "los angeles", "ashburn", "dallas", "california")),
            LocationCountry("🇨🇦", "Canada", listOf("canada", "ca", "toronto", "montreal", "vancouver")),
            LocationCountry("🇬🇧", "United Kingdom", listOf("united kingdom", "uk", "gb", "england", "london")),
            LocationCountry("🇫🇷", "France", listOf("france", "fr", "paris", "roubaix")),
            LocationCountry("🇹🇷", "Turkey", listOf("turkey", "tr", "istanbul", "izmir")),
            LocationCountry("🇦🇪", "UAE", listOf("uae", "ae", "emirates", "dubai", "abu dhabi")),
            LocationCountry("🇷🇺", "Russia", listOf("russia", "ru", "moscow", "saint petersburg")),
            LocationCountry("🇸🇬", "Singapore", listOf("singapore", "sg")),
            LocationCountry("🇯🇵", "Japan", listOf("japan", "jp", "tokyo", "osaka")),
            LocationCountry("🇰🇷", "Korea", listOf("korea", "kr", "seoul")),
            LocationCountry("🇭🇰", "Hong Kong", listOf("hong kong", "hk")),
            LocationCountry("🇮🇳", "India", listOf("india", "in", "mumbai", "delhi")),
            LocationCountry("🇧🇷", "Brazil", listOf("brazil", "br", "sao paulo")),
            LocationCountry("🇦🇺", "Australia", listOf("australia", "au", "sydney", "melbourne")),
            LocationCountry("🇮🇹", "Italy", listOf("italy", "it", "milan", "rome")),
            LocationCountry("🇪🇸", "Spain", listOf("spain", "es", "madrid")),
            LocationCountry("🇵🇱", "Poland", listOf("poland", "pl", "warsaw")),
            LocationCountry("🇫🇮", "Finland", listOf("finland", "fi", "helsinki")),
            LocationCountry("🇸🇪", "Sweden", listOf("sweden", "se", "stockholm")),
            LocationCountry("🇨🇭", "Switzerland", listOf("switzerland", "ch", "zurich")),
            LocationCountry("🇷🇴", "Romania", listOf("romania", "ro", "bucharest")),
            LocationCountry("🇦🇹", "Austria", listOf("austria", "at", "vienna")),
            LocationCountry("🇧🇪", "Belgium", listOf("belgium", "be", "brussels")),
            LocationCountry("🇦🇲", "Armenia", listOf("armenia", "am", "yerevan")),
            LocationCountry("🇶🇦", "Qatar", listOf("qatar", "qa", "doha")),
            LocationCountry("🇸🇦", "Saudi Arabia", listOf("saudi", "saudi arabia", "sa", "riyadh"))
        )
        const val LOCATION_FILTER_ALL = "all"
        const val LOCATION_FILTER_MANUAL = "manual"
        const val LOCATION_RUNTIME_ALL = "runtime_all"
        const val LOCATION_RUNTIME_READY = "runtime_ready"
        const val LOCATION_RUNTIME_ATTENTION = "runtime_attention"
        const val LOCATION_SORT_RECOMMENDED = "sort_recommended"
        const val LOCATION_SORT_NEWEST = "sort_newest"
        const val LOCATION_SORT_LATENCY = "sort_latency"
        const val LOCATION_SORT_RUNTIME_READY = "sort_runtime_ready"
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
        paint.shader = RadialGradient(cx, cy, radius * 1.35f, intArrayOf(PearlPalette.SHINE_SOFT, PearlPalette.SHINE_FAINT, PearlPalette.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.20f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = size * 0.060f
        paint.shader = LinearGradient(cx - radius, cy + radius, cx + radius, cy - radius, intArrayOf(PearlPalette.ACCENT_BLUE, PearlPalette.ACCENT_LILAC, PearlPalette.ACCENT_CORAL), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        paint.strokeWidth = size * 0.014f
        paint.color = if (active) PearlPalette.ACCENT_CORAL else PearlPalette.ACCENT_LILAC
        canvas.drawCircle(cx, cy, radius * 1.11f, paint)

        paint.style = Paint.Style.FILL
        paint.color = PearlPalette.PEARL_WHITE
        canvas.drawCircle(cx, cy, radius * 0.74f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.045f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = PearlPalette.INK
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
        paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(PearlPalette.ACCENT_BLUE, PearlPalette.INK, PearlPalette.ACCENT_CORAL), null, Shader.TileMode.CLAMP)
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
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(PearlPalette.PEARL_GHOST, PearlPalette.PEARL_TOP, PearlPalette.PEARL_WHITE), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(w * 0.50f, h * 0.28f, w * 0.34f, intArrayOf(PearlPalette.SHINE, PearlPalette.CHAMPAGNE_GLOW, PearlPalette.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(w * 0.50f, h * 0.28f, w * 0.36f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = PearlPalette.HAIRLINE
        canvas.drawLine(w * 0.12f, h * 0.78f, w * 0.88f, h * 0.78f, paint)
        paint.color = PearlPalette.CHAMPAGNE_RING
        canvas.drawLine(w * 0.24f, h * 0.84f, w * 0.76f, h * 0.84f, paint)
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
        paint.color = PearlPalette.SHINE
        val snow = Path().apply {
            moveTo(w * 0.34f, h * peak)
            lineTo(w * 0.29f, h * (peak + 0.07f))
            lineTo(w * 0.39f, h * (peak + 0.06f))
            close()
        }
        canvas.drawPath(snow, paint)
    }
}
