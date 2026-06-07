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
