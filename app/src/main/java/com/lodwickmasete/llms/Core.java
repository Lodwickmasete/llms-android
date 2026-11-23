package com.lodwickmasete.llms;

import android.content.Context;

/**
 * App-wide singletons, shared by the Activity and the background service:
 * the saved data, the signalling connection and the call manager.
 */
final class Core {

    private static Core instance;

    final Context app;
    final Data data;
    final Signaling signaling;
    final CallManager calls;

    /** True while MainActivity is on screen (decides: in-app incoming screen vs. a notification). */
    volatile boolean foreground = false;

    static synchronized Core get(Context c) {
        if (instance == null) instance = new Core(c.getApplicationContext());
        return instance;
    }

    private Core(Context app) {
        this.app = app;
        data = new Data(app);
        signaling = new Signaling(data);
        calls = new CallManager(app, data, signaling);

        // Notifications follow the call state, whatever screen (or no screen) is showing
        calls.addListener(new CallManager.Listener() {
            @Override
            public void onCallChanged() {
                if (calls.state() == CallManager.RINGING_IN && !foreground) {
                    CallService.showIncoming(Core.this.app, calls);
                } else if (calls.state() != CallManager.RINGING_IN) {
                    CallService.cancelIncoming(Core.this.app);
                }
                CallService.refresh();
            }
        });
    }
}
