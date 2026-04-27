package message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.ApiClient;
import signal.WsEnvelope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MessageApiService {

    private final ApiClient client;

    public MessageApiService(ApiClient client) {
        this.client = client;
    }

    public List<MessageDto> loadHistory(UUID channelId, String before) throws IOException {
        String path = "/api/channels/" + channelId + "/messages";
        if (before != null) path += "?before=" + before;
        return client.get(path, MessageList.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageDto {
        public String id;
        public String channelId;
        public String authorId;
        public String authorUsername;
        public String authorDisplayName;
        public String authorAvatarUrl;
        public String content;
        public String replyToId;
        public String createdAt;
        public String editedAt;
        public List<WsEnvelope.AttachmentInfo> attachments;

        public String displayName() {
            return (authorDisplayName != null && !authorDisplayName.isBlank())
                    ? authorDisplayName : authorUsername;
        }
    }

    static class MessageList extends ArrayList<MessageDto> {}
}
