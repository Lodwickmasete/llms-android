package com.lodwickmasete.llms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Tiny WebSocket client (RFC 6455, text frames, ws:// only) on a plain Socket.
 * No dependencies. Plain sockets are also not affected by Android's "cleartext HTTP" rule.
 * Pure Java on purpose, so it can be tested on a PC.
 */
class WsClient {

    interface Listener {
        void onOpen();

        void onMessage(String text);

        /** Called once, when the connection ends for any reason. */
        void onClose(String reason);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 1 << 20;

    private final String host;
    private final int port;
    private final String path;
    private final Listener listener;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private volatile Socket socket;
    private volatile boolean closed = false;
    private boolean closeReported = false;
    private OutputStream out;
    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();

    WsClient(String host, int port, String path, int connectTimeoutMs, int readTimeoutMs, Listener l) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.listener = l;
    }

    /** Connects on a background thread; results arrive through the Listener (on that thread). */
    void connect() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "ws-" + host);
        t.setDaemon(true);
        t.start();
    }

    boolean isOpen() {
        return out != null && !closed;
    }

    void close() {
        closed = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    boolean send(String text) {
        if (!isOpen()) return false;
        try {
            writeFrame(0x1, text.getBytes("UTF-8"));
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────
    private void runLoop() {
        String reason = "closed";
        try {
            Socket s = new Socket();
            socket = s;
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout(readTimeoutMs);
            InputStream in = s.getInputStream();
            OutputStream o = s.getOutputStream();

            // Handshake
            byte[] keyBytes = new byte[16];
            random.nextBytes(keyBytes);
            String key = b64(keyBytes);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            o.write(req.getBytes("UTF-8"));
            o.flush();

            String head = readHeaders(in);
            if (!head.startsWith("HTTP/1.1 101")) {
                throw new IOException("Server refused WebSocket (" + head.split("\r\n")[0] + ")");
            }
            String expected = b64(MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes("UTF-8")));
            if (!head.contains(expected)) throw new IOException("Bad WebSocket handshake");

            synchronized (writeLock) {
                out = o;
            }
            if (closed) throw new IOException("closed");
            listener.onOpen();
            readFrames(in);
            reason = "Server closed the connection";
        } catch (java.net.SocketTimeoutException e) {
            reason = closed ? "closed" : "Timed out";
        } catch (Exception e) {
            reason = closed ? "closed" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            closed = true;
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
            reportClose(reason);
        }
    }

    private synchronized void reportClose(String reason) {
        if (closeReported) return;
        closeReported = true;
        listener.onClose(reason);
    }

    private String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed during handshake");
            sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("Handshake too long");
            if ((b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))) state++;
            else state = (b == '\r') ? 1 : 0;
        }
        return sb.toString();
    }

    private void readFrames(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream message = new java.io.ByteArrayOutputStream();
        for (;;) {
            int b0 = readByte(in);
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (len > MAX_MESSAGE) throw new IOException("Message too large");
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask, 4);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload, payload.length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            if (op == 0x8) {                       // close
                return;
            } else if (op == 0x9) {                // ping -> pong
                writeFrame(0xA, payload);
            } else if (op == 0xA) {                // pong
                continue;
            } else {                               // text / binary / continuation
                message.write(payload, 0, payload.length);
                if (message.size() > MAX_MESSAGE) throw new IOException("Message too large");
                if (fin) {
                    byte[] all = message.toByteArray();
                    message.reset();
                    if (op != 0x2) listener.onMessage(new String(all, "UTF-8"));
                }
            }
        }
    }

    private int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("Connection closed");
        return b;
    }

    private void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Connection closed");
            off += n;
        }
    }

    /** Client frames must be masked. */
    private void writeFrame(int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        int len = payload.length;
        byte[] head;
        if (len < 126) {
            head = new byte[]{(byte) (0x80 | opcode), (byte) (0x80 | len)};
        } else if (len < 65536) {
            head = new byte[]{(byte) (0x80 | opcode), (byte) (0x80 | 126), (byte) (len >> 8), (byte) len};
        } else {
            head = new byte[10];
            head[0] = (byte) (0x80 | opcode);
            head[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) head[2 + i] = (byte) (((long) len) >> (8 * (7 - i)));
        }
        byte[] body = new byte[4 + len];
        System.arraycopy(mask, 0, body, 0, 4);
        for (int i = 0; i < len; i++) body[4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        synchronized (writeLock) {
            if (out == null) throw new IOException("not open");
            out.write(head);
            out.write(body);
            out.flush();
        }
    }

    private static String b64(byte[] data) {
        final String tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b = (data[i] & 0xff) << 16;
            if (i + 1 < data.length) b |= (data[i + 1] & 0xff) << 8;
            if (i + 2 < data.length) b |= (data[i + 2] & 0xff);
            sb.append(tbl.charAt((b >> 18) & 63)).append(tbl.charAt((b >> 12) & 63));
            sb.append(i + 1 < data.length ? tbl.charAt((b >> 6) & 63) : '=');
            sb.append(i + 2 < data.length ? tbl.charAt(b & 63) : '=');
        }
        return sb.toString();
    }
}
