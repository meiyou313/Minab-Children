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
 *      The reply is only trusted when it really came from the target:
 *      the "64 bytes from <ip>" line must name the resolved target IP,
 *      and a sub-5 ms reply to a public IP is impossible from a phone
 *      (mobile radio + routing alone exceed that), so it is discarded
 *      as a spoofed/local answer -- see the v0.9 note below.
 *   2. TCP connect + OpenVPN reset probe -- open a Socket to the
 *      profile's remote host:port with a 3 s timeout, send one OpenVPN
 *      P_CONTROL_HARD_RESET_CLIENT_V2 packet, and wait up to 2 s for ANY
 *      reply bytes. A live OpenVPN server answers a client reset with
 *      its own reset, and the reported ms is then the time until the
 *      first reply byte (about two round trips, so it reads higher
 *      than a bare ICMP ping -- that is honest).
 *      v0.9.1 -- the probe is ADVISORY, not a verdict. A server that
 *      never answers it is NOT failed: servers using tls-auth /
 *      tls-crypt silently drop an unsigned reset, and some
 *      middleboxes kill the data after the handshake, so an
 *      unanswered probe proves nothing about the server. When the
 *      probe goes unanswered, the result falls back to the TCP
 *      connect time: a connect that took a realistic time (>= 5 ms
 *      to a public host) proves the server is listening and
 *      reachable, so it is reported OK with the connect time. A
 *      connect answered in 1-3 ms by a middlebox on the path still
 *      yields Timeout, never a green number -- the v0.9 anti-spoof
 *      fix is kept, only the false-failure part is removed. The
 *      real test (which does the full handshake) remains the
 *      arbiter of whether a server really works.
 *      A TCP failure is reported as Failed/Timeout, not as a fake
 *      latency.
 *   Overall per-host budget is ~5 s; a host that answers neither way is
 *   reported as Timeout (ICMP silent + TCP timeout) or Failed (TCP
 *   refused / DNS failure / connection reset with no data).
 *
 *   v0.9 -- why the extra checks: on the user's phone in v0.8, servers
 *   in the US, Netherlands, Poland and Switzerland all showed green
 *   ping badges of 1-3 ms while the real test marked them all Blocked.
 *   A 1-3 ms round trip to another continent is physically impossible
 *   from that location. What the old code measured was not the server:
 *   the TCP connect was answered in 1-3 ms by a middlebox on the path
 *   (it completes the handshake, then kills the data -- which is why
 *   the real test, which actually handshakes, correctly said Blocked).
 *   A failed or unanswered probe must NEVER be shown as a small green
 *   number, so the TCP step now requires the server itself to answer,
 *   and the ICMP step verifies the replying IP and discards impossible
 *   sub-5 ms values to public addresses.
 *
 *   v0.9.1 -- why the probe is advisory now: in v0.9 the ping test
 *   and the real test started FAILING servers that actually work --
 *   including the very server the user was connected to at the time.
 *   The same server had passed both tests in v0.8. The cause was the
 *   v0.9 probe rule: a server that uses tls-auth / tls-crypt silently
 *   drops the unsigned reset packet, so tcpPing reported Failed for a
 *   perfectly working server, and the real test's pre-check skipped
 *   it as Blocked for the same reason. Silence is not proof of death:
 *   only a refused connection (or a reply from something else on the
 *   path) is. So the probe now only ever *confirms* reachability; a
 *   silent server falls back to the connect time, and the full tunnel
 *   test decides whether it really works.
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
    /** Per-host budget in ms for the TCP connect (the ICMP -W 2 and the
        2 s reset-probe read add up to ~5 s worst case per host). */
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
        java.net.InetAddress expected = null;
        try {
            expected = java.net.InetAddress.getByName(host);
        } catch (Exception ignored) {
            // DNS failure: the ICMP check runs without an expected IP
            // (it will fail too) and the TCP step reports Failed.
        }
        Long icmp = icmpPing(host, expected);
        if (icmp != null) return new Result(profile, Status.OK, icmp);
        return tcpPing(profile, host, port, expected);
    }

    /** The "64 bytes from <ip>" line of the ping output, if any. */
    private static final Pattern PING_FROM =
            Pattern.compile("bytes from ([0-9a-fA-F.:]+)");

    /**
     * Returns ms on success, null when ICMP gave no usable answer.
     * Only a reply that really came from the target counts: the
     * replying IP must be the resolved target IP, and a sub-5 ms
     * reply to a public IP is impossible from a phone, so it is
     * discarded as spoofed (a middlebox answering for the target).
     * Local targets (loopback / LAN) are exempt from the 5 ms floor:
     * a 1 ms ping to a server on the same network is genuine.
     */
    static Long icmpPing(String host, java.net.InetAddress expected) {
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
            String output = out.toString();
            Matcher m = PING_TIME.matcher(output);
            if (!m.find()) {
                // No time= line: host silent (or ping binary blocked).
                return null;
            }
            long ms = Math.round(Double.parseDouble(m.group(1)));
            if (expected != null) {
                Matcher from = PING_FROM.matcher(output);
                if (from.find()) {
                    String replyIp = from.group(1);
                    String targetIp = expected.getHostAddress();
                    if (!replyIp.equals(targetIp)) {
                        // The reply came from something else on the
                        // path, not from the server: not a measurement.
                        return null;
                    }
                }
                if (ms < 5 && isPublicAddress(expected)) {
                    // A round trip to a public IP can never be under
                    // 5 ms from a phone: spoofed or local answer.
                    return null;
                }
            }
            return ms;
        } catch (Exception e) {
            return null;
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    /** True for a routable public address (not loopback / LAN / link-local). */
    private static boolean isPublicAddress(java.net.InetAddress addr) {
        return !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()
                && !addr.isSiteLocalAddress() && !addr.isMulticastAddress()
                && !addr.isAnyLocalAddress();
    }

    /**
     * TCP "ping": connect (3 s), then send one OpenVPN client-reset
     * packet and wait up to 2 s for ANY reply bytes.
     *
     * v0.9.1 — the reset probe is advisory only. A live OpenVPN
     * server answers a client reset with its own reset, and the
     * reported ms is then the time to the first reply byte. But a
     * server that never answers the probe is NOT failed: servers
     * using tls-auth / tls-crypt silently drop an unsigned reset,
     * and they work perfectly. When the probe goes unanswered, the
     * result falls back to the TCP connect time — a connect that
     * took a realistic time (>= 5 ms to a public host) proves the
     * server is listening and reachable. A connect answered in
     * 1-3 ms by a middlebox on the path still yields Timeout, never
     * a green number. The real test (full handshake) is the arbiter
     * of whether a server really works.
     */
    private static Result tcpPing(VpnProfile profile, String host, int port,
                                  java.net.InetAddress expected) {
        Socket socket = new Socket();
        long start = System.nanoTime();
        long connectMs = -1;
        try {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            connectMs = (System.nanoTime() - start) / 1_000_000L;
            socket.setSoTimeout(2000);
            byte[] payload = new byte[13];
            payload[0] = 0x38; // P_CONTROL_HARD_RESET_CLIENT_V2, key id 0
            java.util.Random random = new java.util.Random();
            for (int i = 0; i < 8; i++) payload[1 + i] = (byte) random.nextInt(256);
            // Bytes 9-12 stay 0: packet id 0.
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            byte[] buf = new byte[512];
            int read = socket.getInputStream().read(buf);
            if (read > 0) {
                long ms = (System.nanoTime() - start) / 1_000_000L;
                return new Result(profile, Status.OK, ms);
            }
            // No reply: the server closed without answering, or the
            // read returned -1. The connect succeeded, so the server
            // is listening — it just never answers a bare reset
            // (tls-auth / tls-crypt). Fall back to the connect time.
            return connectFallback(profile, connectMs, expected);
        } catch (java.net.SocketTimeoutException e) {
            if (connectMs >= 0) {
                // The read timed out: the connection is still open
                // and the server is silent (tls-auth). Same fallback.
                return connectFallback(profile, connectMs, expected);
            }
            // The connect itself timed out.
            return new Result(profile, Status.TIMEOUT, -1);
        } catch (java.net.UnknownHostException e) {
            return new Result(profile, Status.FAILED, -1);
        } catch (Exception e) {
            // Connection refused / reset etc.
            return new Result(profile, Status.FAILED, -1);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * v0.9.1 — the connect-time fallback for a server that never
     * answers the reset probe. A connect that took a realistic time
     * (>= 5 ms to a public host) proves the server is listening and
     * reachable, so report OK with the connect time. A connect
     * answered in under 5 ms to a public host was answered on the
     * path by a middlebox, not by the server: Timeout, never a green
     * number. Local hosts are exempt from the 5 ms floor, like ICMP.
     */
    private static Result connectFallback(VpnProfile profile, long connectMs,
                                          java.net.InetAddress expected) {
        if (expected != null && isPublicAddress(expected) && connectMs < 5) {
            return new Result(profile, Status.TIMEOUT, -1);
        }
        return new Result(profile, Status.OK, connectMs);
    }
}
