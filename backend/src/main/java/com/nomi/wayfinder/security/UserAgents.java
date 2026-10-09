package com.nomi.wayfinder.security;

import java.util.Locale;

/**
 * A short, readable device name from a User-Agent header: "Chrome · Windows", "Safari · iOS", "Nomi · Android"
 * (the app's WebView), null when it tells nothing. Only for showing the user their sessions; never used for a
 * security decision.
 */
public final class UserAgents {

    private UserAgents() {
    }

    public static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String os = os(ua);
        String client = client(ua);
        if (client == null && os == null) {
            return null;
        }
        if (client == null) {
            return os;
        }
        return os == null ? client : client + " · " + os;
    }

    private static String os(String ua) {
        if (ua.contains("android")) {
            return "Android";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) {
            return "iOS";
        }
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "macOS";
        }
        if (ua.contains("cros")) {
            return "ChromeOS";
        }
        if (ua.contains("linux")) {
            return "Linux";
        }
        return null;
    }

    private static String client(String ua) {
        // Android WebView ("; wv)") and iOS WKWebView (no "safari/" token) = the Capacitor app
        if (ua.contains("; wv)") || (ua.contains("iphone") || ua.contains("ipad")) && ua.contains("applewebkit")
                && !ua.contains("safari/")) {
            return "Nomi";
        }
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("samsungbrowser/")) {
            return "Samsung Internet";
        }
        if (ua.contains("yabrowser/")) {
            return "Yandex";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return null;
    }
}
