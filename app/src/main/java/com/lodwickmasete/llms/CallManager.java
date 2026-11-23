package com.lodwickmasete.llms;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One-to-one WebRTC calls (voice + video). One per app (see Core).
 *
 * Call flow (signalling messages go through the server, media goes phone to phone):
 *   caller                                   callee
 *   invite  ------------------------------>  rings, shows the incoming screen
 *           <-----------------------------   ringing
 *           <-----------------------------   accept   (callee made its PeerConnection)
 *   offer   ------------------------------>
 *           <-----------------------------   answer
 *   ice     <----------------------------->  ice      (both ways, until connected)
 *   hangup / reject / busy                   at any time
 *
 * Nothing here knows about the network layout. It only needs the two phones to reach each
 * other: same router now, the village LAN later. When they cannot (a routed network),
 * put STUN/TURN urls into Data.KEY_ICE_SERVERS - no other code changes.
 */
class CallManager implements Signaling.Listener {

    static final int IDLE = 0;
    static final int CALLING = 1;        // outgoing, waiting for the other phone
    static final int RINGING_IN = 2;     // incoming, waiting for the user
    static final int CONNECTING = 3;     // accepted, setting up the media path
    static final int CONNECTED = 4;
    static final int RECONNECTING = 5;   // media path lost, trying to recover

    interface Listener {
        void onCallChanged();
    }

    private static final int RING_TIMEOUT_MS = 45000;
    private static final int CONNECT_TIMEOUT_MS = 25000;
    private static final int RECONNECT_TIMEOUT_MS = 15000;
    private static final int MAX_VIDEO_BPS = 700000;        // gentle on weak wireless links
    private static boolean webrtcInitialised = false;

    private final Context app;
    private final Data data;
    private final Signaling sig;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    // call info
    private int state = IDLE;
    private String callId = "";
    private String peerId = "";
    private String peerName = "";
    private boolean video = false;
    private boolean outgoing = false;
    private boolean remoteRinging = false;
    private boolean micMuted = false;
    private boolean camEnabled = true;
    private boolean speakerOn = false;
    private boolean frontCamera = true;
    private long connectedAt = 0;
    private String endNote = "";

    // WebRTC
    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private VideoTrack remoteTrack;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper surfaceHelper;
    private SurfaceViewRenderer localView, remoteView;
    private final ArrayList<IceCandidate> pendingIce = new ArrayList<IceCandidate>();
    private boolean remoteDescSet = false;

    // phone resources
    private Ringtone ringtone;
    private AudioManager audio;
    private int savedAudioMode;
    private boolean savedSpeaker;
    private boolean audioModeOn = false;
    private WifiManager.WifiLock wifiLock;

    CallManager(Context app, Data data, Signaling sig) {
        this.app = app.getApplicationContext();
        this.data = data;
        this.sig = sig;
        this.audio = (AudioManager) this.app.getSystemService(Context.AUDIO_SERVICE);
        sig.addListener(this);
    }

    // ── What the UI can read ──────────────────────────────────────────────────
    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    int state() {
        return state;
    }

    boolean inCall() {
        return state != IDLE;
    }

    String peerId() {
        return peerId;
    }

    String peerName() {
        return peerName;
    }

    boolean isVideo() {
        return video;
    }

    boolean isOutgoing() {
        return outgoing;
    }

    boolean isRemoteRinging() {
        return remoteRinging;
    }

    boolean isMuted() {
        return micMuted;
    }

    boolean isCameraEnabled() {
        return camEnabled;
    }

    boolean isSpeakerOn() {
        return speakerOn;
    }

    boolean isFrontCamera() {
        return frontCamera;
    }

    boolean hasLocalVideo() {
        return videoTrack != null;
    }

    long seconds() {
        return connectedAt > 0 ? (System.currentTimeMillis() - connectedAt) / 1000 : 0;
    }

    /** Why the last call ended, in words for a toast ("" when nothing to say). */
    String endMessage() {
        return endNote;
    }

    /** Shared EGL context for the video views (created once, kept for the life of the app). */
    synchronized EglBase.Context eglContext() {
        if (egl == null) egl = EglBase.create();
        return egl.getEglBaseContext();
    }

    /** The call screen hands over its two video views (already init()ed with eglContext()). */
    void setRenderers(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        if (localView != null && videoTrack != null) videoTrack.removeSink(localView);
        if (remoteView != null && remoteTrack != null) remoteTrack.removeSink(remoteView);
        localView = local;
        remoteView = remote;
        bindSinks();
    }

    private void bindSinks() {
        if (videoTrack != null && localView != null) {
            videoTrack.removeSink(localView);
            videoTrack.addSink(localView);
        }
        if (remoteTrack != null && remoteView != null) {
            remoteTrack.removeSink(remoteView);
            remoteTrack.addSink(remoteView);
        }
    }

    // ── Actions (call these from the UI) ──────────────────────────────────────
    /** Starts an outgoing call. Returns null, or a message to show when it cannot start. */
    String startCall(Data.Contact peer, boolean video) {
        if (state != IDLE) return "You are already in a call";
        if (!sig.isConnected()) {
            return "Not connected to the call server. Open Menu > Call server.";
        }
        resetCall(newCallId(), peer.id, peer.name, video, true);

        JSONObject o = Signaling.obj("invite");
        try {
            o.put("to", peerId);
            o.put("callId", callId);
            o.put("video", video);
        } catch (JSONException ignored) {
        }
        sig.send(o);

        startAudioMode();
        acquireWifiLock();
        main.postDelayed(ringTimeout, RING_TIMEOUT_MS);
        setState(CALLING);
        return null;
    }

    /** Answer the incoming call. Ask for microphone / camera permission before calling this. */
    void accept() {
        if (state != RINGING_IN) return;
        main.removeCallbacks(ringTimeout);
        stopRinging();
        startAudioMode();
        acquireWifiLock();
        setState(CONNECTING);
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        if (!beginPeerConnection()) return;
        sendCall("accept");                      // the caller now sends the offer
    }

    void reject() {
        if (state != RINGING_IN) return;
        sendCall("reject");
        finish("declined");
    }

    /** End or cancel the call, whatever state it is in. */
    void hangup() {
        switch (state) {
            case IDLE:
                return;
            case RINGING_IN:
                reject();
                return;
            case CALLING:
                sendCall("hangup");
                finish("cancelled");
                return;
            default:
                sendCall("hangup");
                finish("ended");
        }
    }

    void setMuted(boolean muted) {
        micMuted = muted;
        if (audioTrack != null) audioTrack.setEnabled(!muted);
        notifyChanged();
    }

    void setSpeaker(boolean on) {
        speakerOn = on;
        if (audioModeOn) audio.setSpeakerphoneOn(on);
        notifyChanged();
    }

    void setCameraEnabled(boolean on) {
        camEnabled = on;
        if (videoTrack != null) videoTrack.setEnabled(on);
        if (capturer != null) {
            try {
                if (on) capturer.startCapture(640, 480, 24);
                else capturer.stopCapture();
            } catch (Exception ignored) {
            }
        }
        notifyChanged();
    }

    void switchCamera() {
        if (capturer == null) return;
        capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean isFrontCamera) {
                frontCamera = isFrontCamera;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyChanged();
                    }
                });
            }

            @Override
            public void onCameraSwitchError(String errorDescription) {
            }
        });
    }

    // ── Signalling in ─────────────────────────────────────────────────────────
    @Override
    public void onSignalState(int s, String detail) {
    }

    @Override
    public void onSignal(JSONObject m) {
        String t = m.optString("t");
        String cid = m.optString("callId");

        if ("invite".equals(t)) {
            onInvite(m);
            return;
        }
        if ("unavailable".equals(t)) {                    // from the server: that person is offline
            if (state == CALLING && cid.equals(callId)) finish("not reachable");
            return;
        }
        // everything else belongs to the current call only
        if (state == IDLE || !cid.equals(callId) || !m.optString("from").equals(peerId)) return;

        if ("ringing".equals(t)) {
            if (state == CALLING) {
                remoteRinging = true;
                notifyChanged();
            }
        } else if ("accept".equals(t)) {
            if (state == CALLING && outgoing) {
                main.removeCallbacks(ringTimeout);
                setState(CONNECTING);
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
                if (beginPeerConnection()) createOffer();
            }
        } else if ("reject".equals(t)) {
            finish("declined");
        } else if ("busy".equals(t)) {
            finish("busy");
        } else if ("hangup".equals(t)) {
            finish(state == RINGING_IN ? "missed" : "ended");
        } else if ("offer".equals(t)) {
            if (pc != null && !outgoing) onRemoteOffer(m.optString("sdp"));
        } else if ("answer".equals(t)) {
            if (pc != null && outgoing) onRemoteAnswer(m.optString("sdp"));
        } else if ("ice".equals(t)) {
            onRemoteIce(new IceCandidate(m.optString("sdpMid"), m.optInt("sdpMLineIndex"), m.optString("cand")));
        }
    }

    private void onInvite(JSONObject m) {
        String from = m.optString("from");
        String cid = m.optString("callId");
        boolean vid = m.optBoolean("video");
        if (from.length() == 0 || cid.length() == 0) return;
        if (state != IDLE && from.equals(peerId) && cid.equals(callId)) return;    // duplicate

        Data.Contact c = data.ensureContact(from, m.optString("fromName"));
        String kind = vid ? "Video call" : "Voice call";

        if (state != IDLE) {                                   // already talking to someone
            sendTo(from, cid, "busy");
            data.logCall(from, kind + " · missed");
            notifyChanged();
            return;
        }
        boolean allowed = vid ? data.flag(Data.KEY_ALLOW_VIDEO, true) : data.flag(Data.KEY_ALLOW_VOICE, true);
        if (!allowed) {                                        // privacy toggle in the profile
            sendTo(from, cid, "reject");
            data.logCall(from, kind + " · blocked");
            notifyChanged();
            return;
        }
        resetCall(cid, from, c.name, vid, false);
        sendCall("ringing");
        startRinging();
        main.postDelayed(ringTimeout, RING_TIMEOUT_MS);
        setState(RINGING_IN);
    }

    // ── WebRTC ────────────────────────────────────────────────────────────────
    private synchronized void ensureFactory() {
        if (factory != null) return;
        if (!webrtcInitialised) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());
            webrtcInitialised = true;
        }
        EglBase.Context ctx = eglContext();
        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(JavaAudioDeviceModule.builder(app).createAudioDeviceModule())
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(ctx, true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(ctx))
                .createPeerConnectionFactory();
    }

    private List<PeerConnection.IceServer> iceServers() {
        List<PeerConnection.IceServer> list = new ArrayList<PeerConnection.IceServer>();
        String raw = data.str(Data.KEY_ICE_SERVERS).trim();       // empty = LAN only (host candidates)
        if (raw.length() > 0) {
            for (String url : raw.split("\\s+")) {
                list.add(PeerConnection.IceServer.builder(url).createIceServer());
            }
        }
        return list;
    }

    /** Creates the PeerConnection and adds the local audio (and video) tracks. */
    private boolean beginPeerConnection() {
        try {
            ensureFactory();
            PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers());
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
            pc = factory.createPeerConnection(cfg, new PcObserver());
            if (pc == null) throw new IllegalStateException("no peer connection");

            List<String> streamIds = Collections.singletonList("llms");
            audioSource = factory.createAudioSource(new MediaConstraints());
            audioTrack = factory.createAudioTrack("audio0", audioSource);
            audioTrack.setEnabled(!micMuted);
            pc.addTrack(audioTrack, streamIds);

            if (video && hasPermission(Manifest.permission.CAMERA)) startLocalVideo();
            if (videoTrack != null) pc.addTrack(videoTrack, streamIds);
            bindSinks();
            return true;
        } catch (Throwable t) {
            finish("failed");
            return false;
        }
    }

    private void startLocalVideo() {
        try {
            CameraEnumerator en = Camera2Enumerator.isSupported(app)
                    ? new Camera2Enumerator(app) : new Camera1Enumerator(true);
            String device = null;
            for (String name : en.getDeviceNames()) {
                if (en.isFrontFacing(name)) {
                    device = name;
                    break;
                }
            }
            if (device == null && en.getDeviceNames().length > 0) device = en.getDeviceNames()[0];
            if (device == null) return;

            capturer = en.createCapturer(device, null);
            if (capturer == null) return;
            frontCamera = en.isFrontFacing(device);
            surfaceHelper = SurfaceTextureHelper.create("capture", eglContext());
            videoSource = factory.createVideoSource(false);
            capturer.initialize(surfaceHelper, app, videoSource.getCapturerObserver());
            if (camEnabled) capturer.startCapture(640, 480, 24);
            videoTrack = factory.createVideoTrack("video0", videoSource);
            videoTrack.setEnabled(camEnabled);
        } catch (Throwable t) {
            videoTrack = null;                       // carry on as an audio call
        }
    }

    private void createOffer() {
        final PeerConnection p = pc;
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", video ? "true" : "false"));
        p.createOffer(new Sdp() {
            @Override
            public void onCreateSuccess(final SessionDescription d) {
                p.setLocalDescription(new Sdp() {
                    @Override
                    public void onSetSuccess() {
                        sendSdp("offer", d);
                    }
                }, d);
            }
        }, mc);
    }

    private void onRemoteOffer(String sdp) {
        final PeerConnection p = pc;
        p.setRemoteDescription(new Sdp() {
            @Override
            public void onSetSuccess() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pc != p) return;
                        drainIce();
                        p.createAnswer(new Sdp() {
                            @Override
                            public void onCreateSuccess(final SessionDescription d) {
                                p.setLocalDescription(new Sdp() {
                                    @Override
                                    public void onSetSuccess() {
                                        sendSdp("answer", d);
                                    }
                                }, d);
                            }
                        }, new MediaConstraints());
                    }
                });
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    private void onRemoteAnswer(String sdp) {
        final PeerConnection p = pc;
        p.setRemoteDescription(new Sdp() {
            @Override
            public void onSetSuccess() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pc == p) drainIce();
                    }
                });
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    private void onRemoteIce(IceCandidate c) {
        if (pc != null && remoteDescSet) pc.addIceCandidate(c);
        else pendingIce.add(c);                              // remote description not set yet
    }

    private void drainIce() {
        remoteDescSet = true;
        for (IceCandidate c : pendingIce) pc.addIceCandidate(c);
        pendingIce.clear();
    }

    private void sendSdp(final String type, final SessionDescription d) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state == IDLE) return;
                JSONObject o = Signaling.obj(type);
                try {
                    o.put("to", peerId);
                    o.put("callId", callId);
                    o.put("sdp", d.description);
                } catch (JSONException ignored) {
                }
                sig.send(o);
            }
        });
    }

    private void onIceState(PeerConnection.IceConnectionState s) {
        if (pc == null || state == IDLE) return;
        if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) {
            main.removeCallbacks(connectTimeout);
            main.removeCallbacks(reconnectTimeout);
            if (connectedAt == 0) {
                connectedAt = System.currentTimeMillis();
                limitVideoBitrate();
            }
            setState(CONNECTED);
        } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
            if (state == CONNECTED) {
                setState(RECONNECTING);
                main.postDelayed(reconnectTimeout, RECONNECT_TIMEOUT_MS);
            }
        } else if (s == PeerConnection.IceConnectionState.FAILED) {
            sendCall("hangup");
            finish(connectedAt > 0 ? "connection lost" : "failed");
        }
    }

    private void limitVideoBitrate() {
        try {
            for (RtpSender sender : pc.getSenders()) {
                if (sender.track() != null && "video".equals(sender.track().kind())) {
                    RtpParameters p = sender.getParameters();
                    if (p.encodings.size() > 0) {
                        p.encodings.get(0).maxBitrateBps = MAX_VIDEO_BPS;
                        sender.setParameters(p);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void onRemoteTrack(MediaStreamTrack t) {
        if (t instanceof VideoTrack) {
            remoteTrack = (VideoTrack) t;
            remoteTrack.setEnabled(true);
            bindSinks();
            notifyChanged();
        }
    }

    /** PeerConnection callbacks come on a WebRTC thread: hop to the main thread first. */
    private class PcObserver implements PeerConnection.Observer {
        public void onIceCandidate(final IceCandidate c) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (state == IDLE) return;
                    JSONObject o = Signaling.obj("ice");
                    try {
                        o.put("to", peerId);
                        o.put("callId", callId);
                        o.put("sdpMid", c.sdpMid);
                        o.put("sdpMLineIndex", c.sdpMLineIndex);
                        o.put("cand", c.sdp);
                    } catch (JSONException ignored) {
                    }
                    sig.send(o);
                }
            });
        }

        public void onIceConnectionChange(final PeerConnection.IceConnectionState s) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onIceState(s);
                }
            });
        }

        public void onTrack(final RtpTransceiver transceiver) {
            final MediaStreamTrack t = transceiver.getReceiver().track();
            main.post(new Runnable() {
                @Override
                public void run() {
                    onRemoteTrack(t);
                }
            });
        }

        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            final MediaStreamTrack t = receiver.track();
            main.post(new Runnable() {
                @Override
                public void run() {
                    onRemoteTrack(t);
                }
            });
        }

        public void onSignalingChange(PeerConnection.SignalingState s) {
        }

        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
        }

        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        }

        public void onAddStream(MediaStream stream) {
        }

        public void onRemoveStream(MediaStream stream) {
        }

        public void onDataChannel(DataChannel channel) {
        }

        public void onRenegotiationNeeded() {
        }
    }

    /** SdpObserver with empty defaults; failures end the call. */
    private abstract class Sdp implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription d) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
            failFromThread();
        }

        @Override
        public void onSetFailure(String error) {
            failFromThread();
        }
    }

    private void failFromThread() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state != IDLE) {
                    sendCall("hangup");
                    finish("failed");
                }
            }
        });
    }

    // ── Timers ────────────────────────────────────────────────────────────────
    private final Runnable ringTimeout = new Runnable() {
        @Override
        public void run() {
            if (state == CALLING) {
                sendCall("hangup");
                finish("no answer");
            } else if (state == RINGING_IN) {
                finish("missed");
            }
        }
    };

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (state == CONNECTING) {
                sendCall("hangup");
                finish("failed");
            }
        }
    };

    private final Runnable reconnectTimeout = new Runnable() {
        @Override
        public void run() {
            if (state == RECONNECTING) {
                sendCall("hangup");
                finish("connection lost");
            }
        }
    };

    // ── State, messages, cleanup ──────────────────────────────────────────────
    private void resetCall(String id, String peer, String name, boolean vid, boolean out) {
        callId = id;
        peerId = peer;
        peerName = name;
        video = vid;
        outgoing = out;
        remoteRinging = false;
        micMuted = false;
        camEnabled = !vid || data.flag(Data.KEY_CALL_CAM_ON, true);
        speakerOn = vid || data.flag(Data.KEY_CALL_SPEAKER, false);
        frontCamera = true;
        connectedAt = 0;
        endNote = "";
        remoteDescSet = false;
        pendingIce.clear();
    }

    private static String newCallId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void setState(int s) {
        state = s;
        notifyChanged();
    }

    private void notifyChanged() {
        for (Listener l : listeners) l.onCallChanged();
    }

    private void sendCall(String type) {
        sendTo(peerId, callId, type);
    }

    private void sendTo(String to, String cid, String type) {
        JSONObject o = Signaling.obj(type);
        try {
            o.put("to", to);
            o.put("callId", cid);
        } catch (JSONException ignored) {
        }
        sig.send(o);
    }

    /** Ends the call (note: cancelled / declined / missed / busy / no answer / not reachable / failed / connection lost / ended). */
    private void finish(String note) {
        if (state == IDLE) return;
        boolean wasConnected = connectedAt > 0;
        long secs = seconds();
        String kind = video ? "Video call" : "Voice call";
        String log;
        if (wasConnected) {
            log = kind + " · " + (secs / 60) + ":" + (secs % 60 < 10 ? "0" : "") + (secs % 60)
                    + ("connection lost".equals(note) ? " · connection lost" : "");
        } else {
            log = kind + " · " + note;
        }
        data.logCall(peerId, log);

        main.removeCallbacks(ringTimeout);
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(reconnectTimeout);
        stopRinging();
        releaseMedia();
        stopAudioMode();
        releaseWifiLock();

        endNote = describe(note, wasConnected);
        state = IDLE;
        notifyChanged();
    }

    private String describe(String note, boolean wasConnected) {
        if ("declined".equals(note)) return outgoing ? "Call declined" : "";
        if ("busy".equals(note)) return "User is busy";
        if ("no answer".equals(note)) return "No answer";
        if ("not reachable".equals(note)) return "User is offline or not on the call server";
        if ("failed".equals(note)) return "Could not connect the call";
        if ("connection lost".equals(note)) return "Connection lost";
        if ("missed".equals(note)) return outgoing ? "" : "Missed call";
        return "";
    }

    private void releaseMedia() {
        if (localView != null && videoTrack != null) videoTrack.removeSink(localView);
        if (remoteView != null && remoteTrack != null) remoteTrack.removeSink(remoteView);
        try {
            if (pc != null) {
                pc.close();
                pc.dispose();
            }
        } catch (Throwable ignored) {
        }
        pc = null;
        remoteTrack = null;
        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (Throwable ignored) {
            }
            try {
                capturer.dispose();
            } catch (Throwable ignored) {
            }
            capturer = null;
        }
        try {
            if (videoTrack != null) videoTrack.dispose();
            if (videoSource != null) videoSource.dispose();
            if (surfaceHelper != null) surfaceHelper.dispose();
            if (audioTrack != null) audioTrack.dispose();
            if (audioSource != null) audioSource.dispose();
        } catch (Throwable ignored) {
        }
        videoTrack = null;
        videoSource = null;
        surfaceHelper = null;
        audioTrack = null;
        audioSource = null;
        pendingIce.clear();
        remoteDescSet = false;
    }

    // ── Phone resources ───────────────────────────────────────────────────────
    private boolean hasPermission(String p) {
        return Build.VERSION.SDK_INT < 23 || app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAudioMode() {
        if (audioModeOn) return;
        savedAudioMode = audio.getMode();
        savedSpeaker = audio.isSpeakerphoneOn();
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audio.setSpeakerphoneOn(speakerOn);
        audioModeOn = true;
    }

    private void stopAudioMode() {
        if (!audioModeOn) return;
        audio.setSpeakerphoneOn(savedSpeaker);
        audio.setMode(savedAudioMode);
        audioModeOn = false;
    }

    /** Stops Wi-Fi power saving from throttling the call. */
    private void acquireWifiLock() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "llms-call");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
    }

    private void startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(app,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Throwable ignored) {
        }
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(new long[]{0, 700, 900}, 0);
        } catch (Throwable ignored) {
        }
    }

    private void stopRinging() {
        try {
            if (ringtone != null) ringtone.stop();
        } catch (Throwable ignored) {
        }
        ringtone = null;
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Throwable ignored) {
        }
    }
}