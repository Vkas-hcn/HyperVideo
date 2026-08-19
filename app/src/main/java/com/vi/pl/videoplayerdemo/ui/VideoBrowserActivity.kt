package com.vi.pl.videoplayerdemo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.download.VideoDownloadRepository
import com.vi.pl.videoplayerdemo.search.VideoPlatform
import com.vi.pl.videoplayerdemo.search.VideoUrlDetector

class VideoBrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var lockIcon: ImageView
    private lateinit var loading: View
    private lateinit var errorState: View
    private val recentVideoUrls = ArrayDeque<String>(3)
    private val recentStreamUrls = ArrayDeque<String>(3)
    private val recentFacebookCdnUrls = ArrayDeque<String>(3)
    private val handler = Handler(Looper.getMainLooper())
    private var pageLoaded = false
    private var pageFailed = false
    private var pendingDownloadUrl: String? = null
    private val timeout: Runnable = Runnable {
        if (!pageLoaded) showError()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_browser)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.video_browser_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        webView = findViewById(R.id.video_browser_webview)
        address = findViewById(R.id.video_browser_address)
        lockIcon = findViewById(R.id.video_browser_lock)
        loading = findViewById(R.id.video_browser_loading)
        errorState = findViewById(R.id.video_browser_error)
        configureWebView()

        findViewById<View>(R.id.video_browser_back).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        findViewById<View>(R.id.video_browser_refresh).setOnClickListener {
            load(address.text.toString())
        }
        findViewById<View>(R.id.video_browser_download).setOnClickListener { downloadCurrentVideo() }
        findViewById<View>(R.id.video_browser_help).setOnClickListener { }
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                load(address.text.toString())
                true
            } else false
        }

        load(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = browserClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loading.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    private fun browserClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.scheme !in setOf("http", "https")

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageLoaded = false
            pageFailed = false
            errorState.visibility = View.GONE
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, PAGE_TIMEOUT_MS)
        }

        override fun onPageFinished(view: WebView, url: String) {
            pageLoaded = true
            handler.removeCallbacks(timeout)
            loading.visibility = View.GONE
            if (!pageFailed) errorState.visibility = View.GONE
            setAddressText(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame) showError()
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) showError()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            showError()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            request.url.toString().let { url ->
                when {
                    VideoUrlDetector.isFacebookCdnUrl(url) || VideoUrlDetector.isDirectVideoUrl(url) ->
                        rememberVideoUrl(url)
                    VideoUrlDetector.isManifestUrl(url) -> rememberStreamUrl(url)
                }
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    private fun load(rawUrl: String) {
        val url = rawUrl.trim().let { if (it.contains("://")) it else "https://$it" }
        if (!VideoUrlDetector.isDownloadableHttpUrl(url)) {
            Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        recentVideoUrls.clear()
        recentStreamUrls.clear()
        recentFacebookCdnUrls.clear()
        setAddressText(url)
        webView.loadUrl(url)
    }

    private fun setAddressText(url: String) {
        val display = url.removePrefix("https://").removePrefix("http://")
        address.setText(display)
        lockIcon.setImageResource(
            if (url.startsWith("https://", ignoreCase = true)) R.drawable.ic_lock else R.drawable.ic_unlock,
        )
    }

    private fun rememberVideoUrl(url: String) = runOnUiThread {
        if (VideoUrlDetector.isFacebookCdnUrl(url)) {
            val full = VideoUrlDetector.stripByteRange(url)
            recentFacebookCdnUrls.remove(full)
            if (recentFacebookCdnUrls.size == 3) recentFacebookCdnUrls.removeFirst()
            recentFacebookCdnUrls.addLast(full)
        }
        val stored = VideoUrlDetector.facebookReelUrlFromCdn(url)
            ?: url.takeUnless(VideoUrlDetector::hasByteRange)
            ?: return@runOnUiThread
        recentVideoUrls.remove(stored)
        if (recentVideoUrls.size == 3) recentVideoUrls.removeFirst()
        recentVideoUrls.addLast(stored)
    }

    private fun downloadCurrentVideo() {
        webView.evaluateJavascript(CURRENT_VIDEO_URL_SCRIPT) { encodedUrl ->
            val resolved = encodedUrl.takeUnless { it == "null" }?.trim('"')
                ?.replace("\\\\", "\\")?.replace("\\\"", "\"")
            downloadUrl(resolved, webView.url)
                ?.let { enqueueDownload(it, facebookFallback(resolved)) }
                ?: Toast.makeText(this, R.string.browser_no_video_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadUrl(resolved: String?, pageUrl: String?): String? {
        val page = pageUrl?.takeIf(VideoUrlDetector::isDownloadableHttpUrl)
        val fromJs = resolved
            ?.takeUnless(VideoUrlDetector::isBlobUrl)
        val sniffed = recentVideoUrls.toList().asReversed()
        val candidates = listOfNotNull(fromJs, page) + sniffed
        return when (VideoPlatform.fromUrl(page ?: fromJs.orEmpty())) {
            VideoPlatform.FACEBOOK ->
                candidates.firstNotNullOfOrNull { VideoUrlDetector.facebookDownloadUrl(it) }
                    ?: candidates.firstOrNull { VideoUrlDetector.isDirectVideoUrl(it) }
            VideoPlatform.YOUTUBE -> page ?: fromJs
            else -> page ?: fromJs ?: sniffed.firstOrNull()
        }
    }

    private fun showVideoSelection() {
        val urls = recentVideoUrls.toList().asReversed()
        if (urls.isEmpty()) {
            if (recentStreamUrls.isNotEmpty()) {
                showStreamUnsupported()
                return
            }
            Toast.makeText(this, R.string.browser_no_video_found, Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(this, R.style.FilterBottomSheetTheme)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        content.addView(TextView(this).apply {
            setText(R.string.browser_choose_video)
            setTextColor(getColor(R.color.white))
            textSize = 20f
            typeface = resources.getFont(R.font.inter_bold)
        })
        urls.forEachIndexed { index, url ->
            content.addView(TextView(this).apply {
                text = url
                setTextColor(getColor(R.color.player_text_secondary))
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(20), 0, dp(20))
                setOnClickListener {
                    sheet.dismiss()
                    enqueueDownload(url, facebookFallback(url))
                }
            })
            if (index < urls.lastIndex) content.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(getColor(R.color.player_option_selected_bg))
            })
        }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun facebookFallback(resolved: String?): String {
        val fromJs = resolved?.takeUnless(VideoUrlDetector::isBlobUrl)
            ?.takeIf(VideoUrlDetector::isFacebookCdnUrl)
            ?.let(VideoUrlDetector::stripByteRange)
        return fromJs ?: recentFacebookCdnUrls.lastOrNull().orEmpty()
    }

    private fun enqueueDownload(url: String, fallbackUrl: String = "") {
        if (!VideoUrlDetector.isDownloadableHttpUrl(url)) {
            Toast.makeText(this, R.string.browser_no_video_found, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadUrl = url
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_REQUEST)
            return
        }
        if (VideoPlatform.fromUrl(url) == VideoPlatform.YOUTUBE && !isYoutubeWatchUrl(url)) {
            Toast.makeText(this, R.string.browser_no_video_found, Toast.LENGTH_SHORT).show()
            return
        }
        // YouTube's extractor must pick its own client. The WebView Android UA causes HTTP 400;
        // cookies from this session are still required to pass YouTube's bot check.
        val page = webView.url ?: url
        val youtube = VideoPlatform.fromUrl(page) == VideoPlatform.YOUTUBE
        val userAgent = if (youtube) "" else webView.settings.userAgentString
        VideoDownloadRepository.enqueue(
            this,
            url,
            userAgent = userAgent,
            cookies = cookiesFor(page),
            referer = if (youtube) "" else page,
            fallbackUrl = fallbackUrl,
        )
        Toast.makeText(this, R.string.search_download_queued, Toast.LENGTH_SHORT).show()
    }

    private fun cookiesFor(url: String): String {
        val parts = mutableListOf<String>()
        CookieManager.getInstance().getCookie(url)?.let(parts::add)
        when (VideoPlatform.fromUrl(url)) {
            VideoPlatform.YOUTUBE ->
                CookieManager.getInstance().getCookie("https://www.youtube.com")?.let(parts::add)
            VideoPlatform.FACEBOOK -> listOf(
                "https://www.facebook.com",
                "https://m.facebook.com",
                "https://web.facebook.com",
            ).forEach { CookieManager.getInstance().getCookie(it)?.let(parts::add) }
            else -> Unit
        }
        return parts.flatMap { it.split(';') }.map { it.trim() }.filter { it.contains('=') }.distinct().joinToString("; ")
    }

    private fun isYoutubeWatchUrl(url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        val path = uri.path.orEmpty()
        return !uri.getQueryParameter("v").isNullOrBlank() ||
            path.contains("/shorts/") && path.trimEnd('/').substringAfterLast('/').length >= 6 ||
            path.contains("/live/") ||
            path.contains("/embed/") ||
            uri.host?.contains("youtu.be") == true
    }

    private fun rememberStreamUrl(url: String) = runOnUiThread {
        recentStreamUrls.remove(url)
        if (recentStreamUrls.size == 3) recentStreamUrls.removeFirst()
        recentStreamUrls.addLast(url)
    }

    private fun showStreamUnsupported() {
        Toast.makeText(this, R.string.browser_stream_unsupported, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != WRITE_STORAGE_REQUEST) return
        val url = pendingDownloadUrl.also { pendingDownloadUrl = null } ?: return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            enqueueDownload(url)
        } else {
            Toast.makeText(this, R.string.browser_download_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showError() {
        runOnUiThread {
            pageFailed = true
            handler.removeCallbacks(timeout)
            webView.stopLoading()
            loading.visibility = View.GONE
            errorState.visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val PAGE_TIMEOUT_MS = 20_000L
        private const val WRITE_STORAGE_REQUEST = 701
        private const val CURRENT_VIDEO_URL_SCRIPT = """
            (function() {
              function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u || ''; } }
              function pick(u) {
                if (!u) return '';
                var tests = [/\/reel\/\d{5,}/i,/\/reels\/\d{5,}/i,/\/share\/r\/[^\/?#]+/i,/\/share\/v\/[^\/?#]+/i,/\/videos\/\d{5,}/i,/fb\.watch\/[^\/?#]+/i];
                for (var i = 0; i < tests.length; i++) if (tests[i].test(u)) return abs(u).split('#')[0];
                return '';
              }
              function fbcdn(u) {
                return u && u.indexOf('fbcdn.net') !== -1 && (u.indexOf('efg=') !== -1 || u.indexOf('.mp4') !== -1);
              }
              var href = location.href || '';
              var canon = (document.querySelector('link[rel="canonical"]') || {}).href || '';
              var og = (document.querySelector('meta[property="og:url"]') || {}).content || '';
              var chosen = pick(href) || pick(canon) || pick(og);
              var videos = Array.from(document.querySelectorAll('video'));
              var video = videos.find(function(v) { return !v.paused && v.readyState > 1; }) || videos[0];
              if (!chosen && video) {
                var node = video;
                for (var d = 0; d < 14 && node && !chosen; d++) {
                  var links = node.querySelectorAll ? node.querySelectorAll('a[href*="/reel/"],a[href*="/reels/"],a[href*="/videos/"],a[href*="/share/r/"]') : [];
                  for (var i = 0; i < links.length && !chosen; i++) chosen = pick(links[i].href);
                  node = node.parentElement;
                }
              }
              if (!chosen) {
                var html = document.documentElement.innerHTML || '';
                var id = (html.match(/\/reel\/(\d{10,})/) || html.match(/"video_id"\s*:\s*"?(\d{10,})/) || [])[1];
                if (id) chosen = 'https://www.facebook.com/reel/' + id;
              }
              if (!chosen) {
                try {
                  var entries = performance.getEntriesByType('resource');
                  for (var i = entries.length - 1; i >= 0; i--) {
                    if (fbcdn(entries[i].name)) { chosen = entries[i].name; break; }
                  }
                } catch (e) {}
              }
              var src = video ? (video.currentSrc || video.src || '') : '';
              if (fbcdn(src)) return chosen && chosen.indexOf('/reel/') !== -1 ? chosen : src;
              if (src && src.indexOf('http') === 0) return chosen || src;
              return chosen || href;
            })();
        """

        fun intent(context: Context, url: String) = Intent(context, VideoBrowserActivity::class.java)
            .putExtra(EXTRA_URL, url)
    }
}
