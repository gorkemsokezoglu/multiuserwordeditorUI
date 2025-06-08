package org.multiuserwordeditor.ui;

import org.multiuserwordeditor.model.Document;
import org.multiuserwordeditor.model.Message;
import org.multiuserwordeditor.network.NetworkManager;
import org.multiuserwordeditor.util.ExceptionHandler;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.io.File;
import java.util.logging.Logger;

public class MainWindow extends JFrame {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());

    private NetworkManager networkManager;
    private String userId;
    private JTextPane editorPane;
    private JTextArea chatArea;
    private JTextField messageField;
    private JList<FileDisplayItem> documentList; // String yerine FileDisplayItem
    private DefaultListModel<FileDisplayItem> listModel; // String yerine FileDisplayItem
    private JComboBox<String> fontFamilyCombo;
    private JComboBox<Integer> fontSizeCombo;
    private JToggleButton boldButton;
    private JToggleButton italicButton;
    private JToggleButton underlineButton;
    private JLabel statusLabel;
    private JTextField searchField;
    private Color currentTextColor;
    private String currentTheme = "light";
    private boolean isUpdatingFromServer = false;
    private final Object textChangeLock = new Object();
    private volatile boolean isProcessingTextChange = false;

    private static final int MAX_FILENAME_LENGTH = 100;
    private static final String INVALID_FILENAME_CHARS = "<>:\"|?*/\\\\";

    private String lastContent = "";

    public MainWindow(NetworkManager networkManager, String userId) {
        super("Çok Kullanıcılı Metin Editörü");
        this.networkManager = networkManager;
        this.userId = userId;
        initialize();
        setupNetworkManager();
        ExceptionHandler.setMainFrame(this);
        requestDocumentList();
        setupDragAndDrop();
    }

    private void initialize() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        createMenuBar();

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setDividerLocation(250);
        mainSplitPane.setBorder(null);

        JPanel leftPanel = createLeftPanel();
        mainSplitPane.setLeftComponent(leftPanel);

        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setDividerLocation(600);
        rightSplitPane.setBorder(null);

        JPanel editorPanel = createEditorPanel();
        rightSplitPane.setTopComponent(editorPanel);

        JPanel chatPanel = createChatPanel();
        rightSplitPane.setBottomComponent(chatPanel);

        mainSplitPane.setRightComponent(rightSplitPane);

        statusLabel = new JLabel("Hazır");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        Container contentPane = getContentPane();
        contentPane.add(mainSplitPane, BorderLayout.CENTER);
        contentPane.add(statusLabel, BorderLayout.SOUTH);

        applyTheme(currentTheme);
    }

    private void setupNetworkManager() {
        networkManager.setMessageHandler(message -> {
            if (message == null || !message.isValid()) {
                System.out.println("Geçersiz mesaj alındı");
                return;
            }

            System.out.println("Main: Mesaj alındı -> " + message.getType());

            try {
                switch (message.getType()) {
                    case FILE_LIST_RESP:
                        // RAW MESSAGE DEBUG EKLE
                        System.out.println("DEBUG: Message serialize edilmiş hali: " + message.serialize());

                        // Data içeriğini direkt kontrol et
                        String filesData = message.getData("files");
                        System.out.println("DEBUG: Raw getData('files'): '" + filesData + "'");
                        System.out.println("DEBUG: Raw getData('files') length: "
                                + (filesData != null ? filesData.length() : "null"));

                        // Message'daki tüm dataları göster
                        System.out.println("DEBUG: Message tüm data keys: " + message.getAllDataKeys());

                        handleFileListResponse(message);
                        break;
                    case FILE_CONTENT:
                        handleFileContent(message);
                        break;
                    case FILE_CREATE:
                        handleFileCreated(message);
                        break;
                    case TEXT_UPDATE:
                        handleFileUpdated(message);
                        break;
                    case ERROR:
                        String errorMsg = message.getData("message");
                        // Karakter kodlama düzeltmesi
                        if (errorMsg != null) {
                            errorMsg = new String(errorMsg.getBytes("ISO-8859-1"), "UTF-8");
                        }
                        handleError(errorMsg);
                        break;
                    default:
                        System.out.println("Bilinmeyen mesaj tipi: " + message.getType());
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
                showError("İşlem sırasında bir hata oluştu: " + e.getMessage());
            }
        });

        requestDocumentList();
    }

    private void openSelectedFile() {
        FileDisplayItem selected = documentList.getSelectedValue();

        if (selected == null) {
            showError("Lütfen bir dosya seçin!");
            return;
        }

        String fileId = selected.getFileId();
        String fileName = selected.getFileName();

        System.out.println("DEBUG: Açılacak dosya - ID: " + fileId + ", Name: " + fileName);
        statusLabel.setText("Dosya açılıyor: " + fileName);

        try {
            networkManager.openDocument(fileId);
            System.out.println("DEBUG: openDocument çağrıldı: " + fileId);
        } catch (Exception e) {
            System.err.println("ERROR: Dosya açma hatası: " + e.getMessage());
            statusLabel.setText("Dosya açma hatası: " + e.getMessage());
            showError("Dosya açma hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleManualRefresh() {
        System.out.println("DEBUG: Manual refresh requested by user");
        statusLabel.setText("🔄 Liste yenileniyor...");

        // Use NetworkManager's force refresh method
        networkManager.forceFileListRefresh();
    }

    /**
     * 🔧 UPDATED: Enhanced file list response handler with better error handling
     */
    private void handleFileListResponse(Message message) {
        SwingUtilities.invokeLater(() -> {
            try {
                System.out.println("=== ENHANCED FILE LIST RESPONSE DEBUG ===");

                // Clear current list
                int oldSize = listModel.size();
                listModel.clear();
                System.out.println("DEBUG: Cleared list (previous size: " + oldSize + ")");

                String filesData = message.getData("files");
                System.out.println("DEBUG: Raw files data: '" + filesData + "'");
                System.out.println("DEBUG: Files data length: " + (filesData != null ? filesData.length() : "null"));

                if (filesData == null || filesData.trim().isEmpty()) {
                    System.out.println("DEBUG: No files data received");
                    statusLabel.setText("📋 Doküman listesi boş");
                    return;
                }

                // Parse and add files (| separated)
                String[] files = filesData.split("\\|");
                int addedCount = 0;

                System.out.println("DEBUG: Processing " + files.length + " file entries");

                for (int i = 0; i < files.length; i++) {
                    String file = files[i].trim();
                    System.out.println("DEBUG: Processing file entry " + (i+1) + ": '" + file + "'");

                    if (!file.isEmpty()) {
                        String[] parts = file.split(":");
                        if (parts.length >= 2) {
                            String fileId = parts[0].trim();
                            String fileName = parts[1].trim();
                            String userCount = parts.length > 2 ? parts[2].trim() : "0";

                            System.out.println("DEBUG: Parsed - ID: '" + fileId + "', Name: '" + fileName + "', Users: " + userCount);

                            // Create and add item
                            FileDisplayItem item = new FileDisplayItem(fileId, fileName, userCount);
                            listModel.addElement(item);
                            addedCount++;

                            System.out.println("SUCCESS: Added file to list: " + fileName + " (" + fileId + ")");
                        } else {
                            System.err.println("WARNING: Invalid file entry format: '" + file + "' (expected at least 2 parts, got " + parts.length + ")");
                        }
                    } else {
                        System.out.println("DEBUG: Skipping empty file entry at index " + i);
                    }
                }

                // Update UI and status
                documentList.revalidate();
                documentList.repaint();

                String statusText;
                if (addedCount == 0) {
                    statusText = "📋 Henüz doküman yok";
                } else {
                    statusText = "📋 " + addedCount + " doküman yüklendi";
                }
                statusLabel.setText(statusText);

                System.out.println("SUCCESS: File list updated - " + addedCount + " files added");
                System.out.println("=== ENHANCED FILE LIST RESPONSE END ===");

            } catch (Exception e) {
                System.err.println("ERROR: File list response processing failed: " + e.getMessage());
                e.printStackTrace();
                statusLabel.setText("❌ Liste güncelleme hatası: " + e.getMessage());
                showError("Doküman listesi güncelleme hatası: " + e.getMessage());
            }
        });
    }

    private static class FileDisplayItem {
        private final String fileId;
        private final String fileName;
        private final String userCount;

        public FileDisplayItem(String fileId, String fileName, String userCount) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.userCount = userCount;
        }

        public String getFileId() {
            return fileId;
        }

        public String getFileName() {
            return fileName;
        }

        public String getUserCount() {
            return userCount;
        }

        @Override
        public String toString() {
            // Dosya adını ve aktif kullanıcı sayısını göster
            if (userCount != null && !userCount.equals("0")) {
                return fileName;
            }
            return fileName;
        }
    }

    private void handleFileContent(Message message) {
        SwingUtilities.invokeLater(() -> {
            String content = message.getData("content");
            String filename = message.getData("filename");
            System.out.println("Doküman içeriği alındı: " + filename);

            if (content != null) {
                // ✅ INITIAL LOAD İÇİN DE FLAG SET ET
                isUpdatingFromServer = true;

                editorPane.setText(content);
                editorPane.setCaretPosition(0);
                lastContent = content; // Son içeriği güncelle

                isUpdatingFromServer = false; // ✅ FLAG RESET

                statusLabel.setText("Doküman açıldı: " + filename);
            } else {
                statusLabel.setText("Doküman içeriği alınamadı: " + filename);
            }
        });
    }

    /**
     * 🔧 UPDATED: File created handler with automatic list refresh
     */
    private void handleFileCreated(Message message) {
        SwingUtilities.invokeLater(() -> {
            String filename = message.getData("filename");
            String fileId = message.getFileId();

            System.out.println("=== FILE CREATED HANDLER DEBUG ===");
            System.out.println("DEBUG: Received filename: " + filename);
            System.out.println("DEBUG: Received fileId: " + fileId);

            if (filename != null && fileId != null) {
                // 🔧 IMMEDIATE: Add to list right away (optimistic update)
                FileDisplayItem newItem = new FileDisplayItem(fileId, filename, "0");

                // Check if already exists in list
                boolean alreadyExists = false;
                for (int i = 0; i < listModel.size(); i++) {
                    FileDisplayItem existing = listModel.getElementAt(i);
                    if (existing.getFileId().equals(fileId)) {
                        alreadyExists = true;
                        System.out.println("DEBUG: File already exists in list, skipping add");
                        break;
                    }
                }

                if (!alreadyExists) {
                    // Add to beginning of list (most recent first)
                    listModel.add(0, newItem);

                    // Select the newly created file
                    documentList.setSelectedIndex(0);

                    System.out.println("SUCCESS: New file added to list: " + filename + " (ID: " + fileId + ")");
                    statusLabel.setText("✅ Yeni doküman oluşturuldu: " + filename);

                    // Update UI
                    documentList.revalidate();
                    documentList.repaint();
                }

                // 🔧 ADDITIONAL: Request fresh list from server (for accuracy)
                javax.swing.Timer refreshTimer = new javax.swing.Timer(500, e -> {
                    System.out.println("DEBUG: Requesting fresh file list after creation");
                    networkManager.forceFileListRefresh();
                });
                refreshTimer.setRepeats(false);
                refreshTimer.start();

            } else {
                System.err.println("ERROR: Invalid file creation response - filename or fileId is null");
                statusLabel.setText("❌ Dosya oluşturma hatası: Geçersiz yanıt");

                // Still refresh the list in case of partial success
                requestDocumentList();
            }

            System.out.println("=== FILE CREATED HANDLER END ===");
        });
    }

    /**
     * 🔧 THREAD-SAFE handleTextChange with newline awareness
     */
    private void handleTextChange() {
        // Prevent concurrent text change processing
        synchronized (textChangeLock) {
            if (isUpdatingFromServer || isProcessingTextChange) {
                System.out.println("DEBUG: handleTextChange SKIPPED - flags: updating=" +
                        isUpdatingFromServer + ", processing=" + isProcessingTextChange);
                return;
            }

            isProcessingTextChange = true;
        }

        try {
            FileDisplayItem selected = documentList.getSelectedValue();
            if (selected == null) {
                System.out.println("DEBUG: No file selected, skipping text change");
                return;
            }

            String currentContent = editorPane.getText();
            String fileId = selected.getFileId();

            System.out.println("=== THREAD-SAFE TEXT CHANGE DEBUG ===");
            System.out.println("DEBUG: Current content length: " + currentContent.length());
            System.out.println("DEBUG: Last content length: " + lastContent.length());

            // Content equality check (important for newlines)
            if (currentContent.equals(lastContent)) {
                System.out.println("DEBUG: Content unchanged, skipping");
                return;
            }

            // 🔧 ENHANCED DIFF DETECTION
            ContentDiff diff = analyzeContentDifference(lastContent, currentContent);

            if (diff != null) {
                if (diff.isInsert) {
                    System.out.println("DEBUG: Processing INSERT operation");
                    processInsertOperation(fileId, diff);
                } else {
                    System.out.println("DEBUG: Processing DELETE operation");
                    processDeleteOperation(fileId, diff);
                }

                // Update lastContent only after successful processing
                lastContent = currentContent;
                System.out.println("DEBUG: lastContent updated successfully");

            } else {
                System.err.println("ERROR: Could not analyze content difference");
                // Fallback - sync with current content
                lastContent = currentContent;
            }

        } catch (Exception e) {
            System.err.println("ERROR: handleTextChange exception: " + e.getMessage());
            e.printStackTrace();
            // Emergency fallback
            lastContent = editorPane.getText();
        } finally {
            synchronized (textChangeLock) {
                isProcessingTextChange = false;
            }
            System.out.println("=== THREAD-SAFE TEXT CHANGE END ===");
        }
    }

    /**
     * 🔧 Advanced content difference analysis
     */
    private ContentDiff analyzeContentDifference(String oldContent, String newContent) {
        try {
            int oldLen = oldContent.length();
            int newLen = newContent.length();

            if (newLen > oldLen) {
                // INSERT operation
                int insertPos = findInsertPosition(oldContent, newContent);
                int insertLen = newLen - oldLen;
                String insertedText = newContent.substring(insertPos, insertPos + insertLen);

                return new ContentDiff(true, insertPos, insertLen, insertedText);

            } else if (newLen < oldLen) {
                // DELETE operation
                int deleteLen = oldLen - newLen;

                // Find delete position using LCS (Longest Common Subsequence) approach
                int deletePos = findDeletePosition(oldContent, newContent);
                String deletedText = oldContent.substring(deletePos, deletePos + deleteLen);

                return new ContentDiff(false, deletePos, deleteLen, deletedText);
            }

            return null; // No change

        } catch (Exception e) {
            System.err.println("ERROR: analyzeContentDifference failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 🔧 Enhanced delete position finding with newline awareness
     */
    private int findDeletePosition(String oldContent, String newContent) {
        int oldLen = oldContent.length();
        int newLen = newContent.length();

        // Find common prefix
        int commonPrefix = 0;
        int minLen = Math.min(oldLen, newLen);

        for (int i = 0; i < minLen; i++) {
            if (oldContent.charAt(i) == newContent.charAt(i)) {
                commonPrefix++;
            } else {
                break;
            }
        }

        System.out.println("DEBUG: findDeletePosition - common prefix: " + commonPrefix);

        // 🔧 NEWLINE BOUNDARY CHECK
        if (commonPrefix > 0 && commonPrefix < oldLen) {
            char prevChar = oldContent.charAt(commonPrefix - 1);
            char deletedChar = oldContent.charAt(commonPrefix);

            System.out.println("DEBUG: Character before delete: '" +
                    (prevChar == '\n' ? "NEWLINE" : String.valueOf(prevChar)) + "'");
            System.out.println("DEBUG: First deleted character: '" +
                    (deletedChar == '\n' ? "NEWLINE" : String.valueOf(deletedChar)) + "'");
        }

        return commonPrefix;
    }

    /**
     * 🔧 Process INSERT operation with delays
     */
    private void processInsertOperation(String fileId, ContentDiff diff) {
        try {
            System.out.println("DEBUG: INSERT - pos: " + diff.position +
                    ", text: '" + diff.text.replace("\n", "\\n") + "'");

            // Turkish character check (except newlines)
            if (containsTurkishCharacters(diff.text)) {
                handleTurkishCharacterError(diff.position, diff.length);
                return;
            }

            // Send each character separately with appropriate delays
            for (int i = 0; i < diff.text.length(); i++) {
                char c = diff.text.charAt(i);
                String singleChar = String.valueOf(c);
                int charPosition = diff.position + i;

                System.out.println("DEBUG: Sending character '" +
                        (c == '\n' ? "NEWLINE" : c == ' ' ? "SPACE" : String.valueOf(c)) +
                        "' at position " + charPosition);

                networkManager.insertText(fileId, charPosition, singleChar);

                // 🔧 NEWLINE-SPECIFIC DELAY
                if (c == '\n') {
                    try { Thread.sleep(100); } catch (InterruptedException e) {} // Longer delay for newlines
                } else if (diff.text.length() > 3) {
                    try { Thread.sleep(20); } catch (InterruptedException e) {}
                }
            }

        } catch (Exception e) {
            System.err.println("ERROR: processInsertOperation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🔧 Process DELETE operation with validation
     */
    private void processDeleteOperation(String fileId, ContentDiff diff) {
        try {
            System.out.println("DEBUG: DELETE - pos: " + diff.position +
                    ", length: " + diff.length +
                    ", text: '" + diff.text.replace("\n", "\\n") + "'");

            // Validation before sending
            if (diff.position < 0 || diff.length <= 0) {
                System.err.println("ERROR: Invalid delete parameters - pos: " + diff.position +
                        ", len: " + diff.length);
                return;
            }

            // 🔧 NEWLINE DELETE ANALYSIS
            long newlineCount = diff.text.chars().filter(ch -> ch == '\n').count();
            if (newlineCount > 0) {
                System.out.println("🔥 DELETE contains " + newlineCount + " newline(s) - sending with delay");

                // Longer delay for newline-containing deletes
                try { Thread.sleep(150); } catch (InterruptedException e) {}
            }

            // Send delete operation
            networkManager.deleteText(fileId, diff.position, diff.length);

        } catch (Exception e) {
            System.err.println("ERROR: processDeleteOperation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🔧 Content difference data structure
     */
    private static class ContentDiff {
        final boolean isInsert;
        final int position;
        final int length;
        final String text;

        ContentDiff(boolean isInsert, int position, int length, String text) {
            this.isInsert = isInsert;
            this.position = position;
            this.length = length;
            this.text = text;
        }

        @Override
        public String toString() {
            return String.format("ContentDiff{%s, pos=%d, len=%d, text='%s'}",
                    isInsert ? "INSERT" : "DELETE", position, length,
                    text.replace("\n", "\\n"));
        }
    }

    /**
     * 🔧 Enhanced server update handling with thread safety
     */
    private void handleFileUpdated(Message message) {
        SwingUtilities.invokeLater(() -> {
            synchronized (textChangeLock) {
                isUpdatingFromServer = true;
            }

            try {
                String operation = message.getData("operation");
                String textValue = message.getData("text");
                int position = Integer.parseInt(message.getData("position"));
                String senderId = message.getUserId();

                // Decode special characters
                String text = decodeSpecialCharacters(textValue);

                System.out.println("SERVER UPDATE: " + operation + " by " + senderId +
                        " at pos " + position + " char: " +
                        (text.equals("\n") ? "NEWLINE" :
                                text.equals(" ") ? "SPACE" : "'" + text + "'"));

                if ("insert".equals(operation) || "INSERT".equals(operation)) {
                    handleServerInsert(position, text, senderId);
                } else if ("delete".equals(operation) || "DELETE".equals(operation)) {
                    int deleteLength = Integer.parseInt(message.getData("length"));
                    handleServerDelete(position, deleteLength, senderId);
                }

            } catch (Exception e) {
                System.err.println("File update error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                synchronized (textChangeLock) {
                    isUpdatingFromServer = false;
                }
            }
        });
    }

    /**
     * 🔧 Handle server INSERT with position validation
     */
    private void handleServerInsert(int position, String text, String senderId) {
        try {
            String currentContent = editorPane.getText();

            // Position validation and auto-fix
            if (position < 0) position = 0;
            if (position > currentContent.length()) position = currentContent.length();

            System.out.println("DEBUG: Server INSERT - original pos: " + position +
                    ", content length: " + currentContent.length());

            // Apply insert
            String newContent = currentContent.substring(0, position) + text +
                    currentContent.substring(position);

            editorPane.setText(newContent);
            lastContent = newContent; // Update immediately to prevent loops

            // Log success
            if (text.equals("\n")) {
                System.out.println("✏️ " + senderId + " added NEWLINE at position " + position);
            } else if (text.equals(" ")) {
                System.out.println("✏️ " + senderId + " added SPACE at position " + position);
            } else {
                System.out.println("✏️ " + senderId + " added: \"" + text + "\" at position " + position);
            }

        } catch (Exception e) {
            System.err.println("ERROR: handleServerInsert failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🔧 Handle server DELETE with enhanced validation
     */
    private void handleServerDelete(int position, int length, String senderId) {
        try {
            String currentContent = editorPane.getText();

            System.out.println("DEBUG: Server DELETE - pos: " + position +
                    ", length: " + length + ", content length: " + currentContent.length());

            // Enhanced validation for DELETE
            if (position < 0) {
                System.err.println("ERROR: Invalid delete position: " + position);
                return;
            }

            if (position >= currentContent.length()) {
                System.err.println("ERROR: Delete position beyond content: " + position +
                        " >= " + currentContent.length());
                return;
            }

            if (length <= 0) {
                System.err.println("ERROR: Invalid delete length: " + length);
                return;
            }

            // Auto-fix length if too big
            int maxLength = currentContent.length() - position;
            if (length > maxLength) {
                System.out.println("DEBUG: Auto-fixing delete length: " + length + " → " + maxLength);
                length = maxLength;
            }

            // Get text to be deleted for logging
            String deletedText = currentContent.substring(position, position + length);
            long newlineCount = deletedText.chars().filter(ch -> ch == '\n').count();

            System.out.println("DEBUG: Deleting text: '" + deletedText.replace("\n", "\\n") +
                    "' (contains " + newlineCount + " newlines)");

            // Apply delete
            String newContent = currentContent.substring(0, position) +
                    currentContent.substring(position + length);

            editorPane.setText(newContent);
            lastContent = newContent; // Update immediately to prevent loops

            // Log success
            if (newlineCount > 0) {
                System.out.println("🗑️ " + senderId + " deleted " + length + " characters including " +
                        newlineCount + " newline(s) at position " + position);
            } else {
                System.out.println("🗑️ " + senderId + " deleted " + length + " characters at position " + position);
            }

        } catch (Exception e) {
            System.err.println("ERROR: handleServerDelete failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🔧 Decode special characters from server
     */
    private String decodeSpecialCharacters(String encodedText) {
        if (encodedText == null) return "";

        switch (encodedText) {
            case "__SPACE__":
                System.out.println("DEBUG: CLIENT - Space decoded");
                return " ";
            case "__NEWLINE__":
                System.out.println("DEBUG: CLIENT - Newline decoded");
                return "\n";
            case "__CRLF__":
                System.out.println("DEBUG: CLIENT - CRLF decoded");
                return "\r\n";
            case "__TAB__":
                System.out.println("DEBUG: CLIENT - Tab decoded");
                return "\t";
            default:
                return encodedText;
        }
    }

    /**
     * 🔧 Enhanced DocumentListener with better thread safety
     */
    private void setupEnhancedDocumentListener() {
        editorPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                System.out.println("DEBUG: DocumentListener.insertUpdate - " +
                        "isUpdatingFromServer: " + isUpdatingFromServer +
                        ", isProcessingTextChange: " + isProcessingTextChange);

                if (!isUpdatingFromServer && !isProcessingTextChange) {
                    // Use SwingUtilities.invokeLater to ensure proper EDT handling
                    SwingUtilities.invokeLater(() -> {
                        try {
                            Thread.sleep(50); // Small delay to ensure all updates are complete
                        } catch (InterruptedException ex) {}
                        handleTextChange();
                    });
                } else {
                    System.out.println("DEBUG: Skipping handleTextChange - server update or processing");
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                System.out.println("DEBUG: DocumentListener.removeUpdate - " +
                        "isUpdatingFromServer: " + isUpdatingFromServer +
                        ", isProcessingTextChange: " + isProcessingTextChange);

                if (!isUpdatingFromServer && !isProcessingTextChange) {
                    SwingUtilities.invokeLater(() -> {
                        try {
                            Thread.sleep(50); // Small delay for stability
                        } catch (InterruptedException ex) {}
                        handleTextChange();
                    });
                } else {
                    System.out.println("DEBUG: Skipping handleTextChange - server update or processing");
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Style changes - not relevant for content synchronization
                System.out.println("DEBUG: DocumentListener.changedUpdate (style change)");
            }
        });

        System.out.println("DEBUG: Enhanced DocumentListener setup completed");
    }



    private void handleError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            if (errorMessage != null) {
                try {
                    // Karakter kodlama düzeltmesi
                    String fixedMessage = new String(errorMessage.getBytes("ISO-8859-1"), "UTF-8");
                    showError(fixedMessage);
                    statusLabel.setText("Hata: " + fixedMessage);
                } catch (Exception e) {
                    e.printStackTrace();
                    showError(errorMessage);
                    statusLabel.setText("Hata: " + errorMessage);
                }
            }
        });
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, "Hata", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void requestDocumentList() {
        try {
            System.out.println("=== REQUESTING DOCUMENT LIST ===");
            System.out.println("DEBUG: Current list size before request: " + listModel.size());

            // Show loading status
            statusLabel.setText("📋 Doküman listesi yenileniyor...");

            // Send request through NetworkManager
            networkManager.requestFileList();

            System.out.println("DEBUG: File list request sent successfully through NetworkManager");

            // Set timeout for request
            javax.swing.Timer timeoutTimer = new javax.swing.Timer(10000, e -> {
                if (statusLabel.getText().contains("yenileniyor")) {
                    statusLabel.setText("⚠️ Liste yenileme zaman aşımı");
                    System.out.println("WARNING: File list request timeout");
                }
            });
            timeoutTimer.setRepeats(false);
            timeoutTimer.start();

        } catch (Exception e) {
            System.err.println("ERROR: Failed to request document list: " + e.getMessage());
            e.printStackTrace();
            statusLabel.setText("❌ Liste yenileme hatası: " + e.getMessage());
        }
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Dosya menüsü
        JMenu fileMenu = new JMenu("Dosya");
        addMenuItem(fileMenu, "Yeni", 'N', KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK),
                this::handleNewDocument);
        addMenuItem(fileMenu, "Aç", 'O', KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK),
                this::handleOpenDocument);
        addMenuItem(fileMenu, "Kaydet", 'S', KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
                this::handleSaveDocument);
        fileMenu.addSeparator();
        addMenuItem(fileMenu, "Çıkış", 'Q', KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
                () -> System.exit(0));

        // Düzen menüsü
        JMenu editMenu = new JMenu("Düzen");
        addMenuItem(editMenu, "Bul", 'F', KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
                this::showFindDialog);
        addMenuItem(editMenu, "Değiştir", 'H', KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK),
                this::showReplaceDialog);
        editMenu.addSeparator();
        addMenuItem(editMenu, "Kes", 'X', KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK),
                () -> editorPane.cut());
        addMenuItem(editMenu, "Kopyala", 'C', KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                () -> editorPane.copy());
        addMenuItem(editMenu, "Yapıştır", 'V', KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
                () -> editorPane.paste());

        // Görünüm menüsü
        JMenu viewMenu = new JMenu("Görünüm");
        JMenu themeMenu = new JMenu("Tema");
        addMenuItem(themeMenu, "Açık Tema", null, null, () -> applyTheme("light"));
        addMenuItem(themeMenu, "Koyu Tema", null, null, () -> applyTheme("dark"));
        viewMenu.add(themeMenu);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);

        setJMenuBar(menuBar);
    }

    private void addMenuItem(JMenu menu, String text, Character mnemonic, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (mnemonic != null)
            item.setMnemonic(mnemonic);
        if (accelerator != null)
            item.setAccelerator(accelerator);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }

    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Title panel with refresh button
        JPanel titlePanel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("Dokümanlar");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 14));

        // 🔧 NEW: Add refresh button
        JButton refreshButton = new JButton("Listeyi Yenile");
        refreshButton.setToolTipText("Listeyi yenile");
        refreshButton.setPreferredSize(new Dimension(30, 25));
        refreshButton.addActionListener(e -> handleManualRefresh());

        titlePanel.add(titleLabel, BorderLayout.CENTER);
        titlePanel.add(refreshButton, BorderLayout.EAST);
        panel.add(titlePanel, BorderLayout.NORTH);

        // Document list - FileDisplayItem type
        listModel = new DefaultListModel<>();
        documentList = new JList<>(listModel);
        documentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        documentList.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Double-click event handler
        documentList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { // Double-click
                    openSelectedFile();
                }
            }
        });

        // 🔧 ENHANCED: Right-click context menu
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("Aç");

        JMenuItem refreshItem = new JMenuItem("Listeyi Yenile");
        refreshItem.setIcon(null);  // Her ihtimale karşı simgeyi sıfırla

        openItem.addActionListener(e -> openSelectedFile());
        refreshItem.addActionListener(e -> handleManualRefresh());

        contextMenu.add(openItem);
        contextMenu.addSeparator();
        contextMenu.add(refreshItem);

        documentList.setComponentPopupMenu(contextMenu);

        JScrollPane listScroller = new JScrollPane(documentList);
        listScroller.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        panel.add(listScroller, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        JButton newButton = new JButton("Yeni");
        JButton openButton = new JButton("Aç");

        newButton.addActionListener(e -> handleNewDocument());
        openButton.addActionListener(e -> openSelectedFile());

        buttonPanel.add(newButton);
        buttonPanel.add(openButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Araç çubuğu
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        // Font ailesi seçici
        fontFamilyCombo = new JComboBox<>(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        fontFamilyCombo.setMaximumSize(new Dimension(200, 30));
        fontFamilyCombo.addActionListener(e -> updateFontStyle());

        // Font boyutu seçici
        Integer[] sizes = { 8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72 };
        fontSizeCombo = new JComboBox<>(sizes);
        fontSizeCombo.setMaximumSize(new Dimension(70, 30));
        fontSizeCombo.setSelectedItem(12);
        fontSizeCombo.addActionListener(e -> updateFontStyle());

        // Stil butonları
        boldButton = new JToggleButton("B");
        italicButton = new JToggleButton("I");
        underlineButton = new JToggleButton("U");

        boldButton.setFont(new Font("Arial", Font.BOLD, 12));
        italicButton.setFont(new Font("Arial", Font.ITALIC, 12));

        boldButton.setToolTipText("Kalın (Ctrl+B)");
        italicButton.setToolTipText("İtalik (Ctrl+I)");
        underlineButton.setToolTipText("Altı Çizili (Ctrl+U)");

        boldButton.addActionListener(e -> updateFontStyle());
        italicButton.addActionListener(e -> updateFontStyle());
        underlineButton.addActionListener(e -> updateFontStyle());

        // Renk seçici
        JButton colorButton = new JButton("Renk");
        colorButton.addActionListener(e -> {
            Color newColor = JColorChooser.showDialog(this, "Metin Rengi", currentTextColor);
            if (newColor != null) {
                currentTextColor = newColor;
                updateFontStyle();
            }
        });

        // Arama alanı
        searchField = new JTextField(20);
        searchField.setMaximumSize(new Dimension(200, 30));
        JButton findButton = new JButton("Bul");
        findButton.addActionListener(e -> findText(searchField.getText()));

        // 🔧 SPACE TEST BUTONU EKLE (Debug için)
        JButton spaceTestButton = new JButton("Space");
        spaceTestButton.setToolTipText("Space Test (Debug)");


        toolBar.add(fontFamilyCombo);
        toolBar.add(fontSizeCombo);
        toolBar.addSeparator();
        toolBar.add(boldButton);
        toolBar.add(italicButton);
        toolBar.add(underlineButton);
        toolBar.addSeparator();
        toolBar.add(colorButton);
        toolBar.addSeparator();
        toolBar.add(searchField);
        toolBar.add(findButton);
        // 🔧 SPACE TEST BUTONU EKLE
        toolBar.addSeparator();
        toolBar.add(spaceTestButton);

        // Editör
        editorPane = new JTextPane();
        editorPane.setFont(new Font(fontFamilyCombo.getSelectedItem().toString(), Font.PLAIN,
                (Integer) fontSizeCombo.getSelectedItem()));

        // Kısayol tuşları
        InputMap inputMap = editorPane.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = editorPane.getActionMap();

        // Ctrl+B: Kalın
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK), "bold");
        actionMap.put("bold", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                boldButton.setSelected(!boldButton.isSelected());
                updateFontStyle();
            }
        });

        // Ctrl+I: İtalik
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), "italic");
        actionMap.put("italic", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                italicButton.setSelected(!italicButton.isSelected());
                updateFontStyle();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK), "underline");
        actionMap.put("underline", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                underlineButton.setSelected(!underlineButton.isSelected());
                updateFontStyle();
            }
        });

        editorPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                System.out.println("DEBUG: DocumentListener.insertUpdate - isUpdatingFromServer: " + isUpdatingFromServer);

                // Server update ise işleme
                if (!isUpdatingFromServer) {
                    // EDT kontrolü
                    if (SwingUtilities.isEventDispatchThread()) {
                        handleTextChange();
                    } else {
                        SwingUtilities.invokeLater(() -> handleTextChange());
                    }
                } else {
                    System.out.println("DEBUG: Skipping handleTextChange - server update");
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                System.out.println("DEBUG: DocumentListener.removeUpdate - isUpdatingFromServer: " + isUpdatingFromServer);

                if (!isUpdatingFromServer) {
                    if (SwingUtilities.isEventDispatchThread()) {
                        handleTextChange();
                    } else {
                        SwingUtilities.invokeLater(() -> handleTextChange());
                    }
                } else {
                    System.out.println("DEBUG: Skipping handleTextChange - server update");
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Style değişiklikleri - space karakteri için gerekli değil
                System.out.println("DEBUG: DocumentListener.changedUpdate (style change)");
            }
        });

        // 🔧 SPACE DEBUG İÇİN KeyListener EKLE
        editorPane.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();

                if (c == ' ' || c == '\u0020') {
                    System.out.println("DEBUG: *** SPACE TYPED! *** char='" + c + "' ascii=" + (int)c);
                }

                // Diğer özel karakterler
                if (Character.isISOControl(c) && c != '\b' && c != '\t' && c != '\n' && c != '\r') {
                    System.out.println("DEBUG: Control character typed: " + (int)c);
                }
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    System.out.println("DEBUG: *** SPACE PRESSED! *** keyCode=" + e.getKeyCode());
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    System.out.println("DEBUG: *** SPACE RELEASED! *** keyCode=" + e.getKeyCode());
                }
            }
        });

        // 🔧 SPACE INPUT DEBUG - FocusListener ekle
        editorPane.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                System.out.println("DEBUG: EditorPane focus GAINED");
            }

            @Override
            public void focusLost(FocusEvent e) {
                System.out.println("DEBUG: EditorPane focus LOST");
            }
        });

        JScrollPane scrollPane = new JScrollPane(editorPane);
        panel.add(toolBar, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        System.out.println("DEBUG: createEditorPanel completed with space debugging");
        return panel;
    }


    private void updateFontStyle() {
        StyledDocument doc = editorPane.getStyledDocument();
        Style style = doc.addStyle("currentStyle", null);

        String fontFamily = fontFamilyCombo.getSelectedItem().toString();
        int fontSize = (Integer) fontSizeCombo.getSelectedItem();

        StyleConstants.setFontFamily(style, fontFamily);
        StyleConstants.setFontSize(style, fontSize);
        StyleConstants.setBold(style, boldButton.isSelected());
        StyleConstants.setItalic(style, italicButton.isSelected());
        StyleConstants.setUnderline(style, underlineButton.isSelected());

        if (currentTextColor != null) {
            StyleConstants.setForeground(style, currentTextColor);
        }

        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            doc.setCharacterAttributes(start, end - start, style, false);
        } else {
            editorPane.setCharacterAttributes(style, false);
        }
    }

    private void setupDragAndDrop() {
        editorPane.setDropTarget(new DropTarget() {
            public synchronized void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable transferable = evt.getTransferable();

                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        java.util.List<File> files = (java.util.List<File>) transferable
                                .getTransferData(DataFlavor.javaFileListFlavor);

                        for (File file : files) {
                            // Dosya işleme kodunu buraya ekle
                            handleDroppedFile(file);
                        }
                    }
                } catch (Exception e) {
                    ExceptionHandler.handle(e, "Dosya sürükleme işlemi başarısız oldu");
                }
            }
        });
    }

    private void handleDroppedFile(File file) {
        // Dosya işleme kodunu buraya ekle
        String fileName = file.getName();
        if (isValidFileName(fileName)) {
            networkManager.createDocument(fileName);
        } else {
            showError("Geçersiz dosya adı: " + fileName);
        }
    }

    private void showFindDialog() {
        JDialog dialog = new JDialog(this, "Metin Bul", true);
        dialog.setLayout(new BorderLayout(5, 5));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        JTextField searchField = new JTextField(20);
        JButton findButton = new JButton("Bul");
        JButton findNextButton = new JButton("Sonrakini Bul");

        panel.add(new JLabel("Aranan:"));
        panel.add(searchField);
        panel.add(findButton);
        panel.add(findNextButton);

        dialog.add(panel, BorderLayout.CENTER);

        ActionListener findAction = e -> {
            findText(searchField.getText());
            if (e.getSource() == findButton) {
                dialog.dispose();
            }
        };

        findButton.addActionListener(findAction);
        findNextButton.addActionListener(findAction);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showReplaceDialog() {
        JDialog dialog = new JDialog(this, "Bul ve Değiştir", true);
        dialog.setLayout(new BorderLayout(5, 5));

        JPanel panel = new JPanel(new GridLayout(2, 3, 5, 5));
        JTextField findField = new JTextField();
        JTextField replaceField = new JTextField();
        JButton findButton = new JButton("Bul");
        JButton replaceButton = new JButton("Değiştir");
        JButton replaceAllButton = new JButton("Tümünü Değiştir");

        panel.add(new JLabel("Aranan:"));
        panel.add(findField);
        panel.add(findButton);
        panel.add(new JLabel("Yeni:"));
        panel.add(replaceField);
        panel.add(replaceButton);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(replaceAllButton);

        dialog.add(panel, BorderLayout.CENTER);
        dialog.add(bottomPanel, BorderLayout.SOUTH);

        findButton.addActionListener(e -> findText(findField.getText()));
        replaceButton.addActionListener(e -> replaceText(findField.getText(), replaceField.getText(), false));
        replaceAllButton.addActionListener(e -> replaceText(findField.getText(), replaceField.getText(), true));

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void findText(String searchText) {
        if (searchText == null || searchText.isEmpty())
            return;

        try {
            javax.swing.text.Document doc = editorPane.getDocument();
            String text = doc.getText(0, doc.getLength());
            int pos = text.indexOf(searchText, editorPane.getCaretPosition());

            if (pos >= 0) {
                editorPane.setCaretPosition(pos);
                editorPane.moveCaretPosition(pos + searchText.length());
                editorPane.requestFocusInWindow();
            } else {
                // Baştan aramayı dene
                pos = text.indexOf(searchText, 0);
                if (pos >= 0) {
                    editorPane.setCaretPosition(pos);
                    editorPane.moveCaretPosition(pos + searchText.length());
                    editorPane.requestFocusInWindow();
                } else {
                    JOptionPane.showMessageDialog(this,
                            "\"" + searchText + "\" bulunamadı.",
                            "Arama Sonucu",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        } catch (Exception e) {
            ExceptionHandler.handle(e, "Arama işlemi başarısız oldu");
        }
    }

    private void replaceText(String findText, String replaceText, boolean replaceAll) {
        if (findText == null || findText.isEmpty())
            return;

        try {
            javax.swing.text.Document doc = editorPane.getDocument();
            String text = doc.getText(0, doc.getLength());

            if (replaceAll) {
                // Tümünü değiştir
                text = text.replace(findText, replaceText);
                editorPane.setText(text);
            } else {
                // Seçili metni değiştir
                int start = editorPane.getSelectionStart();
                int end = editorPane.getSelectionEnd();

                if (end > start) {
                    String selectedText = text.substring(start, end);
                    if (selectedText.equals(findText)) {
                        editorPane.replaceSelection(replaceText);
                    }
                }
                // Sonrakini bul
                findText(findText);
            }
        } catch (Exception e) {
            ExceptionHandler.handle(e, "Değiştirme işlemi başarısız oldu");
        }
    }

    private void applyTheme(String theme) {
        currentTheme = theme;

        if ("dark".equals(theme)) {
            editorPane.setBackground(new Color(43, 43, 43));
            editorPane.setForeground(new Color(169, 183, 198));
            editorPane.setCaretColor(Color.WHITE);

            chatArea.setBackground(new Color(43, 43, 43));
            chatArea.setForeground(new Color(169, 183, 198));
            chatArea.setCaretColor(Color.WHITE);

            documentList.setBackground(new Color(43, 43, 43));
            documentList.setForeground(new Color(169, 183, 198));
        } else {
            editorPane.setBackground(Color.WHITE);
            editorPane.setForeground(Color.BLACK);
            editorPane.setCaretColor(Color.BLACK);

            chatArea.setBackground(Color.WHITE);
            chatArea.setForeground(Color.BLACK);
            chatArea.setCaretColor(Color.BLACK);

            documentList.setBackground(Color.WHITE);
            documentList.setForeground(Color.BLACK);
        }
    }

    private void handleNewDocument() {
        String docName = JOptionPane.showInputDialog(this,
                "Doküman adını giriniz:",
                "Yeni Doküman",
                JOptionPane.PLAIN_MESSAGE);

        if (docName != null && !docName.trim().isEmpty()) {
            docName = docName.trim();

            System.out.println("=== NEW DOCUMENT CREATION ===");
            System.out.println("DEBUG: User entered document name: '" + docName + "'");

            // File name validation
            if (!isValidFileName(docName)) {
                System.out.println("ERROR: Invalid filename: " + docName);
                return;
            }

            // Check for duplicate names (optional)
            boolean nameExists = false;
            for (int i = 0; i < listModel.size(); i++) {
                FileDisplayItem item = listModel.getElementAt(i);
                if (item.getFileName().equals(docName)) {
                    nameExists = true;
                    break;
                }
            }

            if (nameExists) {
                int result = JOptionPane.showConfirmDialog(this,
                        "Aynı isimde bir dosya zaten var. Yine de oluşturmak istiyor musunuz?",
                        "Dosya Adı Çakışması",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);

                if (result != JOptionPane.YES_OPTION) {
                    System.out.println("DEBUG: User cancelled due to name conflict");
                    return;
                }
            }

            try {
                System.out.println("DEBUG: Sending create document request: " + docName);
                statusLabel.setText("📄 Doküman oluşturuluyor: " + docName + "...");

                // Send creation request
                networkManager.createDocument(docName);

                System.out.println("SUCCESS: Create document request sent");

                // 🔧 OPTIONAL: Add loading indicator
                javax.swing.Timer loadingTimer = new javax.swing.Timer(100, null);
                loadingTimer.addActionListener(e -> {
                    String currentText = statusLabel.getText();
                    if (currentText.contains("...")) {
                        statusLabel.setText(currentText.replace("...", "."));
                    } else if (currentText.contains("..")) {
                        statusLabel.setText(currentText.replace("..", "..."));
                    } else if (currentText.contains(".")) {
                        statusLabel.setText(currentText.replace(".", ".."));
                    }
                });
                loadingTimer.start();

                // Stop loading after 5 seconds
                javax.swing.Timer stopTimer = new javax.swing.Timer(5000, e -> {
                    loadingTimer.stop();
                    if (statusLabel.getText().contains("oluşturuluyor")) {
                        statusLabel.setText("⏳ Doküman oluşturma işlemi devam ediyor...");
                    }
                });
                stopTimer.setRepeats(false);
                stopTimer.start();

            } catch (Exception e) {
                System.err.println("ERROR: Failed to send create document request: " + e.getMessage());
                e.printStackTrace();
                statusLabel.setText("❌ Doküman oluşturma hatası: " + e.getMessage());
                showError("Doküman oluşturma hatası: " + e.getMessage());
            }

            System.out.println("=== NEW DOCUMENT CREATION END ===");
        } else {
            System.out.println("DEBUG: User cancelled or entered empty name");
        }
    }


    private boolean isValidFileName(String fileName) {
        // Null kontrolü
        if (fileName == null) {
            showError("Dosya adı boş olamaz!");
            return false;
        }

        // Boş string kontrolü
        String trimmedName = fileName.trim();
        if (trimmedName.isEmpty()) {
            showError("Dosya adı boş olamaz!");
            return false;
        }

        // Uzunluk kontrolü
        if (trimmedName.length() > MAX_FILENAME_LENGTH) {
            showError("Dosya adı " + MAX_FILENAME_LENGTH + " karakterden uzun olamaz!");
            return false;
        }

        // Yasak karakter kontrolü
        for (char c : INVALID_FILENAME_CHARS.toCharArray()) {
            if (trimmedName.indexOf(c) != -1) {
                showError("Dosya adında şu karakterler kullanılamaz: " + INVALID_FILENAME_CHARS);
                return false;
            }
        }

        return true;
    }

    private void handleOpenDocument() {
        openSelectedFile(); // Aynı işlevi yap
    }

    private void handleSaveDocument() {
        FileDisplayItem selected = documentList.getSelectedValue();
        if (selected != null) {
            String fileId = selected.getFileId();
            String content = editorPane.getText();
            networkManager.updateDocument(fileId, content);
            statusLabel.setText("Dosya kaydediliyor: " + selected.getFileName());
        } else {
            showError("Lütfen bir doküman seçin!");
        }
    }

    private boolean containsTurkishCharacters(String text) {
        return text.matches(".*[çÇğĞıİöÖşŞüÜ].*");
    }

    // 🔧 MEVCUT: Insert operation (değişiklik yok ama newline debug ekle)
    private void handleInsertOperation(String fileId, String currentContent) {
        try {
            int insertPosition = findInsertPosition(lastContent, currentContent);
            int insertLength = currentContent.length() - lastContent.length();
            String insertedText = currentContent.substring(insertPosition, insertPosition + insertLength);

            System.out.println("DEBUG: INSERT detected");
            System.out.println("DEBUG: Insert position: " + insertPosition);
            System.out.println("DEBUG: Inserted text: '" + insertedText.replace("\n", "\\n") + "'");

            // Türkçe karakter kontrolü (newline hariç)
            if (containsTurkishCharacters(insertedText)) {
                handleTurkishCharacterError(insertPosition, insertedText.length());
                return;
            }

            // Her karakteri ayrı ayrı gönder
            for (int i = 0; i < insertedText.length(); i++) {
                char c = insertedText.charAt(i);
                String singleChar = String.valueOf(c);
                int charPosition = insertPosition + i;

                System.out.println("DEBUG: Sending character '" +
                        (c == '\n' ? "NEWLINE" : c == ' ' ? "SPACE" : String.valueOf(c)) +
                        "' at position " + charPosition);

                networkManager.insertText(fileId, charPosition, singleChar);

                // Newline için ekstra delay
                if (c == '\n') {
                    try { Thread.sleep(50); } catch (InterruptedException e) {}
                } else if (insertedText.length() > 3) {
                    try { Thread.sleep(10); } catch (InterruptedException e) {}
                }
            }

            lastContent = currentContent;
            System.out.println("DEBUG: INSERT completed - lastContent updated");

        } catch (Exception e) {
            System.err.println("ERROR: handleInsertOperation failed: " + e.getMessage());
            e.printStackTrace();
            lastContent = currentContent; // Reset to prevent infinite issues
        }
    }

    private int findInsertPosition(String oldText, String newText) {
        if (oldText.isEmpty()) {
            System.out.println("DEBUG: findInsertPosition - oldText empty, returning 0");
            return 0;
        }

        // En uzun ortak prefix'i bul
        int commonPrefixLength = 0;
        int minLength = Math.min(oldText.length(), newText.length());

        for (int i = 0; i < minLength; i++) {
            if (oldText.charAt(i) == newText.charAt(i)) {
                commonPrefixLength++;
            } else {
                break;
            }
        }

        System.out.println("DEBUG: findInsertPosition - common prefix length: " + commonPrefixLength);

        // Pozisyon debug
        if (commonPrefixLength > 0) {
            char lastCommonChar = oldText.charAt(commonPrefixLength - 1);
            System.out.println("DEBUG: Last common character: '" +
                    (lastCommonChar == '\n' ? "NEWLINE" : String.valueOf(lastCommonChar)) + "'");
        }

        return commonPrefixLength;
    }


    private void handleTurkishCharacterError(int position, int length) {
        SwingUtilities.invokeLater(() -> {
            try {
                isUpdatingFromServer = true;
                editorPane.getDocument().remove(position, length);
                isUpdatingFromServer = false;

                statusLabel.setText("Lütfen İngilizce karakterler kullanınız (ç, ğ, ı, ö, ş, ü kullanılamaz)");
                JOptionPane.showMessageDialog(this,
                        "Lütfen İngilizce karakterler kullanınız.\nTürkçe karakterler (ç, ğ, ı, ö, ş, ü) desteklenmemektedir.",
                        "Geçersiz Karakter",
                        JOptionPane.WARNING_MESSAGE);
            } catch (Exception e) {
                isUpdatingFromServer = false;
                ExceptionHandler.handle(e, "Metin düzeltme sırasında hata oluştu");
            }
        });
    }
    private void setupTextEditor() {

        editorPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                // EDT kontrolü ve handleTextChange çağrısı
                if (SwingUtilities.isEventDispatchThread()) {
                    handleTextChange();
                } else {
                    SwingUtilities.invokeLater(() -> handleTextChange());
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (SwingUtilities.isEventDispatchThread()) {
                    handleTextChange();
                } else {
                    SwingUtilities.invokeLater(() -> handleTextChange());
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Style changes - genellikle boş
                // Space karakteri için gerekli değil
            }
        });

        // 🔧 Space tuşu için özel KeyListener ekle (DEBUG amaçlı)
        editorPane.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (c == ' ') {
                    System.out.println("DEBUG: Space key typed - char: '" + c + "' (ASCII: " + (int)c + ")");
                }

                // Özel karakterleri logla
                if (Character.isISOControl(c) && c != '\b' && c != '\t' && c != '\n') {
                    System.out.println("DEBUG: Control character typed: " + (int)c);
                }
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    System.out.println("DEBUG: Space key pressed - keyCode: " + e.getKeyCode());
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    System.out.println("DEBUG: Space key released - keyCode: " + e.getKeyCode());
                }
            }
        });

        System.out.println("DEBUG: Text editor setup completed with space character debugging");
    }

    private JPanel createChatPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Sohbet başlığı
        JLabel chatLabel = new JLabel("Sohbet");
        chatLabel.setFont(new Font("Arial", Font.BOLD, 14));
        panel.add(chatLabel, BorderLayout.NORTH);

        // Sohbet alanı
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Arial", Font.PLAIN, 12));

        JScrollPane chatScroller = new JScrollPane(chatArea);
        chatScroller.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        panel.add(chatScroller, BorderLayout.CENTER);

        // Mesaj gönderme alanı
        JPanel messagePanel = new JPanel(new BorderLayout(5, 0));
        messageField = new JTextField();
        JButton sendButton = new JButton("Gönder");

        messagePanel.add(messageField, BorderLayout.CENTER);
        messagePanel.add(sendButton, BorderLayout.EAST);
        panel.add(messagePanel, BorderLayout.SOUTH);

        return panel;
    }
}