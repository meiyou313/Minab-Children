/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * BulkImportHelper: the non-UI core of bulk import. It parses .ovpn /
 * .conf text with the upstream ConfigParser (exactly the same parser the
 * single-file ConfigConverter uses), de-duplicates, applies one shared
 * username/password, and saves through ProfileManager.
 *
 * Credentials: they are stored EXACTLY the way upstream stores them —
 * on the profile itself, VpnProfile.mUsername / mPassword, persisted by
 * ProfileManager.saveProfile() into the profile file (encrypted with
 * ProfileEncryption when the user has encryption enabled, as upstream
 * does). No new plaintext store is introduced anywhere: no
 * SharedPreferences password, no extra file. The bulk dialog only
 * collects the values once and hands them to this helper; the helper
 * clears its local copies after saving.
 *
 * Duplicates: a profile is a duplicate when another existing profile
 * has the same name AND the same primary remote host+port. The name
 * alone is not enough (providers reuse names), the remote alone is not
 * enough (one server, several profiles). Duplicates are skipped and
 * counted, never overwritten.
 *
 * .zip: supported. A zip may contain .ovpn / .conf files at any depth;
 * other entries are ignored and counted as failed with their name.
 * Entries are capped (200 files, 2 MB each — the upstream per-file
 * MAX_EMBED_FILE_SIZE) so a hostile zip cannot exhaust memory.
 *
 * Auto-categorisation is intentionally NOT done here: it needs network
 * and must never block the import. The caller (BulkImportActivity)
 * runs AutoCategory.guessCategory() afterwards, on a background thread,
 * one profile at a time, and saves the category only when a country
 * was actually found.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.text.TextUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ProfileManager;

public final class BulkImportHelper {
    public static final int MAX_FILES_PER_IMPORT = 200;
    public static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024; // = VpnProfile.MAX_EMBED_FILE_SIZE

    public static final class ImportResult {
        public int imported;
        public int skippedDuplicates;
        public int failed;
        public final List<String> failedNames = new ArrayList<>();
        public final List<VpnProfile> importedProfiles = new ArrayList<>();

        public String summary() {
            return "Imported: " + imported
                    + ", duplicates skipped: " + skippedDuplicates
                    + ", failed: " + failed;
        }
    }

    public static final class ImportOptions {
        /** When non-null, apply to every imported profile that uses auth-user-pass. */
        public String username;
        public String password;
        public boolean applyCredentialsToAll;
        /** Category to assign to every imported profile (null = leave default). */
        public String category;
    }

    private BulkImportHelper() {
    }

    /**
     * Import one .ovpn/.conf document. {@code displayName} is used for
     * the profile name when the config itself has no name, and for the
     * failure list. Returns the created profile, or null on parse failure.
     */
    public static VpnProfile importOne(Context context, InputStream in,
                                       String displayName, ImportOptions options,
                                       ImportResult result) {
        try {
            ConfigParser cp = new ConfigParser();
            cp.parseConfig(new InputStreamReader(in, "UTF-8"));
            VpnProfile profile = cp.convertProfile();
            if (profile == null) {
                result.failed++;
                if (displayName != null) result.failedNames.add(displayName);
                return null;
            }
            // Name: prefer the file name, fall back to what the parser made.
            String baseName = baseName(displayName);
            if (baseName != null && !baseName.isEmpty()) {
                profile.mName = uniqueName(context, baseName);
            } else if (TextUtils.isEmpty(profile.mName)) {
                profile.mName = uniqueName(context, "Imported profile");
            } else {
                profile.mName = uniqueName(context, profile.mName);
            }

            if (isDuplicate(context, profile)) {
                result.skippedDuplicates++;
                return null;
            }

            applyOptions(profile, options);
            ProfileManager pm = ProfileManager.getInstance(context);
            pm.addProfile(profile);
            profile.addChangeLogEntry("Profile imported via Minab bulk import");
            ProfileManager.saveProfile(context, profile);
            pm.saveProfileList(context);
            result.imported++;
            result.importedProfiles.add(profile);
            return profile;
        } catch (Exception e) {
            result.failed++;
            if (displayName != null) result.failedNames.add(displayName);
            return null;
        }
    }

    /** Import every .ovpn/.conf entry of a zip stream. */
    public static ImportResult importZip(Context context, InputStream in, ImportOptions options) {
        ImportResult result = new ImportResult();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!isOvpnName(name)) continue;
                if (++seen > MAX_FILES_PER_IMPORT) {
                    result.failed++;
                    result.failedNames.add(name + " (limit of " + MAX_FILES_PER_IMPORT + " files)");
                    continue;
                }
                byte[] bytes = readCapped(zip, MAX_ENTRY_BYTES);
                if (bytes == null) {
                    result.failed++;
                    result.failedNames.add(name + " (too large)");
                    continue;
                }
                importOne(context, new ByteArrayInputStream(bytes), fileNameOf(name), options, result);
            }
            if (seen == 0) {
                result.failed++;
                result.failedNames.add("zip contained no .ovpn/.conf files");
            }
        } catch (Exception e) {
            result.failed++;
            result.failedNames.add("zip: " + e.getMessage());
        }
        return result;
    }

    /** True when an identical name + primary remote already exists. */
    public static boolean isDuplicate(Context context, VpnProfile candidate) {
        ProfileManager pm = ProfileManager.getInstance(context);
        String host = candidate.getPrimaryRemoteHost();
        int port = candidate.getPrimaryRemotePort();
        for (VpnProfile existing : pm.getProfiles()) {
            if (existing == candidate) continue;
            if (TextUtils.equals(existing.mName, candidate.mName)
                    && TextUtils.equals(existing.getPrimaryRemoteHost(), host)
                    && existing.getPrimaryRemotePort() == port) {
                return true;
            }
        }
        return false;
    }

    /** Unique profile name: "name", "name (2)", "name (3)", ... */
    public static String uniqueName(Context context, String wanted) {
        ProfileManager pm = ProfileManager.getInstance(context);
        if (pm.getProfileByName(wanted) == null) return wanted;
        int i = 2;
        while (pm.getProfileByName(wanted + " (" + i + ")") != null) i++;
        return wanted + " (" + i + ")";
    }

    private static void applyOptions(VpnProfile profile, ImportOptions options) {
        if (options == null) return;
        if (options.category != null && !options.category.isEmpty()) {
            // The category name itself is recorded in CategoryStore by the
            // caller (BulkImportActivity), which owns the Context.
            profile.setCategory(options.category);
        }
        if (options.applyCredentialsToAll && profile.isUserPWAuth()) {
            // Same fields the profile editor (Settings_Basic) writes.
            // Persisted by ProfileManager.saveProfile(), encrypted when
            // upstream profile encryption is enabled.
            if (options.username != null) profile.mUsername = options.username;
            if (options.password != null) profile.mPassword = options.password;
        }
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
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

    private static String baseName(String displayName) {
        if (displayName == null) return null;
        String name = fileNameOf(displayName);
        if (name.toLowerCase(java.util.Locale.US).endsWith(".ovpn")) {
            name = name.substring(0, name.length() - 5);
        } else if (name.toLowerCase(java.util.Locale.US).endsWith(".conf")) {
            name = name.substring(0, name.length() - 5);
        }
        return name;
    }
}
