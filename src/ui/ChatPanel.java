package ui;

import auth.Session;
import files.FileApiService;
import message.MessageApiService.MessageDto;
import model.ChannelInfo;
import signal.SignalingClient;
import signal.WsEnvelope;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ChatPanel extends JPanel {

    private static final Color BG           = new Color(54, 57, 63);
    private static final Color MSG_BG       = new Color(54, 57, 63);
    private static final Color MSG_HOVER_BG = new Color(60, 63, 70);
    private static final Color INPUT_BG     = new Color(64, 68, 75);
    private static final Color AUTHOR_COLOR = new Color(255, 255, 255);
    private static final Color TIME_COLOR   = new Color(116, 127, 141);
    private static final Color TEXT_COLOR   = new Color(220, 221, 222);
    private static final Color TYPING_COLOR = new Color(185, 187, 190);
    private static final Color EDITED_COLOR = new Color(116, 127, 141);
    private static final Color ATT_BG       = new Color(47, 49, 54);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private ChannelInfo currentChannel;
    private final SignalingClient signalingClient;
    private final FileApiService fileApiService;
    private Consumer<String> onLoadMore;
    private String oldestTimestamp = null;
    private boolean loadingMore = false;

    // pending staged attachments (uploaded but not yet sent)
    private final List<String> pendingAttachmentIds = new ArrayList<>();
    private final List<String> pendingFilenames      = new ArrayList<>();

    // Message rows
    private final JPanel msgList;
    private final JScrollPane msgScroll;
    private final Map<String, MessageRow> rowById = new LinkedHashMap<>();

    // Input area
    private final JTextArea inputField;
    private final JButton sendBtn;
    private final JButton attachBtn;
    private final JLabel typingLabel;
    private final Timer typingClearTimer;
    private final Map<String, String> typingUsers = new ConcurrentHashMap<>(); // userId → username

    // Pending attachments bar (shown above input when files are staged)
    private final JPanel pendingBar;

    // "Load older" button
    private final JButton loadOlderBtn;

    private final SignalingClient.MessageListener wsListener;

    public ChatPanel(SignalingClient signalingClient, FileApiService fileApiService) {
        this.signalingClient = signalingClient;
        this.fileApiService  = fileApiService;
        setLayout(new BorderLayout());
        setBackground(BG);

        // Message list
        msgList = new JPanel();
        msgList.setLayout(new BoxLayout(msgList, BoxLayout.Y_AXIS));
        msgList.setBackground(BG);

        loadOlderBtn = new JButton("↑ Načíst starší zprávy");
        loadOlderBtn.setAlignmentX(CENTER_ALIGNMENT);
        loadOlderBtn.setVisible(false);
        loadOlderBtn.addActionListener(e -> loadMore());
        msgList.add(loadOlderBtn);

        msgScroll = new JScrollPane(msgList,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        msgScroll.setBorder(null);
        msgScroll.setBackground(BG);
        msgScroll.getViewport().setBackground(BG);
        msgScroll.getVerticalScrollBar().setUnitIncrement(20);

        // Typing indicator
        typingLabel = new JLabel(" ");
        typingLabel.setForeground(TYPING_COLOR);
        typingLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        typingLabel.setBorder(new EmptyBorder(0, 16, 0, 0));

        // Pending attachments bar
        pendingBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        pendingBar.setBackground(ATT_BG);
        pendingBar.setVisible(false);

        // Input area
        inputField = new JTextArea(3, 1);
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        inputField.setBackground(INPUT_BG);
        inputField.setForeground(TEXT_COLOR);
        inputField.setCaretColor(Color.WHITE);
        inputField.setFont(new Font("Arial", Font.PLAIN, 14));
        inputField.setBorder(new EmptyBorder(8, 12, 8, 12));
        inputField.setEnabled(false);

        sendBtn = new JButton("Odeslat");
        sendBtn.setEnabled(false);
        sendBtn.addActionListener(e -> doSend());

        attachBtn = new JButton("📎");
        attachBtn.setToolTipText("Přiložit soubor");
        attachBtn.setEnabled(false);
        attachBtn.addActionListener(e -> pickFile());

        inputField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    doSend();
                } else {
                    scheduleTyping();
                }
            }
        });

        JScrollPane inputScroll = new JScrollPane(inputField,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setBorder(BorderFactory.createLineBorder(new Color(40, 43, 48), 1));
        inputScroll.setBackground(INPUT_BG);

        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(attachBtn);
        btnPanel.add(sendBtn);

        JPanel inputBar = new JPanel(new BorderLayout(8, 0));
        inputBar.setBackground(BG);
        inputBar.setBorder(new EmptyBorder(8, 12, 12, 12));
        inputBar.add(inputScroll, BorderLayout.CENTER);
        inputBar.add(btnPanel, BorderLayout.EAST);

        JPanel bottomArea = new JPanel(new BorderLayout());
        bottomArea.setBackground(BG);
        bottomArea.add(typingLabel, BorderLayout.NORTH);
        bottomArea.add(pendingBar, BorderLayout.CENTER);
        bottomArea.add(inputBar, BorderLayout.SOUTH);

        add(msgScroll, BorderLayout.CENTER);
        add(bottomArea, BorderLayout.SOUTH);

        typingClearTimer = new Timer(3000, e -> {
            long now = System.currentTimeMillis();
            typingUsers.entrySet().removeIf(entry -> {
                // value holds "username|timestamp"
                String[] parts = entry.getValue().split("\\|", 2);
                try { return now - Long.parseLong(parts[1]) > 3000; } catch (Exception ex) { return true; }
            });
            updateTypingLabel();
        });
        typingClearTimer.setRepeats(true);
        typingClearTimer.start();

        wsListener = new SignalingClient.MessageListener() {
            @Override public void onChatMessage(WsEnvelope.MessagePayload p) {
                if (currentChannel != null && currentChannel.id().toString().equals(p.channelId)) {
                    SwingUtilities.invokeLater(() -> appendMessage(p));
                }
            }
            @Override public void onChatEdit(String channelId, String messageId, String content, String editedAt) {
                if (currentChannel != null && currentChannel.id().toString().equals(channelId)) {
                    SwingUtilities.invokeLater(() -> {
                        MessageRow row = rowById.get(messageId);
                        if (row != null) row.updateContent(content, editedAt);
                    });
                }
            }
            @Override public void onChatDelete(String channelId, String messageId) {
                if (currentChannel != null && currentChannel.id().toString().equals(channelId)) {
                    SwingUtilities.invokeLater(() -> {
                        MessageRow row = rowById.get(messageId);
                        if (row != null) row.markDeleted();
                    });
                }
            }
            @Override public void onTyping(String channelId, String userId, String username) {
                if (currentChannel != null && currentChannel.id().toString().equals(channelId)) {
                    if (!userId.equals(Session.get().getUserId().toString())) {
                        SwingUtilities.invokeLater(() -> showTyping(username, userId));
                    }
                }
            }
            @Override public void onError(int code, String reason) {}
            @Override public void onConnected() {
                if (currentChannel != null)
                    signalingClient.subscribe(currentChannel.id().toString());
            }
            @Override public void onDisconnected() {}
        };
        signalingClient.addListener(wsListener);
    }

    // ---- Public API ----

    public void loadChannel(ChannelInfo channel, List<MessageDto> history) {
        if (currentChannel != null)
            signalingClient.unsubscribe(currentChannel.id().toString());

        currentChannel = channel;
        rowById.clear();
        msgList.removeAll();
        msgList.add(loadOlderBtn);
        clearPending();

        oldestTimestamp = history.isEmpty() ? null : history.get(0).createdAt;
        loadOlderBtn.setVisible(!history.isEmpty());

        for (MessageDto m : history) addHistoryRow(m);

        inputField.setEnabled(true);
        sendBtn.setEnabled(true);
        attachBtn.setEnabled(true);
        inputField.requestFocus();

        if (signalingClient.isConnected())
            signalingClient.subscribe(channel.id().toString());

        scrollToBottom();
    }

    public void clear() {
        if (currentChannel != null) {
            signalingClient.unsubscribe(currentChannel.id().toString());
            currentChannel = null;
        }
        rowById.clear();
        msgList.removeAll();
        msgList.add(loadOlderBtn);
        loadOlderBtn.setVisible(false);
        clearPending();
        inputField.setEnabled(false);
        sendBtn.setEnabled(false);
        attachBtn.setEnabled(false);
        msgList.revalidate();
        msgList.repaint();
    }

    public void prependHistory(List<MessageDto> older) {
        loadingMore = false;
        loadOlderBtn.setEnabled(true);
        if (older.isEmpty()) {
            loadOlderBtn.setVisible(false);
            return;
        }
        oldestTimestamp = older.get(0).createdAt;

        JScrollBar vBar = msgScroll.getVerticalScrollBar();
        int oldMax = vBar.getMaximum();
        int oldVal = vBar.getValue();

        for (int i = older.size() - 1; i >= 0; i--) {
            MessageRow row = buildRow(older.get(i));
            msgList.add(row, 1);
            rowById.put(older.get(i).id, row);
        }
        msgList.revalidate();
        msgList.repaint();

        SwingUtilities.invokeLater(() -> {
            int newMax = vBar.getMaximum();
            vBar.setValue(oldVal + (newMax - oldMax));
        });
    }

    public void setOnLoadMore(Consumer<String> callback) {
        this.onLoadMore = callback;
    }

    // ---- File attachment ----

    private void pickFile() {
        if (currentChannel == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Vybrat soubor");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = fc.getSelectedFile();
        if (chosen == null) return;

        UUID serverId = currentChannel.serverId();
        if (serverId == null) return;

        JProgressBar progress = new JProgressBar(0, (int) chosen.length());
        progress.setStringPainted(true);
        progress.setString("0 %");

        JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Nahrávání…", false);
        dlg.setLayout(new BorderLayout(8, 8));
        dlg.add(new JLabel("  Nahrávám: " + chosen.getName()), BorderLayout.NORTH);
        dlg.add(progress, BorderLayout.CENTER);
        dlg.setSize(350, 90);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);

        new SwingWorker<FileApiService.AttachmentDto, Long>() {
            @Override protected FileApiService.AttachmentDto doInBackground() throws Exception {
                return fileApiService.upload(serverId, chosen, sent -> publish(sent));
            }
            @Override protected void process(List<Long> chunks) {
                long sent = chunks.get(chunks.size() - 1);
                progress.setValue((int) Math.min(sent, chosen.length()));
                int pct = chosen.length() > 0 ? (int) (sent * 100 / chosen.length()) : 100;
                progress.setString(pct + " %");
            }
            @Override protected void done() {
                dlg.dispose();
                try {
                    FileApiService.AttachmentDto att = get();
                    pendingAttachmentIds.add(att.id());
                    pendingFilenames.add(att.filename());
                    refreshPendingBar();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(ChatPanel.this,
                            "Nahrávání selhalo: " + ex.getMessage(),
                            "Chyba", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void refreshPendingBar() {
        pendingBar.removeAll();
        for (int i = 0; i < pendingFilenames.size(); i++) {
            final int idx = i;
            JLabel fileLabel = new JLabel("📄 " + pendingFilenames.get(i));
            fileLabel.setForeground(TEXT_COLOR);
            fileLabel.setFont(new Font("Arial", Font.PLAIN, 12));

            JButton remove = new JButton("×");
            remove.setMargin(new Insets(0, 4, 0, 4));
            remove.setFont(new Font("Arial", Font.BOLD, 12));
            remove.addActionListener(e -> {
                pendingAttachmentIds.remove(idx);
                pendingFilenames.remove(idx);
                refreshPendingBar();
            });

            JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            chip.setBackground(new Color(67, 70, 78));
            chip.setBorder(BorderFactory.createLineBorder(new Color(80, 85, 95), 1));
            chip.add(fileLabel);
            chip.add(remove);
            pendingBar.add(chip);
        }
        pendingBar.setVisible(!pendingFilenames.isEmpty());
        pendingBar.revalidate();
        pendingBar.repaint();
    }

    private void clearPending() {
        pendingAttachmentIds.clear();
        pendingFilenames.clear();
        pendingBar.removeAll();
        pendingBar.setVisible(false);
    }

    // ---- Message rendering ----

    private void addHistoryRow(MessageDto m) {
        MessageRow row = buildRow(m);
        msgList.add(row);
        rowById.put(m.id, row);
    }

    private void appendMessage(WsEnvelope.MessagePayload p) {
        MessageDto dto = new MessageDto();
        dto.id                = p.id;
        dto.channelId         = p.channelId;
        dto.authorId          = p.authorId;
        dto.authorUsername    = p.authorUsername;
        dto.authorDisplayName = p.authorDisplayName;
        dto.content           = p.content;
        dto.replyToId         = p.replyToId;
        dto.createdAt         = p.createdAt;
        dto.editedAt          = p.editedAt;
        dto.attachments       = p.attachments;

        MessageRow row = buildRow(dto);
        msgList.add(row);
        rowById.put(dto.id, row);
        msgList.revalidate();
        msgList.repaint();

        JScrollBar vBar = msgScroll.getVerticalScrollBar();
        boolean nearBottom = vBar.getValue() >= vBar.getMaximum() - vBar.getVisibleAmount() - 80;
        if (nearBottom) scrollToBottom();
    }

    private MessageRow buildRow(MessageDto m) {
        return new MessageRow(m, Session.get().getUserId().toString());
    }

    // ---- Input ----

    private void doSend() {
        String text = inputField.getText().strip();
        boolean hasAttachments = !pendingAttachmentIds.isEmpty();
        if ((text.isEmpty() && !hasAttachments) || currentChannel == null) return;
        if (text.isEmpty()) text = "​"; // zero-width space placeholder when only files
        inputField.setText("");
        List<String> attIds = new ArrayList<>(pendingAttachmentIds);
        clearPending();
        signalingClient.sendChatMessage(currentChannel.id().toString(), text, null, attIds);
    }

    private void scheduleTyping() {
        if (currentChannel != null) signalingClient.sendTyping(currentChannel.id().toString());
    }

    // ---- Typing indicator ----

    private void showTyping(String username, String userId) {
        typingUsers.put(userId, username + "|" + System.currentTimeMillis());
        updateTypingLabel();
    }

    private void updateTypingLabel() {
        if (typingUsers.isEmpty()) {
            typingLabel.setText(" ");
        } else {
            long now = System.currentTimeMillis();
            List<String> names = typingUsers.values().stream()
                    .map(v -> v.split("\\|", 2)[0])
                    .filter(n -> !n.isBlank())
                    .toList();
            if (names.isEmpty()) { typingLabel.setText(" "); return; }
            typingLabel.setText(names.size() == 1
                    ? names.get(0) + " píše…"
                    : names.size() + " lidé píší…");
        }
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = msgScroll.getVerticalScrollBar();
            vBar.setValue(vBar.getMaximum());
        });
    }

    private void loadMore() {
        if (loadingMore || oldestTimestamp == null || onLoadMore == null) return;
        loadingMore = true;
        loadOlderBtn.setEnabled(false);
        onLoadMore.accept(oldestTimestamp);
    }

    // ---- MessageRow ----

    private class MessageRow extends JPanel {
        private final JLabel contentLabel;
        private final JLabel editedLabel;
        private boolean deleted = false;

        MessageRow(MessageDto m, String currentUserId) {
            setLayout(new BorderLayout(0, 0));
            setBackground(MSG_BG);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            setBorder(new EmptyBorder(4, 16, 4, 16));

            boolean isOwn = m.authorId != null && m.authorId.equals(currentUserId);

            // Header: name + timestamp
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            header.setOpaque(false);

            JLabel nameLabel = new JLabel(m.displayName());
            nameLabel.setForeground(isOwn ? new Color(114, 137, 218) : AUTHOR_COLOR);
            nameLabel.setFont(new Font("Arial", Font.BOLD, 13));

            String time = m.createdAt != null ? TIME_FMT.format(Instant.parse(m.createdAt)) : "";
            JLabel timeLabel = new JLabel(time);
            timeLabel.setForeground(TIME_COLOR);
            timeLabel.setFont(new Font("Arial", Font.PLAIN, 11));

            editedLabel = new JLabel(m.editedAt != null ? " (upraveno)" : "");
            editedLabel.setForeground(EDITED_COLOR);
            editedLabel.setFont(new Font("Arial", Font.ITALIC, 11));

            header.add(nameLabel);
            header.add(timeLabel);
            header.add(editedLabel);

            // Content
            String displayContent = m.content != null && m.content.equals("​") ? "" : m.content;
            contentLabel = new JLabel("<html><body style='width:600px'>"
                    + escapeHtml(displayContent) + "</body></html>");
            contentLabel.setForeground(TEXT_COLOR);
            contentLabel.setFont(new Font("Arial", Font.PLAIN, 14));
            contentLabel.setBorder(new EmptyBorder(1, 0, 1, 0));

            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            body.add(header);
            if (displayContent != null && !displayContent.isEmpty()) body.add(contentLabel);

            // Attachments
            if (m.attachments != null && !m.attachments.isEmpty()) {
                for (WsEnvelope.AttachmentInfo att : m.attachments) {
                    body.add(buildAttachmentWidget(att));
                }
            }

            add(body, BorderLayout.CENTER);

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (!deleted) setBackground(MSG_HOVER_BG);
                }
                @Override public void mouseExited(MouseEvent e) { setBackground(MSG_BG); }
            });
        }

        private JPanel buildAttachmentWidget(WsEnvelope.AttachmentInfo att) {
            JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            chip.setBackground(ATT_BG);
            chip.setBorder(BorderFactory.createLineBorder(new Color(67, 70, 78), 1));
            chip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

            boolean isImage = att.mimeType != null && att.mimeType.startsWith("image/");

            JLabel icon = new JLabel(isImage ? "🖼" : "📄");
            icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));

            String sizeStr = formatSize(att.sizeBytes);
            JLabel info = new JLabel(att.filename + "  (" + sizeStr + ")");
            info.setForeground(TEXT_COLOR);
            info.setFont(new Font("Arial", Font.PLAIN, 12));

            JButton downloadBtn = new JButton("⬇");
            downloadBtn.setToolTipText("Stáhnout");
            downloadBtn.setMargin(new Insets(1, 4, 1, 4));
            downloadBtn.addActionListener(e -> startDownload(att));

            chip.add(icon);
            chip.add(info);
            chip.add(downloadBtn);

            return chip;
        }

        private void startDownload(WsEnvelope.AttachmentInfo att) {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(att.filename));
            fc.setDialogTitle("Uložit jako");
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File dest = fc.getSelectedFile();

            JProgressBar progress = new JProgressBar(0, (int) att.sizeBytes);
            progress.setStringPainted(true);

            JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Stahování…", false);
            dlg.setLayout(new BorderLayout(8, 8));
            dlg.add(new JLabel("  " + att.filename), BorderLayout.NORTH);
            dlg.add(progress, BorderLayout.CENTER);
            dlg.setSize(350, 90);
            dlg.setLocationRelativeTo(ChatPanel.this);
            dlg.setVisible(true);

            new SwingWorker<Void, Long>() {
                @Override protected Void doInBackground() throws Exception {
                    Path tmp = fileApiService.download(att.id, att.filename, received -> publish(received));
                    java.nio.file.Files.copy(tmp, dest.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return null;
                }
                @Override protected void process(List<Long> chunks) {
                    long recv = chunks.get(chunks.size() - 1);
                    progress.setValue((int) Math.min(recv, att.sizeBytes));
                    int pct = att.sizeBytes > 0 ? (int) (recv * 100 / att.sizeBytes) : 100;
                    progress.setString(pct + " %");
                }
                @Override protected void done() {
                    dlg.dispose();
                    try {
                        get();
                        JOptionPane.showMessageDialog(ChatPanel.this,
                                "Soubor uložen: " + dest.getAbsolutePath(),
                                "Staženo", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(ChatPanel.this,
                                "Stahování selhalo: " + ex.getMessage(),
                                "Chyba", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }

        void updateContent(String content, String editedAt) {
            contentLabel.setText("<html><body style='width:600px'>"
                    + escapeHtml(content) + "</body></html>");
            editedLabel.setText(" (upraveno)");
            revalidate(); repaint();
        }

        void markDeleted() {
            deleted = true;
            contentLabel.setText("<html><i style='color:gray'>Zpráva byla smazána</i></html>");
            editedLabel.setText("");
            setBackground(MSG_BG);
            revalidate(); repaint();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }
}
