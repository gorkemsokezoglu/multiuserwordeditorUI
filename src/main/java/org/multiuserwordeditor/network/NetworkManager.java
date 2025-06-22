package org.multiuserwordeditor.network;

import org.multiuserwordeditor.model.Document;
import org.multiuserwordeditor.model.Message;

// WebSocket imports
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 
 * - MTP WebSocket Client Network Manager
 * - Socket'den WebSocket'e migrate edilmiş versiyon
 */
public class NetworkManager {
    private static final Logger LOGGER = Logger.getLogger(NetworkManager.class.getName());

    // WebSocket fields
    private WebSocketClient webSocketClient;
    private URI serverUri;
    private boolean isConnected;
    private String userId;

    // Common fields
    private ExecutorService executorService;
    private Consumer<Message> messageHandler;
    private Consumer<String> errorHandler;
    private Consumer<Document> documentUpdateHandler;
    private Consumer<String> userListUpdateHandler;
    private List<String> operationHistory = new ArrayList<>();

    // MTP Protocol constants
    private static final String DELIMITER = "|";
    private static final String DATA_SEPARATOR = ",";
    private static final String KEY_VALUE_SEPARATOR = ":";
    private static final String MESSAGE_END = "\n";

    public NetworkManager() {
        this.executorService = Executors.newSingleThreadExecutor();
        this.isConnected = false;

        LOGGER.info("WebSocket NetworkManager initialized");

    }

    // WebSocket connect method with connection ready waiting
    public void connect(String host, int port) {
        try {
            LOGGER.info("WebSocket connection başlatılıyor: " + host + ":" + port);

            // WebSocket URI oluştur
            String wsUrl = "ws://" + host + ":" + port;
            serverUri = new URI(wsUrl);

            LOGGER.info("WebSocket URI: " + wsUrl);

            // WebSocket client oluştur
            createWebSocketClient();

            // Bağlantıyı başlat
            webSocketClient.connect();

            LOGGER.info("WebSocket connection request sent");

            // 🔧 FIX: Wait for connection to be ready (with timeout)
            int maxWaitTime = 5000; // 5 seconds
            int waitInterval = 100; // 100ms intervals
            int totalWaited = 0;

            while (!isConnected && totalWaited < maxWaitTime) {
                try {
                    Thread.sleep(waitInterval);
                    totalWaited += waitInterval;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (isConnected) {
                LOGGER.info("✅ WebSocket connection established successfully in " + totalWaited + "ms");
            } else {
                LOGGER.warning("⚠ WebSocket connection timeout after " + maxWaitTime + "ms");
                throw new Exception("WebSocket connection timeout");
            }

        } catch (Exception e) {
            LOGGER.severe("WebSocket connection error: " + e.getMessage());
            handleError("WebSocket sunucusuna bağlanılamadı", e);
        }

    }

    // WebSocket client oluşturma
    private void createWebSocketClient() {
        webSocketClient = new WebSocketClient(serverUri) {

            @Override
            public void onOpen(ServerHandshake handshake) {
                LOGGER.info("✅ WebSocket connection opened!");
                isConnected = true;

                System.out.println("=== WEBSOCKET CLIENT CONNECTED ===");
                System.out.println("Server handshake: " + handshake.getHttpStatus());
                System.out.println("Ready to send/receive messages");
                System.out.println("================================");
            }

            @Override
            public void onMessage(String message) {
                try {
                    System.out.println("📨 WebSocket message received: " + message);
                    handleServerMessage(message);
                } catch (Exception e) {
                    LOGGER.severe("Message handling error: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                LOGGER.info(
                        "WebSocket connection closed. Code: " + code + ", Reason: " + reason + ", Remote: " + remote);
                isConnected = false;

                System.out.println("=== WEBSOCKET CLIENT DISCONNECTED ===");
                System.out.println("Close code: " + code);
                System.out.println("Reason: " + reason);
                System.out.println("Remote initiated: " + remote);
                System.out.println("===================================");

                if (errorHandler != null) {
                    errorHandler.accept("WebSocket bağlantısı kesildi: " + reason);
                }
            }

            @Override
            public void onError(Exception ex) {
                LOGGER.severe("WebSocket error: " + ex.getMessage());
                ex.printStackTrace();

                System.out.println("❌ WebSocket error: " + ex.getMessage());

                isConnected = false;
                handleError("WebSocket hatası", ex);
            }
        };

    }

    // WebSocket mesaj gönderme
    private void sendWebSocketMessage(String message) {
        if (webSocketClient != null && isConnected) {
            try {
                System.out.println("📤 Sending WebSocket message: " + message);
                webSocketClient.send(message);
                LOGGER.info("WebSocket message sent successfully");
            } catch (Exception e) {
                LOGGER.severe("Failed to send WebSocket message: " + e.getMessage());
                handleError("Mesaj gönderilemedi", e);
            }
        } else {
            LOGGER.warning("Cannot send message - WebSocket not connected");
            handleError("WebSocket bağlantısı yok", null);
        }
    }

    // Disconnect method
    public void disconnect() {
        try {
            if (isConnected && userId != null) {
                Message disconnectMsg = Message.createDisconnect(userId, "Client disconnected");
                sendWebSocketMessage(disconnectMsg.serialize());
            }

            isConnected = false;

            if (webSocketClient != null) {
                webSocketClient.close();
            }

            if (executorService != null) {
                executorService.shutdown();
            }

            LOGGER.info("WebSocket client disconnected successfully");

        } catch (Exception e) {
            LOGGER.severe("Disconnect error: " + e.getMessage());
        }

    }

    // Register method - WebSocket implementation
    public void register(String username, String password) {
        try {
            Message registerMsg = Message.createRegister(username, password);
            sendWebSocketMessage(registerMsg.serialize());
            LOGGER.info("Register request sent via WebSocket");
        } catch (Exception e) {
            handleError("Kayıt olunurken hata", e);
        }
    }

    // Login method - WebSocket implementation
    public void login(String username, String password) {
        try {
            Message loginMsg = Message.createLogin(username, password);
            sendWebSocketMessage(loginMsg.serialize());
            LOGGER.info("Login request sent via WebSocket");
        } catch (Exception e) {
            handleError("Giriş yapılırken hata", e);
        }
    }

    // Document creation - WebSocket implementation
    public void createDocument(String filename) {
        try {
            System.out.println("=== WEBSOCKET CREATE DOCUMENT ===");
            System.out.println("DEBUG: Creating document with name: '" + filename + "'");

            if (filename == null || filename.trim().isEmpty()) {
                System.err.println("ERROR: Document name is null or empty");
                throw new IllegalArgumentException("Dosya adı boş olamaz");
            }

            if (!isConnected()) {
                System.err.println("ERROR: Not connected to WebSocket server");
                throw new IllegalStateException("WebSocket sunucusuna bağlı değil");
            }

            // Clean filename and convert to UTF-8
            String cleanFilename = new String(filename.trim().getBytes("UTF-8"), "UTF-8");

            // Create and send message
            Message createMsg = Message.createFileCreate(userId, cleanFilename);
            String rawMessage = createMsg.serialize();

            System.out.println("DEBUG: Document creation request - UserId: " + userId + ", Filename: " + cleanFilename);
            System.out.println("DEBUG: Raw WebSocket message: " + rawMessage);

            // Send via WebSocket
            sendWebSocketMessage(rawMessage);

            LOGGER.info("Document creation request sent via WebSocket - UserId: " + userId + ", Filename: "
                    + cleanFilename);
            System.out.println("SUCCESS: FILE_CREATE WebSocket message sent successfully");

        } catch (Exception e) {
            System.err.println("ERROR: Failed to create document '" + filename + "': " + e.getMessage());
            e.printStackTrace();
            handleError("Doküman oluşturulurken hata oluştu", e);
            LOGGER.severe("Document creation error: " + e.getMessage());
        }

    }

    // Document opening - WebSocket implementation
    public void openDocument(String fileId) {
        try {
            if (fileId == null || fileId.trim().isEmpty()) {
                throw new IllegalArgumentException("Dosya ID boş olamaz");
            }

            if (!isConnected()) {
                throw new IllegalStateException("WebSocket sunucusuna bağlı değil");
            }

            Message openMsg = Message.createFileOpen(userId, fileId.trim());
            sendWebSocketMessage(openMsg.serialize());

            LOGGER.info("Document open request sent via WebSocket: " + fileId);
        } catch (Exception e) {
            handleError("Doküman açılırken hata", e);
        }

    }

    // Document deletion - WebSocket implementation
    public void deleteDocument(String fileId) {
        try {
            System.out.println("=== WEBSOCKET DELETE DOCUMENT ===");
            System.out.println("DEBUG: Deleting document with fileId: '" + fileId + "'");

            if (fileId == null || fileId.trim().isEmpty()) {
                System.err.println("ERROR: FileId is null or empty");
                throw new IllegalArgumentException("Dosya ID boş olamaz");
            }

            if (!isConnected()) {
                System.err.println("ERROR: Not connected to WebSocket server");
                throw new IllegalStateException("WebSocket sunucusuna bağlı değil");
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
            System.out.println("DEBUG: Raw WebSocket delete message: " + rawMessage);

            // Send via WebSocket
            sendWebSocketMessage(rawMessage);

            LOGGER.info(
                    "Document deletion request sent via WebSocket - UserId: " + userId + ", FileId: " + cleanFileId);
            System.out.println("SUCCESS: FILE_DELETE WebSocket message sent successfully");

        } catch (Exception e) {
            System.err.println("ERROR: Failed to delete document '" + fileId + "': " + e.getMessage());
            e.printStackTrace();
            handleError("Doküman silinirken hata oluştu", e);
            LOGGER.severe("Document deletion error: " + e.getMessage());
        }

    }

    // File list request - WebSocket implementation
    public void requestFileList() {
        try {
            System.out.println("=== WEBSOCKET REQUEST FILE LIST ===");

            if (!isConnected()) {
                System.err.println("ERROR: Not connected to WebSocket server");
                throw new IllegalStateException("WebSocket sunucusuna bağlı değil");
            }

            Message listMsg = Message.createFileList(userId);
            String rawMessage = listMsg.serialize();

            System.out.println("DEBUG: Sending FILE_LIST request via WebSocket");
            System.out.println("DEBUG: Raw WebSocket message: " + rawMessage);

            sendWebSocketMessage(rawMessage);

            System.out.println("SUCCESS: FILE_LIST WebSocket request sent");

        } catch (Exception e) {
            System.err.println("ERROR: Failed to request file list: " + e.getMessage());
            e.printStackTrace();
            handleError("Dosya listesi alınırken hata", e);
        }

    }

    // Text insertion - WebSocket implementation
    public void insertText(String fileId, int position, String text) {
        try {
            if (text == null || text.length() == 0) {
                LOGGER.warning("insertText: Invalid text");
                return;
            }

            if (!isConnected()) {
                throw new IllegalStateException("WebSocket sunucusuna bağlı değil");
            }

            // Enhanced message creation with proper escaping
            String data;
            if (text.equals(" ")) {
                data = "position:" + position + ",text:__SPACE__,userId:" + this.userId;
                System.out.println("DEBUG: Space character encoded as __SPACE__");

            } else if (text.equals("\n")) {
                data = "position:" + position + ",text:__NEWLINE__,userId:" + this.userId;
                System.out.println("DEBUG: *** NEWLINE character encoded as __NEWLINE__ ***");

            } else if (text.equals("\r\n")) {
                data = "position:" + position + ",text:__CRLF__,userId:" + this.userId;
                System.out.println("DEBUG: CRLF encoded as __CRLF__");

            } else if (text.equals("\t")) {
                data = "position:" + position + ",text:__TAB__,userId:" + this.userId;
                System.out.println("DEBUG: Tab character encoded as __TAB__");

            } else {
                data = "position:" + position + ",text:" + text + ",userId:" + this.userId;
            }

            System.out.println("DEBUG: Final insertText data: '" + data + "'");

            // Send with enhanced message creation
            sendMessageSafe("TEXT_INSERT", this.userId, fileId, data);

            LOGGER.info("insertText: Sent via WebSocket - pos:" + position + " text:'" +
                    (text.equals("\n") ? "NEWLINE" : text.equals(" ") ? "SPACE" : text) + "'");

        } catch (Exception e) {
            LOGGER.severe("insertText error: " + e.getMessage());
            e.printStackTrace();
        }

    }

    // Text deletion - WebSocket implementation
    public void deleteText(String fileId, int position, int length) {
        try {
            if (!isConnected()) {
                throw new IllegalStateException("WebSocket sunucusuna bağlı değil");
            }

            String data = "position:" + position + ",length:" + length + ",userId:" + this.userId;

            sendMessageSafe("TEXT_DELETE", this.userId, fileId, data);

            LOGGER.info("deleteText: Metin silme isteği WebSocket ile gönderildi - FileId: " + fileId +
                    ", Position: " + position + ", Length: " + length);

        } catch (Exception e) {
            LOGGER.severe("deleteText error: " + e.getMessage());
            e.printStackTrace();
        }

    }

    // Document update - WebSocket implementation
    public void updateDocument(String fileId, String content) {
        if (content != null && !content.isEmpty()) {
            insertText(fileId, 0, content);
        }
    }

    // Document save - WebSocket implementation
    public void saveDocument(String fileId) {
        try {
            if (!isConnected()) {
                throw new IllegalStateException("WebSocket sunucusuna bağlı değil");
            }

            Message saveMsg = Message.createSave(userId, fileId);
            sendWebSocketMessage(saveMsg.serialize());

            LOGGER.info("Document save request sent via WebSocket: " + fileId);
        } catch (Exception e) {
            handleError("Doküman kaydedilirken hata", e);
        }

    }

    // Enhanced message sending with WebSocket
    private void sendMessageSafe(String type, String userId, String fileId, String data) {
        if (isConnected() && webSocketClient != null) {
            try {
                // Construct message manually with newline protection
                StringBuilder messageBuilder = new StringBuilder();
                messageBuilder.append(type).append(DELIMITER);
                messageBuilder.append(userId != null ? userId : "null").append(DELIMITER);
                messageBuilder.append(fileId != null ? fileId : "null").append(DELIMITER);
                messageBuilder.append(data != null ? data : "empty").append(DELIMITER);
                messageBuilder.append(System.currentTimeMillis());
                messageBuilder.append(MESSAGE_END);

                String finalMessage = messageBuilder.toString();

                // Debug for newline messages
                if (data != null && data.contains("__NEWLINE__")) {
                    System.out.println("=== NEWLINE MESSAGE DEBUG ===");
                    System.out.println(
                            "DEBUG: Constructed WebSocket message: '" + finalMessage.replace("\n", "\\n") + "'");
                    System.out.println("DEBUG: Message length: " + finalMessage.length());
                    System.out.println(
                            "DEBUG: Pipe count: " + (finalMessage.length() - finalMessage.replace("|", "").length()));
                    System.out.println("DEBUG: Ends with newline: " + finalMessage.endsWith("\n"));
                    System.out.println("========================");
                }

                // Send via WebSocket
                webSocketClient.send(finalMessage);

                System.out.println("DEBUG: WebSocket message sent successfully: " + type);

            } catch (Exception e) {
                LOGGER.severe("sendMessageSafe error: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("ERROR: Cannot send message - WebSocket not connected");
        }

    }

    // Complete server message handling - migrated from Socket version
    private void handleServerMessage(String rawMessage) {
        try {
            System.out.println("=== WEBSOCKET RAW MESSAGE DEBUG ===");
            System.out.println("Raw WebSocket mesaj: '" + rawMessage + "'");

            // Special handling for FILE_LIST_RESP (just like Socket version)
            if (rawMessage.startsWith("FILE_LIST_RESP|")) {
                System.out.println("DEBUG: FILE_LIST_RESP özel işleme başlıyor...");
                handleFileListResponseRaw(rawMessage);
                return;
            }

            // Special handling for FILE_DELETE_ACK
            if (rawMessage.startsWith("FILE_DELETE_ACK|")) {
                System.out.println("DEBUG: FILE_DELETE_ACK özel işleme başlıyor...");
                handleFileDeleteAckRaw(rawMessage);
                return;
            }

            // Normal message deserialization for other message types
            Message message = Message.deserialize(rawMessage);

            if (message != null && messageHandler != null) {
                messageHandler.accept(message);
            } else if (message == null) {
                LOGGER.warning("Failed to deserialize WebSocket message: " + rawMessage);
            }

        } catch (Exception e) {
            System.err.println("ERROR: WebSocket mesaj işleme hatası: " + e.getMessage());
            e.printStackTrace();
        }

    }

    /**
     * - FILE_LIST_RESP mesajlarını özel olarak işler (Socket kodundan migrate)
     */
    private void handleFileListResponseRaw(String rawMessage) {
        try {
            System.out.println("=== WEBSOCKET FILE_LIST_RESP HANDLER ===");

            // 🔧 FIX: Remove any trailing newlines or whitespace
            String cleanMessage = rawMessage.trim();
            if (cleanMessage.endsWith("\n")) {
                cleanMessage = cleanMessage.substring(0, cleanMessage.length() - 1);
            }

            System.out.println("DEBUG: Cleaned message: '" + cleanMessage + "'");

            // Format:
            // FILE_LIST_RESP|userId|fileId|files:file1:file1.txt:0|file2:file2.txt:0|...|timestamp
            String[] parts = cleanMessage.split("\\|");

            if (parts.length < 4) {
                System.err.println("ERROR: Geçersiz FILE_LIST_RESP formatı - parts: " + parts.length);
                return;
            }

            String userId = "null".equals(parts[1]) ? null : parts[1];
            String fileId = "null".equals(parts[2]) ? null : parts[2];

            // 🔧 FIX: Clean timestamp before parsing
            String timestampStr = parts[parts.length - 1].trim();
            System.out.println("DEBUG: Raw timestamp string: '" + timestampStr + "'");

            long timestamp;
            try {
                timestamp = Long.parseLong(timestampStr);
                System.out.println("DEBUG: Parsed timestamp successfully: " + timestamp);
            } catch (NumberFormatException e) {
                System.err.println("ERROR: Failed to parse timestamp: '" + timestampStr + "'");
                timestamp = System.currentTimeMillis();
            }

            // Files data'yı birleştir (Part[3]'ten Part[length-2]'ye kadar)
            StringBuilder filesDataBuilder = new StringBuilder();

            // İlk dosya part'ı (files: prefix'ini temizle)
            if (parts[3].startsWith("files:")) {
                filesDataBuilder.append(parts[3].substring("files:".length()));
            }

            // Diğer dosya part'larını ekle
            for (int i = 4; i < parts.length - 1; i++) {
                filesDataBuilder.append("|").append(parts[i]);
            }

            String finalFilesData = filesDataBuilder.toString();
            System.out.println("DEBUG: WebSocket birleştirilmiş files data: '" + finalFilesData + "'");

            // Manuel message oluştur
            Message fileListMessage = new Message(Message.MessageType.FILE_LIST_RESP, userId, fileId);
            fileListMessage.addData("files", finalFilesData);

            // Normal handler'a gönder
            if (messageHandler != null) {
                messageHandler.accept(fileListMessage);
            }

            System.out.println("SUCCESS: FILE_LIST_RESP WebSocket message processed");

        } catch (Exception e) {
            System.err.println("ERROR: FILE_LIST_RESP WebSocket parse hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * - FILE_DELETE_ACK mesajlarını özel olarak işler (Socket kodundan migrate)
     */
    private void handleFileDeleteAckRaw(String rawMessage) {
        try {
            System.out.println("=== WEBSOCKET FILE DELETE ACK HANDLER ===");

            // 🔧 FIX: Remove any trailing newlines or whitespace
            String cleanMessage = rawMessage.trim();
            if (cleanMessage.endsWith("\n")) {
                cleanMessage = cleanMessage.substring(0, cleanMessage.length() - 1);
            }

            System.out.println("DEBUG: Raw WebSocket FILE_DELETE_ACK message: '" + cleanMessage + "'");

            // Format: FILE_DELETE_ACK|userId|fileId|status:success,message:Dosya
            // silindi|timestamp
            String[] parts = cleanMessage.split("\\|");

            if (parts.length < 4) {
                System.err.println("ERROR: Geçersiz FILE_DELETE_ACK formatı - parts: " + parts.length);
                return;
            }

            String userId = "null".equals(parts[1]) ? null : parts[1];
            String fileId = "null".equals(parts[2]) ? null : parts[2];
            String dataSection = parts[3];

            // 🔧 FIX: Clean timestamp before parsing
            String timestampStr = parts.length > 4 ? parts[4].trim() : String.valueOf(System.currentTimeMillis());
            long timestamp;
            try {
                timestamp = Long.parseLong(timestampStr);
            } catch (NumberFormatException e) {
                System.err.println("ERROR: Failed to parse DELETE timestamp: '" + timestampStr + "'");
                timestamp = System.currentTimeMillis();
            }

            System.out.println("DEBUG: WebSocket parsed - userId: " + userId + ", fileId: " + fileId);

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

            System.out.println("DEBUG: WebSocket delete result - success: " + success + ", message: '" + message + "'");

            // Manuel message oluştur
            Message deleteAckMessage = new Message(Message.MessageType.FILE_DELETE_ACK, userId, fileId);
            deleteAckMessage.addData("status", status != null ? status : "fail");
            deleteAckMessage.addData("message", message != null ? message : "Bilinmeyen hata");

            // Normal handler'a gönder
            if (messageHandler != null) {
                messageHandler.accept(deleteAckMessage);
            }

            System.out.println("SUCCESS: FILE_DELETE_ACK WebSocket message processed");

        } catch (Exception e) {
            System.err.println("ERROR: FILE_DELETE_ACK WebSocket parse hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Advanced message checking for WebSocket (migrated from Socket version)
    private boolean isCompleteMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        try {
            // Find pipe positions for TYPE|USER_ID|FILE_ID|DATA|TIMESTAMP structure
            int firstPipe = message.indexOf('|');
            if (firstPipe == -1)
                return false;

            int secondPipe = message.indexOf('|', firstPipe + 1);
            if (secondPipe == -1)
                return false;

            int thirdPipe = message.indexOf('|', secondPipe + 1);
            if (thirdPipe == -1)
                return false;

            int lastPipe = message.lastIndexOf('|');
            if (lastPipe == thirdPipe)
                return false;

            // Timestamp validation
            String timestampPart = message.substring(lastPipe + 1);
            Long.parseLong(timestampPart.trim());

            System.out.println(
                    "DEBUG: WebSocket message validation - complete: true, timestamp: '" + timestampPart.trim() + "'");
            return true;

        } catch (NumberFormatException e) {
            System.out.println("DEBUG: WebSocket invalid timestamp in message: '" + message + "'");
            return false;
        }

    }

    // Force file list refresh (compatibility method)
    public void forceFileListRefresh() {
        System.out.println("DEBUG: WebSocket force refresh requested");
        requestFileList();
    }

    // Utility methods
    public boolean isConnected() {
        return isConnected;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
        LOGGER.info("User ID set: " + userId);
    }

    // Handler setters
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

    // Error handling
    private void handleError(String message, Exception e) {
        if (errorHandler != null) {
            errorHandler.accept(e != null ? message + ":" + e.getMessage() : message);
        }
    }

    // Compatibility methods (empty implementations for now)
    public boolean canDeleteDocument(String fileId) {
        return fileId != null && !fileId.trim().isEmpty() && isConnected();
    }
}