/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * PingTester: bulk latency test for many profiles at once.
 *
 * How a single host is measured (in order):
 *   1. ICMP via /system/bin/ping -c 1 -W 2 <host> — the same binary the
 *      system ping uses. Its "time=12.3 ms" line is parsed. This works
 *      without root on Android because the binary is setuid / has the
 *      needed capability on most devices; on devices where it is blocked
 *      it simply fails fast and we fall through to step 2.
 *   2. TCP connect timing — open a Socket to the profile's remote
 *      host:port with a 3 s timeout and measure connect time. This is
 *      NOT a true ICMP ping (a filtered port or a slow TLS handshake
 *      does not apply here — only the TCP handshake is timed), but it
 *      answers the question the list needs: "is this server reachable
 *      from here, and how fast does it answer?" A TCP failure is
 *      reported as Failed, not as a fake latency.
 *   Overall per-host budget is ~3 s; a host that answers neither way is
 *   reported as Timeout (ICMP silent + TCP timeout) or Failed (TCP
 *   refused / DNS failure).
 *
 * Concurrency: a fixed pool of 8 threads, one task per profile. Results
 * arrive through PingListener callbacks (already on a worker thread —
 * the caller must post to the UI thread). cancel() stops scheduling new
 * work and interrupts running tasks; already-finished results stay.
 *
 * VPN safety: pings use plain java.net sockets and the ping binary from
 * the app process. They do NOT touch VpnService, do NOT call
 * VpnService.protect(), and do NOT change routing. While a VPN is
 * active the measured latency is therefore the latency *through* the
 * tunnel for the ICMP case (the ping packet takes the tunnel's default
 * route) — the UI warns about this. Nothing here can disconnect or
 * break the running tunnel; the worst case is a misleading number,
 * which is why the list shows the ping timestamp next to the value.
 */

package de.blinkt.openvpn.minab;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.blinkt.openvpn.VpnProfile;

public final class PingTester {
    public static final int THREADS = 8;
    /** Per-host budget in ms (ICMP -W 2 plus TCP 3000, worst case ~5 s). */
    public static final int TIMEOUT_MS = 3000;

    private static final Pattern PING_TIME =
            Pattern.compile("time[=<]([0-9]+(?:\\.[0-9]+)?)\\s*ms");

    public enum Status { OK, TIMEOUT, FAILED }

    public static final class Result {
        public final VpnProfile profile;
        public final Status status;
        /** Round-trip ms when status == OK, otherwise -1. */
        public final long ms;
        public final long timestampMs;

        public Result(VpnProfile profile, Status status, long ms) {
            this.profile = profile;
            this.status = status;
            this.ms = ms;
            this.timestampMs = System.currentTimeMillis();
        }

        public String displayText() {
            switch (status) {
                case OK: return ms + " ms";
                case TIMEOUT: return "Timeout";
                default: return "Failed";
            }
        }
    }

    public interface PingListener {
        /** Called once per finished profile, on a worker thread. */
        void onResult(Result result);
        /** Called once when all submitted profiles are done or cancelled. */
        void onFinished(boolean cancelled);
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private final List<Future<?>> futures = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Test all given profiles in parallel. Results via the listener. */
    public synchronized void testAll(List<VpnProfile> profiles, PingListener listener) {
        cancelled.set(false);
        futures.clear();
        if (profiles == null || profiles.isEmpty()) {
            listener.onFinished(false);
            return;
        }
        for (VpnProfile profile : profiles) {
            futures.add(pool.submit(() -> {
                if (cancelled.get()) return;
                Result r = testOne(profile);
                if (!cancelled.get()) listener.onResult(r);
            }));
        }
        pool.submit(() -> {
            for (Future<?> f : new ArrayList<>(futures)) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // Cancelled / interrupted: stop waiting.
                    break;
                }
            }
            listener.onFinished(cancelled.get());
        });
    }

    /** Stop a running test. Already-reported results are kept. */
    public synchronized void cancel() {
        cancelled.set(true);
        for (Future<?> f : futures) f.cancel(true);
    }

    public void shutdown() {
        cancel();
        pool.shutdownNow();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Synchronous single-profile test; public so the list can test one row. */
    public static Result testOne(VpnProfile profile) {
        String host = profile.getPrimaryRemoteHost();
        int port = profile.getPrimaryRemotePort();
        if (TextUtils.isEmpty(host)) {
            return new Result(profile, Status.FAILED, -1);
        }
        Long icmp = icmpPing(host);
        if (icmp != null) return new Result(profile, Status.OK, icmp);
        return tcpPing(profile, host, port);
    }

    /** Returns ms on success, null when ICMP gave no usable answer. */
    static Long icmpPing(String host) {
        Process proc = null;
        try {
            // -c 1: one packet. -W 2: wait at most 2 s for the reply.
            proc = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            int exit = proc.waitFor();
            Matcher m = PING_TIME.matcher(out.toString());
            if (m.find()) {
                return Math.round(Double.parseDouble(m.group(1)));
            }
            // No time= line: host silent (or ping binary blocked).
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    private static Result tcpPing(VpnProfile profile, String host, int port) {
        Socket socket = new Socket();
        long start = System.nanoTime();
        try {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return new Result(profile, Status.OK, ms);
        } catch (java.net.SocketTimeoutException e) {
            return new Result(profile, Status.TIMEOUT, -1);
        } catch (java.net.UnknownHostException e) {
            return new Result(profile, Status.FAILED, -1);
        } catch (Exception e) {
            // Connection refused etc. Distinguish a timeout (already
            // handled) from a fast refusal: both are "Failed" here, the
            // user-visible difference is Timeout vs Failed.
            return new Result(profile, Status.FAILED, -1);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
