package com.lodwickmasete.llms;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The connection to the signalling server. One per app (see Core).
 * Connects, registers this phone under its node ID, keeps the link alive with pings,
 * reconnects by itself, and hands every other message to the listeners (CallManager).
 * All callbacks arrive on the main thread; all socket writes happen on one worker thread.
 */
class Signaling {

    static final int DISCONNECTED = 0;
    static final int CONNECTING = 1;
    static final int CONNECTED = 2;

    interface Listener {
        void onSignalState(int state, String detail);

        void onSignal(JSONObject msg);
    }

    interface PeersCallback {
        /** peers: list of {id, name}; null with an error text when it failed. */
        void onPeers(List<String[]> peers, String error);
    }

    private static final int PING_MS = 15000;

    private final Data data;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private WsClient ws;
    private int gen = 0;                 // invalidates callbacks of old connections
    private boolean wanted = false;
    private int state = DISCONNECTED;
    private String detail = "Not started";
    private int backoffMs = 1000;
    private PeersCallback pendingPeers;

    Signaling(Data data) {
        this.data = data;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    int state() {
        return state;
    }

    boolean isConnected() {
        return state == CONNECTED;
    }

    String detail() {
        return detail;
    }

    /** "Connected · ws://192.168.8.101:3000" and friends, for the settings and the home screen. */
    String statusLine() {
        if (!data.serverConfigured()) return "Call server not set up";
        String url = CallServer.wsUrl(data.serverHost(), data.serverPort());
        if (state == CONNECTED) return "Connected · " + url;
        if (state == CONNECTING) return "Connecting · " + url;
        return "Not connected · " + url;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    /** Starts (and keeps) the connection. Safe to call many times. */
    void start() {
        wanted = true;
        if (ws != null) return;
        if (!data.serverConfigured()) {
            setState(DISCONNECTED, "No server address yet");
            return;
        }
        connectNow();
    }

    void stop() {
        wanted = false;
        gen++;
        main.removeCallbacks(reconnectTask);
        main.removeCallbacks(pingTask);
        closeSocket();
        setState(DISCONNECTED, "Stopped");
    }

    /** Server address or profile changed: reconnect right away. */
    void restart() {
        gen++;
        main.removeCallbacks(reconnectTask);
        main.removeCallbacks(pingTask);
        closeSocket();
        backoffMs = 1000;
        wanted = true;
        if (data.serverConfigured()) {
            connectNow();
        } else {
            setState(DISCONNECTED, "No server address yet");
        }
    }

    /** Re-sends the name after the profile was edited. */
    void updateProfile() {
        if (state == CONNECTED) sendRegister();
    }

    private void closeSocket() {
        WsClient w = ws;
        ws = null;
        if (w != null) w.close();
    }

    private void connectNow() {
        final int myGen = ++gen;
        final String host = data.serverHost();
        final int port = data.serverPort();
        setState(CONNECTING, "Connecting to " + CallServer.wsUrl(host, port));

        WsClient w = new WsClient(host, port, CallServer.PATH, 5000, PING_MS * 3, new WsClient.Listener() {
            @Override
            public void onOpen() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (myGen == gen) sendRegister();
                    }
                });
            }

            @Override
            public void onMessage(final String text) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (myGen == gen) handle(text);
                    }
                });
            }

            @Override
            public void onClose(final String reason) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (myGen != gen) return;
                        ws = null;
                        main.removeCallbacks(pingTask);
                        setState(DISCONNECTED, wanted ? reason : detail);
                        failPeers("Connection lost");
                        if (wanted) scheduleReconnect();
                    }
                });
            }
        });
        ws = w;
        w.connect();
    }

    private void scheduleReconnect() {
        main.removeCallbacks(reconnectTask);
        main.postDelayed(reconnectTask, backoffMs);
        backoffMs = Math.min(backoffMs * 2, 10000);
    }

    private final Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {
            if (wanted && ws == null && data.serverConfigured()) connectNow();
        }
    };

    private final Runnable pingTask = new Runnable() {
        @Override
        public void run() {
            if (state == CONNECTED) {
                send(obj("ping"));
                main.postDelayed(this, PING_MS);
            }
        }
    };

    // ── Messages ──────────────────────────────────────────────────────────────
    private void sendRegister() {
        JSONObject o = obj("register");
        try {
            String name = data.str(Data.KEY_NAME);
            o.put("id", data.nodeId());
            o.put("name", name.length() == 0 ? "Someone" : name);
        } catch (JSONException ignored) {
        }
        sendRaw(o);
    }

    private void handle(String text) {
        JSONObject m;
        try {
            m = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        String t = m.optString("t");
        if ("registered".equals(t)) {
            backoffMs = 1000;
            setState(CONNECTED, "Connected");
            main.removeCallbacks(pingTask);
            main.postDelayed(pingTask, PING_MS);
        } else if ("pong".equals(t) || "hello".equals(t)) {
            // keep-alive / greeting: nothing to do
        } else if ("replaced".equals(t)) {
            wanted = false;                       // this node ID signed in somewhere else; do not fight
            setState(DISCONNECTED, "Signed in on another device");
        } else if ("peers".equals(t)) {
            deliverPeers(m);
        } else if ("error".equals(t)) {
            detail = m.optString("msg");
        } else {
            for (Listener l : listeners) l.onSignal(m);
        }
    }

    /** Sends a message to the server. Returns false when not connected. */
    boolean send(JSONObject o) {
        if (state != CONNECTED || ws == null) return false;
        sendRaw(o);
        return true;
    }

    private void sendRaw(final JSONObject o) {
        final WsClient w = ws;
        if (w == null) return;
        sender.execute(new Runnable() {
            @Override
            public void run() {
                w.send(o.toString());
            }
        });
    }

    static JSONObject obj(String type) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", type);
        } catch (JSONException ignored) {
        }
        return o;
    }

    // ── Who is online (used to add people in the settings) ────────────────────
    void requestPeers(final PeersCallback cb) {
        if (state != CONNECTED) {
            cb.onPeers(null, "Not connected to the call server");
            return;
        }
        pendingPeers = cb;
        send(obj("peers"));
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingPeers == cb) failPeers("The server did not answer");
            }
        }, 4000);
    }

    private void deliverPeers(JSONObject m) {
        PeersCallback cb = pendingPeers;
        pendingPeers = null;
        if (cb == null) return;
        List<String[]> list = new ArrayList<String[]>();
        JSONArray arr = m.optJSONArray("peers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null) list.add(new String[]{p.optString("id"), p.optString("name")});
            }
        }
        cb.onPeers(list, null);
    }

    private void failPeers(String error) {
        PeersCallback cb = pendingPeers;
        pendingPeers = null;
        if (cb != null) cb.onPeers(null, error);
    }

    private void setState(int s, String d) {
        state = s;
        detail = d;
        for (Listener l : listeners) l.onSignalState(s, d);
    }
}
