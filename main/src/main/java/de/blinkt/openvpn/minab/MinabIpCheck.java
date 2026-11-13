/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabIpCheck (v0.9): after connecting, show the current public IP
 * and country. One small HTTPS request to ipinfo.io/json through the
 * tunnel (the VPN is the default route, so the request goes through
 * it with no special handling), 5 s timeout, on a worker thread — it
 * never blocks the UI. A failure returns null and the UI shows "—".
 * No speed test lives here on purpose: the user asked for the IP and
 * country only (v0.9 follow-up C).
 */

package de.blinkt.openvpn.minab;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

public final class MinabIpCheck {
    private static final String IP_URL = "https://ipinfo.io/json";
    private static final int TIMEOUT_MS = 5000;

    /** IP + country code, or null when the check failed. */
    public static final class IpInfo {
        public final String ip;
        /** Two-letter country code (e.g. "DE"), may be empty. */
        public final String countryCode;

        public IpInfo(String ip, String countryCode) {
            this.ip = ip;
            this.countryCode = countryCode == null ? "" : countryCode;
        }
    }

    public interface Callback {
        void onResult(IpInfo info);
    }

    private MinabIpCheck() {
    }

    /** Runs off the UI thread; the callback always runs on the main thread. */
    public static void check(final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            IpInfo info = null;
            HttpsURLConnection conn = null;
            try {
                conn = (HttpsURLConnection) new URL(IP_URL).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "Minab");
                if (conn.getResponseCode() == HttpsURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                    reader.close();
                    JSONObject json = new JSONObject(body.toString());
                    String ip = json.optString("ip", "");
                    String country = json.optString("country", "");
                    if (!ip.isEmpty()) info = new IpInfo(ip, country);
                }
            } catch (Exception ignored) {
                // Offline / service down / tunnel not carrying traffic:
                // the caller shows "—". Never fatal.
            } finally {
                if (conn != null) conn.disconnect();
            }
            final IpInfo result = info;
            mainHandler.post(() -> callback.onResult(result));
        }).start();
    }
}
