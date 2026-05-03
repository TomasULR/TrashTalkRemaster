package ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

public class VideoGridPanel extends JPanel {

    private static final Color BG      = new Color(17, 19, 22);
    private static final Color TILE_BG = new Color(30, 33, 36);

    private final Map<String, VideoTile> tiles = new LinkedHashMap<>();
    private final JPanel grid = new JPanel();

    // Expanded (fills grid panel) and fullscreen (own JFrame) state
    private String         expandedTileId  = null;
    private String         fullscreenUserId = null;
    private FullscreenPanel fullscreenPanel = null;
    private JFrame          fullscreenFrame = null;

    public VideoGridPanel() {
        setLayout(new BorderLayout());
        setBackground(BG);
        grid.setBackground(BG);
        add(grid, BorderLayout.CENTER);
    }

    // ---- public API (call on EDT) ----

    public void addTile(String userId, String username) {
        if (tiles.containsKey(userId)) return;
        VideoTile tile = new VideoTile(userId, username);
        tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tile.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    openFullscreen(userId);
                } else {
                    if (expandedTileId == null) {
                        expandedTileId = userId;
                    } else {
                        expandedTileId = null;
                    }
                    reflow();
                }
            }
        });
        tiles.put(userId, tile);
        reflow();
    }

    public void removeTile(String userId) {
        VideoTile tile = tiles.remove(userId);
        if (tile == null) return;
        if (userId.equals(expandedTileId)) expandedTileId = null;
        if (userId.equals(fullscreenUserId)) closeFullscreen();
        reflow();
    }

    public void updateFrame(String userId, BufferedImage frame) {
        VideoTile tile = tiles.get(userId);
        if (tile != null) tile.setFrame(frame);
        if (userId.equals(fullscreenUserId) && fullscreenPanel != null) {
            fullscreenPanel.setFrame(frame);
        }
    }

    public void setSpeaking(String userId, boolean speaking) {
        VideoTile tile = tiles.get(userId);
        if (tile != null) tile.setSpeaking(speaking);
        if (userId.equals(fullscreenUserId) && fullscreenPanel != null) {
            fullscreenPanel.setSpeaking(speaking);
        }
    }

    public void setMuted(String userId, boolean muted) {
        VideoTile tile = tiles.get(userId);
        if (tile != null) tile.setMuted(muted);
        if (userId.equals(fullscreenUserId) && fullscreenPanel != null) {
            fullscreenPanel.setMuted(muted);
        }
    }

    public void setAudioLevel(String userId, int level) {
        VideoTile tile = tiles.get(userId);
        if (tile != null) tile.setAudioLevel(level);
    }

    public boolean hasTile(String userId) { return tiles.containsKey(userId); }
    public boolean isEmpty()              { return tiles.isEmpty(); }

    // ---- expand / fullscreen ----

    private void openFullscreen(String userId) {
        VideoTile tile = tiles.get(userId);
        if (tile == null) return;
        closeFullscreen();

        fullscreenUserId = userId;
        fullscreenPanel  = new FullscreenPanel(tile.username, tile.aColor, tile.initial);
        fullscreenPanel.setFrame(tile.currentFrame);
        fullscreenPanel.setSpeaking(tile.speaking);
        fullscreenPanel.setMuted(tile.muted);

        fullscreenFrame = new JFrame(tile.username + " — TrashTalk");
        fullscreenFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        fullscreenFrame.getContentPane().setBackground(BG);
        fullscreenFrame.getContentPane().setLayout(new BorderLayout());
        fullscreenFrame.getContentPane().add(fullscreenPanel, BorderLayout.CENTER);
        fullscreenFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { closeFullscreen(); }
        });
        // Escape closes fullscreen
        fullscreenPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFs");
        fullscreenPanel.getActionMap().put("closeFs",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    closeFullscreen();
                }});

        fullscreenFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        fullscreenFrame.setVisible(true);
    }

    private void closeFullscreen() {
        fullscreenUserId = null;
        fullscreenPanel  = null;
        if (fullscreenFrame != null) {
            JFrame f = fullscreenFrame;
            fullscreenFrame = null;
            f.dispose();
        }
    }

    // ---- layout ----

    private void reflow() {
        grid.removeAll();
        if (expandedTileId != null && tiles.containsKey(expandedTileId)) {
            // Single tile fills the entire panel
            grid.setLayout(new BorderLayout());
            grid.add(tiles.get(expandedTileId), BorderLayout.CENTER);
        } else {
            expandedTileId = null;
            int n    = Math.max(1, tiles.size());
            int cols = n == 1 ? 1 : n <= 4 ? 2 : n <= 9 ? 3 : 4;
            int rows = (int) Math.ceil((double) n / cols);
            grid.setLayout(new GridLayout(rows, cols, 4, 4));
            for (VideoTile t : tiles.values()) grid.add(t);
        }
        grid.revalidate();
        grid.repaint();
    }

    // ---- avatar palette ----

    private static Color avatarColor(String userId) {
        Color[] palette = {
            new Color( 88, 101, 242), new Color( 87, 242, 135),
            new Color(254, 231,  92), new Color(235,  69, 158),
            new Color( 80, 200, 120), new Color(255, 136,   0),
            new Color(  0, 185, 255), new Color(180, 100, 255),
        };
        return palette[Math.abs(userId.hashCode()) % palette.length];
    }

    // ---- VideoTile ----

    private static class VideoTile extends JPanel {

        private static final Color SPEAK_COLOR  = new Color(35,  165, 90);
        private static final Color MUTED_COLOR  = new Color(237,  66, 69);
        private static final Color OVERLAY_DARK = new Color(  0,   0,  0, 200);
        private static final Color OVERLAY_FADE = new Color(  0,   0,  0,   0);

        volatile BufferedImage currentFrame;
        volatile boolean speaking;
        volatile boolean muted;

        final String username;
        final Color  aColor;
        final String initial;

        VideoTile(String userId, String username) {
            this.username = username;
            this.aColor   = avatarColor(userId);
            this.initial  = username.isEmpty() ? "?" : String.valueOf(username.charAt(0)).toUpperCase();
            setBackground(TILE_BG);
            setMinimumSize(new Dimension(160, 90));
        }

        void setFrame(BufferedImage img)    { this.currentFrame = img; repaint(); }
        void setSpeaking(boolean s)         { if (speaking != s) { speaking = s; repaint(); } }
        void setMuted(boolean m)            { if (muted    != m) { muted    = m; repaint(); } }
        void setAudioLevel(int l)           { repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            renderVideo((Graphics2D) g.create(), getWidth(), getHeight(),
                    currentFrame, username, initial, aColor, speaking, muted, 10);
        }
    }

    // ---- FullscreenPanel ----

    private static class FullscreenPanel extends JPanel {

        private volatile BufferedImage currentFrame;
        private volatile boolean speaking;
        private volatile boolean muted;

        private final String username;
        private final Color  aColor;
        private final String initial;

        FullscreenPanel(String username, Color aColor, String initial) {
            this.username = username;
            this.aColor   = aColor;
            this.initial  = initial;
            setBackground(new Color(17, 19, 22));
        }

        void setFrame(BufferedImage f)  { currentFrame = f; repaint(); }
        void setSpeaking(boolean s)     { speaking = s; repaint(); }
        void setMuted(boolean m)        { muted    = m; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            renderVideo((Graphics2D) g.create(), getWidth(), getHeight(),
                    currentFrame, username, initial, aColor, speaking, muted, 0);
        }
    }

    // ---- shared rendering logic ----

    private static void renderVideo(Graphics2D g2, int w, int h,
                                     BufferedImage frame, String username, String initial,
                                     Color aColor, boolean speaking, boolean muted, int arc) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,  RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Background
        g2.setColor(TILE_BG);
        if (arc > 0) g2.fillRoundRect(0, 0, w, h, arc, arc);
        else         g2.fillRect(0, 0, w, h);

        if (frame != null) {
            // Letterbox: keep aspect ratio
            double scale = Math.min((double) w / frame.getWidth(), (double) h / frame.getHeight());
            int dw = (int)(frame.getWidth()  * scale);
            int dh = (int)(frame.getHeight() * scale);
            Shape clip = arc > 0 ? new RoundRectangle2D.Float(0, 0, w, h, arc, arc) : new Rectangle(0, 0, w, h);
            g2.setClip(clip);
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, w, h);
            g2.drawImage(frame, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
            g2.setClip(null);
        } else {
            int avSize = Math.min(w, h) * 2 / 5;
            avSize = Math.max(avSize, 32);
            int ax = (w - avSize) / 2, ay = (h - avSize) / 2 - 10;

            GradientPaint bg = new GradientPaint(0, 0, TILE_BG.brighter(), w, h, TILE_BG);
            g2.setPaint(bg);
            g2.fill(arc > 0 ? new RoundRectangle2D.Float(0, 0, w, h, arc, arc) : new Rectangle(0, 0, w, h));

            if (speaking) {
                g2.setColor(new Color(35, 165, 90, 50));
                g2.fillOval(ax - 6, ay - 6, avSize + 12, avSize + 12);
            }
            g2.setColor(aColor);
            g2.fillOval(ax, ay, avSize, avSize);
            g2.setColor(Color.WHITE);
            float fz = avSize * 0.42f;
            g2.setFont(new Font("Segoe UI", Font.BOLD, (int) fz));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(initial,
                    ax + (avSize - fm.stringWidth(initial)) / 2,
                    ay + (avSize + fm.getAscent() - fm.getDescent()) / 2);
        }

        // Name bar gradient overlay
        int nameBarH = Math.max(30, h / 5);
        GradientPaint nameGrad = new GradientPaint(
                0, h - nameBarH, new Color(0, 0, 0, 0),
                0, h,            new Color(0, 0, 0, 200));
        g2.setPaint(nameGrad);
        Shape barClip = arc > 0 ? new RoundRectangle2D.Float(0, 0, w, h, arc, arc) : new Rectangle(0, 0, w, h);
        g2.setClip(barClip);
        g2.fillRect(0, h - nameBarH, w, nameBarH);
        g2.setClip(null);

        // Name text
        g2.setColor(Color.WHITE);
        int nameFz = Math.max(11, Math.min(14, h / 14));
        g2.setFont(new Font("Segoe UI", Font.BOLD, nameFz));
        FontMetrics fm = g2.getFontMetrics();
        String disp = username;
        int maxW = w - (muted ? 30 : 14);
        while (fm.stringWidth(disp) > maxW && disp.length() > 1)
            disp = disp.substring(0, disp.length() - 1);
        if (disp.length() < username.length()) disp += "…";
        g2.drawString(disp, 8, h - 9);

        // Muted badge
        if (muted) {
            int sz = Math.max(16, Math.min(20, h / 12));
            int ix = w - sz - 6, iy = h - sz - 5;
            g2.setColor(new Color(237, 66, 69));
            g2.fillOval(ix, iy, sz, sz);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, (int)(sz * 0.7f)));
            FontMetrics emFm = g2.getFontMetrics();
            String ico = "🔇";
            g2.drawString(ico, ix + (sz - emFm.stringWidth(ico)) / 2,
                    iy + (sz + emFm.getAscent() - emFm.getDescent()) / 2);
        }

        // Speaking border glow
        if (speaking) {
            g2.setColor(new Color(35, 165, 90, 55));
            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect(2, 2, w - 5, h - 5, arc, arc);
            g2.setColor(new Color(35, 165, 90));
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, arc, arc);
        }

        // "Click to collapse" hint when this is the only tile shown
        // (added at call-site via arc=0 for fullscreen, we can check h > 400)
        if (arc == 0 && h > 200) {
            g2.setColor(new Color(255, 255, 255, 40));
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            String hint = "Esc — zavřít fullscreen";
            FontMetrics hfm = g2.getFontMetrics();
            g2.drawString(hint, w - hfm.stringWidth(hint) - 10, 20);
        }

        g2.dispose();
    }
}
