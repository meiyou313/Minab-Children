/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabWidgetProvider (v0.9): the home-screen widget for quick
 * connect/disconnect. It shows the current state ("Tap to connect"
 * / "Connected — <server>") and the connected server; tapping it
 * toggles: connect the last-used server when off, disconnect (no
 * confirmation dialog) when on.
 *
 * The widget does not poll (updatePeriodMillis=0). It is refreshed
 * whenever the app sees a connection-state change (see
 * VPNProfileList.updateState and MainActivity.onResume) and right
 * after a tap. If the VPN drops while the app is closed, the widget
 * can be stale until the app is opened again — the widget itself
 * cannot listen to VpnStatus while the process is gone.
 */

package de.blinkt.openvpn.minab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.VpnStatus

class MinabWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            toggle(context)
            updateAllWidgets(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun toggle(context: Context) {
        try {
            if (VpnStatus.isVPNActive()) {
                MinabVpnControl.disconnect(context)
            } else {
                val appContext = context.applicationContext
                val target = MinabVpnControl.lastUsedProfile(appContext)
                    ?: MinabVpnControl.bestProfile(appContext)
                if (target != null) MinabVpnControl.connect(appContext, target)
            }
        } catch (ignored: Exception) {
        }
    }

    companion object {
        const val ACTION_TOGGLE = "de.blinkt.openvpn.minab.WIDGET_TOGGLE"

        /** Refresh every Minab widget instance. Safe to call from anywhere. */
        fun updateAllWidgets(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context.applicationContext)
                val component = ComponentName(context.applicationContext, MinabWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    updateWidgets(context.applicationContext, manager, ids)
                }
            } catch (ignored: Exception) {
            }
        }

        private fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val active = try {
                VpnStatus.isVPNActive()
            } catch (e: Exception) {
                false
            }
            val serverName = if (active) {
                try {
                    val uuid = VpnStatus.getLastConnectedVPNProfile()
                    val profile = uuid?.let {
                        de.blinkt.openvpn.core.ProfileManager.getIfPresent(context, it)
                    }
                    profile?.mName
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            for (id in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.minab_widget)
                if (active) {
                    views.setTextViewText(
                        R.id.minab_widget_state,
                        context.getString(R.string.minab_status_connected)
                    )
                    views.setTextViewText(
                        R.id.minab_widget_server,
                        serverName ?: ""
                    )
                } else {
                    views.setTextViewText(
                        R.id.minab_widget_state,
                        context.getString(R.string.minab_tap_to_connect)
                    )
                    views.setTextViewText(R.id.minab_widget_server, "")
                }
                val toggle = Intent(context, MinabWidgetProvider::class.java)
                toggle.action = ACTION_TOGGLE
                val pending = PendingIntent.getBroadcast(
                    context, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.minab_widget_root, pending)
                try {
                    manager.updateAppWidget(id, views)
                } catch (ignored: Exception) {
                }
            }
        }
    }
}
