package org.nvias.trashtalk.channel;

import org.nvias.trashtalk.domain.Channel;
import org.nvias.trashtalk.domain.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {
    List<Channel> findByServerIdOrderByPositionAsc(UUID serverId);
    List<Channel> findByServerIdAndTypeOrderByPositionAsc(UUID serverId, ChannelType type);
    int countByServerId(UUID serverId);
}
