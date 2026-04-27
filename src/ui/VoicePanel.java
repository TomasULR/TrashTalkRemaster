package ui;

import signal.WsEnvelope;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel hlasového kanálu — zobrazuje účastníky, indikátor mluvení,
 * tlačítka Ztlumit/Odztlumit a Odejít.
 */
public class VoicePanel extends JPanel {

    public interface VoicePanelListener {
        void onMuteToggle(boolean muted);
        void onCameraToggle(boolean cameraOn);
        void onLeave();
    }

    private static final Color BG          = new Color(47, 49, 54);
    private static final Color ROW_BG      = new Color(54, 57, 63);
    private static final Color SPEAKING_BG = new Color(35, 100, 70);
    private static final Color TEXT_COLOR  = new Color(220, 221, 222);

    private final String channelName;
    private VoicePanelListener listener;

    // userId → participantRow
    private final Map<String, ParticipantRow> rows = new LinkedHashMap<>();
    private final JPanel participantsPanel = new JPanel();
    private final JButton muteButton   = new JButton("Ztlumit mikrofon");
    private final JButton cameraButton = new JButton("Zapnout kameru");
    private final JButton leaveButton  = new JButton("Odejít");
    private boolean selfMuted   = false;
    private boolean cameraOn    = false;

    public VoicePanel(String channelName) {
        this.channelName = channelName;
        buildUi();
    }

    public void setListener(VoicePanelListener listener) {
        this.listener = listener;
    }

    // ---- participant management (call on EDT) ----

    public void setParticipants(List<WsEnvelope.ParticipantInfo> participants) {
        rows.clear();
        participantsPanel.removeAll();
        for (WsEnvelope.ParticipantInfo p : participants) addParticipantRow(p.userId, p.username, p.muted);
        participantsPanel.revalidate();
        participantsPanel.repaint();
    }

    public void addParticipant(String userId, String username) {
        if (rows.containsKey(userId)) return;
        addParticipantRow(userId, username, false);
        participantsPanel.revalidate();
        participantsPanel.repaint();
    }

    public void removeParticipant(String userId) {
        ParticipantRow row = rows.remove(userId);
        if (row != null) {
            participantsPanel.remove(row.panel);
            participantsPanel.revalidate();
            participantsPanel.repaint();
        }
    }

    public void setSpeaking(String userId, boolean speaking) {
        ParticipantRow row = rows.get(userId);
        if (row != null) row.setSpeaking(speaking);
    }

    public void setMuted(String userId, boolean muted) {
        ParticipantRow row = rows.get(userId);
        if (row != null) row.setMuted(muted);
    }

    public void setSelfMuted(boolean muted) {
        selfMuted = muted;
        muteButton.setText(muted ? "Odztlumit mikrofon" : "Ztlumit mikrofon");
    }

    public void setCameraOn(boolean on) {
        cameraOn = on;
        cameraButton.setText(on ? "Vypnout kameru" : "Zapnout kameru");
        cameraButton.setBackground(on ? new Color(67, 181, 129) : new Color(66, 70, 77));
    }

    // ---- build UI ----

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(BG);

        // header
        JLabel header = new JLabel(" 🔊 " + channelName, SwingConstants.LEFT);
        header.setForeground(TEXT_COLOR);
        header.setFont(new Font("Arial", Font.BOLD, 14));
        header.setBorder(new EmptyBorder(12, 12, 8, 12));
        header.setOpaque(true);
        header.setBackground(new Color(40, 43, 48));

        // participants list
        participantsPanel.setLayout(new BoxLayout(participantsPanel, BoxLayout.Y_AXIS));
        participantsPanel.setBackground(BG);
        JScrollPane scroll = new JScrollPane(participantsPanel);
        scroll.setBorder(null);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);

        // bottom controls
        muteButton.setBackground(new Color(66, 70, 77));
        muteButton.setForeground(TEXT_COLOR);
        muteButton.setFocusPainted(false);
        muteButton.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        muteButton.addActionListener(e -> {
            selfMuted = !selfMuted;
            setSelfMuted(selfMuted);
            if (listener != null) listener.onMuteToggle(selfMuted);
        });

        cameraButton.setBackground(new Color(66, 70, 77));
        cameraButton.setForeground(TEXT_COLOR);
        cameraButton.setFocusPainted(false);
        cameraButton.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        cameraButton.addActionListener(e -> {
            cameraOn = !cameraOn;
            setCameraOn(cameraOn);
            if (listener != null) listener.onCameraToggle(cameraOn);
        });

        leaveButton.setBackground(new Color(237, 66, 69));
        leaveButton.setForeground(Color.WHITE);
        leaveButton.setFocusPainted(false);
        leaveButton.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        leaveButton.addActionListener(e -> { if (listener != null) listener.onLeave(); });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        controls.setBackground(new Color(40, 43, 48));
        controls.add(muteButton);
        controls.add(cameraButton);
        controls.add(leaveButton);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
    }

    private void addParticipantRow(String userId, String username, boolean muted) {
        ParticipantRow row = new ParticipantRow(username, muted);
        rows.put(userId, row);
        participantsPanel.add(row.panel);
    }

    // ---- inner participant row ----

    private static class ParticipantRow {
        final JPanel panel;
        final JLabel nameLabel;
        final JLabel statusLabel;

        ParticipantRow(String username, boolean muted) {
            panel = new JPanel(new BorderLayout());
            panel.setBackground(ROW_BG);
            panel.setBorder(new EmptyBorder(6, 12, 6, 12));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

            nameLabel = new JLabel("●  " + username);
            nameLabel.setForeground(new Color(220, 221, 222));
            nameLabel.setFont(new Font("Arial", Font.PLAIN, 13));

            statusLabel = new JLabel(muted ? "🔇" : "🎤");
            statusLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));

            panel.add(nameLabel, BorderLayout.CENTER);
            panel.add(statusLabel, BorderLayout.EAST);
        }

        void setSpeaking(boolean speaking) {
            SwingUtilities.invokeLater(() ->
                panel.setBackground(speaking ? SPEAKING_BG : ROW_BG));
        }

        void setMuted(boolean muted) {
            SwingUtilities.invokeLater(() ->
                statusLabel.setText(muted ? "🔇" : "🎤"));
        }
    }
}
