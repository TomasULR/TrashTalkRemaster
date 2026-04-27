package org.nvias.trashtalk.files;

import org.nvias.trashtalk.server.ServerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class StorageQuotaService {

    private final ServerRepository servers;

    public StorageQuotaService(ServerRepository servers) {
        this.servers = servers;
    }

    /**
     * Atomically checks quota and reserves sizeBytes.
     * Throws 413 if the upload would exceed the server's storage limit.
     */
    @Transactional
    public void reserve(UUID serverId, long sizeBytes) {
        var server = servers.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found"));

        if (server.getStorageUsedBytes() + sizeBytes > server.getStorageLimitBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Server storage limit reached (%d / %d bytes used)".formatted(
                            server.getStorageUsedBytes(), server.getStorageLimitBytes()));
        }
        server.setStorageUsedBytes(server.getStorageUsedBytes() + sizeBytes);
        servers.save(server);
    }

    /**
     * Releases previously reserved bytes (called on upload failure or file deletion).
     */
    @Transactional
    public void release(UUID serverId, long sizeBytes) {
        servers.findById(serverId).ifPresent(server -> {
            long updated = Math.max(0, server.getStorageUsedBytes() - sizeBytes);
            server.setStorageUsedBytes(updated);
            servers.save(server);
        });
    }
}
