package net;

import auth.Session;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Binary WebSocket klient pro /ws/media/{sessionId}.
 *
 * Odesílaný frame:   [2B seqNum big-endian][N B opus packet]
 * Přijímaný frame:   [16B sender UUID bytes][2B seqNum][N B opus packet]
 */
public class MediaTransport extends WebSocketClient {

    public record AudioFrame(byte[] senderUuidBytes, short seqNum, byte[] opusData) {}

    private final Consumer<AudioFrame> onFrame;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private short seqNum = 0;

    public MediaTransport(String baseWsUrl, String mediaSessionId, boolean trustAllCerts,
                          Consumer<AudioFrame> onFrame) throws Exception {
        super(buildUri(baseWsUrl, mediaSessionId), buildHeaders());
        this.onFrame = onFrame;
        if (trustAllCerts) applySslBypass();
    }

    public void sendAudio(byte[] opusPacket) {
        if (!isOpen() || opusPacket == null) return;
        short seq = seqNum++;
        ByteBuffer buf = ByteBuffer.allocate(2 + opusPacket.length);
        buf.putShort(seq);
        buf.put(opusPacket);
        send(buf.array());
    }

    @Override public void onOpen(ServerHandshake h) {}

    @Override public void onMessage(String message) {}

    @Override
    public void onMessage(ByteBuffer bytes) {
        if (bytes.remaining() < 19) return; // 16B UUID + 2B seq + at least 1B opus

        byte[] uuidBytes = new byte[16];
        bytes.get(uuidBytes);
        short seq = bytes.getShort();
        byte[] opus = new byte[bytes.remaining()];
        bytes.get(opus);

        if (onFrame != null) onFrame.accept(new AudioFrame(uuidBytes, seq, opus));
    }

    @Override public void onClose(int code, String reason, boolean remote) {}

    @Override public void onError(Exception ex) {}

    public void closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            try { close(); } catch (Exception ignored) {}
        }
    }

    // ---- helpers ----

    private static URI buildUri(String baseWsUrl, String mediaSessionId) throws Exception {
        String token = Session.get().getAccessToken();
        String wsBase = baseWsUrl.replaceFirst("^https://", "wss://")
                                 .replaceFirst("^http://", "ws://");
        return new URI(wsBase + "/ws/media/" + mediaSessionId + "?token=" + token);
    }

    private static Map<String, String> buildHeaders() {
        String token = Session.get().getAccessToken();
        Map<String, String> h = new HashMap<>();
        if (token != null) h.put("Authorization", "Bearer " + token);
        return h;
    }

    private void applySslBypass() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        setSocketFactory(ctx.getSocketFactory());
    }
}
