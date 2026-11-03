package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

class MainActivity : Activity() {

    data class Channel(val name: String, val url: String)

    companion object {

        // Lista remota (formato: NOME | [testo](https://url) )
        const val REMOTE_LIST_URL =
            "https://raw.githubusercontent.com/andreaclementi1975-hue/stream-config/refs/heads/main/stream4"

        // ====== CANALI LOCALI (sempre mostrati per primi, facilmente modificabili) ======
        val DEFAULT_CHANNELS: List<Channel> = listOf(
            Channel("TEST • HTSport Calcio HD", "https://htsport.info/calciohd.htm")
        )

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/110.0.0.0 Safari/537.36"

        private const val BG_COLOR = "#121212"
        private const val ACCENT_COLOR = "#00E5FF"
        private const val COLUMNS = 3

        // ---------- AdBlock ----------
        private val AD_DOMAINS = arrayOf(
            "doubleclick", "googlesyndication", "googleadservices", "googletagservices",
            "popads", "popcash", "adnxs", "exoclick", "onclickads", "onclkds", "onclicka",
            "propellerads", "adsterra", "juicyads", "trafficjunky", "clickadu", "hilltopads",
            "taboola", "outbrain", "histats", "scorecardresearch", "google-analytics",
            "googletagmanager", "adsco.re", "a-ads", "popunder", "adcash", "revcontent", "mgid"
        )

        private val AD_TOKENS = hashSetOf(
            "pop", "popup", "popups", "popunder", "banner", "banners", "analytics", "onclick",
            "adserver", "ads", "adv", "advert", "tracker", "tracking", "adsbygoogle", "prebid"
        )

        private val NON_ALNUM = Regex("[^a-z0-9]+")

        // ---------- Parser lista remota ----------
        private val RE_MD_LINK = Regex("""\((https?://[^)\s]+)\)""")
        private val RE_HTTP = Regex("""https?://[^\s\])]+""")
        private val RE_BARE = Regex("""(?:www\.)?[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?:/[^\s\])]*)?""")

        fun parseChannels(text: String): List<Channel> {
            val out = ArrayList<Channel>()
            for (raw in text.lines()) {
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.isEmpty() || line.startsWith("#")) continue
                val sep = line.indexOf('|')
                if (sep <= 0) continue
                val name = line.substring(0, sep).trim()
                val rest = line.substring(sep + 1).trim()
                val url = RE_MD_LINK.find(rest)?.groupValues?.get(1)
                    ?: RE_HTTP.find(rest)?.value
                    ?: RE_BARE.find(rest)?.value?.let { "https://$it" }
                    ?: continue
                out.add(Channel(if (name.isEmpty()) url else name, url))
            }
            return out
        }

        // ---------- JavaScript: overlay cleaner + fullscreen + autoplay ----------
        private val AUTOPLAY_JS = """
(function () {
  try {
    if (window.__ftvTimer) { clearInterval(window.__ftvTimer); window.__ftvTimer = null; }
    var START = Date.now();
    var AD_RE = /(doubleclick|googlesyndication|googleadservices|popads|popcash|adnxs|exoclick|onclick|propeller|adsterra|juicyads|trafficjunky|clickadu|taboola|outbrain|analytics|banner|adsbygoogle|(^|[^a-z0-9])(pop|popup|popunder|ads)(?![a-z0-9]))/i;
    var PLAYER_SEL = '.jwplayer,.video-js,.plyr,.flowplayer,.fp-player,.mejs__container,.vjs-control-bar';
    var clickedVideo = 0;
    var clickedFrame = false;

    try { window.open = function () { return null; }; } catch (e) {}

    if (!window.__ftvClickGuard) {
      window.__ftvClickGuard = true;
      document.addEventListener('click', function (e) {
        try {
          var a = (e.target && e.target.closest) ? e.target.closest('a') : null;
          if (a && (a.target === '_blank' || AD_RE.test(a.href || ''))) {
            e.preventDefault();
            e.stopPropagation();
          }
        } catch (err) {}
      }, true);
    }

    function area(el) {
      var r = el.getBoundingClientRect();
      return r.width * r.height;
    }

    function hasMedia(el) {
      if (el.tagName === 'VIDEO' || el.tagName === 'IFRAME') return true;
      return !!(el.querySelector && el.querySelector('video,iframe'));
    }

    function isPlayerPart(el) {
      return !!(el.closest && el.closest(PLAYER_SEL));
    }

    function findPlayer() {
      var best = null, bestArea = 0, i, a;
      var vids = document.getElementsByTagName('video');
      for (i = 0; i < vids.length; i++) {
        a = area(vids[i]);
        if (a > bestArea) { bestArea = a; best = vids[i]; }
      }
      var frames = document.getElementsByTagName('iframe');
      for (i = 0; i < frames.length; i++) {
        var f = frames[i];
        if (AD_RE.test(f.src || '')) continue;
        var r = f.getBoundingClientRect();
        if (r.width < 200 || r.height < 100) continue;
        a = r.width * r.height;
        if (a > bestArea) { bestArea = a; best = f; }
      }
      return bestArea > 20000 ? best : null;
    }

    function cleanOverlays(player) {
      if (!document.body) return;
      var all = document.body.querySelectorAll('*');
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        try {
          if (!el.parentNode) continue;
          if (player && (el === player || el.contains(player) || player.contains(el))) continue;
          var tag = el.tagName;
          if (tag === 'SCRIPT') {
            var s = el.src || '';
            if (s && AD_RE.test(s)) { el.parentNode.removeChild(el); }
            continue;
          }
          if (tag === 'IFRAME') {
            if (AD_RE.test(el.src || '')) { el.parentNode.removeChild(el); }
            continue;
          }
          if (tag === 'VIDEO' || tag === 'STYLE' || tag === 'LINK' || tag === 'HEAD') continue;
          if (hasMedia(el) || isPlayerPart(el)) continue;
          var cs = window.getComputedStyle(el);
          var z = parseInt(cs.zIndex, 10);
          if ((cs.position === 'fixed' || cs.position === 'absolute') && !isNaN(z) && z >= 1000) {
            el.parentNode.removeChild(el);
          }
        } catch (err) {}
      }
    }

    function applyFullscreen(el) {
      var st = {
        'position': 'fixed', 'top': '0', 'left': '0',
        'width': '100vw', 'height': '100vh',
        'z-index': '2147483647', 'background': '#000',
        'max-width': 'none', 'max-height': 'none',
        'margin': '0', 'padding': '0', 'border': '0', 'transform': 'none'
      };
      for (var k in st) { el.style.setProperty(k, st[k], 'important'); }
      if (el.tagName === 'VIDEO') { el.style.setProperty('object-fit', 'contain', 'important'); }
      var p = el.parentElement;
      while (p && p !== document.documentElement) {
        p.style.setProperty('transform', 'none', 'important');
        p.style.setProperty('filter', 'none', 'important');
        p.style.setProperty('perspective', 'none', 'important');
        p.style.setProperty('contain', 'none', 'important');
        p.style.setProperty('will-change', 'auto', 'important');
        p.style.setProperty('overflow', 'visible', 'important');
        p.style.setProperty('opacity', '1', 'important');
        p.style.setProperty('z-index', 'auto', 'important');
        p = p.parentElement;
      }
    }

    function lockScroll() {
      var h = document.documentElement, b = document.body;
      if (h) { h.style.setProperty('overflow', 'hidden', 'important'); }
      if (b) {
        b.style.setProperty('overflow', 'hidden', 'important');
        b.style.setProperty('margin', '0', 'important');
        b.style.setProperty('background', '#000', 'important');
      }
      try { window.scrollTo(0, 0); } catch (e) {}
    }

    function fireClick(el) {
      var types = ['mousedown', 'mouseup', 'click'];
      for (var i = 0; i < types.length; i++) {
        try {
          el.dispatchEvent(new MouseEvent(types[i], { bubbles: true, cancelable: true, view: window }));
        } catch (e) {}
      }
    }

    function tryPlay(player) {
      if (!player) return;
      if (player.tagName === 'VIDEO') {
        if (player.paused) {
          try {
            var pr = player.play();
            if (pr && pr.catch) { pr.catch(function () {}); }
          } catch (e) {}
          if (player.paused && clickedVideo < 3) {
            clickedVideo++;
            var btn = document.querySelector('.vjs-big-play-button,.jw-icon-display,.jw-display-icon-container,.plyr__control--overlaid,.play-button,#play,.ytp-large-play-button');
            if (btn) { try { btn.click(); } catch (e) {} }
            fireClick(player);
          }
        }
      } else if (!clickedFrame) {
        clickedFrame = true;
        fireClick(player);
      }
    }

    function tick() {
      try {
        var player = findPlayer();
        cleanOverlays(player);
        if (player) { applyFullscreen(player); }
        lockScroll();
        tryPlay(player);
      } catch (e) {}
      if (Date.now() - START > 10000) {
        clearInterval(window.__ftvTimer);
        window.__ftvTimer = null;
      }
    }

    tick();
    window.__ftvTimer = setInterval(tick, 800);
  } catch (e) {}
})();
"""

        // ---------- JavaScript: play/pausa del <video> ----------
        private val TOGGLE_JS = """
(function () {
  function collect(doc, out) {
    try {
      var v = doc.getElementsByTagName('video');
      for (var i = 0; i < v.length; i++) { out.push(v[i]); }
      var f = doc.getElementsByTagName('iframe');
      for (var j = 0; j < f.length; j++) {
        try { if (f[j].contentDocument) { collect(f[j].contentDocument, out); } } catch (e) {}
      }
    } catch (e) {}
    return out;
  }
  var list = collect(document, []);
  if (!list.length) return 'none';
  var best = list[0], bestArea = 0;
  for (var i = 0; i < list.length; i++) {
    var r = list[i].getBoundingClientRect();
    if (r.width * r.height > bestArea) { bestArea = r.width * r.height; best = list[i]; }
  }
  if (best.paused) {
    try { var p = best.play(); if (p && p.catch) { p.catch(function () {}); } } catch (e) {}
    return 'played';
  }
  best.pause();
  return 'paused';
})();
"""
    }

    // ------------------------------------------------------------------ UI state
    private lateinit var root: FrameLayout
    private lateinit var menuLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var webView: WebView

    private var channels: List<Channel> = DEFAULT_CHANNELS
    private val cards = ArrayList<View>()
    private var lastFocusIndex = 0
    private var loadingList = false

    // Domini ("brand") consentiti per la navigazione principale: canale + player incorporato
    private val allowedBrands: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schermo sempre attivo per streaming TV
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor(BG_COLOR)) }
        buildMenu()
        buildWebView()
        setContentView(root)

        rebuildCards()
        loadRemoteChannels()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            root.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------ menu
    private fun buildMenu() {
        menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), dp(24))
        }

        val title = TextView(this).apply {
            text = "FIRE TV CANALI"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 15f
            setPadding(0, dp(4), 0, dp(16))
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)

        menuLayout.addView(title)
        menuLayout.addView(statusText)
        menuLayout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(
            menuLayout,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun cellParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(96), 1f).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }

    private fun rebuildCards() {
        listContainer.removeAllViews()
        cards.clear()
        var i = 0
        while (i < channels.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until COLUMNS) {
                val idx = i + c
                if (idx < channels.size) {
                    val card = createCard(channels[idx], idx)
                    cards.add(card)
                    row.addView(card, cellParams())
                } else {
                    row.addView(View(this), cellParams())
                }
            }
            listContainer.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            i += COLUMNS
        }
        if (menuLayout.visibility == View.VISIBLE) focusCard(lastFocusIndex)
    }

    private fun createCard(ch: Channel, index: Int): TextView = TextView(this).apply {
        text = ch.name
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = cardBackground(false)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { v, hasFocus ->
            v.background = cardBackground(hasFocus)
            if (hasFocus) lastFocusIndex = index
        }
        setOnClickListener { openChannel(ch) }
    }

    private fun cardBackground(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        if (focused) {
            setColor(Color.parseColor("#26323B"))
            setStroke(dp(5), Color.parseColor(ACCENT_COLOR))
        } else {
            setColor(Color.parseColor("#1E1E1E"))
            setStroke(dp(1), Color.parseColor("#2E2E2E"))
        }
    }

    private fun focusCard(index: Int) {
        (cards.getOrNull(index) ?: cards.firstOrNull())?.requestFocus()
    }

    // ------------------------------------------------------------------ lista remota
    private fun loadRemoteChannels() {
        if (loadingList) return
        loadingList = true
        statusText.text = "Aggiornamento lista canali…"
        Thread {
            val remote: List<Channel> = try {
                parseChannels(downloadText(REMOTE_LIST_URL))
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                loadingList = false
                if (isFinishing) return@runOnUiThread
                if (remote.isNotEmpty()) {
                    val merged = ArrayList<Channel>(DEFAULT_CHANNELS)
                    for (c in remote) {
                        if (merged.none { urlKey(it.url) == urlKey(c.url) }) merged.add(c)
                    }
                    channels = merged
                    statusText.text = "${channels.size} canali  •  MENU = aggiorna lista"
                    rebuildCards()
                } else {
                    statusText.text = "Lista remota non disponibile (uso elenco locale)  •  MENU = riprova"
                }
            }
        }.start()
    }

    private fun urlKey(u: String): String =
        u.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun downloadText(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ WebView
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        webView = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                userAgentString = DESKTOP_UA
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {

                // Blocca redirect verso domini estranei (API 24+)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val req = request ?: return false
                    return shouldBlockNavigation(req.url, req.isForMainFrame)
                }

                // Compatibilità API 22-23
                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    return shouldBlockNavigation(Uri.parse(url), true)
                }

                // AdBlock di rete
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val req = request ?: return null
                    if (!req.isForMainFrame) {
                        val url = req.url.toString()
                        if (isAd(url)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        // Un documento HTML in un frame = player incorporato: ne ricordo il dominio
                        val accept = req.requestHeaders?.get("Accept")
                        if (accept != null && accept.contains("text/html", ignoreCase = true)) {
                            val b = brandOf(req.url.host)
                            if (b.isNotEmpty()) allowedBrands.add(b)
                        }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null || url.startsWith("about:")) return
                    view?.evaluateJavascript(AUTOPLAY_JS, null)
                }
            }

            webChromeClient = object : WebChromeClient() {

                // Nessuna nuova finestra / scheda / popup
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean = false

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.cancel()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.cancel()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    result?.cancel()
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }

                override fun getDefaultVideoPoster(): Bitmap? =
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun hideCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ------------------------------------------------------------------ filtri
    /** @return true = blocca la navigazione */
    private fun shouldBlockNavigation(uri: Uri, isMainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "about") return false
        if (scheme != "http" && scheme != "https") return true // intent://, market://, ecc.
        if (!isMainFrame) return isAd(uri.toString())          // iframe/player: solo filtro ads
        return !allowedBrands.contains(brandOf(uri.host))
    }

    private fun isAd(url: String): Boolean {
        val lower = url.lowercase()
        val host = Uri.parse(lower).host ?: ""
        if (AD_DOMAINS.any { host.contains(it) }) return true
        return lower.split(NON_ALNUM).any { it in AD_TOKENS }
    }

    /** "www.htsport.org" -> "htsport" (confronto indipendente dal TLD). */
    private fun brandOf(host: String?): String {
        val labels = (host ?: return "").lowercase().split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return labels.firstOrNull() ?: ""
        val sld = labels[labels.size - 2]
        return if (labels.size >= 3 && sld in setOf("co", "com", "org", "net", "gov", "edu"))
            labels[labels.size - 3] else sld
    }

    // ------------------------------------------------------------------ apertura / chiusura canale
    private fun openChannel(ch: Channel) {
        allowedBrands.clear()
        val b = brandOf(Uri.parse(ch.url).host)
        if (b.isNotEmpty()) allowedBrands.add(b)

        menuLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.requestFocus()
        webView.loadUrl(ch.url)
    }

    private fun closePlayer() {
        hideCustomView()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.visibility = View.GONE
        allowedBrands.clear()
        menuLayout.visibility = View.VISIBLE
        focusCard(lastFocusIndex)
    }

    private fun handleBack() {
        when {
            customView != null -> hideCustomView()
            webView.visibility == View.VISIBLE -> closePlayer()
            else -> finish()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    // ------------------------------------------------------------------ telecomando
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val playerOpen = webView.visibility == View.VISIBLE

        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBack()
            return true
        }

        if (playerOpen && (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    code == KeyEvent.KEYCODE_MEDIA_PLAY ||
                    code == KeyEvent.KEYCODE_MEDIA_PAUSE)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleOrTap()
            return true
        }

        if (!playerOpen && code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) loadRemoteChannels()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    /** Play/Pausa sul <video>; se non c'è (player in iframe cross-origin) simula un tocco al centro. */
    private fun toggleOrTap() {
        webView.evaluateJavascript(TOGGLE_JS) { result ->
            if (result == null || result.contains("none")) tapCenter()
        }
    }

    private fun tapCenter() {
        val x = webView.width / 2f
        val y = webView.height / 2f
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(down)
        down.recycle()
        mainHandler.postDelayed({
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 70)
    }
}
