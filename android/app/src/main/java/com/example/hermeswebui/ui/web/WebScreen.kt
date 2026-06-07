package com.example.hermeswebui.ui.web

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

// Custom Palette matching Hermes gold/charcoal theme
private val HermesBg = Color(0xFF101012)
private val HermesGold = Color(0xFFE5C158)
private val HermesTextPrimary = Color(0xFFEAEAEA)
private val HermesBorder = Color(0xFF2C2C30)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(
    url: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Periodically flush cookies to disk when the app goes to the background/pause
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                CookieManager.getInstance().flush()
                Log.d("HermesWebScreen", "Flushed cookies to disk on app pause")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var loadingProgress by remember { mutableStateOf(0) }
    var isPageLoading by remember { mutableStateOf(true) }

    // State for managing file chooser (upload callbacks)
    var uploadMessage: ValueCallback<Array<Uri>>? by remember { mutableStateOf(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            } else if (data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            uploadMessage?.onReceiveValue(uris)
        } else {
            uploadMessage?.onReceiveValue(null)
        }
        uploadMessage = null
    }

    // Upfront microphone permission request
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Voice input permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Voice input requires microphone access", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger mic permission request on startup if not already granted
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Intercept native hardware back gesture
    BackHandler(enabled = webViewInstance?.canGoBack() == true) {
        webViewInstance?.goBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HermesBg)
    ) {
        // Premium App Bar
        TopAppBar(
            title = {
                Text(
                    text = "Hermes Client",
                    color = HermesTextPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = HermesBg,
                titleContentColor = HermesTextPrimary
            ),
            actions = {
                // Refresh Action
                IconButton(onClick = { webViewInstance?.reload() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = HermesGold
                    )
                }
                // Log Out / Exit Action
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.Default.ExitToApp,
                        contentDescription = "Disconnect Server",
                        tint = Color.Red
                    )
                }
            },
            modifier = Modifier.height(56.dp)
        )

        // Webpage Loading Progress indicator
        if (isPageLoading) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                color = HermesGold,
                trackColor = HermesBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        } else {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(HermesBorder)
            )
        }

        // WebView Container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewInstance = this
                        
                        // Enable Cookies
                        CookieManager.getInstance().setAcceptCookie(true)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }

                        // WebView settings
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            allowFileAccess = true
                            allowContentAccess = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            
                            // Custom User Agent suffix for client detection
                            userAgentString = "${userAgentString} HermesAndroid"

                            // Enable text zoom / mobile responsive views
                            textZoom = 100
                        }
                        
                        // Clear HTTP cache on launch to ensure fresh resource loading
                        clearCache(true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isPageLoading = true
                                loadingProgress = 0
                            }

                             override fun onPageFinished(view: WebView?, url: String?) {
                                 isPageLoading = false
                                 loadingProgress = 100
                                 CookieManager.getInstance().flush()
                                 Log.d("HermesWebScreen", "Flushed cookies to disk on page finished: $url")
                             }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // Ignore subresource loading errors
                                if (request?.isForMainFrame == true) {
                                    isPageLoading = false
                                    Toast.makeText(context, "Connection lost: ${error?.description}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                         webChromeClient = object : WebChromeClient() {
                             override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                 loadingProgress = newProgress
                                 if (newProgress >= 100) {
                                     isPageLoading = false
                                 }
                             }

                             // Forward JavaScript console messages/errors to Android Logcat
                             override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                 consoleMessage?.let {
                                     val level = when (it.messageLevel()) {
                                         ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                                         ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                                         ConsoleMessage.MessageLevel.LOG -> Log.INFO
                                         ConsoleMessage.MessageLevel.TIP -> Log.DEBUG
                                         else -> Log.VERBOSE
                                     }
                                     Log.println(
                                         level,
                                         "HermesWebViewConsole",
                                         "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}"
                                     )
                                 }
                                 return super.onConsoleMessage(consoleMessage)
                             }

                             // Intercept file chooser for uploading attachments
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                uploadMessage?.onReceiveValue(null)
                                uploadMessage = filePathCallback

                                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                }
                                try {
                                    fileChooserLauncher.launch(intent)
                                } catch (e: ActivityNotFoundException) {
                                    uploadMessage = null
                                    Toast.makeText(context, "No file browser available", Toast.LENGTH_SHORT).show()
                                    return false
                                }
                                return true
                            }

                            // Handle microphone permissions natively
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                if (request == null) return
                                val resources = request.resources
                                if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                                    // Check if we have mic permission granted at system level
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                                    } else {
                                        // Request permission upfront, deny this specific web request for now
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        request.deny()
                                    }
                                } else {
                                    request.grant(resources)
                                }
                            }
                        }

                        // Intercept file downloads from the workspace panel
                        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, contentLength ->
                            try {
                                val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                    setMimeType(mimeType)
                                    addRequestHeader("User-Agent", userAgent)
                                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(downloadUrl))
                                    setDescription("Downloading workspace file...")
                                    setTitle(URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType))
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
                                    )
                                }
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                                Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                // Fallback
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    Toast.makeText(context, "Could not open download link", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        // Load target WebUI page
                        loadUrl(url)
                    }
                },
                update = { webView ->
                    // Keep references up to date
                    webViewInstance = webView
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
