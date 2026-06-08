"""Regression coverage for Android native completion notifications."""

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
WEB_SCREEN = (
    REPO
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "example"
    / "hermeswebui"
    / "ui"
    / "web"
    / "WebScreen.kt"
).read_text(encoding="utf-8")
MANIFEST = (REPO / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(
    encoding="utf-8"
)
MESSAGES_JS = (REPO / "static" / "messages.js").read_text(encoding="utf-8")
PANELS_JS = (REPO / "static" / "panels.js").read_text(encoding="utf-8")


def test_android_webview_exposes_native_notification_bridge():
    assert "android.permission.POST_NOTIFICATIONS" in MANIFEST
    assert 'private const val HERMES_NOTIFICATION_CHANNEL_ID = "hermes_agent_events"' in WEB_SCREEN
    assert "@JavascriptInterface" in WEB_SCREEN
    assert 'addJavascriptInterface(HermesNotificationBridge(ctx.applicationContext), "HermesAndroidBridge")' in WEB_SCREEN
    assert "ActivityResultContracts.RequestMultiplePermissions()" in WEB_SCREEN
    assert "missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)" in WEB_SCREEN


def test_webui_completion_notifications_use_android_bridge_when_hidden():
    assert "window.HermesAndroidBridge.notify" in MESSAGES_JS
    assert "Android notification bridge failed" in MESSAGES_JS
    assert "sendBrowserNotification('Response complete'" in MESSAGES_JS
    assert "sendBrowserNotification(t('bg_complete')" in MESSAGES_JS


def test_android_cron_polling_keeps_native_completion_alerts_alive():
    assert "if(document.hidden && !isHermesAndroid) return;" in PANELS_JS
    assert "sendBrowserNotification('Cron finished'" in PANELS_JS


def test_android_native_background_poller_observes_sessions_and_crons():
    assert "private class HermesBackgroundNotificationPoller" in WEB_SCREEN
    assert 'getJson("$serverUrl/api/sessions?all_profiles=1")' in WEB_SCREEN
    assert 'getJson("$serverUrl/api/crons/recent?since=$cronSinceSeconds")' in WEB_SCREEN
    assert 'CookieManager.getInstance().getCookie(serverUrl)' in WEB_SCREEN
    assert 'showHermesNotification(appContext, "Response complete", snapshot.title)' in WEB_SCREEN
    assert 'showHermesNotification(appContext, "Cron finished", "$name $status")' in WEB_SCREEN


def test_android_poller_keeps_foreground_baseline_and_notifies_in_background():
    assert "backgroundPoller.start(notifyOnCompletion = false)" in WEB_SCREEN
    assert "backgroundPoller.start(notifyOnCompletion = true)" in WEB_SCREEN
    assert "backgroundPoller.stop()" in WEB_SCREEN
    assert "event == Lifecycle.Event.ON_PAUSE" in WEB_SCREEN
    assert "event == Lifecycle.Event.ON_RESUME" in WEB_SCREEN
    assert "if (notifyOnCompletion && completed && receivedNewMessages)" in WEB_SCREEN
    assert 'if (notifyOnCompletion && item.optBoolean("toast_notifications", true))' in WEB_SCREEN


def test_android_app_settings_dialog_controls_native_notifications():
    assert "AndroidAppSettingsDialog" in WEB_SCREEN
    assert "imageVector = Icons.Default.Settings" in WEB_SCREEN
    assert 'PREF_ANDROID_BACKGROUND_POLLING = "android_background_polling"' in WEB_SCREEN
    assert 'PREF_ANDROID_AGENT_NOTIFICATIONS = "android_agent_notifications"' in WEB_SCREEN
    assert 'PREF_ANDROID_CRON_NOTIFICATIONS = "android_cron_notifications"' in WEB_SCREEN
    assert 'PREF_ANDROID_POLL_SECONDS = "android_poll_seconds"' in WEB_SCREEN
    assert "Open Android notification settings" in WEB_SCREEN
