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

    /**
     * Minab Beta v0.9.1 — sentinel for the favorites pseudo-tab
     * (fix #7). It is used as the selected-category value, never as
     * a real category name: it starts with a control character no
     * user can type into the group-name field, so it can never
     * collide with a real group.
     */
    public static final String FAVORITES_TAB = "\u0001favorites";

    private static final String PREFS_NAME = "MinabCategories";
    private static final String KEY_CATEGORIES = "categories";
    /** Minab Beta v0.9.1 — the saved group order (fix #8), a JSON
        array string: a StringSet would lose the order. */
    private static final String KEY_ORDER = "order";

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

    /**
     * Minab Beta v0.9.1 — the display order of the group tabs
     * (fix #8). The order the user set with the reorder dialog is
     * saved; a category with no saved position yet (newly created)
     * is inserted just before "Uncategorized", and "Uncategorized"
     * is last unless the user moved it. Categories that no longer
     * exist are dropped. Without a saved order, the order is the
     * same as getCategories() (alphabetical, Uncategorized last).
     */
    public static List<String> getOrderedCategories(Context context) {
        List<String> known = getCategories(context);
        List<String> saved = readOrder(context);
        List<String> out = new ArrayList<>();
        for (String name : saved) {
            if (known.contains(name) && !out.contains(name)) out.add(name);
        }
        List<String> missing = new ArrayList<>();
        for (String name : known) {
            if (!out.contains(name)) missing.add(name);
        }
        int uncIdx = out.indexOf(DEFAULT_CATEGORY);
        if (uncIdx < 0) {
            out.addAll(missing);
        } else {
            out.addAll(uncIdx, missing);
        }
        return out;
    }

    /** Save the group order set by the reorder dialog (fix #8). */
    public static void saveOrder(Context context, List<String> order) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String name : order) arr.put(name);
        prefs(context).edit().putString(KEY_ORDER, arr.toString()).apply();
    }

    private static List<String> readOrder(Context context) {
        List<String> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY_ORDER, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String name = arr.optString(i, null);
                if (name != null && !name.isEmpty()) out.add(normalise(name));
            }
        } catch (Exception ignored) {
            // Corrupt order: fall back to the default order.
        }
        return out;
    }
}
