/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabBackup (v0.9): the COMPLETE backup. The plain .ovpn zip
 * export already existed (Configs "Back up configs" / "Share as
 * zip" wrote every profile as one .ovpn file), but it lost the
 * groups, the per-profile usernames/passwords, the favorites and
 * the settings — restoring it meant re-grouping everything by hand.
 *
 * A full backup is the same zip of .ovpn files PLUS one small
 * metadata entry, "minab-backup.json":
 *   { "version": 1,
 *     "categories": ["Germany", ...],
 *     "favorites": [<profile uuid>, ...],
 *     "settings": { "sortMode": 1, "showSpeed": true, "failover": true,
 *                   "allowedApps": [...], "allowedAppsAreDisallowed": true },
 *     "profiles": [ { "uuid", "name", "remoteHost", "remotePort",
 *                      "category", "username", "password" } ] }
 * Restoring reads the zip back: the .ovpn files are imported the
 * usual way (duplicates skipped), then every metadata entry is
 * applied to the matching profile — matched by UUID when the
 * profile is still on this phone, otherwise by name + remote
 * host/port — so groups, credentials, favorites and settings come
 * back exactly as they were, with no re-grouping. Favorites are
 * translated the same way (backup UUID -> metadata entry -> name +
 * remote -> restored profile), so they survive even when the
 * profile UUIDs changed (e.g. a restore on a new phone).
 *
 * SECURITY: the metadata carries the per-profile usernames and
 * passwords in plaintext (the user explicitly asked for credentials
 * in the backup), so a full-backup file must be kept private — it
 * is as sensitive as the .ovpn files plus their logins. The plain
 * .ovpn-only export (share) is unchanged and carries no passwords.
 * The zip is capped at 64 MB total and 200 files / 2 MB per entry,
 * the same limits as bulk import, so a hostile file cannot exhaust
 * memory.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.Preferences;
import de.blinkt.openvpn.core.ProfileManager;

public final class MinabBackup {
    /** Name of the metadata entry inside a full backup zip. */
    public static final String META_ENTRY = "minab-backup.json";
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_FILES = 200;
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;

    /** Result of a restore. wasBackup is false when the zip had no metadata. */
    public static final class RestoreResult {
        public boolean wasBackup;
        public int imported;
        public int skippedDuplicates;
        public int failed;
        public int profilesRestored;
        public String error;
    }

    private MinabBackup() {
    }

    // --- Writing ---

    /** Plain .ovpn-only zip (the pre-v0.9 backup/share format, unchanged). */
    public static int writeProfilesZip(Context context, List<VpnProfile> profiles,
                                       OutputStream out) throws Exception {
        int count = 0;
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (VpnProfile profile : profiles) {
                try {
                    String name = profile.mName
                            .replaceAll("[^A-Za-z0-9._-]", "_");
                    if (name.isEmpty()) name = "config";
                    zip.putNextEntry(new ZipEntry(name + ".ovpn"));
                    zip.write(profile.getConfigFile(context, false).getBytes("UTF-8"));
                    zip.closeEntry();
                    count++;
                } catch (Exception ignored) {
                }
            }
        } finally {
            zip.close();
        }
        return count;
    }

    /**
     * Full backup: the .ovpn files plus the metadata entry.
     * Returns the number of .ovpn entries written.
     */
    public static int writeFullBackup(Context context, List<VpnProfile> profiles,
                                      OutputStream out) throws Exception {
        int count = 0;
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (VpnProfile profile : profiles) {
                try {
                    String name = profile.mName
                            .replaceAll("[^A-Za-z0-9._-]", "_");
                    if (name.isEmpty()) name = "config";
                    zip.putNextEntry(new ZipEntry(name + ".ovpn"));
                    zip.write(profile.getConfigFile(context, false).getBytes("UTF-8"));
                    zip.closeEntry();
                    count++;
                } catch (Exception ignored) {
                }
            }
            zip.putNextEntry(new ZipEntry(META_ENTRY));
            zip.write(buildMetadata(context, profiles).getBytes("UTF-8"));
            zip.closeEntry();
        } finally {
            zip.close();
        }
        return count;
    }

    private static String buildMetadata(Context context, List<VpnProfile> profiles)
            throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("version", 1);
        meta.put("exportedAt", System.currentTimeMillis());
        JSONArray categories = new JSONArray();
        for (String c : CategoryStore.getCategories(context)) categories.put(c);
        meta.put("categories", categories);
        JSONArray favorites = new JSONArray();
        for (String uuid : MinabFavorites.getAll(context)) favorites.put(uuid);
        meta.put("favorites", favorites);
        JSONObject settings = new JSONObject();
        SharedPreferences minabPrefs =
                context.getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE);
        settings.put("sortMode", minabPrefs.getInt("minab_sort_mode", 0));
        SharedPreferences defPrefs = Preferences.getDefaultSharedPreferences(context);
        settings.put("showSpeed", defPrefs.getBoolean("minab_show_speed_notification", true));
        settings.put("failover", defPrefs.getBoolean("minab_failover", true));
        JSONArray allowedApps = new JSONArray();
        for (String app : MinabAllowedApps.getStoredApps(context)) allowedApps.put(app);
        settings.put("allowedApps", allowedApps);
        settings.put("allowedAppsAreDisallowed",
                MinabAllowedApps.getStoredAreDisallowed(context));
        meta.put("settings", settings);
        JSONArray profs = new JSONArray();
        for (VpnProfile profile : profiles) {
            JSONObject p = new JSONObject();
            p.put("uuid", profile.getUUIDString());
            p.put("name", profile.mName == null ? "" : profile.mName);
            p.put("remoteHost", profile.getPrimaryRemoteHost());
            p.put("remotePort", profile.getPrimaryRemotePort());
            p.put("category", profile.getCategory());
            p.put("username", profile.mUsername == null ? "" : profile.mUsername);
            p.put("password", profile.mPassword == null ? "" : profile.mPassword);
            profs.put(p);
        }
        meta.put("profiles", profs);
        return meta.toString();
    }

    // --- Reading ---

    /** True when the zip bytes contain a Minab backup metadata entry. */
    public static boolean hasMetadata(byte[] zipBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (META_ENTRY.equals(entry.getName())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Restore a full backup. The .ovpn files are imported first (the
     * usual bulk-import path, duplicates skipped), then the metadata
     * is applied. When the zip has no metadata entry the result has
     * wasBackup=false and the caller should import it as plain configs.
     */
    public static RestoreResult restoreBackup(Context context, byte[] zipBytes) {
        RestoreResult result = new RestoreResult();
        // Pass 1: split the zip into its entries (capped), so the
        // metadata can be read after the .ovpn files are imported.
        List<Entry> ovpnEntries = new ArrayList<>();
        byte[] metaBytes = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            int total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (META_ENTRY.equals(name)) {
                    metaBytes = readCapped(zip, MAX_ENTRY_BYTES);
                    continue;
                }
                if (!isOvpnName(name)) continue;
                if (ovpnEntries.size() >= MAX_FILES) continue;
                byte[] bytes = readCapped(zip, MAX_ENTRY_BYTES);
                if (bytes == null) {
                    result.failed++;
                    continue;
                }
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    result.error = "backup too large";
                    return result;
                }
                ovpnEntries.add(new Entry(name, bytes));
            }
        } catch (Exception e) {
            result.error = e.getMessage();
            return result;
        }
        if (metaBytes == null) {
            result.wasBackup = false;
            return result;
        }
        result.wasBackup = true;

        // Pass 2: import the .ovpn files (no options: the metadata
        // sets category and credentials per profile below).
        BulkImportHelper.ImportResult importResult = new BulkImportHelper.ImportResult();
        BulkImportHelper.ImportOptions opts = new BulkImportHelper.ImportOptions();
        for (Entry e : ovpnEntries) {
            BulkImportHelper.importOne(context,
                    new ByteArrayInputStream(e.bytes), fileNameOf(e.name), opts, importResult);
        }
        result.imported = importResult.imported;
        result.skippedDuplicates = importResult.skippedDuplicates;
        result.failed += importResult.failed;

        // Pass 3: apply the metadata.
        try {
            JSONObject meta = new JSONObject(new String(metaBytes, "UTF-8"));
            applyMetadata(context, meta);
            result.profilesRestored = meta.optJSONArray("profiles") == null
                    ? 0 : meta.optJSONArray("profiles").length();
        } catch (Exception e) {
            result.error = e.getMessage();
        }
        return result;
    }

    private static void applyMetadata(Context context, JSONObject meta) throws Exception {
        ProfileManager pm = ProfileManager.getInstance(context);
        // Key for matching a metadata entry to a live profile:
        // name + primary remote host + port (the bulk-import dedup key).
        Map<String, VpnProfile> byKey = new HashMap<>();
        for (VpnProfile p : pm.getProfiles()) byKey.put(keyOf(p), p);

        JSONArray categories = meta.optJSONArray("categories");
        if (categories != null) {
            for (int i = 0; i < categories.length(); i++) {
                CategoryStore.addCategory(context, categories.optString(i));
            }
        }
        JSONArray profs = meta.optJSONArray("profiles");
        // uuid (backup) -> live profile, for translating favorites.
        Map<String, VpnProfile> byBackupUuid = new HashMap<>();
        if (profs != null) {
            for (int i = 0; i < profs.length(); i++) {
                JSONObject p = profs.getJSONObject(i);
                String uuid = p.optString("uuid", "");
                String name = p.optString("name", "");
                String host = p.optString("remoteHost", "");
                int port = p.optInt("remotePort", 0);
                VpnProfile live = null;
                if (!uuid.isEmpty()) {
                    try {
                        live = ProfileManager.get(context, uuid);
                    } catch (Exception ignored) {
                    }
                }
                if (live == null) live = byKey.get(name + "|" + host + "|" + port);
                if (live == null) continue;
                if (!uuid.isEmpty()) byBackupUuid.put(uuid, live);
                String category = p.optString("category", "");
                if (!category.isEmpty()) {
                    live.setCategory(category);
                    CategoryStore.addCategory(context, category);
                }
                String username = p.optString("username", "");
                String password = p.optString("password", "");
                if (!username.isEmpty()) live.mUsername = username;
                if (!password.isEmpty()) live.mPassword = password;
                ProfileManager.saveProfile(context, live);
            }
        }
        JSONArray favorites = meta.optJSONArray("favorites");
        if (favorites != null) {
            Set<String> liveFavorites = new HashSet<>();
            for (int i = 0; i < favorites.length(); i++) {
                VpnProfile live = byBackupUuid.get(favorites.optString(i));
                if (live != null) liveFavorites.add(live.getUUIDString());
            }
            MinabFavorites.setAll(context, liveFavorites);
        }
        JSONObject settings = meta.optJSONObject("settings");
        if (settings != null) {
            SharedPreferences minabPrefs =
                    context.getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor minabEdit = minabPrefs.edit();
            if (settings.has("sortMode")) {
                minabEdit.putInt("minab_sort_mode", settings.optInt("sortMode", 0));
            }
            minabEdit.apply();
            SharedPreferences defPrefs = Preferences.getDefaultSharedPreferences(context);
            SharedPreferences.Editor defEdit = defPrefs.edit();
            if (settings.has("showSpeed")) {
                defEdit.putBoolean("minab_show_speed_notification",
                        settings.optBoolean("showSpeed", true));
            }
            if (settings.has("failover")) {
                defEdit.putBoolean("minab_failover", settings.optBoolean("failover", true));
            }
            defEdit.apply();
            JSONArray allowedApps = settings.optJSONArray("allowedApps");
            if (allowedApps != null) {
                Set<String> apps = new HashSet<>();
                for (int i = 0; i < allowedApps.length(); i++) {
                    apps.add(allowedApps.optString(i));
                }
                boolean disallowed = settings.optBoolean("allowedAppsAreDisallowed", true);
                MinabAllowedApps.saveChoice(context, apps, disallowed);
                MinabAllowedApps.applyToAllProfiles(context, apps, disallowed);
            }
        }
    }

    private static String keyOf(VpnProfile p) {
        String name = p.mName == null ? "" : p.mName;
        String host = p.getPrimaryRemoteHost();
        if (host == null) host = "";
        return name + "|" + host + "|" + p.getPrimaryRemotePort();
    }

    private static final class Entry {
        final String name;
        final byte[] bytes;

        Entry(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean isOvpnName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".ovpn") || lower.endsWith(".conf");
    }

    private static String fileNameOf(String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
