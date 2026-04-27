package org.nvias.trashtalk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "invites")
@Getter @Setter @NoArgsConstructor
public class Invite {

    @Id
    @Column(length = 16)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(nullable = false)
    private int uses = 0;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Invite(String code, Server server, User creator, Integer maxUses, Instant expiresAt) {
        this.code = code;
        this.server = server;
        this.creator = creator;
        this.maxUses = maxUses;
        this.expiresAt = expiresAt;
    }

    public boolean isValid() {
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxUses != null && uses >= maxUses) return false;
        return true;
    }
}
