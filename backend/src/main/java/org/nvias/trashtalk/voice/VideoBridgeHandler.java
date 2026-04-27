package org.nvias.trashtalk.voice;

import org.nvias.trashtalk.signal.HandshakeAuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Mini-SFU pro video přes WebSocket (/ws/video/{mediaSessionId}).
 *
 * Klient→Server frame: [2B seqNum][2B width][2B height][N B JPEG]
 * Server→Klient frame: [16B senderUUID][2B seqNum][2B width][2B height][N B JPEG]
 *
 * Používá stejné mediaSessionId jako audio bridge — server routuje samostatně.
 */
@Component
public class VideoBridgeHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoBridgeHandler.class);

    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketSession>> channelGroups
            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID>   sessionChannel   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> sessionUserBytes = new ConcurrentHashMap<>();

    private final VoiceService voiceService;

    public VideoBridgeHandler(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String mediaSessionId = (String) session.getAttributes().get("mediaSessionId");
        String userIdStr      = (String) session.getAttributes().get(HandshakeAuthInterceptor.ATTR_USER_ID);

        if (mediaSessionId == null || userIdStr == null) { closeQuietly(session); return; }

        UUID channelId = voiceService.resolveChannelForSession(mediaSessionId);
        UUID userId    = voiceService.resolveUserForSession(mediaSessionId);

        if (channelId == null || userId == null) { closeQuietly(session); return; }

        ByteBuffer buf = ByteBuffer.wrap(new byte[16]);
        buf.putLong(userId.getMostSignificantBits());
        buf.putLong(userId.getLeastSignificantBits());

        sessionChannel.put(session.getId(), channelId);
        sessionUserBytes.put(session.getId(), buf.array());
        channelGroups.computeIfAbsent(channelId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.debug("Video connected: session={} channel={} user={}", session.getId(), channelId, userId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UUID   channelId   = sessionChannel.get(session.getId());
        byte[] senderBytes = sessionUserBytes.get(session.getId());
        if (channelId == null || senderBytes == null) return;

        ByteBuffer payload = message.getPayload();
        if (payload.remaining() < 7) return; // 2 seq + 2 w + 2 h + ≥1 JPEG

        byte[] incoming = payload.array();
        int offset = payload.arrayOffset() + payload.position();
        int len    = payload.remaining();

        // output frame: [16B senderUUID][seqNum+width+height+jpeg from client]
        byte[] out = new byte[16 + len];
        System.arraycopy(senderBytes, 0, out, 0, 16);
        System.arraycopy(incoming, offset, out, 16, len);

        BinaryMessage outMsg = new BinaryMessage(out);
        CopyOnWriteArraySet<WebSocketSession> group = channelGroups.get(channelId);
        if (group == null) return;

        for (WebSocketSession peer : group) {
            if (!peer.getId().equals(session.getId()) && peer.isOpen()) {
                try { peer.sendMessage(outMsg); }
                catch (IOException e) { log.warn("Video send failed: peer={}", peer.getId()); }
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
        log.debug("Video disconnected: session={}", session.getId());
    }

    private void closeQuietly(WebSocketSession session) {
        try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (IOException ignored) {}
    }
}
