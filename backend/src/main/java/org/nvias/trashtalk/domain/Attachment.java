package org.nvias.trashtalk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attachments")
@Getter @Setter @NoArgsConstructor
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id", nullable = false)
    private User uploader;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String filename;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "storage_key", nullable = false, columnDefinition = "TEXT")
    private String storageKey;

    @Column(name = "sha256_hex", nullable = false, length = 64)
    private String sha256Hex;

    @Column(name = "upload_complete", nullable = false)
    private boolean uploadComplete = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Attachment(Server server, User uploader, String filename, long sizeBytes,
                      String mimeType, String storageKey, String sha256Hex) {
        this.server     = server;
        this.uploader   = uploader;
        this.filename   = filename;
        this.sizeBytes  = sizeBytes;
        this.mimeType   = mimeType;
        this.storageKey = storageKey;
        this.sha256Hex  = sha256Hex;
    }
}
