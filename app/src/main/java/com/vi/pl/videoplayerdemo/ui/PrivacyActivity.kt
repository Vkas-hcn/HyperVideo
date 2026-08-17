package com.vi.pl.videoplayerdemo.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vi.pl.videoplayerdemo.R

class PrivacyActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var loading: View
    private lateinit var error: View
    private var failed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.privacy_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        webView = findViewById(R.id.privacy_webview)
        loading = findViewById(R.id.privacy_loading)
        error = findViewById(R.id.privacy_error)
        findViewById<View>(R.id.btn_back).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        findViewById<View>(R.id.privacy_retry).setOnClickListener { load() }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                failed = false
                loading.visibility = View.VISIBLE
                error.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading.visibility = View.GONE
                error.visibility = if (failed) View.VISIBLE else View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                resourceError: WebResourceError?,
            ) {
                if (request?.isForMainFrame != false) showError()
            }
        }
        load()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun load() {
        failed = false
        error.visibility = View.GONE
        loading.visibility = View.VISIBLE
        webView.loadUrl(getString(R.string.privacy_policy_url))
    }

    private fun showError() {
        failed = true
        loading.visibility = View.GONE
        error.visibility = View.VISIBLE
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, PrivacyActivity::class.java)
    }
}
