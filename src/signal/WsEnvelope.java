package signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** JSON envelope pro zprávy ze serveru. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WsEnvelope {
    public String type;
    public String channelId;
    public String messageId;
    public String content;
    public String editedAt;
    public String userId;
    public String username;
    public Integer code;
    public String reason;
    public MessagePayload message;

    // voice fields
    public String mediaSessionId;
    public Boolean muted;
    public List<ParticipantInfo> participants;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessagePayload {
        public String id;
        public String channelId;
        public String authorId;
        public String authorUsername;
        public String authorDisplayName;
        public String content;
        public String replyToId;
        public String createdAt;
        public String editedAt;
        public List<AttachmentInfo> attachments;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentInfo {
        public String id;
        public String filename;
        public long sizeBytes;
        public String mimeType;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParticipantInfo {
        public String userId;
        public String username;
        public boolean muted;
    }
}
