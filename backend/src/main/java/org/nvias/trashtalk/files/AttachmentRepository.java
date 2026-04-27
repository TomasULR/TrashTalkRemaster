package org.nvias.trashtalk.files;

import org.nvias.trashtalk.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByMessageId(UUID messageId);

    @Query("SELECT a FROM Attachment a WHERE a.message.id IN :messageIds AND a.uploadComplete = true")
    List<Attachment> findCompleteByMessageIds(List<UUID> messageIds);

    @Modifying
    @Query("UPDATE Attachment a SET a.uploadComplete = true WHERE a.id = :id")
    void markComplete(UUID id);
}
