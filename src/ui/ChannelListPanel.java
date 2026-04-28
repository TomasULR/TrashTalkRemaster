package ui;

import model.ChannelInfo;
import model.ServerInfo;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Druhý panel (~240px) — seznam kanálů pro vybraný server.
 * Kanály rozděleny do sekcí TEXT CHANNELS a VOICE CHANNELS.
 * Nahoře název serveru + tlačítko nastavení (jen ADMIN/OWNER).
 */
public class ChannelListPanel extends JPanel {

    private static final Color BG          = new Color(47, 49, 54);
    private static final Color SECTION_FG  = new Color(142, 146, 151);
    private static final Color CHANNEL_FG  = new Color(185, 187, 190);
    private static final Color SELECTED_BG = new Color(64, 68, 75);
    private static final Color HOVER_BG    = new Color(58, 60, 67);

    private final JLabel serverNameLabel;
    private final JButton settingsButton;
    private final JPanel channelContainer;

    private ServerInfo currentServer;
    private ChannelInfo selectedChannel;
    private final List<ChannelInfo> channels = new ArrayList<>();

    private Consumer<ChannelInfo> onSelectChannel;
    private Runnable onOpenServerSettings;
    private Runnable onCreateChannel;

    public ChannelListPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(240, 0));
        setBackground(BG);

        // Header: název serveru + gear ikona
        serverNameLabel = new JLabel("Vyber server");
        serverNameLabel.setForeground(Color.WHITE);
        serverNameLabel.setFont(new Font("Arial", Font.BOLD, 15));
        serverNameLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));

        settingsButton = new JButton("⚙");
        settingsButton.setForeground(SECTION_FG);
        settingsButton.setFont(new Font("Arial", Font.PLAIN, 16));
        settingsButton.setBorderPainted(false);
        settingsButton.setContentAreaFilled(false);
        settingsButton.setFocusPainted(false);
        settingsButton.setToolTipText("Nastavení serveru");
        settingsButton.setVisible(false);
        settingsButton.addActionListener(e -> { if (onOpenServerSettings != null) onOpenServerSettings.run(); });

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(42, 44, 48));
        header.setPreferredSize(new Dimension(0, 48));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 1, 0));
        header.add(serverNameLabel, BorderLayout.CENTER);
        header.add(settingsButton, BorderLayout.EAST);

        channelContainer = new JPanel();
        channelContainer.setLayout(new BoxLayout(channelContainer, BoxLayout.Y_AXIS));
        channelContainer.setBackground(BG);

        JScrollPane scroll = new JScrollPane(channelContainer,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(12);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    public void setOnSelectChannel(Consumer<ChannelInfo> h)  { this.onSelectChannel = h; }
    public void setOnOpenServerSettings(Runnable h)          { this.onOpenServerSettings = h; }
    public void setOnCreateChannel(Runnable h)               { this.onCreateChannel = h; }

    public void loadServer(ServerInfo server, List<ChannelInfo> channelList) {
        this.currentServer = server;
        this.channels.clear();
        this.channels.addAll(channelList);
        this.selectedChannel = null;

        serverNameLabel.setText(server.name());

        boolean canManage = "OWNER".equals(server.myRole()) || "ADMINISTRATOR".equals(server.myRole());
        settingsButton.setVisible(canManage);

        rebuild(canManage);
    }

    public void clear() {
        currentServer = null;
        selectedChannel = null;
        channels.clear();
        serverNameLabel.setText("Vyber server");
        settingsButton.setVisible(false);
        channelContainer.removeAll();
        channelContainer.revalidate();
        channelContainer.repaint();
    }

    public void addChannel(ChannelInfo c) {
        channels.add(c);
        rebuild(isAdmin());
    }

    public void removeChannel(ChannelInfo c) {
        channels.removeIf(x -> x.id().equals(c.id()));
        if (selectedChannel != null && selectedChannel.id().equals(c.id())) selectedChannel = null;
        rebuild(isAdmin());
    }

    private boolean isAdmin() {
        return currentServer != null &&
                ("OWNER".equals(currentServer.myRole()) || "ADMINISTRATOR".equals(currentServer.myRole()));
    }

    private void rebuild(boolean canManage) {
        channelContainer.removeAll();
        channelContainer.add(Box.createVerticalStrut(8));

        List<ChannelInfo> textChannels  = channels.stream().filter(ChannelInfo::isText).toList();
        List<ChannelInfo> voiceChannels = channels.stream().filter(ChannelInfo::isVoice).toList();

        addSection("TEXT CHANNELS", textChannels, canManage);
        addSection("VOICE CHANNELS", voiceChannels, canManage);

        channelContainer.add(Box.createVerticalGlue());
        channelContainer.revalidate();
        channelContainer.repaint();
    }

    private void addSection(String title, List<ChannelInfo> list, boolean canManage) {
        JPanel sectionHeader = new JPanel(new BorderLayout());
        sectionHeader.setBackground(BG);
        sectionHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        sectionHeader.setBorder(BorderFactory.createEmptyBorder(8, 8, 2, 8));

        JLabel sectionLabel = new JLabel(title);
        sectionLabel.setFont(new Font("Arial", Font.BOLD, 11));
        sectionLabel.setForeground(SECTION_FG);
        sectionHeader.add(sectionLabel, BorderLayout.CENTER);

        if (canManage) {
            JButton addBtn = new JButton("+");
            addBtn.setFont(new Font("Arial", Font.BOLD, 14));
            addBtn.setForeground(SECTION_FG);
            addBtn.setBorderPainted(false);
            addBtn.setContentAreaFilled(false);
            addBtn.setFocusPainted(false);
            addBtn.setToolTipText("Přidat kanál");
            addBtn.addActionListener(e -> { if (onCreateChannel != null) onCreateChannel.run(); });
            sectionHeader.add(addBtn, BorderLayout.EAST);
        }

        channelContainer.add(sectionHeader);

        for (ChannelInfo c : list) {
            channelContainer.add(buildChannelRow(c));
        }

        if (list.isEmpty()) {
            JLabel empty = new JLabel("  Žádné kanály");
            empty.setForeground(new Color(100, 100, 110));
            empty.setFont(new Font("Arial", Font.ITALIC, 12));
            empty.setBorder(BorderFactory.createEmptyBorder(2, 16, 2, 8));
            empty.setAlignmentX(LEFT_ALIGNMENT);
            channelContainer.add(empty);
        }
    }

    private JComponent buildChannelRow(ChannelInfo c) {
        boolean isSelected = selectedChannel != null && selectedChannel.id().equals(c.id());
        String prefix = c.isVoice() ? "🔊 " : "# ";

        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(isSelected ? SELECTED_BG : getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        row.setOpaque(false);
        row.setBackground(BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel label = new JLabel(prefix + c.name());
        label.setForeground(isSelected ? Color.WHITE : CHANNEL_FG);
        label.setFont(new Font("Arial", Font.PLAIN, 14));
        label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        row.add(label, BorderLayout.CENTER);

        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                if (!isSelected) row.setBackground(HOVER_BG);
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                if (!isSelected) row.setBackground(BG);
            }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                selectedChannel = c;
                rebuild(isAdmin());
                if (onSelectChannel != null) onSelectChannel.accept(c);
            }
        });

        return row;
    }
}
