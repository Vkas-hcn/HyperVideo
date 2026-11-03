package com.vi.pl.videoplayerdemo.uninstall

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.ads.AdMobManager
import com.vi.pl.videoplayerdemo.ads.NativeAdPresenter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** 卸载挽留第 1 步：问题卡 + Keep watching / Still uninstalling。 */
class UninstallRetentionActivity : AppCompatActivity() {

    private var nativePresenter: NativeAdPresenter? = null
    private val navigating = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
        setContentView(R.layout.activity_uninstall_retention)
        applyInsets()

        onBackPressedDispatcher.addCallback(this) { goMain() }
        findViewById<View>(R.id.uninstall_back).setOnClickListener { goMain() }
        findViewById<View>(R.id.uninstall_keep).setOnClickListener { goMain() }
        findViewById<View>(R.id.uninstall_fix).setOnClickListener { goMain() }
        findViewById<View>(R.id.uninstall_speed).setOnClickListener { goMain() }
        findViewById<View>(R.id.uninstall_still).setOnClickListener { startConfirmAfterCachedAd() }

        nativePresenter = NativeAdPresenter(
            AdMobManager.NativePlacement.HOME,
            findViewById(R.id.uninstall_ad_container),
            R.layout.item_native_ad_small,
        ).also { it.show() }
        UninstallAds.preload(this)
    }

    override fun onResume() {
        super.onResume()
        nativePresenter?.show()
    }

    override fun onDestroy() {
        nativePresenter?.release()
        nativePresenter = null
        super.onDestroy()
    }

    /** 有缓存插屏先展示；无缓存/失败立即进确认页。 */
    private fun startConfirmAfterCachedAd() {
        if (!navigating.compareAndSet(false, true)) return
        UninstallAds.showCached(this) {
            navigating.set(false)
            if (isFinishing || isDestroyed) return@showCached
            startActivity(Intent(this, UninstallConfirmActivity::class.java))
        }
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
        val root = findViewById<View>(R.id.uninstall_root)
        val header = findViewById<View>(R.id.uninstall_header)
        val actions = findViewById<View>(R.id.uninstall_actions)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = bars.top)
            header.layoutParams = header.layoutParams.apply {
                height = bars.top + dp(64)
            }
            actions.updatePadding(bottom = bars.bottom + dp(24))
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
