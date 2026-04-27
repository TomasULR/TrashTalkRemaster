package org.nvias.trashtalk.server;

import org.nvias.trashtalk.auth.UserRepository;
import org.nvias.trashtalk.domain.*;
import org.nvias.trashtalk.server.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ServerService {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RNG = new SecureRandom();

    private final ServerRepository serverRepo;
    private final ServerMemberRepository memberRepo;
    private final InviteRepository inviteRepo;
    private final UserRepository userRepo;
    private final PermissionEvaluator perm;

    public ServerService(ServerRepository serverRepo,
                         ServerMemberRepository memberRepo,
                         InviteRepository inviteRepo,
                         UserRepository userRepo,
                         PermissionEvaluator perm) {
        this.serverRepo = serverRepo;
        this.memberRepo = memberRepo;
        this.inviteRepo = inviteRepo;
        this.userRepo   = userRepo;
        this.perm       = perm;
    }

    @Transactional(readOnly = true)
    public List<ServerResponse> listMyServers(UUID userId) {
        return serverRepo.findAllByMemberUserId(userId).stream()
                .map(s -> {
                    ServerRole role = memberRepo.findMember(s.getId(), userId)
                            .map(ServerMember::getRole).orElse(ServerRole.VISITOR);
                    return ServerResponse.from(s, role);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ServerResponse getServer(UUID serverId, UUID userId) {
        Server server = requireServer(serverId);
        ServerRole role = requireMember(serverId, userId).getRole();
        return ServerResponse.from(server, role);
    }

    @Transactional
    public ServerResponse createServer(String name, UUID ownerId) {
        User owner = requireUser(ownerId);
        Server server = serverRepo.save(new Server(name, owner));
        memberRepo.save(new ServerMember(server, owner, ServerRole.OWNER));
        return ServerResponse.from(server, ServerRole.OWNER);
    }

    @Transactional
    public ServerResponse updateServer(UUID serverId, String newName, UUID requesterId) {
        Server server = requireServer(serverId);
        ServerMember member = requireMember(serverId, requesterId);
        perm.requireRole(member.getRole(), ServerRole.ADMINISTRATOR);
        server.setName(newName);
        return ServerResponse.from(serverRepo.save(server), member.getRole());
    }

    @Transactional
    public void deleteServer(UUID serverId, UUID requesterId) {
        requireServer(serverId);
        ServerMember member = requireMember(serverId, requesterId);
        perm.requireRole(member.getRole(), ServerRole.OWNER);
        serverRepo.deleteById(serverId);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID serverId, UUID requesterId) {
        requireServer(serverId);
        requireMember(serverId, requesterId);
        return memberRepo.findAllByServerId(serverId).stream()
                .map(MemberResponse::from).toList();
    }

    @Transactional
    public MemberResponse setMemberRole(UUID serverId, UUID targetUserId, ServerRole newRole, UUID requesterId) {
        requireServer(serverId);
        ServerMember actor = requireMember(serverId, requesterId);
        perm.requireRole(actor.getRole(), ServerRole.ADMINISTRATOR);

        ServerMember target = requireMember(serverId, targetUserId);

        // ADMINISTRATOR nemůže měnit OWNER ani přidávat OWNER roli
        if (target.getRole() == ServerRole.OWNER || newRole == ServerRole.OWNER) {
            perm.requireRole(actor.getRole(), ServerRole.OWNER);
        }

        target.setRole(newRole);
        return MemberResponse.from(memberRepo.save(target));
    }

    @Transactional
    public void kickMember(UUID serverId, UUID targetUserId, UUID requesterId) {
        requireServer(serverId);
        ServerMember actor = requireMember(serverId, requesterId);
        perm.requireRole(actor.getRole(), ServerRole.ADMINISTRATOR);

        ServerMember target = requireMember(serverId, targetUserId);
        if (target.getRole() == ServerRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vlastníka nelze vyhodit");
        }
        memberRepo.delete(target);
    }

    @Transactional
    public void leaveServer(UUID serverId, UUID userId) {
        requireServer(serverId);
        ServerMember member = requireMember(serverId, userId);
        if (member.getRole() == ServerRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vlastník musí nejdříve převést vlastnictví nebo smazat server");
        }
        memberRepo.delete(member);
    }

    // ---- Invites ----

    @Transactional
    public InviteResponse createInvite(UUID serverId, UUID requesterId,
                                       Integer maxUses, Integer expiresInHours) {
        Server server = requireServer(serverId);
        ServerMember actor = requireMember(serverId, requesterId);
        perm.requireRole(actor.getRole(), ServerRole.ADMINISTRATOR);

        User creator = requireUser(requesterId);
        Instant expiresAt = expiresInHours != null
                ? Instant.now().plus(expiresInHours, ChronoUnit.HOURS) : null;

        String code = generateCode(8);
        Invite invite = inviteRepo.save(new Invite(code, server, creator, maxUses, expiresAt));
        return InviteResponse.from(invite);
    }

    @Transactional
    public ServerResponse joinByInvite(String code, UUID userId) {
        Invite invite = inviteRepo.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Neplatná pozvánka"));

        if (!invite.isValid()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Pozvánka vypršela nebo již byla využita");
        }

        User user = requireUser(userId);
        Server server = invite.getServer();

        if (memberRepo.isMember(server.getId(), userId)) {
            ServerRole role = memberRepo.findMember(server.getId(), userId)
                    .map(ServerMember::getRole).orElse(ServerRole.VISITOR);
            return ServerResponse.from(server, role);
        }

        memberRepo.save(new ServerMember(server, user, ServerRole.VISITOR));
        invite.setUses(invite.getUses() + 1);
        inviteRepo.save(invite);

        return ServerResponse.from(server, ServerRole.VISITOR);
    }

    // ---- helpers ----

    private Server requireServer(UUID id) {
        return serverRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server nenalezen"));
    }

    private ServerMember requireMember(UUID serverId, UUID userId) {
        return memberRepo.findMember(serverId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Nejsi členem tohoto serveru"));
    }

    private User requireUser(UUID id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Uživatel nenalezen"));
    }

    private String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(BASE62.charAt(RNG.nextInt(BASE62.length())));
        return sb.toString();
    }
}
