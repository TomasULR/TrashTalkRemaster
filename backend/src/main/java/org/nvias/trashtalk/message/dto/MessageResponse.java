package org.nvias.trashtalk.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.nvias.trashtalk.domain.Attachment;
import org.nvias.trashtalk.domain.Message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID channelId,
        UUID authorId,
        String authorUsername,
        String authorDisplayName,
        String authorAvatarUrl,
        String content,
        UUID replyToId,
        Instant createdAt,
        Instant editedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<AttachmentInfo> attachments
) {
    public record AttachmentInfo(String id, String filename, long sizeBytes, String mimeType) {}

    public static MessageResponse from(Message m, List<Attachment> atts) {
        List<AttachmentInfo> attInfos = atts.stream()
                .map(a -> new AttachmentInfo(a.getId().toString(), a.getFilename(),
                        a.getSizeBytes(), a.getMimeType()))
                .toList();
        return new MessageResponse(
                m.getId(),
                m.getChannel().getId(),
                m.getAuthor().getId(),
                m.getAuthor().getUsername(),
                m.getAuthor().getDisplayName(),
                m.getAuthor().getAvatarUrl(),
                m.getContent(),
                m.getReplyTo() != null ? m.getReplyTo().getId() : null,
                m.getCreatedAt(),
                m.getEditedAt(),
                attInfos.isEmpty() ? null : attInfos
        );
    }
}
