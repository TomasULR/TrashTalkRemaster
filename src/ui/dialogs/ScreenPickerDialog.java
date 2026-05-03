package ui.dialogs;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

/**
 * Lets the user pick which monitor to share before screen capture starts.
 */
public class ScreenPickerDialog extends JDialog {

    private static final Color BG       = new Color(43, 45, 49);
    private static final Color CARD_BG  = new Color(54, 57, 63);
    private static final Color CARD_HOV = new Color(70, 74, 82);
    private Rectangle selectedBounds = null;

    public ScreenPickerDialog(Frame parent) {
        super(parent, "Vyberte zdroj sdílení", true);
        buildUi();
    }

    private void buildUi() {
        setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        JLabel header = new JLabel("Co chcete sdílet?");
        header.setForeground(Color.WHITE);
        header.setFont(new Font("Segoe UI", Font.BOLD, 15));
        header.setBorder(new EmptyBorder(16, 20, 8, 20));
        add(header, BorderLayout.NORTH);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        cardsPanel.setBackground(BG);
        cardsPanel.setBorder(new EmptyBorder(0, 8, 8, 8));

        GraphicsDevice[] screens = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();

        for (int i = 0; i < screens.length; i++) {
            GraphicsDevice dev = screens[i];
            Rectangle bounds   = dev.getDefaultConfiguration().getBounds();
            String label       = "Monitor " + (i + 1) + "  (" + bounds.width + " × " + bounds.height + ")";
            cardsPanel.add(makeCard(label, bounds, tryThumbnail(dev, bounds)));
        }

        JScrollPane scroll = new JScrollPane(cardsPanel);
        scroll.setBorder(null);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        footer.setBackground(BG);
        JButton cancel = new JButton("Zrušit");
        cancel.setForeground(Color.WHITE);
        cancel.setBackground(new Color(66, 70, 77));
        cancel.setFocusPainted(false);
        cancel.setBorderPainted(false);
        cancel.addActionListener(e -> dispose());
        footer.add(cancel);
        add(footer, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setMinimumSize(new Dimension(420, 280));
        setResizable(false);
        setLocationRelativeTo(getOwner());
    }

    /** Grabs a thumbnail for the given screen; returns null on failure (e.g. Wayland before grant). */
    private static BufferedImage tryThumbnail(GraphicsDevice dev, Rectangle bounds) {
        try {
            Robot robot = new Robot(dev);
            BufferedImage full = robot.createScreenCapture(bounds);
            int tw = 240, th = (int)(tw * (double) bounds.height / bounds.width);
            BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(full, 0, 0, tw, th, null);
            g.dispose();
            return thumb;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JPanel makeCard(String label, Rectangle bounds, BufferedImage thumb) {
        int TW = 240, TH = 135;
        JPanel card = new JPanel(new BorderLayout(0, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBackground(CARD_BG);
        card.setBorder(new EmptyBorder(8, 8, 8, 8));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Preview area
        JPanel preview = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(20, 22, 25));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                if (thumb != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(thumb, 0, 0, getWidth(), getHeight(), null);
                } else {
                    // Monitor icon placeholder
                    g2.setColor(new Color(66, 70, 77));
                    int mw = 60, mh = 40;
                    int mx = (getWidth() - mw) / 2, my = (getHeight() - mh) / 2 - 4;
                    g2.setStroke(new BasicStroke(2));
                    g2.drawRoundRect(mx, my, mw, mh, 4, 4);
                    g2.fillRect(mx + mw / 2 - 4, my + mh, 8, 8);
                    g2.fillRoundRect(mx - 6, my + mh + 8, mw + 12, 4, 2, 2);
                    g2.setColor(new Color(150, 153, 160));
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                    String msg = "Náhled nedostupný";
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, my + mh + 26);
                }
                g2.dispose();
            }
        };
        preview.setPreferredSize(new Dimension(TW, TH));
        preview.setOpaque(false);
        preview.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.add(preview, BorderLayout.CENTER);

        // Label
        JLabel lbl = new JLabel(label, SwingConstants.CENTER);
        lbl.setForeground(new Color(200, 202, 205));
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lbl.setBorder(new EmptyBorder(6, 0, 0, 0));
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.add(lbl, BorderLayout.SOUTH);

        // Click handler shared by card and all child components
        java.awt.event.MouseAdapter clickHandler = new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                card.setBackground(CARD_HOV);
                card.repaint();
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                // Only reset when mouse truly leaves the card (not just moves to a child)
                if (!card.getBounds().contains(
                        SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), card))) {
                    card.setBackground(CARD_BG);
                    card.repaint();
                }
            }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                selectedBounds = bounds;
                dispose();
            }
        };
        card.addMouseListener(clickHandler);
        preview.addMouseListener(clickHandler);
        lbl.addMouseListener(clickHandler);

        card.setPreferredSize(new Dimension(TW + 16, TH + 40));
        return card;
    }

    /** Returns the chosen screen bounds, or null if the dialog was cancelled. */
    public Rectangle getSelectedBounds() { return selectedBounds; }
}
