/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.activities;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * Created by arne on 13.10.13.
 *
 * Minab Beta v0.9.1 — the confirmation dialog is gone (fix #11):
 * this activity always disconnects silently now. It is only reached
 * from the app shortcut / notification paths; the Home orb and rows
 * disconnect through MinabVpnControl instead.
 */
public class DisconnectVPN extends Activity {
    private IOpenVPNServiceInternal mService;
    private boolean disconnectPending = false;
    private final ServiceConnection mConnection = new ServiceConnection() {


        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            mService = IOpenVPNServiceInternal.Stub.asInterface(service);
            if (disconnectPending) {
                try {
                    mService.stopVPN(false);
                } catch (RemoteException e) {
                    VpnStatus.logException(e);
                }
                finish();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mService = null;
        }

    };

    @Override
    protected void onResume() {
        super.onResume();
        Intent intent = new Intent(this, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        // Minab Beta v0.9.1 — no confirmation dialog (fix #11):
        // connect / disconnect is one tap on the main button. This
        // activity is only reached from the app shortcut / widget
        // paths now, and it always disconnects silently. (The old
        // "disableconfirmation" preference is gone with the dialog.)
        disconnectPending = true;
        Toast.makeText(this, "Disconnecting VPN", Toast.LENGTH_LONG).show();
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unbindService(mConnection);
    }
}
