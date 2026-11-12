/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * AutoCategory: best-effort automatic categorisation of a profile by
 * country, from its remote host. It NEVER throws and NEVER blocks an
 * import: every failure returns null and the caller keeps the manual
 * category ("Uncategorized" unless the user picked one).
 *
 * Order of attempts:
 *   1. Offline heuristic — a 2-letter country code in the profile name,
 *      file name or remote hostname (e.g. "us-01.vpn.example.com",
 *      "de.ovpn", "nl-server"). This needs no network at all.
 *   2. Online lookup — resolve the remote host to an IP (background
 *      thread only), then GET https://ip-api.com/json/<ip>?fields=status,
 *      countryCode (3 s connect + 3 s read timeout). Result is cached in
 *      SharedPreferences ("MinabGeoCache", ip -> "CC|expiry") for 30 days.
 *
 * Honest limits (also documented in README.md):
 *   - ip-api.com free endpoint is rate-limited (~45 requests/minute from
 *     one IP). Bulk imports therefore do the lookup in chunks of 8 in
 *     parallel with no artificial delay (v0.3; v0.2 paused 1 s per
 *     profile), and any 429 / error simply leaves the profile
 *     uncategorised instead of failing.
 *   - Offline, or when DNS fails, only step 1 runs.
 *   - A hostname can be a CDN / anycast address whose country is not the
 *     country in its name; the heuristic is a guess, the user can always
 *     re-assign the category by hand (long-press in the list, or the
 *     Category field in the profile editor).
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoCategory {
    private static final String GEO_CACHE_PREFS = "MinabGeoCache";
    private static final long CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    private static final int HTTP_TIMEOUT_MS = 3000;

    /** Non-letter/digit boundaries around a 2-letter token, e.g. "-us-", "_de.", ".nl-". */
    private static final Pattern CODE_TOKEN =
            Pattern.compile("(?:^|[^a-z0-9])([a-z]{2})(?:[^a-z0-9]|$)");
    /** A 2-letter TLD at the end of a hostname, e.g. example.de */
    private static final Pattern TLD =
            Pattern.compile("\\.([a-z]{2})$");

    private AutoCategory() {
    }

    /**
     * Best-effort country category for a profile. Returns a country name
     * (e.g. "Germany") or null when nothing could be determined.
     * Safe to call from a background thread only — it may do DNS and HTTP.
     */
    public static String guessCategory(Context context, String profileName,
                                       String fileName, String remoteHost) {
        // 1. Offline heuristic, cheapest first.
        String code = codeFromText(profileName);
        if (code == null) code = codeFromText(fileName);
        if (code == null) code = codeFromText(remoteHost);
        if (code == null) code = tldCode(remoteHost);
        if (code != null) return CountryCodes.nameForCode(code);

        // 2. Online lookup, only if we have a host to resolve.
        if (context == null || TextUtils.isEmpty(remoteHost)) return null;
        try {
            String ip = resolveIp(remoteHost);
            if (ip == null) return null;
            String cached = cachedCountryCode(context, ip);
            if (cached != null) return CountryCodes.nameForCode(cached);
            String fetched = lookupCountryCode(ip);
            if (fetched != null) {
                cacheCountryCode(context, ip, fetched);
                return CountryCodes.nameForCode(fetched);
            }
        } catch (Exception ignored) {
            // Any failure (no network, DNS, HTTP, JSON) -> no auto category.
        }
        return null;
    }

    /** Offline-only part, usable from unit tests without a Context. */
    public static String guessOffline(String profileName, String fileName, String remoteHost) {
        String code = codeFromText(profileName);
        if (code == null) code = codeFromText(fileName);
        if (code == null) code = codeFromText(remoteHost);
        if (code == null) code = tldCode(remoteHost);
        return code == null ? null : CountryCodes.nameForCode(code);
    }

    private static String codeFromText(String text) {
        if (TextUtils.isEmpty(text)) return null;
        String lower = text.toLowerCase(Locale.US);
        Matcher m = CODE_TOKEN.matcher(lower);
        while (m.find()) {
            String candidate = m.group(1);
            if (CountryCodes.isKnownCode(candidate)) return candidate;
        }
        return null;
    }

    private static String tldCode(String host) {
        if (TextUtils.isEmpty(host)) return null;
        Matcher m = TLD.matcher(host.toLowerCase(Locale.US).trim());
        if (m.find() && CountryCodes.isKnownCode(m.group(1))) return m.group(1);
        return null;
    }

    private static String resolveIp(String host) {
        try {
            // Already an IP literal: no DNS needed.
            if (host.matches("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$") || host.contains(":")) {
                return host;
            }
            InetAddress addr = InetAddress.getByName(host);
            return addr == null ? null : addr.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cachedCountryCode(Context context, String ip) {
        SharedPreferences prefs = context.getSharedPreferences(GEO_CACHE_PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(ip, null);
        if (raw == null) return null;
        String[] parts = raw.split("\\|", 2);
        if (parts.length != 2) return null;
        try {
            long expiry = Long.parseLong(parts[1]);
            if (System.currentTimeMillis() > expiry) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return parts[0];
    }

    private static void cacheCountryCode(Context context, String ip, String code) {
        long expiry = System.currentTimeMillis() + CACHE_TTL_MS;
        context.getSharedPreferences(GEO_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(ip, code + "|" + expiry).apply();
    }

    /**
     * ip-api.com free endpoint. No API key. Fields are limited to what we
     * need. Returns the lower-case country code, or null on any failure
     * (including HTTP 429 rate-limiting).
     */
    private static String lookupCountryCode(String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://ip-api.com/json/" + ip + "?fields=status,countryCode");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            if (!"success".equals(json.optString("status"))) return null;
            String code = json.optString("countryCode", null);
            if (TextUtils.isEmpty(code)) return null;
            code = code.toLowerCase(Locale.US);
            return CountryCodes.isKnownCode(code) ? code : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
