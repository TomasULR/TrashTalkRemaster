package media;

import javax.sound.sampled.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Snímá audio z mikrofonu ve 20ms rámcích (960 vzorků při 48 kHz).
 * Volá onFrame(short[]) na capture vlákně — Opus encode by měl být rychlý.
 */
public class AudioCapture {

    public static final int SAMPLE_RATE  = 48000;
    public static final int CHANNELS     = 1;
    public static final int FRAME_SAMPLES = 960;   // 20 ms @ 48 kHz
    public static final int BYTES_PER_FRAME = FRAME_SAMPLES * 2; // 16-bit

    private static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false); // little-endian signed

    private TargetDataLine line;
    private Thread captureThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean muted = false;

    private final Consumer<short[]> onFrame;

    public AudioCapture(Consumer<short[]> onFrame) {
        this.onFrame = onFrame;
    }

    public static AudioCapture startPreview(String mixerName, IntConsumer levelCallback) throws LineUnavailableException {
        AudioCapture capture = new AudioCapture(pcm -> {
            long sum = 0;
            for (short s : pcm) sum += (long) s * s;
            int level = Math.min(100, (int) (Math.sqrt((double) sum / pcm.length) * 100 / 32768));
            levelCallback.accept(level);
        });
        capture.startWithMixer(mixerName);
        return capture;
    }

    public void startWithMixer(String mixerName) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (mixerName != null && !mixerName.equals("Výchozí systémové zařízení")) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(mixerName)) {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (mixer.isLineSupported(info)) {
                        line = (TargetDataLine) mixer.getLine(info);
                        line.open(FORMAT, BYTES_PER_FRAME * 4);
                        line.start();
                        running.set(true);
                        captureThread = new Thread(this::captureLoop, "audio-capture");
                        captureThread.setDaemon(true);
                        captureThread.start();
                        return;
                    }
                }
            }
        }
        start();
    }

    public void start() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info))
            throw new LineUnavailableException("Mikrofon nepodporuje formát 48kHz/16-bit/mono");

        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(FORMAT, BYTES_PER_FRAME * 4);
        line.start();

        running.set(true);
        captureThread = new Thread(this::captureLoop, "audio-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public void stop() {
        running.set(false);
        if (captureThread != null) captureThread.interrupt();
        if (line != null) { line.stop(); line.close(); }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() { return muted; }

    private void captureLoop() {
        byte[] buf = new byte[BYTES_PER_FRAME];
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int read = line.read(buf, 0, buf.length);
            if (read <= 0) continue;
            if (muted) continue;

            short[] pcm = bytesToShorts(buf, read);
            onFrame.accept(pcm);
        }
    }

    private static short[] bytesToShorts(byte[] bytes, int len) {
        short[] shorts = new short[len / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = (short) ((bytes[i * 2] & 0xFF) | ((bytes[i * 2 + 1] & 0xFF) << 8));
        }
        return shorts;
    }
}
