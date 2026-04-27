package ui.dialogs;

import javax.swing.*;
import java.awt.*;

public class JoinServerDialog extends JDialog {

    private String resultCode = null;

    public JoinServerDialog(Frame owner) {
        super(owner, "Přidat server přes pozvánku", true);
        buildUi();
    }

    private void buildUi() {
        setSize(380, 150);
        setResizable(false);
        setLocationRelativeTo(getOwner());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        JTextField codeField = new JTextField(18);
        codeField.setToolTipText("Zadej 8-místný kód pozvánky");
        JButton ok = new JButton("Přidat");
        JButton cancel = new JButton("Zrušit");

        c.gridx = 0; c.gridy = 0; c.weightx = 0.35; panel.add(new JLabel("Kód pozvánky:"), c);
        c.gridx = 1; c.gridy = 0; c.weightx = 0.65; panel.add(codeField, c);
        c.gridx = 0; c.gridy = 1; panel.add(cancel, c);
        c.gridx = 1; c.gridy = 1; panel.add(ok, c);

        ok.addActionListener(e -> {
            String code = codeField.getText().trim();
            if (code.isEmpty()) { JOptionPane.showMessageDialog(this, "Zadej kód pozvánky."); return; }
            resultCode = code;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        codeField.addActionListener(e -> ok.doClick());

        add(panel);
    }

    public String getResultCode() { return resultCode; }
}
