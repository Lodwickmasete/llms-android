package com.lodwickmasete.llms;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The "Call server" section of the Menu: status, manual address, automatic discovery,
 * test connection, who is online, background mode. Saved settings are used at every start.
 */
final class ServerSettings {

    private ServerSettings() {
    }

    /** Adds the section to the menu. {@code refresh} rebuilds the menu after a change. */
    static void addSection(final MainActivity act, final Core core, LinearLayout menu, final Runnable refresh) {
        final Data data = core.data;
        menu.addView(Ui.sectionTitle(act, "Call server"));
        LinearLayout card = Ui.card(act);

        // Status
        boolean connected = core.signaling.isConnected();
        String sub = core.signaling.statusLine();
        if (!connected && data.serverConfigured() && core.signaling.detail().length() > 0
                && core.signaling.state() == Signaling.DISCONNECTED) {
            sub += "\n" + core.signaling.detail();
        }
        Ui.addRow(act, card, Ui.row(act, "Status", sub, connected, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                core.signaling.restart();
                act.toast("Reconnecting…");
            }
        }));

        // Address
        Ui.addRow(act, card, Ui.row(act, "Server address",
                data.serverConfigured() ? data.serverHost() + "  ·  port " + data.serverPort()
                        : "Tap to enter the server IP and port", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        addressDialog(act, core, refresh);
                    }
                }));

        // Automatic discovery
        Ui.addRow(act, card, Ui.row(act, "Find server automatically",
                "Searches this Wi-Fi network", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        discover(act, core, refresh);
                    }
                }));

        // Test
        Ui.addRow(act, card, Ui.row(act, "Test connection",
                "Checks the saved address right now", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testSaved(act, data);
                    }
                }));

        // People online
        Ui.addRow(act, card, Ui.row(act, "People on this server",
                "See who is online and add them", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPeers(act, core);
                    }
                }));

        // Background mode
        Ui.addRow(act, card, Ui.toggleRow(act, "Stay reachable in background",
                "Keeps the connection open so calls can ring when the app is closed",
                data.flag(Data.KEY_BG_SERVICE, true), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        data.setFlag(Data.KEY_BG_SERVICE, on);
                        if (on) CallService.start(act);
                        else CallService.stop(act);
                    }
                }));

        // Node ID
        Ui.addRow(act, card, Ui.row(act, "Your node ID", data.nodeId() + "  ·  tap to copy", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Node ID", data.nodeId()));
                            act.toast("Copied");
                        }
                    }
                }));
        menu.addView(card);
        menu.addView(Ui.note(act, "The server only helps phones find each other. Voice and video go "
                + "directly between the phones. Chats with local bots never use the network."));
    }

    // ── Manual address ────────────────────────────────────────────────────────
    private static void addressDialog(final MainActivity act, final Core core, final Runnable refresh) {
        final Data data = core.data;

        final EditText host = field(act, "IP address or host name, e.g. 192.168.8.101");
        host.setText(data.serverHost());
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText port = field(act, "Port");
        port.setText(String.valueOf(data.serverPort()));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);

        final TextView preview = new TextView(act);
        preview.setTextColor(Ui.ACCENT);
        preview.setTextSize(13);
        preview.setPadding(Ui.dp(act, 4), Ui.dp(act, 10), 0, 0);
        final TextView result = new TextView(act);
        result.setTextSize(13);
        result.setPadding(Ui.dp(act, 4), Ui.dp(act, 6), 0, 0);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                String[] hp = parse(host.getText().toString(), port.getText().toString());
                preview.setText(hp == null ? "" : CallServer.wsUrl(hp[0], Integer.parseInt(hp[1])));
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        host.addTextChangedListener(watcher);
        port.addTextChangedListener(watcher);
        watcher.onTextChanged("", 0, 0, 0);

        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        wrap.addView(host, match());
        wrap.addView(port, match());
        wrap.addView(preview);
        wrap.addView(result);

        final AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Call server")
                .setMessage("The PC running server.js. Use the address it prints when it starts.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Test", null)          // set below so it does not close the dialog
                .setPositiveButton("Save", null)
                .create();
        d.show();

        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] hp = parse(host.getText().toString(), port.getText().toString());
                if (hp == null) {
                    result.setTextColor(0xFFEF9A9A);
                    result.setText("Enter a valid address and port");
                    return;
                }
                result.setTextColor(Ui.DIM);
                result.setText("Testing…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final CallServer.Probe p = CallServer.probe(hp[0], Integer.parseInt(hp[1]), 3000, 3000);
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                result.setTextColor(p.ok ? 0xFF81C784 : 0xFFEF9A9A);
                                result.setText(describe(p));
                            }
                        });
                    }
                }).start();
            }
        });
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] hp = parse(host.getText().toString(), port.getText().toString());
                if (hp == null) {
                    result.setTextColor(0xFFEF9A9A);
                    result.setText("Enter a valid address and port");
                    return;
                }
                data.saveServer(hp[0], Integer.parseInt(hp[1]));
                core.signaling.restart();
                d.dismiss();
                act.toast("Saved " + CallServer.wsUrl(hp[0], Integer.parseInt(hp[1])));
                refresh.run();
            }
        });
    }

    /** Accepts "192.168.8.101", "192.168.8.101:3000" or "ws://192.168.8.101:3000/" in the host box. */
    private static String[] parse(String hostText, String portText) {
        String h = hostText.trim();
        int p = -1;
        if (h.startsWith("ws://")) h = h.substring(5);
        else if (h.startsWith("http://")) h = h.substring(7);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {            // host:port (not an IPv6 address)
            try {
                p = Integer.parseInt(h.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
            h = h.substring(0, colon);
        }
        if (p < 0) {
            try {
                p = Integer.parseInt(portText.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (h.length() == 0 || p < 1 || p > 65535) return null;
        return new String[]{h, String.valueOf(p)};
    }

    // ── Test the saved address ────────────────────────────────────────────────
    private static void testSaved(final MainActivity act, final Data data) {
        if (!data.serverConfigured()) {
            act.toast("Enter the server address first");
            return;
        }
        final AlertDialog wait = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Testing…")
                .setMessage(CallServer.wsUrl(data.serverHost(), data.serverPort()))
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final CallServer.Probe p = CallServer.probe(data.serverHost(), data.serverPort(), 3000, 3000);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        wait.dismiss();
                        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(p.ok ? "Server reachable" : "Cannot reach the server")
                                .setMessage(describe(p))
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private static String describe(CallServer.Probe p) {
        if (p.ok) return "Connected to \"" + p.name + "\" in " + p.millis + " ms.";
        return p.error;
    }

    // ── Automatic discovery ───────────────────────────────────────────────────
    private static void discover(final MainActivity act, final Core core, final Runnable refresh) {
        final boolean[] cancelled = new boolean[1];
        final AlertDialog wait = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Searching…")
                .setMessage("Looking for the call server on this Wi-Fi network.")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cancelled[0] = true;
                    }
                })
                .setCancelable(false)
                .show();

        CallServer.discover(core.data.serverPort(), new CallServer.DiscoverCallback() {
            @Override
            public void onDone(final CallServer.Found f) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (cancelled[0]) return;
                        wait.dismiss();
                        if (f != null) {
                            core.data.saveServer(f.host, f.port);
                            core.signaling.restart();
                            refresh.run();
                            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("Server found")
                                    .setMessage("\"" + f.name + "\" at " + CallServer.wsUrl(f.host, f.port)
                                            + "\n(found by " + (f.how.equals("scan") ? "scanning the network" : "broadcast")
                                            + "). Saved.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        } else {
                            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("No server found")
                                    .setMessage("Check that:\n"
                                            + "• server.js is running on the PC\n"
                                            + "• this phone is on the same Wi-Fi as the PC\n"
                                            + "• the router does not isolate clients (\"AP isolation\" / \"client isolation\")\n"
                                            + "• the PC firewall allows TCP " + core.data.serverPort() + " and UDP 47800\n\n"
                                            + "You can always enter the IP and port by hand.")
                                    .setNegativeButton("Close", null)
                                    .setPositiveButton("Enter manually", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            addressDialog(act, core, refresh);
                                        }
                                    })
                                    .show();
                        }
                    }
                });
            }
        });
    }

    // ── People online ─────────────────────────────────────────────────────────
    private static void showPeers(final MainActivity act, final Core core) {
        core.signaling.requestPeers(new Signaling.PeersCallback() {
            @Override
            public void onPeers(final List<String[]> peers, String error) {
                if (peers == null) {
                    act.toast(error);
                    return;
                }
                if (peers.isEmpty()) {
                    new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("People on this server")
                            .setMessage("No one else is connected yet. Open the app on the other phone and set the same server.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                String[] labels = new String[peers.size()];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = peers.get(i)[1] + "\n" + peers.get(i)[0];
                }
                new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Online now. Tap to add")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String[] p = peers.get(which);
                                core.data.ensureContact(p[0], p[1]);
                                act.refreshHome();
                                act.toast(p[1] + " added to Humans");
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
            }
        });
    }

    // ── View helpers ──────────────────────────────────────────────────────────
    private static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(Ui.FAINT);
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        return e;
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
