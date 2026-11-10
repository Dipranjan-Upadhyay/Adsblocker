package com.adshield

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ad-free browser. Runs in its own process (":browser"), so the heavy WebView memory is
 * released when you leave it and never weighs on the always-running VPN service.
 */
class BrowserActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var counter: TextView
    private lateinit var content: LinearLayout
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val blockedOnPage = AtomicInteger()

    private val engine get() = FilterEngine.get(applicationContext)
    private val youtubeJs by lazy { assets.open("youtube.js").bufferedReader().readText() }
    private val sponsoredJs by lazy { assets.open("sponsored.js").bufferedReader().readText() }
    private val cssJs = "(function(){try{var c=AdShieldNative.css(location.hostname);if(!c)return;" +
        "var s=document.createElement('style');s.textContent=c;" +
        "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
    private var docStartSupported = false

    private val homeHtml = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{font-family:sans-serif;background:#111;color:#eee;padding:24px;text-align:center}
        a{display:block;margin:12px auto;max-width:320px;padding:14px;border-radius:10px;background:#1E88E5;
        color:#fff;text-decoration:none;font-size:18px}p{color:#aaa}</style></head><body>
        <h2>AdShield Browser</h2><p>Ads are blocked on every site you open here.</p>
        <a href="https://m.youtube.com">YouTube</a>
        <a href="https://m.facebook.com">Facebook</a>
        <a href="https://www.instagram.com">Instagram</a>
        <a href="https://www.google.com">Google</a>
        <p>Tip: in the YouTube app, tap Share &rarr; AdShield Browser to watch any video here without ads.</p>
        </body></html>""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; minWidth = 0; minimumWidth = 0
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            setOnClickListener { onClick() }
        }

        urlBar = EditText(this).apply {
            setSingleLine(); hint = "Search or type URL"; textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    navigate(text.toString()); true
                } else false
            }
        }
        counter = TextView(this).apply { textSize = 13f; setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0) }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(btn("‹") { if (web.canGoBack()) web.goBack() })
            addView(urlBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(counter)
            addView(btn("⟳") { web.reload() })
            addView(btn("⌂") { showHome() })
        }

        web = WebView(this)
        setupWebView()

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        fullscreen = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE }
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(fullscreen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else if (!handleIntent(intent)) showHome()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Present as regular Chrome (some sites, incl. Google sign-in, reject WebView's "wv" tag).
            userAgentString = userAgentString.replace("; wv)", ")").replace(" Version/4.0", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(CssBridge(), "AdShieldNative")

        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartSupported) {
            // Injected before any page script runs: the same timing Brave uses.
            WebViewCompat.addDocumentStartJavaScript(web, cssJs, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(web, youtubeJs,
                setOf("https://youtube.com", "https://*.youtube.com"))
            WebViewCompat.addDocumentStartJavaScript(web, sponsoredJs,
                setOf("https://facebook.com", "https://*.facebook.com",
                      "https://instagram.com", "https://*.instagram.com"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (req.isForMainFrame) return null
                val host = req.url.host ?: return null
                if (!engine.shouldBlock(req.url.toString(), host)) return null
                val n = blockedOnPage.incrementAndGet()
                runOnUiThread { counter.text = "🛡$n" }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val uri = req.url
                return when (uri.scheme) {
                    "http", "https" -> false
                    "intent" -> {
                        // Sites try to bounce you into their app (where ads are back). Stay here instead.
                        val fallback = try {
                            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                .getStringExtra("browser_fallback_url")
                        } catch (_: Exception) { null }
                        if (fallback != null) view.loadUrl(fallback)
                        true
                    }
                    "vnd.youtube", "youtube", "fb", "instagram" -> true
                    else -> { // tel:, mailto:, market:, upi: ...
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                blockedOnPage.set(0)
                counter.text = ""
                if (!url.startsWith("about:")) urlBar.setText(url)
                if (!docStartSupported) injectFallback(view, Uri.parse(url).host ?: "")
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; customViewCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                fullscreen.visibility = View.VISIBLE
                content.visibility = View.GONE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                setFullscreenMode(true)
            }

            override fun onHideCustomView() = exitFullscreen()
        }
    }

    /** Older WebView without document-start scripts: inject as early as we can. */
    private fun injectFallback(view: WebView, host: String) {
        view.evaluateJavascript(cssJs, null)
        if (host == "youtube.com" || host.endsWith(".youtube.com")) view.evaluateJavascript(youtubeJs, null)
        if (host.endsWith("facebook.com") || host.endsWith("instagram.com")) view.evaluateJavascript(sponsoredJs, null)
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        content.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setFullscreenMode(false)
    }

    @Suppress("DEPRECATION")
    private fun setFullscreenMode(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (on) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            else 0
        }
    }

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            ' ' !in text && '.' in text -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
        web.loadUrl(url)
        web.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun showHome() {
        urlBar.setText("")
        web.loadDataWithBaseURL("about:blank", homeHtml, "text/html", "utf-8", null)
    }

    /** Opens links sent here via "Open with" or the Share menu (e.g. from the YouTube app). */
    private fun handleIntent(i: Intent?): Boolean {
        val link = when (i?.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("https?://\\S+").find(it)?.value }
            else -> null
        } ?: return false
        web.loadUrl(link)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        FilterEngine.reloadIfStale(applicationContext)
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Plain Activity API")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private inner class CssBridge {
        @JavascriptInterface
        fun css(host: String): String = engine.cssFor(host)
    }
}
