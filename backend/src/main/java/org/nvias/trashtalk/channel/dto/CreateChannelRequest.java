package org.nvias.trashtalk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.nvias.trashtalk.domain.ChannelType;

public record CreateChannelRequest(
        @NotBlank @Size(min = 1, max = 100) String name,
        @NotNull ChannelType type,
        String topic,
        Integer voiceBitrateKbps
) {}
