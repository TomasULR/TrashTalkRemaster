package org.nvias.trashtalk.server;

import jakarta.validation.Valid;
import org.nvias.trashtalk.domain.ServerRole;
import org.nvias.trashtalk.server.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService service;

    public ServerController(ServerService service) {
        this.service = service;
    }

    @GetMapping
    public List<ServerResponse> listMyServers(@AuthenticationPrincipal String userId) {
        return service.listMyServers(UUID.fromString(userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServerResponse create(@Valid @RequestBody CreateServerRequest req,
                                 @AuthenticationPrincipal String userId) {
        return service.createServer(req.name(), UUID.fromString(userId));
    }

    @GetMapping("/{serverId}")
    public ServerResponse get(@PathVariable UUID serverId,
                              @AuthenticationPrincipal String userId) {
        return service.getServer(serverId, UUID.fromString(userId));
    }

    @PatchMapping("/{serverId}")
    public ServerResponse update(@PathVariable UUID serverId,
                                 @RequestBody CreateServerRequest req,
                                 @AuthenticationPrincipal String userId) {
        return service.updateServer(serverId, req.name(), UUID.fromString(userId));
    }

    @DeleteMapping("/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID serverId,
                       @AuthenticationPrincipal String userId) {
        service.deleteServer(serverId, UUID.fromString(userId));
    }

    // ---- Members ----

    @GetMapping("/{serverId}/members")
    public List<MemberResponse> listMembers(@PathVariable UUID serverId,
                                             @AuthenticationPrincipal String userId) {
        return service.listMembers(serverId, UUID.fromString(userId));
    }

    record RoleBody(ServerRole role) {}

    @PatchMapping("/{serverId}/members/{targetUserId}/role")
    public MemberResponse setRole(@PathVariable UUID serverId,
                                   @PathVariable UUID targetUserId,
                                   @RequestBody RoleBody body,
                                   @AuthenticationPrincipal String userId) {
        return service.setMemberRole(serverId, targetUserId, body.role(), UUID.fromString(userId));
    }

    @DeleteMapping("/{serverId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@PathVariable UUID serverId,
                     @PathVariable UUID targetUserId,
                     @AuthenticationPrincipal String userId) {
        service.kickMember(serverId, targetUserId, UUID.fromString(userId));
    }

    @DeleteMapping("/{serverId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID serverId,
                      @AuthenticationPrincipal String userId) {
        service.leaveServer(serverId, UUID.fromString(userId));
    }

    // ---- Invites ----

    record CreateInviteBody(Integer maxUses, Integer expiresInHours) {}

    @PostMapping("/{serverId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse createInvite(@PathVariable UUID serverId,
                                        @RequestBody CreateInviteBody body,
                                        @AuthenticationPrincipal String userId) {
        return service.createInvite(serverId, UUID.fromString(userId),
                body.maxUses(), body.expiresInHours());
    }

    @PostMapping("/join/{code}")
    public ServerResponse joinByInvite(@PathVariable String code,
                                        @AuthenticationPrincipal String userId) {
        return service.joinByInvite(code, UUID.fromString(userId));
    }
}
