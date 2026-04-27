package ui;

import model.ServerInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Levý rail (~72px) s kruhovými ikonami serverů — jako Discord.
 * Nahoře seznam serverů, dole tlačítka "+" (nový server) a "🔗" (join pozvánkou).
 */
public class ServerListPanel extends JPanel {

    private static final int ICON_SIZE = 48;
    private static final int RAIL_WIDTH = 70;
    private static final Color BG = new Color(32, 34, 37);
    private static final Color SELECTED = new Color(88, 101, 242);
    private static final Color HOVER = new Color(67, 181, 129);
    private static final Color ADD_BTN = new Color(60, 64, 72);

    private final List<ServerInfo> servers = new ArrayList<>();
    private ServerInfo selected = null;

    private Consumer<ServerInfo> onSelect;
    private Runnable onCreateServer;
    private Runnable onJoinServer;

    private final JPanel listContainer;

    public ServerListPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(RAIL_WIDTH, 0));
        setBackground(BG);

        listContainer = new JPanel();
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setBackground(BG);

        JScrollPane scroll = new JScrollPane(listContainer,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);

        JPanel bottomBar = buildBottomBar();

        add(scroll, BorderLayout.CENTER);
        add(bottomBar, BorderLayout.SOUTH);
    }

    public void setOnSelect(Consumer<ServerInfo> handler) { this.onSelect = handler; }
    public void setOnCreateServer(Runnable handler) { this.onCreateServer = handler; }
    public void setOnJoinServer(Runnable handler)   { this.onJoinServer = handler; }

    public void setServers(List<ServerInfo> newList) {
        servers.clear();
        servers.addAll(newList);
        rebuildList();
    }

    public void addServer(ServerInfo s) {
        servers.add(s);
        rebuildList();
    }

    public void removeServer(ServerInfo s) {
        servers.removeIf(x -> x.id().equals(s.id()));
        if (selected != null && selected.id().equals(s.id())) selected = null;
        rebuildList();
    }

    public void updateServer(ServerInfo s) {
        servers.replaceAll(x -> x.id().equals(s.id()) ? s : x);
        rebuildList();
    }

    private void rebuildList() {
        listContainer.removeAll();
        listContainer.add(Box.createVerticalStrut(8));
        for (ServerInfo s : servers) {
            listContainer.add(buildServerIcon(s));
            listContainer.add(Box.createVerticalStrut(6));
        }
        listContainer.revalidate();
        listContainer.repaint();
    }

    private JComponent buildServerIcon(ServerInfo s) {
        String initials = initials(s.name());
        boolean isSelected = selected != null && selected.id().equals(s.id());

        JButton btn = new JButton(initials) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = isSelected ? SELECTED : getModel().isRollover() ? HOVER : new Color(54, 57, 63);
                g2.setColor(bg);
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int tx = (getWidth() - fm.stringWidth(getText())) / 2;
                int ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(getText(), tx, ty);
                g2.dispose();
            }
        };
        btn.setFont(new Font("Arial", Font.BOLD, 14));
        btn.setForeground(Color.WHITE);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setToolTipText(s.name());
        btn.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
        btn.setMaximumSize(new Dimension(ICON_SIZE, ICON_SIZE));
        btn.setAlignmentX(CENTER_ALIGNMENT);

        btn.addActionListener(e -> {
            selected = s;
            rebuildList();
            if (onSelect != null) onSelect.accept(s);
        });

        return btn;
    }

    private JPanel buildBottomBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBackground(BG);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));

        bar.add(buildActionButton("+", "Vytvořit server", () -> { if (onCreateServer != null) onCreateServer.run(); }));
        bar.add(Box.createVerticalStrut(4));
        bar.add(buildActionButton("🔗", "Přidat server přes pozvánku", () -> { if (onJoinServer != null) onJoinServer.run(); }));

        return bar;
    }

    private JComponent buildActionButton(String label, String tooltip, Runnable action) {
        JButton btn = new JButton(label) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? HOVER : ADD_BTN);
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setColor(new Color(67, 181, 129));
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                        (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
        btn.setFont(new Font("Arial", Font.BOLD, 18));
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setToolTipText(tooltip);
        btn.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
        btn.setMaximumSize(new Dimension(ICON_SIZE, ICON_SIZE));
        btn.setAlignmentX(CENTER_ALIGNMENT);
        btn.addActionListener(e -> action.run());
        return btn;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) {
            return name.substring(0, Math.min(2, name.length())).toUpperCase();
        }
        return (String.valueOf(words[0].charAt(0)) + words[1].charAt(0)).toUpperCase();
    }
}
