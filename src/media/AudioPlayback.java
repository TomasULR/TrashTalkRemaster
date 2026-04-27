package media;

import javax.sound.sampled.*;

/**
 * Přehrává dekódované PCM rámce přes JavaSound.
 * play() je thread-safe — volá se z vlákna příjmu WS.
 */
public class AudioPlayback {

    private static final AudioFormat FORMAT =
            new AudioFormat(AudioCapture.SAMPLE_RATE, 16, AudioCapture.CHANNELS, true, false);

    private SourceDataLine line;

    public void start() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info))
            throw new LineUnavailableException("Reproduktor nepodporuje formát 48kHz/16-bit/mono");

        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(FORMAT, AudioCapture.BYTES_PER_FRAME * 8);
        line.start();
    }

    public void play(short[] pcm) {
        if (line == null || !line.isOpen()) return;
        byte[] bytes = shortsToBytes(pcm);
        line.write(bytes, 0, bytes.length);
    }

    public void stop() {
        if (line != null) { line.drain(); line.stop(); line.close(); line = null; }
    }

    private static byte[] shortsToBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            bytes[i * 2]     = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xFF);
        }
        return bytes;
    }
}
