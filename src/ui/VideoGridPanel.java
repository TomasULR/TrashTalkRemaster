package ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mřížka video dlaždic — vyplní celý přidělený prostor.
 * Každá dlaždice: video nebo avatar s iniciálou, speaking glow, muted ikona.
 */
public class VideoGridPanel extends JPanel {

    private static final Color BG      = new Color(17, 19, 22);
    private static final Color TILE_BG = new Color(30, 33, 36);

    private final Map<String, VideoTile> tiles = new LinkedHashMap<>();
    private final JPanel grid = new JPanel();

    public VideoGridPanel() {
        setLayout(new BorderLayout());
        setBackground(BG);
        grid.setBackground(BG);
        add(grid, BorderLayout.CENTER);
    }

    // ---- public API (volat na EDT) ----

    public void addTile(String userId, String username) {
        if (tiles.containsKey(userId)) return;
        VideoTile tile = new VideoTile(userId, username);
        tiles.put(userId, tile);
        grid.add(tile);
        reflow();
    }

    public void removeTile(String userId) {
        VideoTile tile = tiles.remove(userId);
        if (tile != null) { grid.remove(tile); reflow(); }
    }

    public void updateFrame(String userId, BufferedImage frame) {
        VideoTile tile = tiles.get(userId);
        if (tile != null) tile.setFrame(frame);
    }

    public void setSpeaking(String userId, boolean speaking) {
        VideoTile tile = tiles.get(userId);
        if (tile != null) tile.setSpeaking(speaking);
    }

    public void setMuted(String userId, boolean muted) {
        VideoTile tile = tiles.get(userId);
        if (tile != null) tile.setMuted(muted);
    }

    public boolean hasTile(String userId) { return tiles.containsKey(userId); }
    public boolean isEmpty()              { return tiles.isEmpty(); }

    // ---- layout ----

    private void reflow() {
        int n = Math.max(1, tiles.size());
        int cols = n == 1 ? 1 : n <= 4 ? 2 : n <= 9 ? 3 : 4;
        int rows = (int) Math.ceil((double) n / cols);
        grid.setLayout(new GridLayout(rows, cols, 4, 4));
        grid.revalidate();
        grid.repaint();
    }

    // ---- color palette for avatars ----

    private static Color avatarColor(String userId) {
        Color[] palette = {
            new Color(88,  101, 242),
            new Color(87,  242, 135),
            new Color(254, 231,  92),
            new Color(235,  69, 158),
            new Color( 80, 200, 120),
            new Color(255, 136,   0),
            new Color(  0, 185, 255),
            new Color(180, 100, 255),
        };
        return palette[Math.abs(userId.hashCode()) % palette.length];
    }

    // ---- VideoTile ----

    private static class VideoTile extends JPanel {

        private static final Color SPEAK_COLOR  = new Color(35,  165, 90);
        private static final Color MUTED_COLOR  = new Color(237,  66, 69);
        private static final Color OVERLAY_DARK = new Color(  0,   0,  0, 200);
        private static final Color OVERLAY_FADE = new Color(  0,   0,  0,   0);

        private volatile BufferedImage currentFrame;
        private volatile boolean speaking;
        private volatile boolean muted;

        private final String  username;
        private final Color   aColor;
        private final String  initial;

        VideoTile(String userId, String username) {
            this.username = username;
            this.aColor   = avatarColor(userId);
            this.initial  = username.isEmpty() ? "?" : String.valueOf(username.charAt(0)).toUpperCase();
            setBackground(TILE_BG);
            setMinimumSize(new Dimension(160, 90));
        }

        void setFrame(BufferedImage img) {
            this.currentFrame = img;
            repaint();
        }

        void setSpeaking(boolean s) {
            if (this.speaking != s) { this.speaking = s; repaint(); }
        }

        void setMuted(boolean m) {
            if (this.muted != m) { this.muted = m; repaint(); }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,  RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int w = getWidth(), h = getHeight();
            int arc = 10;

            // === Zaoblené pozadí ===
            g2.setColor(TILE_BG);
            g2.fillRoundRect(0, 0, w, h, arc, arc);

            BufferedImage frame = currentFrame;
            if (frame != null) {
                // === Video frame ===
                double scale = Math.min((double) w / frame.getWidth(), (double) h / frame.getHeight());
                int dw = (int)(frame.getWidth()  * scale);
                int dh = (int)(frame.getHeight() * scale);
                // Clip to rounded rect before drawing frame
                Shape clip = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
                g2.setClip(clip);
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, w, h);
                g2.drawImage(frame, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                g2.setClip(null);
            } else {
                // === Avatar fallback ===
                int avSize = Math.min(w, h) * 2 / 5;
                avSize = Math.max(avSize, 32);
                int ax = (w - avSize) / 2;
                int ay = (h - avSize) / 2 - 10;

                // Soft gradient background
                GradientPaint bgGrad = new GradientPaint(0, 0, TILE_BG.brighter(), w, h, TILE_BG);
                g2.setPaint(bgGrad);
                Shape bgClip = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
                g2.fill(bgClip);

                // Avatar circle with glow if speaking
                if (speaking) {
                    g2.setColor(new Color(35, 165, 90, 50));
                    g2.fillOval(ax - 6, ay - 6, avSize + 12, avSize + 12);
                }
                g2.setColor(aColor);
                g2.fillOval(ax, ay, avSize, avSize);

                // Initiál
                g2.setColor(Color.WHITE);
                float fontSize = avSize * 0.42f;
                g2.setFont(new Font("Segoe UI", Font.BOLD, (int) fontSize));
                FontMetrics fm = g2.getFontMetrics();
                int tx = ax + (avSize - fm.stringWidth(initial)) / 2;
                int ty = ay + (avSize + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(initial, tx, ty);
            }

            // === Gradient overlay dole (pro jméno) ===
            int nameBarH = Math.max(30, h / 5);
            GradientPaint nameGrad = new GradientPaint(
                0, h - nameBarH, OVERLAY_FADE,
                0, h,            OVERLAY_DARK
            );
            g2.setPaint(nameGrad);
            Shape clipShape = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
            g2.setClip(clipShape);
            g2.fillRect(0, h - nameBarH, w, nameBarH);
            g2.setClip(null);

            // === Jméno ===
            g2.setColor(Color.WHITE);
            int nameFontSize = Math.max(11, Math.min(14, h / 14));
            g2.setFont(new Font("Segoe UI", Font.BOLD, nameFontSize));
            FontMetrics fm = g2.getFontMetrics();
            String displayName = username;
            int maxW = w - (muted ? 28 : 12);
            while (fm.stringWidth(displayName) > maxW && displayName.length() > 1)
                displayName = displayName.substring(0, displayName.length() - 1);
            if (displayName.length() < username.length()) displayName += "…";
            g2.drawString(displayName, 8, h - 9);

            // === Muted ikona ===
            if (muted) {
                int iconSize = Math.max(16, Math.min(20, h / 12));
                int ix = w - iconSize - 6, iy = h - iconSize - 5;
                g2.setColor(MUTED_COLOR);
                g2.fillOval(ix, iy, iconSize, iconSize);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, (int)(iconSize * 0.7f)));
                FontMetrics emFm = g2.getFontMetrics();
                g2.drawString("🔇", ix + (iconSize - emFm.stringWidth("🔇")) / 2,
                               iy + (iconSize + emFm.getAscent() - emFm.getDescent()) / 2);
            }

            // === Speaking border (green glow) ===
            if (speaking) {
                // Outer glow
                g2.setColor(new Color(35, 165, 90, 55));
                g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawRoundRect(2, 2, w - 5, h - 5, arc, arc);
                // Inner border
                g2.setColor(SPEAK_COLOR);
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, arc, arc);
            }

            g2.dispose();
        }
    }
}
