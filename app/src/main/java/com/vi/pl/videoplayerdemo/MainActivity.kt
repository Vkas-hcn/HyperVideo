package com.vi.pl.videoplayerdemo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.vi.pl.videoplayerdemo.hellohello.HintUtil
import com.vi.pl.videoplayerdemo.permission.NotificationPermissionHelper
import com.vi.pl.videoplayerdemo.ui.DownloadFragment
import com.vi.pl.videoplayerdemo.ui.HomeFragment
import com.vi.pl.videoplayerdemo.ui.ProfileFragment
import com.vi.pl.videoplayerdemo.ui.SearchFragment

class MainActivity : AppCompatActivity() {
    private val homeFragment = HomeFragment()
    private val searchFragment = SearchFragment()
    private val downloadFragment = DownloadFragment()
    private val profileFragment = ProfileFragment()
    private val permissionHandler = Handler(Looper.getMainLooper())
    private val homePromptRunnable = Runnable { showHomeNotificationPrompt() }

    /** 通知权限引导（对齐 EfileScan MainActivity.maybeShowHomeNotificationDialog）。 */
    private val permissionHost by lazy { NotificationPermissionHelper.bind(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 提前初始化，确保权限 launcher 在 Activity 进入 STARTED 前完成注册
        permissionHost
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val navBasePadding = bottomNav.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 顶部/左右留出状态栏；底部不留在根布局上，改由底部栏自己吃掉导航栏高度，
            // 这样底部栏背景能一直铺到屏幕底边，不会在下面露出一条空白。
            view.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.updatePadding(bottom = navBasePadding + bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, homeFragment, TAG_HOME)
                .add(R.id.fragment_container, searchFragment, TAG_SEARCH).hide(searchFragment)
                .add(R.id.fragment_container, downloadFragment, TAG_DOWNLOAD).hide(downloadFragment)
                .add(R.id.fragment_container, profileFragment, TAG_PROFILE).hide(profileFragment)
                .commit()
        }

        bottomNav.apply {
            // Keep original red/gray colors from tab_* selectors; Material3 would otherwise tint purple.
            itemIconTintList = null
            setOnItemSelectedListener { item ->
                showFragment(
                    when (item.itemId) {
                        R.id.navigation_home -> TAG_HOME
                        R.id.navigation_search -> TAG_SEARCH
                        R.id.navigation_download -> TAG_DOWNLOAD
                        else -> TAG_PROFILE
                    },
                )
                // 每次回到首页都重新判定一次通知权限（系统框能弹就弹系统框）
                if (item.itemId == R.id.navigation_home) scheduleHomeNotificationPrompt()
                true
            }
        }
    }

    /**
     * 每次首页显示（进入首页 / 切回首页 Tab）都重新判定：没授权就优先弹系统框，
     * 系统框弹不出来才弹自定义框。延迟 300ms，避免和启动页收尾、广告关闭的回调抢时机。
     */
    override fun onResume() {
        super.onResume()
        scheduleHomeNotificationPrompt()
    }

    override fun onPause() {
        permissionHandler.removeCallbacks(homePromptRunnable)
        super.onPause()
    }

    private fun scheduleHomeNotificationPrompt() {
        permissionHandler.removeCallbacks(homePromptRunnable)
        permissionHandler.postDelayed(homePromptRunnable, HOME_NOTIFICATION_PROMPT_DELAY_MS)
    }

    private fun showHomeNotificationPrompt() {
        if (isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        permissionHost.showNotificationInAppPrompt { granted ->
            // 授权成功后补排通知链路（等价 EfileScan 的 NotificationFeature.start）
            if (granted) HintUtil.markLing()
        }
    }

    private fun showFragment(tag: String) {
        val target = supportFragmentManager.findFragmentByTag(tag) ?: return
        supportFragmentManager.beginTransaction().apply {
            supportFragmentManager.fragments.forEach { fragment ->
                if (fragment == target) show(fragment) else hide(fragment)
            }
        }.commit()
    }

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_SEARCH = "search"
        const val TAG_DOWNLOAD = "download"
        const val TAG_PROFILE = "profile"
        const val HOME_NOTIFICATION_PROMPT_DELAY_MS = 300L
    }
}
