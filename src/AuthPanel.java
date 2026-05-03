import auth.AuthApiService;
import auth.Session;
import net.ApiClient;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class AuthPanel extends JFrame {

    private final boolean trustAllCerts;
    private ApiClient apiClient;
    private AuthApiService authService;

    // Server URL field
    private JTextField serverUrlField;

    // Login fields
    private JTextField loginUsernameField;
    private JPasswordField loginPasswordField;
    private JButton loginButton;
    private JLabel loginStatusLabel;

    // Register fields
    private JTextField regUsernameField;
    private JTextField regEmailField;
    private JPasswordField regPasswordField;
    private JPasswordField regPasswordConfirmField;
    private JButton registerButton;
    private JLabel regStatusLabel;

    public AuthPanel(String defaultServerUrl, boolean trustAllCerts) {
        this.trustAllCerts = trustAllCerts;
        this.apiClient     = new ApiClient(defaultServerUrl, trustAllCerts);
        this.authService   = new AuthApiService(apiClient);
        buildUi();
    }

    private void refreshClient() {
        String url = serverUrlField.getText().trim();
        if (!url.equals(apiClient.getBaseUrl())) {
            apiClient   = new ApiClient(url, trustAllCerts);
            authService = new AuthApiService(apiClient);
        }
    }

    private void buildUi() {
        setTitle("TrashTalk — Přihlášení");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(420, 360);
        setResizable(false);
        setLocationRelativeTo(null);

        JLabel title = new JLabel("TRASHTALK", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));
        title.setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0));

        // Server URL row
        JPanel serverRow = new JPanel(new BorderLayout(6, 0));
        serverRow.setBorder(BorderFactory.createEmptyBorder(0, 20, 8, 20));
        JLabel serverLabel = new JLabel("Server:");
        serverRow.add(serverLabel, BorderLayout.WEST);
        serverUrlField = new JTextField(apiClient.getBaseUrl());
        serverUrlField.setToolTipText("Adresa serveru TrashTalk — změň pro připojení k jinému serveru");
        serverRow.add(serverUrlField, BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(title, BorderLayout.NORTH);
        topPanel.add(serverRow, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Přihlášení", buildLoginTab());
        tabs.addTab("Registrace", buildRegisterTab());

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(topPanel, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
    }

    // ---- Login tab ----

    private JPanel buildLoginTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 24, 12, 24));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        loginUsernameField = new JTextField(18);
        loginPasswordField = new JPasswordField(18);
        loginButton        = new JButton("Přihlásit se");
        loginStatusLabel   = statusLabel();

        c.gridx = 0; c.gridy = 0; c.weightx = 0.3; panel.add(new JLabel("Jméno / e-mail:"), c);
        c.gridx = 1; c.gridy = 0; c.weightx = 0.7; panel.add(loginUsernameField, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0.3; panel.add(new JLabel("Heslo:"), c);
        c.gridx = 1; c.gridy = 1; c.weightx = 0.7; panel.add(loginPasswordField, c);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.weightx = 1.0;
        panel.add(loginButton, c);
        c.gridy = 3; panel.add(loginStatusLabel, c);

        loginButton.addActionListener(e -> doLogin());
        loginPasswordField.addActionListener(e -> doLogin());

        return panel;
    }

    private void doLogin() {
        refreshClient();
        String username = loginUsernameField.getText().trim();
        String password = new String(loginPasswordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            loginStatusLabel.setText("Vyplň všechna pole.");
            return;
        }

        setLoginBusy(true);
        loginStatusLabel.setText("Přihlašuji…");

        new SwingWorker<AuthApiService.AuthResponse, Void>() {
            @Override
            protected AuthApiService.AuthResponse doInBackground() throws Exception {
                return authService.login(username, password);
            }

            @Override
            protected void done() {
                setLoginBusy(false);
                try {
                    applySession(get());
                } catch (ExecutionException ex) {
                    loginStatusLabel.setText(friendlyError(ex.getCause()));
                } catch (Exception ex) {
                    loginStatusLabel.setText("Neočekávaná chyba.");
                }
            }
        }.execute();
    }

    private void setLoginBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        loginUsernameField.setEnabled(!busy);
        loginPasswordField.setEnabled(!busy);
    }

    // ---- Register tab ----

    private JPanel buildRegisterTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 24, 12, 24));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        regUsernameField      = new JTextField(18);
        regEmailField         = new JTextField(18);
        regPasswordField      = new JPasswordField(18);
        regPasswordConfirmField = new JPasswordField(18);
        registerButton        = new JButton("Zaregistrovat se");
        regStatusLabel        = statusLabel();

        String[][] rows = {
                {"Uživatelské jméno:", null},
                {"E-mail:", null},
                {"Heslo:", null},
                {"Heslo znovu:", null}
        };
        JComponent[] fields = {
                regUsernameField, regEmailField, regPasswordField, regPasswordConfirmField
        };
        for (int i = 0; i < rows.length; i++) {
            c.gridx = 0; c.gridy = i; c.weightx = 0.35; panel.add(new JLabel(rows[i][0]), c);
            c.gridx = 1; c.gridy = i; c.weightx = 0.65; panel.add(fields[i], c);
        }
        c.gridx = 0; c.gridy = rows.length; c.gridwidth = 2; c.weightx = 1.0;
        panel.add(registerButton, c);
        c.gridy = rows.length + 1; panel.add(regStatusLabel, c);

        registerButton.addActionListener(e -> doRegister());

        return panel;
    }

    private void doRegister() {
        refreshClient();
        String username = regUsernameField.getText().trim();
        String email    = regEmailField.getText().trim();
        String password = new String(regPasswordField.getPassword());
        String confirm  = new String(regPasswordConfirmField.getPassword());

        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            regStatusLabel.setText("Vyplň všechna pole.");
            return;
        }
        if (!password.equals(confirm)) {
            regStatusLabel.setText("Hesla se neshodují.");
            return;
        }
        if (password.length() < 8) {
            regStatusLabel.setText("Heslo musí mít alespoň 8 znaků.");
            return;
        }

        setRegisterBusy(true);
        regStatusLabel.setText("Registruji…");

        new SwingWorker<AuthApiService.AuthResponse, Void>() {
            @Override
            protected AuthApiService.AuthResponse doInBackground() throws Exception {
                return authService.register(username, email, password);
            }

            @Override
            protected void done() {
                setRegisterBusy(false);
                try {
                    applySession(get());
                } catch (ExecutionException ex) {
                    regStatusLabel.setText(friendlyError(ex.getCause()));
                } catch (Exception ex) {
                    regStatusLabel.setText("Neočekávaná chyba.");
                }
            }
        }.execute();
    }

    private void setRegisterBusy(boolean busy) {
        registerButton.setEnabled(!busy);
        regUsernameField.setEnabled(!busy);
        regEmailField.setEnabled(!busy);
        regPasswordField.setEnabled(!busy);
        regPasswordConfirmField.setEnabled(!busy);
    }

    // ---- Common ----

    private void applySession(AuthApiService.AuthResponse resp) {
        Session.get().set(
                apiClient,
                resp.accessToken(), resp.refreshToken(),
                resp.userId(),
                resp.username(), resp.displayName(), resp.avatarUrl()
        );
        dispose();
        new TrashTalkMainPanel(apiClient).setVisible(true);
    }

    private String friendlyError(Throwable ex) {
        if (ex instanceof ApiClient.ApiException ae) {
            return switch (ae.statusCode) {
                case 401 -> "Neplatné přihlašovací údaje.";
                case 409 -> "Uživatel nebo e-mail již existuje.";
                case 400 -> "Neplatná data: " + ae.getMessage();
                default  -> "Chyba serveru (" + ae.statusCode + "): " + ae.getMessage();
            };
        }
        if (ex instanceof IOException) {
            return "Nelze se připojit k serveru.";
        }
        return "Chyba: " + ex.getMessage();
    }

    private JLabel statusLabel() {
        JLabel label = new JLabel(" ", SwingConstants.CENTER);
        label.setForeground(Color.RED);
        return label;
    }
}
