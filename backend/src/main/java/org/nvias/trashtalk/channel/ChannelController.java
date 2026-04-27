package org.nvias.trashtalk.channel;

import jakarta.validation.Valid;
import org.nvias.trashtalk.channel.dto.ChannelResponse;
import org.nvias.trashtalk.channel.dto.CreateChannelRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChannelController {

    private final ChannelService service;

    public ChannelController(ChannelService service) {
        this.service = service;
    }

    @GetMapping("/servers/{serverId}/channels")
    public List<ChannelResponse> list(@PathVariable UUID serverId,
                                       @AuthenticationPrincipal String userId) {
        return service.listChannels(serverId, UUID.fromString(userId));
    }

    @PostMapping("/servers/{serverId}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelResponse create(@PathVariable UUID serverId,
                                   @Valid @RequestBody CreateChannelRequest req,
                                   @AuthenticationPrincipal String userId) {
        return service.createChannel(serverId, req, UUID.fromString(userId));
    }

    @PatchMapping("/channels/{channelId}")
    public ChannelResponse update(@PathVariable UUID channelId,
                                   @Valid @RequestBody CreateChannelRequest req,
                                   @AuthenticationPrincipal String userId) {
        return service.updateChannel(channelId, req, UUID.fromString(userId));
    }

    @DeleteMapping("/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID channelId,
                       @AuthenticationPrincipal String userId) {
        service.deleteChannel(channelId, UUID.fromString(userId));
    }

    @PutMapping("/channels/{channelId}/permissions")
    public ChannelResponse setPermissions(@PathVariable UUID channelId,
                                           @RequestBody Map<String, Map<String, Boolean>> body,
                                           @AuthenticationPrincipal String userId) {
        return service.setChannelPermissions(channelId, body, UUID.fromString(userId));
    }
}
