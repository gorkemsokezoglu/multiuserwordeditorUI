package org.multiuserwordeditor.ui;

import org.multiuserwordeditor.model.Document;
import org.multiuserwordeditor.model.Message;
import org.multiuserwordeditor.network.NetworkManager;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;

public class MainWindow extends JFrame {
    private NetworkManager networkManager;
    private String userId;
    private JTextArea editorArea;
    private JTextArea chatArea;
    private JTextField messageField;
    private JList<String> documentList;
    private DefaultListModel<String> listModel;
    private JComboBox<String> fontStyleCombo;
    private JComboBox<Integer> fontSizeCombo;
    private JLabel statusLabel;

    public MainWindow(NetworkManager networkManager, String userId) {
        super("Çok Kullanıcılı Metin Editörü");
        this.networkManager = networkManager;
        this.userId = userId;
        initialize();
        setupNetworkManager();
    }

    private void initialize() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        // Ana menü
        createMenuBar();

        // Ana panel
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setDividerLocation(250);
        mainSplitPane.setBorder(null);

        // Sol panel
        JPanel leftPanel = createLeftPanel();
        mainSplitPane.setLeftComponent(leftPanel);

        // Sağ panel
        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setDividerLocation(600);
        rightSplitPane.setBorder(null);

        // Editör paneli
        JPanel editorPanel = createEditorPanel();
        rightSplitPane.setTopComponent(editorPanel);

        // Sohbet paneli
        JPanel chatPanel = createChatPanel();
        rightSplitPane.setBottomComponent(chatPanel);

        mainSplitPane.setRightComponent(rightSplitPane);

        // Durum çubuğu
        statusLabel = new JLabel("Hazır");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        // Ana container'a ekleme
        Container contentPane = getContentPane();
        contentPane.add(mainSplitPane, BorderLayout.CENTER);
        contentPane.add(statusLabel, BorderLayout.SOUTH);

        // Doküman listesini güncelle
        requestDocumentList();
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

    private void handleFileListResponse(Message message) {
        SwingUtilities.invokeLater(() -> {
            String filesStr = message.getData("files");
            System.out.println("Gelen dosya listesi: " + filesStr); // Debug için log
            if (filesStr != null && !filesStr.isEmpty()) {
                String[] files = filesStr.split(",");
                listModel.clear();
                for (String file : files) {
                    if (file != null && !file.trim().isEmpty()) {
                        listModel.addElement(file.trim());
                    }
                }
                statusLabel.setText("Doküman listesi güncellendi (" + listModel.size() + " doküman)");
            } else {
                statusLabel.setText("Doküman listesi boş");
            }
        });
    }

    private void handleFileContent(Message message) {
        SwingUtilities.invokeLater(() -> {
            String content = message.getData("content");
            String filename = message.getData("filename");
            System.out.println("Doküman içeriği alındı: " + filename); // Debug için log
            
            if (content != null) {
                editorArea.setText(content);
                editorArea.setCaretPosition(0);
                statusLabel.setText("Doküman açıldı: " + filename);
            } else {
                statusLabel.setText("Doküman içeriği alınamadı: " + filename);
            }
        });
    }

    private void handleFileCreated(Message message) {
        SwingUtilities.invokeLater(() -> {
            String filename = message.getData("filename");
            if (filename != null && !listModel.contains(filename)) {
                listModel.addElement(filename);
                statusLabel.setText("Yeni doküman oluşturuldu: " + filename);
            }
        });
    }

    private void handleFileUpdated(Message message) {
        SwingUtilities.invokeLater(() -> {
            String content = message.getData("content");
            String filename = message.getData("filename");
            if (content != null && filename != null && filename.equals(documentList.getSelectedValue())) {
                if (!content.equals(editorArea.getText())) {
                    editorArea.setText(content);
                }
                statusLabel.setText("Doküman güncellendi: " + filename);
            }
        });
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
        System.out.println("Doküman listesi isteniyor..."); // Debug için log
        networkManager.requestFileList();
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // Dosya menüsü
        JMenu fileMenu = new JMenu("Dosya");
        JMenuItem newItem = new JMenuItem("Yeni");
        JMenuItem openItem = new JMenuItem("Aç");
        JMenuItem saveItem = new JMenuItem("Kaydet");
        JMenuItem exitItem = new JMenuItem("Çıkış");

        newItem.addActionListener(e -> handleNewDocument());
        openItem.addActionListener(e -> handleOpenDocument());
        saveItem.addActionListener(e -> handleSaveDocument());
        exitItem.addActionListener(e -> System.exit(0));
        
        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        
        // Düzen menüsü
        JMenu editMenu = new JMenu("Düzen");
        editMenu.add(new JMenuItem("Kes"));
        editMenu.add(new JMenuItem("Kopyala"));
        editMenu.add(new JMenuItem("Yapıştır"));
        
        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        
        setJMenuBar(menuBar);
    }

    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Doküman listesi başlığı
        JLabel titleLabel = new JLabel("Dokümanlar");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 14));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Doküman listesi
        listModel = new DefaultListModel<>();
        documentList = new JList<>(listModel);
        documentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        documentList.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        documentList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = documentList.getSelectedValue();
                if (selected != null) {
                    networkManager.openDocument(selected);
                }
            }
        });
        
        JScrollPane listScroller = new JScrollPane(documentList);
        listScroller.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        panel.add(listScroller, BorderLayout.CENTER);

        // Buton paneli
        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        JButton newButton = new JButton("Yeni");
        JButton openButton = new JButton("Aç");
        
        newButton.addActionListener(e -> handleNewDocument());
        openButton.addActionListener(e -> handleOpenDocument());
        
        buttonPanel.add(newButton);
        buttonPanel.add(openButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Araç çubuğu
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        // Yazı tipi stili
        String[] styles = {"Normal", "Kalın", "İtalik", "Kalın İtalik"};
        fontStyleCombo = new JComboBox<>(styles);
        
        // Yazı tipi boyutu
        Integer[] sizes = {8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72};
        fontSizeCombo = new JComboBox<>(sizes);
        fontSizeCombo.setSelectedItem(14);
        
        toolBar.add(new JLabel("Stil:"));
        toolBar.add(fontStyleCombo);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(new JLabel("Boyut:"));
        toolBar.add(fontSizeCombo);
        
        panel.add(toolBar, BorderLayout.NORTH);

        // Editör alanı
        editorArea = new JTextArea();
        editorArea.setFont(new Font("Arial", Font.PLAIN, 14));
        editorArea.setLineWrap(true);
        editorArea.setWrapStyleWord(true);
        editorArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { handleTextChange(); }
            public void removeUpdate(DocumentEvent e) { handleTextChange(); }
            public void changedUpdate(DocumentEvent e) { handleTextChange(); }
        });
        
        JScrollPane scrollPane = new JScrollPane(editorArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
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

    private void handleNewDocument() {
        String docName = JOptionPane.showInputDialog(this, "Doküman adını giriniz:");
        if (docName != null && !docName.trim().isEmpty()) {
            docName = docName.trim();
            
            // Dosya adı doğrulama
            if (!isValidFileName(docName)) {
                showError("Geçersiz dosya adı!\nSadece harf, rakam, tire (-) ve alt çizgi (_) kullanabilirsiniz.\nUzunluk 3-50 karakter arası olmalıdır.");
                return;
            }

            System.out.println("Yeni doküman oluşturuluyor: " + docName);
            
            Document doc = new Document();
            doc.setTitle(docName);
            doc.setContent("");
            doc.setOwner(userId);
            
            networkManager.createDocument(docName);
            statusLabel.setText("Yeni doküman oluşturuluyor: " + docName);
        }
    }

    private boolean isValidFileName(String fileName) {
        if (fileName == null) return false;
        
        // Dosya adı uzunluğu kontrolü (3-50 karakter)
        if (fileName.length() < 3 || fileName.length() > 50) return false;
        
        // Sadece harf, rakam, tire ve alt çizgi izin ver
        return fileName.matches("^[a-zA-Z0-9_-]+$");
    }

    private void handleOpenDocument() {
        String selected = documentList.getSelectedValue();
        if (selected != null) {
            System.out.println("Doküman açılıyor: " + selected); // Debug için log
            networkManager.openDocument(selected);
            statusLabel.setText("Doküman açılıyor: " + selected);
        } else {
            showError("Lütfen bir doküman seçin!");
        }
    }

    private void handleSaveDocument() {
        String selected = documentList.getSelectedValue();
        if (selected != null) {
            Document doc = new Document();
            doc.setId(selected);
            doc.setContent(editorArea.getText());
            networkManager.updateDocument(doc, 0, doc.getContent(), true);
        } else {
            showError("Lütfen bir doküman seçin!");
        }
    }

    private void handleTextChange() {
        if (!editorArea.isFocusOwner()) return; // Başka bir işlem tarafından yapılan değişiklikleri yoksay
        
        String selected = documentList.getSelectedValue();
        if (selected != null) {
            String content = editorArea.getText();
            System.out.println("Doküman güncelleniyor: " + selected); // Debug için log
            
            Document doc = new Document();
            doc.setId(selected);
            doc.setTitle(selected);
            doc.setContent(content);
            doc.setOwner(userId);
            
            networkManager.updateDocument(doc, editorArea.getCaretPosition(), content, false);
        }
    }
} 