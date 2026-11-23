package com.lodwickmasete.llms;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding and checking the signalling server. Pure Java (no Android classes).
 *
 *  1. UDP broadcast "LLMS_DISCOVER" on port 47800 - the server answers with its port.
 *  2. If nothing answers (router blocks broadcast): probe every address of the local
 *     subnet(s) on the signalling port and look for the server's WebSocket hello.
 *  3. Always possible: type the IP / port by hand.
 */
final class CallServer {

    static final int DEFAULT_PORT = 3000;
    static final int DISCOVERY_PORT = 47800;
    static final String PATH = "/ws";

    private CallServer() {
    }

    static String wsUrl(String host, int port) {
        return "ws://" + host + ":" + port;
    }

    static class Found {
        String host;
        int port;
        String name;
        String how;     // "broadcast" or "scan"
    }

    static class Probe {
        boolean ok;
        String name = "";
        long millis;
        String error = "";
    }

    interface ProbeCallback {
        void onProbe(Probe result);
    }

    interface DiscoverCallback {
        /** found == null when nothing was found. Called on a background thread. */
        void onDone(Found found);
    }

    // ── Test connection: WebSocket handshake + "hello" from the server ────────
    static Probe probe(String host, int port, int connectTimeoutMs, int waitMs) {
        final Probe result = new Probe();
        final long start = System.currentTimeMillis();
        final Object lock = new Object();
        final String[] hello = new String[1];
        final String[] closeReason = new String[1];

        WsClient ws = new WsClient(host, port, PATH, connectTimeoutMs, waitMs + 1000, new WsClient.Listener() {
            @Override
            public void onOpen() {
            }

            @Override
            public void onMessage(String text) {
                synchronized (lock) {
                    if (hello[0] == null) hello[0] = text;
                    lock.notifyAll();
                }
            }

            @Override
            public void onClose(String reason) {
                synchronized (lock) {
                    closeReason[0] = reason;
                    lock.notifyAll();
                }
            }
        });
        ws.connect();
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + connectTimeoutMs + waitMs;
            while (hello[0] == null && closeReason[0] == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        ws.close();
        result.millis = System.currentTimeMillis() - start;
        if (hello[0] != null && hello[0].contains("\"hello\"")) {
            result.ok = true;
            result.name = jsonString(hello[0], "name");
        } else if (closeReason[0] != null) {
            result.error = niceError(closeReason[0]);
        } else {
            result.error = "No answer from " + host + ":" + port;
        }
        return result;
    }

    private static String niceError(String raw) {
        String l = raw == null ? "" : raw.toLowerCase();
        if (l.contains("refused")) return "Connection refused. Is the server running, and is the port right?";
        if (l.contains("timed out") || l.contains("timeout")) return "Timed out. Wrong IP, or a firewall is blocking the port.";
        if (l.contains("unreachable") || l.contains("no route")) return "Network unreachable. Is this phone on the same Wi-Fi?";
        if (l.contains("unknownhost") || l.contains("resolve")) return "Could not resolve that host name.";
        return raw;
    }

    // ── Discovery ─────────────────────────────────────────────────────────────
    static void discover(final int scanPort, final DiscoverCallback cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Found f = discoverBlocking(scanPort);
                cb.onDone(f);
            }
        }, "discover");
        t.setDaemon(true);
        t.start();
    }

    static Found discoverBlocking(int scanPort) {
        Found f = discoverByBroadcast(2500);
        if (f != null) return f;
        return discoverByScan(scanPort);
    }

    static Found discoverByBroadcast(int waitMs) {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            byte[] msg = "LLMS_DISCOVER".getBytes("UTF-8");

            List<InetAddress> targets = new ArrayList<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (InterfaceAddress ia : localAddresses()) {
                if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
            }
            // some routers drop the first broadcast: send twice
            for (int round = 0; round < 2; round++) {
                for (InetAddress a : targets) {
                    try {
                        sock.send(new DatagramPacket(msg, msg.length, a, DISCOVERY_PORT));
                    } catch (Exception ignored) {
                    }
                }
                Thread.sleep(150);
            }

            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                sock.setSoTimeout((int) Math.max(50, end - System.currentTimeMillis()));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                String text = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (text.contains("llms-server")) {
                    Found f = new Found();
                    f.host = p.getAddress().getHostAddress();
                    f.port = jsonInt(text, "port", DEFAULT_PORT);
                    f.name = jsonString(text, "name");
                    f.how = "broadcast";
                    return f;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
        return null;
    }

    /** Fallback when broadcast is filtered: try every host of the local subnet(s) on one port. */
    static Found discoverByScan(final int port) {
        final List<String> hosts = new ArrayList<String>();
        for (InterfaceAddress ia : localAddresses()) {
            byte[] me = ia.getAddress().getAddress();
            int prefix = ia.getNetworkPrefixLength();
            if (prefix < 22) prefix = 24;                  // never scan huge ranges
            long ip = ((me[0] & 0xffL) << 24) | ((me[1] & 0xffL) << 16) | ((me[2] & 0xffL) << 8) | (me[3] & 0xffL);
            long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            long net = ip & mask;
            long count = (1L << (32 - prefix));
            for (long i = 1; i < count - 1; i++) {
                long h = net + i;
                if (h == ip) continue;
                hosts.add(((h >> 24) & 255) + "." + ((h >> 16) & 255) + "." + ((h >> 8) & 255) + "." + (h & 255));
            }
        }
        if (hosts.isEmpty()) return null;

        final Found[] result = new Found[1];
        final AtomicBoolean done = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (final String h : hosts) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (done.get()) return;
                    // quick TCP check first (most addresses fail here in ~300 ms)
                    java.net.Socket s = new java.net.Socket();
                    try {
                        s.connect(new java.net.InetSocketAddress(h, port), 300);
                    } catch (Exception e) {
                        return;
                    } finally {
                        try {
                            s.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (done.get()) return;
                    Probe p = probe(h, port, 800, 1200);
                    if (p.ok && done.compareAndSet(false, true)) {
                        Found f = new Found();
                        f.host = h;
                        f.port = port;
                        f.name = p.name;
                        f.how = "scan";
                        result[0] = f;
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(25, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        return result[0];
    }

    /** IPv4 addresses of Wi-Fi / Ethernet / hotspot interfaces (not mobile data, not loopback). */
    static List<InterfaceAddress> localAddresses() {
        List<InterfaceAddress> all = new ArrayList<InterfaceAddress>();
        List<InterfaceAddress> preferred = new ArrayList<InterfaceAddress>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase();
                boolean lan = n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap")
                        || n.startsWith("swlan") || n.startsWith("en") || n.startsWith("wl");
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    all.add(ia);
                    if (lan) preferred.add(ia);
                }
            }
        } catch (Exception ignored) {
        }
        return preferred.isEmpty() ? all : preferred;
    }

    // ── Tiny JSON field readers (these replies are flat and tiny) ─────────────
    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static int jsonInt(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
