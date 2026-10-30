package com.vyron.ide

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.ConsoleMessage
import android.widget.EditText
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Android host for the VYRON editor. The editor remains a local asset; Android owns
 * workspace persistence, SAF import/export and the interactive PTY terminal.
 */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var terminalSheet: LinearLayout
    private lateinit var terminalView: TerminalView
    private lateinit var terminalTitle: TextView
    private lateinit var sheetParams: FrameLayout.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var fileChooserCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var terminalSession: TerminalSession? = null
    private val pendingTerminalInput = StringBuilder()
    private var workspaceKey = "default"
    private lateinit var workspaceDir: File
    private var pendingPicker = PickerMode.NONE
    private var importBytes = 0L
    private var sheetMaximized = false
    private var ctrlLatched = false

    private enum class PickerMode { NONE, IMPORT_FILES, IMPORT_FOLDER, EXPORT_FOLDER }

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(8, 13, 24)
        window.navigationBarColor = Color.rgb(8, 13, 24)
        window.decorView.systemUiVisibility = 0

        workspaceDir = File(filesDir, "vyron-workspaces/default")
        workspaceDir.mkdirs()
        createShellLayout()
        configureWebView()
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    private fun createShellLayout() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(30, 30, 30))
            clipChildren = false
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(30, 30, 30))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        terminalSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 17, 24))
            elevation = dp(18).toFloat()
            visibility = View.GONE
            clipToPadding = false
        }
        terminalSheet.addView(createTerminalHeader(), LinearLayout.LayoutParams(-1, dp(42)))
        terminalSheet.addView(createTerminalExtraKeys(), LinearLayout.LayoutParams(-1, dp(42)))

        terminalView = TerminalView(this, null).apply {
            setBackgroundColor(Color.rgb(13, 17, 24))
            setTextSize(14)
            setTerminalViewClient(createTerminalViewClient())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        terminalSheet.addView(terminalView, LinearLayout.LayoutParams(-1, 0, 1f))
        terminalView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right > left && bottom > top) {
                terminalView.updateSize()
                drainPendingTerminalInput()
            }
        }

        sheetParams = FrameLayout.LayoutParams(-1, dp(320), Gravity.BOTTOM)
        root.addView(terminalSheet, sheetParams)
        setContentView(root)
    }

    private fun createTerminalHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(25, 31, 42))
        }
        terminalTitle = TextView(this).apply {
            text = "VYRON SHELL  /  ANDROID"
            textSize = 11f
            letterSpacing = .08f
            setTextColor(Color.rgb(190, 205, 225))
        }
        bar.addView(terminalTitle, LinearLayout.LayoutParams(0, -1, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        bar.addView(keyButton("□") { toggleTerminalMaximize() }, LinearLayout.LayoutParams(dp(42), -1))
        bar.addView(keyButton("×") { hideTerminal() }, LinearLayout.LayoutParams(dp(42), -1))
        return bar
    }

    private fun createTerminalExtraKeys(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            setBackgroundColor(Color.rgb(19, 24, 33))
        }
        val keys = listOf(
            "CTRL" to { ctrlLatched = !ctrlLatched; terminalTitle.text = if (ctrlLatched) "VYRON SHELL  /  CTRL READY" else "VYRON SHELL  /  ANDROID" },
            "ESC" to { writeTerminal("\u001b") },
            "TAB" to { writeTerminal("\t") },
            "↑" to { writeTerminal("\u001b[A") },
            "↓" to { writeTerminal("\u001b[B") },
            "←" to { writeTerminal("\u001b[D") },
            "→" to { writeTerminal("\u001b[C") },
            "^C" to { writeTerminal("\u0003") },
            "REFRESH" to { refreshWorkspace() }
        )
        keys.forEach { (label, action) ->
            row.addView(keyButton(label, action), LinearLayout.LayoutParams(0, -1, if (label == "REFRESH") 1.6f else 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
        return row
    }

    private fun keyButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = if (label.length > 4) 9f else 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(3), 0, dp(3), 0)
            setTextColor(Color.rgb(210, 222, 238))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(39, 49, 65))
                cornerRadius = dp(5).toFloat()
                setStroke(dp(1), Color.rgb(57, 70, 91))
            }
            setOnClickListener { action() }
        }
    }

    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setPositiveButton("Continue") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                val field = EditText(this@MainActivity).apply {
                    setText(defaultValue)
                    setSelection(text.length)
                    textSize = 16f
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(message)
                    .setView(field)
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setPositiveButton("OK") { _, _ -> result.confirm(field.text.toString()) }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                return try {
                    startActivityForResult(intent, REQUEST_WEB_FILE_CHOOSER)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileChooserCallback = null
                    false
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                    ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                    else -> Log.DEBUG
                }
                Log.println(level, "VYRON-Web", "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep VYRON self-contained. External links open in the user's browser.
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                return true
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                "NativeHost",
                setOf("https://appassets.androidplatform.net"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        // The sandboxed HTML preview is not allowed to access Android APIs.
                        if (!isMainFrame || sourceOrigin.host != "appassets.androidplatform.net") return
                        handleNativeMessage(message.data ?: "{}")
                    }
                }
            )
        } else {
            Log.e(TAG, "WebMessageListener is unavailable; native bridge disabled on this WebView build")
            Toast.makeText(this, "Update Android System WebView to enable the native terminal and folder tools.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleNativeMessage(raw: String) {
        try {
            val message = JSONObject(raw)
            val method = message.optString("method")
            val args = message.optJSONObject("args") ?: JSONObject()
            when (method) {
                "terminal.show" -> showTerminal()
                "terminal.hide" -> hideTerminal()
                "terminal.stop" -> stopTerminal()
                "terminal.font" -> terminalView.setTextSize(args.optInt("size", 14).coerceIn(10, 22))
                "workspace.open" -> {
                    setWorkspace(args.optString("key", "default"))
                    ioExecutor.execute { sendWorkspace("workspace.loaded", hasFiles = workspaceHasFiles()) }
                }
                "workspace.save" -> {
                    val snapshot = (args.optJSONObject("files") ?: JSONObject()).toString()
                    ioExecutor.execute { saveWorkspace(JSONObject(snapshot)) }
                }
                "workspace.delete" -> deleteWorkspacePath(args.optString("path"))
                "workspace.mkdir" -> makeWorkspaceDir(args.optString("path"))
                "workspace.refresh" -> ioExecutor.execute { sendWorkspace("workspace.refreshed", hasFiles = true) }
                "workspace.importFiles" -> pickFiles()
                "workspace.importFolder" -> pickFolder()
                "workspace.export" -> exportFolder()
                "terminal.runFile" -> runFile(args.optString("path"))
                "terminal.refresh" -> refreshWorkspace()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bad native message", e)
            postEvent(JSONObject().put("type", "native.error").put("message", e.message ?: "Native request failed"))
        }
    }

    private fun setWorkspace(key: String) {
        workspaceKey = key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "_").take(48).ifBlank { "default" }
        workspaceDir = File(filesDir, "vyron-workspaces/$workspaceKey")
        workspaceDir.mkdirs()
        terminalSession?.let { if (it.getPid() > 0) it.finishIfRunning() }
        terminalSession = null
        pendingTerminalInput.clear()
    }

    private fun workspaceHasFiles(): Boolean = workspaceDir.walkTopDown().any { it != workspaceDir }

    private fun safeWorkspaceFile(relativePath: String): File? {
        val clean = relativePath.replace('\\', '/').trim('/')
        if (clean.isBlank() || clean.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        if (clean.contains(':') || clean.contains('\u0000')) return null
        val rootCanonical = workspaceDir.canonicalFile
        val target = File(rootCanonical, clean).canonicalFile
        if (target.path != rootCanonical.path && !target.path.startsWith(rootCanonical.path + File.separator)) return null
        return target
    }

    private fun saveWorkspace(files: JSONObject) {
        try {
            val keys = files.keys()
            var written = 0
            while (keys.hasNext()) {
                val path = keys.next()
                val value = files.opt(path)
                val target = safeWorkspaceFile(path) ?: continue
                if (path.endsWith("/")) {
                    target.mkdirs()
                    continue
                }
                if (value !is String) continue
                if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_FILE_BYTES) continue
                target.parentFile?.mkdirs()
                target.writeText(value, StandardCharsets.UTF_8)
                written++
            }
            Log.d(TAG, "Saved $written workspace files to ${workspaceDir.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Workspace save failed", e)
            postEvent(JSONObject().put("type", "native.error").put("message", "Workspace save failed: ${e.message}"))
        }
    }

    private fun deleteWorkspacePath(path: String) {
        val target = safeWorkspaceFile(path) ?: return
        if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    private fun makeWorkspaceDir(path: String) {
        val target = safeWorkspaceFile(path) ?: return
        target.mkdirs()
    }

    private fun readWorkspaceFiles(): JSONObject {
        val result = JSONObject()
        var totalBytes = 0L
        if (!workspaceDir.exists()) return result
        workspaceDir.walkTopDown().forEach { file ->
            if (file == workspaceDir) return@forEach
            val relative = file.relativeTo(workspaceDir).invariantSeparatorsPath
            if (file.isDirectory) {
                result.put("$relative/", "")
            } else if (file.length() <= MAX_FILE_BYTES && totalBytes < MAX_WORKSPACE_BYTES) {
                try {
                    val bytes = file.readBytes()
                    if (bytes.any { it == 0.toByte() }) return@forEach
                    val decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                    totalBytes += bytes.size
                    if (totalBytes <= MAX_WORKSPACE_BYTES) result.put(relative, text)
                } catch (_: Exception) {
                    // Non-text assets are intentionally not loaded into the source editor.
                }
            }
        }
        return result
    }

    private fun sendWorkspace(type: String, hasFiles: Boolean, last: String? = null) {
        val event = JSONObject()
            .put("type", type)
            .put("hasFiles", hasFiles)
            .put("files", readWorkspaceFiles())
        if (!last.isNullOrBlank()) event.put("last", last)
        postEvent(event)
    }

    private fun postEvent(event: JSONObject) {
        if (!::webView.isInitialized) return
        val json = event.toString()
        mainHandler.post {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('vyron-native',{detail:$json}));",
                    null
                )
            }
        }
    }

    private fun showTerminal() {
        if (terminalSheet.visibility == View.VISIBLE) return
        sheetMaximized = false
        val screenHeight = resources.displayMetrics.heightPixels
        val desired = (screenHeight * 0.43f).toInt().coerceIn(dp(260), dp(520))
        sheetParams.height = desired
        terminalSheet.layoutParams = sheetParams
        terminalSheet.visibility = View.VISIBLE
        ensureTerminalSession()
        webView.evaluateJavascript("document.body.classList.add('native-shell');", null)
        terminalView.post {
            terminalView.requestFocus()
            terminalView.updateSize()
            drainPendingTerminalInput()
        }
    }

    private fun hideTerminal() {
        terminalSheet.visibility = View.GONE
        sheetMaximized = false
        webView.evaluateJavascript("document.body.classList.remove('native-shell');", null)
        webView.requestFocus()
    }

    private fun toggleTerminalMaximize() {
        sheetMaximized = !sheetMaximized
        sheetParams.height = if (sheetMaximized) ViewGroup.LayoutParams.MATCH_PARENT else
            (resources.displayMetrics.heightPixels * 0.43f).toInt().coerceIn(dp(260), dp(520))
        terminalSheet.layoutParams = sheetParams
        if (sheetMaximized) terminalView.requestFocus()
    }

    private fun ensureTerminalSession() {
        val current = terminalSession
        if (current != null && current.getPid() != -1) {
            terminalView.attachSession(current)
            return
        }
        val home = File(filesDir, "vyron-shell-home/$workspaceKey").apply { mkdirs() }
        val env = arrayOf(
            "HOME=${home.absolutePath}",
            "PWD=${workspaceDir.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "SHELL=/system/bin/sh",
            "PATH=/system/bin:/system/xbin:/vendor/bin"
        )
        val client = createTerminalSessionClient()
        val session = TerminalSession(
            "/system/bin/sh",
            workspaceDir.absolutePath,
            arrayOf("/system/bin/sh", "-i"),
            env,
            1800,
            client
        )
        terminalSession = session
        terminalView.attachSession(session)
    }

    private fun createTerminalSessionClient(): TerminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { terminalView.invalidate() }
        override fun onTitleChanged(changedSession: TerminalSession) {
            mainHandler.post { terminalTitle.text = "VYRON SHELL  /  ${changedSession.getTitle() ?: "ANDROID"}" }
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            mainHandler.post { terminalTitle.text = "VYRON SHELL  /  EXIT ${finishedSession.getExitStatus()}" }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VYRON terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            session.write(bytes, 0, bytes.size)
        }
        override fun onBell(session: TerminalSession) { terminalView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
        override fun onColorsChanged(session: TerminalSession) { terminalView.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) { terminalView.invalidate() }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal error", e) }
    }

    private fun createTerminalViewClient(): TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {
            terminalView.requestFocus()
            val input = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            input.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) { }
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrlLatched
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            if (ctrlLatched) {
                ctrlLatched = false
                terminalTitle.text = "VYRON SHELL  /  ANDROID"
            }
            return false
        }
        override fun onEmulatorSet() { }
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal view error", e) }
    }

    private fun writeTerminal(text: String) {
        var session = terminalSession
        if (session == null || session.getPid() == -1) {
            ensureTerminalSession()
            session = terminalSession
        }
        if (session == null) return
        if (session.getPid() <= 0) {
            pendingTerminalInput.append(text)
            terminalView.post { terminalView.updateSize(); drainPendingTerminalInput() }
        } else {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            session.write(bytes, 0, bytes.size)
        }
        if (ctrlLatched) {
            ctrlLatched = false
            terminalTitle.text = "VYRON SHELL  /  ANDROID"
        }
    }

    private fun drainPendingTerminalInput() {
        val session = terminalSession ?: return
        if (session.getPid() <= 0 || pendingTerminalInput.isEmpty()) return
        val bytes = pendingTerminalInput.toString().toByteArray(StandardCharsets.UTF_8)
        pendingTerminalInput.clear()
        session.write(bytes, 0, bytes.size)
    }

    private fun stopTerminal() {
        terminalSession?.let { if (it.getPid() > 0) it.finishIfRunning() }
        terminalSession = null
        pendingTerminalInput.clear()
        terminalTitle.text = "VYRON SHELL  /  STOPPED"
    }

    private fun runFile(path: String) {
        val target = safeWorkspaceFile(path) ?: return
        if (!target.isFile) return
        val quoted = "'" + target.absolutePath.replace("'", "'\\''") + "'"
        val command = when (target.extension.lowercase(Locale.ROOT)) {
            "sh" -> "sh $quoted"
            "py" -> "python3 $quoted"
            "js", "mjs", "cjs" -> "node $quoted"
            "rb" -> "ruby $quoted"
            "php" -> "php $quoted"
            "lua" -> "lua $quoted"
            "c" -> "clang $quoted -o ${quoted}.vyron-bin && ${quoted}.vyron-bin"
            "cc" -> "clang $quoted -o ${quoted}.vyron-bin && ${quoted}.vyron-bin"
            "cpp", "cxx" -> "clang++ $quoted -o ${quoted}.vyron-bin && ${quoted}.vyron-bin"
            "rs" -> "rustc $quoted -o ${quoted}.vyron-bin && ${quoted}.vyron-bin"
            "go" -> "go run $quoted"
            else -> null
        }
        if (command == null) {
            writeTerminal("echo 'No launch profile for .${target.extension.ifBlank { "txt" }}; configure a runtime in VYRON settings.'\n")
            return
        }
        showTerminal()
        writeTerminal("$command\n")
    }

    private fun refreshWorkspace() {
        sendWorkspace("workspace.refreshed", hasFiles = true)
        postEvent(JSONObject().put("type", "terminal.notice").put("message", "Workspace refreshed from the native file system."))
    }

    private fun pickFiles() {
        pendingPicker = PickerMode.IMPORT_FILES
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_FILES)
    }

    private fun pickFolder() {
        pendingPicker = PickerMode.IMPORT_FOLDER
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_TREE)
    }

    private fun exportFolder() {
        pendingPicker = PickerMode.EXPORT_FOLDER
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_TREE)
    }

    @Deprecated("Uses the Activity result bridge for broad Android 7+ device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQUEST_WEB_FILE_CHOOSER) {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = null
            }
            pendingPicker = PickerMode.NONE
            return
        }
        try {
            when (requestCode) {
                REQUEST_PICK_FILES -> ioExecutor.execute { importPickedFiles(data) }
                REQUEST_WEB_FILE_CHOOSER -> {
                    val callback = fileChooserCallback
                    fileChooserCallback = null
                    val results = mutableListOf<Uri>()
                    data.clipData?.let { clip -> for (i in 0 until clip.itemCount) results.add(clip.getItemAt(i).uri) }
                    if (results.isEmpty()) data.data?.let(results::add)
                    callback?.onReceiveValue(results.takeIf { it.isNotEmpty() }?.toTypedArray())
                }
                REQUEST_PICK_TREE -> {
                    val uri = data.data ?: return
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) { }
                    when (pendingPicker) {
                        PickerMode.IMPORT_FOLDER -> ioExecutor.execute { importPickedFolder(uri) }
                        PickerMode.EXPORT_FOLDER -> ioExecutor.execute { exportWorkspace(uri) }
                        else -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Picker operation failed", e)
            postEvent(JSONObject().put("type", "native.error").put("message", e.message ?: "File operation failed"))
        } finally {
            pendingPicker = PickerMode.NONE
        }
    }

    private fun importPickedFiles(data: Intent) {
        importBytes = workspaceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
        }
        if (uris.isEmpty()) data.data?.let(uris::add)
        var imported = 0
        var last: String? = null
        uris.forEach { uri ->
            val doc = DocumentFile.fromSingleUri(this, uri)
            val name = doc?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "untitled.txt"
            if (copyUriToWorkspace(uri, name)) { imported++; last = name }
        }
        sendWorkspace("workspace.imported", hasFiles = true, last = last)
        postEvent(JSONObject().put("type", "terminal.notice").put("message", "Imported $imported file(s)."))
    }

    private fun importPickedFolder(uri: Uri) {
        importBytes = workspaceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val tree = DocumentFile.fromTreeUri(this, uri) ?: throw IOException("Could not open selected folder")
        var imported = 0
        var skipped = 0
        var last: String? = null
        fun walk(dir: DocumentFile, relative: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val path = if (relative.isBlank()) name else "$relative/$name"
                if (child.isDirectory) {
                    safeWorkspaceFile("$path/.keep")?.parentFile?.mkdirs()
                    walk(child, path)
                } else if (child.isFile) {
                    if (copyUriToWorkspace(child.uri, path)) { imported++; last = path } else skipped++
                }
            }
        }
        walk(tree, "")
        sendWorkspace("workspace.imported", hasFiles = true, last = last)
        postEvent(JSONObject().put("type", "terminal.notice").put("message", "Folder imported: $imported text file(s), $skipped skipped."))
    }

    private fun copyUriToWorkspace(uri: Uri, relativePath: String): Boolean {
        val target = safeWorkspaceFile(relativePath) ?: return false
        return try {
            val input = contentResolver.openInputStream(uri) ?: return false
            val bytes = input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) return false
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.any { it == 0.toByte() }) return false
            val previousBytes = if (target.isFile) target.length() else 0L
            if (importBytes - previousBytes + bytes.size > MAX_IMPORT_TOTAL_BYTES) return false
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            target.parentFile?.mkdirs()
            target.writeText(text, StandardCharsets.UTF_8)
            importBytes = importBytes - previousBytes + bytes.size
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun exportWorkspace(uri: Uri) {
        val destination = DocumentFile.fromTreeUri(this, uri) ?: throw IOException("Could not open destination folder")
        var count = 0
        workspaceDir.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(workspaceDir).invariantSeparatorsPath
            val parts = relative.split('/')
            var parent = destination
            parts.dropLast(1).forEach { part ->
                parent = parent.findFile(part)?.takeIf { it.isDirectory } ?: parent.createDirectory(part)
                    ?: throw IOException("Cannot create folder $part")
            }
            val name = parts.last()
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(source.extension.lowercase(Locale.ROOT)) ?: "text/plain"
            val target = parent.findFile(name)?.takeIf { it.isFile } ?: parent.createFile(mime, name)
                ?: throw IOException("Cannot create file $name")
            contentResolver.openOutputStream(target.uri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
            count++
        }
        postEvent(JSONObject().put("type", "terminal.notice").put("message", "Exported $count file(s) to the selected folder."))
    }

    override fun onBackPressed() {
        when {
            terminalSheet.visibility == View.VISIBLE && sheetMaximized -> toggleTerminalMaximize()
            terminalSheet.visibility == View.VISIBLE -> hideTerminal()
            else -> webView.evaluateJavascript(
                "(() => { const e=document.getElementById('ide'); if(e && e.classList.contains('preview-full')) { window.togglePreviewFullscreen(false); return true; } return false; })()"
            ) { handled -> if (handled != "true") finishWithBack() }
        }
    }

    private fun finishWithBack() {
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        terminalSession?.let { if (it.getPid() > 0) it.finishIfRunning() }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        ioExecutor.shutdown()
        if (::webView.isInitialized) {
            (root.parent as? ViewGroup)?.removeView(root)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "VYRON"
        private const val REQUEST_PICK_FILES = 7101
        private const val REQUEST_PICK_TREE = 7102
        private const val REQUEST_WEB_FILE_CHOOSER = 7103
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024
        private const val MAX_WORKSPACE_BYTES = 12L * 1024L * 1024L
        private const val MAX_IMPORT_TOTAL_BYTES = 60L * 1024L * 1024L
    }
}
