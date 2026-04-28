package org.nvias.trashtalk.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.nvias.trashtalk.domain.UserSettings;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingsDto {
    private String audioInputDevice;
    private String audioOutputDevice;
    private String videoInputDevice;
    private String resolution;
    private Integer fps;

    public UserSettingsDto(UserSettings settings) {
        this.audioInputDevice = settings.getAudioInputDevice();
        this.audioOutputDevice = settings.getAudioOutputDevice();
        this.videoInputDevice = settings.getVideoInputDevice();
        this.resolution = settings.getResolution();
        this.fps = settings.getFps();
    }
}
