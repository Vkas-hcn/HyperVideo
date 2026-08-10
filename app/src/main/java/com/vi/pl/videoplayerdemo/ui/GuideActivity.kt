package com.vi.pl.videoplayerdemo.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R

class GuideActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private var splashAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_GUIDE_SHOWN, false)) {
            showSplash()
        } else {
            showGuide()
        }
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
                    goHome(markGuideShown = false)
                }
            })
            start()
        }
    }

    private fun showGuide() {
        setContentView(R.layout.activity_guide)
        applyInsets(findViewById(android.R.id.content))

        val pager = findViewById<ViewPager2>(R.id.guide_pager)
        val skip = findViewById<TextView>(R.id.btn_skip)
        val action = findViewById<TextView>(R.id.btn_guide_action)
        val dots = findViewById<LinearLayout>(R.id.guide_dots)

        pager.adapter = GuidePagerAdapter(PAGES)
        setupDots(dots, PAGES.size)
        updateChrome(0, skip, action, dots)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateChrome(position, skip, action, dots)
            }
        })

        skip.setOnClickListener { goHome(markGuideShown = true) }
        action.setOnClickListener {
            val last = pager.currentItem >= PAGES.lastIndex
            if (last) {
                goHome(markGuideShown = true)
            } else {
                pager.currentItem = pager.currentItem + 1
            }
        }
    }

    private fun setupDots(container: LinearLayout, count: Int) {
        container.removeAllViews()
        repeat(count) { index ->
            val dot = View(this).apply {
                val size = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = if (index == 0) 0 else (6 * resources.displayMetrics.density).toInt()
                }
                setBackgroundResource(R.drawable.bg_guide_dot)
            }
            container.addView(dot)
        }
    }

    private fun updateChrome(position: Int, skip: TextView, action: TextView, dots: LinearLayout) {
        val last = position >= PAGES.lastIndex
        skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        action.setText(if (last) R.string.got_it else R.string.next)
        val density = resources.displayMetrics.density
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            val active = i == position
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            if (active) {
                lp.width = (24 * density).toInt()
                lp.height = (8 * density).toInt()
                dot.setBackgroundResource(R.drawable.bg_guide_dot_active)
            } else {
                lp.width = (8 * density).toInt()
                lp.height = (8 * density).toInt()
                dot.setBackgroundResource(R.drawable.bg_guide_dot)
            }
            dot.layoutParams = lp
        }
    }

    private fun goHome(markGuideShown: Boolean) {
        if (markGuideShown) {
            prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        }
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

    private class GuidePagerAdapter(
        private val pages: List<Page>,
    ) : RecyclerView.Adapter<GuidePagerAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_guide_page, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(pages[position])

        override fun getItemCount(): Int = pages.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val image: ImageView = itemView.findViewById(R.id.guide_image)
            private val title: TextView = itemView.findViewById(R.id.guide_title)
            private val desc: TextView = itemView.findViewById(R.id.guide_desc)

            fun bind(page: Page) {
                image.setImageResource(page.image)
                title.setText(page.title)
                desc.setText(page.desc)
            }
        }
    }

    private data class Page(
        val image: Int,
        val title: Int,
        val desc: Int,
    )

    private companion object {
        const val PREFS_NAME = "guide_prefs"
        const val KEY_GUIDE_SHOWN = "guide_shown"
        const val SPLASH_MS = 2000L

        val PAGES = listOf(
            Page(R.drawable.ic_guide1, R.string.guide_title_1, R.string.guide_desc_1),
            Page(R.drawable.ic_guide2, R.string.guide_title_2, R.string.guide_desc_2),
            Page(R.drawable.ic_guide3, R.string.guide_title_3, R.string.guide_desc_3),
        )
    }
}
