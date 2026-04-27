package model;

import java.util.UUID;

public record ServerInfo(
        UUID id,
        String name,
        UUID ownerId,
        String iconUrl,
        long storageUsedBytes,
        long storageLimitBytes,
        String myRole   // "OWNER" | "ADMINISTRATOR" | "VISITOR"
) {}
