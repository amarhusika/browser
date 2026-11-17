package com.amar.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /** One browser tab = one WebView (+ a small screenshot for the tab switcher). */
    private class Tab(var web: WebView) {
        var thumb: Bitmap? = null
    }

    private class Visit(val url: String, var title: String, var time: Long)

    private lateinit var webs: FrameLayout
    private lateinit var url: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fullscreen: FrameLayout
    private lateinit var tabCount: TextView
    private lateinit var switcher: View
    private lateinit var grid: LinearLayout
    private lateinit var historyPanel: View
    private lateinit var historyList: LinearLayout

    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private val web: WebView get() = tabs[cur].web

    private val history = ArrayList<Visit>()   // oldest first
    private var historyDirty = false
    private val io = Executors.newSingleThreadExecutor()

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var backCallback: Any? = null
    private var backRegistered = false
    private var savedUiFlags = 0

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 10) autoUnmute(view)   // inject early, before FB mutes
            if (view !== web) return
            progress.progress = newProgress
            progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val u = view.url ?: return
            val last = history.lastOrNull() ?: return
            if (last.url == u && !title.isNullOrBlank() && title != u) {
                last.title = title
                historyDirty = true
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden(); return
            }
            customView = view
            customCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
            fullscreen.visibility = View.VISIBLE
            showSystemBars(false)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            updateBack()
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            fullscreen.removeView(v)
            fullscreen.visibility = View.GONE
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            showSystemBars(true)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            updateBack()
        }

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            return when (u.scheme?.lowercase()) {
                "http", "https", "about", "data", "blob", "javascript", "file", "content" -> false
                "intent" -> openIntentScheme(view, u.toString())
                else -> { openExternal(u); true }
            }
        }

        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
            if (view === web) showUrl(u)
        }

        override fun onPageFinished(view: WebView, u: String?) {
            autoUnmute(view)
            record(view)
            if (view !== web) return
            progress.visibility = View.INVISIBLE
            showUrl(view.url)
        }

        override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
            if (view !== web) return
            showUrl(u)
            updateBack()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Renderer was killed (e.g. low memory): rebuild that tab instead of crashing.
            replaceCrashed(view)
            return true
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webs = findViewById(R.id.webs)
        url = findViewById(R.id.url)
        progress = findViewById(R.id.progress)
        fullscreen = findViewById(R.id.fullscreen)
        tabCount = findViewById(R.id.tabCount)
        switcher = findViewById(R.id.switcher)
        grid = findViewById(R.id.grid)
        historyPanel = findViewById(R.id.historyPanel)
        historyList = findViewById(R.id.historyList)

        setupInsets()
        setupBar()
        if (Build.VERSION.SDK_INT >= 33) backCallback = OnBackInvokedCallback { handleBack() }
        loadHistory()

        val count = savedInstanceState?.getInt(K_COUNT) ?: 0
        for (i in 0 until count) {
            val t = Tab(newWebView())
            savedInstanceState?.getBundle(K_TAB + i)?.let { t.web.restoreState(it) }
            tabs.add(t)
        }
        if (tabs.isEmpty()) {
            addTab(null)                          // start with one completely blank tab
            handleIntent(intent)
        } else {
            showTab((savedInstanceState?.getInt(K_CUR) ?: 0).coerceIn(0, tabs.size - 1))
        }
        updateBack()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        // Stop JS timers, animations and media while the app is not visible.
        web.onPause()
        web.pauseTimers()
        CookieManager.getInstance().flush()
        saveHistory()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(K_COUNT, tabs.size)
        outState.putInt(K_CUR, cur)
        tabs.forEachIndexed { i, t ->
            val b = Bundle()
            t.web.saveState(b)
            outState.putBundle(K_TAB + i, b)
        }
    }

    override fun onDestroy() {
        tabs.forEach {
            it.web.stopLoading()
            it.web.destroy()
        }
        io.shutdown()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val clip = data?.clipData
            val result = if (resultCode == RESULT_OK && clip != null) {
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } else {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            }
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /** Links from other apps: use the current tab if it is blank, otherwise open a new tab. */
    private fun handleIntent(i: Intent?) {
        val target = when (i?.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.let { toUrl(it) }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { toUrl(it) }
            else -> null
        } ?: return
        hideHistory()
        hideSwitcher()
        if (isBlank(web)) load(target) else addTab(target)
    }

    // ---------- setup ----------

    private fun setupInsets() {
        // Android 11+: draw edge-to-edge and pad for status bar, nav bar and keyboard ourselves.
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val w = WebView(this)
        w.visibility = View.GONE
        w.setBackgroundColor(getColor(R.color.chrome))
        w.webViewClient = client
        w.webChromeClient = chrome
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false  // let videos play with sound when opened
            allowFileAccess = false
            allowContentAccess = true                 // open local .html files shared via content://
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)          // target=_blank opens in the same page
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        // Let the system lower renderer priority when the app is not visible.
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        w.setDownloadListener { u, ua, cd, mime, _ -> download(u, ua, cd, mime) }
        webs.addView(w, FrameLayout.LayoutParams(-1, -1))
        return w
    }

    private fun setupBar() {
        url.setOnEditorActionListener { _, id, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id == EditorInfo.IME_ACTION_GO || enter) {
                load(toUrl(url.text.toString())); true
            } else false
        }
        url.setOnFocusChangeListener { _, has ->
            if (has) url.post { url.selectAll() } else showUrl(web.url)
            updateBack()
        }
        findViewById<View>(R.id.home).setOnClickListener { load(HOME) }
        findViewById<View>(R.id.tabs).setOnClickListener { showSwitcher() }
        findViewById<View>(R.id.menu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.newTab).setOnClickListener { openNewTab() }
        findViewById<View>(R.id.switcherMenu).setOnClickListener { showSwitcherMenu(it) }
        findViewById<View>(R.id.historyClose).setOnClickListener { hideHistory() }
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.new_tab)
        m.menu.add(0, 2, 1, R.string.share)
        m.menu.add(0, 3, 2, R.string.history)
        m.menu.add(0, 4, 3, R.string.clear)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openNewTab()
                2 -> web.url?.takeIf { u -> u != HOME }?.let { u ->
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null))
                }
                3 -> showHistory()
                4 -> clearData()
            }
            true
        }
        m.show()
    }

    /** The bar always shows the real link (empty when blank). */
    private fun showUrl(u: String?) {
        if (url.hasFocus()) return
        url.setText(if (u == null || u == HOME) "" else u)
    }

    // ---------- tabs ----------

    private fun isBlank(w: WebView): Boolean = w.url.let { it == null || it == HOME }

    private fun addTab(target: String?) {
        captureThumb()
        tabs.add(Tab(newWebView()))
        showTab(tabs.size - 1)
        if (target != null) load(target)
    }

    /** Make tab [i] the visible one; all other tabs are hidden and paused (saves battery). */
    private fun showTab(i: Int) {
        cur = i
        tabs.forEachIndexed { j, t ->
            if (j == i) {
                t.web.visibility = View.VISIBLE
                t.web.onResume()
            } else {
                t.web.visibility = View.GONE
                t.web.onPause()
            }
        }
        val p = web.progress
        progress.progress = p
        progress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
        // Always replace the bar text on a tab change (even if the field still has focus),
        // so a closed tab's address never stays behind.
        url.setText(web.url.let { if (it == null || it == HOME) "" else it })
        tabCount.text = if (tabs.size > 99) ":D" else tabs.size.toString()
        updateBack()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        webs.removeView(t.web)
        t.web.stopLoading()
        t.web.destroy()
        if (tabs.isEmpty()) {
            cur = 0
            addTab(null)
            url.clearFocus()
            hideKeyboard()
            return
        }
        if (i < cur) cur--
        showTab(cur.coerceAtMost(tabs.size - 1))
    }

    private fun closeAllTabs() {
        tabs.forEach {
            webs.removeView(it.web)
            it.web.stopLoading()
            it.web.destroy()
        }
        tabs.clear()
        cur = 0
        addTab(null)
    }

    private fun openNewTab() {
        hideHistory()
        hideSwitcher()
        addTab(null)
        url.requestFocus()
        url.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(url, 0) }
    }

    private fun replaceCrashed(view: WebView) {
        val t = tabs.firstOrNull { it.web === view }
        val last = view.url
        webs.removeView(view)
        try { view.destroy() } catch (e: Exception) { }
        if (t == null) return
        t.web = newWebView()
        t.thumb = null
        if (last != null && last != HOME) t.web.loadUrl(last)
        showTab(cur)
    }

    /** Small screenshot of the current tab for its card in the tab switcher. */
    private fun captureThumb() {
        val t = tabs.getOrNull(cur) ?: return
        val w = t.web
        if (isBlank(w)) { t.thumb = null; return }
        if (w.width == 0 || w.height == 0) return
        try {
            val s = 0.35f
            val b = Bitmap.createBitmap((w.width * s).toInt(), (w.height * s).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(b)
            c.scale(s, s)
            c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            t.thumb = b
        } catch (e: Throwable) { }
    }

    private fun tabTitle(t: Tab): String {
        val u = t.web.url
        if (u == null || u == HOME) return getString(R.string.new_tab)
        return t.web.title?.takeIf { it.isNotBlank() && it != u } ?: Uri.parse(u).host ?: u
    }

    private fun showSwitcher() {
        captureThumb()
        url.clearFocus()
        hideKeyboard()
        buildGrid()
        switcher.visibility = View.VISIBLE
        updateBack()
    }

    private fun hideSwitcher() {
        if (switcher.visibility != View.VISIBLE) return
        switcher.visibility = View.GONE
        grid.removeAllViews()
        updateBack()
    }

    private fun showSwitcherMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.close_all)
        m.setOnMenuItemClickListener {
            closeAllTabs()
            hideSwitcher()
            true
        }
        m.show()
    }

    /** Two cards per row, like Chrome. */
    private fun buildGrid() {
        grid.removeAllViews()
        var row: LinearLayout? = null
        tabs.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            row?.addView(tabCard(i, t), cardParams())
        }
        if (tabs.size % 2 == 1) row?.addView(Space(this), cardParams())
    }

    private fun cardParams() = LinearLayout.LayoutParams(0, dp(230), 1f).apply {
        setMargins(dp(6), dp(6), dp(6), dp(6))
    }

    private fun tabCard(i: Int, t: Tab): View {
        val sel = i == cur
        val fg = getColor(if (sel) R.color.on_selected else R.color.text)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (sel) R.drawable.card_selected else R.drawable.card)
            setPadding(dp(3), 0, dp(3), dp(3))
            clipToOutline = true
            setOnClickListener {
                showTab(i)
                hideSwitcher()
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        head.addView(TextView(this).apply {
            text = tabTitle(t)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            textSize = 14f
            setTextColor(fg)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            ripple(this)
            if (sel) imageTintList = ColorStateList.valueOf(fg)
            contentDescription = getString(R.string.close)
            setOnClickListener {
                val wasLast = tabs.size == 1
                closeTab(i)
                if (wasLast) hideSwitcher()           // last tab closed: straight to a blank home tab
                else if (switcher.visibility == View.VISIBLE) buildGrid()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(head, LinearLayout.LayoutParams(-1, dp(40)))

        val img = ImageView(this).apply {
            setBackgroundResource(R.drawable.thumb)
            clipToOutline = true
        }
        t.thumb?.let { b ->
            img.setImageBitmap(b)
            img.scaleType = ImageView.ScaleType.MATRIX   // fit width, show the top of the page
            img.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val s = v.width.toFloat() / b.width
                (v as ImageView).imageMatrix = Matrix().apply { setScale(s, s) }
            }
        }
        card.addView(img, LinearLayout.LayoutParams(-1, 0, 1f))
        return card
    }

    // ---------- history ----------

    private val historyFile get() = File(filesDir, "history.json")

    private fun loadHistory() {
        try {
            if (!historyFile.exists()) return
            val a = JSONArray(historyFile.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                history.add(Visit(o.getString("u"), o.optString("t"), o.optLong("d")))
            }
        } catch (e: Exception) { }
    }

    private fun saveHistory() {
        if (!historyDirty) return
        historyDirty = false
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
        val s = a.toString()
        val f = historyFile
        io.execute { try { f.writeText(s) } catch (e: Exception) { } }
    }

    private fun record(view: WebView) {
        val u = view.url ?: return
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        val title = view.title?.takeIf { it.isNotBlank() && it != u } ?: ""
        val now = System.currentTimeMillis()
        val last = history.lastOrNull()
        if (last != null && last.url == u) {
            if (title.isNotEmpty()) last.title = title
            last.time = now
        } else {
            history.add(Visit(u, title, now))
            if (history.size > MAX_HISTORY) history.subList(0, history.size - MAX_HISTORY).clear()
        }
        historyDirty = true
    }

    private fun showHistory() {
        url.clearFocus()
        hideKeyboard()
        buildHistory()
        historyPanel.visibility = View.VISIBLE
        updateBack()
    }

    private fun hideHistory() {
        if (historyPanel.visibility != View.VISIBLE) return
        historyPanel.visibility = View.GONE
        historyList.removeAllViews()
        updateBack()
    }

    private fun buildHistory() {
        historyList.removeAllViews()
        if (history.isEmpty()) {
            historyList.addView(TextView(this).apply {
                setText(R.string.no_history)
                setTextColor(getColor(R.color.hint))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        val timeFmt = android.text.format.DateFormat.getTimeFormat(this)
        var lastDay = ""
        for (k in history.indices.reversed()) {
            val v = history[k]
            val day = dayLabel(v.time)
            if (day != lastDay) {
                lastDay = day
                historyList.addView(TextView(this).apply {
                    text = day
                    setTextColor(getColor(R.color.accent))
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(16), dp(14), dp(16), dp(6))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                ripple(this, borderless = false)
                setOnClickListener {
                    hideHistory()
                    load(v.url)
                }
                setOnLongClickListener {             // long press removes the entry
                    history.remove(v)
                    historyDirty = true
                    buildHistory()
                    true
                }
            }
            item.addView(TextView(this).apply {
                text = v.title.ifBlank { v.url }
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                textSize = 15f
                setTextColor(getColor(R.color.text))
            })
            item.addView(TextView(this).apply {
                text = timeFmt.format(Date(v.time)) + "  ·  " + (Uri.parse(v.url).host ?: v.url)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                textSize = 12f
                setTextColor(getColor(R.color.hint))
            })
            historyList.addView(item, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun dayLabel(t: Long): String {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val today = c.timeInMillis
        return when {
            t >= today -> getString(R.string.today)
            t >= today - DAY_MS -> getString(R.string.yesterday)
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(Date(t))
        }
    }

    // ---------- back ----------

    private fun needsBack(): Boolean =
        customView != null || historyPanel.visibility == View.VISIBLE ||
                switcher.visibility == View.VISIBLE || url.hasFocus() || web.canGoBack()

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as? OnBackInvokedCallback ?: return
        val need = needsBack()
        if (need && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backRegistered = true
        } else if (!need && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backRegistered = false
        }
    }

    private fun handleBack() {
        when {
            customView != null -> chrome.onHideCustomView()
            historyPanel.visibility == View.VISIBLE -> hideHistory()
            switcher.visibility == View.VISIBLE -> hideSwitcher()
            url.hasFocus() -> { url.clearFocus(); hideKeyboard() }
            web.canGoBack() -> web.goBack()
        }
        updateBack()
    }

    @Deprecated("Used only below Android 13")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (needsBack()) handleBack() else super.onBackPressed()
    }

    // ---------- actions ----------

    private fun load(target: String) {
        url.clearFocus()
        hideKeyboard()
        web.loadUrl(target)
        web.requestFocus()
    }

    private fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return HOME
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("data:")) return s
        if (!s.contains(' ') && (Patterns.WEB_URL.matcher(s).matches() || lower.startsWith("localhost"))) {
            return "https://$s"
        }
        return SEARCH + Uri.encode(s)
    }

    /** Clear: wipe cookies, cache, storage, history and all tabs, back to one blank tab. */
    private fun clearData() {
        web.clearCache(true)
        web.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        history.clear()
        historyDirty = true
        saveHistory()
        hideHistory()
        closeAllTabs()
        toast(getString(R.string.cleared))
    }

    private fun download(u: String, ua: String?, cd: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(u, cd, mime)
            val r = DownloadManager.Request(Uri.parse(u))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            mime?.let { r.setMimeType(it) }
            ua?.let { r.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(u)?.let { r.addRequestHeader("Cookie", it) }
            if (Build.VERSION.SDK_INT >= 29) {
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            }
            getSystemService(DownloadManager::class.java).enqueue(r)
            toast(getString(R.string.downloading, name))
        } catch (e: Exception) {
            toast(getString(R.string.cant_download))
        }
    }

    private fun openIntentScheme(view: WebView, s: String): Boolean {
        val i = try { Intent.parseUri(s, Intent.URI_INTENT_SCHEME) } catch (e: Exception) { return true }
        i.addCategory(Intent.CATEGORY_BROWSABLE)
        i.component = null
        i.selector = null
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            i.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
        }
        return true
    }

    private fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ripple(v: View, borderless: Boolean = true) {
        val tv = TypedValue()
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless
                   else android.R.attr.selectableItemBackground
        theme.resolveAttribute(attr, tv, true)
        v.setBackgroundResource(tv.resourceId)
    }

    /** Facebook/Instagram: play videos with sound and hide "Tap to unmute". */
    private fun autoUnmute(w: WebView) {
        val host = Uri.parse(w.url ?: return).host ?: return
        if (!UNMUTE_HOSTS.any { host == it || host.endsWith(".$it") }) return
        w.evaluateJavascript(UNMUTE_JS, null)
    }

    private fun showSystemBars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (show) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            legacySystemBars(show)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacySystemBars(show: Boolean) {
        val d = window.decorView
        if (show) {
            d.systemUiVisibility = savedUiFlags
        } else {
            savedUiFlags = d.systemUiVisibility
            d.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(url.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val HOME = "about:blank"
        private const val SEARCH = "https://www.google.com/search?q="
        private const val REQ_FILE = 1
        private const val K_COUNT = "tabCount"
        private const val K_CUR = "currentTab"
        private const val K_TAB = "tab"
        private const val MAX_HISTORY = 500
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private val UNMUTE_HOSTS = listOf("facebook.com", "fb.com", "fb.watch", "instagram.com")

        /**
         * Facebook/Instagram: videos play with sound and the "Tap to unmute" label is hidden.
         * FB's attempts to mute are intercepted: FB still *thinks* the video is muted (so it
         * doesn't fight back or restart it), but the real audio stays on. Nothing is clicked.
         */
        private val UNMUTE_JS = """
            (function(){
              if (window.__amarSound) return; window.__amarSound = 1;
              var P = HTMLMediaElement.prototype;
              var m = Object.getOwnPropertyDescriptor(P, 'muted');
              var vol = Object.getOwnPropertyDescriptor(P, 'volume');
              if (!m || !m.set) return;
              Object.defineProperty(P, 'muted', {
                configurable: true, enumerable: m.enumerable,
                get: function(){ return this.__fakeMuted === undefined ? m.get.call(this) : this.__fakeMuted; },
                set: function(v){ this.__fakeMuted = !!v; m.set.call(this, false); }
              });
              // every "Tap to unmute" text on the page (any element, any spacing)
              var findLabels = function(){
                var out = [];
                var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
                var n;
                while ((n = w.nextNode())) {
                  var t = (n.nodeValue || '').replace(/\s+/g, ' ').trim();
                  if (/^tap to unmute$/i.test(t) && n.parentElement) out.push(n.parentElement);
                }
                return out;
              };
              // press FB's own label once (FB then switches itself to "unmuted": the label goes
              // away and taps show the normal controls again); hide anything still left
              var fixLabels = function(){
                findLabels().forEach(function(el){
                  var btn = el.closest('[role="button"]') || el;
                  if (!btn.__amarPressed) {
                    btn.__amarPressed = 1;
                    try { btn.click(); } catch (e) {}
                  }
                  setTimeout(function(){
                    if (!el.isConnected) return;
                    var box = el;
                    for (var k = 0; k < 3; k++) {
                      var p = box.parentElement;
                      if (!p || p.querySelector('video') ||
                          (p.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase() !== 'tap to unmute') break;
                      box = p;
                    }
                    box.style.setProperty('display', 'none', 'important');
                  }, 400);
                });
              };
              var sound = function(v){
                m.set.call(v, false);
                if (vol && vol.get.call(v) === 0) vol.set.call(v, 1);
                [300, 900, 2000, 3500].forEach(function(ms){ setTimeout(fixLabels, ms); });
              };
              document.addEventListener('play', function(e){
                if (e.target && e.target.tagName === 'VIDEO') sound(e.target);
              }, true);
              document.querySelectorAll('video').forEach(function(v){ if (!v.paused) sound(v); });
            })();
        """.trimIndent()
    }
}
