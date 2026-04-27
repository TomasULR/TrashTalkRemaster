package org.nvias.trashtalk.server;

import org.nvias.trashtalk.domain.Server;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ServerRepository extends JpaRepository<Server, UUID> {

    @Query("""
        SELECT s FROM Server s
        JOIN ServerMember sm ON sm.server = s
        WHERE sm.user.id = :userId
        ORDER BY s.createdAt ASC
        """)
    List<Server> findAllByMemberUserId(UUID userId);
}
