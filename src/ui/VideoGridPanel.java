package ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Panel s automatickou mřížkou video dlaždic.
 * Počet sloupců = ⌈√n⌉; každá dlaždice je 320×240 px.
 */
public class VideoGridPanel extends JPanel {

    private static final Color BG      = new Color(32, 34, 37);
    private static final Color TILE_BG = new Color(47, 49, 54);
    private static final Color TEXT_FG = new Color(220, 221, 222);

    private final Map<String, VideoTile> tiles = new LinkedHashMap<>();
    private final JPanel grid = new JPanel(new GridLayout(0, 1, 4, 4));

    public VideoGridPanel() {
        setLayout(new BorderLayout());
        setBackground(BG);
        setMinimumSize(new Dimension(0, 0));
        grid.setBackground(BG);
        grid.setBorder(new EmptyBorder(4, 4, 4, 4));

        JScrollPane scroll = new JScrollPane(grid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        add(scroll, BorderLayout.CENTER);
    }

    // ---- tile management (call on EDT) ----

    public void addTile(String userId, String username) {
        if (tiles.containsKey(userId)) return;
        VideoTile tile = new VideoTile(username);
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

    public boolean hasTile(String userId) { return tiles.containsKey(userId); }

    public boolean isEmpty() { return tiles.isEmpty(); }

    private void reflow() {
        int cols = (int) Math.ceil(Math.sqrt(Math.max(1, tiles.size())));
        ((GridLayout) grid.getLayout()).setColumns(cols);
        grid.revalidate();
        grid.repaint();
    }

    // ---- inner tile ----

    private static class VideoTile extends JPanel {
        private final JLabel imageLabel;

        VideoTile(String username) {
            setLayout(new BorderLayout(0, 2));
            setBackground(TILE_BG);
            setBorder(new EmptyBorder(4, 4, 4, 4));
            setPreferredSize(new Dimension(320, 240));

            imageLabel = new JLabel("📷", SwingConstants.CENTER);
            imageLabel.setBackground(BG);
            imageLabel.setOpaque(true);
            imageLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 48));

            JLabel nameLabel = new JLabel(username, SwingConstants.CENTER);
            nameLabel.setForeground(TEXT_FG);
            nameLabel.setFont(new Font("Arial", Font.BOLD, 12));
            nameLabel.setOpaque(true);
            nameLabel.setBackground(Color.BLACK);

            add(imageLabel, BorderLayout.CENTER);
            add(nameLabel, BorderLayout.SOUTH);
        }

        void setFrame(BufferedImage img) {
            if (img == null) {
                SwingUtilities.invokeLater(() -> { imageLabel.setIcon(null); imageLabel.setText("📷"); });
                return;
            }
            Image scaled = img.getScaledInstance(320, 240, Image.SCALE_FAST);
            SwingUtilities.invokeLater(() -> { imageLabel.setIcon(new ImageIcon(scaled)); imageLabel.setText(null); });
        }
    }
}
