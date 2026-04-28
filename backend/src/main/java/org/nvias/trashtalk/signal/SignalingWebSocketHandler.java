package org.nvias.trashtalk.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.nvias.trashtalk.auth.UserRepository;
import org.nvias.trashtalk.voice.VoiceService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SignalingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingWebSocketHandler.class);

    // channelId → sessions subscribed to that channel
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketSession>> channelSubs
            = new ConcurrentHashMap<>();

    // sessionId → userId
    private final ConcurrentHashMap<String, UUID> sessionUsers = new ConcurrentHashMap<>();

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ChatOperationsService ops;
    private final VoiceService          voiceService;
    private final UserRepository        userRepo;

    public SignalingWebSocketHandler(ChatOperationsService ops, VoiceService voiceService,
                                     UserRepository userRepo) {
        this.ops          = ops;
        this.voiceService = voiceService;
        this.userRepo     = userRepo;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = extractUserId(session);
        if (userId == null) { closeQuietly(session); return; }
        sessionUsers.put(session.getId(), userId);
        log.debug("WS connected: session={} user={}", session.getId(), userId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionUsers.remove(session.getId());
        channelSubs.values().forEach(set -> set.remove(session));
        log.debug("WS disconnected: session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage raw) {
        UUID userId = sessionUsers.get(session.getId());
        if (userId == null) { closeQuietly(session); return; }

        WsMessage msg;
        try {
            msg = json.readValue(raw.getPayload(), WsMessage.class);
        } catch (Exception e) {
            sendError(session, 400, "Neplatný JSON"); return;
        }
        if (msg.type == null) { sendError(session, 400, "Chybí type"); return; }

        switch (msg.type) {
            case "ping"        -> send(session, pong());
            case "subscribe"   -> doSubscribe(session, userId, msg);
            case "unsubscribe" -> doUnsubscribe(session, msg);
            case "chat.send"   -> doChatSend(session, userId, msg);
            case "chat.edit"   -> doChatEdit(session, userId, msg);
            case "chat.delete" -> doChatDelete(session, userId, msg);
            case "typing"      -> doTyping(session, userId, msg);
            case "voice.join"  -> doVoiceJoin(session, userId, msg);
            case "voice.leave" -> doVoiceLeave(session, userId, msg);
            case "voice.mute"  -> doVoiceMute(session, userId, msg);
            case "webrtc.sdp.offer"     -> doSdpOffer(session, userId, msg);
            case "webrtc.sdp.answer"    -> doSdpAnswer(session, userId, msg);
            case "webrtc.ice.candidate" -> doIceCandidate(session, userId, msg);
            default            -> sendError(session, 400, "Neznámý typ: " + msg.type);
        }
    }

    // ---- Handlers (thin — delegate DB work to ChatOperationsService) ----

    private void doSubscribe(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null) { sendError(session, 400, "channelId chybí"); return; }
        var result = ops.checkSubscribePermission(userId, msg.channelId);
        if (result.isError()) { sendError(session, result.errorCode(), result.errorReason()); return; }
        channelSubs.computeIfAbsent(result.channelId(), k -> new CopyOnWriteArraySet<>()).add(session);
    }

    private void doUnsubscribe(WebSocketSession session, WsMessage msg) {
        if (msg.channelId == null) return;
        try {
            UUID cid = UUID.fromString(msg.channelId);
            CopyOnWriteArraySet<WebSocketSession> subs = channelSubs.get(cid);
            if (subs != null) subs.remove(session);
        } catch (Exception ignored) {}
    }

    private void doChatSend(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null || msg.content == null || msg.content.isBlank()) {
            sendError(session, 400, "channelId a content jsou povinné"); return;
        }
        var result = ops.sendMessage(userId, msg.channelId, msg.content, msg.replyToId, msg.attachmentIds);
        if (result.isError()) { sendError(session, result.errorCode(), result.errorReason()); return; }
        broadcast(msg.channelId, result.broadcast());
    }

    private void doChatEdit(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.messageId == null || msg.content == null || msg.content.isBlank()) {
            sendError(session, 400, "messageId a content jsou povinné"); return;
        }
        var result = ops.editMessage(userId, msg.messageId, msg.content);
        if (result.isError()) { sendError(session, result.errorCode(), result.errorReason()); return; }
        broadcastByChannelStr(result.broadcast().channelId, result.broadcast());
    }

    private void doChatDelete(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.messageId == null) { sendError(session, 400, "messageId chybí"); return; }
        var result = ops.deleteMessage(userId, msg.messageId);
        if (result.isError()) { sendError(session, result.errorCode(), result.errorReason()); return; }
        broadcastByChannelStr(result.broadcast().channelId, result.broadcast());
    }

    private void doTyping(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null) return;
        UUID channelId;
        try { channelId = UUID.fromString(msg.channelId); } catch (Exception e) { return; }

        String username = ops.resolveUsername(userId);

        WsMessage broadcast = new WsMessage();
        broadcast.type      = "typing";
        broadcast.channelId = msg.channelId;
        broadcast.userId    = userId.toString();
        broadcast.username  = username;

        CopyOnWriteArraySet<WebSocketSession> subs =
                channelSubs.getOrDefault(channelId, new CopyOnWriteArraySet<>());
        subs.stream()
            .filter(s -> !s.getId().equals(session.getId()))
            .forEach(s -> send(s, broadcast));
    }

    // ---- Voice handlers ----

    private void doVoiceJoin(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null) { sendError(session, 400, "channelId chybí"); return; }
        UUID channelId;
        try { channelId = UUID.fromString(msg.channelId); } catch (Exception e) { sendError(session, 400, "Neplatné channelId"); return; }

        VoiceService.JoinResult result;
        try {
            result = voiceService.joinChannel(channelId, userId);
        } catch (Exception e) {
            sendError(session, 400, e.getMessage()); return;
        }

        String username = userRepo.findById(userId).map(u -> u.getUsername()).orElse("?");

        // odpověď joinujícímu uživateli
        WsMessage ack = new WsMessage();
        ack.type            = "voice.joined";
        ack.channelId       = msg.channelId;
        ack.userId          = userId.toString();
        ack.username        = username;
        ack.mediaSessionId  = result.mediaSessionId();
        ack.participants    = result.participants().stream()
                .map(p -> new WsMessage.ParticipantInfo(p.userId(), p.username(), p.muted()))
                .toList();
        send(session, ack);

        // broadcast ostatním v kanálu
        WsMessage broadcast = new WsMessage();
        broadcast.type           = "voice.joined";
        broadcast.channelId      = msg.channelId;
        broadcast.userId         = userId.toString();
        broadcast.username       = username;
        broadcast.mediaSessionId = result.mediaSessionId();
        broadcast.participants   = ack.participants;

        CopyOnWriteArraySet<WebSocketSession> subs =
                channelSubs.getOrDefault(channelId, new CopyOnWriteArraySet<>());
        subs.stream()
            .filter(s -> !s.getId().equals(session.getId()))
            .forEach(s -> send(s, broadcast));
    }

    private void doVoiceLeave(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null) return;
        UUID channelId;
        try { channelId = UUID.fromString(msg.channelId); } catch (Exception e) { return; }

        voiceService.leaveChannel(channelId, userId);

        WsMessage broadcast = new WsMessage();
        broadcast.type      = "voice.left";
        broadcast.channelId = msg.channelId;
        broadcast.userId    = userId.toString();

        CopyOnWriteArraySet<WebSocketSession> subs =
                channelSubs.getOrDefault(channelId, new CopyOnWriteArraySet<>());
        subs.forEach(s -> send(s, broadcast));
    }

    private void doVoiceMute(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null) return;
        UUID channelId;
        try { channelId = UUID.fromString(msg.channelId); } catch (Exception e) { return; }

        WsMessage broadcast = new WsMessage();
        broadcast.type      = "voice.muted";
        broadcast.channelId = msg.channelId;
        broadcast.userId    = userId.toString();
        broadcast.muted     = msg.muted != null && msg.muted;

        CopyOnWriteArraySet<WebSocketSession> subs =
                channelSubs.getOrDefault(channelId, new CopyOnWriteArraySet<>());
        subs.forEach(s -> send(s, broadcast));
    }

    // ---- WebRTC Handlers ----

    private void doSdpOffer(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null || msg.sdpOffer == null || msg.targetUserId == null) return;
        WsMessage outgoing = new WsMessage();
        outgoing.type = "webrtc.sdp.offer";
        outgoing.channelId = msg.channelId;
        outgoing.userId = userId.toString();
        outgoing.sdpOffer = msg.sdpOffer;
        broadcastToUserInChannel(msg.channelId, msg.targetUserId, outgoing);
    }

    private void doSdpAnswer(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null || msg.sdpAnswer == null || msg.targetUserId == null) return;
        WsMessage outgoing = new WsMessage();
        outgoing.type = "webrtc.sdp.answer";
        outgoing.channelId = msg.channelId;
        outgoing.userId = userId.toString();
        outgoing.sdpAnswer = msg.sdpAnswer;
        broadcastToUserInChannel(msg.channelId, msg.targetUserId, outgoing);
    }

    private void doIceCandidate(WebSocketSession session, UUID userId, WsMessage msg) {
        if (msg.channelId == null || msg.iceCandidate == null || msg.targetUserId == null) return;
        WsMessage outgoing = new WsMessage();
        outgoing.type = "webrtc.ice.candidate";
        outgoing.channelId = msg.channelId;
        outgoing.userId = userId.toString();
        outgoing.iceCandidate = msg.iceCandidate;
        outgoing.sdpMid = msg.sdpMid;
        outgoing.sdpMLineIndex = msg.sdpMLineIndex;
        broadcastToUserInChannel(msg.channelId, msg.targetUserId, outgoing);
    }

    private void broadcastToUserInChannel(String channelIdStr, String targetUserIdStr, WsMessage msg) {
        try {
            UUID channelId = UUID.fromString(channelIdStr);
            CopyOnWriteArraySet<WebSocketSession> subs = channelSubs.get(channelId);
            if (subs == null) return;

            subs.forEach(s -> {
                UUID subUserId = sessionUsers.get(s.getId());
                if (subUserId != null && subUserId.toString().equals(targetUserIdStr)) {
                    send(s, msg);
                }
            });
        } catch (Exception ignored) {}
    }

    // ---- Broadcast helpers ----

    private void broadcast(String channelIdStr, WsMessage msg) {
        try {
            broadcastByChannelStr(channelIdStr, msg);
        } catch (Exception ignored) {}
    }

    private void broadcastByChannelStr(String channelIdStr, WsMessage msg) {
        try {
            UUID channelId = UUID.fromString(channelIdStr);
            CopyOnWriteArraySet<WebSocketSession> subs =
                    channelSubs.getOrDefault(channelId, new CopyOnWriteArraySet<>());
            subs.forEach(s -> send(s, msg));
        } catch (Exception ignored) {}
    }

    private void send(WebSocketSession session, WsMessage msg) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(msg)));
        } catch (IOException e) {
            log.warn("WS send failed: session={} err={}", session.getId(), e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, int code, String reason) {
        WsMessage err = new WsMessage();
        err.type   = "error";
        err.code   = code;
        err.reason = reason;
        send(session, err);
    }

    private WsMessage pong() {
        WsMessage p = new WsMessage(); p.type = "pong"; return p;
    }

    private static UUID extractUserId(WebSocketSession session) {
        Object val = session.getAttributes().get(HandshakeAuthInterceptor.ATTR_USER_ID);
        if (!(val instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private void closeQuietly(WebSocketSession session) {
        try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (IOException ignored) {}
    }
}
