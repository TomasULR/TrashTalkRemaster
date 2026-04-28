package ui.dialogs;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SettingsDialog extends JDialog {
    private JComboBox<String> audioInputCombo;
    private JComboBox<String> audioOutputCombo;
    private JComboBox<String> videoSourceCombo;
    private JComboBox<String> resolutionCombo;
    
    private String selectedInput;
    private String selectedOutput;
    private boolean saved = false;

    public SettingsDialog(Window owner) {
        super(owner, "Nastavení (Settings)", ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout());
        
        JPanel panel = new JPanel(new GridLayout(5, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("Vstupní zvukové zařízení (Mikrofon):"));
        audioInputCombo = new JComboBox<>(getAudioDevices(true));
        panel.add(audioInputCombo);

        panel.add(new JLabel("Výstupní zvukové zařízení (Sluchátka):"));
        audioOutputCombo = new JComboBox<>(getAudioDevices(false));
        panel.add(audioOutputCombo);

        panel.add(new JLabel("Zdroj obrazu (Kamera/Obrazovka):"));
        videoSourceCombo = new JComboBox<>(new String[]{"Kamera", "Sdílení Obrazovky (1080p 60fps)"});
        panel.add(videoSourceCombo);

        panel.add(new JLabel("Rozlišení:"));
        resolutionCombo = new JComboBox<>(new String[]{"1920x1080", "1280x720", "640x480"});
        panel.add(resolutionCombo);

        JButton saveBtn = new JButton("Uložit");
        saveBtn.addActionListener(e -> {
            selectedInput = (String) audioInputCombo.getSelectedItem();
            selectedOutput = (String) audioOutputCombo.getSelectedItem();
            saved = true;
            // TODO: sync with backend
            dispose();
        });
        panel.add(new JLabel());
        panel.add(saveBtn);

        add(panel, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(owner);
    }
    
    public boolean isSaved() { return saved; }
    public String getAudioInput() { return selectedInput; }
    public String getAudioOutput() { return selectedOutput; }
    public String getVideoSource() { return (String) videoSourceCombo.getSelectedItem(); }

    private String[] getAudioDevices(boolean input) {
        List<String> list = new ArrayList<>();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixers) {
            Mixer m = AudioSystem.getMixer(mixerInfo);
            if (input && m.getTargetLineInfo().length > 0) {
                list.add(mixerInfo.getName());
            } else if (!input && m.getSourceLineInfo().length > 0) {
                list.add(mixerInfo.getName());
            }
        }
        if (list.isEmpty()) list.add("Výchozí systémové zařízení");
        return list.toArray(new String[0]);
    }
}
