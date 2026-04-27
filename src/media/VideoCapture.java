package media;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Snímá webcam přes OpenCV (JavaCV), každý snímek komprimuje do JPEG a předává callbacku.
 * Formát výstupu: JPEG bytes při WIDTH×HEIGHT px, ~30 fps.
 */
public class VideoCapture implements AutoCloseable {

    public static final int WIDTH  = 640;
    public static final int HEIGHT = 480;
    private static final int   FPS          = 30;
    private static final float JPEG_QUALITY = 0.60f;

    private final Consumer<byte[]> onFrame;

    private OpenCVFrameGrabber grabber;
    private Thread             captureThread;
    private volatile boolean   running      = false;
    private volatile boolean   cameraMuted  = false;

    public VideoCapture(Consumer<byte[]> onFrame) {
        this.onFrame = onFrame;
    }

    public void start() throws Exception {
        grabber = new OpenCVFrameGrabber(0);
        grabber.setImageWidth(WIDTH);
        grabber.setImageHeight(HEIGHT);
        grabber.setFrameRate(FPS);
        grabber.start();

        running       = true;
        captureThread = new Thread(this::captureLoop, "video-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public void stop() {
        running = false;
        if (captureThread != null) { captureThread.interrupt(); captureThread = null; }
        if (grabber != null) {
            try { grabber.stop(); grabber.release(); } catch (Exception ignored) {}
            grabber = null;
        }
    }

    public void setMuted(boolean muted) { this.cameraMuted = muted; }

    @Override public void close() { stop(); }

    // ---- capture loop ----

    private void captureLoop() {
        Java2DFrameConverter converter = new Java2DFrameConverter();
        ImageWriter           writer   = ImageIO.getImageWritersByFormatName("jpeg").next();
        JPEGImageWriteParam   param    = new JPEGImageWriteParam(null);
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Frame frame = grabber.grab();
                if (frame == null || frame.image == null || cameraMuted) continue;

                BufferedImage img = converter.convert(frame);
                if (img == null) continue;

                ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
                writer.setOutput(new MemoryCacheImageOutputStream(baos));
                writer.write(null, new IIOImage(img, null, null), param);
                onFrame.accept(baos.toByteArray());

            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) break;
                try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
            }
        }
        writer.dispose();
    }
}
