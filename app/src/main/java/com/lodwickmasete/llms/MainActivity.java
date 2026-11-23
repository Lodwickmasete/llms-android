package com.lodwickmasete.llms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Humans & LLMs - local first.
 *
 * Home (chat list: Local bots / Humans) -> Chat -> Call (people only, WebRTC).
 * Menu slides over everything; Profile is opened from Menu or the avatar on Home.
 * This class only wires the screens together; each screen lives in its own file:
 *   HomeScreen, ChatScreen, CallScreen, SettingsScreens + ServerSettings (menu, profile),
 *   BotBrain (local model), Data (storage), Ui (view helpers),
 *   Core / Signaling / CallManager / CallService / CallServer / WsClient (calling).
 */
public class MainActivity extends Activity {

    static final int REQ_CALL = 401;
    static final int REQ_NOTIFICATIONS = 402;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Core core;
    private Data data;
    private BotBrain brain;
    private HomeScreen home;
    private ChatScreen chat;
    private CallScreen call;
    private SettingsScreens settings;

    private View homeRoot, chatRoot, callRoot, profileRoot;
    private GestureDetector swipeDetector;
    private Runnable afterCallPermissions;

    private final CallManager.Listener callListener = new CallManager.Listener() {
        @Override
        public void onCallChanged() {
            showCallOrBack();
        }
    };

    private final Signaling.Listener signalListener = new Signaling.Listener() {
        @Override
        public void onSignalState(int state, String detail) {
            if (home != null) home.refresh();
            if (settings != null && settings.isMenuOpen()) settings.populateMenu();
        }

        @Override
        public void onSignal(JSONObject msg) {
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.BLACK);

        core = Core.get(this);
        data = core.data;
        brain = new BotBrain(this, data, handler);
        settings = new SettingsScreens(this, data, brain);
        home = new HomeScreen(this, data, brain);
        chat = new ChatScreen(this, data, brain);
        call = new CallScreen(this, data, core.calls);

        brain.onStateChanged = new Runnable() {
            @Override
            public void run() {
                chat.updateHeader();
            }
        };

        homeRoot = home.root();
        chatRoot = chat.root();
        callRoot = call.root();
        profileRoot = findViewById(R.id.profile_screen);
        swipeDetector = new GestureDetector(this, new SwipeListener());

        core.calls.addListener(callListener);
        core.signaling.addListener(signalListener);

        home.refresh();
        showScreen(homeRoot);
        brain.ensureStorageAccess();
        showCallOrBack();                       // a call may already be in progress (opened from a notification)
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        core.signaling.start();
        if (data.flag(Data.KEY_BG_SERVICE, true)) CallService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        core.foreground = true;
        CallService.cancelIncoming(this);
        home.refresh();                          // picks up newly copied models / granted permission
        showCallOrBack();
        if (brain.hasStorageAccess()) askNotificationsOnce();
    }

    @Override
    protected void onPause() {
        super.onPause();
        core.foreground = false;
        data.saveChats();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!data.flag(Data.KEY_BG_SERVICE, true) && !core.calls.inCall()) core.signaling.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        core.calls.removeListener(callListener);
        core.signaling.removeListener(signalListener);
        call.destroy();
        if (isFinishing() && core.calls.inCall()) core.calls.hangup();   // no screen left to control it
        brain.cancel();
        brain.unload();
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    private void showScreen(View screen) {
        hideKeyboard();
        homeRoot.setVisibility(screen == homeRoot ? View.VISIBLE : View.GONE);
        chatRoot.setVisibility(screen == chatRoot ? View.VISIBLE : View.GONE);
        profileRoot.setVisibility(screen == profileRoot ? View.VISIBLE : View.GONE);
        callRoot.setVisibility(screen == callRoot ? View.VISIBLE : View.GONE);
        screen.requestFocus(); // keeps inputs un-focused (no white border) until tapped
    }

    void openChat(Data.Chat c) {
        chat.open(c);
        showScreen(chatRoot);
    }

    void closeChat() {
        chat.close();
        home.refresh();
        showScreen(homeRoot);
    }

    void openProfile() {
        settings.showProfile();
        showScreen(profileRoot);
    }

    void closeProfile() {
        settings.loadProfile();                 // discard unsaved edits
        showScreen(homeRoot);
    }

    void openMenu() {
        settings.openMenu();
    }

    void closeMenu(boolean animate) {
        settings.closeMenu(animate);
    }

    void refreshHome() {
        if (home != null) home.refresh();
    }

    void clearChats(boolean botsOnly) {
        if (chat.hasChat()) closeChat();
        data.clearChats(botsOnly);
        home.refresh();
    }

    // ── Calls ─────────────────────────────────────────────────────────────────
    /** Chat -> call button. Checks permissions, then CallManager starts the call. */
    void startCall(final Data.Contact peer, final boolean video) {
        withCallPermissions(video, new Runnable() {
            @Override
            public void run() {
                String error = core.calls.startCall(peer, video);
                if (error != null) toast(error);
            }
        });
    }

    /** Accept button / notification action. */
    void acceptIncomingCall() {
        withCallPermissions(core.calls.isVideo(), new Runnable() {
            @Override
            public void run() {
                core.calls.accept();
            }
        });
    }

    /** Microphone is required; camera only for video (without it the call is audio-only). */
    private void withCallPermissions(boolean video, Runnable onReady) {
        if (Build.VERSION.SDK_INT < 23) {
            onReady.run();
            return;
        }
        ArrayList<String> need = new ArrayList<String>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (video && !hasPermission(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission("android.permission.POST_NOTIFICATIONS")) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (need.isEmpty()) {
            onReady.run();
        } else {
            afterCallPermissions = onReady;
            requestPermissions(need.toArray(new String[need.size()]), REQ_CALL);
        }
    }

    /** Shows the call screen while a call exists; returns to where the user was when it ends. */
    private void showCallOrBack() {
        if (callRoot == null) return;
        boolean inCall = core.calls.inCall();
        boolean showing = callRoot.getVisibility() == View.VISIBLE;
        lockScreenFlags(core.calls.state() == CallManager.RINGING_IN);

        if (inCall) {
            if (!showing) showScreen(callRoot);
        } else if (showing) {
            String why = core.calls.endMessage();
            if (why.length() > 0) toast(why);
            if (chat.hasChat()) {
                chat.refresh();                 // shows the new "Voice call · 2:13" line
                showScreen(chatRoot);
            } else {
                showScreen(homeRoot);
            }
            home.refresh();
        } else {
            if (chat.hasChat()) chat.refresh();
            if (home != null) home.refresh();
        }
    }

    /** Let an incoming call appear over the lock screen and wake the display. */
    private void lockScreenFlags(boolean on) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        } else if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    /** Intent from the incoming-call notification (Accept / tap). */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra(CallService.EXTRA_CALL_ACTION);
        if (action == null) return;
        intent.removeExtra(CallService.EXTRA_CALL_ACTION);
        if (CallService.CALL_ACCEPT.equals(action) && core.calls.state() == CallManager.RINGING_IN) {
            acceptIncomingCall();
        }
    }

    private void askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || data.flag("asked_notifications", false)) return;
        data.setFlag("asked_notifications", true);
        if (!hasPermission("android.permission.POST_NOTIFICATIONS")) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onBackPressed() {
        if (settings.isMenuOpen()) {
            closeMenu(true);
        } else if (core.calls.inCall()) {
            toast("Use End to hang up");         // a stray back press should not drop a call
        } else if (profileRoot.getVisibility() == View.VISIBLE) {
            closeProfile();
        } else if (chatRoot.getVisibility() == View.VISIBLE) {
            closeChat();
        } else {
            super.onBackPressed();
        }
    }

    // ── Swipe right on Home opens the menu, swipe left closes it ──────────────
    private class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (e1 == null || e2 == null) return false;
            float dx = e2.getX() - e1.getX();
            float dy = e2.getY() - e1.getY();
            if (Math.abs(dx) < Ui.dp(MainActivity.this, 90) || Math.abs(dx) < Math.abs(dy) * 2
                    || Math.abs(vx) < 600) {
                return false;
            }
            boolean menuOpen = settings.isMenuOpen();
            if (dx > 0 && !menuOpen && homeRoot.getVisibility() == View.VISIBLE) {
                openMenu();
            } else if (dx < 0 && menuOpen) {
                closeMenu(true);
            }
            return false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeDetector != null) swipeDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // ── Results ───────────────────────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SettingsScreens.REQ_PICK_IMAGE
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            settings.onImagePicked(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL) {
            Runnable next = afterCallPermissions;
            afterCallPermissions = null;
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                toast("Microphone permission is required for calls");
                return;
            }
            if (core.calls.isVideo() && !hasPermission(Manifest.permission.CAMERA)) {
                toast("No camera permission: video will not be sent");
            }
            if (next != null) next.run();
        } else if (requestCode == BotBrain.REQ_STORAGE) {
            home.refresh();
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) toast("Storage permission is required to read models");
            askNotificationsOnce();
        }
    }

    // ── Helpers (used by the screens) ─────────────────────────────────────────
    boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    void confirm(String title, String message, String yes, final Runnable onYes) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onYes.run();
                    }
                })
                .show();
    }
}
