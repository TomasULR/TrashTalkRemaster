import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;

public class Main {

    // Výchozí adresa backendu — uživatel ji může přepsat v přihlašovací obrazovce
    private static final String  DEFAULT_SERVER_URL  = "https://localhost:25565";
    private static final boolean TRUST_ALL_CERTS_DEV = true;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception e) {
            System.err.println("FlatLaf se nepodařilo načíst, používám výchozí L&F");
        }

        SwingUtilities.invokeLater(() ->
            new AuthPanel(DEFAULT_SERVER_URL, TRUST_ALL_CERTS_DEV).setVisible(true)
        );
    }
}
