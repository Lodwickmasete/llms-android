package com.lodwickmasete.llms;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Home: a WhatsApp-style chat list with two tabs - Local bots / Humans.
 * Every row shows an avatar, the name, the last message and the time.
 */
class HomeScreen {

    private static final int TAB_BOTS = 0;
    private static final int TAB_HUMANS = 1;

    private static class Row {
        String name;
        String preview;
        String time;
        boolean bot;
        boolean hint;        // preview is a hint, not a message (shown dimmer)
        long sort;
        String avatarSeed;
        Data.Chat chat;
        Data.Contact contact;
    }

    private final MainActivity act;
    private final Data data;
    private final BotBrain brain;
    private final View root;

    private final ImageView menuButton, accountButton, fab;
    private final TextView status, tabBots, tabHumans, empty;
    private final View tabs;
    private final ListView list;
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private final RowAdapter adapter = new RowAdapter();
    private int tab;

    HomeScreen(final MainActivity act, Data data, BotBrain brain) {
        this.act = act;
        this.data = data;
        this.brain = brain;
        root = act.findViewById(R.id.home_screen);

        menuButton = (ImageView) act.findViewById(R.id.home_menu_button);
        accountButton = (ImageView) act.findViewById(R.id.home_account_button);
        status = (TextView) act.findViewById(R.id.home_status);
        tabs = act.findViewById(R.id.home_tabs);
        tabBots = (TextView) act.findViewById(R.id.tab_bots);
        tabHumans = (TextView) act.findViewById(R.id.tab_humans);
        list = (ListView) act.findViewById(R.id.home_list);
        empty = (TextView) act.findViewById(R.id.home_empty);
        fab = (ImageView) act.findViewById(R.id.home_fab);

        tab = data.prefs().getInt(Data.KEY_HOME_TAB, TAB_BOTS);
        tabs.setBackground(Ui.box(act, 24, Ui.CARD, Ui.BORDER, 1));
        fab.setBackground(Ui.blueCircle());
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);

        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HomeScreen.this.act.openMenu();
            }
        });
        accountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HomeScreen.this.act.openProfile();
            }
        });
        tabBots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_BOTS);
            }
        });
        tabHumans.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_HUMANS);
            }
        });
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tab == TAB_BOTS) {
                    startNewBotChat();
                } else {
                    addContactDialog();
                }
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openRow(rows.get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                rowMenu(rows.get(position));
                return true;
            }
        });
    }

    View root() {
        return root;
    }

    private void selectTab(int t) {
        tab = t;
        data.prefs().edit().putInt(Data.KEY_HOME_TAB, t).apply();
        refresh();
    }

    // ── Build the list ────────────────────────────────────────────────────────
    void refresh() {
        // header: avatar + connection status
        Bitmap me = data.avatar();
        if (me != null) {
            accountButton.setImageBitmap(me);
        } else {
            accountButton.setImageResource(R.drawable.ic_account);
        }
        Signaling sg = Core.get(act).signaling;
        status.setText(!data.serverConfigured() ? "On-device only · call server not set up"
                : (sg.isConnected() ? "Call server connected" : sg.state() == Signaling.CONNECTING
                ? "Connecting to call server…" : "Call server not connected"));

        styleTab(tabBots, tab == TAB_BOTS);
        styleTab(tabHumans, tab == TAB_HUMANS);

        rows.clear();
        if (tab == TAB_BOTS) {
            List<Data.Chat> chats = data.sortedChats(true);
            for (int i = 0; i < chats.size(); i++) {
                Data.Chat c = chats.get(i);
                Row r = new Row();
                r.bot = true;
                r.chat = c;
                r.name = Ui.botName(c.model);
                r.avatarSeed = c.model;
                r.preview = previewOf(c);
                r.time = Ui.day(c.updated);
                r.sort = c.updated;
                rows.add(r);
            }
        } else {
            for (int i = 0; i < data.contacts.size(); i++) {
                Data.Contact p = data.contacts.get(i);
                Data.Chat c = data.chatForPeer(p.id);
                Row r = new Row();
                r.bot = false;
                r.contact = p;
                r.name = p.name;
                r.avatarSeed = p.id;
                if (c != null && !c.msgs.isEmpty()) {
                    r.preview = previewOf(c);
                    r.time = Ui.day(c.updated);
                    r.sort = c.updated;
                } else {
                    r.preview = "No messages yet · tap to say hello";
                    r.hint = true;
                    r.time = "";
                    r.sort = 0;
                }
                rows.add(r);
            }
            Collections.sort(rows, new Comparator<Row>() {
                @Override
                public int compare(Row a, Row b) {
                    if (a.sort != b.sort) return a.sort < b.sort ? 1 : -1;
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
        }
        adapter.notifyDataSetChanged();

        boolean none = rows.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) {
            if (tab == TAB_BOTS) {
                empty.setText(brain.listModels().isEmpty()
                        ? "No model found.\nPut a .gguf file in:\n" + brain.modelsDirPath()
                        : "No chats with a local bot yet.\nTap + to start one.");
            } else {
                empty.setText("No people added yet.\nTap + to add someone by name and node ID.");
            }
        }
    }

    private String previewOf(Data.Chat c) {
        Data.Message last = c.last();
        if (last == null) return "";
        String t = last.text.replace('\n', ' ');
        return last.fromUser ? "You: " + t : t;
    }

    private void styleTab(TextView t, boolean on) {
        t.setBackground(on ? Ui.box(act, 20, 0xFF10202B, Ui.ACCENT, 1) : null);
        t.setTextColor(on ? Ui.ACCENT : 0xFFBDBDBD);
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private void openRow(Row r) {
        if (r.bot) {
            act.openChat(r.chat);
        } else {
            act.openChat(data.peerChat(r.contact.id));
        }
    }

    private void rowMenu(final Row r) {
        if (r.bot) {
            act.confirm("Delete conversation?", "This cannot be undone.", "Delete", new Runnable() {
                @Override
                public void run() {
                    data.deleteChat(r.chat);
                    refresh();
                }
            });
            return;
        }
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(r.name)
                .setItems(new String[]{"Clear chat", "Remove contact"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            Data.Chat c = data.chatForPeer(r.contact.id);
                            if (c != null) data.deleteChat(c);
                            refresh();
                        } else {
                            act.confirm("Remove " + r.name + "?", "Their chat will be deleted too.", "Remove",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            data.removeContact(r.contact);
                                            refresh();
                                        }
                                    });
                        }
                    }
                })
                .show();
    }

    /** + on the bots tab: start a new chat (asks which model when there are several). */
    private void startNewBotChat() {
        final List<String> models = brain.listModels();
        if (models.isEmpty()) {
            act.toast("No model found. Put a .gguf file in " + brain.modelsDirPath());
            act.openMenu();
            return;
        }
        if (models.size() == 1) {
            act.openChat(data.newBotChat(models.get(0)));
            return;
        }
        String[] names = new String[models.size()];
        for (int i = 0; i < names.length; i++) names[i] = Ui.botName(models.get(i));
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("New chat with")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        act.openChat(data.newBotChat(models.get(which)));
                    }
                })
                .show();
    }

    /** + on the humans tab: add a person by name and node ID. */
    private void addContactDialog() {
        final EditText name = new EditText(act);
        name.setHint("Name");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(Ui.FAINT);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        final EditText node = new EditText(act);
        node.setHint("Node ID (optional)");
        node.setTextColor(Color.WHITE);
        node.setHintTextColor(Ui.FAINT);
        node.setSingleLine(true);
        node.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        wrap.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(node, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add person")
                .setMessage("Ask them for the node ID shown on their profile.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        if (n.length() == 0) {
                            act.toast("Enter a name");
                            return;
                        }
                        data.addContact(n, node.getText().toString().trim());
                        refresh();
                    }
                })
                .show();
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(act).inflate(R.layout.home_item, parent, false);
            }
            Row r = getItem(position);
            ((ImageView) convertView.findViewById(R.id.row_avatar)).setImageBitmap(
                    Ui.avatar(r.avatarSeed == null ? r.name : r.avatarSeed, r.name, r.bot, Ui.dp(act, 54)));
            ((TextView) convertView.findViewById(R.id.row_name)).setText(r.name);
            ((TextView) convertView.findViewById(R.id.row_time)).setText(r.time);
            TextView preview = (TextView) convertView.findViewById(R.id.row_preview);
            preview.setText(r.preview);
            preview.setTextColor(r.hint ? Ui.FAINT : 0xFF9E9E9E);
            preview.setTypeface(r.hint ? Typeface.defaultFromStyle(Typeface.ITALIC) : Typeface.DEFAULT);
            return convertView;
        }
    }
}
