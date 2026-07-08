package com.innovation313.glowedge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var mainFlipper: ViewFlipper
    private lateinit var prefs: SharedPreferences
    private val styleNameViews = HashMap<Int, TextView>()
    private val themeButtons = ArrayList<Button>()

    companion object {
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
        mainFlipper = findViewById(R.id.mainFlipper)

        // Onboarding
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

        // Main UI
        findViewById<Button>(R.id.btnToggle).setOnClickListener { toggleService() }
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            mainFlipper.displayedChild = when (item.itemId) {
                R.id.nav_settings -> 1
                R.id.nav_premium -> 2
                else -> 0
            }
            true
        }

        buildStyleCards()
        buildThemeButtons()
        setupSliders()
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
        refreshStyleHighlights()
        refreshThemeHighlights()
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

    private fun notifyService() {
        if (VisualizerService.isRunning) {
            startService(Intent(this, VisualizerService::class.java))
        }
    }

    // ---------- Styles page ----------

    private fun buildStyleCards() {
        val container = findViewById<LinearLayout>(R.id.stylesContainer)
        container.removeAllViews()
        styleNameViews.clear()
        val gold = ContextCompat.getColor(this, R.color.gold)

        GlowStyles.all.forEach { s ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setBackgroundResource(R.drawable.bg_card)
            card.setPadding(44, 36, 44, 36)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 28
            card.layoutParams = lp

            val textCol = LinearLayout(this)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )

            val name = TextView(this)
            name.text = s.name
            name.textSize = 18f
            name.setTextColor(Color.WHITE)
            styleNameViews[s.id] = name

            val tagline = TextView(this)
            tagline.text = s.tagline
            tagline.textSize = 13f
            tagline.setTextColor(Color.parseColor("#8A93B5"))

            textCol.addView(name)
            textCol.addView(tagline)
            card.addView(textCol)

            if (s.premium) {
                val lock = TextView(this)
                lock.text = getString(R.string.premium_lock)
                lock.textSize = 14f
                lock.setTextColor(gold)
                card.addView(lock)
                card.setOnClickListener {
                    Toast.makeText(this, R.string.premium_toast, Toast.LENGTH_LONG).show()
                }
            } else {
                val apply = Button(this)
                apply.text = getString(R.string.apply)
                apply.setOnClickListener {
                    ProfileManager.setStyle(this, s.id)
                    notifyService()
                    refreshStyleHighlights()
                }
                card.addView(apply)
            }
            container.addView(card)
        }
        refreshStyleHighlights()
    }

    private fun refreshStyleHighlights() {
        val selected = ProfileManager.style(this)
        val gold = ContextCompat.getColor(this, R.color.gold)
        styleNameViews.forEach { (id, tv) ->
            tv.setTextColor(if (id == selected) gold else Color.WHITE)
        }
    }

    // ---------- Settings page ----------

    private fun buildThemeButtons() {
        val container = findViewById<LinearLayout>(R.id.themeContainer)
        container.removeAllViews()
        themeButtons.clear()
        ProfileManager.themes.forEachIndexed { index, theme ->
            val btn = Button(this)
            btn.text = theme.name
            btn.tag = index
            btn.setOnClickListener {
                ProfileManager.setTheme(this, index)
                notifyService()
                refreshThemeHighlights()
            }
            themeButtons.add(btn)
            container.addView(btn)
        }
        refreshThemeHighlights()
    }

    private fun refreshThemeHighlights() {
        val selected = ProfileManager.themeIndex(this)
        themeButtons.forEach { btn ->
            btn.alpha = if ((btn.tag as? Int) == selected) 1.0f else 0.55f
        }
    }

    private fun setupSliders() {
        val thickness = findViewById<SeekBar>(R.id.seekThickness)
        thickness.min = 6
        thickness.max = 40
        thickness.progress = ProfileManager.thickness(this)
        thickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    ProfileManager.setThickness(this@MainActivity, value)
                    notifyService()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val speed = findViewById<SeekBar>(R.id.seekSpeed)
        speed.min = 2
        speed.max = 20
        speed.progress = ProfileManager.speed(this)
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    ProfileManager.setSpeed(this@MainActivity, value)
                    notifyService()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }
}
