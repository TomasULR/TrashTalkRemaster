package org.nvias.trashtalk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "server_members")
@Getter @Setter @NoArgsConstructor
public class ServerMember {

    @EmbeddedId
    private ServerMemberId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("serverId")
    @JoinColumn(name = "server_id")
    private Server server;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false, columnDefinition = "server_role")
    private ServerRole role = ServerRole.VISITOR;

    @Column(name = "nickname", length = 64)
    private String nickname;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt = Instant.now();

    public ServerMember(Server server, User user, ServerRole role) {
        this.id = new ServerMemberId(server.getId(), user.getId());
        this.server = server;
        this.user = user;
        this.role = role;
    }
}
