package org.multiuserwordeditor.ui;

import org.multiuserwordeditor.model.Message;
import org.multiuserwordeditor.network.NetworkManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class LoginWindow extends JFrame {
    private NetworkManager networkManager;
    private JTextField hostField;
    private JTextField portField;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JButton connectButton;
    private JButton registerButton;
    private JButton loginButton;
    private JLabel statusLabel;
    private MainWindow mainWindow;

    public LoginWindow(NetworkManager networkManager) {
        super("Giriş Ekranı");
        this.networkManager = networkManager;
        initialize();
        setupNetworkManager();
    }

    private void initialize() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(400, 350);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Ana panel
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Bağlantı paneli
        JPanel connectionPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        connectionPanel.setBorder(BorderFactory.createTitledBorder("Sunucu Bağlantısı"));

        hostField = new JTextField("localhost", 15);
        portField = new JTextField("12345", 15);
        usernameField = new JTextField(15);
        passwordField = new JPasswordField(15);
        connectButton = new JButton("Bağlan");
        registerButton = new JButton("Kayıt Ol");
        loginButton = new JButton("Giriş Yap");

        // Bağlantı alanları
        connectionPanel.add(new JLabel("Sunucu:"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel("Port:"));
        connectionPanel.add(portField);
        mainPanel.add(connectionPanel);
        mainPanel.add(Box.createVerticalStrut(20));

        // Kullanıcı paneli
        JPanel userPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        userPanel.setBorder(BorderFactory.createTitledBorder("Kullanıcı Bilgileri"));
        userPanel.add(new JLabel("Kullanıcı Adı:"));
        userPanel.add(usernameField);
        userPanel.add(new JLabel("Şifre:"));
        userPanel.add(passwordField);
        mainPanel.add(userPanel);
        mainPanel.add(Box.createVerticalStrut(20));

        // Buton paneli
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttonPanel.add(connectButton);
        buttonPanel.add(registerButton);
        buttonPanel.add(loginButton);
        mainPanel.add(buttonPanel);

        add(mainPanel, BorderLayout.CENTER);

        // Durum çubuğu
        statusLabel = new JLabel("Bağlantı bekleniyor...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.SOUTH);

        setEditingEnabled(false);
        setupEventHandlers();
    }

    private void setupEventHandlers() {
        connectButton.addActionListener(e -> handleConnect());
        registerButton.addActionListener(e -> handleRegister());
        loginButton.addActionListener(e -> handleLogin());

        // Enter tuşu ile giriş yapma
        ActionListener loginAction = e -> handleLogin();
        passwordField.addActionListener(loginAction);
    }

    private void setupNetworkManager() {
        networkManager.setMessageHandler(message -> {
            if (message == null || !message.isValid()) return;

            System.out.println("Login: Mesaj alındı -> " + message.getType());
            
            switch (message.getType()) {
                case CONNECT_ACK:
                    handleConnectAck(message);
                    break;

                case LOGIN_ACK:
                    handleLoginAck(message);
                    break;

                case REGISTER_ACK:
                    handleRegisterAck(message);
                    break;

                case ERROR:
                    handleError(message.getData("message"));
                    break;
            }
        });

        networkManager.setErrorHandler(error -> {
            showError(error);
            setConnectionStatus(false);
        });
    }

    private void handleConnect() {
        try {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());
            networkManager.connect(host, port);
            statusLabel.setText("Sunucuya bağlanılıyor...");
        } catch (NumberFormatException e) {
            showError("Geçersiz port numarası!");
        } catch (Exception e) {
            showError("Bağlantı hatası: " + e.getMessage());
        }
    }

    private void handleLogin() {
        try {
            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();
            
            if (username.isEmpty() || password.isEmpty()) {
                showError("Kullanıcı adı ve şifre boş olamaz!");
                return;
            }

            networkManager.login(username, password);
            statusLabel.setText("Giriş yapılıyor...");
        } catch (Exception e) {
            showError("Giriş hatası: " + e.getMessage());
        }
    }

    private void handleRegister() {
        try {
            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();
            
            if (username.isEmpty() || password.isEmpty()) {
                showError("Kullanıcı adı ve şifre boş olamaz!");
                return;
            }

            networkManager.register(username, password);
            statusLabel.setText("Kayıt yapılıyor...");
        } catch (Exception e) {
            showError("Kayıt hatası: " + e.getMessage());
        }
    }

    private void handleConnectAck(Message message) {
        if ("success".equals(message.getData("status"))) {
            setConnectionStatus(true);
            statusLabel.setText("Sunucuya bağlandı. Lütfen giriş yapın.");
        } else {
            showError(message.getData("message"));
            setConnectionStatus(false);
        }
    }

    private void handleLoginAck(Message message) {
        if ("success".equals(message.getData("status"))) {
            String userId = message.getUserId();
            if (userId != null && !userId.trim().isEmpty()) {
                statusLabel.setText("Giriş başarılı!");
                networkManager.setUserId(userId);
                openMainWindow(userId);
            } else {
                showError("Sunucudan geçersiz kullanıcı ID alındı!");
            }
        } else {
            showError("Giriş başarısız: " + message.getData("message"));
        }
    }

    private void handleRegisterAck(Message message) {
        if ("success".equals(message.getData("status"))) {
            statusLabel.setText("Kayıt başarılı! Şimdi giriş yapabilirsiniz.");
        } else {
            showError(message.getData("message"));
        }
    }

    private void handleError(String message) {
        showError(message);
        setConnectionStatus(false);
    }

    private void openMainWindow(String userId) {
        SwingUtilities.invokeLater(() -> {
            if (mainWindow == null) {
                mainWindow = new MainWindow(networkManager, userId);
            }
            mainWindow.setVisible(true);
            this.setVisible(false);
        });
    }

    private void setConnectionStatus(boolean connected) {
        SwingUtilities.invokeLater(() -> {
            registerButton.setEnabled(connected);
            loginButton.setEnabled(connected);
            connectButton.setEnabled(!connected);
            hostField.setEnabled(!connected);
            portField.setEnabled(!connected);
        });
    }

    private void setEditingEnabled(boolean enabled) {
        registerButton.setEnabled(enabled);
        loginButton.setEnabled(enabled);
        connectButton.setEnabled(!enabled);
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, "Hata", JOptionPane.ERROR_MESSAGE);
        });
    }
} 