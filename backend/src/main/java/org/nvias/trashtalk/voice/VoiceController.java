package org.nvias.trashtalk.voice;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @GetMapping("/channels/{channelId}/participants")
    public List<VoiceService.ParticipantInfo> getParticipants(
            @PathVariable UUID channelId,
            @AuthenticationPrincipal String userId) {
        return voiceService.getParticipants(channelId);
    }
}
