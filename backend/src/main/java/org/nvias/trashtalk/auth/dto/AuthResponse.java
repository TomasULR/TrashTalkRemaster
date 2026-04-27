package org.nvias.trashtalk.auth.dto;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UUID userId,
        String username,
        String displayName,
        String avatarUrl
) {}
