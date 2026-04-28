import auth.AuthApiService;
import auth.Session;
import files.FileApiService;
import message.MessageApiService;
import model.ChannelInfo;
import model.ServerInfo;
import net.ApiClient;
import server.ServerApiService;
import signal.SignalingClient;
import ui.ChannelListPanel;
import ui.ChatPanel;
import ui.ServerListPanel;
import ui.VideoGridPanel;
import ui.VoicePanel;
import ui.dialogs.CreateChannelDialog;
import ui.dialogs.CreateServerDialog;
import ui.dialogs.JoinServerDialog;
import voice.LocalAudioMonitor;
import voice.WebRtcManager;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class TrashTalkMainPanel extends JFrame {

    private final ApiClient apiClient;
    private final ServerApiService serverApi;
    private final MessageApiService messageApi;
    private final SignalingClient signalingClient;
    private final FileApiService fileApi;

    private final ServerListPanel  serverListPanel;
    private final ChannelListPanel channelListPanel;
    private final JPanel           mainArea;
    private final ChatPanel        chatPanel;
    private final JLabel           mainPlaceholder;

    private ServerInfo  activeServer;
    private ChannelInfo activeChannel;

    // voice + video state
    private volatile WebRtcManager  webRtcManager;
    private LocalAudioMonitor        audioMonitor;
    private VoicePanel               voicePanel;
    private VideoGridPanel           videoGridPanel;
    private JSplitPane               voiceSplit;
    private ChannelInfo              activeVoiceChannel;
    private boolean                  localCameraOn = false;
    // signals that arrive before webRtcManager is ready are queued here
    private final java.util.Queue<Runnable> pendingSignals =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public TrashTalkMainPanel(ApiClient apiClient) {
        this.apiClient       = apiClient;
        this.serverApi       = new ServerApiService(apiClient);
        this.messageApi      = new MessageApiService(apiClient);
        this.signalingClient = new SignalingClient(apiClient.getBaseUrl(), apiClient.isTrustAllCerts());
        this.fileApi         = new FileApiService(apiClient);

        serverListPanel  = new ServerListPanel();
        channelListPanel = new ChannelListPanel();
        mainArea         = new JPanel(new BorderLayout());
        chatPanel        = new ChatPanel(signalingClient, fileApi);
        mainPlaceholder  = makePlaceholder();

        buildUi();
        wireEvents();
        signalingClient.addListener(voiceSignalingListener());
        signalingClient.connect();
        loadServers();
    }

    // ---- UI construction ----

    private void buildUi() {
        setTitle("TrashTalk — " + Session.get().getDisplayName());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 720);
        setMinimumSize(new Dimension(850, 500));
        setLocationRelativeTo(null);

        mainArea.setBackground(new Color(54, 57, 63));
        showPlaceholder();

        JPanel center = new JPanel(new BorderLayout());
        center.add(channelListPanel, BorderLayout.WEST);
        center.add(mainArea, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(serverListPanel, BorderLayout.WEST);
        root.add(center, BorderLayout.CENTER);

        setJMenuBar(buildMenuBar());
        setContentPane(root);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                signalingClient.disconnect();
            }
        });
    }

    private void wireEvents() {
        serverListPanel.setOnSelect(this::onServerSelected);
        serverListPanel.setOnCreateServer(this::doCreateServer);
        serverListPanel.setOnJoinServer(this::doJoinServer);

        channelListPanel.setOnSelectChannel(this::onChannelSelected);
        channelListPanel.setOnOpenServerSettings(this::openServerSettings);
        channelListPanel.setOnCreateChannel(this::doCreateChannel);

        chatPanel.setOnLoadMore(cursor ->
            new SwingWorker<List<MessageApiService.MessageDto>, Void>() {
                @Override protected List<MessageApiService.MessageDto> doInBackground() throws Exception {
                    return messageApi.loadHistory(activeChannel.id(), cursor);
                }
                @Override protected void done() {
                    try { chatPanel.prependHistory(get()); }
                    catch (Exception ex) { showError("Chyba při načítání zpráv."); }
                }
            }.execute()
        );
    }

    // ---- Data loading ----

    private void loadServers() {
        new SwingWorker<List<ServerInfo>, Void>() {
            @Override protected List<ServerInfo> doInBackground() throws Exception {
                return serverApi.listMyServers();
            }
            @Override protected void done() {
                try { serverListPanel.setServers(get()); }
                catch (ExecutionException ex) { showError("Nepodařilo se načíst servery: " + ex.getCause().getMessage()); }
                catch (Exception ignored) {}
            }
        }.execute();
    }

    private void onServerSelected(ServerInfo server) {
        this.activeServer  = server;
        this.activeChannel = null;
        channelListPanel.clear();
        chatPanel.clear();
        showPlaceholder();

        new SwingWorker<List<ChannelInfo>, Void>() {
            @Override protected List<ChannelInfo> doInBackground() throws Exception {
                return serverApi.listChannels(server.id());
            }
            @Override protected void done() {
                try { channelListPanel.loadServer(server, get()); }
                catch (ExecutionException ex) { showError("Nepodařilo se načíst kanály: " + ex.getCause().getMessage()); }
                catch (Exception ignored) {}
            }
        }.execute();
    }

    private void onChannelSelected(ChannelInfo channel) {
        if (!channel.isText()) {
            doVoiceJoin(channel);
            return;
        }
        this.activeChannel = channel;

        new SwingWorker<List<MessageApiService.MessageDto>, Void>() {
            @Override protected List<MessageApiService.MessageDto> doInBackground() throws Exception {
                return messageApi.loadHistory(channel.id(), null);
            }
            @Override protected void done() {
                try {
                    chatPanel.loadChannel(channel, get());
                    showChatPanel();
                } catch (ExecutionException ex) {
                    showError("Nepodařilo se načíst zprávy: " + ex.getCause().getMessage());
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    // ---- Panel switching ----

    private void showPlaceholder() {
        mainArea.removeAll();
        mainArea.add(mainPlaceholder, BorderLayout.CENTER);
        mainArea.revalidate();
        mainArea.repaint();
    }

    private void showChatPanel() {
        mainArea.removeAll();
        mainArea.add(chatPanel, BorderLayout.CENTER);
        mainArea.revalidate();
        mainArea.repaint();
    }

    private void doVoiceJoin(ChannelInfo channel) {
        doVoiceLeave();

        activeVoiceChannel = channel;
        voicePanel = new VoicePanel(channel.name());
        voicePanel.setListener(new VoicePanel.VoicePanelListener() {
            @Override public void onMuteToggle(boolean muted) {
                // TODO: webrtcManager.setMuted(muted) if implemented
                signalingClient.sendVoiceMute(channel.id().toString(), muted);
            }
            @Override public void onCameraToggle(boolean cameraOn) {
                doToggleCamera(cameraOn);
            }
            @Override public void onScreenShareToggle(boolean streamOn) {
                if (streamOn && webRtcManager != null) {
                    webRtcManager.startScreenShare();
                } else if (!streamOn && webRtcManager != null) {
                    webRtcManager.stopScreenShare();
                    if (!localCameraOn && videoGridPanel != null) {
                        String selfId = Session.get().getUserId().toString();
                        videoGridPanel.removeTile(selfId);
                        if (videoGridPanel.isEmpty()) collapseVideoArea();
                    }
                }
            }
            @Override public void onLeave() { doVoiceLeave(); showPlaceholder(); }
        });

        showVoicePanel();
        signalingClient.subscribe(channel.id().toString());
        signalingClient.sendVoiceJoin(channel.id().toString());
    }

    private void doVoiceLeave() {
        if (activeVoiceChannel != null) {
            signalingClient.sendVoiceLeave(activeVoiceChannel.id().toString());
            signalingClient.unsubscribe(activeVoiceChannel.id().toString());
            activeVoiceChannel = null;
        }
        pendingSignals.clear();
        if (audioMonitor  != null) { audioMonitor.stop(); audioMonitor = null; }
        if (webRtcManager != null) { webRtcManager.dispose(); webRtcManager = null; }
        localCameraOn  = false;
        videoGridPanel = null;
        voiceSplit     = null;
        voicePanel     = null;
    }

    private void showVoicePanel() {
        videoGridPanel = new VideoGridPanel();
        voiceSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, videoGridPanel, voicePanel);
        voiceSplit.setResizeWeight(0.65);
        voiceSplit.setDividerSize(4);

        mainArea.removeAll();
        mainArea.add(voiceSplit, BorderLayout.CENTER);
        mainArea.revalidate();
        mainArea.repaint();

        // Collapse video area until camera or remote video arrives
        SwingUtilities.invokeLater(() -> voiceSplit.setDividerLocation(0));
    }

    private void doToggleCamera(boolean cameraOn) {
        this.localCameraOn = cameraOn;
        if (webRtcManager == null) return;
        if (cameraOn) {
            webRtcManager.startCamera();
            String selfId   = Session.get().getUserId().toString();
            String selfName = Session.get().getDisplayName() + " (Já)";
            if (videoGridPanel != null && !videoGridPanel.hasTile(selfId)) {
                videoGridPanel.addTile(selfId, selfName);
            }
            expandVideoArea();
        } else {
            String selfId = Session.get().getUserId().toString();
            if (videoGridPanel != null) {
                videoGridPanel.removeTile(selfId);
                if (videoGridPanel.isEmpty()) collapseVideoArea();
            }
        }
    }

    private void expandVideoArea() {
        SwingUtilities.invokeLater(() -> {
            if (voiceSplit != null) voiceSplit.setDividerLocation(0.65);
        });
    }

    private void collapseVideoArea() {
        SwingUtilities.invokeLater(() -> {
            if (voiceSplit != null) voiceSplit.setDividerLocation(0);
        });
    }

    // ---- Server / channel actions ----

    private void doCreateServer() {
        CreateServerDialog dlg = new CreateServerDialog(this);
        dlg.setVisible(true);
        String name = dlg.getResultName();
        if (name == null) return;

        new SwingWorker<ServerInfo, Void>() {
            @Override protected ServerInfo doInBackground() throws Exception {
                return serverApi.createServer(name);
            }
            @Override protected void done() {
                try { ServerInfo s = get(); serverListPanel.addServer(s); onServerSelected(s); }
                catch (ExecutionException ex) { showError("Nepodařilo se vytvořit server: " + ex.getCause().getMessage()); }
                catch (Exception ignored) {}
            }
        }.execute();
    }

    private void doJoinServer() {
        JoinServerDialog dlg = new JoinServerDialog(this);
        dlg.setVisible(true);
        String code = dlg.getResultCode();
        if (code == null) return;

        new SwingWorker<ServerInfo, Void>() {
            @Override protected ServerInfo doInBackground() throws Exception {
                return serverApi.joinByInvite(code);
            }
            @Override protected void done() {
                try { ServerInfo s = get(); serverListPanel.addServer(s); onServerSelected(s); }
                catch (ExecutionException ex) { showError("Nepodařilo se přidat server: " + ex.getCause().getMessage()); }
                catch (Exception ignored) {}
            }
        }.execute();
    }

    private void doCreateChannel() {
        if (activeServer == null) return;
        CreateChannelDialog dlg = new CreateChannelDialog(this);
        dlg.setVisible(true);
        if (dlg.getResultName() == null) return;

        new SwingWorker<ChannelInfo, Void>() {
            @Override protected ChannelInfo doInBackground() throws Exception {
                return serverApi.createChannel(
                        activeServer.id(), dlg.getResultName(), dlg.getResultType(), null, null);
            }
            @Override protected void done() {
                try { channelListPanel.addChannel(get()); }
                catch (ExecutionException ex) { showError("Nepodařilo se vytvořit kanál: " + ex.getCause().getMessage()); }
                catch (Exception ignored) {}
            }
        }.execute();
    }

    private void openServerSettings() {
        if (activeServer == null) return;
        JOptionPane.showMessageDialog(this,
                "Server settings dialog bude hotový v Fázi 8.",
                "Nastavení serveru — " + activeServer.name(), JOptionPane.INFORMATION_MESSAGE);
    }

    // ---- Menu ----

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu accountMenu = new JMenu(Session.get().getUsername());
        
        JMenuItem settingsItem = new JMenuItem("Nastavení (Settings)");
        settingsItem.addActionListener(e -> {
            ui.dialogs.SettingsDialog dialog = new ui.dialogs.SettingsDialog(this);
            dialog.setVisible(true);
            if (dialog.isSaved()) {
                // Future Backend Sync
                // Here we would apply audio and video selection to WebRTC variables
            }
        });
        accountMenu.add(settingsItem);
        accountMenu.addSeparator();

        JMenuItem logoutItem = new JMenuItem("Odhlásit se");
        logoutItem.addActionListener(e -> logout());
        accountMenu.add(logoutItem);

        JMenu serverMenu = new JMenu("Server");
        JMenuItem inviteItem = new JMenuItem("Vytvořit pozvánku…");
        inviteItem.addActionListener(e -> doCreateInvite());
        serverMenu.add(inviteItem);

        JMenu helpMenu = new JMenu("Nápověda");
        JMenuItem aboutItem = new JMenuItem("O aplikaci");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "TrashTalk v0.1-alpha\nSelf-hosted Discord clone\nFáze 5/10", "O aplikaci",
                JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(aboutItem);

        bar.add(accountMenu);
        bar.add(serverMenu);
        bar.add(helpMenu);
        return bar;
    }

    private void doCreateInvite() {
        if (activeServer == null) { showError("Nejdřív vyber server."); return; }
        new SwingWorker<ServerApiService.InviteResult, Void>() {
            @Override protected ServerApiService.InviteResult doInBackground() throws Exception {
                return serverApi.createInvite(activeServer.id(), null, 24);
            }
            @Override protected void done() {
                try {
                    String code = get().code();
                    JTextField field = new JTextField(code);
                    field.setEditable(false);
                    JOptionPane.showMessageDialog(TrashTalkMainPanel.this,
                            new Object[]{"Kód pozvánky (platí 24 h):", field},
                            "Pozvánka vytvořena", JOptionPane.INFORMATION_MESSAGE);
                } catch (ExecutionException ex) {
                    showError("Nepodařilo se vytvořit pozvánku: " + ex.getCause().getMessage());
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void logout() {
        signalingClient.disconnect();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try { new AuthApiService(apiClient).logout(); } catch (Exception ignored) {}
                return null;
            }
            @Override protected void done() {
                Session.get().clear(apiClient);
                dispose();
                SwingUtilities.invokeLater(() -> new AuthPanel(apiClient).setVisible(true));
            }
        }.execute();
    }

    // ---- Voice + video signaling listener ----

    private SignalingClient.MessageListener voiceSignalingListener() {
        return new SignalingClient.MessageListener() {
            @Override public void onChatMessage(signal.WsEnvelope.MessagePayload m) {}
            @Override public void onChatEdit(String c, String m, String ct, String ea) {}
            @Override public void onChatDelete(String c, String m) {}
            @Override public void onTyping(String c, String u, String un) {}
            @Override public void onError(int code, String reason) {}
            @Override public void onConnected() {}
            @Override public void onDisconnected() {}

            @Override
            public void onSdpOffer(String channelId, String senderUserId, String sdpOffer) {
                if (!channelId.equals(activeVoiceChannel != null ? activeVoiceChannel.id().toString() : "")) return;
                if (webRtcManager != null) {
                    webRtcManager.handleOffer(senderUserId, sdpOffer);
                } else {
                    pendingSignals.add(() -> webRtcManager.handleOffer(senderUserId, sdpOffer));
                }
            }

            @Override
            public void onSdpAnswer(String channelId, String senderUserId, String sdpAnswer) {
                if (!channelId.equals(activeVoiceChannel != null ? activeVoiceChannel.id().toString() : "")) return;
                if (webRtcManager != null) {
                    webRtcManager.handleAnswer(senderUserId, sdpAnswer);
                } else {
                    pendingSignals.add(() -> webRtcManager.handleAnswer(senderUserId, sdpAnswer));
                }
            }

            @Override
            public void onIceCandidate(String channelId, String senderUserId, String candidate, String sdpMid, int sdpMLineIndex) {
                if (!channelId.equals(activeVoiceChannel != null ? activeVoiceChannel.id().toString() : "")) return;
                if (webRtcManager != null) {
                    webRtcManager.handleIceCandidate(senderUserId, candidate, sdpMid, sdpMLineIndex);
                } else {
                    pendingSignals.add(() -> webRtcManager.handleIceCandidate(senderUserId, candidate, sdpMid, sdpMLineIndex));
                }
            }

            @Override
            public void onVoiceJoined(String channelId, String userId, String username,
                                      String mediaSessionId, java.util.List<signal.WsEnvelope.ParticipantInfo> participants) {
                SwingUtilities.invokeLater(() -> {
                    if (voicePanel == null) return;
                    voicePanel.setParticipants(participants);

                    String myId = Session.get().getUserId().toString();
                    if (userId.equals(myId) && mediaSessionId != null && webRtcManager == null) {
                        webRtcManager = new WebRtcManager(signalingClient, channelId, myId, (uid, frame) -> {
                            SwingUtilities.invokeLater(() -> {
                                if (videoGridPanel == null) return;
                                if (!videoGridPanel.hasTile(uid)) {
                                    String label = uid.equals(myId)
                                            ? Session.get().getDisplayName() + " (Já)"
                                            : uid.substring(0, 8);
                                    videoGridPanel.addTile(uid, label);
                                    expandVideoArea();
                                }
                                videoGridPanel.updateFrame(uid, frame);
                            });
                        });

                        // Flush signals that arrived before webRtcManager was ready
                        Runnable r;
                        while ((r = pendingSignals.poll()) != null) r.run();

                        // Create peer connections to everyone already in the channel
                        for (signal.WsEnvelope.ParticipantInfo p : participants) {
                            if (!p.userId.equals(myId)) {
                                webRtcManager.createPeerConnection(p.userId, true);
                            }
                        }

                        // Start local mic monitor — feeds speaking indicator and audio level bar
                        final String selfId = myId;
                        audioMonitor = new LocalAudioMonitor(level -> {
                            boolean isSpeaking = level > 8;
                            SwingUtilities.invokeLater(() -> {
                                if (voicePanel    != null) {
                                    voicePanel.setAudioLevel(selfId, level);
                                    voicePanel.setSpeaking(selfId, isSpeaking);
                                }
                                if (videoGridPanel != null)
                                    videoGridPanel.setSpeaking(selfId, isSpeaking);
                            });
                        });
                        audioMonitor.start();
                    } else if (!userId.equals(myId) && webRtcManager != null) {
                        // Someone new joined — I initiate the connection to them
                        webRtcManager.createPeerConnection(userId, true);
                    }
                });
            }

            @Override
            public void onVoiceLeft(String channelId, String userId) {
                SwingUtilities.invokeLater(() -> {
                    if (voicePanel    != null) voicePanel.removeParticipant(userId);
                    if (videoGridPanel != null) {
                        videoGridPanel.removeTile(userId);
                        if (videoGridPanel.isEmpty()) collapseVideoArea();
                    }
                });
            }

            @Override
            public void onVoiceMuted(String channelId, String userId, boolean muted) {
                SwingUtilities.invokeLater(() -> {
                    if (voicePanel != null) voicePanel.setMuted(userId, muted);
                });
            }
        };
    }

    // ---- Helpers ----

    private JLabel makePlaceholder() {
        JLabel lbl = new JLabel(
                "<html><center>Vyber kanál pro zahájení chatu</center></html>",
                SwingConstants.CENTER);
        lbl.setForeground(new Color(185, 187, 190));
        lbl.setFont(new Font("Arial", Font.PLAIN, 16));
        return lbl;
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Chyba", JOptionPane.ERROR_MESSAGE);
    }
}
