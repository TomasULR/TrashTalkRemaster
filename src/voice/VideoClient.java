package voice;

import auth.Session;
import media.VideoCapture;
import net.VideoTransport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Orchestruje video session: capture → JPEG encode → send | receive → decode → display.
 * Používá stejné mediaSessionId jako VoiceClient — server routuje audio a video separátně.
 */
public class VideoClient {

    public interface VideoListener {
        void onFrame(String userId, BufferedImage frame);
        void onError(String message);
    }

    private final String        baseWsUrl;
    private final boolean       trustAllCerts;
    private final VideoListener listener;

    private VideoCapture  capture;
    private VideoTransport transport;

    private volatile boolean cameraOn = false;

    public VideoClient(String baseWsUrl, boolean trustAllCerts, VideoListener listener) {
        this.baseWsUrl     = baseWsUrl;
        this.trustAllCerts = trustAllCerts;
        this.listener      = listener;
    }

    /** Připojí se k video bridge. Kamera zůstane vypnutá dokud uživatel nezavolá startCamera(). */
    public void join(String mediaSessionId) {
        try {
            transport = new VideoTransport(baseWsUrl, mediaSessionId, trustAllCerts, this::onRemoteFrame);
            transport.connect();
        } catch (Exception e) {
            if (listener != null) listener.onError("Video transport selhal: " + e.getMessage());
        }
    }

    /** Spustí webcam a začne odesílat video. Při selhání volá listener.onError(). */
    public void startCamera() {
        if (cameraOn || transport == null) return;
        try {
            capture  = new VideoCapture(this::onLocalFrame);
            capture.start();
            cameraOn = true;
        } catch (Exception e) {
            capture = null;
            if (listener != null) listener.onError("Kamera selhala: " + e.getMessage());
        }
    }

    public void stopCamera() {
        cameraOn = false;
        if (capture != null) { capture.stop(); capture = null; }
    }

    public void leave() {
        stopCamera();
        if (transport != null) { transport.closeQuietly(); transport = null; }
    }

    public boolean isCameraOn() { return cameraOn; }

    // ---- pipeline ----

    private void onLocalFrame(byte[] jpegBytes) {
        if (!cameraOn || transport == null) return;
        transport.sendVideo(jpegBytes, VideoCapture.WIDTH, VideoCapture.HEIGHT);
        // zobrazit vlastní náhled lokálně
        if (listener != null) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegBytes));
                String selfId = Session.get().getUserId().toString();
                if (img != null) listener.onFrame(selfId, img);
            } catch (Exception ignored) {}
        }
    }

    private void onRemoteFrame(VideoTransport.VideoFrame frame) {
        if (listener == null) return;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(frame.jpegData()));
            if (img != null) listener.onFrame(uuidBytesToString(frame.senderUuidBytes()), img);
        } catch (Exception ignored) {}
    }

    private static String uuidBytesToString(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return "unknown";
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong()).toString();
    }
}
