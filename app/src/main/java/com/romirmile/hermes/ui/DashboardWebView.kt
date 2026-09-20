package com.romirmile.hermes.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.romirmile.hermes.R
import com.romirmile.hermes.bridge.BridgeHost
import com.romirmile.hermes.bridge.HermesAndroidBridge

/**
 * The app's interface: a WebView that hosts the vendored Hermes-mobile shell.
 *
 * The shell is served from the app's own assets through [WebViewAssetLoader], so it gets an https
 * origin (`https://appassets.androidplatform.net/…`) instead of the opaque `file://` origin — the
 * reference app had the same thing through Capacitor's local server, and the shell needs it for DOM
 * storage and for its navigation rules.
 *
 * The guest dashboard is a different origin (`http://127.0.0.1:9129`) and is opened on demand by the
 * shell; the native proxy in [HermesAndroidBridge] keeps the shell out of CORS trouble.
 */
private const val ASSET_DOMAIN = "appassets.androidplatform.net"
private const val SHELL_PATH = "/assets/www/index.html"
private const val SHELL_URL = "https://$ASSET_DOMAIN$SHELL_PATH?locale=en"
private const val INJECTED_SCRIPT_ASSET = "www/hermes-android-page.js"
private const val MAX_RETRIES = 6
private const val RETRY_DELAY_MS = 2_000L

/** Where the WebView is in its load/retry cycle, so the screen can say so. */
private enum class WebViewPhase { LOADING, RETRYING, SHOWN }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DashboardWebView(
    modifier: Modifier = Modifier,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val currentOnExit by rememberUpdatedState(onExit)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var phase by remember { mutableStateOf(WebViewPhase.LOADING) }
    var attempt by remember { mutableStateOf(0) }
    var lastError by remember { mutableStateOf("") }

    // Pending bridge callbacks: the JS thread parks on a latch, the launcher answers from the UI
    // thread. One outstanding request at a time is all the shell ever issues. A plain holder is used
    // instead of Compose state because the bridge writes to it from the WebView's JS thread.
    val pending = remember { PendingCallbacks() }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val paths = uris.map { uri ->
            // Keep read access across restarts, then hand the URI to the shell. Nothing is copied
            // into public storage: scoped storage never needs it, and the shell only passes the
            // reference on.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            uri.toString()
        }
        pending.picker?.invoke(paths)
        pending.picker = null
    }

    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        pending.microphone?.invoke(granted)
        pending.microphone = null
    }

    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val pageScript = remember { loadPageScript(context) }

    val host = remember {
        object : BridgeHost {
            override fun runOnUiThread(block: () -> Unit) = post(block)

            private fun post(block: () -> Unit) {
                val view = webView
                if (view != null) {
                    view.post(block)
                } else if (activity != null) {
                    activity.runOnUiThread(block)
                } else {
                    block()
                }
            }

            override fun currentUrl(): String? = webView?.url

            override fun loadShellHome(): Boolean {
                val view = webView ?: return false
                loadEnglish(view, SHELL_URL)
                return true
            }

            override fun showSoftKeyboard(): Boolean {
                val view = webView ?: return false
                view.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                return true
            }

            override fun pickDocuments(onResult: (List<String>) -> Unit) {
                pending.picker = onResult
                // ActivityResultLauncher.launch() belongs on the UI thread; this arrives from JS.
                post { documentPicker.launch(arrayOf("*/*")) }
            }

            override fun requestMicrophonePermission(onResult: (Boolean) -> Unit) {
                pending.microphone = onResult
                post { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            }

            override fun hasMicrophonePermission(): Boolean =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    val bridge = remember { HermesAndroidBridge(context, host) }

    DisposableEffect(Unit) {
        onDispose {
            bridge.shutdown()
            webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webView = null
        }
    }

    // Back: WebView history first (the shell and the dashboard both push history), and the engine
    // screen once there is nothing to go back to.
    BackHandler {
        val view = webView
        if (view == null) {
            currentOnExit()
            return@BackHandler
        }
        val url = view.url.orEmpty()
        if (isDashboardUrl(url)) {
            if (view.canGoBack()) view.goBack() else currentOnExit()
            return@BackHandler
        }
        // Shell page: it can handle back itself (closing its drawer), like the reference app did.
        view.evaluateJavascript(
            "(function(){try{return window.__hermesHandleMobileBack?!!window.__hermesHandleMobileBack():false;}catch(e){return false;}})()"
        ) { result ->
            if (result == "true") {
                // The shell consumed the back press.
            } else if (view.canGoBack()) {
                view.goBack()
            } else {
                currentOnExit()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                createWebView(
                    context = ctx,
                    assetLoader = assetLoader,
                    bridge = bridge,
                    pageScript = pageScript,
                    onPageSettled = {
                        phase = WebViewPhase.SHOWN
                        attempt = 0
                        lastError = ""
                    },
                    onMainFrameError = { message, tries ->
                        lastError = message
                        attempt = tries
                        phase = WebViewPhase.RETRYING
                    }
                ).also { view ->
                    webView = view
                    loadEnglish(view, SHELL_URL)
                }
            }
        )

        if (phase != WebViewPhase.SHOWN) {
            LoadingOverlay(phase = phase, attempt = attempt, error = lastError)
        }
    }
}

/** Every page load carries `Accept-Language: en`, so a Chinese device locale cannot flip the UI. */
private fun loadEnglish(view: WebView, url: String) {
    view.loadUrl(url, mapOf("Accept-Language" to "en"))
}

private fun isDashboardUrl(url: String): Boolean =
    url.contains("127.0.0.1:9129") || url.contains("localhost:9129")

private fun loadPageScript(context: Context): String = runCatching {
    context.assets.open(INJECTED_SCRIPT_ASSET)
        .bufferedReader()
        .use { it.readText() }
        .replace("__HERMES_SHELL_URL__", SHELL_URL)
}.getOrDefault("")

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    assetLoader: WebViewAssetLoader,
    bridge: HermesAndroidBridge,
    pageScript: String,
    onPageSettled: () -> Unit,
    onMainFrameError: (String, Int) -> Unit
): WebView {
    val webView = WebView(context)
    val retries = intArrayOf(0)

    webView.settings.apply {
        // Set explicitly: the reference inherited these from Capacitor defaults, a plain WebView
        // does not have them.
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Phone screen, like the reference: no zoom, no wide viewport, no desktop overview.
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        useWideViewPort = false
        loadWithOverviewMode = false
        javaScriptCanOpenWindowsAutomatically = false
        // The user agent is NOT spoofed: the reference app did not set one either.
    }
    webView.setInitialScale(0)

    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme.orEmpty()
            // Anything that is not http(s) (mailto:, qq:, market:, …) belongs to the system.
            if (scheme.isNotEmpty() && !scheme.equals("http", true) && !scheme.equals("https", true)) {
                return try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, request.url).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    true
                } catch (_: Exception) {
                    true
                }
            }
            return false
        }

        override fun onPageFinished(view: WebView, url: String) {
            // The reference injects its mobile chrome and safe-area plumbing on every page, shell and
            // dashboard alike.
            retries[0] = 0
            if (pageScript.isNotEmpty()) {
                view.evaluateJavascript(pageScript, null)
            }
            pushSafeAreaInsets(view)
            onPageSettled()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            // The VM may still be booting when the shell is created, so a main-frame failure is
            // retried with a short backoff instead of leaving an empty screen.
            if (!request.isForMainFrame) return
            val message = error.description?.toString().orEmpty()
            if (retries[0] < MAX_RETRIES) {
                retries[0] += 1
                onMainFrameError(message, retries[0])
                val target = view.url?.takeIf { it.isNotEmpty() } ?: SHELL_URL
                view.postDelayed({ loadEnglish(view, target) }, RETRY_DELAY_MS)
            } else {
                onMainFrameError(message, retries[0])
            }
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // Mirrors the reference: audio capture is granted only when RECORD_AUDIO is already
            // granted, and denied otherwise (the bridge method is what asks for it).
            val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (wantsAudio && granted) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request.deny()
            }
        }
    }

    webView.addJavascriptInterface(bridge, HermesAndroidBridge.INTERFACE_NAME)

    // Raw pixel insets for the page, exactly like the reference (the shell converts them with the
    // device pixel ratio). The Compose container stays edge-to-edge so the WebView owns the window.
    ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setTag(R.id.hermes_insets_tag, intArrayOf(bars.top, bars.right, bars.bottom, bars.left))
        if (view is WebView) pushSafeAreaInsets(view)
        insets
    }
    ViewCompat.requestApplyInsets(webView)

    return webView
}

/** Pushes the stored insets into the current page. */
private fun pushSafeAreaInsets(view: WebView) {
    val insets = view.getTag(R.id.hermes_insets_tag) as? IntArray ?: return
    view.evaluateJavascript(
        "window.__hermesAndroidSetInsets&&window.__hermesAndroidSetInsets(" +
            "${insets[0]},${insets[1]},${insets[2]},${insets[3]});",
        null
    )
}

/** Holds the one outstanding picker/microphone callback; written from the JS thread. */
private class PendingCallbacks {
    @Volatile var picker: ((List<String>) -> Unit)? = null
    @Volatile var microphone: ((Boolean) -> Unit)? = null
}

@Composable
private fun LoadingOverlay(phase: WebViewPhase, attempt: Int, error: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Text(
                text = if (phase == WebViewPhase.RETRYING) {
                    stringResource(R.string.webview_retrying, attempt)
                } else {
                    stringResource(R.string.webview_loading)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (error.isNotBlank()) {
                Text(
                    text = stringResource(R.string.webview_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
