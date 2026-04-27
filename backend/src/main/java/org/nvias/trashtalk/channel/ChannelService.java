package org.nvias.trashtalk.channel;

import org.nvias.trashtalk.channel.dto.ChannelResponse;
import org.nvias.trashtalk.channel.dto.CreateChannelRequest;
import org.nvias.trashtalk.domain.Channel;
import org.nvias.trashtalk.domain.ServerRole;
import org.nvias.trashtalk.server.PermissionEvaluator;
import org.nvias.trashtalk.server.ServerMemberRepository;
import org.nvias.trashtalk.server.ServerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChannelService {

    private final ChannelRepository channelRepo;
    private final ServerRepository serverRepo;
    private final ServerMemberRepository memberRepo;
    private final PermissionEvaluator perm;

    public ChannelService(ChannelRepository channelRepo,
                          ServerRepository serverRepo,
                          ServerMemberRepository memberRepo,
                          PermissionEvaluator perm) {
        this.channelRepo = channelRepo;
        this.serverRepo  = serverRepo;
        this.memberRepo  = memberRepo;
        this.perm        = perm;
    }

    @Transactional(readOnly = true)
    public List<ChannelResponse> listChannels(UUID serverId, UUID userId) {
        requireMember(serverId, userId);
        return channelRepo.findByServerIdOrderByPositionAsc(serverId)
                .stream().map(ChannelResponse::from).toList();
    }

    @Transactional
    public ChannelResponse createChannel(UUID serverId, CreateChannelRequest req, UUID userId) {
        var server = serverRepo.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server nenalezen"));
        var member = requireMember(serverId, userId);
        perm.requirePermission(member.getRole(), null, PermissionEvaluator.MANAGE_CHANNELS);

        int position = channelRepo.countByServerId(serverId);
        Channel channel = new Channel(server, req.name(), req.type(), position);
        channel.setTopic(req.topic());
        if (req.voiceBitrateKbps() != null) channel.setVoiceBitrateKbps(req.voiceBitrateKbps());
        return ChannelResponse.from(channelRepo.save(channel));
    }

    @Transactional
    public ChannelResponse updateChannel(UUID channelId, CreateChannelRequest req, UUID userId) {
        Channel channel = requireChannel(channelId);
        var member = requireMember(channel.getServer().getId(), userId);
        perm.requirePermission(member.getRole(), null, PermissionEvaluator.MANAGE_CHANNELS);

        channel.setName(req.name());
        if (req.topic() != null) channel.setTopic(req.topic());
        if (req.voiceBitrateKbps() != null) channel.setVoiceBitrateKbps(req.voiceBitrateKbps());
        return ChannelResponse.from(channelRepo.save(channel));
    }

    @Transactional
    public void deleteChannel(UUID channelId, UUID userId) {
        Channel channel = requireChannel(channelId);
        var member = requireMember(channel.getServer().getId(), userId);
        perm.requirePermission(member.getRole(), null, PermissionEvaluator.MANAGE_CHANNELS);
        channelRepo.delete(channel);
    }

    @Transactional
    public ChannelResponse setChannelPermissions(UUID channelId,
                                                  Map<String, Map<String, Boolean>> permissions,
                                                  UUID userId) {
        Channel channel = requireChannel(channelId);
        var member = requireMember(channel.getServer().getId(), userId);
        perm.requireRole(member.getRole(), ServerRole.ADMINISTRATOR);
        channel.setPermissionsJson(permissions);
        return ChannelResponse.from(channelRepo.save(channel));
    }

    // ---- helpers ----

    private Channel requireChannel(UUID id) {
        return channelRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kanál nenalezen"));
    }

    private org.nvias.trashtalk.domain.ServerMember requireMember(UUID serverId, UUID userId) {
        return memberRepo.findMember(serverId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Nejsi členem tohoto serveru"));
    }
}
