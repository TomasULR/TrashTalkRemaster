import com.formdev.flatlaf.FlatDarkLaf;
import net.ApiClient;

import javax.swing.*;

public class Main {

    // Adresa backendu — v produkci změnit na false (validace TLS certu)
    private static final String SERVER_URL = "https://localhost:25565";
    private static final boolean TRUST_ALL_CERTS_DEV = true;

    public static void main(String[] args) {
        // FlatLaf tmavý theme před prvním oknem
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception e) {
            System.err.println("FlatLaf se nepodařilo načíst, používám výchozí L&F");
        }

        ApiClient apiClient = new ApiClient(SERVER_URL, TRUST_ALL_CERTS_DEV);

        SwingUtilities.invokeLater(() -> {
            AuthPanel authPanel = new AuthPanel(apiClient);
            authPanel.setVisible(true);
        });
    }
}
