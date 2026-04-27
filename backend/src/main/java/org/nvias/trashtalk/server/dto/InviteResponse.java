package org.nvias.trashtalk.server.dto;

import org.nvias.trashtalk.domain.Invite;

import java.time.Instant;

public record InviteResponse(
        String code,
        String serverName,
        Integer maxUses,
        int uses,
        Instant expiresAt,
        Instant createdAt
) {
    public static InviteResponse from(Invite i) {
        return new InviteResponse(
                i.getCode(), i.getServer().getName(),
                i.getMaxUses(), i.getUses(),
                i.getExpiresAt(), i.getCreatedAt()
        );
    }
}
