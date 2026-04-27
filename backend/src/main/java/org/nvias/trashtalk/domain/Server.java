package org.nvias.trashtalk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "servers")
@Getter @Setter @NoArgsConstructor
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "icon_url")
    private String iconUrl;

    @Column(name = "storage_used_bytes", nullable = false)
    private long storageUsedBytes = 0;

    @Column(name = "storage_limit_bytes", nullable = false)
    private long storageLimitBytes = 1_073_741_824L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Server(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }

    public long storageRemainingBytes() {
        return storageLimitBytes - storageUsedBytes;
    }
}
