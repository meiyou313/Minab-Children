/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * ImportSummaryStore: the last bulk-import summary, persisted
 * (v0.3 bug fix 1). In v0.2 the summary lived only in a transient
 * Toast and in a status line that was reset on return, so after
 * leaving the page the user could no longer tell how many configs
 * had been selected or imported. The store keeps the counts
 * (selected / imported / duplicates skipped / failed, plus the
 * failed names and the time) in a small SharedPreferences file and
 * BulkImportActivity shows it again every time the screen opens.
 * No credentials are stored here — only counts and file names.
 */
package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class ImportSummaryStore {
    private static final String PREFS_NAME = "MinabImportSummary";
    private static final String KEY_TIME = "time";
    private static final String KEY_SELECTED = "selected";
    private static final String KEY_IMPORTED = "imported";
    private static final String KEY_SKIPPED = "skippedDuplicates";
    private static final String KEY_FAILED = "failed";
    private static final String KEY_FAILED_NAMES = "failedNames";

    private ImportSummaryStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Save one import run. {@code failedNames} is capped at 10 entries. */
    public static void save(Context context, int selected, int imported,
                            int skippedDuplicates, int failed, List<String> failedNames) {
        List<String> names = failedNames == null ? new ArrayList<>() : failedNames;
        if (names.size() > 10) names = names.subList(0, 10);
        prefs(context).edit()
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putInt(KEY_SELECTED, selected)
                .putInt(KEY_IMPORTED, imported)
                .putInt(KEY_SKIPPED, skippedDuplicates)
                .putInt(KEY_FAILED, failed)
                .putString(KEY_FAILED_NAMES, TextUtils.join("\n", names))
                .apply();
    }

    /** True when a previous import summary is stored. */
    public static boolean hasSummary(Context context) {
        return prefs(context).contains(KEY_TIME);
    }

    /**
     * The stored summary as display text, e.g.
     * "Last import: 12 Nov, 14:03 — selected 12, imported 10,
     * duplicates skipped 1, failed 1", plus failed names when any.
     * Null when nothing was ever imported.
     */
    public static String formatLastSummary(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_TIME)) return null;
        long time = p.getLong(KEY_TIME, 0);
        int selected = p.getInt(KEY_SELECTED, 0);
        int imported = p.getInt(KEY_IMPORTED, 0);
        int skipped = p.getInt(KEY_SKIPPED, 0);
        int failed = p.getInt(KEY_FAILED, 0);
        String when = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time));
        StringBuilder sb = new StringBuilder();
        sb.append(when)
                .append(" — selected ").append(selected)
                .append(", imported ").append(imported)
                .append(", duplicates skipped ").append(skipped)
                .append(", failed ").append(failed);
        String names = p.getString(KEY_FAILED_NAMES, "");
        if (!TextUtils.isEmpty(names)) sb.append("\n").append(names);
        return sb.toString();
    }
}
