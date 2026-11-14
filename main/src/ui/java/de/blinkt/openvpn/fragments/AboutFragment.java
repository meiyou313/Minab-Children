/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.android.vending.billing.IInAppBillingService;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Vector;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.VpnStatus;
import de.blinkt.openvpn.minab.MinabUpdateChecker;

public class AboutFragment extends Fragment implements View.OnClickListener {

    private static final String RESPONSE_CODE = "RESPONSE_CODE";


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.about, container, false);
        TextView ver = (TextView) v.findViewById(R.id.version);

        String version;
        String name = "Openvpn";
        try {
            PackageInfo packageinfo = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0);
            version = packageinfo.versionName;
            name = getString(R.string.app);
        } catch (NameNotFoundException e) {
            version = "error fetching version";
        }

        ver.setText(getString(R.string.version_info, name, version));

        // Minab Beta v0.9 — the About text carries the version at
        // its top (the string has %1$s), so it is set here, not in
        // the layout.
        TextView aboutText = v.findViewById(R.id.minab_about_text_view);
        if (aboutText != null) {
            aboutText.setText(getString(R.string.minab_about_text, version));
        }

        // Minab Beta v0.4 — update notice on About. The check is
        // cached for 24 h inside MinabUpdateChecker.
        TextView updateStatus = v.findViewById(R.id.minab_update_status);
        final String installedVersion = version;
        MinabUpdateChecker.check(getActivity(), false, (latestTag, available) -> {
            if (getActivity() == null || updateStatus == null) return;
            renderUpdateStatus(updateStatus, installedVersion, latestTag, available, false);
        });

        // Minab Beta v0.9 — "Check for updates" at the TOP of the
        // About screen (like Namaki). A manual check always goes to
        // the network (force=true) and shows its result clearly,
        // including a failure message (the automatic check above
        // just hides the notice when it cannot reach GitHub).
        android.widget.Button checkUpdateButton =
                v.findViewById(R.id.minab_about_check_update);
        if (checkUpdateButton != null) {
            checkUpdateButton.setOnClickListener(view -> {
                if (getActivity() == null || updateStatus == null) return;
                checkUpdateButton.setEnabled(false);
                updateStatus.setText(getString(R.string.minab_update_checking));
                updateStatus.setVisibility(View.VISIBLE);
                updateStatus.setOnClickListener(null);
                MinabUpdateChecker.check(getActivity(), true, (latestTag, available) -> {
                    checkUpdateButton.setEnabled(true);
                    if (getActivity() == null || updateStatus == null) return;
                    renderUpdateStatus(updateStatus, installedVersion, latestTag, available, true);
                });
            });
        }

        // Minab Beta v0.9.1 — the upstream boilerplate (OpenVPN /
        // OpenVPN3 / OpenSSL versions, translators, full license
        // texts) is gone from this screen (fix #1): About now
        // shows only the version and Minab's own text. The code
        // that filled those views is gone with them.

        ViewCompat.setOnApplyWindowInsetsListener(v, (view, windowInsets) ->
                {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), insets.bottom);
                    return WindowInsetsCompat.CONSUMED;
                }
        );
        return v;
    }

    /** Minab Beta v0.9 — one rendering for the update notice, shared
        by the automatic check and the manual "Check for updates"
        button. manual=true also shows a failure message when the
        check could not reach GitHub at all (the automatic check
        just hides the notice then). */
    private void renderUpdateStatus(TextView updateStatus, String installedVersion,
                                    String latestTag, boolean available, boolean manual) {
        if (latestTag == null) {
            if (manual) {
                updateStatus.setText(getString(R.string.minab_update_failed));
                updateStatus.setVisibility(View.VISIBLE);
                updateStatus.setOnClickListener(null);
            } else {
                updateStatus.setVisibility(View.GONE);
            }
        } else if (available) {
            updateStatus.setText(getString(R.string.minab_update_available_short, latestTag));
            updateStatus.setVisibility(View.VISIBLE);
            updateStatus.setOnClickListener(view -> MinabUpdateChecker.openReleases(getActivity()));
        } else {
            updateStatus.setText(getString(R.string.minab_update_none, installedVersion));
            updateStatus.setVisibility(View.VISIBLE);
            updateStatus.setOnClickListener(null);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }


    @Override
    public void onClick(View v) {

    }
}
