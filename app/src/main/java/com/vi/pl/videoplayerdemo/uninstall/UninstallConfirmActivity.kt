package com.vi.pl.videoplayerdemo.uninstall

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.vi.pl.videoplayerdemo.HyperVideoApplication
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** 全屏卸载确认页：Not Now 回首页，Uninstall 二次插屏后打开应用详情设置页。 */
class UninstallConfirmActivity : AppCompatActivity() {

    private val navigating = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
        setContentView(R.layout.activity_uninstall_confirm)
        applyInsets()

        findViewById<View>(R.id.confirm_back).setOnClickListener { finish() }
        findViewById<View>(R.id.confirm_not_now).setOnClickListener { goMain() }
        findViewById<View>(R.id.confirm_uninstall).setOnClickListener { showSecondAdThenSettings() }

        UninstallAds.preload(this)
    }

    private fun goMain() {
        if (isFinishing || isDestroyed) return
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
    }

    /** 第二次插屏：仅用缓存；无缓存/失败/后台时直接/安全跳过。 */
    private fun showSecondAdThenSettings() {
        if (!navigating.compareAndSet(false, true)) return
        UninstallAds.showCached(this) {
            if (isFinishing || isDestroyed) return@showCached
            if (!HyperVideoApplication.get().isAppForeground()) {
                navigating.set(false)
                return@showCached
            }
            openSettings()
        }
    }

    private fun openSettings() {
        val started = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }.isSuccess
        if (started) {
            finishAffinity()
        } else {
            Toast.makeText(this, R.string.uninstall_settings_failed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.confirm_root)
        val header = findViewById<View>(R.id.confirm_header)
        val content = findViewById<View>(R.id.confirm_content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = bars.top)
            header.layoutParams = header.layoutParams.apply {
                height = bars.top + dp(64)
            }
            content.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
