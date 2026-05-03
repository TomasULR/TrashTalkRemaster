package voice;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import media.AudioCapture;
import media.AudioPlayback;
import media.OpusCodec;
import signal.SignalingClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class WebRtcManager {

    public enum RtcStatus { IDLE, CONNECTING, CONNECTED, FAILED }

    private static final String SCREEN_CHANNEL = "screen-share";
    // Voice is sent over a DataChannel using Opus, bypassing WebRTC's ADM which
    // crashes on Linux/Wayland with webrtc-java 0.9.0 (SIGSEGV in native ADM thread).
    private static final String VOICE_CHANNEL  = "voice-data";

    private final SignalingClient signaling;
    private final String channelId;
    private final String selfUserId;
    private final BiConsumer<String, BufferedImage> frameConsumer;
    private final IntConsumer audioLevelCallback;
    private final Consumer<RtcStatus> statusCallback;

    private PeerConnectionFactory factory;
    private VideoDeviceSource videoSource;
    private VideoTrack localVideoTrack;

    // Audio — pure Java via AudioCapture/OpusCodec to avoid WebRTC ADM crash on Linux
    private AudioCapture audioCapture;
    private OpusCodec    opusCodec;
    private final Map<String, RTCDataChannel> voiceDataChannels  = new HashMap<>();
    private final Map<String, AudioPlayback>  audioPlaybacks     = new HashMap<>();

    // Screen share — DataChannel path (avoids native crash on Linux)
    private final Map<String, RTCDataChannel> screenDataChannels = new HashMap<>();
    private volatile Thread screenCaptureThread;

    private volatile long lastLevelUpdateMs = 0;

    private final Map<String, RTCPeerConnection>     peers     = new HashMap<>();
    private final Map<String, RTCIceConnectionState> iceStates = new HashMap<>();

    public WebRtcManager(SignalingClient signaling, String channelId, String selfUserId,
                         BiConsumer<String, BufferedImage> frameConsumer,
                         IntConsumer audioLevelCallback,
                         Consumer<RtcStatus> statusCallback) {
        this.signaling          = signaling;
        this.channelId          = channelId;
        this.selfUserId         = selfUserId;
        this.frameConsumer      = frameConsumer;
        this.audioLevelCallback = audioLevelCallback;
        this.statusCallback     = statusCallback;
        init();
    }

    // ---- status ----

    private void updateStatus() {
        if (statusCallback == null) return;
        if (iceStates.isEmpty()) { statusCallback.accept(RtcStatus.IDLE); return; }
        boolean anyFailed     = iceStates.values().stream().anyMatch(s ->
                s == RTCIceConnectionState.FAILED || s == RTCIceConnectionState.DISCONNECTED);
        boolean anyConnecting = iceStates.values().stream().anyMatch(s ->
                s == RTCIceConnectionState.CHECKING || s == RTCIceConnectionState.NEW);
        boolean allConnected  = iceStates.values().stream().allMatch(s ->
                s == RTCIceConnectionState.CONNECTED || s == RTCIceConnectionState.COMPLETED);
        if (anyFailed)          statusCallback.accept(RtcStatus.FAILED);
        else if (allConnected)  statusCallback.accept(RtcStatus.CONNECTED);
        else if (anyConnecting) statusCallback.accept(RtcStatus.CONNECTING);
        else                    statusCallback.accept(RtcStatus.IDLE);
    }

    // ---- init ----

    private void init() {
        try {
            factory = new PeerConnectionFactory();
            // No WebRTC AudioTrackSource — starting it launches a native ADM thread that
            // crashes on Linux/Wayland (SIGSEGV at libwebrtc-java+0xad9aaf).
            // Instead, use AudioCapture (javax.sound) for mic input and level metering.
            try {
                opusCodec = new OpusCodec();
                audioCapture = new AudioCapture(this::onAudioFrame);
                audioCapture.start();
            } catch (Exception e) {
                System.err.println("AudioCapture init failed (no mic?): " + e.getMessage());
                audioCapture = null;
                opusCodec    = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onAudioFrame(short[] pcm) {
        // Audio level metering
        if (audioLevelCallback != null) {
            long now = System.currentTimeMillis();
            if (now - lastLevelUpdateMs >= 50) {
                lastLevelUpdateMs = now;
                long sum = 0;
                for (short s : pcm) sum += (long) s * s;
                int level = Math.min(100, (int) (Math.sqrt((double) sum / pcm.length) * 100 / 32768));
                audioLevelCallback.accept(level);
            }
        }
        // Encode and broadcast to all connected peers
        if (opusCodec != null) {
            byte[] opus = opusCodec.encode(pcm);
            if (opus != null) sendVoicePacket(opus);
        }
    }

    private void sendVoicePacket(byte[] opus) {
        if (voiceDataChannels.isEmpty()) return;
        ByteBuffer buf = ByteBuffer.wrap(opus);
        RTCDataChannelBuffer dcBuf = new RTCDataChannelBuffer(buf, true);
        for (RTCDataChannel dc : voiceDataChannels.values()) {
            try {
                if (dc.getState() == RTCDataChannelState.OPEN) {
                    dc.send(dcBuf);
                    buf.rewind();
                }
            } catch (Exception ignored) {}
        }
    }

    public void setMuted(boolean muted) {
        if (audioCapture != null) audioCapture.setMuted(muted);
    }

    // ---- camera ----

    public void startCamera() {
        try {
            if (videoSource != null) { videoSource.stop(); videoSource.dispose(); }
            videoSource = new VideoDeviceSource();
            videoSource.setVideoCaptureCapability(new VideoCaptureCapability(1920, 1080, 60));
            videoSource.start();
            if (localVideoTrack != null) localVideoTrack.dispose();
            localVideoTrack = factory.createVideoTrack("camera_" + selfUserId, videoSource);
            localVideoTrack.addSink(new WebRtcVideoSink(selfUserId, frameConsumer));
            for (RTCPeerConnection pc : peers.values()) {
                pc.addTrack(localVideoTrack, List.of("stream_" + selfUserId));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---- screen share ----

    private static Boolean grimAvailable = null;

    private static boolean isGrimAvailable() {
        if (grimAvailable != null) return grimAvailable;
        try {
            Process p = new ProcessBuilder("which", "grim")
                    .redirectErrorStream(true).start();
            grimAvailable = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            grimAvailable = false;
        }
        System.out.println("[ScreenShare] grim available: " + grimAvailable);
        return grimAvailable;
    }

    private static BufferedImage captureWithGrim() throws Exception {
        Process p = new ProcessBuilder("grim", "-t", "png", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        BufferedImage img = javax.imageio.ImageIO.read(p.getInputStream());
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        if (img == null) throw new IOException("grim produced no image");
        return img;
    }

    public void startScreenShare(Rectangle captureBounds) {
        stopScreenShare();

        // On Wayland with no explicit bounds, prefer grim (wlr-screencopy) over the
        // unreliable PipeWire/Robot portal which consistently throws SecurityException
        // on KDE Plasma 6 / Bazzite due to HiDPI logical vs physical pixel mismatches.
        final boolean startWithGrim = (captureBounds == null) && isGrimAvailable();

        final Robot robot;
        try {
            robot = new Robot();
        } catch (Exception e) {
            throw new UnsupportedOperationException("Nelze spustit sdílení obrazovky: " + e.getMessage(), e);
        }

        // Always build Robot fallback candidates (used when grim is unavailable or fails)
        final Rectangle[] robotCandidates;
        if (captureBounds != null) {
            robotCandidates = new Rectangle[]{ captureBounds };
        } else {
            java.awt.Rectangle dev = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            int fw = dev.width,  fh = dev.height;
            int hw = fw / 2,     hh = fh / 2;
            robotCandidates = new Rectangle[]{
                new Rectangle(0, 0, fw,   fh),
                new Rectangle(0, 0, hw,   hh),
                new Rectangle(0, 0, 2560, 1440),
                new Rectangle(0, 0, 1920, 1080),
                new Rectangle(0, 0, 1280,  720),
            };
        }

        if (startWithGrim) {
            System.out.println("[ScreenShare] using grim for Wayland capture");
        } else {
            System.out.println("[ScreenShare] using Robot, first candidate: " + robotCandidates[0]);
        }

        screenCaptureThread = new Thread(() -> {
            // phase 0 = grim, phase 1 = Robot probe
            int     phase        = startWithGrim ? 0 : 1;
            int     probeIdx     = 0;
            boolean rectLocked   = (robotCandidates.length == 1);
            int     consecErrors = 0;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    BufferedImage frame;
                    if (phase == 0) {
                        frame = captureWithGrim();
                    } else {
                        frame = robot.createScreenCapture(robotCandidates[probeIdx]);
                    }

                    consecErrors = 0;
                    frameConsumer.accept(selfUserId, frame);
                    sendScreenFrame(frame);
                    Thread.sleep(66); // ~15 fps

                } catch (InterruptedException e) {
                    break;

                } catch (SecurityException e) {
                    if (phase == 1 && !rectLocked && probeIdx + 1 < robotCandidates.length) {
                        probeIdx++;
                        System.out.println("[ScreenShare] SecurityException, probing: "
                                + robotCandidates[probeIdx]);
                        try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                    } else {
                        consecErrors++;
                        System.err.println("[ScreenShare] SecurityException #" + consecErrors);
                        if (consecErrors >= 5) { System.err.println("[ScreenShare] giving up"); break; }
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    }

                } catch (Throwable e) {
                    if (phase == 0) {
                        System.err.println("[ScreenShare] grim failed (" + e.getMessage()
                                + "), falling back to Robot probe");
                        phase = 1;
                        try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                    } else {
                        consecErrors++;
                        System.err.println("[ScreenShare] error #" + consecErrors + ": " + e);
                        if (consecErrors >= 10) {
                            System.err.println("[ScreenShare] too many errors, stopping");
                            break;
                        }
                        try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                    }
                }
            }
            System.out.println("[ScreenShare] capture thread exited");
        }, "screen-capture");
        screenCaptureThread.setDaemon(true);
        screenCaptureThread.start();
    }

    private void sendScreenFrame(BufferedImage frame) {
        if (screenDataChannels.isEmpty()) return;
        try {
            BufferedImage scaled = scaleFrame(frame, 1280, 720);
            byte[] jpeg = toJpeg(scaled, 0.55f);
            ByteBuffer buf = ByteBuffer.wrap(jpeg);
            RTCDataChannelBuffer dcBuf = new RTCDataChannelBuffer(buf, true);
            for (RTCDataChannel dc : screenDataChannels.values()) {
                try {
                    if (dc.getState() == RTCDataChannelState.OPEN) {
                        dc.send(dcBuf);
                        buf.rewind();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopScreenShare() {
        if (screenCaptureThread != null) {
            screenCaptureThread.interrupt();
            screenCaptureThread = null;
        }
    }

    // ---- peer connections ----

    public void createPeerConnection(String remoteUserId, boolean isInitiator) {
        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer stun = new RTCIceServer();
        stun.urls.add("stun:stun.l.google.com:19302");
        config.iceServers.add(stun);

        iceStates.put(remoteUserId, RTCIceConnectionState.NEW);
        updateStatus();

        RTCPeerConnection pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                signaling.sendIceCandidate(channelId, remoteUserId,
                        candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex);
            }

            @Override
            public void onIceConnectionChange(RTCIceConnectionState state) {
                iceStates.put(remoteUserId, state);
                updateStatus();
            }

            @Override
            public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = receiver.getTrack();
                if ("video".equalsIgnoreCase(track.getKind())) {
                    ((VideoTrack) track).addSink(new WebRtcVideoSink(remoteUserId, frameConsumer));
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                switch (channel.getLabel()) {
                    case SCREEN_CHANNEL:
                        screenDataChannels.put(remoteUserId, channel);
                        channel.registerObserver(makeScreenReceiver(remoteUserId));
                        break;
                    case VOICE_CHANNEL:
                        voiceDataChannels.put(remoteUserId, channel);
                        channel.registerObserver(makeVoiceReceiver(remoteUserId));
                        break;
                }
            }
        });

        if (localVideoTrack != null) pc.addTrack(localVideoTrack, List.of("stream_" + selfUserId));

        // Initiator pre-creates both DataChannels so the SCTP m-line is in the offer.
        if (isInitiator) {
            RTCDataChannel screenDc = pc.createDataChannel(SCREEN_CHANNEL, new RTCDataChannelInit());
            screenDataChannels.put(remoteUserId, screenDc);
            screenDc.registerObserver(makeScreenReceiver(remoteUserId));

            RTCDataChannel voiceDc = pc.createDataChannel(VOICE_CHANNEL, new RTCDataChannelInit());
            voiceDataChannels.put(remoteUserId, voiceDc);
            voiceDc.registerObserver(makeVoiceReceiver(remoteUserId));
        }

        peers.put(remoteUserId, pc);

        if (isInitiator) {
            pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override public void onSuccess() {
                            signaling.sendOffer(channelId, remoteUserId, description.sdp);
                        }
                        @Override public void onFailure(String error) {
                            System.err.println("setLocalDescription failed: " + error);
                        }
                    });
                }
                @Override public void onFailure(String error) {
                    System.err.println("createOffer failed: " + error);
                }
            });
        }
    }

    private RTCDataChannelObserver makeScreenReceiver(String fromUserId) {
        return new RTCDataChannelObserver() {
            @Override public void onBufferedAmountChange(long amount) {}
            @Override public void onStateChange() {}

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                try {
                    byte[] data = new byte[buffer.data.remaining()];
                    buffer.data.get(data);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) frameConsumer.accept(fromUserId, img);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    private RTCDataChannelObserver makeVoiceReceiver(String fromUserId) {
        return new RTCDataChannelObserver() {
            @Override public void onBufferedAmountChange(long amount) {}

            @Override
            public void onStateChange() {
                // Start playback line when channel opens
                RTCDataChannel dc = voiceDataChannels.get(fromUserId);
                if (dc != null && dc.getState() == RTCDataChannelState.OPEN) {
                    if (!audioPlaybacks.containsKey(fromUserId)) {
                        try {
                            AudioPlayback pb = new AudioPlayback();
                            pb.start();
                            audioPlaybacks.put(fromUserId, pb);
                        } catch (Exception e) {
                            System.err.println("AudioPlayback start failed: " + e.getMessage());
                        }
                    }
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                if (opusCodec == null) return;
                try {
                    byte[] opus = new byte[buffer.data.remaining()];
                    buffer.data.get(opus);
                    short[] pcm = opusCodec.decode(fromUserId, opus);
                    if (pcm != null) {
                        AudioPlayback pb = audioPlaybacks.get(fromUserId);
                        if (pb != null) pb.play(pcm);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    public void handleOffer(String remoteUserId, String sdp) {
        RTCPeerConnection pc = peers.get(remoteUserId);
        if (pc == null) {
            createPeerConnection(remoteUserId, false);
            pc = peers.get(remoteUserId);
        }
        if (pc == null) return;

        final RTCPeerConnection finalPc = pc;
        finalPc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, sdp),
                new SetSessionDescriptionObserver() {
            @Override public void onSuccess() {
                finalPc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override public void onSuccess(RTCSessionDescription description) {
                        finalPc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                            @Override public void onSuccess() {
                                signaling.sendAnswer(channelId, remoteUserId, description.sdp);
                            }
                            @Override public void onFailure(String error) {}
                        });
                    }
                    @Override public void onFailure(String error) {}
                });
            }
            @Override public void onFailure(String error) {}
        });
    }

    public void handleAnswer(String remoteUserId, String sdp) {
        RTCPeerConnection pc = peers.get(remoteUserId);
        if (pc != null) {
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp),
                    new SetSessionDescriptionObserver() {
                @Override public void onSuccess() {}
                @Override public void onFailure(String error) {}
            });
        }
    }

    public void handleIceCandidate(String remoteUserId, String sdp, String sdpMid, int sdpMLineIndex) {
        RTCPeerConnection pc = peers.get(remoteUserId);
        if (pc != null) {
            pc.addIceCandidate(new RTCIceCandidate(sdpMid, sdpMLineIndex, sdp));
        }
    }

    public void dispose() {
        for (RTCPeerConnection pc : peers.values()) {
            try { pc.close(); } catch (Exception ignored) {}
        }
        peers.clear();
        iceStates.clear();
        if (statusCallback != null) statusCallback.accept(RtcStatus.IDLE);

        stopScreenShare();
        screenDataChannels.values().forEach(dc -> { try { dc.dispose(); } catch (Exception ignored) {} });
        screenDataChannels.clear();

        voiceDataChannels.values().forEach(dc -> { try { dc.dispose(); } catch (Exception ignored) {} });
        voiceDataChannels.clear();

        audioPlaybacks.values().forEach(pb -> { try { pb.stop(); } catch (Exception ignored) {} });
        audioPlaybacks.clear();

        if (audioCapture != null) {
            try { audioCapture.stop(); } catch (Exception ignored) {}
            audioCapture = null;
        }
        if (opusCodec != null) {
            try { opusCodec.close(); } catch (Exception ignored) {}
            opusCodec = null;
        }

        if (localVideoTrack != null) {
            try { localVideoTrack.dispose(); } catch (Exception ignored) {}
            localVideoTrack = null;
        }
        if (videoSource != null) {
            try { videoSource.stop(); }    catch (Exception ignored) {}
            try { videoSource.dispose(); } catch (Exception ignored) {}
            videoSource = null;
        }
        if (factory != null) {
            try { factory.dispose(); } catch (Exception ignored) {}
            factory = null;
        }
    }

    // ---- frame helpers ----

    private static BufferedImage scaleFrame(BufferedImage src, int maxW, int maxH) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW && h <= maxH) return src;
        float scale = Math.min((float) maxW / w, (float) maxH / h);
        int nw = Math.max(1, (int) (w * scale));
        int nh = Math.max(1, (int) (h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static byte[] toJpeg(BufferedImage img, float quality) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        writer.setOutput(ImageIO.createImageOutputStream(out));
        writer.write(null, new IIOImage(img, null, null), param);
        writer.dispose();
        return out.toByteArray();
    }
}
