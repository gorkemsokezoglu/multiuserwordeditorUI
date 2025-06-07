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
    private JList<String> documentList;
    private DefaultListModel<String> listModel;
    private JComboBox<String> fontFamilyCombo;
    private JComboBox<Integer> fontSizeCombo;
    private JToggleButton boldButton;
    private JToggleButton italicButton;
    private JToggleButton underlineButton;
    private JLabel statusLabel;
    private JTextField searchField;
    private Color currentTextColor;
    private String currentTheme = "light";

    private static final int MAX_FILENAME_LENGTH = 100;
    private static final String INVALID_FILENAME_CHARS = "<>:\"|?*/\\\\";

    public MainWindow(NetworkManager networkManager, String userId) {
        super("Çok Kullanıcılı Metin Editörü");
        this.networkManager = networkManager;
        this.userId = userId;
        ExceptionHandler.setMainFrame(this);
        initialize();
        setupNetworkManager();
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
                editorPane.setText(content);
                editorPane.setCaretPosition(0);
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
                if (!content.equals(editorPane.getText())) {
                    editorPane.setText(content);
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

        // Ctrl+U: Altı çizili
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK), "underline");
        actionMap.put("underline", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                underlineButton.setSelected(!underlineButton.isSelected());
                updateFontStyle();
            }
        });

        editorPane.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                handleTextChange();
            }

            public void removeUpdate(DocumentEvent e) {
                handleTextChange();
            }

            public void changedUpdate(DocumentEvent e) {
                handleTextChange();
            }
        });

        JScrollPane scrollPane = new JScrollPane(editorPane);
        panel.add(toolBar, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

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
        String docName = JOptionPane.showInputDialog(this, "Doküman adını giriniz:");
        if (docName != null && !docName.trim().isEmpty()) {
            docName = docName.trim();

            // Dosya adı doğrulama
            if (!isValidFileName(docName)) {
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

        // Başında ve sonunda boşluk kontrolü
        if (!trimmedName.equals(fileName)) {
            showError("Dosya adı başında ve sonunda boşluk olamaz!");
            return false;
        }

        return true;
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
            doc.setContent(editorPane.getText());
            networkManager.updateDocument(doc, 0, doc.getContent(), true);
        } else {
            showError("Lütfen bir doküman seçin!");
        }
    }

    private void handleTextChange() {
        if (!editorPane.isFocusOwner())
            return; // Başka bir işlem tarafından yapılan değişiklikleri yoksay

        String selected = documentList.getSelectedValue();
        if (selected != null) {
            try {
                String content = editorPane.getText();
                System.out.println("Doküman güncelleniyor: " + selected); // Debug için log

                Document doc = new Document();
                doc.setId(selected);
                doc.setTitle(selected);
                doc.setContent(content);
                doc.setOwner(userId);

                // Sadece değişen kısmı gönder
                int caretPos = editorPane.getCaretPosition();
                networkManager.updateDocument(doc, caretPos, content, false);

                LOGGER.fine("Doküman güncellendi: " + selected + ", pozisyon: " + caretPos);
            } catch (Exception e) {
                ExceptionHandler.handle(e, "Doküman güncellenirken hata oluştu");
            }
        }
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