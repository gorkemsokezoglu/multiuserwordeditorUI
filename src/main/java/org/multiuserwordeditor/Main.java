package org.multiuserwordeditor;

import org.multiuserwordeditor.network.NetworkManager;
import org.multiuserwordeditor.ui.LoginWindow;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        try {
            // Look and Feel'i sistem varsayılanına ayarla
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Swing thread'inde çalıştır
        SwingUtilities.invokeLater(() -> {
            NetworkManager networkManager = new NetworkManager();
            LoginWindow loginWindow = new LoginWindow(networkManager);
            loginWindow.setVisible(true);
        });
    }
} 