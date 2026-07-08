package com.innovation313.glowedge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PAGE_WELCOME = 0
        private const val PAGE_OVERLAY = 1
        private const val PAGE_AUDIO = 2
        private const val PAGE_NOTIF = 3
        private const val PAGE_MAIN = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("glowedge_prefs", Context.MODE_PRIVATE)
        flipper = findViewById(R.id.flipper)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ProfileManager.setOnboarded(this)
            goToNextNeededPage()
        }
        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.btnGrantAudio).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
        findViewById<Button>(R.id.btnGrantNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11
                )
            } else {
                goToNextNeededPage()
            }
        }
        findViewById<Button>(R.id.btnSkipNotif).setOnClickListener {
            prefs.edit().putBoolean("notif_skipped", true).apply()
            goToNextNeededPage()
        }
        findViewById<Button>(R.id.btnToggle).setOnClickListener { toggleService() }

        buildProfileButtons()
    }

    override fun onResume() {
        super.onResume()
        if (ProfileManager.isOnboarded(this)) {
            goToNextNeededPage()
        }
        updateMainUi()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        goToNextNeededPage()
    }

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasAudio() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun notifHandled() = hasNotif() || prefs.getBoolean("notif_skipped", false)

    private fun goToNextNeededPage() {
        val page = when {
            !hasOverlay() -> PAGE_OVERLAY
            !hasAudio() -> PAGE_AUDIO
            !notifHandled() -> PAGE_NOTIF
            else -> PAGE_MAIN
        }
        flipper.displayedChild = page
        if (page == PAGE_MAIN) updateMainUi()
    }

    private fun updateMainUi() {
        val running = VisualizerService.isRunning
        findViewById<Button>(R.id.btnToggle).text =
            getString(if (running) R.string.stop_glow else R.string.start_glow)
        findViewById<TextView>(R.id.tvStatus).text =
            getString(if (running) R.string.status_running else R.string.status_stopped)
        highlightSelectedProfile()
    }

    private fun toggleService() {
        if (VisualizerService.isRunning) {
            startService(
                Intent(this, VisualizerService::class.java)
                    .setAction(VisualizerService.ACTION_STOP)
            )
        } else {
            startForegroundService(Intent(this, VisualizerService::class.java))
        }
        flipper.postDelayed({ updateMainUi() }, 500)
    }

    private fun buildProfileButtons() {
        val container = findViewById<LinearLayout>(R.id.profileContainer)
        container.removeAllViews()
        ProfileManager.profiles.forEachIndexed { index, profile ->
            val btn = Button(this)
            btn.text = profile.name
            btn.tag = index
            btn.setOnClickListener {
                ProfileManager.select(this, index)
                if (VisualizerService.isRunning) {
                    startService(Intent(this, VisualizerService::class.java))
                }
                highlightSelectedProfile()
            }
            container.addView(btn)
        }
        highlightSelectedProfile()
    }

    private fun highlightSelectedProfile() {
        val container = findViewById<LinearLayout>(R.id.profileContainer)
        val selected = ProfileManager.selectedIndex(this)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = if ((child.tag as? Int) == selected) 1.0f else 0.55f
        }
    }
}
