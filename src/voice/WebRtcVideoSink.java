package voice;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.I420Buffer;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

public class WebRtcVideoSink implements VideoTrackSink {

    private final String remoteUserId;
    private final BiConsumer<String, BufferedImage> frameConsumer;

    public WebRtcVideoSink(String remoteUserId, BiConsumer<String, BufferedImage> frameConsumer) {
        this.remoteUserId = remoteUserId;
        this.frameConsumer = frameConsumer;
    }

    @Override
    public void onVideoFrame(VideoFrame frame) {
        try {
            frame.retain();
            I420Buffer i420 = frame.buffer.toI420();
            int width  = i420.getWidth();
            int height = i420.getHeight();

            ByteBuffer dataY = i420.getDataY();
            ByteBuffer dataU = i420.getDataU();
            ByteBuffer dataV = i420.getDataV();
            int strideY = i420.getStrideY();
            int strideU = i420.getStrideU();
            int strideV = i420.getStrideV();

            byte[] yPlane = new byte[strideY * height];
            byte[] uPlane = new byte[strideU * ((height + 1) / 2)];
            byte[] vPlane = new byte[strideV * ((height + 1) / 2)];
            dataY.get(yPlane);
            dataU.get(uPlane);
            dataV.get(vPlane);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] raster = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int Y = yPlane[y * strideY + x] & 0xFF;
                    int U = uPlane[(y >> 1) * strideU + (x >> 1)] & 0xFF;
                    int V = vPlane[(y >> 1) * strideV + (x >> 1)] & 0xFF;
                    // BT.601 limited range
                    int c = Y - 16, d = U - 128, e = V - 128;
                    int r = clamp((298 * c           + 409 * e + 128) >> 8);
                    int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                    int b = clamp((298 * c + 516 * d           + 128) >> 8);
                    raster[y * width + x] = (r << 16) | (g << 8) | b;
                }
            }

            i420.release();
            frameConsumer.accept(remoteUserId, image);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            frame.release();
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
