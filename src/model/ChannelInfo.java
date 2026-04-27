package model;

import java.util.UUID;

public record ChannelInfo(
        UUID id,
        UUID serverId,
        String name,
        String type,        // "TEXT" | "VOICE"
        int position,
        String topic,
        Integer voiceBitrateKbps
) {
    public boolean isText()  { return "TEXT".equals(type); }
    public boolean isVoice() { return "VOICE".equals(type); }
}
