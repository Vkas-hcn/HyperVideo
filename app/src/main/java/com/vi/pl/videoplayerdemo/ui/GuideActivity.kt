package com.vi.pl.videoplayerdemo.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R

class GuideActivity : AppCompatActivity() {
    private var splashAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSplash()
        onBackPressedDispatcher.addCallback(this) {

        }
    }

    override fun onDestroy() {
        splashAnimator?.cancel()
        super.onDestroy()
    }

    private fun showSplash() {
        setContentView(R.layout.activity_guide_splash)
        applyInsets(findViewById(android.R.id.content))
        val progress = findViewById<ProgressBar>(R.id.splash_progress)
        splashAnimator = ObjectAnimator.ofInt(progress, "progress", 0, progress.max).apply {
            duration = SPLASH_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startActivity(Intent(this@GuideActivity, MainActivity::class.java))
                    finish()
                }
            })
            start()
        }
    }

    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private companion object {
        const val SPLASH_MS = 2000L
    }
}
