package com.innovation313.glowedge

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
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
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.io.FileOutputStream

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
        private const val REQ_PHONE_STATE = 20
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("glowedge_prefs", Context.MODE_PRIVATE)
        flipper = findViewById(R.id.flipper)
        mainFlipper = findViewById(R.id.mainFlipper)

        // Edge-to-edge is mandatory from targetSdk 35+ (no per-app opt-out anymore).
        // Pad content below the status bar and the bottom nav above the gesture bar
        // so nothing sits underneath the system bars.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(flipper) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }

        // Premium feel: soft cross-fade when switching tabs
        mainFlipper.inAnimation = android.view.animation.AlphaAnimation(0f, 1f).apply { duration = 180 }
        mainFlipper.outAnimation = android.view.animation.AlphaAnimation(1f, 0f).apply { duration = 140 }

        // Onboarding
        findViewById<TextView>(R.id.btnStart).setOnClickListener {
            ProfileManager.setOnboarded(this)
            goToNextNeededPage()
        }
        findViewById<TextView>(R.id.btnGrantOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<TextView>(R.id.btnGrantAudio).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
        findViewById<TextView>(R.id.btnSkipAudio).setOnClickListener {
            prefs.edit().putBoolean("audio_skipped", true).apply()
            goToNextNeededPage()
        }
        findViewById<TextView>(R.id.btnGrantNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11
                )
            } else {
                goToNextNeededPage()
            }
        }
        findViewById<TextView>(R.id.btnSkipNotif).setOnClickListener {
            prefs.edit().putBoolean("notif_skipped", true).apply()
            goToNextNeededPage()
        }

        // Main UI
        findViewById<TextView>(R.id.btnToggle).setOnClickListener { toggleService() }
        findViewById<TextView>(R.id.btnTest).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.overlay_title, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            startForegroundService(Intent(this, VisualizerService::class.java)
                .setAction(VisualizerService.ACTION_TEST))
            Toast.makeText(this, "Test glow running for 5 seconds...", Toast.LENGTH_SHORT).show()
        }
        findViewById<BottomNavigationView>(R.id.bottomNav).also { nav ->
            nav.setOnItemSelectedListener { item ->
                mainFlipper.displayedChild = when (item.itemId) {
                    R.id.nav_settings -> 1
                    R.id.nav_wallpapers -> 2
                    R.id.nav_premium -> 3
                    else -> 0
                }
                true
            }
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(nav) { v, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
                insets
            }
        }

        buildStyleCards()
        buildThemeButtons()
        buildWallpaperCards()
        setupSliders()
        setupHeroHeader()

        findViewById<View>(R.id.btnShare).setOnClickListener { shareGlowCard() }
        findViewById<View>(R.id.btnFeedback).setOnClickListener { sendFeedback() }
    }

    /** Opens the user's email app pre-filled to the developer, with a subject and a
     *  reason template so feedback always arrives with context. */
    private fun sendFeedback() {
        val email = getString(R.string.support_email)
        val subject = getString(R.string.feedback_subject)
        val body = getString(R.string.feedback_body)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.feedback_button)))
        } catch (e: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    /** One-time premium entrance for the hero header (halo + logo + name fade/scale in),
     *  plus a static gold shimmer on the app name. Deliberately NOT a looping animation —
     *  this app is built battery-conscious (Battery Saver default ON, capped frame rate),
     *  so the home screen should never animate forever in the background. */
    private fun setupHeroHeader() {
        val halo = findViewById<View>(R.id.heroHalo)
        val logo = findViewById<ImageView>(R.id.heroLogo)
        val heroName = findViewById<TextView>(R.id.heroAppName)

        val text = heroName.text.toString()
        val w = heroName.paint.measureText(text).coerceAtLeast(1f)
        heroName.paint.shader = android.graphics.LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(Color.parseColor("#FFD54F"), Color.parseColor("#FFF6D8"), Color.parseColor("#FFD54F")),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )

        listOf(halo, logo, heroName).forEach { it.alpha = 0f }
        halo.scaleX = 0.7f; halo.scaleY = 0.7f
        halo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setStartDelay(80).start()
        logo.animate().alpha(1f).setDuration(600).setStartDelay(160).start()
        heroName.animate().alpha(1f).setDuration(600).setStartDelay(260).start()
    }

    override fun onResume() {
        super.onResume()
        if (ProfileManager.isOnboarded(this)) {
            goToNextNeededPage()
        }
        updateMainUi()
        findViewById<PreviewView?>(R.id.previewView)?.refresh()
        // Refresh after returning from the Notification Access screen, and rebind the
        // listener if access was just granted, so Music Only works without a reboot.
        updateMusicStatus()
        notifyService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE_STATE) {
            // Phone permission just answered — refresh the service so Call Glow's
            // listener registers now that permission state may have changed.
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                notifyService()
                Toast.makeText(this, getString(R.string.call_glow_ready), Toast.LENGTH_SHORT).show()
            } else {
                // Denied — turn the toggle back off so it reflects reality.
                ProfileManager.setCallGlow(this, false)
                findViewById<MaterialSwitch>(R.id.switchCallGlow)?.isChecked = false
                Toast.makeText(this, getString(R.string.call_glow_denied), Toast.LENGTH_LONG).show()
            }
            return
        }
        goToNextNeededPage()
    }

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasAudio() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun audioHandled() = hasAudio() || prefs.getBoolean("audio_skipped", false)

    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun notifHandled() = hasNotif() || prefs.getBoolean("notif_skipped", false)

    private fun goToNextNeededPage() {
        val page = when {
            !hasOverlay() -> PAGE_OVERLAY
            !audioHandled() -> PAGE_AUDIO
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
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
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
        findViewById<PreviewView?>(R.id.previewView)?.refresh()
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
                findViewById<PreviewView?>(R.id.previewView)?.refresh()
            }
            themeChips.add(chip)
            container.addView(chip)
        }

        // "Custom Colors" chip — millions of combinations via the built-in HSV picker
        val customIndex = ProfileManager.themes.size
        val custom = TextView(this)
        custom.text = "🎨 Custom Colors — apne rang banayen"
        custom.tag = customIndex
        custom.textSize = 14f
        custom.setTextColor(Color.WHITE)
        custom.setBackgroundResource(R.drawable.bg_card)
        custom.setPadding(dp(18), dp(14), dp(18), dp(14))
        val clp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        clp.bottomMargin = dp(10)
        custom.layoutParams = clp
        custom.setOnClickListener {
            ProfileManager.setTheme(this, customIndex)
            refreshThemeHighlights()
            showCustomColorPicker()
        }
        themeChips.add(custom)
        container.addView(custom)
        refreshThemeHighlights()

        findViewById<TextView>(R.id.btnSetCurrentWallpaper).setOnClickListener {
            applyWallpaper(ProfileManager.theme(this))
        }
    }

    private fun buildWallpaperCards() {
        findViewById<TextView>(R.id.btnLiveWallpaper).setOnClickListener {
            // Android keeps no copy of the wallpaper you're replacing, so we save the
            // current one first — that's what lets Remove restore it later.
            backupCurrentWallpaper()
            try {
                val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        android.content.ComponentName(this@MainActivity, GlowLiveWallpaper::class.java)
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                    Toast.makeText(this, "Pick \u201CGlowEdge\u201D from the list", Toast.LENGTH_LONG).show()
                } catch (e2: Exception) {
                    Toast.makeText(this, "Live wallpaper not supported on this device", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<TextView>(R.id.btnRemoveLiveWallpaper).setOnClickListener {
            restoreOrPickWallpaper()
        }
        val container = findViewById<LinearLayout>(R.id.wallpaperContainer)
        container.removeAllViews()
        ProfileManager.themes.forEach { theme ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setBackgroundResource(R.drawable.bg_card)
            card.setPadding(dp(12), dp(12), dp(16), dp(12))
            val cardLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            cardLp.bottomMargin = dp(12)
            card.layoutParams = cardLp

            val preview = ImageView(this)
            preview.layoutParams = LinearLayout.LayoutParams(dp(70), dp(124))
            preview.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.setImageBitmap(WallpaperGenerator.generate(theme, dp(140), dp(248)))

            val textCol = LinearLayout(this)
            textCol.orientation = LinearLayout.VERTICAL
            val tclp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tclp.marginStart = dp(16)
            textCol.layoutParams = tclp

            val title = TextView(this)
            title.text = theme.name
            title.textSize = 15f
            title.setTextColor(Color.WHITE)
            title.setTypeface(null, android.graphics.Typeface.BOLD)
            textCol.addView(title)

            val setBtn = TextView(this)
            setBtn.text = "Set as Lock Screen"
            setBtn.textSize = 13f
            setBtn.setTextColor(ContextCompat.getColor(this, R.color.gold))
            val btnLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            btnLp.topMargin = dp(8)
            setBtn.layoutParams = btnLp
            setBtn.setOnClickListener { applyWallpaper(theme) }
            textCol.addView(setBtn)

            card.addView(preview)
            card.addView(textCol)
            container.addView(card)
        }
    }

    /**
     * Saves whatever wallpaper is currently set into app-private storage, so Remove can
     * put it back later. Android does not keep a copy of the wallpaper you replace, and
     * apps can't read another app's wallpaper afterwards — so we snapshot it up front.
     * Only ever runs right before we replace the wallpaper ourselves.
     */
    private fun backupCurrentWallpaper() {
        try {
            val wm = WallpaperManager.getInstance(this)
            // If GlowEdge's live wallpaper is already active, there's nothing worth saving.
            val info = wm.wallpaperInfo
            if (info != null && info.packageName == packageName) return

            val drawable = wm.drawable ?: return
            val bmp = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(b)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(c)
                    b
                }
            } ?: return

            val file = java.io.File(filesDir, "previous_wallpaper.png")
            java.io.FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: SecurityException) {
            // Some OS versions restrict reading the wallpaper; restore just won't be offered.
        } catch (_: Exception) {
        }
    }

    /**
     * Restores the wallpaper that was in place before GlowEdge, if we saved one.
     * If there's no saved wallpaper (e.g. the live wallpaper was set before this feature
     * existed, or the OS blocked reading it), we fall back to opening the system picker.
     */
    private fun restoreOrPickWallpaper() {
        val file = java.io.File(filesDir, "previous_wallpaper.png")
        if (file.exists()) {
            Toast.makeText(this, getString(R.string.restoring_wallpaper), Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        WallpaperManager.getInstance(this).setBitmap(bmp)
                        file.delete()
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.wallpaper_restored), Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                } catch (_: Exception) {
                }
                runOnUiThread { openWallpaperPicker() }
            }.start()
            return
        }
        openWallpaperPicker()
    }

    /** Fallback: open the system wallpaper picker so the user can choose any wallpaper,
     *  which replaces (and so removes) the GlowEdge live wallpaper. */
    private fun openWallpaperPicker() {
        Toast.makeText(this, getString(R.string.remove_live_wallpaper_hint), Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.remove_live_wallpaper)
            ))
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings → Wallpaper to change it", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyWallpaper(theme: Profile) {
        Toast.makeText(this, "Setting lock screen wallpaper\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dm = resources.displayMetrics
                val bs = ProfileManager.batteryStyle(this)
                val bi = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lvl = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 50) ?: 50
                val scale = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                val pct = ((lvl * 100f) / scale.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                val status = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val chg = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                          status == android.os.BatteryManager.BATTERY_STATUS_FULL
                val bmp = WallpaperGenerator.generateWithBattery(
                    theme, dm.widthPixels, dm.heightPixels, pct, chg, bs
                )
                val wm = WallpaperManager.getInstance(this)
                // Lock screen only, as requested — a static wallpaper can target just the
                // lock screen (FLAG_LOCK); this is not possible for live/animated wallpapers
                // on stock Android, which is why these wallpapers are static.
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                runOnUiThread {
                    Toast.makeText(this, "Lock screen wallpaper applied \u2014 ${theme.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't set wallpaper", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Built-in HSV picker: two swatches (Color 1 / Color 2) + Hue, Saturation,
     *  Brightness sliders — millions of combinations, no external library. */
    private fun showCustomColorPicker() {
        var c1 = ProfileManager.customStart(this)
        var c2 = ProfileManager.customEnd(this)
        var editing = 0 // 0 = color1, 1 = color2

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(22), dp(18), dp(22), dp(8))
        root.setBackgroundColor(Color.parseColor("#111938"))

        val title = TextView(this)
        title.text = "Apne rang chunein"
        title.textSize = 18f
        title.setTextColor(ContextCompat.getColor(this, R.color.gold))
        root.addView(title)

        // swatch row
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val rowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        rowLp.topMargin = dp(14)
        row.layoutParams = rowLp
        val sw1 = TextView(this); val sw2 = TextView(this)
        listOf(sw1, sw2).forEachIndexed { i, sw ->
            sw.gravity = android.view.Gravity.CENTER
            sw.textSize = 13f
            sw.setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (i == 1) lp.marginStart = dp(10)
            sw.layoutParams = lp
            row.addView(sw)
        }
        root.addView(row)

        // sliders
        val hsv = FloatArray(3)
        val seekH = SeekBar(this); val seekS = SeekBar(this); val seekV = SeekBar(this)
        fun addSlider(label: String, sb: SeekBar, maxV: Int) {
            val tv = TextView(this); tv.text = label; tv.textSize = 13f
            tv.setTextColor(Color.parseColor("#8A93B5"))
            val tlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tlp.topMargin = dp(12); tv.layoutParams = tlp
            root.addView(tv); sb.max = maxV; root.addView(sb)
        }
        addSlider("Rang (Hue)", seekH, 360)
        addSlider("Gehrai (Saturation)", seekS, 100)
        addSlider("Chamak (Brightness)", seekV, 100)

        fun currentColor() = if (editing == 0) c1 else c2
        fun refreshSwatches() {
            sw1.setBackgroundColor(c1); sw2.setBackgroundColor(c2)
            sw1.text = if (editing == 0) "◉ Color 1" else "Color 1"
            sw2.text = if (editing == 1) "◉ Color 2" else "Color 2"
        }
        fun loadSliders() {
            Color.colorToHSV(currentColor(), hsv)
            seekH.progress = hsv[0].toInt()
            seekS.progress = (hsv[1] * 100).toInt()
            seekV.progress = (hsv[2] * 100).toInt()
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                if (!fromUser) return
                val col = Color.HSVToColor(floatArrayOf(
                    seekH.progress.toFloat(),
                    seekS.progress / 100f,
                    seekV.progress / 100f))
                if (editing == 0) c1 = col else c2 = col
                refreshSwatches()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekH.setOnSeekBarChangeListener(listener)
        seekS.setOnSeekBarChangeListener(listener)
        seekV.setOnSeekBarChangeListener(listener)
        sw1.setOnClickListener { editing = 0; refreshSwatches(); loadSliders() }
        sw2.setOnClickListener { editing = 1; refreshSwatches(); loadSliders() }
        refreshSwatches(); loadSliders()

        android.app.AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                ProfileManager.setCustomColors(this, c1, c2)
                ProfileManager.setTheme(this, ProfileManager.themes.size)
                notifyService()
                refreshThemeHighlights()
                findViewById<PreviewView?>(R.id.previewView)?.refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Generates a branded "share card" image of the user's current glow (style, colors,
     *  personal text) and opens the system share sheet. Entirely on-device — the share
     *  Intent hands the file to whichever app the user picks; GlowEdge itself never
     *  touches the network, so this needs no internet permission. */
    private fun shareGlowCard() {
        try {
            val w = 1080
            val h = 1350
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#0A1128"), Color.parseColor("#0E1631"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

            val theme = ProfileManager.theme(this)
            val c1 = theme.colorStart
            val c2 = theme.colorEnd
            val inset = 44f

            // Glow border: soft blurred pass, then a crisp pass on top
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            borderPaint.style = Paint.Style.STROKE
            borderPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, Shader.TileMode.CLAMP)
            borderPaint.strokeWidth = 26f
            borderPaint.maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, 64f, 64f, borderPaint)
            borderPaint.maskFilter = null
            borderPaint.strokeWidth = 7f
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, 64f, 64f, borderPaint)

            // Center: the style name
            val styleName = GlowStyles.all.firstOrNull { it.id == ProfileManager.style(this) }?.name ?: "GlowEdge"
            val displayText = styleName

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            titlePaint.textAlign = Paint.Align.CENTER
            titlePaint.typeface = Typeface.DEFAULT_BOLD
            titlePaint.textSize = if (displayText.length > 12) 76f else 100f
            titlePaint.shader = LinearGradient(w / 2f - 320f, 0f, w / 2f + 320f, 0f, c1, c2, Shader.TileMode.CLAMP)
            titlePaint.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawText(displayText, w / 2f, h / 2f - 30f, titlePaint)
            titlePaint.maskFilter = null

            val stylePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            stylePaint.textAlign = Paint.Align.CENTER
            stylePaint.color = Color.parseColor("#8A93B5")
            stylePaint.textSize = 38f
            canvas.drawText("$styleName  \u00b7  ${theme.name}", w / 2f, h / 2f + 56f, stylePaint)

            // Footer branding
            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            brandPaint.textAlign = Paint.Align.CENTER
            brandPaint.color = Color.parseColor("#FFD54F")
            brandPaint.typeface = Typeface.DEFAULT_BOLD
            brandPaint.textSize = 46f
            canvas.drawText("GlowEdge", w / 2f, h - 150f, brandPaint)

            val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            taglinePaint.textAlign = Paint.Align.CENTER
            taglinePaint.color = Color.parseColor("#8A93B5")
            taglinePaint.textSize = 28f
            taglinePaint.letterSpacing = 0.08f
            canvas.drawText("POWERED BY INNOVATION-313", w / 2f, h - 100f, taglinePaint)

            val dir = File(cacheDir, "shared")
            dir.mkdirs()
            val file = File(dir, "glowedge_share.png")
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "My glow on GlowEdge by Innovation-313 \u2728")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_glow)))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't create the share image", Toast.LENGTH_SHORT).show()
        }
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

        val saver = findViewById<MaterialSwitch>(R.id.switchSaver)
        saver.isChecked = ProfileManager.batterySaver(this)
        saver.setOnCheckedChangeListener { _, checked ->
            ProfileManager.setBatterySaver(this, checked)
            notifyService()
        }

        val intro = findViewById<MaterialSwitch>(R.id.switchIntro)
        intro.isChecked = ProfileManager.intro(this)
        intro.setOnCheckedChangeListener { _, checked ->
            ProfileManager.setIntro(this, checked)
        }

        val musicOnly = findViewById<MaterialSwitch>(R.id.switchMusicOnly)
        musicOnly.isChecked = ProfileManager.musicOnly(this)
        musicOnly.setOnCheckedChangeListener { _, checked ->
            ProfileManager.setMusicOnly(this, checked)
            if (checked && !hasNotificationAccess()) {
                Toast.makeText(this, getString(R.string.notif_access_needed), Toast.LENGTH_LONG).show()
                openNotificationAccess()
            }
            notifyService()
        }

        findViewById<TextView>(R.id.btnMusicNotifAccess).setOnClickListener {
            openNotificationAccess()
        }

        updateMusicStatus()

        buildGlowEdgesButtons()
        buildBatteryStyleButtons()

        val callGlow = findViewById<MaterialSwitch>(R.id.switchCallGlow)
        callGlow.isChecked = ProfileManager.callGlow(this)
        callGlow.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.READ_PHONE_STATE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this, arrayOf(android.Manifest.permission.READ_PHONE_STATE), REQ_PHONE_STATE
                    )
                }
                ProfileManager.setCallGlow(this, true)
            } else {
                ProfileManager.setCallGlow(this, false)
            }
            notifyService()
        }
    }

    /** Segmented selector for which screen edges glow: All / Sides / Top &amp; bottom. */
    private fun buildGlowEdgesButtons() {
        val container = findViewById<LinearLayout>(R.id.glowEdgesContainer)
        container.removeAllViews()
        val labels = listOf(
            getString(R.string.glow_edges_all),
            getString(R.string.glow_edges_sides),
            getString(R.string.glow_edges_topbottom)
        )
        labels.forEachIndexed { index, label ->
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 13f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(10), dp(12), dp(10), dp(12))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = if (index < 2) dp(8) else 0
            chip.layoutParams = lp
            val selected = ProfileManager.glowEdges(this) == index
            chip.setBackgroundResource(R.drawable.bg_card)
            chip.setTextColor(if (selected) ContextCompat.getColor(this, R.color.gold) else Color.WHITE)
            chip.alpha = if (selected) 1f else 0.6f
            chip.setOnClickListener {
                ProfileManager.setGlowEdges(this, index)
                notifyService()
                buildGlowEdgesButtons()
                findViewById<PreviewView?>(R.id.previewView)?.refresh()
            }
            container.addView(chip)
        }
    }

    /**
     * Shows plainly what the music detector is doing right now, so Music Only isn't a
     * black box: whether we have Notification Access, and whether music is detected at
     * this moment. If access was just granted, ask Android to bind our listener — it
     * otherwise won't connect until a reboot, which makes the feature look broken.
     */
    private fun updateMusicStatus() {
        val status = findViewById<TextView>(R.id.musicStatus) ?: return
        val hasAccess = MediaPlaybackDetector.hasNotificationAccess(this)
        if (hasAccess) MediaPlaybackDetector.requestRebind(this)

        val playing = MediaPlaybackDetector.isMusicPlaying(this)
        status.text = when {
            !hasAccess -> getString(R.string.music_status_no_access)
            playing -> getString(R.string.music_status_playing)
            else -> getString(R.string.music_status_idle)
        }
        status.setTextColor(
            if (!hasAccess) Color.parseColor("#FF8A80")
            else ContextCompat.getColor(this, R.color.gold)
        )
    }

    /** Segmented selector for the battery module design shown on both wallpapers. */
    private fun buildBatteryStyleButtons() {
        val container = findViewById<LinearLayout>(R.id.batteryStyleContainer)
        container.removeAllViews()
        BatteryModule.styleNames.forEachIndexed { index, label ->
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 13f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(10), dp(12), dp(10), dp(12))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = if (index < BatteryModule.STYLE_COUNT - 1) dp(8) else 0
            chip.layoutParams = lp
            val selected = ProfileManager.batteryStyle(this) == index
            chip.setBackgroundResource(R.drawable.bg_card)
            chip.setTextColor(if (selected) ContextCompat.getColor(this, R.color.gold) else Color.WHITE)
            chip.alpha = if (selected) 1f else 0.6f
            chip.setOnClickListener {
                ProfileManager.setBatteryStyle(this, index)
                buildBatteryStyleButtons()
                Toast.makeText(this, getString(R.string.battery_style_applied), Toast.LENGTH_SHORT).show()
            }
            container.addView(chip)
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
