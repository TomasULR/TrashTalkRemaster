package org.nvias.trashtalk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter @Setter @NoArgsConstructor
public class UserSettings {

    @Id
    private UUID id; // Same as User ID

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private User user;

    @Column(name = "audio_input_device")
    private String audioInputDevice;

    @Column(name = "audio_output_device")
    private String audioOutputDevice;

    @Column(name = "video_input_device")
    private String videoInputDevice;

    @Column(name = "resolution")
    private String resolution = "1920x1080";

    @Column(name = "fps")
    private Integer fps = 60;
}
