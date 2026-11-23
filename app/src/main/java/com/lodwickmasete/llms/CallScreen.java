package com.lodwickmasete.llms;

import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;

import java.util.Locale;

/**
 * The live screen: shows whatever CallManager is doing (calling, incoming, connecting,
 * connected, reconnecting) and turns button taps into CallManager actions.
 * It holds no call logic of its own.
 */
class CallScreen implements CallManager.Listener {

    private final MainActivity act;
    private final CallManager calls;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View root;

    private final SurfaceViewRenderer remoteVideo, localVideo;
    private final View placeholder, topbar, selfCard, controls, incomingControls, cameraGroup, flipGroup;
    private final ImageView avatar, btnMute, btnSpeaker, btnCamera, btnFlip, btnEnd, btnDecline, btnAccept;
    private final TextView name, status, topName, topStatus, selfHint;

    private String avatarFor = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (calls.state() == CallManager.CONNECTED) {
                setStatus(timer());
                handler.postDelayed(this, 1000);
            }
        }
    };

    CallScreen(final MainActivity act, Data data, final CallManager calls) {
        this.act = act;
        this.calls = calls;
        root = act.findViewById(R.id.call_screen);

        remoteVideo = (SurfaceViewRenderer) act.findViewById(R.id.call_remote_video);
        localVideo = (SurfaceViewRenderer) act.findViewById(R.id.call_local_video);
        placeholder = act.findViewById(R.id.call_placeholder);
        topbar = act.findViewById(R.id.call_topbar);
        selfCard = act.findViewById(R.id.call_self_card);
        controls = act.findViewById(R.id.call_controls);
        incomingControls = act.findViewById(R.id.call_incoming_controls);
        cameraGroup = act.findViewById(R.id.call_camera_group);
        flipGroup = act.findViewById(R.id.call_flip_group);
        avatar = (ImageView) act.findViewById(R.id.call_avatar);
        btnMute = (ImageView) act.findViewById(R.id.call_btn_mute);
        btnSpeaker = (ImageView) act.findViewById(R.id.call_btn_speaker);
        btnCamera = (ImageView) act.findViewById(R.id.call_btn_camera);
        btnFlip = (ImageView) act.findViewById(R.id.call_btn_flip);
        btnEnd = (ImageView) act.findViewById(R.id.call_btn_end);
        btnDecline = (ImageView) act.findViewById(R.id.call_btn_decline);
        btnAccept = (ImageView) act.findViewById(R.id.call_btn_accept);
        name = (TextView) act.findViewById(R.id.call_name);
        status = (TextView) act.findViewById(R.id.call_status);
        topName = (TextView) act.findViewById(R.id.call_top_name);
        topStatus = (TextView) act.findViewById(R.id.call_top_status);
        selfHint = (TextView) act.findViewById(R.id.call_self_hint);

        root.setKeepScreenOn(true);

        // Video views share the call manager's EGL context
        remoteVideo.init(calls.eglContext(), null);
        remoteVideo.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        remoteVideo.setEnableHardwareScaler(true);
        localVideo.init(calls.eglContext(), null);
        localVideo.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        localVideo.setEnableHardwareScaler(true);
        localVideo.setZOrderMediaOverlay(true);
        localVideo.setMirror(true);
        calls.setRenderers(localVideo, remoteVideo);
        calls.addListener(this);

        btnEnd.setBackground(Ui.circle(0xFFE53935));
        btnDecline.setBackground(Ui.circle(0xFFE53935));
        btnAccept.setBackground(Ui.circle(0xFF2E9E5B));
        btnFlip.setBackground(Ui.circle(0x44FFFFFF));

        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calls.setMuted(!calls.isMuted());
            }
        });
        btnSpeaker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calls.setSpeaker(!calls.isSpeakerOn());
            }
        });
        btnCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calls.setCameraEnabled(!calls.isCameraEnabled());
            }
        });
        View.OnClickListener flip = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calls.switchCamera();
            }
        };
        btnFlip.setOnClickListener(flip);
        selfCard.setOnClickListener(flip);
        btnEnd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calls.hangup();
            }
        });
        btnDecline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calls.reject();
            }
        });
        btnAccept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.acceptIncomingCall();           // asks for permissions, then calls.accept()
            }
        });
    }

    View root() {
        return root;
    }

    /** Activity is going away for good: give the video views back. */
    void destroy() {
        handler.removeCallbacks(tick);
        calls.removeListener(this);
        calls.setRenderers(null, null);
        remoteVideo.release();
        localVideo.release();
    }

    // ── Reflect the call state ────────────────────────────────────────────────
    @Override
    public void onCallChanged() {
        int st = calls.state();
        if (st == CallManager.IDLE) {
            handler.removeCallbacks(tick);
            remoteVideo.clearImage();
            localVideo.clearImage();
            avatarFor = "";
            return;
        }

        boolean video = calls.isVideo();
        boolean incoming = st == CallManager.RINGING_IN;
        boolean videoLive = video && (st == CallManager.CONNECTED || st == CallManager.RECONNECTING);

        if (!calls.peerId().equals(avatarFor)) {
            avatarFor = calls.peerId();
            Bitmap pa = Ui.avatar(calls.peerId(), calls.peerName(), false, Ui.dp(act, 150));
            avatar.setImageBitmap(pa);
        }
        name.setText(calls.peerName());
        topName.setText(calls.peerName());

        handler.removeCallbacks(tick);
        if (st == CallManager.CONNECTED) {
            setStatus(timer());
            handler.postDelayed(tick, 1000);
        } else {
            setStatus(statusFor(st, video));
        }

        remoteVideo.setVisibility(videoLive ? View.VISIBLE : View.INVISIBLE);
        placeholder.setVisibility(videoLive ? View.GONE : View.VISIBLE);
        topbar.setVisibility(videoLive ? View.VISIBLE : View.GONE);

        selfCard.setVisibility(video && !incoming && calls.hasLocalVideo() ? View.VISIBLE : View.GONE);
        selfHint.setVisibility(calls.isCameraEnabled() ? View.GONE : View.VISIBLE);
        localVideo.setMirror(calls.isFrontCamera());

        incomingControls.setVisibility(incoming ? View.VISIBLE : View.GONE);
        controls.setVisibility(incoming ? View.GONE : View.VISIBLE);
        cameraGroup.setVisibility(video ? View.VISIBLE : View.GONE);
        flipGroup.setVisibility(video && calls.hasLocalVideo() ? View.VISIBLE : View.GONE);

        btnMute.setImageResource(calls.isMuted() ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        toggleStyle(btnMute, calls.isMuted());
        toggleStyle(btnSpeaker, calls.isSpeakerOn());
        btnCamera.setImageResource(calls.isCameraEnabled() ? R.drawable.ic_videocam : R.drawable.ic_videocam_off);
        toggleStyle(btnCamera, !calls.isCameraEnabled());
    }

    private String statusFor(int st, boolean video) {
        switch (st) {
            case CallManager.CALLING:
                return calls.isRemoteRinging() ? "Ringing…" : "Calling…";
            case CallManager.RINGING_IN:
                return video ? "Incoming video call" : "Incoming voice call";
            case CallManager.CONNECTING:
                return "Connecting…";
            case CallManager.RECONNECTING:
                return "Reconnecting…";
            default:
                return "";
        }
    }

    private void setStatus(String s) {
        status.setText(s);
        topStatus.setText(s);
    }

    private String timer() {
        long s = calls.seconds();
        return String.format(Locale.ENGLISH, "%02d:%02d", s / 60, s % 60);
    }

    private void toggleStyle(ImageView b, boolean on) {
        b.setBackground(Ui.circle(on ? 0xFFFFFFFF : 0x44FFFFFF));
        b.setColorFilter(on ? 0xFF000000 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
    }
}
