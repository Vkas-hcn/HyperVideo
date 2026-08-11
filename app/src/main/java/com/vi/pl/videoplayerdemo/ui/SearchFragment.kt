package com.vi.pl.videoplayerdemo.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.search.VideoPlatform
import com.vi.pl.videoplayerdemo.search.normalizeUrl

class SearchFragment : Fragment(R.layout.fragment_search) {
    private var lastOpenedUrl: String? = null
    private val guidePrefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val input = view.findViewById<EditText>(R.id.search_input)
        val container = view.findViewById<View>(R.id.search_input_container)

        input.setOnFocusChangeListener { _, hasFocus ->
            container.background = ContextCompat.getDrawable(
                requireContext(),
                if (hasFocus) R.drawable.bg_search_input_focused else R.drawable.bg_search_input_normal,
            )
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Paste / bulk insert: start download when a platform link appears.
                if (count > 8) tryDownload(s?.toString().orEmpty(), fromPaste = true)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                tryDownload(input.text?.toString().orEmpty(), fromPaste = false)
                hideKeyboard(input)
                input.clearFocus()
                true
            } else {
                false
            }
        }

        view.findViewById<View>(R.id.platform_tiktok).setOnClickListener {
            openPlatformSite(VideoPlatform.TIKTOK)
        }
        view.findViewById<View>(R.id.platform_instagram).setOnClickListener {
            openPlatformSite(VideoPlatform.INSTAGRAM)
        }
        view.findViewById<View>(R.id.platform_youtube).setOnClickListener {
            openPlatformSite(VideoPlatform.YOUTUBE)
        }
        view.findViewById<View>(R.id.platform_facebook).setOnClickListener {
            openPlatformSite(VideoPlatform.FACEBOOK)
        }

        setupGuide(view)
    }

    private fun setupGuide(view: View) {
        val overlay = view.findViewById<View>(R.id.search_guide_overlay)
        val pager = view.findViewById<ViewPager2>(R.id.guide_pager)
        val skip = view.findViewById<TextView>(R.id.btn_skip)
        val action = view.findViewById<TextView>(R.id.btn_guide_action)
        val dots = view.findViewById<LinearLayout>(R.id.guide_dots)

        pager.adapter = GuidePagerAdapter(PAGES)
        setupDots(dots)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateChrome(position, skip, action, dots)
            }
        })

        view.findViewById<View>(R.id.btn_search_help).setOnClickListener {
            showGuide(overlay, pager, skip, action, dots)
        }
        skip.setOnClickListener { closeGuide(overlay) }
        action.setOnClickListener {
            if (pager.currentItem >= PAGES.lastIndex) {
                closeGuide(overlay)
            } else {
                pager.currentItem += 1
            }
        }

        if (!guidePrefs.getBoolean(KEY_GUIDE_SHOWN, false)) {
            showGuide(overlay, pager, skip, action, dots)
        }
    }

    private fun showGuide(
        overlay: View,
        pager: ViewPager2,
        skip: TextView,
        action: TextView,
        dots: LinearLayout,
    ) {
        pager.setCurrentItem(0, false)
        updateChrome(0, skip, action, dots)
        overlay.visibility = View.VISIBLE
    }

    private fun closeGuide(overlay: View) {
        guidePrefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        overlay.visibility = View.GONE
    }

    private fun setupDots(container: LinearLayout) {
        container.removeAllViews()
        repeat(PAGES.size) { index ->
            val size = (8 * resources.displayMetrics.density).toInt()
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = if (index == 0) 0 else (6 * resources.displayMetrics.density).toInt()
                }
                setBackgroundResource(R.drawable.bg_guide_dot)
            })
        }
    }

    private fun updateChrome(
        position: Int,
        skip: TextView,
        action: TextView,
        dots: LinearLayout,
    ) {
        skip.visibility = if (position >= PAGES.lastIndex) View.INVISIBLE else View.VISIBLE
        action.setText(if (position >= PAGES.lastIndex) R.string.got_it else R.string.next)
        val density = resources.displayMetrics.density
        for (index in 0 until dots.childCount) {
            val dot = dots.getChildAt(index)
            val active = index == position
            val layoutParams = dot.layoutParams as LinearLayout.LayoutParams
            layoutParams.width = ((if (active) 24 else 8) * density).toInt()
            layoutParams.height = (8 * density).toInt()
            dot.layoutParams = layoutParams
            dot.setBackgroundResource(
                if (active) R.drawable.bg_guide_dot_active else R.drawable.bg_guide_dot,
            )
        }
    }

    private fun openPlatformSite(platform: VideoPlatform) {
        startActivity(VideoBrowserActivity.intent(requireContext(), platform.homeUrl))
    }

    private fun tryDownload(raw: String, fromPaste: Boolean) {
        val uri = normalizeUrl(raw) ?: run {
            if (!fromPaste) {
                Toast.makeText(requireContext(), R.string.search_link_invalid, Toast.LENGTH_SHORT).show()
            }
            return
        }
        VideoPlatform.fromUrl(raw) ?: run {
            if (!fromPaste) {
                Toast.makeText(requireContext(), R.string.search_link_unsupported, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val url = uri.toString()
        if (fromPaste && url == lastOpenedUrl) return
        lastOpenedUrl = url
        startActivity(VideoBrowserActivity.intent(requireContext(), url))
    }

    private fun hideKeyboard(target: View) {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private class GuidePagerAdapter(
        private val pages: List<Page>,
    ) : RecyclerView.Adapter<GuidePagerAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_page, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(pages[position])
        }

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

        val PAGES = listOf(
            Page(R.drawable.ic_guide1, R.string.guide_title_1, R.string.guide_desc_1),
            Page(R.drawable.ic_guide2, R.string.guide_title_2, R.string.guide_desc_2),
            Page(R.drawable.ic_guide3, R.string.guide_title_3, R.string.guide_desc_3),
        )
    }
}
