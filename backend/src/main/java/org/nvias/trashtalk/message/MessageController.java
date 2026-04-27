package org.nvias.trashtalk.message;

import org.nvias.trashtalk.channel.ChannelRepository;
import org.nvias.trashtalk.domain.Attachment;
import org.nvias.trashtalk.domain.Channel;
import org.nvias.trashtalk.domain.Message;
import org.nvias.trashtalk.files.AttachmentRepository;
import org.nvias.trashtalk.message.dto.MessageResponse;
import org.nvias.trashtalk.server.PermissionEvaluator;
import org.nvias.trashtalk.server.ServerMemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/channels/{channelId}/messages")
public class MessageController {

    private static final int PAGE_SIZE = 50;

    private final MessageRepository      messageRepo;
    private final ChannelRepository      channelRepo;
    private final ServerMemberRepository memberRepo;
    private final PermissionEvaluator    perm;
    private final AttachmentRepository   attachmentRepo;

    public MessageController(MessageRepository messageRepo,
                             ChannelRepository channelRepo,
                             ServerMemberRepository memberRepo,
                             PermissionEvaluator perm,
                             AttachmentRepository attachmentRepo) {
        this.messageRepo    = messageRepo;
        this.channelRepo    = channelRepo;
        this.memberRepo     = memberRepo;
        this.perm           = perm;
        this.attachmentRepo = attachmentRepo;
    }

    /**
     * GET /api/channels/{channelId}/messages?before=<ISO8601>
     * Vrací max 50 zpráv, od nejnovější. Volitelně paginace přes 'before'.
     * Výsledek je seřazen ASC (nejstarší první) pro zobrazení v UI.
     */
    @GetMapping
    public List<MessageResponse> history(
            @PathVariable UUID channelId,
            @RequestParam(required = false) String before,
            @AuthenticationPrincipal String userId) {

        Channel channel = channelRepo.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kanál nenalezen"));

        var member = memberRepo.findMember(channel.getServer().getId(), UUID.fromString(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Nejsi členem tohoto serveru"));

        perm.requirePermission(member.getRole(), channel, PermissionEvaluator.READ);

        var pageable = PageRequest.of(0, PAGE_SIZE);
        List<Message> rawMsgs;
        if (before != null) {
            Instant cursor = Instant.parse(before);
            rawMsgs = messageRepo.findBefore(channelId, cursor, pageable);
        } else {
            rawMsgs = messageRepo.findLatest(channelId, pageable);
        }

        List<UUID> msgIds = rawMsgs.stream().map(Message::getId).toList();
        Map<UUID, List<Attachment>> attsByMsg = msgIds.isEmpty()
                ? Map.of()
                : attachmentRepo.findCompleteByMessageIds(msgIds).stream()
                        .collect(Collectors.groupingBy(a -> a.getMessage().getId()));

        List<MessageResponse> msgs = rawMsgs.stream()
                .map(m -> MessageResponse.from(m, attsByMsg.getOrDefault(m.getId(), List.of())))
                .collect(Collectors.toCollection(ArrayList::new));

        // Přetočit na ASC pro UI (chronologicky)
        Collections.reverse(msgs);
        return msgs;
    }
}
