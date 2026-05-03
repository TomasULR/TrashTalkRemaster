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
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
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

    private final SignalingClient signaling;
    private final String channelId;
    private final String selfUserId;
    private final BiConsumer<String, BufferedImage> frameConsumer;
    private final IntConsumer audioLevelCallback;
    private final Consumer<RtcStatus> statusCallback;

    private PeerConnectionFactory factory;
    private AudioTrackSource audioSource;
    private AudioTrack localAudioTrack;
    private VideoDeviceSource videoSource;
    private VideoTrack localVideoTrack;

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
            factory     = new PeerConnectionFactory();
            audioSource = factory.createAudioSource(new AudioOptions());
            localAudioTrack = factory.createAudioTrack("audio_" + selfUserId, audioSource);
            if (audioLevelCallback != null) {
                localAudioTrack.addSink((AudioTrackSink) (audioData, bitsPerSample, sampleRate, channels, frames) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastLevelUpdateMs < 50) return;
                    lastLevelUpdateMs = now;
                    if (bitsPerSample == 16 && audioData.length >= 2) {
                        long sum = 0;
                        for (int i = 0; i + 1 < audioData.length; i += 2) {
                            short s = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
                            sum += (long) s * s;
                        }
                        int n = audioData.length / 2;
                        double rms = Math.sqrt((double) sum / n);
                        int level = Math.min(100, (int) (rms / 327.68));
                        audioLevelCallback.accept(level);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    public void startScreenShare(Rectangle captureBounds) {
        stopScreenShare();
        try {
            Rectangle screenRect = captureBounds != null
                    ? captureBounds
                    : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            Robot robot = new Robot();

            screenCaptureThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        BufferedImage frame = robot.createScreenCapture(screenRect);
                        // Local preview
                        frameConsumer.accept(selfUserId, frame);
                        // Send to peers via DataChannels
                        sendScreenFrame(frame);
                        Thread.sleep(66); // ~15 fps
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }, "screen-capture");
            screenCaptureThread.setDaemon(true);
            screenCaptureThread.start();
        } catch (Exception e) {
            throw new UnsupportedOperationException("Nelze spustit sdílení obrazovky: " + e.getMessage(), e);
        }
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
                if (SCREEN_CHANNEL.equals(channel.getLabel())) {
                    screenDataChannels.put(remoteUserId, channel);
                    channel.registerObserver(makeScreenReceiver(remoteUserId));
                }
            }
        });

        if (localAudioTrack != null) pc.addTrack(localAudioTrack, List.of("stream_" + selfUserId));
        if (localVideoTrack != null) pc.addTrack(localVideoTrack, List.of("stream_" + selfUserId));

        // Initiator pre-creates DataChannel so the SCTP m-line is in the offer.
        // Non-initiator receives it via onDataChannel above.
        if (isInitiator) {
            RTCDataChannel dc = pc.createDataChannel(SCREEN_CHANNEL, new RTCDataChannelInit());
            screenDataChannels.put(remoteUserId, dc);
            dc.registerObserver(makeScreenReceiver(remoteUserId));
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

        if (localVideoTrack != null) {
            try { localVideoTrack.dispose(); } catch (Exception ignored) {}
            localVideoTrack = null;
        }
        if (videoSource != null) {
            try { videoSource.stop(); }    catch (Exception ignored) {}
            try { videoSource.dispose(); } catch (Exception ignored) {}
            videoSource = null;
        }
        if (localAudioTrack != null) {
            try { localAudioTrack.dispose(); } catch (Exception ignored) {}
            localAudioTrack = null;
        }
        audioSource = null;
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
