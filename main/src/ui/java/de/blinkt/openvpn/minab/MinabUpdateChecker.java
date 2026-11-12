/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabUpdateChecker: lightweight check for a newer Minab release
 * on GitHub (public releases API, no auth). It never blocks the UI:
 * the HTTP call runs on a background thread and the callback is
 * posted to the main thread. The result is cached for 24 hours so a
 * launch check does not hit the network every time; a manual check
 * (force=true) always goes to the network. Nothing is uploaded and
 * no personal data is sent — the request only reads the latest
 * public release tag of meiyou313/minab.
 *
 * Version comparison is numeric on the dotted parts ("0.4" vs
 * "0.10"); a "-beta" suffix is ignored for the comparison, so
 * 0.4-beta and 0.4 count as the same version.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import de.blinkt.openvpn.BuildConfig;

public final class MinabUpdateChecker {
    /** Where the user downloads a newer version. */
    public static final String RELEASES_URL = "https://github.com/meiyou313/minab/releases";
    private static final String API_URL = "https://api.github.com/repos/meiyou313/minab/releases/latest";

    private static final String PREFS_NAME = "MinabUpdateCheck";
    private static final String KEY_LATEST_TAG = "latest_tag";
    private static final String KEY_CHECKED_AT = "checked_at";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // 24 h
    private static final int HTTP_TIMEOUT_MS = 5000;

    /** latestTag is null when the check failed (offline, API error). */
    public interface Callback {
        void onResult(String latestTag, boolean updateAvailable);
    }

    private MinabUpdateChecker() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Compare two version strings numerically. True when latest is newer. */
    public static boolean isNewer(String current, String latest) {
        if (current == null || latest == null) return false;
        String[] cur = normalise(current).split("\\.");
        String[] lat = normalise(latest).split("\\.");
        int len = Math.max(cur.length, lat.length);
        for (int i = 0; i < len; i++) {
            int c = i < cur.length ? parsePart(cur[i]) : 0;
            int l = i < lat.length ? parsePart(lat[i]) : 0;
            if (l > c) return true;
            if (l < c) return false;
        }
        return false;
    }

    private static String normalise(String version) {
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        return v;
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Open the releases page in the browser. */
    public static void openReleases(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /**
     * Check for a newer release. With force=false a result cached
     * less than 24 h ago is returned without network. The callback
     * always runs on the main thread.
     */
    public static void check(final Context context, final boolean force, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String latestTag = null;
            if (!force) {
                SharedPreferences p = prefs(appContext);
                long checkedAt = p.getLong(KEY_CHECKED_AT, 0);
                if (System.currentTimeMillis() - checkedAt < CHECK_INTERVAL_MS) {
                    latestTag = p.getString(KEY_LATEST_TAG, null);
                    final String cached = latestTag;
                    mainHandler.post(() -> callback.onResult(
                            cached, cached != null && isNewer(BuildConfig.VERSION_NAME, cached)));
                    return;
                }
            }
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setConnectTimeout(HTTP_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "Minab");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                    reader.close();
                    latestTag = new JSONObject(body.toString()).optString("tag_name", null);
                    if (latestTag != null && latestTag.isEmpty()) latestTag = null;
                }
                conn.disconnect();
            } catch (Exception ignored) {
                // Offline / API error: keep the last cached tag, if any.
                latestTag = prefs(appContext).getString(KEY_LATEST_TAG, null);
            }
            if (latestTag != null) {
                prefs(appContext).edit()
                        .putString(KEY_LATEST_TAG, latestTag)
                        .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                        .apply();
            }
            final String found = latestTag;
            mainHandler.post(() -> callback.onResult(
                    found, found != null && isNewer(BuildConfig.VERSION_NAME, found)));
        }).start();
    }
}
