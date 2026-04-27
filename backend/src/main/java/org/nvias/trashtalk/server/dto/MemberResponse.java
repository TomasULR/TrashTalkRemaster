package org.nvias.trashtalk.server.dto;

import org.nvias.trashtalk.domain.ServerMember;
import org.nvias.trashtalk.domain.ServerRole;

import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        ServerRole role,
        String nickname,
        Instant joinedAt
) {
    public static MemberResponse from(ServerMember m) {
        return new MemberResponse(
                m.getUser().getId(),
                m.getUser().getUsername(),
                m.getUser().getDisplayName(),
                m.getUser().getAvatarUrl(),
                m.getRole(),
                m.getNickname(),
                m.getJoinedAt()
        );
    }
}
