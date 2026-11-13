/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabVpnControl (v0.9): one place for "connect this profile" /
 * "disconnect now" used by the Home orb, the best-server button,
 * failover and the home-screen widget, so they all take the same
 * path (LaunchVPN, staying on Home, no log window — see
 * VPNProfileList.startVPN). Disconnect uses the bind-and-stopVPN
 * pattern (the Quick Settings tile pattern) so no confirmation
 * dialog appears; it is also how the widget disconnects.
 *
 * bestServers() is the shared ranking for "best server" and
 * failover: servers that passed the real test first (fastest real
 * delay), then servers with a ping (fastest ping), then the rest by
 * name. Favorites are NOT boosted here — this ranking is about
 * health, and the list UI already puts favorites first.
 */

package de.blinkt.openvpn.minab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus

object MinabVpnControl {

    /** Connect a profile — the same LaunchVPN path as Home's startVPN. */
    fun connect(context: Context, profile: VpnProfile) {
        try {
            ProfileManager.saveProfile(context, profile)
        } catch (e: Exception) {
        }
        val intent = Intent(context, LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "minab")
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
        intent.action = Intent.ACTION_MAIN
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    /** Drop the current VPN without any confirmation dialog. Safe from any thread. */
    fun disconnect(context: Context) {
        MinabFailover.noteUserDisconnect()
        val appContext = context.applicationContext
        val intent = Intent(appContext, OpenVPNService::class.java)
        intent.action = OpenVPNService.START_SERVICE
        try {
            appContext.bindService(intent, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    try {
                        IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false)
                    } catch (e: RemoteException) {
                        VpnStatus.logException(e)
                    }
                    try {
                        appContext.unbindService(this)
                    } catch (ignored: Exception) {
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }, Context.BIND_AUTO_CREATE)
        } catch (ignored: Exception) {
            // Service not running (nothing to drop) or context gone.
        }
    }

    /** Profiles ranked best-first for "best server" / failover. */
    fun bestServers(context: Context, excludeUuids: Set<String> = emptySet()): List<VpnProfile> {
        val all = try {
            ProfileManager.getInstance(context).getProfiles().toList()
        } catch (e: Exception) {
            return emptyList()
        }
        val candidates = all.filter { !excludeUuids.contains(it.getUUIDString()) }
        val byReal = candidates.filter { it.mLastRealMs > 0 }.sortedBy { it.mLastRealMs }
        val byPing = candidates.filter { it.mLastRealMs <= 0 && it.mLastPingMs > 0 }
            .sortedBy { it.mLastPingMs }
        val rest = candidates.filter { it.mLastRealMs <= 0 && it.mLastPingMs <= 0 }
            .sortedBy { it.mName ?: "" }
        return byReal + byPing + rest
    }

    /** The single best server (best real test, else best ping), or null when none exists. */
    fun bestProfile(context: Context, profiles: List<VpnProfile>? = null): VpnProfile? {
        val ranked = if (profiles == null) {
            bestServers(context)
        } else {
            val byReal = profiles.filter { it.mLastRealMs > 0 }.sortedBy { it.mLastRealMs }
            val byPing = profiles.filter { it.mLastRealMs <= 0 && it.mLastPingMs > 0 }
                .sortedBy { it.mLastPingMs }
            val rest = profiles.filter { it.mLastRealMs <= 0 && it.mLastPingMs <= 0 }
                .sortedBy { it.mName ?: "" }
            byReal + byPing + rest
        }
        // "Best" only means something when at least one server was
        // actually tested; an untested list has no best server.
        val tested = ranked.firstOrNull { it.mLastRealMs > 0 || it.mLastPingMs > 0 }
        return tested
    }

    /** The profile the orb / widget connects: last-used, else first. */
    fun lastUsedProfile(context: Context): VpnProfile? {
        val all = try {
            ProfileManager.getInstance(context).getProfiles().toList()
        } catch (e: Exception) {
            return null
        }
        if (all.isEmpty()) return null
        return all.maxByOrNull { it.mLastUsed } ?: all.firstOrNull()
    }
}
