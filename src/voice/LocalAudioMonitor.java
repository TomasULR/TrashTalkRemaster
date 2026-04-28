package voice;

import javax.sound.sampled.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Monitoruje hlasitost lokálního mikrofonu a volá callback s hodnotou 0–100 každých ~50ms.
 * Gracefully selže pokud mikrofon není dostupný nebo ho WebRTC drží exkluzivně.
 */
public class LocalAudioMonitor {

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    private final IntConsumer levelCallback;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private TargetDataLine line;

    public LocalAudioMonitor(IntConsumer levelCallback) {
        this.levelCallback = levelCallback;
    }

    public void start() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) return;
            line = (TargetDataLine) AudioSystem.getLine(info);
            int bufBytes = (int) (FORMAT.getSampleRate() * FORMAT.getFrameSize() * 0.05f); // 50ms
            line.open(FORMAT, bufBytes);
            line.start();
            running.set(true);
            thread = new Thread(this::loop, "local-audio-monitor");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception ignored) {}
    }

    private void loop() {
        int bufSize = (int) (FORMAT.getSampleRate() * FORMAT.getFrameSize() * 0.05f);
        byte[] buf = new byte[bufSize];
        while (running.get()) {
            int n = line.read(buf, 0, buf.length);
            if (n < 2) continue;
            long sumSq = 0;
            for (int i = 0; i < n - 1; i += 2) {
                short s = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
                sumSq += (long) s * s;
            }
            double rms = Math.sqrt((double) sumSq / (n / 2));
            int level = (int) Math.min(100, rms / 200.0);
            levelCallback.accept(level);
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) { thread.interrupt(); thread = null; }
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
            line = null;
        }
    }
}
