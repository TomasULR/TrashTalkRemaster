package ui.dialogs;

import javax.swing.*;
import java.awt.*;

public class CreateChannelDialog extends JDialog {

    private String resultName = null;
    private String resultType = null;

    public CreateChannelDialog(Frame owner) {
        super(owner, "Vytvořit kanál", true);
        buildUi();
    }

    private void buildUi() {
        setSize(380, 200);
        setResizable(false);
        setLocationRelativeTo(getOwner());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        JTextField nameField = new JTextField(18);
        JRadioButton textBtn  = new JRadioButton("Text", true);
        JRadioButton voiceBtn = new JRadioButton("Voice");
        ButtonGroup typeGroup = new ButtonGroup();
        typeGroup.add(textBtn);
        typeGroup.add(voiceBtn);

        JButton ok = new JButton("Vytvořit");
        JButton cancel = new JButton("Zrušit");

        c.gridx = 0; c.gridy = 0; c.weightx = 0.35; panel.add(new JLabel("Název:"), c);
        c.gridx = 1; c.gridy = 0; c.weightx = 0.65; panel.add(nameField, c);
        c.gridx = 0; c.gridy = 1; panel.add(new JLabel("Typ:"), c);
        JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        typePanel.setOpaque(false);
        typePanel.add(textBtn);
        typePanel.add(voiceBtn);
        c.gridx = 1; c.gridy = 1; panel.add(typePanel, c);
        c.gridx = 0; c.gridy = 2; panel.add(cancel, c);
        c.gridx = 1; c.gridy = 2; panel.add(ok, c);

        ok.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { JOptionPane.showMessageDialog(this, "Název nesmí být prázdný."); return; }
            resultName = name;
            resultType = voiceBtn.isSelected() ? "VOICE" : "TEXT";
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        add(panel);
    }

    public String getResultName() { return resultName; }
    public String getResultType() { return resultType; }
}
