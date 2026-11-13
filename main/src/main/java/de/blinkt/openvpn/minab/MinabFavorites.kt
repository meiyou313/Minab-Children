/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabFavorites (v0.9): starred servers. A favorite stays at the
 * top of the Home list (above the ping/real sort, which still orders
 * the favorites among themselves and the rest among themselves).
 * Stored as a set of profile UUIDs in "MinabPrefs", so it survives
 * restarts and is included in a full backup (see MinabBackup).
 */

package de.blinkt.openvpn.minab

import android.content.Context

object MinabFavorites {
    private const val PREFS = "MinabPrefs"
    private const val KEY = "minab_favorites"

    /** UUIDs of the starred profiles (never null). */
    @JvmStatic
    fun getAll(context: Context): Set<String> {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet()) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    @JvmStatic
    fun isFavorite(context: Context, uuid: String?): Boolean {
        if (uuid == null) return false
        return getAll(context).contains(uuid)
    }

    /** Toggles the star; returns the new state (true = now a favorite). */
    @JvmStatic
    fun toggle(context: Context, uuid: String): Boolean {
        val current = getAll(context).toMutableSet()
        val nowFavorite = if (current.contains(uuid)) {
            current.remove(uuid)
            false
        } else {
            current.add(uuid)
            true
        }
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, current).apply()
        } catch (e: Exception) {
            // Prefs unavailable: the in-memory toggle is lost, but the
            // list refresh below still shows the tap's effect for now.
        }
        return nowFavorite
    }

    /** Replace the whole set (used by backup restore). */
    @JvmStatic
    fun setAll(context: Context, uuids: Set<String>) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, uuids.toSet()).apply()
        } catch (e: Exception) {
        }
    }
}
