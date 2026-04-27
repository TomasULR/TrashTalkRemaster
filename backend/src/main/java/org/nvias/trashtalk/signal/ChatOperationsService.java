package org.nvias.trashtalk.signal;

import org.nvias.trashtalk.auth.UserRepository;
import org.nvias.trashtalk.channel.ChannelRepository;
import org.nvias.trashtalk.domain.*;
import org.nvias.trashtalk.files.AttachmentRepository;
import org.nvias.trashtalk.message.MessageRepository;
import org.nvias.trashtalk.server.PermissionEvaluator;
import org.nvias.trashtalk.server.ServerMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transakční vrstva pro WebSocket chat operace.
 * Všechny metody jsou @Transactional — lazy loading JPA asociací je bezpečný.
 */
@Service
public class ChatOperationsService {

    private final MessageRepository      messageRepo;
    private final ChannelRepository      channelRepo;
    private final ServerMemberRepository memberRepo;
    private final UserRepository         userRepo;
    private final PermissionEvaluator    perm;
    private final AttachmentRepository   attachmentRepo;

    public ChatOperationsService(MessageRepository messageRepo,
                                 ChannelRepository channelRepo,
                                 ServerMemberRepository memberRepo,
                                 UserRepository userRepo,
                                 PermissionEvaluator perm,
                                 AttachmentRepository attachmentRepo) {
        this.messageRepo    = messageRepo;
        this.channelRepo    = channelRepo;
        this.memberRepo     = memberRepo;
        this.userRepo       = userRepo;
        this.perm           = perm;
        this.attachmentRepo = attachmentRepo;
    }

    public record SendResult(WsMessage broadcast, String errorReason, int errorCode) {
        static SendResult error(int code, String reason) { return new SendResult(null, reason, code); }
        static SendResult ok(WsMessage msg)              { return new SendResult(msg, null, 0); }
        boolean isError() { return errorReason != null; }
    }

    @Transactional
    public SendResult sendMessage(UUID userId, String channelIdStr,
                                  String content, String replyToIdStr,
                                  List<String> attachmentIds) {
        UUID channelId = parseUUID(channelIdStr);
        if (channelId == null) return SendResult.error(400, "channelId neplatné");

        Channel channel = channelRepo.findById(channelId).orElse(null);
        if (channel == null) return SendResult.error(404, "Kanál nenalezen");

        ServerMember member = memberRepo.findMember(channel.getServer().getId(), userId).orElse(null);
        if (member == null || !perm.hasPermission(member.getRole(), channel, PermissionEvaluator.WRITE))
            return SendResult.error(403, "Nemáš právo psát do tohoto kanálu");

        User author = userRepo.findById(userId).orElse(null);
        if (author == null) return SendResult.error(500, "Uživatel nenalezen");

        String trimmed = content.strip();
        if (trimmed.length() > 4000) trimmed = trimmed.substring(0, 4000);

        Message message = new Message(channel, author, trimmed);
        if (replyToIdStr != null) {
            UUID replyId = parseUUID(replyToIdStr);
            if (replyId != null) messageRepo.findById(replyId).ifPresent(message::setReplyTo);
        }
        Message saved = messageRepo.save(message);

        List<WsMessage.AttachmentInfo> attInfos = new ArrayList<>();
        if (attachmentIds != null) {
            for (String attIdStr : attachmentIds) {
                UUID attId = parseUUID(attIdStr);
                if (attId == null) continue;
                attachmentRepo.findById(attId).ifPresent(att -> {
                    if (!att.getUploader().getId().equals(userId)) return;
                    att.setMessage(saved);
                    attachmentRepo.save(att);
                    attInfos.add(new WsMessage.AttachmentInfo(
                            att.getId().toString(), att.getFilename(),
                            att.getSizeBytes(), att.getMimeType()));
                });
            }
        }

        WsMessage broadcast = new WsMessage();
        broadcast.type      = "chat.message";
        broadcast.channelId = channelIdStr;
        broadcast.message   = toPayload(saved, attInfos);
        return SendResult.ok(broadcast);
    }

    @Transactional
    public SendResult editMessage(UUID userId, String messageIdStr, String content) {
        UUID messageId = parseUUID(messageIdStr);
        if (messageId == null) return SendResult.error(400, "messageId neplatné");

        Message message = messageRepo.findById(messageId).orElse(null);
        if (message == null || message.isDeleted()) return SendResult.error(404, "Zpráva nenalezena");
        if (!message.getAuthor().getId().equals(userId))
            return SendResult.error(403, "Nelze editovat cizí zprávu");

        message.setContent(content.strip());
        message.setEditedAt(Instant.now());
        messageRepo.save(message);

        WsMessage broadcast = new WsMessage();
        broadcast.type      = "chat.edit";
        broadcast.channelId = message.getChannel().getId().toString();
        broadcast.messageId = messageIdStr;
        broadcast.content   = message.getContent();
        broadcast.editedAt  = message.getEditedAt().toString();
        return SendResult.ok(broadcast);
    }

    @Transactional
    public SendResult deleteMessage(UUID userId, String messageIdStr) {
        UUID messageId = parseUUID(messageIdStr);
        if (messageId == null) return SendResult.error(400, "messageId neplatné");

        Message message = messageRepo.findById(messageId).orElse(null);
        if (message == null || message.isDeleted()) return SendResult.error(404, "Zpráva nenalezena");

        ServerMember member = memberRepo.findMember(message.getChannel().getServer().getId(), userId).orElse(null);
        boolean isAuthor    = message.getAuthor().getId().equals(userId);
        boolean canModerate = member != null && perm.hasPermission(
                member.getRole(), message.getChannel(), PermissionEvaluator.MANAGE_MESSAGES);

        if (!isAuthor && !canModerate) return SendResult.error(403, "Nelze smazat tuto zprávu");

        message.setDeletedAt(Instant.now());
        messageRepo.save(message);

        WsMessage broadcast = new WsMessage();
        broadcast.type      = "chat.delete";
        broadcast.channelId = message.getChannel().getId().toString();
        broadcast.messageId = messageIdStr;
        return SendResult.ok(broadcast);
    }

    @Transactional(readOnly = true)
    public SubscribeResult checkSubscribePermission(UUID userId, String channelIdStr) {
        UUID channelId = parseUUID(channelIdStr);
        if (channelId == null) return SubscribeResult.error(400, "channelId neplatné");

        Channel channel = channelRepo.findById(channelId).orElse(null);
        if (channel == null) return SubscribeResult.error(404, "Kanál nenalezen");

        ServerMember member = memberRepo.findMember(channel.getServer().getId(), userId).orElse(null);
        if (member == null || !perm.hasPermission(member.getRole(), channel, PermissionEvaluator.READ))
            return SubscribeResult.error(403, "Přístup odepřen");

        return SubscribeResult.ok(channelId);
    }

    @Transactional(readOnly = true)
    public String resolveUsername(UUID userId) {
        return userRepo.findById(userId)
                .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getUsername())
                .orElse("unknown");
    }

    public record SubscribeResult(UUID channelId, String errorReason, int errorCode) {
        static SubscribeResult error(int code, String reason) { return new SubscribeResult(null, reason, code); }
        static SubscribeResult ok(UUID channelId) { return new SubscribeResult(channelId, null, 0); }
        boolean isError() { return errorReason != null; }
    }

    private WsMessage.MessagePayload toPayload(Message m) {
        List<WsMessage.AttachmentInfo> attInfos = attachmentRepo.findByMessageId(m.getId()).stream()
                .filter(Attachment::isUploadComplete)
                .map(a -> new WsMessage.AttachmentInfo(
                        a.getId().toString(), a.getFilename(), a.getSizeBytes(), a.getMimeType()))
                .toList();
        return toPayload(m, attInfos);
    }

    private WsMessage.MessagePayload toPayload(Message m, List<WsMessage.AttachmentInfo> attInfos) {
        return new WsMessage.MessagePayload(
                m.getId().toString(),
                m.getChannel().getId().toString(),
                m.getAuthor().getId().toString(),
                m.getAuthor().getUsername(),
                m.getAuthor().getDisplayName(),
                m.getContent(),
                m.getReplyTo() != null ? m.getReplyTo().getId().toString() : null,
                m.getCreatedAt().toString(),
                m.getEditedAt() != null ? m.getEditedAt().toString() : null,
                attInfos.isEmpty() ? null : attInfos
        );
    }

    private static UUID parseUUID(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
