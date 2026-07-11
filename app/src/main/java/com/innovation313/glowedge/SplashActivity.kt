package com.innovation313.glowedge

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * First screen the user sees on launch: a brief, professional branded intro
 * ("Powered by Innovation-313") before handing off to MainActivity. Fixed,
 * short duration — no loading work happens here, so it never overstays.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION_MS = 1300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val halo = findViewById<View>(R.id.splashHalo)
        val logo = findViewById<ImageView>(R.id.splashLogo)
        val name = findViewById<TextView>(R.id.splashAppName)
        val poweredBy = findViewById<TextView>(R.id.splashPoweredBy)

        listOf(halo, logo, name, poweredBy).forEach { it.alpha = 0f }
        halo.scaleX = 0.7f; halo.scaleY = 0.7f
        halo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650).setStartDelay(60).start()
        logo.animate().alpha(1f).setDuration(550).setStartDelay(140).start()
        name.animate().alpha(1f).setDuration(550).setStartDelay(220).start()
        poweredBy.animate().alpha(1f).setDuration(500).setStartDelay(420).start()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }, SPLASH_DURATION_MS)
    }
}
