package org.nvias.trashtalk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "channels")
@Getter @Setter @NoArgsConstructor
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "channel_type")
    private ChannelType type;

    @Column(nullable = false)
    private int position = 0;

    @Column
    private String topic;

    @Column(name = "voice_bitrate_kbps")
    private Integer voiceBitrateKbps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Map<String, Boolean>> permissionsJson = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Channel(Server server, String name, ChannelType type, int position) {
        this.server = server;
        this.name = name;
        this.type = type;
        this.position = position;
    }
}
