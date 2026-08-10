package com.vi.pl.videoplayerdemo.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.search.VideoPlatform
import com.vi.pl.videoplayerdemo.search.normalizeUrl

class SearchFragment : Fragment(R.layout.fragment_search) {
    private var lastOpenedUrl: String? = null

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
        view.findViewById<View>(R.id.btn_search_help).setOnClickListener { }
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
}
