import os

file_path = "src/TrashTalkMainPanel.java"
with open(file_path, "r", encoding="utf-8") as f:
    text = f.read()

# 1. Imports
text = text.replace("import voice.VideoClient;\nimport voice.VoiceClient;", "import voice.WebRtcManager;")

# 2. Fields
text = text.replace(
"""    // voice + video state
    private VoiceClient    voiceClient;
    private VideoClient    videoClient;""", 
"""    // voice + video state
    private WebRtcManager  webRtcManager;"""
)

# 3. doVoiceJoin
text = text.replace(
"""        voicePanel.setListener(new VoicePanel.VoicePanelListener() {
            @Override public void onMuteToggle(boolean muted) {
                if (voiceClient != null) voiceClient.setMuted(muted);
                signalingClient.sendVoiceMute(channel.id().toString(), muted);
            }""",
"""        voicePanel.setListener(new VoicePanel.VoicePanelListener() {
            @Override public void onMuteToggle(boolean muted) {
                // TODO: webrtcManager.setMuted(muted) if implemented
                signalingClient.sendVoiceMute(channel.id().toString(), muted);
            }"""
)

# 4. doVoiceLeave
text = text.replace(
"""        if (activeVoiceChannel != null) {
            signalingClient.sendVoiceLeave(activeVoiceChannel.id().toString());
            signalingClient.unsubscribe(activeVoiceChannel.id().toString());
            activeVoiceChannel = null;
        }
        if (voiceClient != null) { voiceClient.leave(); voiceClient = null; }
        if (videoClient != null) { videoClient.leave(); videoClient = null; }
        videoGridPanel = null;""",
"""        if (activeVoiceChannel != null) {
            signalingClient.sendVoiceLeave(activeVoiceChannel.id().toString());
            signalingClient.unsubscribe(activeVoiceChannel.id().toString());
            activeVoiceChannel = null;
        }
        if (webRtcManager != null) { webRtcManager.dispose(); webRtcManager = null; }
        videoGridPanel = null;"""
)

# 5. doToggleCamera
text = text.replace(
"""    private void doToggleCamera(boolean cameraOn) {
        if (videoClient == null) return;
        if (cameraOn) {
            videoClient.startCamera();
            if (!videoClient.isCameraOn()) {
                // startCamera failed — revert button
                if (voicePanel != null) voicePanel.setCameraOn(false);
                return;
            }
            String selfId   = Session.get().getUserId().toString();
            String selfName = Session.get().getDisplayName() + " (Já)";
            if (videoGridPanel != null && !videoGridPanel.hasTile(selfId)) {
                videoGridPanel.addTile(selfId, selfName);
            }
            expandVideoArea();
        } else {
            videoClient.stopCamera();
            String selfId = Session.get().getUserId().toString();
            if (videoGridPanel != null) {
                videoGridPanel.removeTile(selfId);
                if (videoGridPanel.isEmpty()) collapseVideoArea();
            }
        }
    }""",
"""    private void doToggleCamera(boolean cameraOn) {
        if (webRtcManager == null) return;
        if (cameraOn) {
            webRtcManager.startScreenShare();
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
    }"""
)

# 6. onVoiceJoined section in voiceSignalingListener()
text = text.replace(
"""                    if (userId.equals(myId) && mediaSessionId != null && voiceClient == null) {
                        // Start audio client
                        voiceClient = new VoiceClient(apiClient.getBaseUrl(), apiClient.isTrustAllCerts(),
                                new VoiceClient.VoiceListener() {
                                    @Override public void onSpeaking(String uid, boolean speaking) {
                                        SwingUtilities.invokeLater(() -> {
                                            if (voicePanel != null) voicePanel.setSpeaking(uid, speaking);
                                        });
                                    }
                                    @Override public void onError(String msg) {
                                        SwingUtilities.invokeLater(() -> showError("Voice: " + msg));
                                    }
                                });
                        voiceClient.join(mediaSessionId);

                        // Start video client (transport only — camera off until user clicks)
                        videoClient = new VideoClient(apiClient.getBaseUrl(), apiClient.isTrustAllCerts(),
                                new VideoClient.VideoListener() {
                                    @Override public void onFrame(String uid, java.awt.image.BufferedImage frame) {
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
                                    }
                                    @Override public void onError(String msg) {
                                        SwingUtilities.invokeLater(() -> {
                                            showError("Video: " + msg);
                                            // Revert camera button if camera failed to start
                                            if (voicePanel != null && videoClient != null && !videoClient.isCameraOn()) {
                                                voicePanel.setCameraOn(false);
                                            }
                                        });
                                    }
                                });
                        videoClient.join(mediaSessionId);
                    }""",
"""                    if (userId.equals(myId) && mediaSessionId != null && webRtcManager == null) {
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
                    } else if (!userId.equals(myId) && webRtcManager != null) {
                        webRtcManager.createPeerConnection(userId, true);
                    }"""
)

# 7. Add WebRTC Signaling events to the listener
text = text.replace(
"""            @Override public void onDisconnected() {}

            @Override
            public void onVoiceJoined(String channelId, String userId, String username,""",
"""            @Override public void onDisconnected() {}

            @Override
            public void onSdpOffer(String channelId, String senderUserId, String sdpOffer) {
                if (webRtcManager != null && channelId.equals(activeVoiceChannel != null ? activeVoiceChannel.id().toString() : "")) {
                    webRtcManager.handleOffer(senderUserId, sdpOffer);
                }
            }

            @Override
            public void onSdpAnswer(String channelId, String senderUserId, String sdpAnswer) {
                if (webRtcManager != null && channelId.equals(activeVoiceChannel != null ? activeVoiceChannel.id().toString() : "")) {
                    webRtcManager.handleAnswer(senderUserId, sdpAnswer);
                }
            }

            @Override
            public void onIceCandidate(String channelId, String senderUserId, String candidate, String sdpMid, int sdpMLineIndex) {
                if (webRtcManager != null && channelId.equals(activeVoiceChannel != null ? activeVoiceChannel.id().toString() : "")) {
                    webRtcManager.handleIceCandidate(senderUserId, candidate, sdpMid, sdpMLineIndex);
                }
            }

            @Override
            public void onVoiceJoined(String channelId, String userId, String username,"""
)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(text)
