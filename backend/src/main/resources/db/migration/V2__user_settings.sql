CREATE TABLE user_settings (
    id                  UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    audio_input_device  VARCHAR(255),
    audio_output_device VARCHAR(255),
    video_input_device  VARCHAR(255),
    resolution          VARCHAR(32)  DEFAULT '1920x1080',
    fps                 INTEGER      DEFAULT 60
);
