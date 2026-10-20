package com.vi.pl.videoplayerdemo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.vi.pl.videoplayerdemo.ui.DownloadFragment
import com.vi.pl.videoplayerdemo.ui.HomeFragment
import com.vi.pl.videoplayerdemo.ui.ProfileFragment
import com.vi.pl.videoplayerdemo.ui.SearchFragment

class MainActivity : AppCompatActivity() {
    private val homeFragment = HomeFragment()
    private val searchFragment = SearchFragment()
    private val downloadFragment = DownloadFragment()
    private val profileFragment = ProfileFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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

        findViewById<BottomNavigationView>(R.id.bottom_navigation).apply {
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
                true
            }
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
    }
}
