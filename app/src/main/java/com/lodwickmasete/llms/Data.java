package com.lodwickmasete.llms;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Models + storage. Everything lives in SharedPreferences (JSON) on the phone.
 * Nothing here talks to a network: human chats are only stored locally until the
 * node / P2P transport is wired (see ChatScreen.deliverToPeer).
 */
public class Data {

    static final String PREFS = "llms_prefs";

    // models / profile
    static final String KEY_MODEL = "selected_model";      // default model for new bot chats
    static final String KEY_MODELS_DIR = "models_dir";
    static final String KEY_NAME = "user_name";
    static final String KEY_INFO = "user_info";
    static final String KEY_ROLE = "user_role";
    static final String KEY_VILLAGE = "user_village";
    static final String KEY_STYLE = "user_style";          // 0 short, 1 balanced, 2 detailed
    static final String KEY_AVATAR = "user_avatar";
    static final String KEY_NODE_ID = "node_id";

    // storage
    static final String KEY_CONVS = "conversations";
    static final String KEY_CONTACTS = "contacts";
    static final String KEY_SEEDED = "contacts_seeded";
    static final String KEY_HOME_TAB = "home_tab";

    // privacy toggles
    static final String KEY_INCLUDE = "include_profile";   // share profile with the local AI (off by default)
    static final String KEY_SHOW_PHOTO = "show_photo";
    static final String KEY_SHOW_ABOUT = "show_about";
    static final String KEY_SHOW_ONLINE = "show_online";
    static final String KEY_ALLOW_VOICE = "allow_voice";
    static final String KEY_ALLOW_VIDEO = "allow_video";

    // menu toggles
    static final String KEY_CALL_CAM_ON = "call_cam_on";
    static final String KEY_CALL_SPEAKER = "call_speaker";
    static final String KEY_BG_SERVICE = "bg_service";     // stay reachable for calls in the background

    // call server (signalling)
    static final String KEY_SRV_HOST = "srv_host";
    static final String KEY_SRV_PORT = "srv_port";
    static final String KEY_ICE_SERVERS = "ice_servers";   // optional STUN/TURN urls, space separated

    // ── Models ────────────────────────────────────────────────────────────────
    static class Message {
        final boolean fromUser;
        String text;
        final String model;
        boolean error = false;
        boolean system = false;   // centered note, e.g. "Voice call · 02:13"
        long time;

        Message(boolean fromUser, String text, String model) {
            this.fromUser = fromUser;
            this.text = text;
            this.model = model;
            this.time = System.currentTimeMillis();
        }
    }

    static class Contact {
        String id;
        String name;
        String role;
        String info;
        String village;

        Contact(String id, String name, String role, String info, String village) {
            this.id = id;
            this.name = name;
            this.role = role == null ? "" : role;
            this.info = info == null ? "" : info;
            this.village = village == null ? "" : village;
        }
    }

    static class Chat {
        long id;
        boolean bot = true;
        String peerId = "";      // human chats: Contact.id
        String model = "";       // bot chats: .gguf file name
        long updated;
        final ArrayList<Message> msgs = new ArrayList<Message>();

        void add(Message m) {
            msgs.add(m);
            updated = System.currentTimeMillis();
        }

        /** Last real (non-system, non-empty) message, or null. */
        Message last() {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Message m = msgs.get(i);
                if (!m.system && m.text.length() > 0) return m;
            }
            return null;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final Context ctx;
    final ArrayList<Chat> chats = new ArrayList<Chat>();
    final ArrayList<Contact> contacts = new ArrayList<Contact>();
    private Bitmap avatarCache;
    private boolean avatarLoaded = false;

    Data(Context c) {
        ctx = c.getApplicationContext();
        loadContacts();
        loadChats();
    }

    SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean flag(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }

    void setFlag(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    String str(String key) {
        return prefs().getString(key, "");
    }

    // ── Avatar ────────────────────────────────────────────────────────────────
    File avatarFile() {
        return new File(ctx.getFilesDir(), "user.png");
    }

    /** The user's own picture, or null when none is set. */
    Bitmap avatar() {
        if (!avatarLoaded) {
            String path = str(KEY_AVATAR);
            avatarCache = (path.length() > 0 && new File(path).exists())
                    ? BitmapFactory.decodeFile(path) : null;
            avatarLoaded = true;
        }
        return avatarCache;
    }

    void invalidateAvatar() {
        avatarLoaded = false;
    }

    // ── Call server config ────────────────────────────────────────────────────
    String serverHost() {
        return str(KEY_SRV_HOST).trim();
    }

    int serverPort() {
        return prefs().getInt(KEY_SRV_PORT, CallServer.DEFAULT_PORT);
    }

    boolean serverConfigured() {
        return serverHost().length() > 0;
    }

    void saveServer(String host, int port) {
        prefs().edit().putString(KEY_SRV_HOST, host.trim()).putInt(KEY_SRV_PORT, port).apply();
    }

    /** Stable id shown to other people and used by the node. */
    String nodeId() {
        String id = str(KEY_NODE_ID);
        if (id.length() == 0) id = randomNodeId();
        prefs().edit().putString(KEY_NODE_ID, id).apply();
        return id;
    }

    private String randomNodeId() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder("LL-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ── Chats ─────────────────────────────────────────────────────────────────
    private void loadChats() {
        chats.clear();
        try {
            JSONArray all = new JSONArray(prefs().getString(KEY_CONVS, "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject c = all.optJSONObject(i);
                if (c == null) continue;
                Chat ch = new Chat();
                ch.id = c.optLong("id");
                ch.bot = !"human".equals(c.optString("kind", "bot")); // old saves have no "kind" = bot
                ch.peerId = c.optString("peer", "");
                ch.model = c.optString("model", "");
                ch.updated = c.optLong("updated");
                JSONArray msgs = c.optJSONArray("msgs");
                if (msgs != null) {
                    for (int j = 0; j < msgs.length(); j++) {
                        JSONObject o = msgs.optJSONObject(j);
                        if (o == null) continue;
                        String model = o.optString("m", "");
                        Message m = new Message(o.optBoolean("u"), o.optString("t", ""),
                                model.length() == 0 ? null : model);
                        m.error = o.optBoolean("e");
                        m.system = o.optBoolean("s");
                        m.time = o.optLong("ts", 0);
                        ch.msgs.add(m);
                    }
                }
                chats.add(ch);
            }
        } catch (JSONException ignored) {
        }
    }

    /** Saves every chat that has at least one message. */
    void saveChats() {
        try {
            JSONArray all = new JSONArray();
            for (int i = 0; i < chats.size(); i++) {
                Chat ch = chats.get(i);
                if (ch.msgs.isEmpty()) continue;
                JSONArray msgs = new JSONArray();
                for (int j = 0; j < ch.msgs.size(); j++) {
                    Message m = ch.msgs.get(j);
                    JSONObject o = new JSONObject();
                    o.put("u", m.fromUser);
                    o.put("t", m.text);
                    o.put("m", m.model == null ? "" : m.model);
                    o.put("e", m.error);
                    o.put("s", m.system);
                    o.put("ts", m.time);
                    msgs.put(o);
                }
                JSONObject c = new JSONObject();
                c.put("id", ch.id);
                c.put("kind", ch.bot ? "bot" : "human");
                c.put("peer", ch.peerId);
                c.put("model", ch.model);
                c.put("updated", ch.updated);
                c.put("msgs", msgs);
                all.put(c);
            }
            prefs().edit().putString(KEY_CONVS, all.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    Chat newBotChat(String model) {
        Chat c = new Chat();
        c.id = System.currentTimeMillis();
        c.bot = true;
        c.model = model == null ? "" : model;
        c.updated = c.id;
        chats.add(c);
        return c;
    }

    Chat chatForPeer(String peerId) {
        for (int i = 0; i < chats.size(); i++) {
            Chat c = chats.get(i);
            if (!c.bot && c.peerId.equals(peerId)) return c;
        }
        return null;
    }

    /** One conversation per person, like WhatsApp. Created on first open. */
    Chat peerChat(String peerId) {
        Chat c = chatForPeer(peerId);
        if (c != null) return c;
        c = new Chat();
        c.id = System.currentTimeMillis();
        c.bot = false;
        c.peerId = peerId;
        chats.add(c);
        return c;
    }

    /** Non-empty chats of one kind, newest first. */
    List<Chat> sortedChats(boolean bot) {
        List<Chat> list = new ArrayList<Chat>();
        for (int i = 0; i < chats.size(); i++) {
            Chat c = chats.get(i);
            if (c.bot == bot && !c.msgs.isEmpty()) list.add(c);
        }
        Collections.sort(list, new Comparator<Chat>() {
            @Override
            public int compare(Chat a, Chat b) {
                return a.updated < b.updated ? 1 : (a.updated > b.updated ? -1 : 0);
            }
        });
        return list;
    }

    void deleteChat(Chat c) {
        chats.remove(c);
        saveChats();
    }

    /** botsOnly = true clears AI chats only. */
    void clearChats(boolean botsOnly) {
        for (int i = chats.size() - 1; i >= 0; i--) {
            if (!botsOnly || chats.get(i).bot) chats.remove(i);
        }
        saveChats();
    }

    // ── Contacts (humans) ─────────────────────────────────────────────────────
    private void loadContacts() {
        contacts.clear();
        try {
            JSONArray all = new JSONArray(prefs().getString(KEY_CONTACTS, "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null) continue;
                contacts.add(new Contact(o.optString("id"), o.optString("name"), o.optString("role"),
                        o.optString("info"), o.optString("village")));
            }
        } catch (JSONException ignored) {
        }
        if (!prefs().getBoolean(KEY_SEEDED, false)) {
            seedDemoContacts();
        }
    }

    /** Demo people so the Humans tab is not empty before the node is wired. */
    private void seedDemoContacts() {
        contacts.add(new Contact("LL-7K2M-41A9", "Amina Wanjiru", "Teacher",
                "Teaches at the primary school. Happy to help with homework.", "Kisumu East"));
        contacts.add(new Contact("LL-D93P-Q2XT", "Baraka Otieno", "Nurse",
                "Dispensary night shifts. Ask me about first aid.", "Lake Road"));
        contacts.add(new Contact("LL-M4RE-88HN", "Mama Zawadi", "Shopkeeper",
                "Market side shop. Open every day except Sunday.", "Market Side"));
        contacts.add(new Contact("LL-X6VB-7C2K", "Juma Kariuki", "Mechanic",
                "Motorbikes, pumps and generators.", "Workshop Road"));
        saveContacts();
        prefs().edit().putBoolean(KEY_SEEDED, true).apply();
    }

    void saveContacts() {
        try {
            JSONArray all = new JSONArray();
            for (int i = 0; i < contacts.size(); i++) {
                Contact c = contacts.get(i);
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("role", c.role);
                o.put("info", c.info);
                o.put("village", c.village);
                all.put(o);
            }
            prefs().edit().putString(KEY_CONTACTS, all.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    Contact contact(String id) {
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.get(i).id.equals(id)) return contacts.get(i);
        }
        return null;
    }

    /** The contact with this node ID, created on the spot when someone new calls. */
    Contact ensureContact(String id, String name) {
        Contact c = contact(id);
        if (c != null) return c;
        return addContact(name == null || name.length() == 0 ? id : name, id);
    }

    /** Adds a system note ("Voice call · 2:13", "Missed video call") to the chat with this person. */
    void logCall(String peerId, String text) {
        Chat c = peerChat(peerId);
        Message m = new Message(false, text, null);
        m.system = true;
        c.add(m);
        saveChats();
    }

    Contact addContact(String name, String nodeId) {
        if (nodeId == null || nodeId.trim().length() == 0) nodeId = randomNodeId();
        Contact c = new Contact(nodeId.trim(), name.trim(), "", "", "");
        contacts.add(c);
        saveContacts();
        return c;
    }

    void removeContact(Contact c) {
        Chat ch = chatForPeer(c.id);
        if (ch != null) chats.remove(ch);
        contacts.remove(c);
        saveContacts();
        saveChats();
    }
}
