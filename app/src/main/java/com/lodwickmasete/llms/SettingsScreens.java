package com.lodwickmasete.llms;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The Menu and Profile screens. Both have a fixed top bar (back + title) and a scrolling body
 * made of titled sections (Local AI, Calls, Network, Storage, About / Public profile, Privacy, Local AI).
 */
class SettingsScreens {

    static final int REQ_PICK_IMAGE = 101;

    private final MainActivity act;
    private final Data data;
    private final BotBrain brain;

    // ── Menu ──────────────────────────────────────────────────────────────────
    private final View menuRoot;
    private final LinearLayout menuContent;

    // ── Profile ───────────────────────────────────────────────────────────────
    private final View profileRoot;
    private final ImageView accImagePreview;
    private final TextView usernamePreview, userroleBrief, uservillagePreview, userinfoPreview;
    private final TextView saveButton, changePhotoButton, removePhotoButton;
    private final TextView styleShort, styleBalanced, styleDetailed;
    private final EditText nameInput, roleInput, villageInput, infoInput;
    private final LinearLayout nodeContainer, privacyContainer, aiContainer;
    private Bitmap pendingAvatar = null;
    private boolean avatarRemoved = false;
    private int draftStyle = 1;

    SettingsScreens(final MainActivity act, final Data data, final BotBrain brain) {
        this.act = act;
        this.data = data;
        this.brain = brain;

        menuRoot = act.findViewById(R.id.menu_screen);
        menuContent = (LinearLayout) act.findViewById(R.id.menu_content);
        act.findViewById(R.id.menu_back_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.closeMenu(true);
            }
        });

        profileRoot = act.findViewById(R.id.profile_screen);
        accImagePreview = (ImageView) act.findViewById(R.id.accimage_preview);
        usernamePreview = (TextView) act.findViewById(R.id.username_preview);
        userroleBrief = (TextView) act.findViewById(R.id.userrole_preview);
        uservillagePreview = (TextView) act.findViewById(R.id.uservillage_preview);
        userinfoPreview = (TextView) act.findViewById(R.id.userinfo_preview);
        saveButton = (TextView) act.findViewById(R.id.save_button_account);
        changePhotoButton = (TextView) act.findViewById(R.id.change_photo_button);
        removePhotoButton = (TextView) act.findViewById(R.id.remove_photo_button);
        styleShort = (TextView) act.findViewById(R.id.style_short);
        styleBalanced = (TextView) act.findViewById(R.id.style_balanced);
        styleDetailed = (TextView) act.findViewById(R.id.style_detailed);
        nameInput = (EditText) act.findViewById(R.id.name_input);
        roleInput = (EditText) act.findViewById(R.id.role_input);
        villageInput = (EditText) act.findViewById(R.id.village_input);
        infoInput = (EditText) act.findViewById(R.id.info_input);
        nodeContainer = (LinearLayout) act.findViewById(R.id.profile_node_container);
        privacyContainer = (LinearLayout) act.findViewById(R.id.profile_privacy_container);
        aiContainer = (LinearLayout) act.findViewById(R.id.profile_ai_container);

        EditText[] edits = new EditText[]{nameInput, roleInput, villageInput, infoInput};
        int[] boxes = new int[]{R.id.input_username_background, R.id.input_role_background,
                R.id.input_village_background, R.id.input_userinfo_background};
        for (int i = 0; i < edits.length; i++) {
            edits[i].setBackground(null);
            bindFocusBorder(edits[i], act.findViewById(boxes[i]));
        }

        act.findViewById(R.id.account_back_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.closeProfile();
            }
        });
        View.OnClickListener pickPhoto = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.setType("image/*");
                act.startActivityForResult(pick, REQ_PICK_IMAGE);
            }
        };
        accImagePreview.setOnClickListener(pickPhoto);
        changePhotoButton.setOnClickListener(pickPhoto);
        removePhotoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingAvatar = null;
                avatarRemoved = true;
                accImagePreview.setImageResource(R.drawable.ic_account);
            }
        });
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveProfile();
            }
        });
        styleShort.setOnClickListener(styleClick(0));
        styleBalanced.setOnClickListener(styleClick(1));
        styleDetailed.setOnClickListener(styleClick(2));

        buildProfileSections();
        loadProfile();
    }

    private void bindFocusBorder(EditText edit, final View container) {
        container.setBackground(Ui.box(act, 21, Color.BLACK, Ui.BORDER, 1));
        edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                container.setBackground(hasFocus
                        ? Ui.box(act, 21, Color.BLACK, 0xFFFFFFFF, 2)
                        : Ui.box(act, 21, Color.BLACK, Ui.BORDER, 1));
            }
        });
    }

    private View.OnClickListener styleClick(final int style) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                draftStyle = style;
                updateStyleChips();
            }
        };
    }

    /** Adds a toggle row that saves its own preference as soon as it is flipped. */
    private void addToggle(LinearLayout card, String title, String sub, final String key, boolean def,
                           final Runnable after) {
        Ui.addRow(act, card, Ui.toggleRow(act, title, sub, data.flag(key, def),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        data.setFlag(key, checked);
                        if (after != null) after.run();
                    }
                }));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MENU
    // ══════════════════════════════════════════════════════════════════════════
    boolean isMenuOpen() {
        return menuRoot.getVisibility() == View.VISIBLE;
    }

    void openMenu() {
        if (isMenuOpen()) return;
        act.hideKeyboard();
        populateMenu();
        int w = act.getResources().getDisplayMetrics().widthPixels;
        menuRoot.animate().cancel();
        menuRoot.setTranslationX(-w);
        menuRoot.setVisibility(View.VISIBLE);
        menuRoot.animate().translationX(0).setDuration(220).start();
        menuRoot.requestFocus();
    }

    void closeMenu(boolean animate) {
        if (!isMenuOpen()) return;
        menuRoot.animate().cancel();
        if (animate) {
            int w = act.getResources().getDisplayMetrics().widthPixels;
            menuRoot.animate().translationX(-w).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    menuRoot.setVisibility(View.GONE);
                }
            }).start();
        } else {
            menuRoot.setTranslationX(0);
            menuRoot.setVisibility(View.GONE);
        }
    }

    void populateMenu() {
        menuContent.removeAllViews();

        // Account
        menuContent.addView(accountCard());

        // Local AI
        menuContent.addView(Ui.sectionTitle(act, "Local AI"));
        LinearLayout ai = Ui.card(act);
        List<String> models = brain.listModels();
        String selected = brain.defaultModel();
        for (int i = 0; i < models.size(); i++) {
            final String name = models.get(i);
            boolean on = name.equals(selected);
            Ui.addRow(act, ai, Ui.row(act, Ui.botName(name),
                    on ? "Default for new chats" : "Tap to use for new chats", on,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            brain.setDefaultModel(name);
                            populateMenu();
                            act.toast("Default model set");
                        }
                    }));
        }
        if (!brain.hasStorageAccess()) {
            Ui.addRow(act, ai, Ui.row(act, "Allow storage access",
                    "Needed to read models from " + brain.modelsDirPath(), false, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            brain.ensureStorageAccess();
                        }
                    }));
        } else if (models.isEmpty()) {
            Ui.addRow(act, ai, Ui.hintRow(act, "No .gguf models found in " + brain.modelsDirPath()));
        }
        Ui.addRow(act, ai, Ui.row(act, "Models folder", brain.modelsDirPath(), false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                brain.showFolderDialog(new Runnable() {
                    @Override
                    public void run() {
                        populateMenu();
                        act.refreshHome();
                    }
                });
            }
        }));
        menuContent.addView(ai);
        menuContent.addView(Ui.note(act, "Models run on this phone. Chats with local bots are never sent anywhere."));

        // Calls
        menuContent.addView(Ui.sectionTitle(act, "Calls"));
        LinearLayout calls = Ui.card(act);
        addToggle(calls, "Camera on in video calls", "Start video calls with your camera on", Data.KEY_CALL_CAM_ON, true, null);
        addToggle(calls, "Speaker by default", "Use the loudspeaker when a call starts", Data.KEY_CALL_SPEAKER, false, null);
        menuContent.addView(calls);
        menuContent.addView(Ui.note(act, "Start a call from the chat with a person. Your profile controls who may call you."));

        // Call server (status, address, discovery, test)
        ServerSettings.addSection(act, Core.get(act), menuContent, new Runnable() {
            @Override
            public void run() {
                populateMenu();
                act.refreshHome();
            }
        });

        // Storage
        menuContent.addView(Ui.sectionTitle(act, "Storage"));
        LinearLayout storage = Ui.card(act);
        Ui.addRow(act, storage, Ui.row(act, "Clear AI chats", "Deletes your conversations with local bots", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        act.confirm("Clear AI chats?", "All conversations with local bots will be deleted.", "Clear",
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        act.clearChats(true);
                                        act.toast("AI chats cleared");
                                    }
                                });
                    }
                }));
        Ui.addRow(act, storage, Ui.row(act, "Clear all chats", "Deletes AI chats and chats with people", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        act.confirm("Clear all chats?", "Every conversation on this phone will be deleted.", "Clear",
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        act.clearChats(false);
                                        act.toast("Chats cleared");
                                    }
                                });
                    }
                }));
        menuContent.addView(storage);

        // About
        menuContent.addView(Ui.sectionTitle(act, "About"));
        LinearLayout about = Ui.card(act);
        Ui.addRow(act, about, Ui.row(act, "About this app", "Humans & LLMs, local first", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Humans & LLMs")
                        .setMessage("Chat with AI models that run on your phone (llama.cpp, Qwen / ChatML), "
                                + "and call people over your local network (WebRTC).\n\n"
                                + "Only calls with people use the call server.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        }));
        menuContent.addView(about);
    }

    private View accountCard() {
        LinearLayout card = Ui.card(act);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12));

        ImageView av = new ImageView(act);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap me = data.avatar();
        if (me != null) av.setImageBitmap(me);
        else av.setImageResource(R.drawable.ic_account);
        row.addView(av, new LinearLayout.LayoutParams(Ui.dp(act, 52), Ui.dp(act, 52)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(act, 12);
        TextView name = new TextView(act);
        String n = data.str(Data.KEY_NAME);
        name.setText(n.length() == 0 ? "Your name" : n);
        name.setTextColor(Color.WHITE);
        name.setTextSize(18);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(name);
        TextView sub = new TextView(act);
        sub.setText("View and edit your profile");
        sub.setTextColor(Ui.DIM);
        sub.setTextSize(12);
        col.addView(sub);
        row.addView(col, clp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.closeMenu(false);
                act.openProfile();
            }
        });
        card.addView(row);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        lp.topMargin = Ui.dp(act, 8);
        return card;
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            act.toast("Copied");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PROFILE
    // ══════════════════════════════════════════════════════════════════════════
    /** Static parts of the profile that are built from code (toggle cards). */
    private void buildProfileSections() {
        // Node ID (read-only, shown to people you add)
        LinearLayout node = Ui.card(act);
        Ui.addRow(act, node, Ui.row(act, "Your node ID", data.nodeId() + "  ·  tap to copy", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copy("Node ID", data.nodeId());
                    }
                }));
        nodeContainer.addView(node);

        // Privacy: what people can see / who can call
        privacyContainer.addView(Ui.note(act, "Your profile is normally shown to the people you chat with. Choose what they can see."));
        LinearLayout priv = Ui.card(act);
        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) priv.getLayoutParams();
        plp.topMargin = Ui.dp(act, 10);
        addToggle(priv, "Show my photo", "People see your profile picture", Data.KEY_SHOW_PHOTO, true, null);
        addToggle(priv, "Show occupation and about", "Visible on your profile to others", Data.KEY_SHOW_ABOUT, true, null);
        addToggle(priv, "Show when I'm online", "Let people see if you are on the node", Data.KEY_SHOW_ONLINE, true, null);
        addToggle(priv, "Allow voice calls", "People you added can call you", Data.KEY_ALLOW_VOICE, true, null);
        addToggle(priv, "Allow video calls", "People you added can video call you", Data.KEY_ALLOW_VIDEO, true, null);
        privacyContainer.addView(priv);

        // Local AI: sharing is opt-in
        LinearLayout ai = Ui.card(act);
        addToggle(ai, "Share my profile with the AI",
                "Adds your name, occupation, village and about text to the local model's prompt. "
                        + "It never leaves this phone.", Data.KEY_INCLUDE, false, null);
        aiContainer.addView(ai);
    }

    /** Loads the saved profile into every place it is shown and resets unsaved edits. */
    void loadProfile() {
        String name = data.str(Data.KEY_NAME);
        String info = data.str(Data.KEY_INFO);
        String role = data.str(Data.KEY_ROLE);
        String village = data.str(Data.KEY_VILLAGE);
        nameInput.setText(name);
        infoInput.setText(info);
        roleInput.setText(role);
        villageInput.setText(village);
        draftStyle = data.prefs().getInt(Data.KEY_STYLE, 1);
        updateStyleChips();

        usernamePreview.setText(name.isEmpty() ? "Your name" : name);
        userinfoPreview.setText(info.isEmpty() ? "About you" : info);
        userroleBrief.setText(role);
        userroleBrief.setVisibility(role.isEmpty() ? View.GONE : View.VISIBLE);
        uservillagePreview.setText(village);
        uservillagePreview.setVisibility(village.isEmpty() ? View.GONE : View.VISIBLE);

        data.invalidateAvatar();
        pendingAvatar = null;
        avatarRemoved = false;
        Bitmap avatar = data.avatar();
        if (avatar != null) {
            accImagePreview.setImageBitmap(avatar);
        } else {
            accImagePreview.setImageResource(R.drawable.ic_account);
        }
        act.refreshHome();
    }

    private void updateStyleChips() {
        TextView[] chips = new TextView[]{styleShort, styleBalanced, styleDetailed};
        for (int i = 0; i < chips.length; i++) {
            boolean on = (i == draftStyle);
            chips[i].setBackground(Ui.box(act, 18, on ? 0xFF10202B : Ui.CARD, on ? Ui.ACCENT : Ui.BORDER, 1));
            chips[i].setTextColor(on ? Ui.ACCENT : 0xFFFFFFFF);
        }
    }

    void showProfile() {
        loadProfile();
    }

    private void saveProfile() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError("Enter your name");
            return;
        }
        SharedPreferences.Editor editor = data.prefs().edit();
        editor.putString(Data.KEY_NAME, name);
        editor.putString(Data.KEY_INFO, infoInput.getText().toString().trim());
        editor.putString(Data.KEY_ROLE, roleInput.getText().toString().trim());
        editor.putString(Data.KEY_VILLAGE, villageInput.getText().toString().trim());
        editor.putInt(Data.KEY_STYLE, draftStyle);

        if (avatarRemoved) {
            data.avatarFile().delete();
            editor.remove(Data.KEY_AVATAR);
        } else if (pendingAvatar != null) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(data.avatarFile());
                pendingAvatar.compress(Bitmap.CompressFormat.PNG, 100, out);
                editor.putString(Data.KEY_AVATAR, data.avatarFile().getAbsolutePath());
            } catch (IOException e) {
                act.toast("Could not save picture");
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        editor.apply();
        Core.get(act).signaling.updateProfile();          // the server shows your new name to others
        act.hideKeyboard();
        loadProfile();
        act.toast("Profile saved");
    }

    void onImagePicked(Uri uri) {
        Bitmap bmp = decodeUri(uri, 512);
        if (bmp != null) {
            pendingAvatar = circleCrop(bmp, 512);
            avatarRemoved = false;
            accImagePreview.setImageBitmap(pendingAvatar);
        } else {
            act.toast("Could not load image");
        }
    }

    private Bitmap decodeUri(Uri uri, int target) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = act.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            if (in != null) in.close();

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = act.getContentResolver().openInputStream(uri);
            Bitmap result = BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap circleCrop(Bitmap src, int size) {
        int min = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - min) / 2;
        int y = (src.getHeight() - min) / 2;
        Bitmap square = Bitmap.createBitmap(src, x, y, min, min);
        Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);

        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(scaled, 0, 0, paint);
        return out;
    }
}
