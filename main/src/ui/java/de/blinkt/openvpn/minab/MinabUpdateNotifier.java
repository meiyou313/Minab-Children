/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabUpdateNotifier (v0.9): the system-notification half of the
 * update notice. The Home banner (VPNProfileList.checkForUpdates)
 * already shows when MinabUpdateChecker finds a newer release; this
 * posts the same finding as a mobile notification, so the user sees
 * it even without opening the app.
 *
 * Accuracy is the point: a notification is only posted when the
 * checker positively finds a genuinely newer tag (it already
 * compares versions numerically), and at most once per version —
 * the last-notified tag is stored in "MinabPrefs", so an update is
 * never announced twice. Tapping the notification opens the
 * releases page. When notifications are off, post() is a silent
 * no-op (the permission is requested at first launch; see
 * VPNProfileList.checkForNotificationPermission).
 */

package de.blinkt.openvpn.minab;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import de.blinkt.openvpn.R;

public final class MinabUpdateNotifier {
    private static final String CHANNEL_ID = "minab_update_channel";
    private static final int NOTIFICATION_ID = 9001;
    private static final String PREFS = "MinabPrefs";
    private static final String KEY_NOTIFIED_TAG = "minab_update_notified_tag";

    private MinabUpdateNotifier() {
    }

    /**
     * Check (cached 24 h, like the Home banner) and notify once per
     * newer version. Safe to call on every Home creation: the checker
     * cache and the notified-tag store make it cheap and quiet.
     */
    public static void maybeNotifyUpdate(final Context context) {
        final Context appContext = context.getApplicationContext();
        MinabUpdateChecker.check(appContext, false, (latestTag, available) -> {
            if (!available || latestTag == null) return;
            SharedPreferences prefs =
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (latestTag.equals(prefs.getString(KEY_NOTIFIED_TAG, null))) return;
            postNotification(appContext, latestTag);
            prefs.edit().putString(KEY_NOTIFIED_TAG, latestTag).apply();
        });
    }

    private static void postNotification(Context context, String latestTag) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.minab_update_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT);
                manager.createNotificationChannel(channel);
            }
            Intent releases = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(MinabUpdateChecker.RELEASES_URL));
            releases.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pending = PendingIntent.getActivity(
                    context, 0, releases,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_sw)
                    .setContentTitle(context.getString(R.string.minab_update_notification_title))
                    .setContentText(context.getString(R.string.minab_update_available, latestTag))
                    .setContentIntent(pending)
                    .setAutoCancel(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {
            // Notifications off / SecurityException on Android 13+:
            // the Home banner still shows the update. Never fatal.
        }
    }
}
