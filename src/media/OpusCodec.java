package media;

import club.minnced.opus.util.OpusLibrary;
import com.sun.jna.ptr.PointerByReference;
import tomp2p.opuswrapper.Opus;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper přes Opus JNA binding (club.minnced:opus-java 1.1.1).
 * Správné typy dle javap: PointerByReference pro handle, ShortBuffer/ByteBuffer pro PCM/výstup.
 * Nativní libopus je bundlovaná uvnitř opus-java-natives-1.1.1.jar.
 */
public class OpusCodec implements AutoCloseable {

    private static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;
    private static final int CHANNELS    = AudioCapture.CHANNELS;
    private static final int FRAME_SIZE  = AudioCapture.FRAME_SAMPLES;
    private static final int MAX_PACKET  = 4000;

    private static volatile boolean nativeLoaded = false;

    private final PointerByReference encoder;
    private final ConcurrentHashMap<String, PointerByReference> decoders = new ConcurrentHashMap<>();

    public OpusCodec() {
        ensureNativeLoaded();

        IntBuffer err = IntBuffer.allocate(1);
        encoder = Opus.INSTANCE.opus_encoder_create(SAMPLE_RATE, CHANNELS, Opus.OPUS_APPLICATION_VOIP, err);
        if (encoder == null || err.get(0) != 0)
            throw new RuntimeException("opus_encoder_create selhalo, kód: " + err.get(0));
    }

    /** Enkóduje 960 PCM vzorků (20 ms) na Opus packet. Vrací null při chybě. */
    public byte[] encode(short[] pcm) {
        ByteBuffer out = ByteBuffer.allocate(MAX_PACKET);
        int len = Opus.INSTANCE.opus_encode(encoder, ShortBuffer.wrap(pcm), FRAME_SIZE, out, MAX_PACKET);
        if (len <= 0) return null;
        byte[] result = new byte[len];
        out.get(0, result, 0, len);
        return result;
    }

    /** Dekóduje Opus packet pro daného odesílatele — každý má vlastní decoder se stavem. */
    public short[] decode(String senderId, byte[] data) {
        PointerByReference dec = decoders.computeIfAbsent(senderId, id -> {
            PointerByReference d = Opus.INSTANCE.opus_decoder_create(SAMPLE_RATE, CHANNELS, IntBuffer.allocate(1));
            if (d == null) throw new RuntimeException("opus_decoder_create selhalo pro " + id);
            return d;
        });
        short[] pcm = new short[FRAME_SIZE * CHANNELS];
        ShortBuffer outBuf = ShortBuffer.wrap(pcm);
        int samples = Opus.INSTANCE.opus_decode(dec, data, data != null ? data.length : 0, outBuf, FRAME_SIZE, 0);
        if (samples <= 0) return null;
        return pcm;
    }

    public void removeDecoder(String senderId) {
        PointerByReference dec = decoders.remove(senderId);
        if (dec != null) Opus.INSTANCE.opus_decoder_destroy(dec);
    }

    @Override
    public void close() {
        decoders.values().forEach(dec -> Opus.INSTANCE.opus_decoder_destroy(dec));
        decoders.clear();
        if (encoder != null) Opus.INSTANCE.opus_encoder_destroy(encoder);
    }

    private static synchronized void ensureNativeLoaded() {
        if (nativeLoaded) return;
        try {
            OpusLibrary.loadFromJar();
            nativeLoaded = true;
        } catch (Exception e) {
            throw new RuntimeException("Opus nativní knihovna se nepodařila načíst: " + e.getMessage(), e);
        }
    }
}
