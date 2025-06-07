package org.multiuserwordeditor.network;

import org.multiuserwordeditor.model.Document;
import org.multiuserwordeditor.model.Message;

import java.io.*;
import java.net.Socket;
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

            // Mesaj okuma thread'ini başlat
            executorService.submit(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        handleServerMessage(line);
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
        if (isConnected && writer != null) {
            try {
                String message = String.format("%s%s%s%s%s%s%s%s%d%s",
                        type, DELIMITER,
                        userId != null ? userId : "null", DELIMITER,
                        fileId != null ? fileId : "null", DELIMITER,
                        data, DELIMITER,
                        System.currentTimeMillis(), MESSAGE_END);

                // UTF-8 olarak gönder
                byte[] messageBytes = message.getBytes("UTF-8");
                String utf8Message = new String(messageBytes, "UTF-8");

                writer.println(utf8Message);
                writer.flush();

                System.out.println("Gönderilen mesaj: " + utf8Message);
            } catch (Exception e) {
                handleError("Mesaj gönderilirken hata oluştu", e);
            }
        } else if (errorHandler != null) {
            errorHandler.accept("Sunucuya bağlı değil!");
        }
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
            if (filename == null || filename.trim().isEmpty()) {
                throw new IllegalArgumentException("Dosya adı boş olamaz");
            }

            // Dosya adını temizle ve UTF-8'e dönüştür
            String cleanFilename = new String(filename.trim().getBytes("UTF-8"), "UTF-8");

            // Mesajı oluştur ve gönder
            Message createMsg = Message.createFileCreate(userId, cleanFilename);
            String rawMessage = createMsg.serialize();

            // UTF-8 olarak gönder
            LOGGER.info("Doküman oluşturma isteği gönderiliyor: " + rawMessage);
            writer.println(rawMessage);
            writer.flush();
        } catch (IllegalArgumentException e) {
            handleError("Geçersiz dosya adı", e);
        } catch (Exception e) {
            handleError("Doküman oluşturulurken hata oluştu", e);
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

    public void updateDocument(Document doc, int position, String text, boolean isDelete) {
        try {
            Message updateMsg;
            if (isDelete) {
                updateMsg = Message.createTextDelete(userId, doc.getId(), position, text.length());
            } else {
                updateMsg = Message.createTextUpdate(userId, doc.getId(), position, text);
            }
            writer.println(updateMsg.serialize());
            writer.flush();
        } catch (Exception e) {
            handleError("Doküman güncellenirken hata", e);
        }
    }

    public void requestFileList() {
        try {
            Message listMsg = Message.createFileList(userId);
            writer.println(listMsg.serialize());
            writer.flush();
        } catch (Exception e) {
            handleError("Dosya listesi alınırken hata", e);
        }
    }



    private void handleServerMessage(String rawMessage) {
        try {
            System.out.println("=== RAW MESSAGE DEBUG ===");
            System.out.println("Raw mesaj: '" + rawMessage + "'");

            // ========== FILE_LIST_RESP ÖZL İŞLEME ==========
            if (rawMessage.startsWith("FILE_LIST_RESP|")) {
                System.out.println("DEBUG: FILE_LIST_RESP özel işleme başlıyor...");

                handleFileListResponseRaw(rawMessage);
                return;  // Normal deserialize'a gitme
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

    /**
     * FILE_LIST_RESP mesajlarını özel olarak işler
     */
    private void handleFileListResponseRaw(String rawMessage) {
        try {
            // Format: FILE_LIST_RESP|userId|fileId|files:file1:file1.txt:0|file2:file2.txt:0|...|timestamp
            String[] parts = rawMessage.split("\\|");

            if (parts.length < 4) {
                System.err.println("ERROR: Geçersiz FILE_LIST_RESP formatı");
                return;
            }

            String userId = "null".equals(parts[1]) ? null : parts[1];
            String fileId = "null".equals(parts[2]) ? null : parts[2];
            long timestamp = Long.parseLong(parts[parts.length - 1]);  // Son part timestamp

            // Files data'yı birleştir (Part[3]'ten Part[length-2]'ye kadar)
            StringBuilder filesDataBuilder = new StringBuilder();

            // İlk dosya part'ı (files: prefix'ini temizle)
            if (parts[3].startsWith("files:")) {
                filesDataBuilder.append(parts[3].substring("files:".length()));
            }

            // Diğer dosya part'larını ekle
            for (int i = 4; i < parts.length - 1; i++) {  // Son part timestamp olduğu için -1
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

    public void sendTextUpdate(String fileId, int position, String text, String operation) {
        try {
            Message message;
            switch (operation.toUpperCase()) {
                case "INSERT":
                    message = Message.createTextInsert(userId, fileId, position, text);
                    break;
                case "DELETE":
                    message = Message.createTextDelete(userId, fileId, position, text.length());
                    break;
                default:
                    message = Message.createTextUpdate(userId, fileId, position, text);
                    break;
            }

            String rawMessage = message.serialize();
            System.out.println("Gönderilen metin güncellemesi: " + rawMessage);
            writer.println(rawMessage);
            writer.flush();
        } catch (Exception e) {
            handleError("Metin güncellemesi gönderilirken hata", e);
        }
    }
}
