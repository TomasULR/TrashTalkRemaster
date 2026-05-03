package ui.dialogs;

import media.AudioCapture;
import media.AudioPlayback;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SettingsDialog extends JDialog {

    private final JComboBox<String> audioInputCombo;
    private final JComboBox<String> audioOutputCombo;
    private final JComboBox<String> videoSourceCombo;
    private final JComboBox<String> resolutionCombo;

    private final JProgressBar micLevelBar;
    private final JLabel       micDot;

    private AudioCapture previewCapture;

    private String  selectedInput;
    private String  selectedOutput;
    private boolean saved = false;

    public SettingsDialog(Window owner) {
        super(owner, "Nastavení", ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout());

        // ── Audio section ──────────────────────────────────────────
        JPanel audioPanel = new JPanel(new GridBagLayout());
        audioPanel.setBorder(BorderFactory.createTitledBorder("Zvuk"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.fill   = GridBagConstraints.HORIZONTAL;

        // Mic label + combo
        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        audioPanel.add(new JLabel("Mikrofon:"), g);
        g.gridx = 1; g.weightx = 1.0; g.gridwidth = 2;
        audioInputCombo = new JComboBox<>(getAudioDevices(true));
        audioPanel.add(audioInputCombo, g);

        // Mic level indicator row
        g.gridwidth = 1;
        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        micDot = new JLabel("●");
        micDot.setFont(micDot.getFont().deriveFont(16f));
        micDot.setForeground(Color.GRAY);
        audioPanel.add(micDot, g);

        g.gridx = 1; g.weightx = 1.0; g.gridwidth = 2;
        micLevelBar = new JProgressBar(0, 100);
        micLevelBar.setPreferredSize(new Dimension(220, 10));
        micLevelBar.setForeground(new Color(67, 181, 129));
        micLevelBar.setBorderPainted(false);
        audioPanel.add(micLevelBar, g);

        // Speaker label + combo
        g.gridwidth = 1;
        g.gridx = 0; g.gridy = 2; g.weightx = 0;
        audioPanel.add(new JLabel("Sluchátka:"), g);
        g.gridx = 1; g.weightx = 1.0; g.gridwidth = 2;
        audioOutputCombo = new JComboBox<>(getAudioDevices(false));
        audioPanel.add(audioOutputCombo, g);

        // Speaker test button
        g.gridwidth = 1;
        g.gridx = 1; g.gridy = 3; g.weightx = 1.0;
        JButton testBtn = new JButton("▶  Test zvuku");
        testBtn.addActionListener(e -> {
            testBtn.setEnabled(false);
            AudioPlayback.playTestTone((String) audioOutputCombo.getSelectedItem());
            Timer t = new Timer(1300, ev -> testBtn.setEnabled(true));
            t.setRepeats(false);
            t.start();
        });
        audioPanel.add(testBtn, g);

        // ── Video section ──────────────────────────────────────────
        JPanel videoPanel = new JPanel(new GridBagLayout());
        videoPanel.setBorder(BorderFactory.createTitledBorder("Video"));
        GridBagConstraints gv = new GridBagConstraints();
        gv.insets = new Insets(4, 6, 4, 6);
        gv.fill   = GridBagConstraints.HORIZONTAL;

        gv.gridx = 0; gv.gridy = 0; gv.weightx = 0;
        videoPanel.add(new JLabel("Zdroj obrazu:"), gv);
        gv.gridx = 1; gv.weightx = 1.0;
        videoSourceCombo = new JComboBox<>(new String[]{"Kamera", "Sdílení obrazovky (1080p 60fps)"});
        videoPanel.add(videoSourceCombo, gv);

        gv.gridx = 0; gv.gridy = 1; gv.weightx = 0;
        videoPanel.add(new JLabel("Rozlišení:"), gv);
        gv.gridx = 1; gv.weightx = 1.0;
        resolutionCombo = new JComboBox<>(new String[]{"1920x1080", "1280x720", "640x480"});
        videoPanel.add(resolutionCombo, gv);

        // ── Main content ───────────────────────────────────────────
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(10, 10, 4, 10));
        content.add(audioPanel);
        content.add(Box.createVerticalStrut(8));
        content.add(videoPanel);
        add(content, BorderLayout.CENTER);

        // ── Buttons ────────────────────────────────────────────────
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton cancelBtn = new JButton("Zrušit");
        cancelBtn.addActionListener(e -> dispose());
        JButton saveBtn = new JButton("Uložit");
        saveBtn.addActionListener(e -> {
            selectedInput  = (String) audioInputCombo.getSelectedItem();
            selectedOutput = (String) audioOutputCombo.getSelectedItem();
            saved = true;
            dispose();
        });
        btnRow.add(cancelBtn);
        btnRow.add(saveBtn);
        add(btnRow, BorderLayout.SOUTH);

        // Restart preview when device changes
        audioInputCombo.addActionListener(e -> restartMicPreview());

        pack();
        setMinimumSize(new Dimension(400, getHeight()));
        setLocationRelativeTo(owner);

        restartMicPreview();
    }

    // ── Mic preview ────────────────────────────────────────────────

    private void restartMicPreview() {
        stopMicPreview();
        String device = (String) audioInputCombo.getSelectedItem();
        try {
            previewCapture = AudioCapture.startPreview(device, level -> SwingUtilities.invokeLater(() -> {
                micLevelBar.setValue(level);
                if (level > 40) {
                    micDot.setForeground(new Color(67, 181, 129));   // green — speaking
                } else if (level > 5) {
                    micDot.setForeground(new Color(250, 166, 26));   // yellow — quiet input
                } else {
                    micDot.setForeground(Color.GRAY);                // silent
                }
            }));
        } catch (Exception ex) {
            micDot.setForeground(Color.RED);
            micDot.setToolTipText("Chyba: " + ex.getMessage());
        }
    }

    private void stopMicPreview() {
        if (previewCapture != null) {
            try { previewCapture.stop(); } catch (Exception ignored) {}
            previewCapture = null;
        }
    }

    @Override
    public void dispose() {
        stopMicPreview();
        super.dispose();
    }

    // ── Getters ────────────────────────────────────────────────────

    public boolean isSaved()         { return saved; }
    public String  getAudioInput()   { return selectedInput; }
    public String  getAudioOutput()  { return selectedOutput; }
    public String  getVideoSource()  { return (String) videoSourceCombo.getSelectedItem(); }

    private String[] getAudioDevices(boolean input) {
        List<String> list = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(mi);
            if (input  && m.getTargetLineInfo().length > 0) list.add(mi.getName());
            if (!input && m.getSourceLineInfo().length > 0) list.add(mi.getName());
        }
        if (list.isEmpty()) list.add("Výchozí systémové zařízení");
        return list.toArray(new String[0]);
    }
}
