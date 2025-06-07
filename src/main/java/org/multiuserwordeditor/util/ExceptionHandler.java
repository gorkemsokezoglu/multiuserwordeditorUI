package org.multiuserwordeditor.util;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class ExceptionHandler {
    private static final Logger LOGGER = Logger.getLogger(ExceptionHandler.class.getName());
    private static JFrame mainFrame;

    public static void setMainFrame(JFrame frame) {
        mainFrame = frame;
    }

    public static void handle(Exception e, String userMessage, String logMessage) {
        LOGGER.log(Level.SEVERE, logMessage, e);

        SwingUtilities.invokeLater(() -> {
            String detailedMessage = String.format("%s%n%nHata Detayı:%n%s",
                    userMessage, e.getMessage());
            JOptionPane.showMessageDialog(mainFrame,
                    detailedMessage,
                    "Hata",
                    JOptionPane.ERROR_MESSAGE);
        });
    }

    public static void handle(Exception e, String userMessage) {
        handle(e, userMessage, "Bir hata oluştu");
    }

    public static void handleSilently(Exception e, String logMessage) {
        LOGGER.log(Level.SEVERE, logMessage, e);
    }
}