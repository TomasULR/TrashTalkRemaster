package org.nvias.trashtalk.voice;

import org.nvias.trashtalk.auth.UserRepository;
import org.nvias.trashtalk.channel.ChannelRepository;
import org.nvias.trashtalk.domain.Channel;
import org.nvias.trashtalk.domain.ChannelType;
import org.nvias.trashtalk.domain.User;
import org.nvias.trashtalk.domain.VoiceSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class VoiceService {

    public record ParticipantInfo(String userId, String username, boolean muted) {}
    public record JoinResult(String mediaSessionId, List<ParticipantInfo> participants) {}

    private final VoiceSessionRepository voiceRepo;
    private final ChannelRepository      channelRepo;
    private final UserRepository         userRepo;

    public VoiceService(VoiceSessionRepository voiceRepo,
                        ChannelRepository channelRepo,
                        UserRepository userRepo) {
        this.voiceRepo   = voiceRepo;
        this.channelRepo = channelRepo;
        this.userRepo    = userRepo;
    }

    @Transactional
    public JoinResult joinChannel(UUID channelId, UUID userId) {
        Channel channel = channelRepo.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kanál nenalezen"));
        if (channel.getType() != ChannelType.VOICE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kanál není hlasový");

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Uživatel nenalezen"));

        // close any stale active session for this user in this channel
        voiceRepo.markLeft(channelId, userId, Instant.now());

        VoiceSession session = new VoiceSession();
        session.setChannel(channel);
        session.setUser(user);
        session.setMediaSessionId(UUID.randomUUID().toString());
        voiceRepo.save(session);

        List<ParticipantInfo> participants = getParticipants(channelId);
        return new JoinResult(session.getMediaSessionId(), participants);
    }

    @Transactional
    public void leaveChannel(UUID channelId, UUID userId) {
        voiceRepo.markLeft(channelId, userId, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<ParticipantInfo> getParticipants(UUID channelId) {
        return voiceRepo.findActiveByChannelId(channelId).stream()
                .map(vs -> new ParticipantInfo(
                        vs.getUser().getId().toString(),
                        vs.getUser().getUsername(),
                        false))
                .toList();
    }

    @Transactional(readOnly = true)
    public UUID resolveChannelForSession(String mediaSessionId) {
        return voiceRepo.findAll().stream()
                .filter(vs -> vs.getMediaSessionId().equals(mediaSessionId) && vs.getLeftAt() == null)
                .map(vs -> vs.getChannel().getId())
                .findFirst()
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public UUID resolveUserForSession(String mediaSessionId) {
        return voiceRepo.findAll().stream()
                .filter(vs -> vs.getMediaSessionId().equals(mediaSessionId) && vs.getLeftAt() == null)
                .map(vs -> vs.getUser().getId())
                .findFirst()
                .orElse(null);
    }
}
