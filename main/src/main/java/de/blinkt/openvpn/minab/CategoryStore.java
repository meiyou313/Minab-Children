/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * CategoryStore: the list of known category names, kept in a small
 * SharedPreferences file ("MinabCategories"). Categories themselves live
 * on each VpnProfile (VpnProfile.mCategory, persisted with the profile);
 * this store only remembers which category names exist so the filter UI
 * can offer them (including names a user created and then removed from
 * every profile, until explicitly deleted), and so a renamed category
 * can be applied to all profiles that used the old name.
 *
 * Categories are plain user text. They are never sent anywhere.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ProfileManager;

public final class CategoryStore {
    public static final String DEFAULT_CATEGORY = "Uncategorized";

    private static final String PREFS_NAME = "MinabCategories";
    private static final String KEY_CATEGORIES = "categories";

    private CategoryStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Normalise a user-supplied category name; null/blank -> default. */
    public static String normalise(String category) {
        if (category == null) return DEFAULT_CATEGORY;
        String trimmed = category.trim();
        if (trimmed.isEmpty()) return DEFAULT_CATEGORY;
        if (trimmed.length() > 64) trimmed = trimmed.substring(0, 64).trim();
        return trimmed;
    }

    /** All known categories, sorted, default first. */
    public static List<String> getCategories(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_CATEGORIES, null);
        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (stored != null) all.addAll(stored);
        all.add(DEFAULT_CATEGORY);
        // Also include categories actually used by profiles, in case the
        // store was cleared or profiles came from a backup.
        try {
            for (VpnProfile p : ProfileManager.getInstance(context).getProfiles()) {
                all.add(normalise(p.getCategory()));
            }
        } catch (Exception ignored) {
            // Profile list not loadable here; stored set is enough.
        }
        List<String> out = new ArrayList<>(all);
        out.remove(DEFAULT_CATEGORY);
        out.add(0, DEFAULT_CATEGORY);
        return Collections.unmodifiableList(out);
    }

    public static void addCategory(Context context, String category) {
        String name = normalise(category);
        Set<String> stored = new HashSet<>();
        Set<String> old = prefs(context).getStringSet(KEY_CATEGORIES, null);
        if (old != null) stored.addAll(old);
        if (stored.add(name)) {
            prefs(context).edit().putStringSet(KEY_CATEGORIES, stored).apply();
        }
    }

    /** Rename a category everywhere: store + every profile using it. */
    public static void renameCategory(Context context, String oldName, String newName) {
        String from = normalise(oldName);
        String to = normalise(newName);
        if (from.equals(to)) return;
        addCategory(context, to);
        try {
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile p : pm.getProfiles()) {
                if (from.equals(normalise(p.getCategory()))) {
                    p.setCategory(to);
                    ProfileManager.saveProfile(context, p);
                }
            }
        } catch (Exception ignored) {
        }
        removeCategoryNameOnly(context, from);
    }

    /**
     * Delete a category name from the store. Profiles that used it fall
     * back to "Uncategorized" (their mCategory is rewritten and saved).
     * The default category itself can never be deleted.
     */
    public static void deleteCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equals(name)) return;
        try {
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile p : pm.getProfiles()) {
                if (name.equals(normalise(p.getCategory()))) {
                    p.setCategory(DEFAULT_CATEGORY);
                    ProfileManager.saveProfile(context, p);
                }
            }
        } catch (Exception ignored) {
        }
        removeCategoryNameOnly(context, name);
    }

    private static void removeCategoryNameOnly(Context context, String name) {
        Set<String> stored = new HashSet<>();
        Set<String> old = prefs(context).getStringSet(KEY_CATEGORIES, null);
        if (old != null) stored.addAll(old);
        if (stored.remove(name)) {
            prefs(context).edit().putStringSet(KEY_CATEGORIES, stored).apply();
        }
    }
}
