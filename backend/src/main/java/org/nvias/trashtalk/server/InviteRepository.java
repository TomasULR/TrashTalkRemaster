package org.nvias.trashtalk.server;

import org.nvias.trashtalk.domain.Invite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InviteRepository extends JpaRepository<Invite, String> {
    Optional<Invite> findByCode(String code);
}
