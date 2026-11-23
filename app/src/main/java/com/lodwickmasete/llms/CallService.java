package com.lodwickmasete.llms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the app alive in the background so the call server connection stays up and incoming
 * calls can arrive. Also owns the incoming-call notification (Accept / Decline / full screen).
 */
public class CallService extends Service {

    static final String ACTION_DECLINE = "com.lodwickmasete.llms.DECLINE";
    static final String EXTRA_CALL_ACTION = "call_action";     // read by MainActivity
    static final String CALL_ACCEPT = "accept";
    static final String CALL_OPEN = "open";

    private static final String CH_BACKGROUND = "reachable";
    private static final String CH_INCOMING = "incoming_calls";
    private static final int ID_BACKGROUND = 41;
    private static final int ID_INCOMING = 42;

    // FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34) written as a number so older compile SDKs still build
    private static final int FGS_SPECIAL_USE = 0x40000000;

    private static CallService instance;

    static void start(Context c) {
        Intent i = new Intent(c, CallService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // not allowed right now (app in background): the app still works while it is open
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, CallService.class));
    }

    /** Re-posts the ongoing notification so its text matches the call state. */
    static void refresh() {
        if (instance != null) instance.postForeground();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        postForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Core core = Core.get(this);
        core.signaling.start();
        postForeground();
        if (intent != null && ACTION_DECLINE.equals(intent.getAction())) {
            core.calls.reject();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void postForeground() {
        try {
            Core core = Core.get(this);
            String text = core.calls.inCall() ? "In a call with " + core.calls.peerName()
                    : (core.signaling.isConnected() ? "Ready for calls" : "Waiting for the call server");
            Notification n = builder(this, CH_BACKGROUND)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Humans & LLMs")
                    .setContentText(text)
                    .setOngoing(true)
                    .setContentIntent(openApp(this, CALL_OPEN, 1))
                    .build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID_BACKGROUND, n, FGS_SPECIAL_USE);
            else startForeground(ID_BACKGROUND, n);
        } catch (Exception ignored) {
        }
    }

    // ── Incoming call notification ────────────────────────────────────────────
    static void showIncoming(Context c, CallManager calls) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            PendingIntent open = openApp(c, CALL_OPEN, 2);
            PendingIntent accept = openApp(c, CALL_ACCEPT, 3);
            Intent dec = new Intent(c, CallService.class).setAction(ACTION_DECLINE);
            PendingIntent decline = Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(c, 4, dec, flags())
                    : PendingIntent.getService(c, 4, dec, flags());

            String kind = calls.isVideo() ? "Incoming video call" : "Incoming voice call";
            Notification.Builder b = builder(c, CH_INCOMING)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle(calls.peerName())
                    .setContentText(kind)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .addAction(0, "Decline", decline)
                    .addAction(0, "Accept", accept);
            if (Build.VERSION.SDK_INT >= 21) b.setCategory(Notification.CATEGORY_CALL);
            nm.notify(ID_INCOMING, b.build());
        } catch (Exception ignored) {
        }
    }

    static void cancelIncoming(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_INCOMING);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private static PendingIntent openApp(Context c, String action, int requestCode) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra(EXTRA_CALL_ACTION, action)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, requestCode, i, flags());
    }

    private static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(channel) == null) {
                boolean incoming = CH_INCOMING.equals(channel);
                NotificationChannel ch = new NotificationChannel(channel,
                        incoming ? "Incoming calls" : "Ready for calls",
                        incoming ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW);
                ch.setSound(null, null);                     // CallManager rings by itself
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            return new Notification.Builder(c, channel);
        }
        return new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH);
    }
}
