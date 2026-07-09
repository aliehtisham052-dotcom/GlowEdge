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
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView as TV
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var mainFlipper: ViewFlipper
    private lateinit var prefs: SharedPreferences
    private val styleNameViews = HashMap<Int, TextView>()

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
        findViewById<TextView>(R.id.btnToggle).setOnClickListener { toggleService() }
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
        findViewById<TextView>(R.id.btnToggle).text =
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

    private val styleCards = HashMap<Int, LinearLayout>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildStyleCards() {
        val container = findViewById<LinearLayout>(R.id.stylesContainer)
        container.removeAllViews()
        styleNameViews.clear()
        styleCards.clear()

        GlowStyles.all.forEach { st ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setBackgroundResource(R.drawable.bg_card)
            card.setPadding(dp(18), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            card.layoutParams = lp
            styleCards[st.id] = card

            // Accent dot
            val dot = TextView(this)
            dot.text = "●"
            dot.textSize = 16f
            dot.setTextColor(Color.parseColor("#FFD54F"))
            dot.setPadding(0, 0, dp(14), 0)
            card.addView(dot)

            val textCol = LinearLayout(this)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )

            val name = TextView(this)
            name.text = st.name
            name.textSize = 17f
            name.setTextColor(Color.WHITE)
            name.setTypeface(name.typeface, android.graphics.Typeface.BOLD)
            styleNameViews[st.id] = name

            val tagline = TextView(this)
            tagline.text = st.tagline
            tagline.textSize = 13f
            tagline.setTextColor(Color.parseColor("#8A93B5"))
            tagline.setPadding(0, dp(2), 0, 0)

            textCol.addView(name)
            textCol.addView(tagline)
            card.addView(textCol)

            val apply = TextView(this)
            apply.text = getString(R.string.apply)
            apply.textSize = 13f
            apply.setTextColor(Color.WHITE)
            apply.setTypeface(apply.typeface, android.graphics.Typeface.BOLD)
            apply.setBackgroundResource(R.drawable.chip_apply)
            apply.setPadding(dp(18), dp(9), dp(18), dp(9))
            styleChips[st.id] = apply
            card.addView(apply)

            val pick = View.OnClickListener {
                ProfileManager.setStyle(this, st.id)
                notifyService()
                refreshStyleHighlights()
            }
            apply.setOnClickListener(pick)
            card.setOnClickListener(pick)

            container.addView(card)
        }
        refreshStyleHighlights()
    }

    private val styleChips = HashMap<Int, TextView>()

    private fun refreshStyleHighlights() {
        val selected = ProfileManager.style(this)
        styleCards.forEach { (id, card) ->
            card.setBackgroundResource(
                if (id == selected) R.drawable.bg_card_active else R.drawable.bg_card
            )
        }
        styleNameViews.forEach { (id, tv) ->
            tv.setTextColor(if (id == selected) ContextCompat.getColor(this, R.color.gold) else Color.WHITE)
        }
        styleChips.forEach { (id, chip) ->
            chip.text = getString(if (id == selected) R.string.applied else R.string.apply)
            chip.alpha = if (id == selected) 1f else 0.85f
        }
    }

    // ---------- Settings page ----------

    private fun buildThemeButtons() {
        val container = findViewById<LinearLayout>(R.id.themeContainer)
        container.removeAllViews()
        themeChips.clear()
        ProfileManager.themes.forEachIndexed { index, theme ->
            val chip = TextView(this)
            chip.text = theme.name
            chip.tag = index
            chip.textSize = 14f
            chip.setTextColor(Color.WHITE)
            chip.setBackgroundResource(R.drawable.bg_card)
            chip.setPadding(dp(18), dp(14), dp(18), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            chip.layoutParams = lp
            chip.setOnClickListener {
                ProfileManager.setTheme(this, index)
                notifyService()
                refreshThemeHighlights()
            }
            themeChips.add(chip)
            container.addView(chip)
        }
        refreshThemeHighlights()
    }

    private val themeChips = ArrayList<TextView>()

    private fun refreshThemeHighlights() {
        val selected = ProfileManager.themeIndex(this)
        themeChips.forEach { chip ->
            val isSel = (chip.tag as? Int) == selected
            chip.setBackgroundResource(if (isSel) R.drawable.bg_card_active else R.drawable.bg_card)
            chip.setTextColor(if (isSel) ContextCompat.getColor(this, R.color.gold) else Color.WHITE)
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

        val intensity = findViewById<SeekBar>(R.id.seekIntensity)
        intensity.min = 3
        intensity.max = 20
        intensity.progress = ProfileManager.intensity(this)
        intensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    ProfileManager.setIntensity(this@MainActivity, value)
                    notifyService()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val autostart = findViewById<MaterialSwitch>(R.id.switchAutostart)
        autostart.isChecked = prefs.getBoolean("autostart", false)
        autostart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("autostart", checked).apply()
        }

        val notifGlow = findViewById<MaterialSwitch>(R.id.switchNotifGlow)
        notifGlow.isChecked = prefs.getBoolean("notif_glow", false)
        notifGlow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notif_glow", checked).apply()
            if (checked && !hasNotificationAccess()) {
                Toast.makeText(this, R.string.notif_glow_need_access, Toast.LENGTH_LONG).show()
                openNotificationAccess()
            }
        }
        findViewById<TextView>(R.id.btnNotifAccess).setOnClickListener {
            openNotificationAccess()
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
