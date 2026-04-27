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
 * Binary WebSocket klient pro /ws/video/{sessionId}.
 *
 * Odesílaný frame:   [2B seqNum big-endian][2B width][2B height][N B JPEG]
 * Přijímaný frame:   [16B sender UUID bytes][2B seqNum][2B width][2B height][N B JPEG]
 */
public class VideoTransport extends WebSocketClient {

    public record VideoFrame(byte[] senderUuidBytes, short seqNum, int width, int height, byte[] jpegData) {}

    private final Consumer<VideoFrame> onFrame;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private short seqNum = 0;

    public VideoTransport(String baseWsUrl, String mediaSessionId, boolean trustAllCerts,
                          Consumer<VideoFrame> onFrame) throws Exception {
        super(buildUri(baseWsUrl, mediaSessionId), buildHeaders());
        this.onFrame = onFrame;
        if (trustAllCerts) applySslBypass();
    }

    public void sendVideo(byte[] jpegBytes, int width, int height) {
        if (!isOpen() || jpegBytes == null) return;
        short seq = seqNum++;
        ByteBuffer buf = ByteBuffer.allocate(6 + jpegBytes.length);
        buf.putShort(seq);
        buf.putShort((short) width);
        buf.putShort((short) height);
        buf.put(jpegBytes);
        send(buf.array());
    }

    @Override public void onOpen(ServerHandshake h) {}
    @Override public void onMessage(String message) {}

    @Override
    public void onMessage(ByteBuffer bytes) {
        if (bytes.remaining() < 23) return; // 16B UUID + 2B seq + 2B w + 2B h + ≥1B JPEG

        byte[] uuidBytes = new byte[16];
        bytes.get(uuidBytes);
        short seq    = bytes.getShort();
        int   width  = bytes.getShort() & 0xFFFF;
        int   height = bytes.getShort() & 0xFFFF;
        byte[] jpeg  = new byte[bytes.remaining()];
        bytes.get(jpeg);

        if (onFrame != null) onFrame.accept(new VideoFrame(uuidBytes, seq, width, height, jpeg));
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
        String token  = Session.get().getAccessToken();
        String wsBase = baseWsUrl.replaceFirst("^https://", "wss://")
                                 .replaceFirst("^http://", "ws://");
        return new URI(wsBase + "/ws/video/" + mediaSessionId + "?token=" + token);
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
