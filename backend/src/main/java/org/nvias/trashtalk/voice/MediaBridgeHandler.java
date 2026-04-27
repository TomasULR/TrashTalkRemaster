package org.nvias.trashtalk.voice;

import org.nvias.trashtalk.signal.HandshakeAuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Mini-SFU pro audio přes WebSocket.
 *
 * Klient → Server frame: [2B seqNum big-endian][N B opus]
 * Server → Klient frame: [16B sender UUID bytes][2B seqNum][N B opus]
 *
 * Každá WS session musí mít atribut "mediaSessionId" nastavený interceptorem.
 * Na základě mediaSessionId se vyhledá channelId a session se přidá do skupiny.
 */
@Component
public class MediaBridgeHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaBridgeHandler.class);

    // channelId → aktivní WebSocket sessions v tomto kanálu
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketSession>> channelGroups
            = new ConcurrentHashMap<>();

    // sessionId → (channelId, senderUUID bytes)
    private final ConcurrentHashMap<String, UUID> sessionChannel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> sessionUserBytes = new ConcurrentHashMap<>();

    private final VoiceService voiceService;

    public MediaBridgeHandler(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String mediaSessionId = (String) session.getAttributes().get("mediaSessionId");
        String userIdStr      = (String) session.getAttributes().get(HandshakeAuthInterceptor.ATTR_USER_ID);

        if (mediaSessionId == null || userIdStr == null) {
            closeQuietly(session);
            return;
        }

        UUID channelId = voiceService.resolveChannelForSession(mediaSessionId);
        UUID userId    = voiceService.resolveUserForSession(mediaSessionId);

        if (channelId == null || userId == null) {
            closeQuietly(session);
            return;
        }

        // uložení UUID jako 16 bytů pro přidávání do broadcastu
        ByteBuffer buf = ByteBuffer.wrap(new byte[16]);
        buf.putLong(userId.getMostSignificantBits());
        buf.putLong(userId.getLeastSignificantBits());

        sessionChannel.put(session.getId(), channelId);
        sessionUserBytes.put(session.getId(), buf.array());
        channelGroups.computeIfAbsent(channelId, k -> new CopyOnWriteArraySet<>()).add(session);

        log.debug("Media connected: session={} channel={} user={}", session.getId(), channelId, userId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UUID channelId = sessionChannel.get(session.getId());
        byte[] senderBytes = sessionUserBytes.get(session.getId());
        if (channelId == null || senderBytes == null) return;

        ByteBuffer payload = message.getPayload();
        if (payload.remaining() < 3) return; // minimálně 2B seqNum + 1B opus

        byte[] incoming = payload.array();
        int offset = payload.arrayOffset() + payload.position();
        int len    = payload.remaining();

        // výstupní frame: [16B UUID][2B seqNum][opus data]
        byte[] out = new byte[18 + len];
        System.arraycopy(senderBytes, 0, out, 0, 16);
        System.arraycopy(incoming, offset, out, 16, len);

        BinaryMessage outMsg = new BinaryMessage(out);
        CopyOnWriteArraySet<WebSocketSession> group = channelGroups.get(channelId);
        if (group == null) return;

        for (WebSocketSession peer : group) {
            if (!peer.getId().equals(session.getId()) && peer.isOpen()) {
                try {
                    peer.sendMessage(outMsg);
                } catch (IOException e) {
                    log.warn("Media send failed: peer={}", peer.getId());
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID channelId = sessionChannel.remove(session.getId());
        sessionUserBytes.remove(session.getId());
        if (channelId != null) {
            CopyOnWriteArraySet<WebSocketSession> group = channelGroups.get(channelId);
            if (group != null) {
                group.remove(session);
                if (group.isEmpty()) channelGroups.remove(channelId);
            }
        }
        log.debug("Media disconnected: session={}", session.getId());
    }

    private void closeQuietly(WebSocketSession session) {
        try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (IOException ignored) {}
    }
}
