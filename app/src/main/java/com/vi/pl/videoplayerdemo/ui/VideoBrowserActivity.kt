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
import android.view.Gravity
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
    private var pendingDownload: PendingDownload? = null
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
            userAgentString = userAgentString.replace("; wv", "")
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
            recentVideoUrls.clear()
            recentStreamUrls.clear()
            recentFacebookCdnUrls.clear()
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

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            showError()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            request.url.toString().let { url ->
                when {
                    VideoUrlDetector.isFacebookCdnUrl(url) || VideoUrlDetector.isTikTokCdnUrl(url) ||
                        VideoUrlDetector.isDirectVideoUrl(url) ->
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
        if (!pageLoaded || pageFailed) {
            Toast.makeText(this, R.string.browser_video_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        webView.evaluateJavascript(VIDEO_READY_SCRIPT) { readyEncoded ->
            val pageHasVideo = readyEncoded == "true"
            val sniffedMedia = recentVideoUrls.isNotEmpty() ||
                recentFacebookCdnUrls.isNotEmpty() ||
                recentStreamUrls.isNotEmpty()
            if (!pageHasVideo && !sniffedMedia) {
                Toast.makeText(this, R.string.browser_video_not_ready, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            Toast.makeText(this, R.string.browser_quality_resolving, Toast.LENGTH_SHORT).apply {
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(96))
                show()
            }
            webView.evaluateJavascript(CURRENT_VIDEO_URL_SCRIPT) { encodedUrl ->
                val resolved = encodedUrl.takeUnless { it == "null" }?.trim('"')
                    ?.replace("\\\\", "\\")?.replace("\\\"", "\"")
                val url = downloadUrl(resolved, webView.url)
                if (url == null) {
                    Toast.makeText(this, R.string.browser_no_video_found, Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }
                val fallback = facebookFallback(resolved)
                webView.evaluateJavascript(PAGE_META_SCRIPT) { metaEncoded ->
                    val meta = decodeJs(metaEncoded)
                    val pageTitle = meta.substringBefore('\n').ifBlank { webView.title }.orEmpty()
                    val coverUrl = meta.substringAfter('\n', "").trim()
                    webView.evaluateJavascript(VIDEO_INFO_SCRIPT) { info ->
                        val parts = info.takeUnless { it == "null" }?.trim('"')?.split(',') ?: emptyList()
                        val width = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val height = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val duration = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                        val page = webView.url ?: url
                        val youtube = VideoPlatform.fromUrl(page) == VideoPlatform.YOUTUBE
                        DownloadQualityCoordinator.resolve(
                            this,
                            QualityDownloadRequest(
                                url = url,
                                sourcePageUrl = page,
                                fallbackUrl = fallback,
                                title = pageTitle,
                                coverUrl = coverUrl,
                                userAgent = if (youtube) "" else webView.settings.userAgentString,
                                cookies = cookiesFor(page),
                                referer = if (youtube) "" else page,
                                durationSec = duration,
                                sourceHeight = listOf(width, height).filter { it > 0 }.minOrNull() ?: 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun downloadUrl(resolved: String?, pageUrl: String?): String? {
        val page = pageUrl?.takeIf(VideoUrlDetector::isDownloadableHttpUrl)
        val fromJs = resolved
            ?.takeUnless { it.isBlank() || VideoUrlDetector.isBlobUrl(it) }
        val sniffed = recentVideoUrls.toList().asReversed()
        val sniffedFacebook = recentFacebookCdnUrls.toList().asReversed()
        val candidates = listOfNotNull(fromJs) + sniffedFacebook + listOfNotNull(page) + sniffed
        return when (VideoPlatform.fromUrl(page ?: fromJs.orEmpty())) {
            VideoPlatform.FACEBOOK ->
                candidates.firstOrNull(VideoUrlDetector::isProgressiveFileUrl)
                    ?: candidates.firstNotNullOfOrNull { VideoUrlDetector.facebookDownloadUrl(it) }
            VideoPlatform.TIKTOK ->
                candidates.firstOrNull { VideoUrlDetector.isTikTokCdnUrl(it) || VideoUrlDetector.isDirectVideoUrl(it) }
                    ?: candidates.firstNotNullOfOrNull { VideoUrlDetector.tiktokDownloadUrl(it) }
            VideoPlatform.INSTAGRAM ->
                candidates.firstOrNull { VideoUrlDetector.isInstagramCdnUrl(it) || VideoUrlDetector.isDirectVideoUrl(it) }
                    ?: candidates.firstOrNull { !VideoUrlDetector.isFeedPage(it) }
            VideoPlatform.YOUTUBE -> page?.takeIf { !VideoUrlDetector.isFeedPage(it) } ?: fromJs
            else -> candidates.firstOrNull { !VideoUrlDetector.isFeedPage(it) }
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
                    enqueueDownload(url, facebookFallback(url), 0, webView.title.orEmpty())
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
        val stream = recentStreamUrls.lastOrNull()?.takeIf(VideoUrlDetector::isManifestUrl)
        val fromJs = resolved?.takeUnless(VideoUrlDetector::isBlobUrl)
            ?.takeIf {
                VideoUrlDetector.isManifestUrl(it) || VideoUrlDetector.isFacebookCdnUrl(it) ||
                    VideoUrlDetector.isTikTokCdnUrl(it) || VideoUrlDetector.isInstagramCdnUrl(it) ||
                    VideoUrlDetector.isDirectVideoUrl(it)
            }
            ?.let { if (VideoUrlDetector.isManifestUrl(it)) it else VideoUrlDetector.stripByteRange(it) }
        val cdn = fromJs
            ?: recentFacebookCdnUrls.lastOrNull()
            ?: recentVideoUrls.lastOrNull {
                VideoUrlDetector.isTikTokCdnUrl(it) || VideoUrlDetector.isInstagramCdnUrl(it) ||
                    VideoUrlDetector.isDirectVideoUrl(it)
            }
        return listOfNotNull(stream, cdn).distinct().joinToString("\n")
    }

    private fun enqueueDownload(
        url: String,
        fallbackUrl: String = "",
        maxHeight: Int = 0,
        title: String = "",
        coverUrl: String = "",
    ) {
        if (!VideoUrlDetector.isDownloadableHttpUrl(url)) {
            Toast.makeText(this, R.string.browser_no_video_found, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = PendingDownload(url, fallbackUrl, maxHeight, title, coverUrl)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_REQUEST)
            return
        }
        if (VideoUrlDetector.isFeedPage(url)) {
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
            sourcePageUrl = page,
            title = title.ifBlank { webView.title }.orEmpty(),
            coverUrl = coverUrl,
            userAgent = userAgent,
            cookies = cookiesFor(page),
            referer = if (youtube) "" else page,
            fallbackUrl = fallbackUrl,
            maxHeight = maxHeight,
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
            VideoPlatform.TIKTOK -> listOf(
                "https://www.tiktok.com",
                "https://m.tiktok.com",
            ).forEach { CookieManager.getInstance().getCookie(it)?.let(parts::add) }
            VideoPlatform.INSTAGRAM ->
                CookieManager.getInstance().getCookie("https://www.instagram.com")?.let(parts::add)
            else -> Unit
        }
        return parts.flatMap { it.split(';') }.map { it.trim() }.filter { it.contains('=') }.distinct().joinToString("; ")
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
        val pending = pendingDownload.also { pendingDownload = null } ?: return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            enqueueDownload(pending.url, pending.fallbackUrl, pending.maxHeight, pending.title, pending.coverUrl)
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

    private fun decodeJs(encoded: String?): String =
        encoded.takeUnless { it == null || it == "null" }?.trim('"')
            ?.replace("\\\\", "\\")
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            .orEmpty()

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val PAGE_TIMEOUT_MS = 20_000L
        private const val WRITE_STORAGE_REQUEST = 701
        private data class PendingDownload(
            val url: String,
            val fallbackUrl: String,
            val maxHeight: Int,
            val title: String = "",
            val coverUrl: String = "",
        )
        private const val VIDEO_READY_SCRIPT = """
            (function() {
              var videos = Array.from(document.querySelectorAll('video'));
              var vh = window.innerHeight || document.documentElement.clientHeight || 0;
              for (var i = 0; i < videos.length; i++) {
                var v = videos[i];
                var r = v.getBoundingClientRect();
                if (r.width < 40 || r.height < 40 || r.bottom <= 0 || r.top >= vh) continue;
                if (v.readyState >= 2 || v.videoWidth > 0) return true;
              }
              return false;
            })();
        """
        private const val VIDEO_INFO_SCRIPT = """
            (function() {
              const videos = Array.from(document.querySelectorAll('video'));
              const video = videos.find(function(v) { return !v.paused && v.readyState > 1; }) || videos[0];
              if (!video) return '0,0,0';
              return [video.videoWidth || 0, video.videoHeight || 0, Math.floor(video.duration || 0)].join(',');
            })();
        """
        private const val PAGE_META_SCRIPT = """
            (function() {
              var t = (document.querySelector('meta[property="og:title"]') || {}).content || document.title || '';
              var o = (document.querySelector('meta[property="og:image"]') || {}).content || '';
              return t + '\n' + o;
            })();
        """
        private const val CURRENT_VIDEO_URL_SCRIPT = """
            (function() {
              function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u || ''; } }
              function pick(u) {
                if (!u) return '';
                var tests = [/\/reel\/\d{5,}/i,/\/reels\/\d{5,}/i,/\/share\/r\/[^\/?#]+/i,/\/share\/v\/[^\/?#]+/i,/\/videos\/\d{5,}/i,/fb\.watch\/[^\/?#]+/i,/@[^\/"' ]+\/video\/\d+/i,/\/video\/\d{8,}/i];
                for (var i = 0; i < tests.length; i++) if (tests[i].test(u)) return abs(u).split('#')[0];
                return '';
              }
              function fbcdn(u) {
                return u && u.indexOf('fbcdn.net') !== -1 && (u.indexOf('efg=') !== -1 || u.indexOf('.mp4') !== -1);
              }
              function ttcdn(u) {
                return u && (u.indexOf('tiktokcdn') !== -1 || u.indexOf('muscdn.com') !== -1 || u.indexOf('/video/tos/') !== -1);
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
                  var links = node.querySelectorAll ? node.querySelectorAll('a[href*="/reel/"],a[href*="/reels/"],a[href*="/videos/"],a[href*="/share/r/"],a[href*="/video/"]') : [];
                  for (var i = 0; i < links.length && !chosen; i++) chosen = pick(links[i].href);
                  node = node.parentElement;
                }
                if (!chosen) {
                  var all = document.querySelectorAll('a[href*="/video/"],a[href*="/reel/"]');
                  var rect = video.getBoundingClientRect();
                  var best = '', bestDist = 1e9;
                  for (var i = 0; i < all.length; i++) {
                    var p = pick(all[i].href);
                    if (!p) continue;
                    var r = all[i].getBoundingClientRect();
                    var dist = Math.abs(r.top - rect.top) + Math.abs(r.left - rect.left);
                    if (dist < bestDist) { bestDist = dist; best = p; }
                  }
                  chosen = best;
                }
              }
              if (!chosen) {
                var html = document.documentElement.innerHTML || '';
                var tt = href.indexOf('tiktok') !== -1;
                if (tt) {
                  var tm = html.match(/\/@[^\/"' ]+\/video\/(\d{8,})/) || html.match(/"aweme_id"\s*:\s*"(\d{15,})"/) || html.match(/"itemId"\s*:\s*"(\d{15,})"/) || html.match(/"item_id"\s*:\s*"(\d{15,})"/);
                  if (tm) chosen = 'https://www.tiktok.com/@tiktok/video/' + tm[1];
                } else {
                  var id = (html.match(/\/reel\/(\d{10,})/) || html.match(/"video_id"\s*:\s*"?(\d{10,})/) || [])[1];
                  if (id) chosen = 'https://www.facebook.com/reel/' + id;
                }
              }
              if (!chosen) {
                try {
                  var entries = performance.getEntriesByType('resource');
                  for (var i = entries.length - 1; i >= 0; i--) {
                    if (fbcdn(entries[i].name) || ttcdn(entries[i].name)) { chosen = entries[i].name; break; }
                  }
                } catch (e) {}
              }
              var src = video ? (video.currentSrc || video.src || '') : '';
              if (fbcdn(src) || ttcdn(src)) {
                if (!/[?&](?:bytestart|byteend|bytes)=/i.test(src)) return src;
                return chosen || src;
              }
              if (src && src.indexOf('http') === 0 && src.indexOf('blob:') !== 0) return chosen || src;
              return chosen || '';
            })();
        """

        fun intent(context: Context, url: String) = Intent(context, VideoBrowserActivity::class.java)
            .putExtra(EXTRA_URL, url)
    }
}
