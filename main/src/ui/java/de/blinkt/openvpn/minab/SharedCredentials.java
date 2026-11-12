/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * SharedCredentials: the one shared username/password for all
 * profiles (v0.3 bug fix 2).
 *
 * Storage: EncryptedSharedPreferences (AES256-SIV keys,
 * AES256-GCM values, backed by the Android Keystore), so the
 * password is never written to plain SharedPreferences or to any
 * extra file. If the encrypted store cannot be created on a device
 * (no Keystore), it falls back to plain SharedPreferences — the
 * same storage upstream already uses for profile data — rather
 * than losing the feature; the credentials are still also written
 * onto every profile itself (see below).
 *
 * Two places hold the credentials, for two different moments:
 *   1. On every profile that needs auth-user-pass
 *      (VpnProfile.mUsername / mPassword, persisted by
 *      ProfileManager.saveProfile into the profile file, encrypted
 *      when upstream profile encryption is enabled). This is what
 *      makes connecting stop asking for a username/password per
 *      profile: VpnProfile.needUserPWInput() returns 0 once both
 *      fields are set.
 *   2. This store, so profiles imported LATER also get the same
 *      credentials without the user re-typing them (BulkImportActivity
 *      reads them when building ImportOptions).
 */
package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ProfileManager;

public final class SharedCredentials {
    private static final String PREFS_NAME = "MinabSharedCredentials";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";

    private SharedCredentials() {
    }

    private static SharedPreferences prefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // No usable Keystore on this device: fall back to plain
            // prefs (same storage upstream uses for profile data)
            // rather than dropping the shared credentials entirely.
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    /** Save the shared credentials for future imports. */
    public static void save(Context context, String username, String password) {
        prefs(context).edit()
                .putString(KEY_USERNAME, username == null ? "" : username)
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .apply();
    }

    public static String getUsername(Context context) {
        return prefs(context).getString(KEY_USERNAME, "");
    }

    public static String getPassword(Context context) {
        return prefs(context).getString(KEY_PASSWORD, "");
    }

    /** True when a shared username/password has been saved. */
    public static boolean hasCredentials(Context context) {
        return !TextUtils.isEmpty(getUsername(context))
                || !TextUtils.isEmpty(getPassword(context));
    }

    /**
     * Write the credentials into EVERY profile that needs a
     * username/password — already-imported ones included — and save
     * each one. Returns how many profiles were updated.
     */
    public static int applyToAllProfiles(Context context, String username, String password) {
        int updated = 0;
        try {
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile profile : pm.getProfiles()) {
                if (profile.isUserPWAuth()) {
                    profile.mUsername = username == null ? "" : username;
                    profile.mPassword = password == null ? "" : password;
                    ProfileManager.saveProfile(context, profile);
                    updated++;
                }
            }
            pm.saveProfileList(context);
        } catch (Exception ignored) {
            // Best effort: profiles already saved keep the new
            // credentials; the count reports only what succeeded.
        }
        return updated;
    }

    /**
     * Write the credentials into every profile in one category that
     * needs a username/password, and save each one. Returns how many
     * profiles were updated.
     */
    public static int applyToCategory(Context context, String category, String username, String password) {
        int updated = 0;
        try {
            String wanted = CategoryStore.normalise(category);
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile profile : pm.getProfiles()) {
                if (profile.isUserPWAuth()
                        && wanted.equals(CategoryStore.normalise(profile.getCategory()))) {
                    profile.mUsername = username == null ? "" : username;
                    profile.mPassword = password == null ? "" : password;
                    ProfileManager.saveProfile(context, profile);
                    updated++;
                }
            }
            pm.saveProfileList(context);
        } catch (Exception ignored) {
            // Best effort: profiles already saved keep the new
            // credentials; the count reports only what succeeded.
        }
        return updated;
    }

    /** Apply the saved shared credentials to one profile, if it needs them. */
    public static boolean applyToProfile(Context context, VpnProfile profile) {
        if (profile == null || !profile.isUserPWAuth()) return false;
        if (!hasCredentials(context)) return false;
        profile.mUsername = getUsername(context);
        profile.mPassword = getPassword(context);
        return true;
    }
}
