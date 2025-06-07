package org.multiuserwordeditor.model;

import java.util.*;
import java.lang.StringBuilder;

/**
 * MTP (Multi-user Text Protocol) Mesaj Sınıfı
 * Format: TYPE|USER_ID|FILE_ID|DATA_FIELD1:VALUE1,FIELD2:VALUE2|TIMESTAMP\n
 */
public class Message {
    // Mesaj tipleri - kategorilere ayrılmış
    public enum MessageType {
        // 1. Bağlantı İşlemleri
        CONNECT, // İstemci -> Sunucu: Bağlantı isteği
        CONNECT_ACK, // Sunucu -> İstemci: Bağlantı yanıtı
        DISCONNECT, // İstemci <-> Sunucu: Bağlantı sonlandırma

        // 2. Dosya İşlemleri
        FILE_LIST, // İstemci -> Sunucu: Dosya listesi isteği
        FILE_LIST_RESP, // Sunucu -> İstemci: Dosya listesi yanıtı
        FILE_CREATE, // İstemci -> Sunucu: Yeni dosya oluşturma
        FILE_OPEN, // İstemci -> Sunucu: Dosya açma isteği
        FILE_CONTENT, // Sunucu -> İstemci: Dosya içeriği

        // 3. Metin Düzenleme İşlemleri
        TEXT_INSERT, // İstemci <-> Sunucu: Metin ekleme
        TEXT_DELETE, // İstemci <-> Sunucu: Metin silme
        TEXT_UPDATE, // İstemci <-> Sunucu: Metin güncelleme

        // 4. Kullanıcı Yönetimi
        REGISTER, // İstemci -> Sunucu: Kayıt isteği
        REGISTER_ACK, // Sunucu -> İstemci: Kayıt yanıtı
        LOGIN, // İstemci -> Sunucu: Giriş isteği
        LOGIN_ACK, // Sunucu -> İstemci: Giriş yanıtı

        // 5. Diğer İşlemler
        SAVE, // İstemci -> Sunucu: Kaydetme isteği
        ERROR // Sunucu -> İstemci: Hata bildirimi
    }

    // Mesaj alanları
    private MessageType type;
    private String userId;
    private String fileId;
    private Map<String, String> data;
    private long timestamp;

    // Sabitler
    private static final String DELIMITER = "|";
    private static final String MESSAGE_END = "\n";
    private static final String DATA_SEPARATOR = ",";
    private static final String KEY_VALUE_SEPARATOR = ":";

    // Constructors
    public Message() {
        this.data = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String userId, String fileId) {
        this();
        this.type = type;
        this.userId = userId;
        this.fileId = fileId;
    }
    // Message.java'ya eklenecek debug metodları

    /**
     * Tüm data key'lerini döndürür - debug için
     */
    public Set<String> getAllDataKeys() {
        if (data == null) {
            return new HashSet<>();
        }
        return data.keySet();
    }

    /**
     * Raw data Map'ini döndürür - debug için
     */
    public Map<String, String> getAllData() {
        return data != null ? new HashMap<>(data) : new HashMap<>();
    }

    /**
     * Message'ı debug formatında yazdırır
     */
    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Message{");
        sb.append("type=").append(type);
        sb.append(", userId='").append(userId).append("'");
        sb.append(", fileId='").append(fileId).append("'");
        sb.append(", timestamp=").append(timestamp);
        sb.append(", data={");

        if (data != null) {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                sb.append(entry.getKey()).append("='").append(entry.getValue()).append("', ");
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    // Data ekleme metotları
    public Message addData(String key, String value) {
        if (key != null && value != null) {
            data.put(key, value);
        }
        return this;
    }

    public Message addData(String key, int value) {
        return addData(key, String.valueOf(value));
    }

    public Message addData(String key, boolean value) {
        return addData(key, String.valueOf(value));
    }

    // Data alma metotları
    public String getData(String key) {
        return data.get(key);
    }

    public Integer getDataAsInt(String key) {
        String value = getData(key);
        if (value == null)
            return null;

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Boolean getDataAsBoolean(String key) {
        String value = getData(key);
        if (value == null)
            return null;
        return Boolean.parseBoolean(value);
    }

    // Factory metotları - Bağlantı İşlemleri
    public static Message createConnect(String username) {
        return new Message(MessageType.CONNECT, null, null)
                .addData("username", username);
    }

    public static Message createConnectAck(String userId, boolean success, String message) {
        return new Message(MessageType.CONNECT_ACK, userId, null)
                .addData("status", success ? "success" : "fail")
                .addData("message", message);
    }

    public static Message createDisconnect(String userId, String reason) {
        return new Message(MessageType.DISCONNECT, userId, null)
                .addData("reason", reason);
    }

    // Factory metotları - Dosya İşlemleri
    public static Message createFileList(String userId) {
        return new Message(MessageType.FILE_LIST, userId, null);
    }

    public static Message createFileListResponse(String userId, List<String> files) {
        return new Message(MessageType.FILE_LIST_RESP, userId, null)
                .addData("files", String.join(",", files));
    }

    public static Message createFileCreate(String userId, String fileName) {
        return new Message(MessageType.FILE_CREATE, userId, null)
                .addData("name", fileName);
    }

    public static Message createFileOpen(String userId, String fileId) {
        return new Message(MessageType.FILE_OPEN, userId, fileId);
    }

    public static Message createFileContent(String userId, String fileId, String content) {
        return new Message(MessageType.FILE_CONTENT, userId, fileId)
                .addData("content", content);
    }

    // Factory metotları - Metin Düzenleme İşlemleri
    public static Message createTextInsert(String userId, String fileId, int position, String text) {
        return new Message(MessageType.TEXT_INSERT, userId, fileId)
                .addData("position", position)
                .addData("text", text);
    }

    public static Message createTextDelete(String userId, String fileId, int position, int length) {
        return new Message(MessageType.TEXT_DELETE, userId, fileId)
                .addData("position", position)
                .addData("length", length);
    }

    public static Message createTextUpdate(String userId, String fileId, int position, String text) {
        return new Message(MessageType.TEXT_UPDATE, userId, fileId)
                .addData("position", position)
                .addData("text", text);
    }

    // Factory metotları - Kullanıcı Yönetimi
    public static Message createRegister(String username, String password) {
        return new Message(MessageType.REGISTER, null, null)
                .addData("username", username)
                .addData("password", password);
    }

    public static Message createRegisterAck(boolean success, String message) {
        return new Message(MessageType.REGISTER_ACK, null, null)
                .addData("status", success ? "success" : "fail")
                .addData("message", message);
    }

    public static Message createLogin(String username, String password) {
        return new Message(MessageType.LOGIN, null, null)
                .addData("username", username)
                .addData("password", password);
    }

    public static Message createLoginAck(String userId, boolean success, String message) {
        return new Message(MessageType.LOGIN_ACK, userId, null)
                .addData("status", success ? "success" : "fail")
                .addData("message", message);
    }

    // Factory metotları - Diğer İşlemler
    public static Message createSave(String userId, String fileId) {
        return new Message(MessageType.SAVE, userId, fileId);
    }

    public static Message createError(String userId, String errorMessage) {
        return new Message(MessageType.ERROR, userId, null)
                .addData("message", errorMessage);
    }

    // Serialize - mesajı string'e çevir
    public String serialize() {
        try {
            StringBuilder sb = new StringBuilder();

            // TYPE|USER_ID|FILE_ID|DATA|TIMESTAMP\n
            sb.append(type != null ? type.name() : "NULL").append(DELIMITER);
            sb.append(userId != null ? userId : "null").append(DELIMITER);
            sb.append(fileId != null ? fileId : "null").append(DELIMITER);
            sb.append(serializeData()).append(DELIMITER);
            sb.append(timestamp).append(MESSAGE_END);

            // UTF-8 olarak kodla
            return new String(sb.toString().getBytes("UTF-8"), "UTF-8");
        } catch (Exception e) {
            System.err.println("Mesaj serileştirme hatası: " + e.getMessage());
            return null;
        }
    }

    // Data'yı serialize et: key1:value1,key2:value2
    private String serializeData() {
        try {
            if (data.isEmpty()) {
                return "empty";
            }

            StringBuilder sb = new StringBuilder();
            boolean first = true;

            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (!first)
                    sb.append(DATA_SEPARATOR);

                // Key ve value'yu UTF-8 olarak kodla
                String key = new String(entry.getKey().getBytes("UTF-8"), "UTF-8");
                String value = entry.getValue() != null ? new String(entry.getValue().getBytes("UTF-8"), "UTF-8") : "";

                sb.append(key).append(KEY_VALUE_SEPARATOR).append(value);
                first = false;
            }

            return sb.toString();
        } catch (Exception e) {
            System.err.println("Data serileştirme hatası: " + e.getMessage());
            return "empty";
        }
    }

    // Deserialize - string'den mesaj oluştur
    public static Message deserialize(String rawMessage) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return null;
        }

        try {
            // UTF-8'e dönüştür
            rawMessage = new String(rawMessage.getBytes("ISO-8859-1"), "UTF-8");

            // \n'i temizle
            String cleanMessage = rawMessage.trim();
            if (cleanMessage.endsWith(MESSAGE_END)) {
                cleanMessage = cleanMessage.substring(0, cleanMessage.length() - 1);
            }

            // | ile ayır
            String[] parts = cleanMessage.split("\\" + DELIMITER, 5);
            if (parts.length != 5) {
                throw new Exception("Geçersiz mesaj formatı: " + parts.length + " parça");
            }

            Message message = new Message();

            // MessageType
            try {
                message.type = MessageType.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                throw new Exception("Geçersiz mesaj tipi: " + parts[0]);
            }

            // User ID
            message.userId = "null".equals(parts[1]) ? null : parts[1];

            // File ID
            message.fileId = "null".equals(parts[2]) ? null : parts[2];

            // Data - UTF-8 olarak parse et
            message.parseData(new String(parts[3].getBytes("UTF-8"), "UTF-8"));

            // Timestamp
            try {
                message.timestamp = Long.parseLong(parts[4]);
            } catch (NumberFormatException e) {
                message.timestamp = System.currentTimeMillis();
            }

            return message;
        } catch (Exception e) {
            System.err.println("Mesaj parse hatası: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // Data string'ini parse et
    private void parseData(String dataString) {
        if ("empty".equals(dataString) || dataString.trim().isEmpty()) {
            return;
        }

        String[] pairs = dataString.split(DATA_SEPARATOR);

        for (String pair : pairs) {
            String[] keyValue = pair.split(KEY_VALUE_SEPARATOR, 2);
            if (keyValue.length == 2) {
                data.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
    }

    // Validation
    public boolean isValid() {
        return type != null && timestamp > 0;
    }

    // Getters & Setters
    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, String> getData() {
        return new HashMap<>(data);
    }

    // Debug
    @Override
    public String toString() {
        return String.format("Message{type=%s, userId='%s', fileId='%s', data=%s, timestamp=%d}",
                type, userId, fileId, data, timestamp);
    }
}