/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.Vector;

import de.blinkt.openvpn.R;

public class FaqFragment extends Fragment {
    static class FAQEntry {

        public FAQEntry(int startVersion, int endVersion, int title, int description) {
            this.startVersion = startVersion;
            this.endVersion = endVersion;
            this.description = description;
            this.title = title;
        }

        final int startVersion;
        final int endVersion;
        final int description;
        final int title;

        public boolean runningVersion() {
            if (Build.VERSION.SDK_INT >= startVersion) {
                if (Build.VERSION.SDK_INT <= endVersion)
                    return true;

                if (endVersion == -1)
                    return true;

                String release = Build.VERSION.RELEASE;
                boolean isOlderThan443 =  !release.startsWith("4.4.3")  && !release.startsWith("4.4.4") &&
                        !release.startsWith("4.4.5") && !release.startsWith("4.4.6");

                boolean isOlderThan442 = isOlderThan443 && !release.startsWith("4.4.2");


                if(Build.VERSION.SDK_INT== Build.VERSION_CODES.KITKAT) {
                    if (endVersion == -441 && isOlderThan442)
                        return true;

                    if (endVersion == -442 && isOlderThan443)
                        return true;
                } else if (endVersion == -441 || endVersion == -442) {
                    return Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT;
                }


            }
            return false;
        }

        public String getVersionsString(Context c) {
            if (startVersion == Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                if (endVersion == -1)
                    return null;
                else
                    return c.getString(R.string.version_upto, getAndroidVersionString(c, endVersion));
            }

            if (endVersion == -1)
                return c.getString(R.string.version_and_later, getAndroidVersionString(c, startVersion));


            String startver = getAndroidVersionString(c, startVersion);

            if (endVersion == startVersion)
                return startver;

            return String.format("%s - %s", startver, getAndroidVersionString(c, endVersion));
        }


        private String getAndroidVersionString(Context c, int versionCode) {
            switch (versionCode) {
                case Build.VERSION_CODES.ICE_CREAM_SANDWICH:
                    return "4.0 (Ice Cream Sandwich)";
                case -441:
                    return "4.4.1 (KitKat)";
                case -442:
                    return "4.4.2 (KitKat)";
                case Build.VERSION_CODES.JELLY_BEAN_MR2:
                    return "4.3 (Jelly Bean MR2)";
                case Build.VERSION_CODES.KITKAT:
                    return "4.4 (KitKat)";
                case Build.VERSION_CODES.LOLLIPOP:
                    return "5.0 (Lollipop)";
                default:
                    return "API " + versionCode;
            }
        }


    }

    private static FAQEntry[] faqitemsVersionSpecific = {

            // Minab Beta v0.8 — the FAQ is Minab only now: the
            // generic upstream ics-openvpn entries (TAP mode,
            // remote API, kill switch, NCP, Samsung quirks, TLS
            // alerts, routing details, battery, shortcuts, log
            // copying, proxy/CIDR notes) described the upstream
            // app, not this one, so they are gone. What is left
            // answers the questions this app's users actually ask.
            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_bulk_title, R.string.minab_faq_bulk),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_groups_title, R.string.minab_faq_groups),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_ping_title, R.string.minab_faq_ping),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_real_title, R.string.minab_faq_real),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_data_title, R.string.minab_faq_data),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_copymove_title, R.string.minab_faq_copymove),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_update_title, R.string.minab_faq_update),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_security_title, R.string.minab_faq_security),

            // Minab Beta v0.9 — the new features, each as its own
            // question with a complete answer.
            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_best_title, R.string.minab_faq_best),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_failover_title, R.string.minab_faq_failover),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_ip_title, R.string.minab_faq_ip),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_search_title, R.string.minab_faq_search),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_widget_title, R.string.minab_faq_widget),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_perapp_title, R.string.minab_faq_perapp),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_link_title, R.string.minab_faq_link),

            new FAQEntry(Build.VERSION_CODES.ICE_CREAM_SANDWICH, -1, R.string.minab_faq_backup_title, R.string.minab_faq_backup),
    };


    private RecyclerView mRecyclerView;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.faq, container, false);

        DisplayMetrics displaymetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        int dpWidth = (int) (displaymetrics.widthPixels / getResources().getDisplayMetrics().density);

        //better way but does not work on 5.0
        //int dpWidth = (int) (container.getWidth()/getResources().getDisplayMetrics().density);
        int columns = dpWidth / 360;
        columns = Math.max(1, columns);


        mRecyclerView = v.findViewById(R.id.faq_recycler_view);

        // use this setting to improve performance if you know that changes
        // in content do not change the layout size of the RecyclerView
        mRecyclerView.setHasFixedSize(true);


        mRecyclerView.setLayoutManager(new StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL));

        mRecyclerView.setAdapter(new FaqViewAdapter(getActivity(), getFAQEntries()));

        Utils.applyInsetListener(v);

        return v;
    }



    private FAQEntry[] getFAQEntries() {
        Vector<FAQEntry> faqItems = new Vector<>();

        for (FAQEntry fe : faqitemsVersionSpecific) {
            if (fe.runningVersion())
                faqItems.add(fe);
        }
        for (FAQEntry fe : faqitemsVersionSpecific) {
            if (!fe.runningVersion())
                faqItems.add(fe);
        }

        return faqItems.toArray(new FAQEntry[faqItems.size()]);
    }

}
