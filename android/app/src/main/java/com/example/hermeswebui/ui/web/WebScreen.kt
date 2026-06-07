package com.example.hermeswebui.ui.web

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import android.widget.Toast
import com.example.hermeswebui.MainActivity
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.absoluteValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Custom Palette matching Hermes gold/charcoal theme
private val HermesBg = Color(0xFF101012)
private val HermesGold = Color(0xFFE5C158)
private val HermesTextPrimary = Color(0xFFEAEAEA)
private val HermesTextSecondary = Color(0xFF8E8E93)
private val HermesBorder = Color(0xFF2C2C30)
private val HermesCardBg = Color(0xFF1A1A1E)
private const val HERMES_NOTIFICATION_CHANNEL_ID = "hermes_agent_events"
private const val HERMES_NOTIFICATION_CHANNEL_NAME = "Hermes agent events"
private const val PREF_ANDROID_BACKGROUND_POLLING = "android_background_polling"
private const val PREF_ANDROID_AGENT_NOTIFICATIONS = "android_agent_notifications"
private const val PREF_ANDROID_CRON_NOTIFICATIONS = "android_cron_notifications"
private const val PREF_ANDROID_POLL_SECONDS = "android_poll_seconds"
private const val DEFAULT_ANDROID_POLL_SECONDS = 30

private const val INJECTION_SCRIPT = """
(function() {
    // 1. EventSource Override
    if (window.EventSource && !window.__eventSourceOverridden) {
        var OriginalES = window.EventSource;
        var WrappedEventSource = function(url, options) {
            var urlStr = String(url);
            if (urlStr.includes('api/sessions/gateway/stream') ||
                urlStr.includes('api/approval/stream') ||
                urlStr.includes('api/clarify/stream') ||
                urlStr.includes('api/sessions/events')) {
                console.log('Blocked EventSource for: ' + urlStr + ' (Android optimization)');
                throw new Error('SSE disabled on Android wrapper to conserve connection pool');
            }
            if (this instanceof WrappedEventSource) {
                return new OriginalES(url, options);
            } else {
                return OriginalES(url, options);
            }
        };
        WrappedEventSource.prototype = OriginalES.prototype;
        WrappedEventSource.CONNECTING = OriginalES.CONNECTING;
        WrappedEventSource.OPEN = OriginalES.OPEN;
        WrappedEventSource.CLOSED = OriginalES.CLOSED;
        window.EventSource = WrappedEventSource;
        window.__eventSourceOverridden = true;
        console.log('EventSource override injected');
    }

    // 2. Viewport Height Fix
    var injectStylesAndSetupResize = function() {
        if (document.getElementById('hermes-android-viewport-fix')) return;
        var style = document.createElement('style');
        style.id = 'hermes-android-viewport-fix';
        style.textContent = 'html, body { height: 100vh !important; height: 100dvh !important; min-height: 100% !important; }';
        if (document.documentElement) {
            document.documentElement.appendChild(style);
        }
        var setH = function() {
            var h = window.innerHeight + 'px';
            if (document.documentElement) document.documentElement.style.setProperty('height', h, 'important');
            if (document.body) document.body.style.setProperty('height', h, 'important');
        };
        setH();
        window.addEventListener('resize', setH);
        window.addEventListener('load', setH);
        document.addEventListener('DOMContentLoaded', setH);
        console.log('Viewport height fix style injected');
    };

    if (document.documentElement) {
        injectStylesAndSetupResize();
    } else {
        document.addEventListener('DOMContentLoaded', injectStylesAndSetupResize);
    }
})();
"""

private class HermesNotificationBridge(private val context: Context) {
    @JavascriptInterface
    fun notify(title: String?, body: String?) {
        showHermesNotification(context, title, body)
    }
}

private data class HermesSessionSnapshot(
    val title: String,
    val messageCount: Int,
    val isRunning: Boolean
)

private class HermesBackgroundNotificationPoller(
    context: Context,
    private val serverUrl: String,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val sessionsById = mutableMapOf<String, HermesSessionSnapshot>()
    private var firstSessionPoll = true
    private var cronSinceSeconds = System.currentTimeMillis() / 1000.0
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                pollOnce()
                handler.postDelayed(this, pollIntervalMillis())
            }.start()
        }
    }

    fun start() {
        if (running || !prefs.getBoolean(PREF_ANDROID_BACKGROUND_POLLING, true)) return
        running = true
        handler.post(pollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun pollIntervalMillis(): Long {
        val seconds = prefs.getInt(PREF_ANDROID_POLL_SECONDS, DEFAULT_ANDROID_POLL_SECONDS)
            .coerceIn(15, 300)
        return seconds * 1000L
    }

    private fun pollOnce() {
        if (prefs.getBoolean(PREF_ANDROID_AGENT_NOTIFICATIONS, true)) {
            pollSessions()
        }
        if (prefs.getBoolean(PREF_ANDROID_CRON_NOTIFICATIONS, true)) {
            pollCronCompletions()
        }
    }

    private fun pollSessions() {
        val root = getJson("$serverUrl/api/sessions?all_profiles=1") ?: return
        val sessions = root.optJSONArray("sessions") ?: return
        val next = mutableMapOf<String, HermesSessionSnapshot>()
        for (i in 0 until sessions.length()) {
            val item = sessions.optJSONObject(i) ?: continue
            val sid = item.optString("session_id", "")
            if (sid.isBlank()) continue
            val snapshot = HermesSessionSnapshot(
                title = item.optString("title", "Hermes conversation"),
                messageCount = item.optInt("message_count", 0),
                isRunning = item.optBoolean("is_streaming", false) ||
                    item.optString("active_stream_id", "").isNotBlank()
            )
            next[sid] = snapshot
            val previous = sessionsById[sid]
            if (!firstSessionPoll && previous != null) {
                val completed = previous.isRunning && !snapshot.isRunning
                val receivedNewMessages = snapshot.messageCount > previous.messageCount
                if (completed && receivedNewMessages) {
                    showHermesNotification(appContext, "Response complete", snapshot.title)
                }
            }
        }
        sessionsById.clear()
        sessionsById.putAll(next)
        firstSessionPoll = false
    }

    private fun pollCronCompletions() {
        val root = getJson("$serverUrl/api/crons/recent?since=$cronSinceSeconds") ?: return
        val completions = root.optJSONArray("completions") ?: return
        var newest = cronSinceSeconds
        for (i in 0 until completions.length()) {
            val item = completions.optJSONObject(i) ?: continue
            val completedAt = item.optDouble("completed_at", 0.0)
            if (completedAt > newest) newest = completedAt
            if (item.optBoolean("toast_notifications", true)) {
                val name = item.optString("name", "Cron job")
                val status = if (item.optString("status", "") == "error") "failed" else "completed"
                showHermesNotification(appContext, "Cron finished", "$name $status")
            }
        }
        cronSinceSeconds = newest
    }

    private fun getJson(url: String): JSONObject? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                CookieManager.getInstance().getCookie(serverUrl)?.let { cookie ->
                    setRequestProperty("Cookie", cookie)
                }
            }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: Exception) {
            Log.w("HermesPoller", "Polling failed for $url", e)
            null
        }
    }
}

private fun showHermesNotification(context: Context, title: String?, body: String?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        Log.w("HermesNotifications", "Notification skipped because POST_NOTIFICATIONS is not granted")
        return
    }

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            HERMES_NOTIFICATION_CHANNEL_ID,
            HERMES_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Agent responses, cron completions, and background task alerts"
        }
        manager.createNotificationChannel(channel)
    }

    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val safeTitle = title?.trim()?.take(80).takeUnless { it.isNullOrEmpty() } ?: "Hermes"
    val safeBody = body?.trim()?.take(240).orEmpty()
    val notification = NotificationCompat.Builder(context, HERMES_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(safeTitle)
        .setContentText(safeBody)
        .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt().absoluteValue, notification)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(
    url: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sharedPrefs = remember { context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var showAndroidSettings by remember { mutableStateOf(false) }
    val backgroundPoller = remember(url) { HermesBackgroundNotificationPoller(context, url) }

    // Periodically flush cookies to disk when the app goes to the background/pause
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                CookieManager.getInstance().flush()
                backgroundPoller.start()
                Log.d("HermesWebScreen", "Flushed cookies to disk on app pause")
            } else if (event == Lifecycle.Event.ON_RESUME) {
                backgroundPoller.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            backgroundPoller.stop()
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

    // Upfront microphone + notification permission request
    val appPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == false) {
            Toast.makeText(context, "Voice input requires microphone access", Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permissions[Manifest.permission.POST_NOTIFICATIONS] == false
        ) {
            Toast.makeText(context, "Notifications are disabled for Hermes", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger runtime permission requests on startup if not already granted
    LaunchedEffect(Unit) {
        val missingPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missingPermissions.isNotEmpty()) {
            appPermissionLauncher.launch(missingPermissions.toTypedArray())
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
            .statusBarsPadding()
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
                IconButton(onClick = { showAndroidSettings = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Android Settings",
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
                .imePadding()
                .navigationBarsPadding()
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewInstance = this
                        
                        // Enable remote debugging
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            WebView.setWebContentsDebuggingEnabled(true)
                        }

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
                        addJavascriptInterface(HermesNotificationBridge(ctx.applicationContext), "HermesAndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isPageLoading = true
                                loadingProgress = 0
                                view?.evaluateJavascript(INJECTION_SCRIPT, null)
                            }

                             override fun onPageFinished(view: WebView?, url: String?) {
                                 isPageLoading = false
                                 loadingProgress = 100
                                 CookieManager.getInstance().flush()
                                 Log.d("HermesWebScreen", "Flushed cookies to disk on page finished: $url")
                                 view?.evaluateJavascript(INJECTION_SCRIPT, null)
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
                                 view?.evaluateJavascript(INJECTION_SCRIPT, null)
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
                                        appPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
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

    if (showAndroidSettings) {
        AndroidAppSettingsDialog(
            prefs = sharedPrefs,
            onDismiss = { showAndroidSettings = false },
            onOpenSystemNotificationSettings = { openAndroidNotificationSettings(context) }
        )
    }
}

@Composable
private fun AndroidAppSettingsDialog(
    prefs: SharedPreferences,
    onDismiss: () -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
) {
    var backgroundPolling by remember { mutableStateOf(prefs.getBoolean(PREF_ANDROID_BACKGROUND_POLLING, true)) }
    var agentNotifications by remember { mutableStateOf(prefs.getBoolean(PREF_ANDROID_AGENT_NOTIFICATIONS, true)) }
    var cronNotifications by remember { mutableStateOf(prefs.getBoolean(PREF_ANDROID_CRON_NOTIFICATIONS, true)) }
    var pollSeconds by remember {
        mutableStateOf(prefs.getInt(PREF_ANDROID_POLL_SECONDS, DEFAULT_ANDROID_POLL_SECONDS).coerceIn(15, 300))
    }

    fun save() {
        prefs.edit()
            .putBoolean(PREF_ANDROID_BACKGROUND_POLLING, backgroundPolling)
            .putBoolean(PREF_ANDROID_AGENT_NOTIFICATIONS, agentNotifications)
            .putBoolean(PREF_ANDROID_CRON_NOTIFICATIONS, cronNotifications)
            .putInt(PREF_ANDROID_POLL_SECONDS, pollSeconds)
            .apply()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HermesCardBg,
        title = { Text("Android app settings", color = HermesTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsSwitchRow(
                    title = "Background notification checks",
                    subtitle = "Watch for completed agent responses and cron jobs while the app is backgrounded.",
                    checked = backgroundPolling,
                    onCheckedChange = { backgroundPolling = it; save() }
                )
                SettingsSwitchRow(
                    title = "Agent response notifications",
                    subtitle = "Notify when a conversation that was running in the background finishes.",
                    checked = agentNotifications,
                    enabled = backgroundPolling,
                    onCheckedChange = { agentNotifications = it; save() }
                )
                SettingsSwitchRow(
                    title = "Cron completion notifications",
                    subtitle = "Notify when scheduled jobs report new output.",
                    checked = cronNotifications,
                    enabled = backgroundPolling,
                    onCheckedChange = { cronNotifications = it; save() }
                )
                Text("Check interval: ${pollSeconds}s", color = HermesTextPrimary)
                Slider(
                    value = pollSeconds.toFloat(),
                    onValueChange = { pollSeconds = it.toInt().coerceIn(15, 300) },
                    onValueChangeFinished = { save() },
                    valueRange = 15f..300f,
                    steps = 18,
                    enabled = backgroundPolling,
                    colors = SliderDefaults.colors(
                        thumbColor = HermesGold,
                        activeTrackColor = HermesGold,
                        inactiveTrackColor = HermesBorder,
                    )
                )
                OutlinedButton(
                    onClick = onOpenSystemNotificationSettings,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HermesGold),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Android notification settings")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = HermesGold)
            }
        }
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (enabled) HermesTextPrimary else HermesTextSecondary)
            Text(subtitle, color = HermesTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HermesGold,
                checkedTrackColor = HermesGold.copy(alpha = 0.35f),
            )
        )
    }
}

private fun openAndroidNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Android settings", Toast.LENGTH_SHORT).show()
    }
}
