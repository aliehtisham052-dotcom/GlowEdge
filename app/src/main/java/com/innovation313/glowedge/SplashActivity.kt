package com.innovation313.glowedge

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/**
 * First screen the user sees on launch: the Innovation-313 branded opening
 * clip (glow_intro.mp4), played once, before handing off to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_MAX_MS = 5000L
    }

    private var moved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_splash)

        val videoView = findViewById<VideoView>(R.id.splashVideo)
        val uri = Uri.parse("android.resource://$packageName/${R.raw.glow_intro}")
        videoView.setVideoURI(uri)

        videoView.setOnPreparedListener { mp: MediaPlayer ->
            mp.isLooping = false
            mp.setVolume(0f, 0f)   // silent intro — no sound on app open
        }

        videoView.setOnCompletionListener { goToMain() }
        videoView.setOnErrorListener { _, _, _ -> goToMain(); true }

        videoView.postDelayed({ if (!isFinishing) goToMain() }, SPLASH_MAX_MS)

        videoView.start()
    }

    private fun goToMain() {
        if (moved) return
        moved = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
