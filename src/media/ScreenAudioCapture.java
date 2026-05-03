package media;

import javax.sound.sampled.*;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Captures desktop/system audio for screen sharing.
 *
 * Linux  — uses {@code pacat --record} from the default output monitor so only
 *          the local machine's audio is captured. To exclude voice-chat audio
 *          from the stream, route your headphones to a separate PulseAudio sink
 *          and let AudioPlayback play participant voices through it.
 *
 * Others — searches for a loopback/Stereo-Mix device via javax.sound; falls
 *          back to silent (no audio) if none is found.
 */
public class ScreenAudioCapture {

    private static final int SAMPLE_RATE  = 48000;
    private static final int FRAME_SAMPLES = 960; // 20 ms at 48 kHz

    private final Consumer<short[]> onFrame;
    private volatile Thread captureThread;
    private volatile boolean running;

    // subprocess handle so we can kill it on stop()
    private volatile Process pacatProcess;

    public ScreenAudioCapture(Consumer<short[]> onFrame) {
        this.onFrame = onFrame;
    }

    public void start() {
        running = true;
        captureThread = new Thread(() -> {
            if (isLinux() && isPacatAvailable()) {
                captureWithPacat();
            } else {
                captureWithJavaxSound();
            }
        }, "screen-audio-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public void stop() {
        running = false;
        Process p = pacatProcess;
        if (p != null) p.destroyForcibly();
        Thread t = captureThread;
        if (t != null) { t.interrupt(); captureThread = null; }
    }

    // ---- Linux path ----

    private void captureWithPacat() {
        try {
            String monitor = defaultMonitor();
            ProcessBuilder pb = new ProcessBuilder(
                "pacat", "--record",
                "--format=s16le", "--rate=48000", "--channels=1",
                "--latency-msec=50");
            if (monitor != null) { pb.command().add("-d"); pb.command().add(monitor); }
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            pacatProcess = pb.start();
            InputStream in = pacatProcess.getInputStream();
            byte[] raw = new byte[FRAME_SAMPLES * 2]; // 16-bit mono

            while (running && !Thread.currentThread().isInterrupted()) {
                int pos = 0;
                while (pos < raw.length) {
                    int n = in.read(raw, pos, raw.length - pos);
                    if (n < 0) { running = false; break; }
                    pos += n;
                }
                if (!running) break;
                onFrame.accept(toShorts(raw));
            }
        } catch (Exception e) {
            if (running) System.err.println("[ScreenAudio] pacat error: " + e.getMessage());
        } finally {
            Process p = pacatProcess;
            if (p != null) p.destroyForcibly();
        }
    }

    /** Returns the monitor source for the default PulseAudio/PipeWire sink, or null. */
    private static String defaultMonitor() {
        try {
            Process p = new ProcessBuilder("pactl", "get-default-sink")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            String sink = new String(p.getInputStream().readAllBytes()).trim();
            return sink.isEmpty() ? null : sink + ".monitor";
        } catch (Exception e) {
            return null;
        }
    }

    // ---- javax.sound path (Windows / fallback) ----

    private void captureWithJavaxSound() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            TargetDataLine line = findLoopbackLine(fmt);
            if (line == null) {
                System.err.println("[ScreenAudio] no loopback/Stereo-Mix device found — no stream audio");
                return;
            }
            line.open(fmt);
            line.start();
            byte[] raw = new byte[FRAME_SAMPLES * 2];
            while (running && !Thread.currentThread().isInterrupted()) {
                int read = 0;
                while (read < raw.length) {
                    int n = line.read(raw, read, raw.length - read);
                    if (n < 0) { running = false; break; }
                    read += n;
                }
                if (!running) break;
                onFrame.accept(toShorts(raw));
            }
            line.stop();
            line.close();
        } catch (Exception e) {
            if (running) System.err.println("[ScreenAudio] javax.sound error: " + e.getMessage());
        }
    }

    /**
     * Finds a loopback or Stereo-Mix capture device.
     * On Windows this is "Stereo Mix" / "What U Hear" if enabled in Sound settings.
     */
    private static TargetDataLine findLoopbackLine(AudioFormat fmt) {
        String[] keywords = {"stereo mix", "what u hear", "loopback", "monitor", "wave out mix"};
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            String name = info.getName().toLowerCase();
            for (String kw : keywords) {
                if (name.contains(kw)) {
                    try {
                        Mixer m = AudioSystem.getMixer(info);
                        DataLine.Info li = new DataLine.Info(TargetDataLine.class, fmt);
                        if (m.isLineSupported(li)) return (TargetDataLine) m.getLine(li);
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    // ---- helpers ----

    private static short[] toShorts(byte[] raw) {
        short[] out = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    private static Boolean pacatAvailable = null;

    private static boolean isPacatAvailable() {
        if (pacatAvailable != null) return pacatAvailable;
        try {
            Process p = new ProcessBuilder("which", "pacat")
                    .redirectErrorStream(true).start();
            pacatAvailable = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            pacatAvailable = false;
        }
        System.out.println("[ScreenAudio] pacat available: " + pacatAvailable);
        return pacatAvailable;
    }
}
