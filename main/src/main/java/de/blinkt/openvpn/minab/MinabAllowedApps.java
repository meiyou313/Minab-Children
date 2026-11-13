/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabAllowedApps (v0.9): the shared store for per-app VPN (split
 * tunneling), used by the Settings picker (MinabAllowedAppsActivity),
 * by bulk import (new profiles get the same choice), and by full
 * backup/restore (MinabBackup).
 *
 * It only stores the choice. The choice itself lives on every profile
 * in the upstream fields VpnProfile.mAllowedAppsVpn (the package set)
 * and mAllowedAppsVpnAreDisallowed (true = the set bypasses the VPN,
 * false = only the set uses the VPN); OpenVPNService applies those
 * fields through VpnService.Builder. An empty set with "disallowed"
 * (the default) means every app uses the VPN, exactly as before.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ProfileManager;

public final class MinabAllowedApps {
    private static final String PREFS = "MinabPrefs";
    /** Package names chosen in the picker (empty = no choice yet). */
    public static final String KEY_APPS = "minab_allowed_apps";
    /** True = the set bypasses the VPN ("all except these"); false = only the set uses it. */
    public static final String KEY_ARE_DISALLOWED = "minab_allowed_apps_are_disallowed";

    private MinabAllowedApps() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True once the user has saved a choice in the picker. */
    public static boolean hasStoredChoice(Context context) {
        return prefs(context).contains(KEY_APPS);
    }

    public static Set<String> getStoredApps(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_APPS, null);
        return stored == null ? Collections.<String>emptySet() : new HashSet<>(stored);
    }

    public static boolean getStoredAreDisallowed(Context context) {
        return prefs(context).getBoolean(KEY_ARE_DISALLOWED, true);
    }

    /** Save the picker's choice (also applied to all profiles by the caller). */
    public static void saveChoice(Context context, Set<String> apps, boolean areDisallowed) {
        prefs(context).edit()
                .putStringSet(KEY_APPS, new HashSet<>(apps))
                .putBoolean(KEY_ARE_DISALLOWED, areDisallowed)
                .apply();
    }

    /** Write the stored choice onto one profile (no save; the caller saves). */
    public static void applyStoredToProfile(Context context, VpnProfile profile) {
        if (!hasStoredChoice(context)) return;
        profile.mAllowedAppsVpn = new HashSet<>(getStoredApps(context));
        profile.mAllowedAppsVpnAreDisallowed = getStoredAreDisallowed(context);
    }

    /** Write the given choice onto every profile and save them. Returns the count. */
    public static int applyToAllProfiles(Context context, Set<String> apps, boolean areDisallowed) {
        int count = 0;
        ProfileManager pm = ProfileManager.getInstance(context);
        for (VpnProfile profile : pm.getProfiles()) {
            profile.mAllowedAppsVpn = new HashSet<>(apps);
            profile.mAllowedAppsVpnAreDisallowed = areDisallowed;
            ProfileManager.saveProfile(context, profile);
            count++;
        }
        return count;
    }
}
