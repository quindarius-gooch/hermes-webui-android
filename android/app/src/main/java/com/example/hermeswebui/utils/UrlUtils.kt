package com.example.hermeswebui.utils

object UrlUtils {
    /**
     * Normalizes the user input URL string:
     * - Trims leading and trailing whitespace
     * - Prepends "http://" if "http://" or "https://" protocol is missing
     * - Strips trailing slashes
     */
    fun normalizeUrl(input: String): String {
        var url = input.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        // Remove trailing slash for consistency
        if (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
        return url
    }
}
