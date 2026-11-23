package com.lodwickmasete.llms;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * One chat screen for both kinds of conversation:
 *  - local bot: welcome message, replies streamed from BotBrain (on-device)
 *  - human: "no messages yet" state, call buttons, messages meant for the village node
 */
class ChatScreen {

    private final MainActivity act;
    private final Data data;
    private final BotBrain brain;
    private final View root;

    private final ImageView backButton, headerAvatar, voiceButton, videoButton, clearButton, sendButton, welcomeAvatar;
    private final TextView title, subtitle, welcome, welcomeSub, welcomeNote, disclaimer;
    private final View headerInfo, welcomeBox, inputBox;
    private final ListView list;
    private final EditText input;
    private final Adapter adapter = new Adapter();

    private Data.Chat chat;
    private Data.Contact peer;          // null for bot chats
    private boolean generating = false;
    private Bitmap incomingAvatar;      // small avatar beside incoming messages

    ChatScreen(final MainActivity act, Data data, BotBrain brain) {
        this.act = act;
        this.data = data;
        this.brain = brain;
        root = act.findViewById(R.id.chat_screen);

        backButton = (ImageView) act.findViewById(R.id.chat_back);
        headerAvatar = (ImageView) act.findViewById(R.id.chat_avatar);
        headerInfo = act.findViewById(R.id.chat_header_info);
        title = (TextView) act.findViewById(R.id.chat_title);
        subtitle = (TextView) act.findViewById(R.id.chat_subtitle);
        voiceButton = (ImageView) act.findViewById(R.id.chat_voice_btn);
        videoButton = (ImageView) act.findViewById(R.id.chat_video_btn);
        welcomeBox = act.findViewById(R.id.welcome_box);
        welcomeAvatar = (ImageView) act.findViewById(R.id.welcome_avatar);
        welcome = (TextView) act.findViewById(R.id.welcome_text);
        welcomeSub = (TextView) act.findViewById(R.id.welcome_sub);
        welcomeNote = (TextView) act.findViewById(R.id.welcome_note);
        list = (ListView) act.findViewById(R.id.chat_list);
        input = (EditText) act.findViewById(R.id.input_question);
        inputBox = act.findViewById(R.id.inputquestion_background);
        clearButton = (ImageView) act.findViewById(R.id.clear_button);
        sendButton = (ImageView) act.findViewById(R.id.sendquestion_button);
        disclaimer = (TextView) act.findViewById(R.id.chat_disclaimer);

        input.setBackground(null);
        bindFocusBorder();
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.closeChat();
            }
        });
        headerInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (peer != null) showPeerProfile();
            }
        });
        voiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (peer != null) act.startCall(peer, false);
            }
        });
        videoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (peer != null) act.startCall(peer, true);
            }
        });
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
            }
        });
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSendClicked();
            }
        });
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                clearButton.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
    }

    private void bindFocusBorder() {
        inputBox.setBackground(Ui.box(act, 35, Color.BLACK, Ui.BORDER, 1));
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                inputBox.setBackground(hasFocus
                        ? Ui.box(act, 35, Color.BLACK, 0xFFFFFFFF, 2)
                        : Ui.box(act, 35, Color.BLACK, Ui.BORDER, 1));
            }
        });
    }

    View root() {
        return root;
    }

    /** A chat is loaded (it may be hidden behind the call screen). */
    boolean hasChat() {
        return chat != null;
    }

    // ── Open / close ──────────────────────────────────────────────────────────
    void open(Data.Chat c) {
        chat = c;
        peer = c.bot ? null : data.contact(c.peerId);
        generating = false;
        input.setText("");

        boolean bot = c.bot;
        voiceButton.setVisibility(bot ? View.GONE : View.VISIBLE);
        videoButton.setVisibility(bot ? View.GONE : View.VISIBLE);
        disclaimer.setVisibility(bot ? View.VISIBLE : View.GONE);

        if (bot) {
            String name = Ui.botName(c.model);
            headerAvatar.setImageBitmap(Ui.avatar(c.model, name, true, Ui.dp(act, 40)));
            incomingAvatar = Ui.avatar(c.model, name, true, Ui.dp(act, 28));
        } else {
            String name = peer == null ? "Unknown" : peer.name;
            headerAvatar.setImageBitmap(Ui.avatar(c.peerId, name, false, Ui.dp(act, 40)));
            incomingAvatar = Ui.avatar(c.peerId, name, false, Ui.dp(act, 28));
        }
        updateHeader();
        refresh();
    }

    /** Leaves the chat: stops any reply in progress and saves. */
    void close() {
        if (chat == null) return;
        if (generating) {
            brain.cancel();
            generating = false;
            dropEmptyTrailingBot();
        }
        if (chat.msgs.isEmpty()) {
            data.chats.remove(chat);   // never started: do not keep an empty chat around
        } else {
            data.saveChats();
        }
        chat = null;
        peer = null;
    }

    // ── Header / empty state ──────────────────────────────────────────────────
    void updateHeader() {
        if (chat == null) return;
        if (chat.bot) {
            title.setText(chat.model.length() == 0 ? "No model" : Ui.botName(chat.model));
            subtitle.setText(brain.isLoading() ? "Loading model…"
                    : (generating ? "Typing…" : "On-device · private"));
        } else {
            title.setText(peer == null ? "Unknown" : peer.name);
            subtitle.setText(peer == null || peer.role.length() == 0 ? "Via village node" : peer.role);
        }
    }

    private void updateWelcome() {
        String name = data.str(Data.KEY_NAME);
        if (chat.bot) {
            welcomeAvatar.setVisibility(View.GONE);
            welcomeNote.setVisibility(View.GONE);
            welcome.setText(name.length() == 0 ? "Hello!" : "Hello, " + name + "!");
            Ui.applyGradient(welcome);
            boolean noModel = chat.model.length() == 0;
            welcomeSub.setText(noModel
                    ? "No model found. Put a .gguf file in:\n" + brain.modelsDirPath()
                    : "How can I help you today?");
        } else {
            String who = peer == null ? "this person" : peer.name;
            Ui.clearGradient(welcome);
            welcomeAvatar.setVisibility(View.VISIBLE);
            welcomeAvatar.setImageBitmap(Ui.avatar(chat.peerId, who, false, Ui.dp(act, 96)));
            welcome.setText(who);
            StringBuilder sub = new StringBuilder("No messages yet.\nSay hello to ").append(who).append(".");
            welcomeSub.setText(sub.toString());
            StringBuilder note = new StringBuilder();
            if (peer != null && peer.village.length() > 0) note.append(peer.village).append("\n");
            note.append("Messages and calls with people go through the village node.\nYour AI chats never leave this phone.");
            welcomeNote.setText(note.toString());
            welcomeNote.setVisibility(View.VISIBLE);
        }
    }

    private void showPeerProfile() {
        StringBuilder sb = new StringBuilder();
        if (peer.role.length() > 0) sb.append(peer.role).append("\n");
        if (peer.village.length() > 0) sb.append(peer.village).append("\n");
        if (peer.info.length() > 0) sb.append("\n").append(peer.info).append("\n");
        sb.append("\nNode ID: ").append(peer.id);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(peer.name)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    void refresh() {
        if (chat == null) return;
        boolean empty = chat.msgs.isEmpty();
        welcomeBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) updateWelcome();
        updateHeader();
        sendButton.setAlpha(generating ? 0.5f : 1f);
        adapter.notifyDataSetChanged();
    }

    // ── Sending ───────────────────────────────────────────────────────────────
    private void onSendClicked() {
        if (chat == null) return;
        if (generating) {            // tapping while the bot is replying = stop
            stopGenerating();
            return;
        }
        String text = input.getText().toString().trim();
        if (text.length() == 0) {
            act.toast("Type a message first");
            return;
        }
        if (chat.bot) {
            sendToBot(text);
        } else {
            sendToHuman(text);
        }
    }

    // Human chats ----------------------------------------------------------
    private void sendToHuman(String text) {
        if (peer == null) return;
        Data.Message m = new Data.Message(true, text, null);
        chat.add(m);
        data.saveChats();
        input.setText("");
        refresh();
        deliverToPeer(peer, m);
    }

    /**
     * Hook for the real transport. This is the ONLY place a message leaves the phone:
     * send it to the village node / server here. For now it stays stored locally.
     */
    private void deliverToPeer(Data.Contact to, Data.Message m) {
        // TODO: wire to the node (P2P Wi-Fi) / server.
    }

    // Bot chats ------------------------------------------------------------
    private void sendToBot(String question) {
        String model = chat.model;
        if (model.length() == 0 || !brain.listModels().contains(model)) {
            String fallback = brain.defaultModel();
            if (fallback == null) {
                act.toast("No model found. Put a .gguf file in " + brain.modelsDirPath());
                act.openMenu();
                return;
            }
            model = fallback;
            chat.model = model;
        }
        if (brain.isBusy()) {
            act.toast("Finishing the previous reply, try again in a moment");
            return;
        }
        chat.add(new Data.Message(true, question, null));
        input.setText("");
        data.saveChats();
        startReply(question, model);
    }

    private void startReply(String question, String model) {
        final Data.Message bot = new Data.Message(false, "", model);
        chat.add(bot);
        generating = true;
        refresh();

        final Data.Chat thisChat = chat;
        brain.generate(chat, model, question, new BotBrain.Listener() {
            @Override
            public void onToken(String token) {
                if (chat != thisChat) return;
                appendToken(bot, token);
            }

            @Override
            public void onDone(String error) {
                if (chat != thisChat) return;
                if (error != null && bot.text.isEmpty()) {
                    bot.text = error;
                    bot.error = true;
                }
                generating = false;
                data.saveChats();
                refresh();
            }
        });
    }

    private void appendToken(Data.Message bot, String token) {
        if (bot.text.isEmpty()) {
            int i = 0;
            while (i < token.length() && Character.isWhitespace(token.charAt(i))) i++;
            token = token.substring(i);
            if (token.isEmpty()) return;
        }
        bot.text = bot.text + token;
        adapter.notifyDataSetChanged();
        updateHeader();
    }

    private void stopGenerating() {
        if (generating) {
            brain.cancel();
            generating = false;
            dropEmptyTrailingBot();
            data.saveChats();
        }
        refresh();
    }

    private void dropEmptyTrailingBot() {
        if (chat == null || chat.msgs.isEmpty()) return;
        Data.Message last = chat.msgs.get(chat.msgs.size() - 1);
        if (!last.fromUser && !last.system && last.text.isEmpty()) chat.msgs.remove(chat.msgs.size() - 1);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return chat == null ? 0 : chat.msgs.size();
        }

        @Override
        public Data.Message getItem(int i) {
            return chat.msgs.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(act).inflate(R.layout.chat_item, parent, false);
            }
            final Data.Message m = getItem(position);

            TextView system = (TextView) convertView.findViewById(R.id.item_system);
            View row = convertView.findViewById(R.id.item_row);
            ImageView avatar = (ImageView) convertView.findViewById(R.id.item_avatar);
            View bubble = convertView.findViewById(R.id.item_bubble);
            TextView text = (TextView) convertView.findViewById(R.id.item_text);
            TextView meta = (TextView) convertView.findViewById(R.id.item_meta);

            if (m.system) {
                system.setVisibility(View.VISIBLE);
                system.setBackground(Ui.box(act, 12, 0xFF141414, 0, 0));
                system.setText(m.text);
                row.setVisibility(View.GONE);
                return convertView;
            }
            system.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);

            boolean out = m.fromUser;
            ((LinearLayout) row).setGravity(out ? Gravity.END | Gravity.BOTTOM : Gravity.START | Gravity.BOTTOM);

            // Incoming: small avatar on the first message of a run
            if (out) {
                avatar.setVisibility(View.GONE);
            } else {
                Data.Message prev = position > 0 ? chat.msgs.get(position - 1) : null;
                boolean first = prev == null || prev.fromUser || prev.system;
                avatar.setVisibility(first ? View.VISIBLE : View.INVISIBLE);
                avatar.setImageBitmap(incomingAvatar);
            }

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(out ? 0xFF1F3B54 : 0xFF202020);
            float r = Ui.dp(act, 16);
            float s = Ui.dp(act, 4);
            bg.setCornerRadii(out
                    ? new float[]{r, r, s, s, r, r, r, r}
                    : new float[]{s, s, r, r, r, r, r, r});
            bubble.setBackground(bg);

            int maxW = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.72f);
            text.setMaxWidth(maxW);
            text.setTextColor(m.error ? 0xFFEF9A9A : (out ? 0xFFFFFFFF : 0xFFE0E0E0));
            text.setText(m.text.isEmpty() ? "…" : m.text);

            String time = Ui.clock(m.time);
            meta.setText(out && !chat.bot ? time + "  ✓" : time);

            View.OnLongClickListener copy = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
                        act.toast("Copied");
                    }
                    return true;
                }
            };
            bubble.setOnLongClickListener(copy);
            text.setOnLongClickListener(copy);
            return convertView;
        }
    }
}
