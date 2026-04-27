package org.nvias.trashtalk.server.dto;

import org.nvias.trashtalk.domain.Server;
import org.nvias.trashtalk.domain.ServerRole;

import java.time.Instant;
import java.util.UUID;

public record ServerResponse(
        UUID id,
        String name,
        UUID ownerId,
        String iconUrl,
        long storageUsedBytes,
        long storageLimitBytes,
        Instant createdAt,
        ServerRole myRole       // role přihlášeného uživatele v tomto serveru
) {
    public static ServerResponse from(Server s, ServerRole myRole) {
        return new ServerResponse(
                s.getId(), s.getName(), s.getOwner().getId(),
                s.getIconUrl(), s.getStorageUsedBytes(), s.getStorageLimitBytes(),
                s.getCreatedAt(), myRole
        );
    }
}
