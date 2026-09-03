package com.vi.pl.videoplayerdemo.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.download.DownloadBarLauncher

class GuideActivity : AppCompatActivity() {
    private var splashAnimator: ObjectAnimator? = null
    private var pendingPermissionRequest = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) DownloadBarLauncher.ensureRunning(this)
        goMain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSplash()
        onBackPressedDispatcher.addCallback(this) { }
    }

    override fun onResume() {
        super.onResume()
        if (pendingPermissionRequest) {
            pendingPermissionRequest = false
            requestNotificationPermission()
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
                    onSplashFinished()
                }
            })
            start()
        }
    }

    private fun onSplashFinished() {
        if (!shouldRequestNotificationPermission()) {
            if (canShowDownloadBar()) DownloadBarLauncher.ensureRunning(this)
            goMain()
            return
        }
        markNotificationPermissionRequested()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            requestNotificationPermission()
        } else {
            pendingPermissionRequest = true
        }
    }

    private fun shouldRequestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (isNotificationPermissionGranted()) return false
        return !wasNotificationPermissionRequested()
    }

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun canShowDownloadBar(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isNotificationPermissionGranted()

    private fun isNotificationPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun wasNotificationPermissionRequested(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_REQUESTED, false)

    private fun markNotificationPermissionRequested() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
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
        const val PREFS_NAME = "notice_permission"
        const val KEY_REQUESTED = "requested"
    }
}
