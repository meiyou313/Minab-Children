/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabFailover (v0.9): when the connected server drops, connect to
 * the next healthy server by itself — no test, just the switch, and
 * a clear message with the new server's name (toast + notification)
 * so the user notices.
 *
 * How it decides the drop was NOT the user:
 *   - The user disconnecting from the app UI (orb / server row /
 *     widget) records the moment (noteUserDisconnect); a drop within
 *     15 s of that is the user's own disconnect, not a failure.
 *   - A real test running (RealTester) disconnects servers itself;
 *     failover stays out while one runs.
 *   - OpenVPN retries a dropped connection by itself first
 *     (connect-retry-max is -1, i.e. forever), so a drop does not
 *     fail over at once: the switch is scheduled 20 s after the
 *     connection leaves CONNECTED, and only runs when the VPN is
 *     still down then. A brief blip that OpenVPN heals by itself
 *     never triggers a switch.
 * It never loops forever: at most 3 alternative servers per drop
 * event, then it stops and says so. The alternatives come from the
 * last test results, best first (see MinabVpnControl.bestServers);
 * no test runs as part of a switch.
 *
 * Limits, honestly: the state listener lives in MainActivity, so
 * failover only runs while the app is open (in the foreground). If
 * the app is closed when the server drops, nothing switches — the
 * user reconnects by hand. And a disconnect tapped in the system
 * notification shade cannot be told apart from a drop (the service
 * handles that button itself); in that case failover may reconnect
 * once, and it still stops after 3 tries.
 */

package de.blinkt.openvpn.minab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.VpnStatus

object MinabFailover {
    private const val PREF_FAILOVER = "minab_failover"
    private const val USER_DISCONNECT_WINDOW_MS = 15_000L
    /** Wait this long after the drop before switching (lets OpenVPN heal a blip itself). */
    private const val FAILOVER_DELAY_MS = 20_000L
    private const val MAX_ALTERNATIVES = 3
    /** A connection that stays up this long resets the attempt count (a later drop is new). */
    private const val STABLE_RESET_MS = 60_000L
    private const val CHANNEL_ID = "minab_connection_channel"
    private const val NOTIFICATION_ID = 9002

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var lastConnectedUuid: String? = null
    private var userDisconnectAt: Long = Long.MIN_VALUE
    private var failoverPending: Boolean = false
    private var failoverAttempts: Int = 0
    private var lastAttemptAt: Long = Long.MIN_VALUE
    private var triedUuids: MutableSet<String> = mutableSetOf()
    private var droppedUuid: String? = null

    private val failoverRunnable = Runnable { runFailover() }

    fun isEnabled(context: Context): Boolean {
        return try {
            Preferences.getDefaultSharedPreferences(context).getBoolean(PREF_FAILOVER, true)
        } catch (e: Exception) {
            true
        }
    }

    /** Call when the user disconnects from the app UI (orb / row / widget). */
    fun noteUserDisconnect() {
        userDisconnectAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(failoverRunnable)
        failoverPending = false
    }

    /**
     * Feed every connection-state change here (MainActivity's state
     * listener). Must be called on the main thread (VpnStatus posts
     * state updates there).
     */
    fun onStateChanged(context: Context, level: ConnectionStatus?) {
        appContext = context.applicationContext
        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAttemptAt > STABLE_RESET_MS) {
                failoverAttempts = 0
                triedUuids.clear()
            }
            lastConnectedUuid = VpnStatus.getLastConnectedVPNProfile()
            handler.removeCallbacks(failoverRunnable)
            failoverPending = false
            return
        }
        // Not connected. Only a drop counts: we must have been
        // connected in this session, and the user must not have
        // disconnected just now.
        val wasConnectedUuid = lastConnectedUuid ?: return
        if (RealTester.isRunning()) return
        val now = SystemClock.elapsedRealtime()
        if (now - userDisconnectAt < USER_DISCONNECT_WINDOW_MS) return
        if (failoverPending) return
        droppedUuid = wasConnectedUuid
        triedUuids.add(wasConnectedUuid)
        failoverPending = true
        handler.postDelayed(failoverRunnable, FAILOVER_DELAY_MS)
    }

    private fun runFailover() {
        failoverPending = false
        val context = appContext ?: return
        if (!isEnabled(context)) return
        if (VpnStatus.isVPNActive()) return // OpenVPN healed it by itself.
        if (RealTester.isRunning()) return
        val now = SystemClock.elapsedRealtime()
        if (now - userDisconnectAt < USER_DISCONNECT_WINDOW_MS) return
        if (failoverAttempts >= MAX_ALTERNATIVES) {
            notifyGaveUp(context)
            failoverAttempts = 0
            triedUuids.clear()
            return
        }
        val next = MinabVpnControl.bestServers(context, triedUuids).firstOrNull()
        if (next == null) {
            notifyGaveUp(context)
            failoverAttempts = 0
            triedUuids.clear()
            return
        }
        // Switch, no test: just connect to the next healthy server
        // from the last test results.
        triedUuids.add(next.getUUIDString())
        failoverAttempts++
        lastAttemptAt = now
        MinabVpnControl.connect(context, next)
        val message = context.getString(R.string.minab_failover_switched, next.mName)
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
        }
        postSwitchNotification(context, message)
    }

    private fun notifyGaveUp(context: Context) {
        val message = context.getString(R.string.minab_failover_gave_up)
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
        }
        postSwitchNotification(context, message)
    }

    private fun postSwitchNotification(context: Context, message: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.minab_connection_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_sw)
                .setContentTitle(context.getString(R.string.app))
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (ignored: Exception) {
        }
    }
}
