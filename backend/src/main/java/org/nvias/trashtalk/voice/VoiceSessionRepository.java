package org.nvias.trashtalk.voice;

import org.nvias.trashtalk.domain.VoiceSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoiceSessionRepository extends JpaRepository<VoiceSession, UUID> {

    @Query("SELECT vs FROM VoiceSession vs JOIN FETCH vs.user WHERE vs.channel.id = :channelId AND vs.leftAt IS NULL")
    List<VoiceSession> findActiveByChannelId(@Param("channelId") UUID channelId);

    @Query("SELECT vs FROM VoiceSession vs WHERE vs.channel.id = :channelId AND vs.user.id = :userId AND vs.leftAt IS NULL")
    Optional<VoiceSession> findActiveByChannelIdAndUserId(@Param("channelId") UUID channelId,
                                                          @Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE VoiceSession vs SET vs.leftAt = :now WHERE vs.channel.id = :channelId AND vs.user.id = :userId AND vs.leftAt IS NULL")
    void markLeft(@Param("channelId") UUID channelId, @Param("userId") UUID userId, @Param("now") Instant now);
}
