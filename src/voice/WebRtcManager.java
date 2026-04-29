package voice;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
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
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.desktop.DesktopCaptureCallback;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import signal.SignalingClient;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

public class WebRtcManager {

    private final SignalingClient signaling;
    private final String channelId;
    private final String selfUserId;
    private final BiConsumer<String, BufferedImage> frameConsumer;
    private final IntConsumer audioLevelCallback;

    private PeerConnectionFactory factory;
    private AudioTrackSource audioSource;
    private AudioTrack localAudioTrack;
    private ScreenCapturer screenCapturer;
    private Thread screenCaptureThread;
    private VideoDeviceSource videoSource;
    private VideoTrack localVideoTrack;

    private volatile long lastLevelUpdateMs = 0;

    private final Map<String, RTCPeerConnection> peers = new HashMap<>();

    public WebRtcManager(SignalingClient signaling, String channelId, String selfUserId,
                         BiConsumer<String, BufferedImage> frameConsumer,
                         IntConsumer audioLevelCallback) {
        this.signaling = signaling;
        this.channelId = channelId;
        this.selfUserId = selfUserId;
        this.frameConsumer = frameConsumer;
        this.audioLevelCallback = audioLevelCallback;
        init();
    }

    private void init() {
        try {
            factory = new PeerConnectionFactory();
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

    public void startCamera() {
        try {
            if (videoSource != null) { videoSource.stop(); videoSource.dispose(); }
            videoSource = new VideoDeviceSource();
            videoSource.setVideoCaptureCapability(new VideoCaptureCapability(1920, 1080, 60));
            videoSource.start();
            if (localVideoTrack != null) localVideoTrack.dispose();
            localVideoTrack = factory.createVideoTrack("camera_" + selfUserId, videoSource);
            // Lokální preview — uvidíš sám sebe v gridu
            localVideoTrack.addSink(new WebRtcVideoSink(selfUserId, frameConsumer));
            for (RTCPeerConnection pc : peers.values()) {
                pc.addTrack(localVideoTrack, List.of("stream_" + selfUserId));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void startScreenShare() {
        try {
            stopScreenShare();
            screenCapturer = new ScreenCapturer();
            List<DesktopSource> sources = screenCapturer.getDesktopSources();
            if (sources.isEmpty()) return;
            screenCapturer.selectSource(sources.get(0));

            DesktopCaptureCallback callback = (result, videoFrame) -> {
                if (result != DesktopCapturer.Result.SUCCESS || videoFrame == null) return;
                BufferedImage img = videoFrameToImage(videoFrame);
                if (img != null) frameConsumer.accept(selfUserId, img);
            };
            screenCapturer.start(callback);

            screenCaptureThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    screenCapturer.captureFrame();
                    try { Thread.sleep(16); } catch (InterruptedException e) { break; }
                }
            }, "screen-capture");
            screenCaptureThread.setDaemon(true);
            screenCaptureThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopScreenShare() {
        if (screenCaptureThread != null) { screenCaptureThread.interrupt(); screenCaptureThread = null; }
        if (screenCapturer != null) { screenCapturer.dispose(); screenCapturer = null; }
        if (localVideoTrack != null) { localVideoTrack.dispose(); localVideoTrack = null; }
    }

    private static BufferedImage videoFrameToImage(VideoFrame frame) {
        try {
            frame.retain();
            I420Buffer i420 = frame.buffer.toI420();
            int width  = i420.getWidth();
            int height = i420.getHeight();
            ByteBuffer dataY = i420.getDataY(), dataU = i420.getDataU(), dataV = i420.getDataV();
            int strideY = i420.getStrideY(), strideU = i420.getStrideU(), strideV = i420.getStrideV();
            byte[] yP = new byte[strideY * height];
            byte[] uP = new byte[strideU * ((height + 1) / 2)];
            byte[] vP = new byte[strideV * ((height + 1) / 2)];
            dataY.get(yP); dataU.get(uP); dataV.get(vP);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] raster = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int Y = yP[y * strideY + x] & 0xFF;
                    int U = uP[(y >> 1) * strideU + (x >> 1)] & 0xFF;
                    int V = vP[(y >> 1) * strideV + (x >> 1)] & 0xFF;
                    int c = Y - 16, d = U - 128, e = V - 128;
                    int r = Math.max(0, Math.min(255, (298*c + 409*e + 128) >> 8));
                    int g = Math.max(0, Math.min(255, (298*c - 100*d - 208*e + 128) >> 8));
                    int b = Math.max(0, Math.min(255, (298*c + 516*d + 128) >> 8));
                    raster[y * width + x] = (r << 16) | (g << 8) | b;
                }
            }
            i420.release();
            return image;
        } catch (Exception e) {
            return null;
        } finally {
            frame.release();
        }
    }

    public void createPeerConnection(String remoteUserId, boolean isInitiator) {
        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer stun = new RTCIceServer();
        stun.urls.add("stun:stun.l.google.com:19302");
        config.iceServers.add(stun);

        RTCPeerConnection pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                signaling.sendIceCandidate(channelId, remoteUserId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex);
            }

            @Override
            public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = receiver.getTrack();
                if ("video".equalsIgnoreCase(track.getKind())) {
                    VideoTrack remoteVideo = (VideoTrack) track;
                    remoteVideo.addSink(new WebRtcVideoSink(remoteUserId, frameConsumer));
                }
            }
        });

        if (localAudioTrack != null) {
            pc.addTrack(localAudioTrack, List.of("stream_" + selfUserId));
        }
        if (localVideoTrack != null) {
            pc.addTrack(localVideoTrack, List.of("stream_" + selfUserId));
        }

        peers.put(remoteUserId, pc);

        if (isInitiator) {
            pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            signaling.sendOffer(channelId, remoteUserId, description.sdp);
                        }
                        @Override
                        public void onFailure(String error) {
                            System.err.println("setLocalDescription failed: " + error);
                        }
                    });
                }
                @Override
                public void onFailure(String error) {
                    System.err.println("createOffer failed: " + error);
                }
            });
        }
    }

    public void handleOffer(String remoteUserId, String sdp) {
        RTCPeerConnection pc = peers.get(remoteUserId);
        if (pc == null) {
            createPeerConnection(remoteUserId, false);
            pc = peers.get(remoteUserId);
        }
        if (pc == null) return;

        final RTCPeerConnection finalPc = pc;
        finalPc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, sdp), new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                finalPc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription description) {
                        finalPc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                signaling.sendAnswer(channelId, remoteUserId, description.sdp);
                            }
                            @Override
                            public void onFailure(String error) { }
                        });
                    }
                    @Override
                    public void onFailure(String error) { }
                });
            }
            @Override
            public void onFailure(String error) { }
        });
    }

    public void handleAnswer(String remoteUserId, String sdp) {
        RTCPeerConnection pc = peers.get(remoteUserId);
        if (pc != null) {
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp), new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { }
                @Override public void onFailure(String error) { }
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
            pc.close();
        }
        peers.clear();
        stopScreenShare();
        audioSource = null;
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (factory != null) { factory.dispose(); factory = null; }
    }
}
