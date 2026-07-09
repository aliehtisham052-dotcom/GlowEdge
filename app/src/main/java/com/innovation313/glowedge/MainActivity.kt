package com.innovation313.glowedge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
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
    private lateinit var tabs: ViewFlipper
    private lateinit var prefs: SharedPreferences

    private val styleCards = HashMap<Int, LinearLayout>()
    private val styleNames = HashMap<Int, TextView>()
    private val styleChips = HashMap<Int, TextView>()
    private val themeChips = ArrayList<TextView>()

    private val PAGE_OVERLAY = 1; private val PAGE_AUDIO = 2
    private val PAGE_NOTIF = 3; private val PAGE_MAIN = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("glowedge_prefs", Context.MODE_PRIVATE)
        flipper = findViewById(R.id.flipper)
        tabs = findViewById(R.id.tabs)

        findViewById<TextView>(R.id.btnStart).setOnClickListener { Settings.setOnboarded(this); nextPage() }
        findViewById<TextView>(R.id.btnGrantOverlay).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<TextView>(R.id.btnGrantAudio).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
        findViewById<TextView>(R.id.btnSkipAudio).setOnClickListener {
            prefs.edit().putBoolean("audio_skipped", true).apply(); nextPage()
        }
        findViewById<TextView>(R.id.btnGrantNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33)
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            else nextPage()
        }
        findViewById<TextView>(R.id.btnSkipNotif).setOnClickListener {
            prefs.edit().putBoolean("notif_skipped", true).apply(); nextPage()
        }

        findViewById<TextView>(R.id.btnToggle).setOnClickListener { toggle() }
        findViewById<TextView>(R.id.btnTest).setOnClickListener {
            if (!AndroidSettings.canDrawOverlays(this)) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            startForegroundService(Intent(this, VisualizerService::class.java).setAction(VisualizerService.ACTION_TEST))
            Toast.makeText(this, "Test glow...", Toast.LENGTH_SHORT).show()
        }
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener {
            tabs.displayedChild = when (it.itemId) {
                R.id.nav_settings -> 1; R.id.nav_about -> 2; else -> 0
            }; true
        }

        buildStyles(); buildThemes(); wireSliders(); wireToggles()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.onboarded(this)) nextPage()
        updateUi()
        findViewById<PreviewView?>(R.id.preview)?.refresh()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); nextPage()
    }

    private fun hasOverlay() = AndroidSettings.canDrawOverlays(this)
    private fun hasAudio() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun audioDone() = hasAudio() || prefs.getBoolean("audio_skipped", false)
    private fun hasNotif() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun notifDone() = hasNotif() || prefs.getBoolean("notif_skipped", false)

    private fun nextPage() {
        flipper.displayedChild = when {
            !hasOverlay() -> PAGE_OVERLAY
            !audioDone() -> PAGE_AUDIO
            !notifDone() -> PAGE_NOTIF
            else -> PAGE_MAIN
        }
        if (flipper.displayedChild == PAGE_MAIN) updateUi()
    }

    private fun updateUi() {
        val run = VisualizerService.isRunning
        findViewById<TextView>(R.id.btnToggle).text = getString(if (run) R.string.stop_glow else R.string.start_glow)
        findViewById<TextView>(R.id.tvStatus).text = getString(if (run) R.string.status_running else R.string.status_stopped)
        highlightStyles(); highlightThemes()
    }

    private fun toggle() {
        if (VisualizerService.isRunning)
            startService(Intent(this, VisualizerService::class.java).setAction(VisualizerService.ACTION_STOP))
        else startForegroundService(Intent(this, VisualizerService::class.java))
        flipper.postDelayed({ updateUi() }, 500)
    }

    private fun notifyService() { if (VisualizerService.isRunning) startService(Intent(this, VisualizerService::class.java)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildStyles() {
        val box = findViewById<LinearLayout>(R.id.stylesBox); box.removeAllViews()
        styleCards.clear(); styleNames.clear(); styleChips.clear()
        Styles.all.forEach { st ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card); setPadding(dp(18), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            }
            styleCards[st.id] = card
            card.addView(TextView(this).apply {
                text = "\u25CF"; textSize = 18f; setTextColor(Styles.accent(st.id)); setPadding(0, 0, dp(14), 0)
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(this).apply { text = st.name; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }
            styleNames[st.id] = name
            col.addView(name)
            col.addView(TextView(this).apply { text = st.tagline; textSize = 13f; setTextColor(Color.parseColor("#8A93B5")); setPadding(0, dp(2), 0, 0) })
            card.addView(col)
            val chip = TextView(this).apply {
                text = getString(R.string.apply); textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                setBackgroundResource(R.drawable.chip_apply); setPadding(dp(18), dp(9), dp(18), dp(9))
            }
            styleChips[st.id] = chip; card.addView(chip)
            val pick = View.OnClickListener {
                Settings.setStyleId(this, st.id); notifyService(); highlightStyles()
                findViewById<PreviewView?>(R.id.preview)?.refresh()
            }
            chip.setOnClickListener(pick); card.setOnClickListener(pick)
            box.addView(card)
        }
        highlightStyles()
    }

    private fun highlightStyles() {
        val sel = Settings.styleId(this); val gold = ContextCompat.getColor(this, R.color.gold)
        styleCards.forEach { (id, c) -> c.setBackgroundResource(if (id == sel) R.drawable.bg_card_active else R.drawable.bg_card) }
        styleNames.forEach { (id, tv) -> tv.setTextColor(if (id == sel) gold else Color.WHITE) }
        styleChips.forEach { (id, ch) -> ch.text = getString(if (id == sel) R.string.applied else R.string.apply); ch.alpha = if (id == sel) 1f else 0.85f }
    }

    private fun buildThemes() {
        val box = findViewById<LinearLayout>(R.id.themesBox); box.removeAllViews(); themeChips.clear()
        Settings.themes.forEachIndexed { i, th ->
            val chip = TextView(this).apply {
                text = th.name; tag = i; textSize = 14f; setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_card); setPadding(dp(18), dp(14), dp(18), dp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    Settings.setThemeIndex(this@MainActivity, i); notifyService(); highlightThemes()
                    findViewById<PreviewView?>(R.id.preview)?.refresh()
                }
            }
            themeChips.add(chip); box.addView(chip)
        }
        highlightThemes()
    }

    private fun highlightThemes() {
        val sel = Settings.themeIndex(this); val gold = ContextCompat.getColor(this, R.color.gold)
        themeChips.forEach { ch ->
            val on = (ch.tag as? Int) == sel
            ch.setBackgroundResource(if (on) R.drawable.bg_card_active else R.drawable.bg_card)
            ch.setTextColor(if (on) gold else Color.WHITE)
        }
    }

    private fun slider(id: Int, lo: Int, hi: Int, get: () -> Int, set: (Int) -> Unit) {
        findViewById<SeekBar>(id).apply {
            min = lo; max = hi; progress = get()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) { if (u) { set(v); notifyService() } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun wireSliders() {
        slider(R.id.seekThickness, 6, 40, { Settings.thickness(this) }, { Settings.setThickness(this, it) })
        slider(R.id.seekSpeed, 2, 20, { Settings.speed(this) }, { Settings.setSpeed(this, it) })
        slider(R.id.seekIntensity, 3, 20, { Settings.intensity(this) }, { Settings.setIntensity(this, it) })
        slider(R.id.seekSensitivity, 1, 10, { Settings.sensitivity(this) }, { Settings.setSensitivity(this, it) })
    }

    private fun sw(id: Int, get: () -> Boolean, set: (Boolean) -> Unit, onCheck: ((Boolean) -> Unit)? = null) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, c -> set(c); onCheck?.invoke(c) }
        }
    }

    private fun wireToggles() {
        sw(R.id.switchMusicOnly, { Settings.musicOnly(this) }, { Settings.setMusicOnly(this, it) }, { notifyService() })
        sw(R.id.switchSaver, { Settings.batterySaver(this) }, { Settings.setBatterySaver(this, it) }, { notifyService() })
        sw(R.id.switchAutostart, { Settings.autostart(this) }, { Settings.setAutostart(this, it) })
        sw(R.id.switchNotifGlow, { Settings.notifGlow(this) }, { Settings.setNotifGlow(this, it) }, { checked ->
            if (checked && !hasNotifAccess()) { Toast.makeText(this, R.string.notif_glow_need_access, Toast.LENGTH_LONG).show(); openNotifAccess() }
        })
        findViewById<TextView>(R.id.btnNotifAccess).setOnClickListener { openNotifAccess() }
    }

    private fun hasNotifAccess(): Boolean {
        val e = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return e.contains(packageName)
    }

    private fun openNotifAccess() {
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        catch (_: Exception) { startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) }
    }
}
