package voice;

import media.AudioCapture;
import media.AudioPlayback;
import media.OpusCodec;
import net.MediaTransport;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Orchestruje hlasovou session: capture → encode → send | receive → decode → playback.
 */
public class VoiceClient {

    public interface VoiceListener {
        void onSpeaking(String userId, boolean speaking);
        void onError(String message);
    }

    private static final int RMS_SPEAKING_THRESHOLD = 500;

    private final String baseWsUrl;
    private final boolean trustAllCerts;
    private final VoiceListener listener;

    private AudioCapture    capture;
    private AudioPlayback   playback;
    private OpusCodec       codec;
    private MediaTransport  transport;

    private volatile boolean muted = false;

    public VoiceClient(String baseWsUrl, boolean trustAllCerts, VoiceListener listener) {
        this.baseWsUrl     = baseWsUrl;
        this.trustAllCerts = trustAllCerts;
        this.listener      = listener;
    }

    public void join(String mediaSessionId) {
        try {
            codec    = new OpusCodec();
            playback = new AudioPlayback();
            playback.start();

            transport = new MediaTransport(baseWsUrl, mediaSessionId, trustAllCerts, this::onAudioFrame);
            transport.addHeader("Authorization", "Bearer " + auth.Session.get().getAccessToken());
            transport.connect();

            capture = new AudioCapture(this::onCapturedFrame);
            capture.start();
        } catch (Exception e) {
            listener.onError("Voice init selhal: " + e.getMessage());
            leave();
        }
    }

    public void leave() {
        if (capture   != null) { capture.stop();          capture   = null; }
        if (transport != null) { transport.closeQuietly(); transport = null; }
        if (playback  != null) { playback.stop();          playback  = null; }
        if (codec     != null) { try { codec.close(); } catch (Exception ignored) {} codec = null; }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (capture != null) capture.setMuted(muted);
    }

    public boolean isMuted() { return muted; }

    // ---- audio pipeline ----

    private void onCapturedFrame(short[] pcm) {
        if (muted || codec == null || transport == null) return;

        // speaking detection (RMS)
        if (listener != null) {
            long sumSq = 0;
            for (short s : pcm) sumSq += (long) s * s;
            int rms = (int) Math.sqrt((double) sumSq / pcm.length);
            listener.onSpeaking("self", rms > RMS_SPEAKING_THRESHOLD);
        }

        byte[] opus = codec.encode(pcm);
        if (opus != null) transport.sendAudio(opus);
    }

    private void onAudioFrame(MediaTransport.AudioFrame frame) {
        if (codec == null || playback == null) return;

        String senderId = uuidBytesToString(frame.senderUuidBytes());
        short[] pcm = codec.decode(senderId, frame.opusData());
        if (pcm == null) return;

        // speaking detection for remote
        if (listener != null) {
            long sumSq = 0;
            for (short s : pcm) sumSq += (long) s * s;
            int rms = (int) Math.sqrt((double) sumSq / pcm.length);
            listener.onSpeaking(senderId, rms > RMS_SPEAKING_THRESHOLD);
        }

        playback.play(pcm);
    }

    private static String uuidBytesToString(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return "unknown";
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long high = buf.getLong();
        long low  = buf.getLong();
        return new UUID(high, low).toString();
    }
}
