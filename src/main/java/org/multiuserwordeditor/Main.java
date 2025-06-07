package org.multiuserwordeditor;

import org.multiuserwordeditor.network.NetworkManager;
import org.multiuserwordeditor.ui.LoginWindow;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        try {
            // Loglama sistemini başlat
            File logsDir = new File("logs");
            if (!logsDir.exists()) {
                logsDir.mkdir();
            }

            LogManager.getLogManager().readConfiguration(
                    Main.class.getClassLoader().getResourceAsStream("logging.properties"));

            LOGGER.info("Uygulama başlatılıyor...");

            // Look and Feel'i sistem varsayılanına ayarla
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            LOGGER.info("Sistem teması ayarlandı");

            // Swing thread'inde çalıştır
            SwingUtilities.invokeLater(() -> {
                try {
                    NetworkManager networkManager = new NetworkManager();
                    LoginWindow loginWindow = new LoginWindow(networkManager);
                    loginWindow.setVisible(true);
                    LOGGER.info("Giriş penceresi açıldı");
                } catch (Exception e) {
                    LOGGER.severe("Uygulama başlatılırken hata: " + e.getMessage());
                    JOptionPane.showMessageDialog(null,
                            "Uygulama başlatılırken bir hata oluştu:\n" + e.getMessage(),
                            "Hata",
                            JOptionPane.ERROR_MESSAGE);
                }
            });
        } catch (Exception e) {
            System.err.println("Kritik hata: " + e.getMessage());
            e.printStackTrace();
            JOptionPane.showMessageDialog(null,
                    "Kritik bir hata oluştu:\n" + e.getMessage(),
                    "Kritik Hata",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}