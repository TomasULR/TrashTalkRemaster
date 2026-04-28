package ui.dialogs;

import javax.swing.*;
import java.awt.*;

public class CreateServerDialog extends JDialog {

    private String resultName = null;

    public CreateServerDialog(Frame owner) {
        super(owner, "Vytvořit server", true);
        buildUi();
    }

    private void buildUi() {
        setSize(360, 160);
        setResizable(false);
        setLocationRelativeTo(getOwner());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        JTextField nameField = new JTextField(20);
        JButton ok = new JButton("Vytvořit");
        JButton cancel = new JButton("Zrušit");

        c.gridx = 0; c.gridy = 0; c.weightx = 0.3; panel.add(new JLabel("Název serveru:"), c);
        c.gridx = 1; c.gridy = 0; c.weightx = 0.7; panel.add(nameField, c);
        c.gridx = 0; c.gridy = 1; c.gridwidth = 1; panel.add(cancel, c);
        c.gridx = 1; c.gridy = 1; panel.add(ok, c);

        ok.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { JOptionPane.showMessageDialog(this, "Název nesmí být prázdný."); return; }
            resultName = name;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        nameField.addActionListener(e -> ok.doClick());

        add(panel);
    }

    /** Vrací název nebo null, pokud uživatel zrušil. */
    public String getResultName() { return resultName; }
}
