package ui;

import signal.WsEnvelope;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VoicePanel extends JPanel {

    public interface VoicePanelListener {
        void onMuteToggle(boolean muted);
        void onCameraToggle(boolean cameraOn);
        void onScreenShareToggle(boolean streamOn);
        void onLeave();
        default void onSettings() {}
    }

    private static final Color BG          = new Color(43, 45, 49);
    private static final Color HEADER_BG   = new Color(35, 37, 40);
    private static final Color CARD_BG     = new Color(54, 57, 63);
    private static final Color CTRL_BG     = new Color(35, 37, 40);
    private static final Color SPEAK_COLOR = new Color(35, 165, 90);
    private static final Color MUTED_COLOR = new Color(237, 66, 69);
    private static final Color TEXT_FG     = new Color(220, 221, 222);

    private final String channelName;
    private VoicePanelListener listener;

    private final Map<String, ParticipantCard> cards = new LinkedHashMap<>();
    private final JPanel listPanel = new JPanel();

    private final JButton muteBtn     = makeCtrlBtn("🎤", "Ztlumit mikrofon");
    private final JButton camBtn      = makeCtrlBtn("📷", "Zapnout kameru");
    private final JButton shareBtn    = makeCtrlBtn("🖥", "Sdílet obrazovku");
    private final JButton settingsBtn = makeCtrlBtn("⚙", "Nastavení zvuku");
    private final JButton leaveBtn    = makeCtrlBtn("📵", "Odejít z hovoru");

    private boolean selfMuted = false;
    private boolean cameraOn  = false;
    private boolean streamOn  = false;

    public VoicePanel(String channelName) {
        this.channelName = channelName;
        buildUi();
    }

    public void setListener(VoicePanelListener l) { this.listener = l; }

    // ---- participant management (call on EDT) ----

    public void setParticipants(List<WsEnvelope.ParticipantInfo> participants) {
        cards.clear();
        listPanel.removeAll();
        for (WsEnvelope.ParticipantInfo p : participants) {
            appendCard(p.userId, p.username, p.muted);
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    public void addParticipant(String userId, String username) {
        if (cards.containsKey(userId)) return;
        appendCard(userId, username, false);
        listPanel.revalidate();
        listPanel.repaint();
    }

    public void removeParticipant(String userId) {
        ParticipantCard card = cards.remove(userId);
        if (card == null) return;
        int idx = -1;
        for (int i = 0; i < listPanel.getComponentCount(); i++) {
            if (listPanel.getComponent(i) == card) { idx = i; break; }
        }
        listPanel.remove(card);
        if (idx >= 0 && idx < listPanel.getComponentCount()) {
            Component next = listPanel.getComponent(idx);
            if (next instanceof Box.Filler) listPanel.remove(next);
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    public void setSpeaking(String userId, boolean speaking) {
        ParticipantCard c = cards.get(userId);
        if (c != null) c.setSpeaking(speaking);
    }

    public void setMuted(String userId, boolean muted) {
        ParticipantCard c = cards.get(userId);
        if (c != null) c.setMuted(muted);
    }

    public void setAudioLevel(String userId, int level) {
        ParticipantCard c = cards.get(userId);
        if (c != null) c.setAudioLevel(level);
    }

    public void setSelfMuted(boolean muted) {
        selfMuted = muted;
        muteBtn.setText(muted ? "🔇" : "🎤");
        muteBtn.setBackground(muted ? MUTED_COLOR : new Color(88, 101, 242));
    }

    // ---- build UI ----

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(BG);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(HEADER_BG);
        header.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel chanLabel = new JLabel("🔊  " + channelName);
        chanLabel.setForeground(TEXT_FG);
        chanLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        header.add(chanLabel, BorderLayout.WEST);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(BG);
        listPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(null);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        controls.setBackground(CTRL_BG);
        controls.setBorder(new EmptyBorder(4, 4, 4, 4));

        muteBtn.setBackground(new Color(88, 101, 242));
        muteBtn.addActionListener(e -> {
            selfMuted = !selfMuted;
            setSelfMuted(selfMuted);
            if (listener != null) listener.onMuteToggle(selfMuted);
        });

        camBtn.addActionListener(e -> {
            cameraOn = !cameraOn;
            camBtn.setBackground(cameraOn ? SPEAK_COLOR : new Color(66, 70, 77));
            camBtn.setText(cameraOn ? "📹" : "📷");
            if (listener != null) listener.onCameraToggle(cameraOn);
        });

        shareBtn.addActionListener(e -> {
            streamOn = !streamOn;
            shareBtn.setBackground(streamOn ? SPEAK_COLOR : new Color(66, 70, 77));
            if (listener != null) listener.onScreenShareToggle(streamOn);
        });

        settingsBtn.addActionListener(e -> { if (listener != null) listener.onSettings(); });

        leaveBtn.setBackground(MUTED_COLOR);
        leaveBtn.addActionListener(e -> { if (listener != null) listener.onLeave(); });

        controls.add(muteBtn);
        controls.add(camBtn);
        controls.add(shareBtn);
        controls.add(settingsBtn);
        controls.add(leaveBtn);

        add(header,   BorderLayout.NORTH);
        add(scroll,   BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
    }

    private void appendCard(String userId, String username, boolean muted) {
        ParticipantCard card = new ParticipantCard(userId, username, muted);
        cards.put(userId, card);
        listPanel.add(card);
        listPanel.add(Box.createRigidArea(new Dimension(0, 4)));
    }

    private static JButton makeCtrlBtn(String emoji, String tooltip) {
        JButton btn = new JButton(emoji);
        btn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        btn.setForeground(Color.WHITE);
        btn.setBackground(new Color(66, 70, 77));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setPreferredSize(new Dimension(50, 50));
        btn.setToolTipText(tooltip);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        return btn;
    }

    private static Color avatarColor(String userId) {
        Color[] palette = {
            new Color( 88, 101, 242), new Color( 87, 242, 135),
            new Color(254, 231,  92), new Color(235,  69, 158),
            new Color( 80, 200, 120), new Color(255, 136,   0),
            new Color(  0, 185, 255), new Color(180, 100, 255),
        };
        return palette[Math.abs(userId.hashCode()) % palette.length];
    }

    // ---- Participant card (custom-painted) ----

    private static class ParticipantCard extends JPanel {
        private final String username;
        private final Color  aColor;
        private final String initial;
        private volatile boolean speaking;
        private volatile boolean muted;
        private volatile int     audioLevel;

        ParticipantCard(String userId, String username, boolean muted) {
            this.username = username;
            this.aColor   = avatarColor(userId);
            this.initial  = username.isEmpty() ? "?" : String.valueOf(username.charAt(0)).toUpperCase();
            this.muted    = muted;
            setOpaque(false);
            setPreferredSize(new Dimension(0, 58));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
            setMinimumSize(new Dimension(80, 58));
        }

        void setSpeaking(boolean s) { if (speaking != s) { speaking = s; repaint(); } }
        void setMuted(boolean m)    { if (muted    != m) { muted    = m; repaint(); } }
        void setAudioLevel(int l)   { audioLevel = l; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();

            // Card background
            g2.setColor(CARD_BG);
            g2.fillRoundRect(0, 0, w, h, 8, 8);

            // Green left accent strip when speaking
            if (speaking) {
                g2.setColor(SPEAK_COLOR);
                g2.fillRoundRect(0, 0, 5, h, 8, 8);
                g2.fillRect(3, 0, 2, h);
            }

            // Avatar glow + circle
            int avD = 36, ax = 14, ay = (h - avD) / 2;
            if (speaking) {
                g2.setColor(new Color(35, 165, 90, 55));
                g2.fillOval(ax - 4, ay - 4, avD + 8, avD + 8);
            }
            g2.setColor(aColor);
            g2.fillOval(ax, ay, avD, avD);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 15));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(initial,
                ax + avD / 2 - fm.stringWidth(initial) / 2,
                ay + avD / 2 + (fm.getAscent() - fm.getDescent()) / 2);

            // Name + audio bar
            int textX    = ax + avD + 10;
            int iconSlot = muted ? 28 : (speaking ? 24 : 0);
            int textMaxW = Math.max(20, w - textX - iconSlot - 8);
            boolean hasBar = audioLevel > 0 && !muted;

            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            fm = g2.getFontMetrics();
            int nameY = h / 2 + (fm.getAscent() - fm.getDescent()) / 2 - (hasBar ? 5 : 0);

            g2.setColor(speaking ? Color.WHITE : TEXT_FG);
            String disp = username;
            while (fm.stringWidth(disp) > textMaxW && disp.length() > 1)
                disp = disp.substring(0, disp.length() - 1);
            if (disp.length() < username.length()) disp += "…";
            g2.drawString(disp, textX, nameY);

            // Audio level bar
            if (hasBar) {
                int barW = Math.min(textMaxW, 84), barH = 3;
                int barY = nameY + 7;
                g2.setColor(new Color(255, 255, 255, 22));
                g2.fillRoundRect(textX, barY, barW, barH, 2, 2);
                int fill = Math.max(0, Math.min(barW, barW * audioLevel / 100));
                if (fill > 0) {
                    g2.setColor(speaking ? SPEAK_COLOR : new Color(67, 181, 129));
                    g2.fillRoundRect(textX, barY, fill, barH, 2, 2);
                }
            }

            // Right-side icon
            if (muted) {
                int sz = 22, ix = w - sz - 8, iy = (h - sz) / 2;
                g2.setColor(MUTED_COLOR);
                g2.fillOval(ix, iy, sz, sz);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 11));
                fm = g2.getFontMetrics();
                String ico = "🔇";
                g2.drawString(ico, ix + (sz - fm.stringWidth(ico)) / 2,
                              iy + (sz + fm.getAscent() - fm.getDescent()) / 2);
            } else if (speaking) {
                int sz = 20, ix = w - sz - 8, iy = (h - sz) / 2;
                g2.setColor(new Color(35, 165, 90, 150));
                g2.fillOval(ix, iy, sz, sz);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 10));
                fm = g2.getFontMetrics();
                String ico = "🎤";
                g2.drawString(ico, ix + (sz - fm.stringWidth(ico)) / 2,
                              iy + (sz + fm.getAscent() - fm.getDescent()) / 2);
            }

            g2.dispose();
        }
    }
}
