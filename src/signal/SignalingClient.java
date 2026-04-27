package signal;

import auth.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.*;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * WebSocket klient pro /ws/signal endpoint.
 * Posílá JWT v Authorization headeru při handshake.
 * Automaticky se znovu připojuje po výpadku.
 */
public class SignalingClient {

    public interface MessageListener {
        void onChatMessage(WsEnvelope.MessagePayload msg);
        void onChatEdit(String channelId, String messageId, String content, String editedAt);
        void onChatDelete(String channelId, String messageId);
        void onTyping(String channelId, String userId, String username);
        void onError(int code, String reason);
        void onConnected();
        void onDisconnected();

        default void onVoiceJoined(String channelId, String userId, String username,
                                   String mediaSessionId, java.util.List<WsEnvelope.ParticipantInfo> participants) {}
        default void onVoiceLeft(String channelId, String userId) {}
        default void onVoiceMuted(String channelId, String userId, boolean muted) {}
    }

    private final String wsUrl;         // wss://host:25565/ws/signal
    private final boolean trustAllCerts;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    private volatile WsImpl ws;
    private volatile boolean intentionallyClosed = false;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private final Map<String, Consumer<WsEnvelope>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    public SignalingClient(String baseUrl, boolean trustAllCerts) {
        // baseUrl je https://... nebo wss://..., převedeme na wss://
        this.wsUrl = baseUrl.replaceFirst("^https://", "wss://")
                            .replaceFirst("^http://", "ws://") + "/ws/signal";
        this.trustAllCerts = trustAllCerts;
    }

    public void addListener(MessageListener l) { listeners.add(l); }
    public void removeListener(MessageListener l) { listeners.remove(l); }

    public void connect() {
        intentionallyClosed = false;
        doConnect();
    }

    public void disconnect() {
        intentionallyClosed = true;
        if (ws != null) ws.close();
    }

    public boolean isConnected() {
        return ws != null && ws.isOpen();
    }

    // ---- Protocol helpers ----

    public void subscribe(String channelId) {
        send(Map.of("type", "subscribe", "channelId", channelId));
    }

    public void unsubscribe(String channelId) {
        send(Map.of("type", "unsubscribe", "channelId", channelId));
    }

    public void sendChatMessage(String channelId, String content, String replyToId,
                                java.util.List<String> attachmentIds) {
        var m = new HashMap<String, Object>();
        m.put("type", "chat.send");
        m.put("channelId", channelId);
        m.put("content", content);
        if (replyToId != null) m.put("replyToId", replyToId);
        if (attachmentIds != null && !attachmentIds.isEmpty()) m.put("attachmentIds", attachmentIds);
        send(m);
    }

    public void sendEdit(String messageId, String content) {
        send(Map.of("type", "chat.edit", "messageId", messageId, "content", content));
    }

    public void sendDelete(String messageId) {
        send(Map.of("type", "chat.delete", "messageId", messageId));
    }

    public void sendTyping(String channelId) {
        send(Map.of("type", "typing", "channelId", channelId));
    }

    public void sendVoiceJoin(String channelId) {
        send(Map.of("type", "voice.join", "channelId", channelId));
    }

    public void sendVoiceLeave(String channelId) {
        send(Map.of("type", "voice.leave", "channelId", channelId));
    }

    public void sendVoiceMute(String channelId, boolean muted) {
        var m = new HashMap<String, Object>();
        m.put("type", "voice.mute");
        m.put("channelId", channelId);
        m.put("muted", muted);
        send(m);
    }

    // ---- Internals ----

    private void send(Object payload) {
        if (!isConnected()) return;
        try {
            ws.send(json.writeValueAsString(payload));
        } catch (Exception e) {
            // silently drop — user will see message not appear
        }
    }

    private void doConnect() {
        try {
            String token = Session.get().getAccessToken();
            URI uri = new URI(wsUrl + "?token=" + token); // fallback — query param

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + token);

            ws = new WsImpl(uri, headers);

            if (trustAllCerts && wsUrl.startsWith("wss://")) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new java.security.SecureRandom());
                ws.setSocketFactory(ctx.getSocketFactory());
            }

            ws.connect();
        } catch (Exception e) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (intentionallyClosed || reconnecting.getAndSet(true)) return;
        scheduler.schedule(() -> {
            reconnecting.set(false);
            if (!intentionallyClosed) doConnect();
        }, 3, TimeUnit.SECONDS);
    }

    private void dispatch(WsEnvelope env) {
        if (env == null || env.type == null) return;
        switch (env.type) {
            case "chat.message" -> {
                if (env.message != null)
                    listeners.forEach(l -> l.onChatMessage(env.message));
            }
            case "chat.edit" -> {
                String cid = env.channelId, mid = env.messageId;
                String ct = env.content, ea = env.editedAt;
                listeners.forEach(l -> l.onChatEdit(cid, mid, ct, ea));
            }
            case "chat.delete" -> {
                String cid = env.channelId, mid = env.messageId;
                listeners.forEach(l -> l.onChatDelete(cid, mid));
            }
            case "typing" -> {
                String cid = env.channelId, uid = env.userId, un = env.username;
                listeners.forEach(l -> l.onTyping(cid, uid, un));
            }
            case "error" -> {
                int code = env.code != null ? env.code : 0;
                String r = env.reason;
                listeners.forEach(l -> l.onError(code, r));
            }
            case "voice.joined" -> {
                String cid = env.channelId, uid = env.userId, un = env.username;
                String msid = env.mediaSessionId;
                java.util.List<WsEnvelope.ParticipantInfo> parts = env.participants != null ? env.participants : java.util.List.of();
                listeners.forEach(l -> l.onVoiceJoined(cid, uid, un, msid, parts));
            }
            case "voice.left" -> {
                String cid = env.channelId, uid = env.userId;
                listeners.forEach(l -> l.onVoiceLeft(cid, uid));
            }
            case "voice.muted" -> {
                String cid = env.channelId, uid = env.userId;
                boolean m = env.muted != null && env.muted;
                listeners.forEach(l -> l.onVoiceMuted(cid, uid, m));
            }
            case "pong" -> {} // ignore
        }
    }

    private class WsImpl extends WebSocketClient {
        WsImpl(URI uri, Map<String, String> headers) {
            super(uri);
            headers.forEach(this::addHeader);
        }

        @Override public void onOpen(ServerHandshake h) {
            listeners.forEach(MessageListener::onConnected);
        }

        @Override public void onMessage(String message) {
            try {
                dispatch(json.readValue(message, WsEnvelope.class));
            } catch (Exception ignored) {}
        }

        @Override public void onClose(int code, String reason, boolean remote) {
            listeners.forEach(MessageListener::onDisconnected);
            if (remote && !intentionallyClosed) scheduleReconnect();
        }

        @Override public void onError(Exception ex) {
            if (!intentionallyClosed) scheduleReconnect();
        }
    }
}
