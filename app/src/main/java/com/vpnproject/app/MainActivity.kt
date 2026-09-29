package com.vpnproject.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

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
            text = "Auto-connector skeleton: import configs, pin healthy IPs, then connect through Android VPN."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(12), 0, dp(24))
        })

        status = TextView(this).apply {
            text = "Phase 0: Android shell is ready. Next: config import and parser."
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
            text = "Import config (next milestone)"
            isEnabled = false
        })

        setContentView(root)
    }

    @Deprecated("Deprecated in Android framework, acceptable for this no-AndroidX skeleton.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            status.text = if (resultCode == RESULT_OK) {
                "VPN permission granted. Engine implementation comes in the next phases."
            } else {
                "VPN permission was not granted."
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val VPN_PERMISSION_REQUEST = 1001
    }
}
