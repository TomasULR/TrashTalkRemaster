package org.nvias.trashtalk.channel.dto;

import org.nvias.trashtalk.domain.Channel;
import org.nvias.trashtalk.domain.ChannelType;

import java.util.Map;
import java.util.UUID;

public record ChannelResponse(
        UUID id,
        UUID serverId,
        String name,
        ChannelType type,
        int position,
        String topic,
        Integer voiceBitrateKbps,
        Map<String, Map<String, Boolean>> permissionsJson
) {
    public static ChannelResponse from(Channel c) {
        return new ChannelResponse(
                c.getId(), c.getServer().getId(), c.getName(), c.getType(),
                c.getPosition(), c.getTopic(), c.getVoiceBitrateKbps(),
                c.getPermissionsJson()
        );
    }
}
