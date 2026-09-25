package com.example.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.browser.TrackerBlocker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GodBrowserScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var urlInput by remember { mutableStateOf("https://duckduckgo.com") }
    var currentUrl by remember { mutableStateOf("https://duckduckgo.com") }
    var pageTitle by remember { mutableStateOf("GOD Mode Secure Sandbox") }
    var isLoading by remember { mutableStateOf(false) }
    var blockedTrackerCount by remember { mutableIntStateOf(0) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.apply { stopLoading(); destroy() }
            webViewInstance = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Hardened Browser",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "SANDBOX",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF81C784),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Trackers Blocked: $blockedTrackerCount • App traffic follows VPN routing",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        webViewInstance?.let { webView ->
                            webView.clearCache(true)
                            webView.clearHistory()
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            webView.loadUrl("about:blank")
                            blockedTrackerCount = 0
                            Toast.makeText(context, "Session wiped & cache shredded cleanly.", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Nuke Session", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // URL Input & Control Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webViewInstance?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = { webViewInstance?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(
                        onClick = { webViewInstance?.reload() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("Search or type URL...", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focusManager.clearFocus()
                            val target = if (urlInput.startsWith("http://") || urlInput.startsWith("https://")) {
                                urlInput
                            } else if (urlInput.contains(".") && !urlInput.contains(" ")) {
                                "https://$urlInput"
                            } else {
                                "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(urlInput, "UTF-8")
                            }
                            currentUrl = target
                            webViewInstance?.loadUrl(target)
                        }),
                        leadingIcon = {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Hardened WebView Sandbox Container
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            @SuppressLint("SetJavaScriptEnabled")
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true // Enable HTML5 DOM storage for web app compatibility
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                allowFileAccess = false
                                allowContentAccess = false
                                setGeolocationEnabled(false)
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return false
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    if (TrackerBlocker.isBlocked(reqUrl)) {
                                        view?.post { blockedTrackerCount++ }
                                        return WebResourceResponse("text/plain", "UTF-8", null)
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                    url?.let {
                                        currentUrl = it
                                        urlInput = it
                                    }
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                }

                                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                                    // Invalid certificates must never be accepted.
                                    handler?.cancel()
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) {
                                        isLoading = false
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    title?.let { pageTitle = it }
                                }
                            }

                            webViewInstance = this
                            loadUrl(currentUrl)
                        }
                    },
                    update = { view ->
                        webViewInstance = view
                    }
                )
            }
        }
    }
}
