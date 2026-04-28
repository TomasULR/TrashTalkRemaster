package org.nvias.trashtalk.user;

import org.nvias.trashtalk.domain.User;
import org.nvias.trashtalk.domain.UserSettings;
import org.nvias.trashtalk.auth.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/settings")
public class UserSettingsController {

    @Autowired
    private UserSettingsRepository settingsRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public ResponseEntity<UserSettingsDto> getSettings(Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsernameIgnoreCase(username).orElseThrow();
        UserSettings settings = settingsRepository.findById(user.getId()).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUser(user);
            return settingsRepository.save(s);
        });
        return ResponseEntity.ok(new UserSettingsDto(settings));
    }

    @PutMapping
    public ResponseEntity<UserSettingsDto> updateSettings(@RequestBody UserSettingsDto dto, Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsernameIgnoreCase(username).orElseThrow();
        UserSettings settings = settingsRepository.findById(user.getId()).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUser(user);
            return s;
        });

        settings.setAudioInputDevice(dto.getAudioInputDevice());
        settings.setAudioOutputDevice(dto.getAudioOutputDevice());
        settings.setVideoInputDevice(dto.getVideoInputDevice());
        if (dto.getResolution() != null) settings.setResolution(dto.getResolution());
        if (dto.getFps() != null) settings.setFps(dto.getFps());

        UserSettings saved = settingsRepository.save(settings);
        return ResponseEntity.ok(new UserSettingsDto(saved));
    }
}
