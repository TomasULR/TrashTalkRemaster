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
import media.ScreenAudioCapture;
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

    private static final String SCREEN_CHANNEL       = "screen-share";
    private static final String SCREEN_AUDIO_CHANNEL = "screen-audio";
    // Voice is sent over a DataChannel using Opus, bypassing WebRTC's ADM which
    // crashes on Linux/Wayland with webrtc-java 0.9.0 (SIGSEGV in native ADM thread).
    private static final String VOICE_CHANNEL        = "voice-data";

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
    private final Map<String, RTCDataChannel> screenDataChannels      = new HashMap<>();
    private final Map<String, RTCDataChannel> screenAudioDataChannels = new HashMap<>();
    private final Map<String, AudioPlayback>  screenAudioPlaybacks    = new HashMap<>();
    private volatile Thread           screenCaptureThread;
    private volatile ScreenAudioCapture screenAudioCapture;
    // Separate codec instance for screen-audio encoding so it doesn't share native
    // state with the voice codec — concurrent encode() calls on one instance → SIGFPE.
    private volatile OpusCodec screenAudioOpusCodec;
    private volatile Process   ffmpegProcess;

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

    // Tool availability cache — detected once at first capture attempt
    private static Boolean grimAvailable      = null;
    private static Boolean ffmpegX11Available = null;

    private static boolean isNativeCaptureAvailable() {
        return isGrimAvailable() || isFfmpegX11Available();
    }

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

    // ffmpeg x11grab: captures the X11 display directly — no XDG portal, no permission dialog.
    // Works with xwaylandvideobridge because the bridge feeds Wayland content into the X11 root.
    private static boolean isFfmpegX11Available() {
        if (ffmpegX11Available != null) return ffmpegX11Available;
        String display = System.getenv("DISPLAY");
        if (display == null || display.isEmpty()) { return ffmpegX11Available = false; }
        try {
            Process p = new ProcessBuilder("ffmpeg", "-formats")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            ffmpegX11Available = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    && out.contains("x11grab");
        } catch (Exception e) {
            ffmpegX11Available = false;
        }
        System.out.println("[ScreenShare] ffmpeg x11grab available: " + ffmpegX11Available);
        return ffmpegX11Available;
    }

    private static BufferedImage captureWithGrim() throws Exception {
        Process p = new ProcessBuilder("grim", "-t", "png", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        BufferedImage img = ImageIO.read(p.getInputStream());
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        if (img == null) throw new IOException("grim produced no image");
        return img;
    }

    private static BufferedImage captureWithFfmpegX11() throws Exception {
        String display = System.getenv("DISPLAY");
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("trashtalk-cap-", ".png");
        try {
            Process p = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-f", "x11grab",
                    "-i", display,
                    "-vframes", "1",
                    "-update", "1",
                    tmp.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) { p.destroyForcibly(); throw new IOException("ffmpeg timed out"); }
            if (p.exitValue() != 0) throw new IOException("ffmpeg exit " + p.exitValue());
            BufferedImage img = ImageIO.read(tmp.toFile());
            if (img == null) throw new IOException("ffmpeg produced no image");
            return img;
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    /** Captures using the best available native tool (grim → ffmpeg x11grab). */
    private static BufferedImage captureNative() throws Exception {
        if (isGrimAvailable())      return captureWithGrim();
        if (isFfmpegX11Available()) return captureWithFfmpegX11();
        throw new IOException("No native capture tool available");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /** Returns the union of all screen bounds in Java/X11 logical coordinates. */
    private static java.awt.Rectangle combinedScreenBounds() {
        java.awt.Rectangle r = new java.awt.Rectangle();
        for (java.awt.GraphicsDevice dev :
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            r = r.union(dev.getDefaultConfiguration().getBounds());
        }
        return r;
    }

    /**
     * Crops {@code full} to the region described by {@code region} in Java's logical
     * coordinate space (origin = top-left of the combined virtual screen).
     * Handles the case where the native screenshot is at a different pixel scale.
     */
    private static BufferedImage cropToRegion(BufferedImage full, Rectangle region) {
        java.awt.Rectangle combined = combinedScreenBounds();
        if (combined.width <= 0 || combined.height <= 0) return full;
        double sx = (double) full.getWidth()  / combined.width;
        double sy = (double) full.getHeight() / combined.height;
        int cx = (int) Math.round((region.x - combined.x) * sx);
        int cy = (int) Math.round((region.y - combined.y) * sy);
        int cw = (int) Math.round(region.width  * sx);
        int ch = (int) Math.round(region.height * sy);
        cx = Math.max(0, Math.min(cx, full.getWidth()  - 1));
        cy = Math.max(0, Math.min(cy, full.getHeight() - 1));
        cw = Math.min(cw, full.getWidth()  - cx);
        ch = Math.min(ch, full.getHeight() - cy);
        if (cw <= 0 || ch <= 0) return full;
        return full.getSubimage(cx, cy, cw, ch);
    }

    /**
     * Reads one complete JPEG frame from an MJPEG byte stream.
     * Scans for SOI (FF D8) and collects bytes up to and including EOI (FF D9).
     */
    private static byte[] readMjpegFrame(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(256 * 1024);
        // Skip bytes until SOI marker
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("ffmpeg stream closed");
            if (prev == 0xFF && b == 0xD8) { buf.write(0xFF); buf.write(0xD8); break; }
            prev = b;
        }
        // Collect until EOI marker
        prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("ffmpeg stream closed");
            buf.write(b);
            if (prev == 0xFF && b == 0xD9) break;
            prev = b;
        }
        return buf.toByteArray();
    }

    public void startScreenShare(Rectangle captureBounds) {
        stopScreenShare();

        // Start desktop audio alongside video — use a dedicated OpusCodec instance to avoid
        // concurrent encode() calls on the shared voice codec (causes SIGFPE in JNA/libopus).
        try { screenAudioOpusCodec = new OpusCodec(); } catch (Exception e) { screenAudioOpusCodec = null; }
        if (screenAudioCapture == null) {
            screenAudioCapture = new ScreenAudioCapture(this::sendScreenAudioPacket);
            screenAudioCapture.start();
        }

        if (isLinux() && isFfmpegX11Available()) {
            // ── Linux path: long-lived ffmpeg process streaming MJPEG at 60 fps ──────────────
            // ffmpeg captures exactly the selected region at 60 fps, outputs JPEG frames
            // to stdout. No XDG portal, no per-frame process spawn overhead.
            java.awt.Rectangle src = captureBounds != null ? captureBounds : combinedScreenBounds();
            // Output capped at 1920×1080 (must be even for MJPEG encoder)
            int outW, outH;
            double ar = (double) src.width / src.height;
            if (src.width > 1920 || src.height > 1080) {
                if (ar >= (double) 1920 / 1080) { outW = 1920; outH = (int)(1920 / ar); }
                else                             { outH = 1080; outW = (int)(1080 * ar); }
            } else { outW = src.width; outH = src.height; }
            outW = outW & ~1; outH = outH & ~1; // round down to even

            String display = System.getenv("DISPLAY");
            // Avoid double screen spec: if DISPLAY already contains ".", use as-is (e.g. ":0.0")
            String screenSpec = display.contains(".") ? display : display + ".0";
            String input = screenSpec + "+" + src.x + "," + src.y;
            System.out.printf("[ScreenShare] ffmpeg MJPEG stream  display=%s  region=%dx%d+%d+%d  out=%dx%d%n",
                    input, src.width, src.height, src.x, src.y, outW, outH);
            java.io.File ffmpegLog = new java.io.File(System.getProperty("java.io.tmpdir"), "ffmpeg_screenshare.log");
            try {
                ffmpegProcess = new ProcessBuilder(
                        "ffmpeg", "-y",
                        "-f", "x11grab", "-framerate", "60",
                        "-video_size", src.width + "x" + src.height,
                        "-i", input,
                        "-vf", "scale=" + outW + ":" + outH + ",format=yuvj420p",
                        "-vcodec", "mjpeg", "-q:v", "4",
                        "-f", "image2pipe", "pipe:1")
                        .redirectError(ffmpegLog)
                        .start();
                System.out.println("[ScreenShare] ffmpeg stderr → " + ffmpegLog.getAbsolutePath());
            } catch (Exception e) {
                throw new UnsupportedOperationException("Nelze spustit ffmpeg: " + e.getMessage(), e);
            }
            final java.io.InputStream ffIn =
                    new java.io.BufferedInputStream(ffmpegProcess.getInputStream(), 1 << 20);

            screenCaptureThread = new Thread(() -> {
                int consecErrors = 0;
                int frameCount = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] jpeg = readMjpegFrame(ffIn);
                        consecErrors = 0;
                        frameCount++;
                        if (frameCount <= 3 || frameCount % 60 == 0) {
                            System.out.printf("[ScreenShare] frame #%d  size=%d  channels=%d%n",
                                    frameCount, jpeg.length, screenDataChannels.size());
                        }
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                        if (img != null) {
                            frameConsumer.accept(selfUserId, img);
                        } else {
                            System.err.println("[ScreenShare] ImageIO.read returned null for frame #" + frameCount
                                    + " (size=" + jpeg.length + ", first bytes="
                                    + String.format("%02X %02X %02X %02X",
                                        jpeg.length > 0 ? jpeg[0] & 0xFF : 0,
                                        jpeg.length > 1 ? jpeg[1] & 0xFF : 0,
                                        jpeg.length > 2 ? jpeg[2] & 0xFF : 0,
                                        jpeg.length > 3 ? jpeg[3] & 0xFF : 0) + ")");
                        }
                        sendRawJpegFrame(jpeg);
                    } catch (IOException e) {
                        System.err.println("[ScreenShare] ffmpeg stream ended: " + e.getMessage());
                        break;
                    } catch (Throwable e) {
                        if (Thread.currentThread().isInterrupted()) break;
                        if (++consecErrors >= 10) { System.err.println("[ScreenShare] too many errors"); break; }
                    }
                }
                System.out.println("[ScreenShare] capture thread exited after " + frameCount + " frames");
            }, "screen-capture");

        } else {
            // ── Windows / Linux without ffmpeg: Robot capture ────────────────────────────────
            final Robot robot;
            try {
                robot = new Robot();
            } catch (Exception e) {
                throw new UnsupportedOperationException("Nelze spustit sdílení obrazovky: " + e.getMessage(), e);
            }
            final Rectangle[] candidates;
            if (captureBounds != null) {
                candidates = new Rectangle[]{ captureBounds };
            } else {
                java.awt.Rectangle dev = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                candidates = new Rectangle[]{
                    new Rectangle(0, 0, dev.width, dev.height),
                    new Rectangle(0, 0, dev.width/2, dev.height/2),
                    new Rectangle(0, 0, 2560, 1440),
                    new Rectangle(0, 0, 1920, 1080),
                    new Rectangle(0, 0, 1280,  720),
                };
            }
            System.out.println("[ScreenShare] Robot capture, first candidate: " + candidates[0]);

            screenCaptureThread = new Thread(() -> {
                int     probeIdx     = 0;
                boolean rectLocked   = (candidates.length == 1);
                int     consecErrors = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        BufferedImage frame = robot.createScreenCapture(candidates[probeIdx]);
                        consecErrors = 0;
                        frameConsumer.accept(selfUserId, frame);
                        sendScreenFrame(frame);
                        Thread.sleep(1000 / 60);
                    } catch (InterruptedException e) {
                        break;
                    } catch (SecurityException e) {
                        if (!rectLocked && probeIdx + 1 < candidates.length) {
                            probeIdx++;
                            System.out.println("[ScreenShare] SecurityException, probing: " + candidates[probeIdx]);
                            try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                        } else {
                            if (++consecErrors >= 5) { System.err.println("[ScreenShare] giving up"); break; }
                            try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                        }
                    } catch (Throwable e) {
                        if (++consecErrors >= 10) { System.err.println("[ScreenShare] too many errors"); break; }
                        try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                    }
                }
                System.out.println("[ScreenShare] capture thread exited");
            }, "screen-capture");
        }

        screenCaptureThread.setDaemon(true);
        screenCaptureThread.start();
    }

    private void sendScreenFrame(BufferedImage frame) {
        if (screenDataChannels.isEmpty()) return;
        try {
            // Send at source resolution (no downscale). Cap at 1920×1080 only if larger,
            // to keep JPEG payload within DataChannel message limits (~256 KB).
            BufferedImage out = scaleFrame(frame, 1920, 1080);
            byte[] jpeg = toJpeg(out, 0.70f);
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

    private void sendRawJpegFrame(byte[] jpeg) {
        if (screenDataChannels.isEmpty()) return;
        ByteBuffer buf = ByteBuffer.wrap(jpeg);
        RTCDataChannelBuffer dcBuf = new RTCDataChannelBuffer(buf, true);
        for (RTCDataChannel dc : screenDataChannels.values()) {
            try {
                if (dc.getState() == RTCDataChannelState.OPEN) { dc.send(dcBuf); buf.rewind(); }
            } catch (Exception ignored) {}
        }
    }

    private void sendScreenAudioPacket(short[] pcm) {
        if (screenAudioDataChannels.isEmpty() || screenAudioOpusCodec == null) return;
        byte[] opus = screenAudioOpusCodec.encode(pcm);
        if (opus == null) return;
        ByteBuffer buf = ByteBuffer.wrap(opus);
        RTCDataChannelBuffer dcBuf = new RTCDataChannelBuffer(buf, true);
        for (RTCDataChannel dc : screenAudioDataChannels.values()) {
            try {
                if (dc.getState() == RTCDataChannelState.OPEN) {
                    dc.send(dcBuf);
                    buf.rewind();
                }
            } catch (Exception ignored) {}
        }
    }

    public void stopScreenShare() {
        if (screenCaptureThread != null) {
            screenCaptureThread.interrupt();
            screenCaptureThread = null;
        }
        Process fp = ffmpegProcess;
        if (fp != null) { fp.destroyForcibly(); ffmpegProcess = null; }
        if (screenAudioCapture != null) {
            screenAudioCapture.stop();
            screenAudioCapture = null;
        }
        if (screenAudioOpusCodec != null) {
            try { screenAudioOpusCodec.close(); } catch (Exception ignored) {}
            screenAudioOpusCodec = null;
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
                    case SCREEN_AUDIO_CHANNEL:
                        screenAudioDataChannels.put(remoteUserId, channel);
                        channel.registerObserver(makeScreenAudioReceiver(remoteUserId));
                        break;
                    case VOICE_CHANNEL:
                        voiceDataChannels.put(remoteUserId, channel);
                        channel.registerObserver(makeVoiceReceiver(remoteUserId));
                        break;
                }
            }
        });

        if (localVideoTrack != null) pc.addTrack(localVideoTrack, List.of("stream_" + selfUserId));

        // Initiator pre-creates all DataChannels so the SCTP m-line is in the offer.
        if (isInitiator) {
            RTCDataChannel screenDc = pc.createDataChannel(SCREEN_CHANNEL, new RTCDataChannelInit());
            screenDataChannels.put(remoteUserId, screenDc);
            screenDc.registerObserver(makeScreenReceiver(remoteUserId));

            RTCDataChannel screenAudioDc = pc.createDataChannel(SCREEN_AUDIO_CHANNEL, new RTCDataChannelInit());
            screenAudioDataChannels.put(remoteUserId, screenAudioDc);
            screenAudioDc.registerObserver(makeScreenAudioReceiver(remoteUserId));

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
                    if (img != null) {
                        frameConsumer.accept(fromUserId, img);
                    } else {
                        System.err.println("[ScreenShare] receiver ImageIO.read null, size=" + data.length
                                + " first=" + (data.length > 1 ? String.format("%02X %02X", data[0] & 0xFF, data[1] & 0xFF) : "?"));
                    }
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

    private RTCDataChannelObserver makeScreenAudioReceiver(String fromUserId) {
        return new RTCDataChannelObserver() {
            @Override public void onBufferedAmountChange(long amount) {}

            @Override
            public void onStateChange() {
                RTCDataChannel dc = screenAudioDataChannels.get(fromUserId);
                if (dc != null && dc.getState() == RTCDataChannelState.OPEN) {
                    if (!screenAudioPlaybacks.containsKey(fromUserId)) {
                        try {
                            AudioPlayback pb = new AudioPlayback();
                            pb.start();
                            screenAudioPlaybacks.put(fromUserId, pb);
                        } catch (Exception e) {
                            System.err.println("ScreenAudio playback start failed: " + e.getMessage());
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
                    // Use a separate key so screen-audio decoder state doesn't conflict with voice
                    short[] pcm = opusCodec.decode(fromUserId + ":screen", opus);
                    if (pcm != null) {
                        AudioPlayback pb = screenAudioPlaybacks.get(fromUserId);
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

        screenAudioDataChannels.values().forEach(dc -> { try { dc.dispose(); } catch (Exception ignored) {} });
        screenAudioDataChannels.clear();

        screenAudioPlaybacks.values().forEach(pb -> { try { pb.stop(); } catch (Exception ignored) {} });
        screenAudioPlaybacks.clear();

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
