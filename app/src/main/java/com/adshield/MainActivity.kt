package com.adshield

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/** Tiny UI built in code (no AndroidX / layout inflation) to keep the app light. */
class MainActivity : Activity() {

    private val reqVpn = 1
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var listsButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply { text = "AdShield"; textSize = 26f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) }
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        val browser = Button(this).apply {
            text = "Open ad-free browser (YouTube, Facebook, Instagram…)"
            setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) }
        }
        listsButton = Button(this).apply { setOnClickListener { updateFilterLists() } }
        updateListsLabel()
        val battery = Button(this).apply {
            text = "Disable battery optimisation"
            setOnClickListener { requestBatteryExemption() }
        }
        val alwaysOn = Button(this).apply {
            text = "Open VPN settings (turn on Always-on)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        }
        val help = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "To keep AdShield running permanently:\n" +
                "1. Disable battery optimisation for AdShield.\n" +
                "2. In VPN settings, tap the gear next to AdShield and enable " +
                "\"Always-on VPN\". Android will then restart it automatically, even after reboot.\n" +
                "3. Set Settings > Network > Private DNS to Off or Automatic " +
                "(a custom Private DNS hostname bypasses the blocker)."
        }
        listOf(title, status, toggle, browser, listsButton, battery, alwaysOn, help).forEach(root::addView)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun refresh() {
        val on = AdBlockVpnService.isRunning
        status.text = if (on) "Protection ON\nAd requests blocked this session: ${AdBlockVpnService.blockedCount.get()}"
                      else "Protection OFF"
        toggle.text = if (on) "Turn off" else "Turn on"
    }

    private fun onToggle() {
        if (AdBlockVpnService.isRunning) {
            startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
        } else {
            val consent = VpnService.prepare(this)
            if (consent != null) startActivityForResult(consent, reqVpn)
            else onActivityResult(reqVpn, RESULT_OK, null)
        }
    }

    @Deprecated("Plain Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVpn && resultCode == RESULT_OK) {
            startForegroundService(
                Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_START)
            )
        }
    }

    private fun updateListsLabel() {
        val f = File(filesDir, FilterEngine.REMOTE_LISTS.keys.first())
        listsButton.text = if (f.exists())
            "Update browser filter lists (last: ${DateFormat.getDateInstance().format(Date(f.lastModified()))})"
        else "Download EasyList + EasyPrivacy for the browser"
    }

    /** Downloads the same community lists Brave and uBlock Origin use, for the built-in browser. */
    private fun updateFilterLists() {
        listsButton.isEnabled = false
        listsButton.text = "Downloading…"
        Thread {
            val error = try {
                for ((name, url) in FilterEngine.REMOTE_LISTS) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000; conn.readTimeout = 30000
                    try {
                        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $name")
                        val tmp = File(filesDir, "$name.tmp")
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        if (!tmp.renameTo(File(filesDir, name))) throw IOException("could not save $name")
                    } finally { conn.disconnect() }
                }
                null
            } catch (e: Exception) { e.message ?: e.toString() }
            runOnUiThread {
                listsButton.isEnabled = true
                updateListsLabel()
                Toast.makeText(this, error?.let { "Update failed: $it" } ?: "Filter lists updated",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }
}
