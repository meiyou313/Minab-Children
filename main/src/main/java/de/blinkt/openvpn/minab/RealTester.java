/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * RealTester (v0.6): the shared "Real test" runner, used by Home
 * and by Configs. One tap, two stages, on a worker thread (never
 * the UI thread):
 *   1. PING PHASE — the fast parallel ping, but only when some
 *      profile has no ping result from the last 5 min. The results
 *      rank the servers for stage 2 (ping OK first by ms, then
 *      never-tested, then Timeout/Failed last — they can still work,
 *      e.g. UDP servers with ICMP blocked).
 *   2. REAL PHASE — connect each server in ranked order, fetch
 *      https://web.telegram.org/ through the tunnel, record the
 *      time (or "Blocked"), disconnect, next server. Stops early
 *      as soon as targetPasses servers have passed, so the user
 *      never waits through a long list.
 *
 * The caller drives the actual connect: the runner asks for it
 * through Listener.onConnectRequested (posted to the UI thread),
 * because starting LaunchVPN needs an Activity. Disconnects are
 * done here (bind + stopVPN, the Quick Settings tile pattern —
 * the DisconnectVPN activity would ask the user every time).
 *
 * Mutual exclusion: real tests are mutually exclusive across the
 * whole app — a static current-runner slot. start() returns false
 * when another real test is already running (the caller then
 * toasts "a real test is already running"). Starting a new run
 * while the previous one is only winding down after a cancel
 * supersedes it: the old worker never disconnects the new run's
 * server (its disconnects are guarded by generation).
 *
 * Ping tests do NOT use this class and are not excluded by it
 * (they do not touch the tunnel).
 */

package de.blinkt.openvpn.minab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.widget.Toast;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

public class RealTester implements VpnStatus.StateListener {

    /** The real test stops as soon as this many servers have passed it. */
    public static final int REAL_TEST_TARGET = 3;

    /** Callbacks to the fragment that owns the run. All are posted
        to the UI thread by this class. */
    public interface Listener {
        /** Ping phase progress (done/total). */
        void onPingProgress(int done, int total);
        /** Real phase progress: done/total, server under test, working so far. */
        void onRealProgress(int done, int total, String currentName, int working);
        /** One profile's stored result changed (ping or real): refresh the list. */
        void onProfileResult(VpnProfile profile);
        /** The run ended (cancelled = stopped early, not finished). */
        void onFinished(boolean cancelled, int working);
        /** Ask the caller to connect this profile (UI thread; the
            caller uses its own startVPN / LaunchVPN path). */
        void onConnectRequested(VpnProfile profile);
    }

    private static final Object LOCK = new Object();
    /** The currently running (or winding-down) tester, if any. */
    private static RealTester current = null;
    /** Bumped by every start(); a worker only disconnects the VPN
        and clears the slot while it is still the newest run. */
    private static int generation = 0;

    /** True while any real test is running anywhere in the app. */
    public static boolean isRunning() {
        synchronized (LOCK) {
            return current != null && current.active;
        }
    }

    private final Context appContext;
    private final long connectTimeoutMs;
    private final int targetPasses;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean started = false;
    private volatile boolean active = false;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile int runId = 0;
    private int myGeneration = 0;
    private Thread worker = null;

    /** Last connection level seen by our own StateListener (written
        on whatever thread VpnStatus calls us on, read by the
        worker thread). */
    private volatile ConnectionStatus lastConnectionLevel = null;

    private final AtomicInteger pingDoneCount = new AtomicInteger(0);
    private volatile int pingTotalCount = 0;
    private volatile int realDoneCount = 0;
    private volatile int realTotalCount = 0;
    private volatile int realWorkingCount = 0;
    private volatile String realCurrentName = null;

    private PingTester pingTester = null;

    public RealTester(Context appContext, long connectTimeoutMs,
                      int targetPasses, Listener listener) {
        this.appContext = appContext.getApplicationContext();
        this.connectTimeoutMs = connectTimeoutMs;
        this.targetPasses = targetPasses;
        this.listener = listener;
    }

    /** Start the run. Returns false when another real test is
        already running (the caller toasts that); a previous run
        that was already cancelled is superseded instead. */
    public boolean start(List<VpnProfile> profiles) {
        synchronized (LOCK) {
            if (started) return false;
            RealTester cur = current;
            if (cur != null && cur != this && cur.active && !cur.cancelled.get()) {
                return false;
            }
            if (cur != null && cur != this) cur.cancel();
            current = this;
            myGeneration = ++generation;
            started = true;
            active = true;
        }
        VpnStatus.addStateListener(this);
        List<VpnProfile> copy = new ArrayList<>(profiles);
        worker = new Thread(() -> run(copy));
        worker.start();
        return true;
    }

    /** Stop a running test: drop the VPN under test (if this run
        still owns it), stop scheduling further servers, leave
        already-recorded results. Safe to call when nothing runs. */
    public void cancel() {
        cancelled.set(true);
        runId++;
        active = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        if (pingTester != null) pingTester.cancel();
        if (myGeneration == generation) requestDisconnect();
    }

    /** True when this run must stop: cancelled, superseded by a
        newer run, or its worker thread was interrupted. */
    private boolean stopped() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    private void postToMain(Runnable r) {
        mainHandler.post(r);
    }

    // --- VpnStatus.StateListener: only the level matters here. ---

    @Override
    public void updateState(String state, String logmessage, int localizedResId,
                            ConnectionStatus level, Intent intent) {
        lastConnectionLevel = level;
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    // --- The coordinator: runs on the worker thread. ---

    private void run(List<VpnProfile> profiles) {
        int myRunId = runId;
        boolean finishedOk = false;
        try {
            // Stage 0: drop any VPN already up, so pings and real
            // tests measure the direct path, not the old tunnel.
            if (VpnStatus.isVPNActive()) {
                postToMain(() -> Toast.makeText(
                        appContext, R.string.minab_real_disconnects_vpn,
                        Toast.LENGTH_LONG).show());
                requestDisconnect();
                waitForDisconnected(10_000);
            }
            if (stopped() || myRunId != runId) return;
            // Stage 1: fast parallel ping, unless every profile
            // already has a ping result from the last 5 minutes.
            long fiveMinAgo = System.currentTimeMillis() - 5 * 60 * 1000L;
            boolean needPing = false;
            for (VpnProfile p : profiles) {
                if (p.mLastPingTime <= 0 || p.mLastPingTime < fiveMinAgo
                        || p.mLastPingResult == null || p.mLastPingResult.isEmpty()) {
                    needPing = true;
                    break;
                }
            }
            if (needPing && !runPingPhaseBlocking(profiles)) return;
            if (stopped() || myRunId != runId) return;
            // Stage 2: ranked sequential real tests, stop after
            // targetPasses passes.
            List<VpnProfile> ranked = rankProfilesForRealTest(profiles);
            realTotalCount = ranked.size();
            realDoneCount = 0;
            realWorkingCount = 0;
            realCurrentName = ranked.isEmpty() ? null : ranked.get(0).mName;
            postRealProgress();
            for (VpnProfile profile : ranked) {
                if (realWorkingCount >= targetPasses) break;
                if (stopped() || myRunId != runId) break;
                realCurrentName = profile.mName;
                postRealProgress();
                // Minab v0.8: fast pre-check — a server that is
                // PROVABLY dead is skipped in ~2 s instead of burning
                // the whole connect cap. Minab v0.9.1: the probe is
                // advisory only (see probeServer). It may skip a
                // server only on positive proof of death; silence or
                // a timeout NEVER skips — the full tunnel test runs
                // instead. A skip records the same "Blocked" result
                // as a failed full test, so the list stays truthful;
                // nothing was connected, so there is nothing to
                // disconnect.
                if (!probeServer(profile)) {
                    if (myRunId == runId && !cancelled.get()) {
                        profile.mLastRealMs = -1;
                        profile.mLastRealTime = System.currentTimeMillis();
                        profile.mLastRealResult = appContext.getString(R.string.minab_real_blocked);
                        ProfileManager.saveProfile(appContext, profile);
                        realDoneCount++;
                        postRealProgress();
                        postToMain(() -> listener.onProfileResult(profile));
                    }
                    continue;
                }
                // Connect on the UI thread (startActivity needs it).
                postToMain(() -> {
                    if (!cancelled.get()) listener.onConnectRequested(profile);
                });
                boolean connected = waitForConnected(profile, connectTimeoutMs);
                long ms = -1;
                boolean ok = false;
                if (connected && !stopped() && myRunId == runId) {
                    ms = testTelegram();
                    ok = ms >= 0;
                }
                // Record the result. Guarded by runId: a superseded
                // run must not touch the new run's counters.
                if (myRunId == runId && !cancelled.get()) {
                    final long finalMs = ms;
                    final boolean finalOk = ok;
                    profile.mLastRealMs = finalOk ? finalMs : -1;
                    profile.mLastRealTime = System.currentTimeMillis();
                    profile.mLastRealResult = finalOk
                            ? appContext.getString(R.string.minab_real_ms, finalMs)
                            : appContext.getString(R.string.minab_real_blocked);
                    ProfileManager.saveProfile(appContext, profile);
                    realDoneCount++;
                    if (finalOk) realWorkingCount++;
                    postRealProgress();
                    postToMain(() -> listener.onProfileResult(profile));
                }
                // Drop this server before the next one. Guarded by
                // generation: an older, cancelled run must never
                // disconnect a newer run's server.
                if (myRunId == runId && VpnStatus.isVPNActive()) {
                    requestDisconnect();
                    waitForDisconnected(10_000);
                }
            }
            // Leave no VPN up behind the run.
            if (myRunId == runId && VpnStatus.isVPNActive()) {
                requestDisconnect();
                waitForDisconnected(5_000);
            }
            finishedOk = !cancelled.get() && myRunId == runId;
        } finally {
            VpnStatus.removeStateListener(this);
            synchronized (LOCK) {
                if (current == this) current = null;
            }
            active = false;
            final boolean wasCancelled = !finishedOk;
            final int working = realWorkingCount;
            postToMain(() -> listener.onFinished(wasCancelled, working));
        }
    }

    private void postRealProgress() {
        final int done = realDoneCount;
        final int total = realTotalCount;
        final String name = realCurrentName;
        final int working = realWorkingCount;
        postToMain(() -> listener.onRealProgress(done, total, name, working));
    }

    /** Order for the real phase: ping OK first by ms ascending,
        then never-tested, then Timeout/Failed last (they can still
        work — e.g. UDP servers with ICMP blocked — so they are
        included, just last). Collections.sort (not List.sort) so
        this stays safe on the minSdk (23). */
    private static List<VpnProfile> rankProfilesForRealTest(List<VpnProfile> profiles) {
        List<VpnProfile> ranked = new ArrayList<>(profiles);
        Collections.sort(ranked, new Comparator<VpnProfile>() {
            @Override
            public int compare(VpnProfile a, VpnProfile b) {
                int ga = group(a);
                int gb = group(b);
                if (ga != gb) return ga - gb;
                if (ga == 0) return Long.compare(a.mLastPingMs, b.mLastPingMs);
                String na = a.mName == null ? "" : a.mName;
                String nb = b.mName == null ? "" : b.mName;
                return na.compareTo(nb);
            }

            private int group(VpnProfile p) {
                if (p.mLastPingMs >= 0) return 0;
                if (p.mLastPingResult == null || p.mLastPingResult.isEmpty()) return 1;
                return 2;
            }
        });
        return ranked;
    }

    /** Parallel ping phase, driven from the coordinator thread:
        returns false when cancelled/superseded (the caller then
        stops the run). Results are recorded like the standalone
        ping test. */
    private boolean runPingPhaseBlocking(List<VpnProfile> profiles) {
        PingTester tester = new PingTester();
        pingTester = tester;
        pingDoneCount.set(0);
        pingTotalCount = profiles.size();
        postToMain(() -> listener.onPingProgress(0, pingTotalCount));
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] pingCancelled = new boolean[]{false};
        tester.testAll(profiles, new PingTester.PingListener() {
            @Override
            public void onResult(PingTester.Result result) {
                result.profile.mLastPingMs =
                        result.status == PingTester.Status.OK ? result.ms : -1;
                result.profile.mLastPingTime = result.timestampMs;
                result.profile.mLastPingResult = result.displayText();
                ProfileManager.saveProfile(appContext, result.profile);
                final int done = pingDoneCount.incrementAndGet();
                final int total = pingTotalCount;
                postToMain(() -> {
                    listener.onPingProgress(done, total);
                    listener.onProfileResult(result.profile);
                });
            }

            @Override
            public void onFinished(boolean cancelled) {
                pingCancelled[0] = cancelled;
                pingTester = null;
                latch.countDown();
            }
        });
        try {
            // 8 threads, ~5 s worst case per host.
            if (!latch.await(120, TimeUnit.SECONDS)) pingCancelled[0] = true;
        } catch (InterruptedException e) {
            return false;
        }
        return !pingCancelled[0] && !cancelled.get();
    }

    /** True only when OpenVPN reports CONNECTED for exactly this
        profile. (VpnStatus.isVPNActive() alone is not enough: it is
        also true while connecting, and the last-connected UUID is
        set when the service starts, before the tunnel is up.) */
    private boolean isConnectedTo(VpnProfile profile) {
        return lastConnectionLevel == ConnectionStatus.LEVEL_CONNECTED
                && Objects.equals(profile.getUUIDString(),
                        VpnStatus.getLastConnectedVPNProfile());
    }

    private boolean waitForConnected(VpnProfile profile, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) return false;
            if (isConnectedTo(profile)) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return isConnectedTo(profile);
    }

    private boolean waitForDisconnected(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) return false;
            if (!VpnStatus.isVPNActive()) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !VpnStatus.isVPNActive();
    }

    // --- Minab v0.8: fast pre-check before each full tunnel connect.
    // --- Minab v0.9.1: the probe is advisory only.
    //
    // A dead server used to burn the whole connect cap (12 s on Home,
    // 20 s on Configs) before being marked Blocked. The probe below
    // answers in ~2 s whether the server is even reachable:
    //   - TCP profiles: a direct TCP connect to the profile's remote
    //     host:port (2 s timeout).
    //   - UDP profiles: one OpenVPN P_CONTROL_HARD_RESET_CLIENT_V2
    //     packet (first byte 0x38, then 8 random session-id bytes,
    //     then 4-byte packet id 0) to the remote host:port, waiting
    //     up to 2 s for ANY UDP reply. A live OpenVPN server answers
    //     a client reset with its own reset.
    //
    // v0.9.1 — why the probe must never skip on silence: in v0.9 the
    // probe skipped a server whenever it did not answer, and that
    // marked WORKING servers Blocked — including the very server the
    // user was connected to at the time (it had passed the real test
    // in v0.8). The cause: servers using tls-auth / tls-crypt silently
    // drop an unsigned reset packet, and some paths swallow the probe
    // entirely, so "no answer" proves nothing about the server. The
    // probe now returns a three-way answer — ALIVE (answered), DEAD
    // (positive proof the server is gone), UNKNOWN (silence, timeout,
    // DNS failure, or any other inconclusive outcome) — and a server
    // is skipped ONLY when the probe says DEAD twice in a row.
    // Silence falls through to the full tunnel test, which is the
    // only verdict that counts.
    //
    // What counts as DEAD (positive proof):
    //   - TCP: the connect was REFUSED (ConnectException). The host
    //     is up and nothing listens on that port.
    //   - UDP: an ICMP port-unreachable arrived (the socket is
    //     connected, so receive() throws PortUnreachableException).
    //     The host is up and the port is closed.
    // Everything else — timeout, silence, DNS failure, no network —
    // is UNKNOWN: the full test decides.

    /** How long the pre-check waits for an answer (per server). */
    private static final int PROBE_TIMEOUT_MS = 2000;

    /** The probe answered: the server is reachable. */
    private static final int PROBE_ALIVE = 1;
    /** The probe was inconclusive: run the full tunnel test. */
    private static final int PROBE_UNKNOWN = 0;
    /** The probe proved the server is gone (refused / port closed). */
    private static final int PROBE_DEAD = -1;

    /**
     * True when the full tunnel test should run for this server.
     * False only when the probe proved the server dead twice in a
     * row. An unexpected error inside the probe itself returns
     * true, so a probe bug can never disable the real test.
     */
    private boolean probeServer(VpnProfile profile) {
        String host;
        int port;
        boolean udp;
        try {
            host = profile.mServerName;
            port = Integer.parseInt(profile.mServerPort);
            udp = profile.mUseUdp;
        } catch (Exception e) {
            // Unparsable profile data: the probe cannot run, so do
            // the full test instead of guessing.
            return true;
        }
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) return true;
        try {
            int verdict = udp ? probeUdp(host, port) : probeTcp(host, port);
            if (verdict == PROBE_ALIVE || verdict == PROBE_UNKNOWN) return true;
            // DEAD: one retry. A single refusal can be a transient
            // glitch (a brief mobile-data drop), and a wrongly
            // skipped server is marked Blocked without ever getting
            // its full test. Two refusals in a row is a real answer;
            // the second try costs at most another 2 s, still far
            // less than the 12/20 s full wait.
            verdict = udp ? probeUdp(host, port) : probeTcp(host, port);
            return verdict != PROBE_DEAD;
        } catch (IOException e) {
            // Socket setup failed (e.g. no network at all when the
            // socket was created): let the full test decide.
            return true;
        } catch (RuntimeException e) {
            // A bug in the probe itself: never let it disable the
            // real test.
            return true;
        }
    }

    /**
     * Direct TCP connect to host:port. ALIVE when the connect
     * succeeds. DEAD only when the connect was refused
     * (ConnectException) — positive proof nothing listens there.
     * A connect timeout, DNS failure, or any other IOException is
     * UNKNOWN: silence is not proof of death.
     */
    private int probeTcp(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            try {
                socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            } catch (java.net.ConnectException e) {
                return PROBE_DEAD;
            } catch (IOException e) {
                return PROBE_UNKNOWN;
            }
            return PROBE_ALIVE;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * One OpenVPN client-reset packet to host:port; ALIVE when ANY
     * UDP reply arrives within the timeout. DEAD only when an ICMP
     * port-unreachable arrives (the socket is connected, so
     * receive() throws PortUnreachableException) — positive proof
     * the port is closed. Silence (timeout) is UNKNOWN: servers
     * using tls-auth / tls-crypt silently drop an unsigned reset,
     * and they work perfectly. DNS failure and other IOExceptions
     * are UNKNOWN too.
     */
    private int probeUdp(String host, int port) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(PROBE_TIMEOUT_MS);
            byte[] payload = new byte[13];
            payload[0] = 0x38; // P_CONTROL_HARD_RESET_CLIENT_V2, key id 0
            java.util.Random random = new java.util.Random();
            for (int i = 0; i < 8; i++) payload[1 + i] = (byte) random.nextInt(256);
            // Bytes 9-12 stay 0: packet id 0.
            try {
                InetAddress address = InetAddress.getByName(host);
                // Connected socket: an ICMP port-unreachable for
                // this port is reported as PortUnreachableException
                // on receive(), instead of being silently dropped.
                socket.connect(address, port);
                socket.send(new DatagramPacket(payload, payload.length));
                byte[] buf = new byte[512];
                socket.receive(new DatagramPacket(buf, buf.length));
            } catch (java.net.PortUnreachableException e) {
                return PROBE_DEAD;
            } catch (IOException e) {
                return PROBE_UNKNOWN;
            }
            return PROBE_ALIVE;
        } finally {
            socket.close();
        }
    }

    /** Drop the current VPN without any confirmation dialog (the
        DisconnectVPN activity would ask the user every time).
        Same bind-and-stopVPN pattern as the Quick Settings tile.
        Safe to call from the worker thread; the service callback
        runs on the main thread. */
    private void requestDisconnect() {
        Intent intent = new Intent(appContext, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        try {
            appContext.bindService(intent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false);
                    } catch (RemoteException e) {
                        VpnStatus.logException(e);
                    }
                    try {
                        appContext.unbindService(this);
                    } catch (Exception ignored) {
                        // Already unbound / context gone.
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {
            // Service not running (nothing to drop) or context gone.
        }
    }

    /** Fetch the Telegram web client through the tunnel. Returns the
        milliseconds until the response arrived, or -1 when the fetch
        failed (blocked / timeout / no route). Only a small prefix of
        the body is read; the point is reachability, not content.
        Minab v0.9.1: 10 s timeouts (6 s was too tight on a slow
        mobile link, and a slow fetch is not a blocked server) and
        one retry — a single failed fetch must not mark a working
        server Blocked. */
    private long testTelegram() {
        long ms = fetchTelegramOnce();
        if (ms < 0) ms = fetchTelegramOnce();
        return ms;
    }

    private long fetchTelegramOnce() {
        long start = SystemClock.elapsedRealtime();
        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) new URL("https://web.telegram.org/").openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Minab");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                try {
                    byte[] buf = new byte[512];
                    conn.getInputStream().read(buf);
                } catch (Exception ignored) {
                    // Body unreadable after a good status: still
                    // counts — the tunnel carried the response.
                }
                long ms = SystemClock.elapsedRealtime() - start;
                // Minab v0.7: a real HTTPS fetch through a VPN tunnel
                // needs several round trips (TCP + TLS handshake +
                // HTTP request/response), so it can never complete in
                // a few milliseconds. A value under 50 ms means the
                // measurement is bogus (e.g. served without any
                // network round trip), not a real pass — return -1
                // (Blocked) instead of a "0 ms" success.
                if (ms < 50) return -1;
                return ms;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
