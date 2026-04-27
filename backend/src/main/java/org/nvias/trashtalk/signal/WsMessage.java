package org.nvias.trashtalk.signal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Envelope pro všechny WebSocket zprávy (klient → server i server → klient).
 *
 * Typy (pole "type"):
 *   Klient → server:
 *     subscribe        { channelId }
 *     unsubscribe      { channelId }
 *     chat.send        { channelId, content, replyToId?, attachmentIds?: [uuid, ...] }
 *     chat.edit        { messageId, content }
 *     chat.delete      { messageId }
 *     typing           { channelId }
 *     ping             {}
 *     voice.join       { channelId }
 *     voice.leave      { channelId }
 *     voice.mute       { channelId, muted }
 *
 *   Server → klient:
 *     chat.message     { channelId, message: MessagePayload }
 *     chat.edit        { channelId, messageId, content, editedAt }
 *     chat.delete      { channelId, messageId }
 *     typing           { channelId, userId, username }
 *     error            { code, reason }
 *     pong             {}
 *     voice.joined     { channelId, userId, username, mediaSessionId, participants: [ParticipantInfo] }
 *     voice.left       { channelId, userId }
 *     voice.muted      { channelId, userId, muted }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {
    public String type;

    // subscribe / unsubscribe / chat.send / typing
    public String channelId;

    // chat.send / chat.edit
    public String content;

    // chat.send (optional reply)
    public String replyToId;

    // chat.send (optional attachment IDs from /files/upload)
    public List<String> attachmentIds;

    // chat.edit / chat.delete
    public String messageId;

    // server → client: full message payload
    public MessagePayload message;

    // chat.edit broadcast
    public String editedAt;

    // typing broadcast
    public String userId;
    public String username;

    // error
    public Integer code;
    public String reason;

    // voice.join / voice.leave / voice.mute (C→S) a voice.joined / voice.left / voice.muted (S→C)
    public String mediaSessionId;
    public Boolean muted;
    public java.util.List<ParticipantInfo> participants;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParticipantInfo(String userId, String username, boolean muted) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessagePayload(
            String id,
            String channelId,
            String authorId,
            String authorUsername,
            String authorDisplayName,
            String content,
            String replyToId,
            String createdAt,
            String editedAt,
            List<AttachmentInfo> attachments
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttachmentInfo(
            String id,
            String filename,
            long sizeBytes,
            String mimeType
    ) {}
}
