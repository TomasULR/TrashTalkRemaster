package org.nvias.trashtalk.server;

import org.nvias.trashtalk.domain.ServerMember;
import org.nvias.trashtalk.domain.ServerMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServerMemberRepository extends JpaRepository<ServerMember, ServerMemberId> {

    @Query("SELECT sm FROM ServerMember sm JOIN FETCH sm.user WHERE sm.server.id = :serverId ORDER BY sm.joinedAt")
    List<ServerMember> findAllByServerId(UUID serverId);

    @Query("SELECT sm FROM ServerMember sm WHERE sm.server.id = :serverId AND sm.user.id = :userId")
    Optional<ServerMember> findMember(UUID serverId, UUID userId);

    @Query("SELECT CASE WHEN COUNT(sm) > 0 THEN true ELSE false END FROM ServerMember sm WHERE sm.server.id = :serverId AND sm.user.id = :userId")
    boolean isMember(UUID serverId, UUID userId);
}
