package org.nvias.trashtalk.message;

import org.nvias.trashtalk.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.author
        LEFT JOIN FETCH m.replyTo
        WHERE m.channel.id = :channelId
          AND m.deletedAt IS NULL
        ORDER BY m.createdAt DESC
        """)
    List<Message> findLatest(UUID channelId, Pageable pageable);

    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.author
        LEFT JOIN FETCH m.replyTo
        WHERE m.channel.id = :channelId
          AND m.deletedAt IS NULL
          AND m.createdAt < :before
        ORDER BY m.createdAt DESC
        """)
    List<Message> findBefore(UUID channelId, Instant before, Pageable pageable);
}
