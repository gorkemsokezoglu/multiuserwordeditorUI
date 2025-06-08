package org.multiuserwordeditor.network;

import org.multiuserwordeditor.model.Document;
import org.multiuserwordeditor.model.Message;

import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

public class NetworkManager {
    private static final Logger LOGGER = Logger.getLogger(NetworkManager.class.getName());

    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private ExecutorService executorService;
    private boolean isConnected;
    private String userId;
    private Consumer<Message> messageHandler;
    private Consumer<String> errorHandler;
    private Consumer<Document> documentUpdateHandler;
    private Consumer<String> userListUpdateHandler;
    private List<String> operationHistory = new ArrayList<>();

    // Mesaj formatı sabitleri
    private static final String DELIMITER = "|";
    private static final String DATA_SEPARATOR = ",";
    private static final String KEY_VALUE_SEPARATOR = ":";
    private static final String MESSAGE_END = "\n";

    public NetworkManager() {
        this.executorService = Executors.newSingleThreadExecutor();
        this.isConnected = false;
    }

    public void connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            writer = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8")), true);
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            isConnected = true;

            // 🔧 YENİ: Newline-aware mesaj okuma
            executorService.submit(() -> {
                try {
                    StringBuilder messageBuffer = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        System.out.println("DEBUG: Raw line received: '" + line + "'");

                        // Mesaj sonu kontrolü - | sayısı ile
                        if (isCompleteMessage(line)) {
                            // Tek başına complete mesaj
                            handleServerMessage(line);
                            messageBuffer.setLength(0); // Buffer'ı temizle
                        } else {
                            // Parçalı mesaj - buffer'a ekle
                            if (messageBuffer.length() > 0) {
                                messageBuffer.append("\n"); // Newline'ı restore et
                            }
                            messageBuffer.append(line);

                            // Buffer'daki mesaj complete mi kontrol et
                            String bufferedMessage = messageBuffer.toString();
                            if (isCompleteMessage(bufferedMessage)) {
                                handleServerMessage(bufferedMessage);
                                messageBuffer.setLength(0); // Buffer'ı temizle
                            }
                        }
                    }
                } catch (IOException e) {
                    handleError("Sunucu bağlantısı kesildi", e);
                }
            });

            LOGGER.info("Sunucuya bağlanıldı: " + host + ":" + port);
        } catch (IOException e) {
            handleError("Sunucuya bağlanılamadı", e);
        }
    }


    public void deleteDocument(String fileId) {
        try {
            System.out.println("=== NETWORK MANAGER DELETE DOCUMENT ===");
            System.out.println("DEBUG: Deleting document with fileId: '" + fileId + "'");

            if (fileId == null || fileId.trim().isEmpty()) {
                System.err.println("ERROR: FileId is null or empty");
                throw new IllegalArgumentException("Dosya ID boş olamaz");
            }

            if (!isConnected()) {
                System.err.println("ERROR: Not connected to server");
                throw new IllegalStateException("Sunucuya bağlı değil");
            }

            if (userId == null || userId.trim().isEmpty()) {
                System.err.println("ERROR: UserId is null or empty");
                throw new IllegalStateException("Kullanıcı girişi yapılmamış");
            }

            // Clean fileId
            String cleanFileId = fileId.trim();

            // Client side fileId format fix (if needed)
            if (cleanFileId.contains(":")) {
                String[] parts = cleanFileId.split(":");
                if (parts.length >= 1) {
                    cleanFileId = parts[0].trim();
                    System.out.println("DEBUG: Client-side fileId fix: '" + fileId + "' → '" + cleanFileId + "'");
                }
            }

            // Create and send delete message
            Message deleteMsg = Message.createFileDelete(userId, cleanFileId);
            String rawMessage = deleteMsg.serialize();

            System.out.println("DEBUG: Delete request - UserId: " + userId + ", FileId: " + cleanFileId);
            System.out.println("DEBUG: Raw delete message: " + rawMessage);

            // Send as UTF-8
            writer.println(rawMessage);
            writer.flush();

            // 🔧 ENHANCED: Auto-refresh file list after deletion with delay
            scheduleFileListRefresh();

            LOGGER.info("Document deletion request sent - UserId: " + userId + ", FileId: " + cleanFileId);
            System.out.println("SUCCESS: FILE_DELETE message sent successfully");
            System.out.println("=== NETWORK MANAGER DELETE DOCUMENT END ===");

        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: Invalid fileId: " + e.getMessage());
            handleError("Geçersiz dosya ID", e);
            throw e;
        } catch (IllegalStateException e) {
            System.err.println("ERROR: Invalid state: " + e.getMessage());
            handleError("Bağlantı sorunu", e);
            throw e;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to delete document '" + fileId + "': " + e.getMessage());
            e.printStackTrace();
            handleError("Doküman silinirken hata oluştu", e);
            LOGGER.severe("Document deletion error: " + e.getMessage());
            throw new RuntimeException("Document deletion failed", e);
        }
    }

    /**
     * 🔧 NEW: Check if user can delete document (optional validation)
     */
    public boolean canDeleteDocument(String fileId) {
        // Basic client-side validation
        if (fileId == null || fileId.trim().isEmpty()) {
            return false;
        }

        if (!isConnected() || userId == null) {
            return false;
        }

        return true;
    }

    private boolean isCompleteMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        // 🔧 YENİ: Daha akıllı pipe kontrolü
        // İlk 3 pipe'ı bul (TYPE|USER_ID|FILE_ID|)
        int firstPipe = message.indexOf('|');
        if (firstPipe == -1) return false;

        int secondPipe = message.indexOf('|', firstPipe + 1);
        if (secondPipe == -1) return false;

        int thirdPipe = message.indexOf('|', secondPipe + 1);
        if (thirdPipe == -1) return false;

        // Son pipe'ı bul (|TIMESTAMP)
        int lastPipe = message.lastIndexOf('|');
        if (lastPipe == thirdPipe) return false; // DATA kısmı yok

        // TIMESTAMP kısmı sayı olmalı
        try {
            String timestampPart = message.substring(lastPipe + 1);
            Long.parseLong(timestampPart.trim());

            boolean isComplete = true;

            // Debug için log
            System.out.println("DEBUG: Smart message check - isComplete: " + isComplete +
                    " timestamp: '" + timestampPart.trim() + "' message: '" +
                    (message.length() > 100 ? message.substring(0, 100) + "..." : message) + "'");

            return isComplete;

        } catch (NumberFormatException e) {
            System.out.println("DEBUG: Invalid timestamp in message: '" + message + "'");
            return false;
        }
    }

    public void disconnect() {
        if (isConnected && userId != null) {
            Message disconnectMsg = Message.createDisconnect(userId, "Client disconnected");
            writer.println(disconnectMsg.serialize());
            writer.flush();
        }

        try {
            isConnected = false;
            if (writer != null)
                writer.close();
            if (reader != null)
                reader.close();
            if (socket != null)
                socket.close();
            executorService.shutdown();
        } catch (IOException e) {
            handleError("Bağlantı kapatılırken hata", e);
        }
    }

    private String formatData(String... keyValues) {
        if (keyValues.length == 0)
            return "empty";
        if (keyValues.length % 2 != 0)
            return "empty";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0)
                sb.append(DATA_SEPARATOR);
            sb.append(keyValues[i]).append(KEY_VALUE_SEPARATOR).append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    private void sendMessage(String type, String userId, String fileId, String data) {
        // 🔧 DEPRECATED: Use sendMessageSafe instead
        sendMessageSafe(type, userId, fileId, data);
    }

    public void register(String username, String password) {
        try {
            Message registerMsg = Message.createRegister(username, password);
            writer.println(registerMsg.serialize());
            writer.flush();
        } catch (Exception e) {
            handleError("Kayıt olunurken hata", e);
        }
    }

    public void login(String username, String password) {
        try {
            Message loginMsg = Message.createLogin(username, password);
            writer.println(loginMsg.serialize());
            writer.flush();
        } catch (Exception e) {
            handleError("Giriş yapılırken hata", e);
        }
    }

    // Türkçe karakterleri İngilizce karakterlere dönüştüren yardımcı metod
    private String convertTurkishToEnglish(String text) {
        if (text == null)
            return null;

        return text.replace("ı", "i")
                .replace("ğ", "g")
                .replace("ü", "u")
                .replace("ş", "s")
                .replace("ö", "o")
                .replace("ç", "c")
                .replace("İ", "I")
                .replace("Ğ", "G")
                .replace("Ü", "U")
                .replace("Ş", "S")
                .replace("Ö", "O")
                .replace("Ç", "C");
    }

    public void createDocument(String filename) {
        try {
            System.out.println("=== ENHANCED NETWORK MANAGER CREATE DOCUMENT ===");
            System.out.println("DEBUG: Creating document with name: '" + filename + "'");

            if (filename == null || filename.trim().isEmpty()) {
                System.err.println("ERROR: Document name is null or empty");
                throw new IllegalArgumentException("Dosya adı boş olamaz");
            }

            if (!isConnected()) {
                System.err.println("ERROR: Not connected to server");
                throw new IllegalStateException("Sunucuya bağlı değil");
            }

            // Clean filename and convert to UTF-8
            String cleanFilename = new String(filename.trim().getBytes("UTF-8"), "UTF-8");

            // Create and send message
            Message createMsg = Message.createFileCreate(userId, cleanFilename);
            String rawMessage = createMsg.serialize();

            System.out.println("DEBUG: Document creation request - UserId: " + userId + ", Filename: " + cleanFilename);
            System.out.println("DEBUG: Raw message: " + rawMessage);

            // Send as UTF-8
            writer.println(rawMessage);
            writer.flush();

            // 🔧 ENHANCED: Auto-refresh file list after creation with delay
            scheduleFileListRefresh();

            LOGGER.info("Document creation request sent - UserId: " + userId + ", Filename: " + cleanFilename);
            System.out.println("SUCCESS: FILE_CREATE message sent successfully");
            System.out.println("=== ENHANCED NETWORK MANAGER CREATE DOCUMENT END ===");

        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: Invalid filename: " + e.getMessage());
            handleError("Geçersiz dosya adı", e);
            throw e;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to create document '" + filename + "': " + e.getMessage());
            e.printStackTrace();
            handleError("Doküman oluşturulurken hata oluştu", e);
            LOGGER.severe("Document creation error: " + e.getMessage());
            throw new RuntimeException("Document creation failed", e);
        }
    }

    public void openDocument(String fileId) {
        try {
            Message openMsg = Message.createFileOpen(userId, fileId);
            writer.println(openMsg.serialize());
            writer.flush();
        } catch (Exception e) {
            handleError("Doküman açılırken hata", e);
        }
    }

    public void saveDocument(String fileId) {
        try {
            Message saveMsg = Message.createSave(userId, fileId);
            writer.println(saveMsg.serialize());
            writer.flush();
        } catch (Exception e) {
            handleError("Doküman kaydedilirken hata", e);
        }
    }


// 🔧 1. CLIENT: NetworkManager.java - Enhanced NEWLINE message creation

    public void insertText(String fileId, int position, String text) {
        try {
            if (text == null || text.length() == 0) {
                LOGGER.warning("insertText: Invalid text");
                return;
            }

            // 🔧 ENHANCED MESSAGE CREATION WITH PROPER ESCAPING
            String data;
            if (text.equals(" ")) {
                data = "position:" + position + ",text:__SPACE__,userId:" + this.userId;
                System.out.println("DEBUG: Space character encoded as __SPACE__");

            } else if (text.equals("\n")) {
                // 🔧 CRITICAL FIX: Properly construct NEWLINE message
                data = "position:" + position + ",text:__NEWLINE__,userId:" + this.userId;
                System.out.println("DEBUG: *** NEWLINE character encoded as __NEWLINE__ ***");
                System.out.println("DEBUG: NEWLINE message data: '" + data + "'");

            } else if (text.equals("\r\n")) {
                data = "position:" + position + ",text:__CRLF__,userId:" + this.userId;
                System.out.println("DEBUG: CRLF encoded as __CRLF__");

            } else if (text.equals("\t")) {
                data = "position:" + position + ",text:__TAB__,userId:" + this.userId;
                System.out.println("DEBUG: Tab character encoded as __TAB__");

            } else {
                // Normal characters
                data = "position:" + position + ",text:" + text + ",userId:" + this.userId;
            }

            System.out.println("DEBUG: Final insertText data: '" + data + "'");

            // 🔧 SEND WITH ENHANCED MESSAGE CREATION
            sendMessageSafe("TEXT_INSERT", this.userId, fileId, data);

            LOGGER.info("insertText: Sent - pos:" + position + " text:'" +
                    (text.equals("\n") ? "NEWLINE" : text.equals(" ") ? "SPACE" : text) + "'");

        } catch (Exception e) {
            LOGGER.severe("insertText error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void sendMessageSafe(String type, String userId, String fileId, String data) {
        if (isConnected && writer != null) {
            try {
                // 🔧 CONSTRUCT MESSAGE MANUALLY WITH NEWLINE PROTECTION
                StringBuilder messageBuilder = new StringBuilder();
                messageBuilder.append(type).append(DELIMITER);
                messageBuilder.append(userId != null ? userId : "null").append(DELIMITER);
                messageBuilder.append(fileId != null ? fileId : "null").append(DELIMITER);
                messageBuilder.append(data != null ? data : "empty").append(DELIMITER);
                messageBuilder.append(System.currentTimeMillis());
                messageBuilder.append(MESSAGE_END); // Add final newline

                String finalMessage = messageBuilder.toString();

                // 🔧 DEBUG FOR NEWLINE MESSAGES
                if (data != null && data.contains("__NEWLINE__")) {
                    System.out.println("=== NEWLINE MESSAGE DEBUG ===");
                    System.out.println("DEBUG: Constructed message: '" + finalMessage.replace("\n", "\\n") + "'");
                    System.out.println("DEBUG: Message length: " + finalMessage.length());
                    System.out.println("DEBUG: Pipe count: " + (finalMessage.length() - finalMessage.replace("|", "").length()));
                    System.out.println("DEBUG: Ends with newline: " + finalMessage.endsWith("\n"));
                    System.out.println("========================");
                }

                // Send the message
                writer.print(finalMessage);
                writer.flush();

                System.out.println("DEBUG: Message sent successfully: " + type);

            } catch (Exception e) {
                LOGGER.severe("sendMessageSafe error: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("ERROR: Cannot send message - not connected or writer is null");
        }
    }

    public void requestFileList() {
        try {
            System.out.println("=== ENHANCED NETWORK MANAGER REQUEST FILE LIST ===");

            if (!isConnected()) {
                System.err.println("ERROR: Not connected to server");
                throw new IllegalStateException("Sunucuya bağlı değil");
            }

            Message listMsg = Message.createFileList(userId);
            String rawMessage = listMsg.serialize();

            System.out.println("DEBUG: Sending FILE_LIST request");
            System.out.println("DEBUG: Raw message: " + rawMessage);

            writer.println(rawMessage);
            writer.flush();

            System.out.println("SUCCESS: FILE_LIST request sent");
            System.out.println("=== ENHANCED NETWORK MANAGER REQUEST FILE LIST END ===");

        } catch (Exception e) {
            System.err.println("ERROR: Failed to request file list: " + e.getMessage());
            e.printStackTrace();
            handleError("Dosya listesi alınırken hata", e);

            // 🔧 RETRY MECHANISM: Auto-retry after 2 seconds
            scheduleFileListRetry();
        }
    }
    private void scheduleFileListRefresh() {
        // Use a timer to refresh file list after a short delay
        java.util.Timer refreshTimer = new java.util.Timer();
        refreshTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                System.out.println("DEBUG: Auto-refreshing file list after document creation");
                try {
                    requestFileList();
                } catch (Exception e) {
                    System.err.println("ERROR: Auto-refresh failed: " + e.getMessage());
                }
                refreshTimer.cancel();
            }
        }, 800); // 800ms delay to ensure server processing is complete
    }

    /**
     * 🔧 NEW: Schedule file list retry on failure
     */
    private void scheduleFileListRetry() {
        java.util.Timer retryTimer = new java.util.Timer();
        retryTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                System.out.println("DEBUG: Retrying file list request...");
                try {
                    requestFileList();
                } catch (Exception retryEx) {
                    System.err.println("ERROR: Retry also failed: " + retryEx.getMessage());
                }
                retryTimer.cancel();
            }
        }, 1000); // 2 second retry delay
    }

    private void handleServerMessage(String rawMessage) {
        try {
            System.out.println("=== RAW MESSAGE DEBUG ===");
            System.out.println("Raw mesaj: '" + rawMessage + "'");

            // ========== FILE_LIST_RESP ÖZL İŞLEME ==========
            if (rawMessage.startsWith("FILE_LIST_RESP|")) {
                System.out.println("DEBUG: FILE_LIST_RESP özel işleme başlıyor...");

                handleFileListResponseRaw(rawMessage);
                return; // Normal deserialize'a gitme
            }
            if (rawMessage.startsWith("FILE_DELETE_ACK|")) {
                System.out.println("DEBUG: FILE_DELETE_ACK özel işleme başlıyor...");
                handleFileDeleteAckRaw(rawMessage);
                return; // Normal deserialize'a gitme
            }


            // ========== DİĞER MESAJLAR NORMAL İŞLEME ==========
            Message message = Message.deserialize(rawMessage);

            if (messageHandler != null) {
                messageHandler.accept(message);
            }

        } catch (Exception e) {
            System.err.println("ERROR: Mesaj işleme hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void handleFileDeleteAckRaw(String rawMessage) {
        try {
            System.out.println("=== FILE DELETE ACK HANDLER DEBUG ===");
            System.out.println("DEBUG: Raw FILE_DELETE_ACK message: '" + rawMessage + "'");

            // Format: FILE_DELETE_ACK|userId|fileId|status:success,message:Dosya silindi|timestamp
            String[] parts = rawMessage.split("\\|");

            if (parts.length < 4) {
                System.err.println("ERROR: Geçersiz FILE_DELETE_ACK formatı - parts: " + parts.length);
                return;
            }

            String userId = "null".equals(parts[1]) ? null : parts[1];
            String fileId = "null".equals(parts[2]) ? null : parts[2];
            String dataSection = parts[3];
            long timestamp = parts.length > 4 ? Long.parseLong(parts[4]) : System.currentTimeMillis();

            System.out.println("DEBUG: Parsed - userId: " + userId + ", fileId: " + fileId);
            System.out.println("DEBUG: Data section: '" + dataSection + "'");

            // Parse data section
            Map<String, String> dataMap = new HashMap<>();
            if (!"empty".equals(dataSection)) {
                String[] dataPairs = dataSection.split(",");
                for (String pair : dataPairs) {
                    String[] keyValue = pair.split(":", 2);
                    if (keyValue.length == 2) {
                        dataMap.put(keyValue[0].trim(), keyValue[1].trim());
                    }
                }
            }

            String status = dataMap.get("status");
            String message = dataMap.get("message");
            boolean success = "success".equals(status);

            System.out.println("DEBUG: Delete result - success: " + success + ", message: '" + message + "'");

            // Manuel message oluştur
            Message deleteAckMessage = new Message(Message.MessageType.FILE_DELETE_ACK, userId, fileId);
            deleteAckMessage.addData("status", status != null ? status : "fail");
            deleteAckMessage.addData("message", message != null ? message : "Bilinmeyen hata");

            // Normal handler'a gönder
            if (messageHandler != null) {
                messageHandler.accept(deleteAckMessage);
            }

            System.out.println("SUCCESS: FILE_DELETE_ACK message processed successfully");
            System.out.println("=== FILE DELETE ACK HANDLER END ===");

        } catch (Exception e) {
            System.err.println("ERROR: FILE_DELETE_ACK raw parse hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    /**
     * FILE_LIST_RESP mesajlarını özel olarak işler
     */
    private void handleFileListResponseRaw(String rawMessage) {
        try {
            // Format:
            // FILE_LIST_RESP|userId|fileId|files:file1:file1.txt:0|file2:file2.txt:0|...|timestamp
            String[] parts = rawMessage.split("\\|");

            if (parts.length < 4) {
                System.err.println("ERROR: Geçersiz FILE_LIST_RESP formatı");
                return;
            }

            String userId = "null".equals(parts[1]) ? null : parts[1];
            String fileId = "null".equals(parts[2]) ? null : parts[2];
            long timestamp = Long.parseLong(parts[parts.length - 1]); // Son part timestamp

            // Files data'yı birleştir (Part[3]'ten Part[length-2]'ye kadar)
            StringBuilder filesDataBuilder = new StringBuilder();

            // İlk dosya part'ı (files: prefix'ini temizle)
            if (parts[3].startsWith("files:")) {
                filesDataBuilder.append(parts[3].substring("files:".length()));
            }

            // Diğer dosya part'larını ekle
            for (int i = 4; i < parts.length - 1; i++) { // Son part timestamp olduğu için -1
                filesDataBuilder.append("|").append(parts[i]);
            }

            String finalFilesData = filesDataBuilder.toString();
            System.out.println("DEBUG: Birleştirilmiş files data: '" + finalFilesData + "'");
            System.out.println("DEBUG: Files data uzunluk: " + finalFilesData.length());

            // Manuel message oluştur
            Message fileListMessage = new Message(Message.MessageType.FILE_LIST_RESP, userId, fileId);
            fileListMessage.addData("files", finalFilesData);

            // Normal handler'a gönder
            if (messageHandler != null) {
                messageHandler.accept(fileListMessage);
            }

        } catch (Exception e) {
            System.err.println("ERROR: FILE_LIST_RESP raw parse hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    public void forceFileListRefresh() {
        System.out.println("DEBUG: Force refresh requested");
        requestFileList();
    }

    private void handleError(String message, Exception e) {
        if (errorHandler != null) {
            errorHandler.accept(e != null ? message + ": " + e.getMessage() : message);
        }
    }

    private String extractDataValue(String data, String key) {
        if (data == null || key == null)
            return null;
        String[] pairs = data.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":");
            if (keyValue.length == 2 && key.equals(keyValue[0])) {
                return keyValue[1];
            }
        }
        return null;
    }

    public void setMessageHandler(Consumer<Message> handler) {
        this.messageHandler = handler;
    }

    public void setErrorHandler(Consumer<String> handler) {
        this.errorHandler = handler;
    }

    public void setDocumentUpdateHandler(Consumer<Document> handler) {
        this.documentUpdateHandler = handler;
    }

    public void setUserListUpdateHandler(Consumer<String> handler) {
        this.userListUpdateHandler = handler;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void updateDocument(String fileId, String content) {
        insertText(fileId, 0, content);
    }

    public void deleteText(String fileId, int position, int length) {
        try {
            String data = "position:" + position + ",length:" + length + ",userId:" + this.userId;

            // 🔧 DOĞRU sendMessage() ÇAĞRISI
            sendMessage("TEXT_DELETE", this.userId, fileId, data);

            LOGGER.info("deleteText: Metin silme isteği gönderiliyor - FileId: " + fileId +
                    ", Position: " + position + ", Length: " + length);

        } catch (Exception e) {
            LOGGER.severe("deleteText error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
